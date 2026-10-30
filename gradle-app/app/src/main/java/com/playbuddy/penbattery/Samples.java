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

    /** 一个放电周期：startTs = 最近一次读到 100% 的时刻；endTs = 其后第一次读到 ≤10% 的时刻（未到则 -1） */
    public static final class Cycle {
        public final long startTs;
        public final long endTs;
        Cycle(long startTs, long endTs) { this.startTs = startTs; this.endTs = endTs; }
    }

    /** 一个已封存（走完）的周期：idx 是它在库里的序号，也就是界面上的「第 n 周期」。 */
    public static final class Sealed {
        public final int idx;
        public final long startTs;
        public final long endTs;
        Sealed(int idx, long startTs, long endTs) { this.idx = idx; this.startTs = startTs; this.endTs = endTs; }
    }

    private static final int FULL_PCT = 100;
    private static final int LOW_PCT = 10;
    /** 同一个值在这么短的时间内不重复入库（60 s 轮询 ⇒ 满电挂着不动时会刷屏） */
    private static final long DEDUP_MS = 10 * 60_000L;
    /** 只保留最近这么多个点，防止长期无限增长 */
    private static final int MAX_POINTS = 20_000;

    private static final class Db extends SQLiteOpenHelper {
        Db(Context c) { super(c, "pen_battery_samples.db", null, 2); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE samples (ts INTEGER PRIMARY KEY, pct INTEGER NOT NULL)");
            createCycles(db);
        }
        /**
         * v1 → v2 只加 cycles 表。**不能沿用旧的 DROP + onCreate**：那会把用户跑出来的
         * 时间序列整表清掉，而周期封存的起点恰恰只存在于这份序列里。
         */
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) createCycles(db);
        }
    }

    /**
     * 封存的周期表。为什么要落库而不每次从 samples 现推：
     *   ① samples 按条数截断（MAX_POINTS），老周期的端点会消失，「第 n 周期」就不能再靠现推得到；
     *   ② 序号要稳定 —— 现推的话最早一轮一旦掉出窗口，后面每一轮的编号都会往前挪。
     * start_ts 唯一 ⇒ 回填可以反复跑而不会插重复。
     */
    private static void createCycles(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cycles ("
                + "idx INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "start_ts INTEGER UNIQUE NOT NULL, "
                + "end_ts INTEGER NOT NULL)");
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
        return new Cycle(start, end);
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
        try (Cursor cur = w.rawQuery("SELECT ts, pct FROM samples ORDER BY ts ASC", null)) {
            while (cur.moveToNext()) {
                long ts = cur.getLong(0);
                int pct = cur.getInt(1);
                if (pct >= FULL_PCT) { start = ts; }                       // 新的满电 ⇒ 开新一轮
                else if (pct <= LOW_PCT) {
                    long s = start > 0 ? start : History.lastFullBefore(c, ts);
                    if (s <= 0) {
                        android.util.Log.i("Samples", "低电量 " + pct + "% 在 " + ts
                                + " 找不到起点：序列与按天表里都没有更早的满电");
                    } else if (start > 0 || s > lastEnd) {
                        ContentValues cv = new ContentValues();
                        cv.put("start_ts", s);
                        cv.put("end_ts", ts);
                        // start_ts 唯一 ⇒ 同一个起点只会封一次；夹在中间的重复低电量读数不会插第二条
                        if (w.insertWithOnConflict("cycles", null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0) {
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

    /** 已封存的周期，按序号升序 */
    public static List<Sealed> sealed(Context c) {
        List<Sealed> out = new ArrayList<>();
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase();
             Cursor cur = r.rawQuery("SELECT idx, start_ts, end_ts FROM cycles ORDER BY idx ASC", null)) {
            while (cur.moveToNext()) out.add(new Sealed(cur.getInt(0), cur.getLong(1), cur.getLong(2)));
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
