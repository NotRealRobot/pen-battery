package com.playbuddy.penbattery;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 记录页：30 天概览条 + RecyclerView 明细 + 导出/清空（官方 MD3 对话框）。 */
public class HistoryFragment extends Fragment {

    static final int DAYS = 30;
    /** 明细警告底色的阈值：与 Samples 判本轮终点同一口径（都是 ≤10%） */
    private static final int LOW_WARN_PCT = 10;

    private ViewGroup boxTrend, boxDetail;
    private LineChart trend;
    private MaterialTextView runtime, runtimeHint, summary;
    private final Adapter adapter = new Adapter();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c,
                             @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_history, c, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        trend = v.findViewById(R.id.trend);
        runtime = v.findViewById(R.id.runtime);
        runtimeHint = v.findViewById(R.id.runtimeHint);
        summary = v.findViewById(R.id.summary);
        boxTrend = v.findViewById(R.id.boxTrend);
        boxDetail = v.findViewById(R.id.boxDetail);
        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        v.findViewById(R.id.btnExport).setOnClickListener(x -> ((MainActivity) requireActivity()).exportHistory());
        v.findViewById(R.id.btnClear).setOnClickListener(x -> confirmClear());
        refresh();
    }

    @Override public void onResume() { super.onResume(); refresh(); }

    /** 遮罩上的文案不是装饰品：点它就打开设备选择框，选完这一层立刻重画 */
    private Runnable pick() {
        return () -> ((MainActivity) requireActivity()).pickTarget(this::refresh);
    }

    public void refresh() {
        if (trend == null) return;
        android.content.Context c = requireContext();

        drawTrend(chartPoints(c));
        reloadCycle();

        int daysWithData = History.countDays(c);
        summary.setText(getString(R.string.overview_summary, DAYS, daysWithData)
                + NL + getString(R.string.overview_sampling_note));

        String[] dates = History.lastDays(DAYS);
        String today = dates[0];
        List<Row> rows = new ArrayList<>();
        for (String date : dates) {
            rows.add(new Row(date, History.get(c, date), date.equals(today)));
        }
        adapter.submit(rows);
    }

    private static final String NL = String.valueOf((char) 10);

    /**
     * 曲线数据：**一天只画一个点**，取当天的最后一个读数。
     * 优先用按天表（History），那天没有按天记录时退化为该日时间序列里的最后一个点
     * ⇒ 图、上方的运行时长、下方的明细三者同一口径。
     *
     * 曾经试过"同日多点摊成紧凑小圆点"，实际不成立：同值 10 分钟去重 + 应用只在短暂时段
     * 打开 ⇒ 一天往往只剩一两个点，密度还极不均匀，用户看过后要求改回一天一点。
     */
    private List<Samples.Point> chartPoints(android.content.Context c) {
        java.util.TreeMap<String, Samples.Point> byDay = new java.util.TreeMap<>();
        for (Samples.Point p : Samples.recent(c, DAYS)) {
            String day = dayOf(p.ts);
            Samples.Point kept = byDay.get(day);
            if (kept == null || p.ts > kept.ts) byDay.put(day, p);
        }
        for (String date : History.lastDays(DAYS)) {
            History.Day d = History.get(c, date);
            if (d == null) continue;
            long ts = d.tsMillis();
            if (ts > 0) byDay.put(date, new Samples.Point(ts, d.pct));
        }
        return new ArrayList<>(byDay.values());
    }

    private static final java.time.ZoneId ZONE = java.time.ZoneId.systemDefault();
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    private static String dayOf(long ms) {
        return java.time.Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate().format(DF);
    }

    private static long startOfDay(long ms) {
        // 注意：不要用 LocalDate.ofInstant(...) —— 它在 android.jar 里有声明，
        // 但这台 MIUI 14 / API 33 的运行时没有该方法，会 NoSuchMethodError 闪退（2.6.2 实机踩过）。
        return java.time.Instant.ofEpochMilli(ms).atZone(ZONE)
                .toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** 折线只连真实采到的点：断口如实留着，不插值、不补线（常驻已移除，采样仅在前台） */
    private void drawTrend(List<Samples.Point> pts) {
        trend.getDescription().setEnabled(false);
        trend.getLegend().setEnabled(false);
        trend.setDrawGridBackground(false);
        trend.setTouchEnabled(false);          // 关键：别抢外层 NestedScrollView 的竖向滑动
        trend.setDragEnabled(false);
        trend.setScaleEnabled(false);
        trend.setPinchZoom(false);
        trend.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        int primary = BatteryFragment.color(trend, R.attr.colorPrimary);
        int variant = BatteryFragment.color(trend, R.attr.colorOnSurfaceVariant);
        int outline = BatteryFragment.color(trend, R.attr.colorOutlineVariant);
        int card    = BatteryFragment.color(trend, R.attr.colorSurfaceContainerLow);

        YAxis left = trend.getAxisLeft();
        left.setEnabled(false);
        left.setAxisMinimum(0f);
        YAxis right = trend.getAxisRight();
        right.setEnabled(true);
        right.setAxisMinimum(0f);
        right.setAxisMaximum(100f);
        right.setLabelCount(5, true);
        right.setTextColor(variant);
        right.setGridColor(outline);
        right.enableGridDashedLine(6f, 8f, 0f);   // 官方 API 名：enableGridDashedLine(len, space, phase)
        right.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return ((int) v) + "%"; }
        });

        XAxis x = trend.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(variant);
        x.setGranularity(1f);                     // 横轴按天：标签只落在整点上

        if (pts.isEmpty()) {
            trend.clear();
            x.setValueFormatter(null);
            trend.invalidate();
            return;
        }

        // x 轴单位 = 距首日零点的天数（一天一个点 ⇒ 点落在当天最后一次读数的真实时刻上）
        final long day0 = startOfDay(pts.get(0).ts);
        final long lastTs = pts.get(pts.size() - 1).ts;
        List<Entry> es = new ArrayList<>();
        for (Samples.Point p : pts) es.add(new Entry((p.ts - day0) / 86_400_000f, p.pct));

        LineDataSet ds = new LineDataSet(es, null);
        ds.setMode(LineDataSet.Mode.LINEAR);
        ds.setColor(primary);
        ds.setLineWidth(2.2f);
        ds.setCircleColor(primary);
        ds.setCircleRadius(2.6f);
        ds.setCircleHoleRadius(1.1f);
        ds.setCircleHoleColor(card);
        ds.setDrawValues(false);
        ds.setDrawFilled(true);
        ds.setFillColor(primary);
        ds.setFillAlpha(38);
        trend.setData(new LineData(ds));

        x.setAxisMinimum(0f);
        x.setAxisMaximum(Math.max(1f, (lastTs - day0) / 86_400_000f));
        x.setLabelCount((int) Math.min(7f, Math.max(2f, (lastTs - day0) / 86_400_000f + 1)), false);
        x.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float days) {
                return DAY_LABEL.format(java.time.Instant.ofEpochMilli(
                        day0 + (long) (days * 86_400_000L)).atZone(ZONE).toLocalDate());
            }
        });
        trend.invalidate();
    }

    /**
     * 与系统时钟同步地刷新"本**轮**已运行"。
     *
     * 分工是刻意的：显示跟秒针走（纯算术，不查库），查库每分钟才回一次 ——
     * 因为 `Samples.cycle()` 是 SQLite 查询，按秒查它既没必要也白耗电。
     * 回库的那一次才可能带来新事实：又读到 100% ⇒ 开新一轮；读到 ≤10% ⇒ 本轮定格。
     *
     * 计时口径本身没动，也禁止动（项目约定）：时长 = 当前时刻 − 本轮起始时刻。
     */
    private final android.os.Handler tickHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private long cyStart, cyEnd;              // 本轮端点；cyEnd <= 0 ⇒ 还没结束
    private long lastDbMs;                    // 上次真正查库的时刻

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            android.content.Context c = getContext();
            if (c == null || !isResumed()) return;
            if (cyStart > 0 && cyEnd <= 0) renderRuntime();               // 跟着秒针走
            if (System.currentTimeMillis() - lastDbMs >= 60_000L) {
                reloadCycle();                                            // 内含 schedule()
                return;
            }
            schedule();
        }
    };

    /** 睡到下一个整秒（或整分钟）⇒ 显示与系统时钟对齐，不会越走越偏 */
    private void schedule() {
        boolean running = cyStart > 0 && cyEnd <= 0;
        long period = running ? 1_000L : 60_000L;
        long now = System.currentTimeMillis();
        tickHandler.removeCallbacks(ticker);
        tickHandler.postDelayed(ticker, period - (now % period));
    }

    /** 查库 → 刷新端点与显示 → 重新排班 */
    private void reloadCycle() {
        android.content.Context c = getContext();
        if (c == null) return;
        lastDbMs = System.currentTimeMillis();
        showRuntime(Samples.cycle(c, DAYS, History.lastFullTs(c)));
        applyMasks();          // 标签要按"本轮还在不在计时"定，所以放在拿到端点之后
        schedule();
    }

    /**
     * 概览卡的遮罩标签跟着本轮状态走：还在计时就不能写"已停止记录"——
     * 用户看到的数字明明在涨（10-14 指出这条自相矛盾）。
     */
    private void applyMasks() {
        if (boxTrend == null) return;
        if (!State.shouldMask()) {
            Disabled.clear(boxTrend); Disabled.clear(boxDetail);
            return;
        }
        boolean running = cyStart > 0 && cyEnd <= 0;
        Disabled.apply(boxTrend, getString(running ? R.string.disabled_level : R.string.disabled_trend),
                getString(R.string.link_switch_device), pick());
        Disabled.apply(boxDetail, getString(R.string.disabled_detail), null, null);
    }

    /** 周期 = 最近一次读到 100% 的时刻 → 其后第一次读到 ≤10% 的时刻；未到终点就显示"已运行"。记下端点再画 */
    private void showRuntime(Samples.Cycle cy) {
        cyStart = cy.startTs;
        cyEnd = cy.endTs;
        renderRuntime();
    }

    /** 只画，不查库：秒级刷新走的也是这里 */
    private void renderRuntime() {
        if (cyStart <= 0) {
            runtime.setText(R.string.runtime_none);
            runtimeHint.setText(R.string.runtime_hint_none);
            return;
        }
        boolean done = cyEnd > 0;
        long endMs = done ? cyEnd : System.currentTimeMillis();   // 没结束 ⇒ 就是"现在减起点"（项目约定）
        runtime.setText(getString(done ? R.string.runtime_done : R.string.runtime_running)
                + " " + humanDuration(endMs - cyStart));
        runtimeHint.setText(done
                ? getString(R.string.runtime_hint_done, TS_FMT.format(ts(cyStart)),
                                                TS_FMT.format(ts(cyEnd)))
                : getString(R.string.runtime_hint_running, TS_FMT.format(ts(cyStart))));
    }

    private static java.time.LocalDateTime ts(long ms) {
        return java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 毫秒 → "X 天 Y 小时 Z 分"（省略前导为零的单位） */
    /** 时长单位也走资源：中文「3 天 2 小时」，英文「3d 2h」 */
    private String humanDuration(long ms) {
        long mins = Math.max(0, ms / 60_000L);
        long d = mins / 1440, h = (mins % 1440) / 60, m = mins % 60;
        if (d > 0) return getString(R.string.dur_dhm, d, h, m);
        if (h > 0) return getString(R.string.dur_hm, h, m);
        return getString(R.string.dur_m, m);
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dlg_clear_title)
                .setMessage(R.string.dlg_clear_body)
                .setNegativeButton(R.string.dlg_cancel, null)
                .setPositiveButton(R.string.dlg_clear_ok, (d, w) -> {
                    History.clear(requireContext());
                    Samples.clear(requireContext());   // 两张表一起清，否则曲线与明细对不上
                    refresh();
                })
                .show();
    }

    // ---------------------------------------------------- 列表
    private static class Row {
        final String date;
        final History.Day rec;
        final boolean today;
        Row(String date, History.Day rec, boolean today) {
            this.date = date; this.rec = rec; this.today = today;
        }
    }

    private static class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<Row> data = new ArrayList<>();

        void submit(List<Row> rows) {
            data.clear();
            data.addAll(rows);
            notifyDataSetChanged();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_history, p, false);
            return new Holder(v);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int i) {
            Row r = data.get(i);
            Context2 c = new Context2(h.itemView);
            h.date.setText(r.date.substring(5) + "  " + weekday(h.itemView.getContext(), r.date));
            h.date.setTextColor(r.today ? c.color(R.attr.colorOnSurface)
                    : c.color(R.attr.colorOnSurfaceVariant));
            // 回收复用 ⇒ 两个分支都必须显式设，否则上一行的红底会跟着跑
            h.itemView.setBackgroundColor(r.rec != null && r.rec.pct <= LOW_WARN_PCT
                    ? ColorUtils.setAlphaComponent(c.color(R.attr.colorError), 40)
                    : android.graphics.Color.TRANSPARENT);
            if (r.rec == null) {
                h.level.setText("—");
                h.level.setTextColor(c.color(R.attr.colorOutline));
                h.last.setText(R.string.no_record);
                h.count.setText("");
            } else {
                h.level.setText(r.rec.pct + "%");
                h.level.setTextColor(r.rec.pct <= 20
                        ? c.color(R.attr.colorError)
                        : c.color(android.R.attr.textColorPrimary));
                h.last.setText(r.rec.time);
                h.count.setText(h.itemView.getContext().getString(R.string.count_times, r.rec.count));
            }
        }

        @Override public int getItemCount() { return data.size(); }

        static class Holder extends RecyclerView.ViewHolder {
            final MaterialTextView date, level, last, count;
            Holder(View v) {
                super(v);
                date = v.findViewById(R.id.colDate);
                level = v.findViewById(R.id.colLevel);
                last = v.findViewById(R.id.colLast);
                count = v.findViewById(R.id.colCount);
            }
        }
    }

    /** 取主题色的小工具（走官方 MaterialColors，正确处理主题引用） */
    private static class Context2 {
        private final View v;
        Context2(View v) { this.v = v; }
        int color(int attr) { return BatteryFragment.color(v, attr); }
    }

    /** 星期短名跟着语言走（自己给两套，不赌设备 CLD） */
    static String weekday(android.content.Context c, String iso) {
        try {
            String[] w = c.getResources().getStringArray(R.array.weekdays_short);
            return w[java.time.LocalDate.parse(iso).getDayOfWeek().getValue() - 1];
        } catch (Exception ex) { return ""; }
    }
}
