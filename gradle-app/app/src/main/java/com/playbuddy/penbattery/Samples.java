package com.playbuddy.penbattery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 电量时间序列（曲线图与"运行时长"的数据源）。
 *
 * 与 {@link History} 的分工：History 是"每天最后一次读数"的按天表（明细列表与导出用它）；
 * 本表存每一次带时间戳的读数，是画曲线必需的 —— 按天那份还原不出形状。
 *
 * 采样只在应用处于前台时发生（常驻已移除），所以序列天然有断口。图上如实连点，不插值。
 */
public final class Samples {

    public static final class Point {
        public final long ts;      // 毫秒
        public final int pct;
        Point(long ts, int pct) { this.ts = ts; this.pct = pct; }
    }

    /**
     * 一个放电周期：startTs = 最近一次读到 100% 的时刻，或用户手动开始新一轮的时刻；
     * endTs = 其后第一次读到 ≤10%、读到 100%（充满即另起一轮）或手动收尾的时刻（未到则 -1）
     */
    public static final class Cycle {
        public final long startTs;
        public final long endTs;
        /** 终点是用户手动收尾，而不是读到 ≤10% */
        public final boolean manual;
        Cycle(long startTs, long endTs, boolean manual) {
            this.startTs = startTs; this.endTs = endTs; this.manual = manual;
        }
    }

    /** 一个已封存（走完）的周期：idx 是它在库里的序号，也就是界面上的「第 n 周期」。 */
    public static final class Sealed {
        public final int idx;
        public final long startTs;
        public final long endTs;
        public final boolean manual;
        Sealed(int idx, long startTs, long endTs, boolean manual) {
            this.idx = idx; this.startTs = startTs; this.endTs = endTs; this.manual = manual;
        }
    }

    private static final int FULL_PCT = 100;
    private static final int LOW_PCT = 10;
    /** 同一个值在这么短的时间内不重复入库（60 s 轮询 ⇒ 满电挂着不动时会刷屏） */
    private static final long DEDUP_MS = 10 * 60_000L;
    /** 只保留最近这么多个点，防止长期无限增长 */
    private static final int MAX_POINTS = 20_000;

