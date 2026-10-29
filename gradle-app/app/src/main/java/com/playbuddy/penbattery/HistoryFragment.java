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
    /** 已封存的周期（进页面时刷新一次），图上画分隔线、明细里生成分组都读它 */
    private List<Samples.Sealed> sealedNow = new ArrayList<>();
    /** 哪些分组被展开 —— 只活在本次视图生命周期里，用户要的是"默认封存"，不是持久化的展开状态 */
    private final java.util.Set<Integer> open = new java.util.HashSet<>();

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
        adapter.toggle = cy -> {
            if (!open.remove(cy.idx)) open.add(cy.idx);
            adapter.submit(buildRows(requireContext()));
        };

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

        // 先封存再画：图上的分隔线、明细里的分组标题、以及「第 n 周期」的编号都读同一份封存表
        Samples.sealCompleted(c);
        reloadCycle();
        drawTrend(chartPoints(c));

        int daysWithData = History.countDays(c);
        summary.setText(getString(R.string.overview_summary, DAYS, daysWithData)
                + NL + getString(R.string.overview_sampling_note));

        adapter.submit(buildRows(c));
    }

    /**
     * 明细 = 最后一道边界之后的按天行（新的在上）+ 已封存的周期（默认收起）。
     *
     * 顺序自下而上由旧到新（用户 10-27 指出）：表头跟日期走同一条轴，所以「第 1 周期」
     * 落在最底；多轮时越新的表头越靠上。展开的逐日行放在表头**下方**（用户 10-27 要求
     * 向下展开），块内仍是终点日在上、起点日在下 ⇒ 整表依旧"越往下越旧"。
     *
     * 一天可以属于两轮：周期在某天中途收尾 ⇒ 那天既进上一组的尾巴，也在下面**另起一行**
     * 记新周期从那一刻起的读数（用户 10-22 指出的第 2 点）。
     */
    private List<Row> buildRows(android.content.Context c) {
        List<Row> rows = new ArrayList<>();
        long boundary = -1;
        for (Samples.Sealed s : sealedNow) boundary = Math.max(boundary, s.endTs);

        String from = boundary > 0 ? dayOf(boundary) : null;
        String today = History.lastDays(1)[0];
        for (String date : History.lastDays(DAYS)) {
            if (from != null && date.compareTo(from) < 0) continue;   // 更早的天已归入下面的分组
            History.Day rec = History.get(c, date);
            if (from != null && date.equals(from) && rec != null && rec.tsMillis() <= boundary) {
                rec = null;   // 那天的最后一次读数属于上一轮 ⇒ 新周期在这天还没有自己的读数
            }
            rows.add(Row.day(date, rec, date.equals(today)));
        }

        // 已封存的周期倒序铺：最新的先、第 1 周期最后（== 最底）
        for (int i = sealedNow.size() - 1; i >= 0; i--) {
            Samples.Sealed s = sealedNow.get(i);
            boolean opened = open.contains(s.idx);
            rows.add(Row.head(s, opened));
            if (opened) rows.addAll(groupDays(c, s));
        }
        return rows;
    }

    /**
     * 一个封存周期名下的按天行：当天的收尾值取「周期窗口内最后一个点」，不是当天全局最后一次。
     * 从终点日往回铺，接在表头下方 ⇒ 与整表同向：越往下越旧。
     */
    private List<Row> groupDays(android.content.Context c, Samples.Sealed s) {
        List<Row> out = new ArrayList<>();
        java.time.LocalDate d = day(s.endTs), first = day(s.startTs);
        String today = History.lastDays(1)[0];
        while (!d.isBefore(first)) {
            String date = d.format(DF);
            long ds = startOfDay(d.atStartOfDay(ZONE).toInstant().toEpochMilli());
            long from = Math.max(ds, s.startTs), to = Math.min(ds + 86_399_999L, s.endTs);
            Samples.Point p = Samples.lastBetween(c, from, to);
            int cnt = Samples.countBetween(c, from, to);
            if (p == null) {
                // 采样按条数截断，老周期的点可能已经掉了 ⇒ 退回到按天表，只要那条记录落在本窗口内
                History.Day h = History.get(c, date);
                if (h != null && h.tsMillis() >= from && h.tsMillis() <= to) {
                    p = new Samples.Point(h.tsMillis(), h.pct);
                    cnt = h.count;
                }
            }
            out.add(Row.day(date, p == null ? null : new History.Day(date, p.pct, TIME.format(ts(p.ts)), cnt),
                    date.equals(today)));
            d = d.minusDays(1);
        }
        return out;
    }

    private static java.time.LocalDate day(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate();
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
    /**
     * 封存行的时间精确到秒：周期终点那天往往既有旧周期的 4% 又有充回后的 100%，
     * 只显示到分钟分不清"这一行到底是哪一笔"（用户 10-26 的实际数据）。
     * 展开区的按天行本来就带秒（按天表存的就是 HH:mm:ss），两边口径一致。
     */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

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
            x.removeAllLimitLines();      // 没数据也要把上一轮的边界线清掉，否则空图上会留着竖线
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

        // 完整周期的两端各画一条竖线：起点 = 读到 100% 那刻，终点 = 其后第一次 ≤10% 那刻。
        // 进行中的那一轮只画起点 —— 终点还不存在，画了就是撒谎。
        // （这个开关在 AxisBase 上，不在 Chart 上 —— 3.1.0 实测）
        x.setDrawLimitLinesBehindData(true);
        x.removeAllLimitLines();
        for (Samples.Sealed s : sealedNow) {
            addBoundary(x, s.startTs, day0, primary);
            addBoundary(x, s.endTs, day0, primary);
        }
        if (cyStart > 0) addBoundary(x, cyStart, day0, primary);

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

    /** 一条周期边界竖线；时刻落在可视窗口左边之外就不画（老周期的端点可能已在 30 天窗口外） */
    private static void addBoundary(XAxis axis, long ms, long day0, int primary) {
        float xp = (ms - day0) / 86_400_000f;
        if (xp < 0f) return;
        com.github.mikephil.charting.components.LimitLine l =
                new com.github.mikephil.charting.components.LimitLine(xp);
        l.setLineColor(ColorUtils.setAlphaComponent(primary, 150));
        l.setLineWidth(1.6f);
        l.enableDashedLine(10f, 6f, 0f);
        axis.addLimitLine(l);
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
        sealedNow = Samples.sealed(c);
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
    /** 一行要么是「第 n 周期」的分组标题，要么是某天在该周期里的读数 */
    private static class Row {
        final Samples.Sealed head;
        final String date;
        final History.Day rec;
        final boolean today;
        final boolean opened;
        private Row(Samples.Sealed head, String date, History.Day rec, boolean today, boolean opened) {
            this.head = head; this.date = date; this.rec = rec; this.today = today; this.opened = opened;
        }
        static Row head(Samples.Sealed s, boolean opened) { return new Row(s, null, null, false, opened); }
        static Row day(String date, History.Day rec, boolean today) { return new Row(null, date, rec, today, false); }
    }

    private static class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEAD = 0, TYPE_DAY = 1;
        private final List<Row> data = new ArrayList<>();
        java.util.function.Consumer<Samples.Sealed> toggle;

        void submit(List<Row> rows) {
            data.clear();
            data.addAll(rows);
            notifyDataSetChanged();
        }

        @Override public int getItemViewType(int position) { return data.get(position).head != null ? TYPE_HEAD : TYPE_DAY; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LayoutInflater inf = LayoutInflater.from(p.getContext());
            return t == TYPE_HEAD ? new HeadHolder(inf.inflate(R.layout.item_cycle_header, p, false))
                                  : new Holder(inf.inflate(R.layout.item_history, p, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int i) {
            Row r = data.get(i);
            if (h instanceof HeadHolder) {
                HeadHolder hh = (HeadHolder) h;
                android.content.Context ctx = hh.itemView.getContext();
                hh.title.setText(ctx.getString(R.string.cycle_sealed, r.head.idx));
                hh.range.setText(ctx.getString(R.string.cycle_range,
                        TS_FMT.format(ts(r.head.startTs)), TS_FMT.format(ts(r.head.endTs))));
                hh.caret.setText(ctx.getString(r.opened ? R.string.caret_expanded : R.string.caret_collapsed));
                hh.itemView.setContentDescription(ctx.getString(R.string.cycle_toggle));
                hh.itemView.setOnClickListener(v -> { if (toggle != null) toggle.accept(r.head); });
                return;
            }
            Holder d = (Holder) h;
            Context2 c = new Context2(d.itemView);
            d.date.setText(r.date.substring(5) + "  " + weekday(d.itemView.getContext(), r.date));
            d.date.setTextColor(r.today ? c.color(R.attr.colorOnSurface)
                    : c.color(R.attr.colorOnSurfaceVariant));
            // 回收复用 ⇒ 两个分支都必须显式设，否则上一行的红底会跟着跑
            d.itemView.setBackgroundColor(r.rec != null && r.rec.pct <= LOW_WARN_PCT
                    ? ColorUtils.setAlphaComponent(c.color(R.attr.colorError), 40)
                    : android.graphics.Color.TRANSPARENT);
            if (r.rec == null) {
                d.level.setText("—");
                d.level.setTextColor(c.color(R.attr.colorOutline));
                d.last.setText(R.string.no_record);
                d.count.setText("");
            } else {
                d.level.setText(r.rec.pct + "%");
                d.level.setTextColor(r.rec.pct <= 20
                        ? c.color(R.attr.colorError)
                        : c.color(android.R.attr.textColorPrimary));
                d.last.setText(r.rec.time);
                d.count.setText(d.itemView.getContext().getString(R.string.count_times, r.rec.count));
            }
        }

        @Override public int getItemCount() { return data.size(); }

        static class HeadHolder extends RecyclerView.ViewHolder {
            final MaterialTextView caret, title, range;
            HeadHolder(View v) {
                super(v);
                caret = v.findViewById(R.id.cycleCaret);
                title = v.findViewById(R.id.cycleTitle);
                range = v.findViewById(R.id.cycleRange);
            }
        }

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
