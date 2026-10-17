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
        public boolean complete() { return startTs > 0 && endTs > 0; }
    }

    private static final int FULL_PCT = 100;
    private static final int LOW_PCT = 10;
    /** 同一个值在这么短的时间内不重复入库（60 s 轮询 ⇒ 满电挂着不动时会刷屏） */
    private static final long DEDUP_MS = 10 * 60_000L;
    /** 只保留最近这么多个点，防止长期无限增长 */
    private static final int MAX_POINTS = 20_000;

    private static final class Db extends SQLiteOpenHelper {
        Db(Context c) { super(c, "pen_battery_samples.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE samples (ts INTEGER PRIMARY KEY, pct INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int a, int b) {
            db.execSQL("DROP TABLE IF EXISTS samples");
            onCreate(db);
        }
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

            // 截断过老的数据（按条数，不按时间：前台采样密度不均）
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

    public static int count(Context c) {
        try (Db d = db(c); SQLiteDatabase r = d.getReadableDatabase();
             Cursor cur = r.rawQuery("SELECT COUNT(*) FROM samples", null)) {
            return cur.moveToFirst() ? cur.getInt(0) : 0;
        }
    }

    public static void clear(Context c) {
        try (Db d = db(c); SQLiteDatabase w = d.getWritableDatabase()) {
            w.delete("samples", null, null);
        }
    }

    private Samples() { }
}