    private static final class Db extends SQLiteOpenHelper {
        Db(Context c) { super(c, "pen_battery_samples.db", null, 3); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE samples (ts INTEGER PRIMARY KEY, pct INTEGER NOT NULL)");
            createCycles(db);
        }
        /**
         * v1 → v2 只加 cycles 表；v2 → v3 给 cycles 补 manual 列（老库里封存的都是读到 ≤10% 的周期）。
         * **不能沿用旧的 DROP + onCreate**：那会把用户跑出来的
         * 时间序列整表清掉，而周期封存的起点恰恰只存在于这份序列里。
         */
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                createCycles(db);          // 新建的表已经带 manual 列
            } else if (oldVersion < 3) {
                db.execSQL("ALTER TABLE cycles ADD COLUMN manual INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    /**
     * 封存的周期表。为什么要落库而不每次从 samples 现推：
     *   ① samples 按条数截断（MAX_POINTS），老周期的端点会消失，「第 n 周期」就不能再靠现推得到；
     *   ② 序号要稳定 —— 现推的话最早一轮一旦掉出窗口，后面每一轮的编号都会往前挪。
     * start_ts 唯一 ⇒ 回填可以反复跑而不会插重复。
     *
     * 手动开始的新一轮也落在这里，用 **end_ts = 0** 表示还没收尾（已封存的行一律 end_ts &gt; 0）。
     * 这样不用加列、不用升库版本，序号（idx）也在开始那一刻就定下来。
     */
    private static void createCycles(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cycles ("
                + "idx INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "start_ts INTEGER UNIQUE NOT NULL, "
                + "end_ts INTEGER NOT NULL, "
                + "manual INTEGER NOT NULL DEFAULT 0)");
    }

    private static Db db(Context c) { return new Db(c.getApplicationContext()); }

    /** 记一个点：与上一个点同值且间隔很短时跳过 */
    public static void record(Context c, int pct) {
        try (Db d = db(c); SQLiteDatabase w = d.getWritableDatabase()) {
            Long lastTs = null; Integer lastPct = null;
            try (Cursor cur = w.rawQuery(
                    "SELECT ts, pct FROM samples ORDER BY ts DESC LIMIT 1", null)) {
                if (cur.moveToFirst()) { lastTs = cur.getLong(0); lastPct = cur.getInt(1); }
            }
            long now = System.currentTimeMillis();
            if (lastPct != null && lastPct == pct && lastTs != null && now - lastTs < DEDUP_MS) return;

            ContentValues cv = new ContentValues();
            cv.put("ts", now);
            cv.put("pct", pct);
            w.insert("samples", null, cv);

            // 读回 100% ⇒ 充满就是新周期的起点：手动开始的那一轮到此收尾（终点 = 这次满电的时刻）
            if (pct >= FULL_PCT) closeOpen(w, now);

            // 读到 ≤10% ⇒ 这一轮已经在终点上：当场归档，别等下次打开记录页。
            // 拖到那时再算，终点还可能被按天表当天的"充回 100%"顶掉（用户 10-26 的实际数据就是这种日子）。
            if (pct <= LOW_PCT) sealInto(c, w);

            // 截断过老的数据（按条数，不按时间：前台采样密度不均）。放在归档之后：
            // 刚走完那轮的起点可能就是最老的那批点，先封存掉才敢删。
            w.execSQL("DELETE FROM samples WHERE ts < "
                    + "(SELECT MIN(ts) FROM (SELECT ts FROM samples ORDER BY ts DESC LIMIT ?))",
                    new Object[]{MAX_POINTS});
        }
    }

    /** 最近 days 天内的全部点，按时间升序 */
    public static List<Point> recent(Context c, int days) {
        long from = System.currentTimeMillis() - days * 86_400_000L;
        List<Point> out = new ArrayList<>();
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase();
             Cursor cur = r.rawQuery("SELECT ts, pct FROM samples WHERE ts >= ? ORDER BY ts ASC",
                     new String[]{String.valueOf(from)})) {
            while (cur.moveToNext()) out.add(new Point(cur.getLong(0), cur.getInt(1)));
        }
        return out;
    }

    /**
     * 当前周期：起点 = 最近一次读到 100% 的时刻；终点 = 起点之后第一次读到 ≤10% 的时刻。
     * 充回 100% 会自动产生新起点 ⇒ 无需额外记录"充电事件"。
     *
     * @param seedStartMs 起点种子：本表是 2.6.0 才建的，之前的满电只存在按天表里
     *                    （{@link History#lastFullTs}），不传进来的话周期就会从升级那刻重新开始。
     */
    public static Cycle cycle(Context c, int days, long seedStartMs) {
        long start = seedStartMs > 0 ? seedStartMs : -1, end = -1;
        for (Point p : recent(c, days)) {
            if (p.pct >= FULL_PCT) { start = p.ts; end = -1; }      // 新的 100% ⇒ 开新一轮
            else if (start > 0 && end < 0 && p.pct <= LOW_PCT) end = p.ts;
        }
        // 手动开始 / 手动结束的一轮可能比样本里最后一次 100% 更晚：取起点最新的那一轮当「本轮 / 上一轮」。
        // 否则手动那轮封存后，概览卡会退回去显示更早的自动轮。
        long sealedStart = -1, sealedEnd = -1, lastEnd = -1;
        boolean sealedManual = false;
        for (Sealed s : sealed(c)) {
            if (s.endTs > lastEnd) lastEnd = s.endTs;
            if (s.startTs > sealedStart) {
                sealedStart = s.startTs;
                sealedEnd = s.endTs;
                sealedManual = s.manual;
            }
        }
        long open = openStart(c);
        if (sealedStart > start) start = sealedStart;
        if (open > start) start = open;
        boolean manual = false;
        if (start > 0 && start == sealedStart) {
            // 封存表优先：同一轮已经封过就以封存终点为准（手动收尾后不会再被后来的 ≤10% 改写）
            end = sealedEnd;
            manual = sealedManual;
        } else if (start > 0 && start == open) {
            end = -1;                       // 手动开始、还在计时
        }
        // 起点已被更晚的封存盖住 ⇒ 这一轮不在了。
        // 用严格小于：手动开始的一轮若在 100% 那一刻收尾，上一轮的终点与本轮起点相等，不能把本轮判掉。
        if (end < 0 && start > 0 && start < lastEnd) start = -1;
        return new Cycle(start, end, manual);
    }

    /**
     * 手动结束当前周期：把「最近一次 100% → 现在」封存成已完成的周期（manual = 1）。
     * 用于电量还没到 10% 就要去充电的情况；下一次读到 100% 会自然开新一轮。
     * 返回 true 表示新写入了一笔；没有进行中的周期、或同一轮已经封过时返回 false。
     */
    public static boolean endNow(Context c, int days) {
        Cycle cy = cycle(c, days, History.lastFullTs(c));
        if (cy.startTs <= 0 || cy.endTs > 0) return false;
        try (Db d = db(c); SQLiteDatabase w = d.getWritableDatabase()) {
            // 手动开始的那一轮在表里已有一行（end_ts = 0）⇒ 收尾是更新它，不是再插一行
            return sealRow(w, cy.startTs, System.currentTimeMillis(), true);
        }
    }

    /**
     * 手动开始新一轮：上一轮结束后，笔只充了一部分就拿来用，不必等下一次读到 100%。
     * 起点 = 现在；之后照常按「≤10%、读到 100% 或手动结束」收尾。
     * 返回 true 表示真的开了一轮；已有本轮在计时时返回 false。
     */
    public static boolean startNow(Context c, int days) {
        Cycle cy = cycle(c, days, History.lastFullTs(c));
        if (cy.startTs > 0 && cy.endTs <= 0) return false;
        try (Db d = db(c); SQLiteDatabase w = d.getWritableDatabase()) {
            ContentValues cv = new ContentValues();
            cv.put("start_ts", System.currentTimeMillis());
            cv.put("end_ts", 0);                 // 开放行：还没收尾
            // start_ts 唯一 ⇒ 连点两下只落一行
            return w.insertWithOnConflict("cycles", null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0;
        }
    }

    /**
     * 把时间序列里已经走完的周期回填进 cycles 表（幂等，可每次进页面都跑）。
     * 判定口径与 {@link #cycle} 完全一致：读到 100% 开新一轮，其后第一次 ≤10% 收尾。
     *
     * 起点补法：序列里没看见 100% 时（本表 2.6.0 才建，更早那一轮的满电只存在按天表里），
     * 退到 {@link History#lastFullBefore} 取终点之前最近的一次满电日。两处都没有，就不封 ——
     * 宁可这一轮先不收起来，也不编一个没观测到的起点。
     */
    public static int sealCompleted(Context c) {
        try (Db d = db(c); SQLiteDatabase w = d.getWritableDatabase()) {
            return sealInto(c, w);
        }
    }

    private static int sealInto(Context c, SQLiteDatabase w) {
        int added = 0;
        long start = -1;
        // 已经封到哪（含以前几次运行封的）：补出来的起点必须晚于它，
        // 否则低电量持续那几天会拿一个更老的满电日反复去凑"新周期"
        long lastEnd = -1;
        try (Cursor cur = w.rawQuery("SELECT MAX(end_ts) FROM cycles", null)) {
            if (cur.moveToFirst() && !cur.isNull(0)) lastEnd = cur.getLong(0);
        }
        // 手动开始、还没收尾的那一轮：samples 里没有它的 100% 起点，起点只能从表里拿
        long open = openStart(w);
        try (Cursor cur = w.rawQuery("SELECT ts, pct FROM samples ORDER BY ts ASC", null)) {
            while (cur.moveToNext()) {
                long ts = cur.getLong(0);
                int pct = cur.getInt(1);
                if (pct >= FULL_PCT) {                                     // 新的满电 ⇒ 开新一轮
                    if (open > 0 && open < ts) {                           // 手动那轮到此刻收尾
                        if (sealRow(w, open, ts, false)) added++;
                        open = -1;
                        lastEnd = ts;
                    }
                    start = ts;
                } else if (pct <= LOW_PCT) {
                    if (open > 0 && open < ts && start <= 0) {
                        if (sealRow(w, open, ts, false)) {
                            added++;
                            lastEnd = ts;
                            android.util.Log.i("Samples", "封存手动周期 " + open + " → " + ts);
                        }
                        open = -1;
                        start = -1;
                        continue;
                    }
                    long s = start > 0 ? start : History.lastFullBefore(c, ts);
                    if (s <= 0) {
                        android.util.Log.i("Samples", "低电量 " + pct + "% 在 " + ts
                                + " 找不到起点：序列与按天表里都没有更早的满电");
                    } else if (start > 0 || s > lastEnd) {
                        // 同一个起点只会封一次；夹在中间的重复低电量读数不会插第二条
                        if (sealRow(w, s, ts, false)) {
                            added++;
                            lastEnd = ts;
                            // 留一行证据：装机后若明细里仍没有分组，先看这条日志有没有打出来
                            android.util.Log.i("Samples", "封存周期 " + s + " → " + ts
                                    + (start > 0 ? "（起点来自序列）" : "（起点来自按天表）"));
                        }
                    }
                    start = -1;
                }
            }
        }
        return added;
    }

    /**
     * 封一轮：表里已有开放行（同一个起点）就更新它，否则插一行。
     * 返回 true 表示这次真的封了一轮（重复调用不会重复计数）。
     */
    private static boolean sealRow(SQLiteDatabase w, long startTs, long endTs, boolean manual) {
        ContentValues cv = new ContentValues();
        cv.put("end_ts", endTs);
        cv.put("manual", manual ? 1 : 0);
        int n = w.update("cycles", cv, "start_ts = ? AND end_ts = 0",
                new String[]{String.valueOf(startTs)});
        if (n > 0) return true;
        cv.put("start_ts", startTs);
        return w.insertWithOnConflict("cycles", null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0;
    }

    /** 充满的那一刻：所有还没收尾的手动周期到此结束（新一轮从这次 100% 起算） */
    private static void closeOpen(SQLiteDatabase w, long ts) {
        ContentValues cv = new ContentValues();
        cv.put("end_ts", ts);
        w.update("cycles", cv, "end_ts = 0 AND start_ts < ?", new String[]{String.valueOf(ts)});
    }

    private static long openStart(SQLiteDatabase db) {
        try (Cursor cur = db.rawQuery("SELECT MAX(start_ts) FROM cycles WHERE end_ts = 0", null)) {
            if (cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        }
        return -1;
    }

    private static long openStart(Context c) {
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase()) {
            return openStart(r);
        }
    }

    /** 已封存的周期，按序号升序。end_ts = 0 的开放行（手动开始、还在计时）不算封存 */
    public static List<Sealed> sealed(Context c) {
        List<Sealed> out = new ArrayList<>();
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase();
             Cursor cur = r.rawQuery("SELECT idx, start_ts, end_ts, manual FROM cycles "
                     + "WHERE end_ts > 0 ORDER BY idx ASC", null)) {
            while (cur.moveToNext()) {
                out.add(new Sealed(cur.getInt(0), cur.getLong(1), cur.getLong(2), cur.getInt(3) != 0));
            }
        }
        return out;
    }

    /** 某时间窗内最后一个点；没有则 null。用于「一天跨两轮」时把那两轮各自当天的收尾值分开 */
    public static Point lastBetween(Context c, long from, long to) {
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase();
             Cursor cur = r.rawQuery("SELECT ts, pct FROM samples WHERE ts >= ? AND ts <= ? "
                     + "ORDER BY ts DESC LIMIT 1", new String[]{String.valueOf(from), String.valueOf(to)})) {
            if (cur.moveToFirst()) return new Point(cur.getLong(0), cur.getInt(1));
        }
        return null;
    }

    /** 某时间窗内的读数次数 */
    public static int countBetween(Context c, long from, long to) {
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase();
             Cursor cur = r.rawQuery("SELECT COUNT(*) FROM samples WHERE ts >= ? AND ts <= ?",
                     new String[]{String.valueOf(from), String.valueOf(to)})) {
            if (cur.moveToFirst()) return cur.getInt(0);
        }
        return 0;
    }

    public static int count(Context c) {
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase();
             Cursor cur = r.rawQuery("SELECT COUNT(*) FROM samples", null)) {
            return cur.moveToFirst() ? cur.getInt(0) : 0;
        }
    }

    public static void clear(Context c) {
        try (Db d = db(c); SQLiteDatabase w = d.getWritableDatabase()) {
            w.delete("samples", null, null);
            // 封存的周期也得一起清：只清 samples 的话「清空」后明细里还留着一串空壳分组标题
            w.delete("cycles", null, null);
        }
    }

    private Samples() { }
}
