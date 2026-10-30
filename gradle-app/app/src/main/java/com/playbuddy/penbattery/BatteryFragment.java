package com.playbuddy.penbattery;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import androidx.core.graphics.ColorUtils;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textview.MaterialTextView;

/** 电量页：订阅 State 渲染，控件全部是官方 MD3 组件。 */
public class BatteryFragment extends Fragment implements State.Listener {

    private MaterialTextView penName, level, status, updated, today, days;
    private LinearProgressIndicator progress;
    private LinearProgressIndicator stripes;   // 官方 indeterminate 占位条（取代自绘斜纹）
    private Chip chipSub;
    private MaterialCardView cardLevel;
    private SwipeRefreshLayout refresh;
    private View scroll;
    private android.graphics.drawable.ColorDrawable scrim;
    private int lastTintPct = -2;
    private String refreshBaseline;
    private final Runnable stopRefresh = new Runnable() {
        @Override public void run() { if (refresh != null) refresh.setRefreshing(false); setScrim(false); }
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c,
                             @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_battery, c, false);
    }

    private MaterialCardView boxStats;
    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        penName = v.findViewById(R.id.penName);
        level = v.findViewById(R.id.level);
        progress = v.findViewById(R.id.progress);
        stripes = v.findViewById(R.id.stripes);
        // 动画开不开只由 render() 决定；在这里无条件 true 会让"目标无效"时也先转起来
        status = v.findViewById(R.id.status);
        updated = v.findViewById(R.id.updated);
        today = v.findViewById(R.id.today);
        days = v.findViewById(R.id.days);
        chipSub = v.findViewById(R.id.chipSub);
        cardLevel = v.findViewById(R.id.cardLevel);

        // 下拉刷新：视图每次重建都要重来一遍，否则渐变缓存会停在旧卡片上
        boxStats = v.findViewById(R.id.boxStats);
        lastTintPct = -2;
        refresh = v.findViewById(R.id.refresh);
        refresh.setColorSchemeColors(color(refresh, R.attr.colorPrimary));
        scroll = v.findViewById(R.id.scroll);
        scrim = new android.graphics.drawable.ColorDrawable(
                androidx.core.content.ContextCompat.getColor(v.getContext(), R.color.refresh_scrim));
        scrim.setAlpha(0);
        scroll.setForeground(scrim);        // 蒙版挂在滚动视图的 foreground：不参与滚动、不加层级

        refresh.setOnRefreshListener(() -> {
            refreshBaseline = State.lastTime;
            setScrim(true);
            host().refreshNow();          // 内部会重判权限/蓝牙/目标
            refresh.removeCallbacks(stopRefresh);
            if (!State.supported) stopRefresh.run();     // 没东西可刷 ⇒ 立刻收圈，不演 12 秒
            else refresh.postDelayed(stopRefresh, 12_000L);   // 兜底：连不上也别让转圈不停
        });

        render();
    }

    @Override public void onResume() {
        super.onResume();
        State.add(this);
        render();
    }

    @Override public void onStop() { State.remove(this); super.onStop(); }

    @Override public void onStateChanged() { render(); }

    private MainActivity host() { return (MainActivity) requireActivity(); }

    /**
     * 两种"读不到但不算设备无效"的情况（蓝牙没开 / 还没定下设备）不盖遮罩，
     * 而是在状态行给一个带下划线的主色词，点它直接去解决。
     */
    private void renderStatus() {
        String base = statusText();
        if (!State.btOn) {
            Links.bind(status, base, getString(R.string.link_bt_on),
                    Links.INLINE, this::requestBtOn);
        } else if (PenTarget.address(requireContext()) == null) {
            Links.bind(status, base, getString(R.string.link_switch_device),
                    Links.INLINE, pick());
        } else {
            Links.plain(status, base);
        }
    }

    /** 「点我打开蓝牙」：统一走 Activity 里的系统弹窗入口 */
    private void requestBtOn() { host().askEnableBluetooth(); }
    /** 遮罩/状态行里的可点词：打开设备选择框，选完这一层立刻重画 */
    private Runnable pick() {
        return () -> host().pickTarget(this::render);
    }

    private void render() {
        Context c = getContext();
        if (c == null || level == null) return;
        // 遮罩只在"蓝牙开着 + 目标不是这支笔"时出现；数据一律保留，只是不再往里写
        if (State.shouldMask()) {
            Disabled.apply(cardLevel, getString(R.string.disabled_level),
                    getString(R.string.link_switch_device), pick());
            Disabled.apply(boxStats, getString(R.string.disabled_stats), null, null);
        } else {
            Disabled.clear(cardLevel); Disabled.clear(boxStats);
        }

        penName.setText(State.connected
                ? R.string.pen_connected : R.string.pen_disconnected);

        if (State.pct < 0) {
            level.setText("--");
            level.setTextColor(color(level, android.R.attr.textColorPrimary));
        } else {
            level.setText(State.pct + "%");
            level.setTextColor(State.pct <= 20
                    ? color(level, R.attr.colorError)
                    : color(level, android.R.attr.textColorPrimary));
        }
        // 订阅成功 ⇒ 实心进度条；未连接/订阅失败 ⇒ 官方 indeterminate 占位条（两种状态区分）
        boolean live = State.levelSub;
        progress.setVisibility(live ? View.VISIBLE : View.GONE);
        // 斜纹条的意思是"正在加载"。目标无效时并没有在加载任何东西 ⇒ 必须关掉动画本身，
        // 光隐藏不够（indeterminate 的补间还在跑），遮罩下转圈既骗人也费电
        if (!live && !State.shouldMask()) {
            stripes.setVisibility(View.VISIBLE);
            stripes.setIndeterminate(true);
        } else {
            stripes.setIndeterminate(false);
            stripes.setVisibility(View.GONE);
        }
        if (live) {
            progress.setProgress(State.pct < 0 ? 0 : State.pct, true);   // 官方进度条自带补间动画
        }

        renderStatus();
        updated.setText(State.lastTime.isEmpty() ? ""
                : getString(R.string.last_update) + " " + State.lastTime
                  + (State.source.isEmpty() ? "" : " · " + State.source));

        updateSubChip(State.levelSub);

        tintCard();
        if (refresh != null && refresh.isRefreshing()
                && !java.util.Objects.equals(refreshBaseline, State.lastTime)) {
            refresh.removeCallbacks(stopRefresh);
            refresh.setRefreshing(false);
            setScrim(false);
            refreshBaseline = null;
        }

        History.Day d = History.get(c, History.lastDays(1)[0]);
        today.setText(d == null ? "—" : getString(R.string.count_times, d.count));
        days.setText(getString(R.string.count_days, History.countDays(c)));
    }

    /**
     * 底板随电量渐变：5 档标定（0 / 15 / 35 / 60 / 100）分段线性插值。
     * 高电量用低饱和橄榄绿，接近见底才逐步走向鲜艳的红 —— 警示色晚到才有效。
     * 叠色浓度随电量下降而加深（8% → 22%），只在数值变化时重设。
     */
    private void tintCard() {
        if (cardLevel == null) return;
        int pct = State.pct;
        if (pct == lastTintPct) return;
        lastTintPct = pct;

        int base = color(cardLevel, R.attr.colorSurfaceContainerLow);
        if (pct < 0) { cardLevel.setCardBackgroundColor(base); return; }

        android.content.Context cc = cardLevel.getContext();
        int[] stops = {0, 15, 35, 60, 100};
        int[] cols = {
                androidx.core.content.ContextCompat.getColor(cc, R.color.battery_crit),
                androidx.core.content.ContextCompat.getColor(cc, R.color.battery_low),
                androidx.core.content.ContextCompat.getColor(cc, R.color.battery_fair),
                androidx.core.content.ContextCompat.getColor(cc, R.color.battery_ok),
                androidx.core.content.ContextCompat.getColor(cc, R.color.battery_full) };
        int clamp = Math.min(100, pct);
        int i = 0;
        while (i < stops.length - 2 && clamp > stops[i + 1]) i++;
        float t = (clamp - stops[i]) / (float) (stops[i + 1] - stops[i]);
        int tint = ColorUtils.blendARGB(cols[i], cols[i + 1], Math.max(0f, Math.min(1f, t)));
        float alpha = 0.08f + 0.14f * (1 - clamp / 100f);
        cardLevel.setCardBackgroundColor(ColorUtils.blendARGB(base, tint, alpha));
    }

    /** 刷新中给整页盖一层半透明黑蒙版（140 ms 淡入淡出） */
    private void setScrim(boolean on) {
        if (scrim == null) return;
        android.animation.ObjectAnimator oa = android.animation.ObjectAnimator
                .ofInt(scrim, "alpha", on ? 255 : 0).setDuration(140);
        if (!on) oa.setStartDelay(90);      // 先让转圈收起，再淡出蒙版
        oa.start();
    }

    /**
     * 订阅状态圆点：实心主题色 = 已订阅；空心描边 = 未订阅。
     * 挂在 Chip 的 leading icon 位；Chip 本身 checkable=false，所以点它不会换图标（见布局注释）。
     */
    private void updateSubChip(boolean subscribed) {
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (subscribed) {
            dot.setColor(color(chipSub, R.attr.colorPrimary));
        } else {
            dot.setColor(android.graphics.Color.TRANSPARENT);
            dot.setStroke(Math.max(1, dp(chipSub, 1.5f)), color(chipSub, R.attr.colorOutline));
        }
        dot.setSize(24, 24);
        chipSub.setChipIconVisible(true);
        chipSub.setChipIconTint(null);          // 不让官方 tint 改掉圆点颜色
        chipSub.setChipIconSize(dp(chipSub, 8f));
        chipSub.setChipIcon(dot);
    }

    private String statusText() {
        String s = State.status;
        if (!State.connected) {
            if (s == null || s.isEmpty() || s.equals(getString(R.string.status_idle))) {
                return getString(R.string.status_not_connected);
            }
            if (State.statusRes == R.string.gatt_disconnected) return getString(R.string.status_sleeping);
        }
        return s;
    }

    /**
     * 取主题语义色。
     * 🔴 之前的实现直接把 resolveAttribute 出来的 tv.data 当 ARGB 用 —— 但
     *    textColorPrimary 这类属性解析出来是【资源引用】，data 其实是资源 id，
     *    当颜色写入后 alpha 字节是 0x00/垃圾值 ⇒ 数字被画成透明（"电量数值不显示"的真因）。
     *    改用官方 MaterialColors.getColor(View, attr)：内部正确处理 ColorStateList 与资源引用。
     */
    static int color(View v, int attr) {
        return MaterialColors.getColor(v, attr);
    }

    static int dp(View v, float d) {
        return Math.round(d * v.getResources().getDisplayMetrics().density);
    }
}
