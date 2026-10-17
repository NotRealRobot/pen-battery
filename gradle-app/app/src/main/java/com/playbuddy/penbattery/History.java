package com.playbuddy.penbattery;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 电量历史：按天记录「当天最后一次读到的电量」+ 当天读数次数。
 *
 * 存储：SharedPreferences（应用私有），不占额外权限。
 *   key   = "d:yyyy-MM-dd"
 *   value = "<电量>|<HH:mm:ss>|<次数>"     例：100|23:14:05|7
 *
 * 设计取舍：每天只保留"最后一次"而不存全部流水 ——
 *   ① 需求就是"每天最后一次"；② 无数据库、体积恒定；③ 长期不膨胀。
 */
public class History {

    static final String PREFS = "pen_battery_history";
    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 某一天的一条记录 */
    public static class Day {
        public final String date;   // yyyy-MM-dd
        public final int pct;       // 电量百分比
        public final String time;   // 当天最后一次读数时间 HH:mm:ss
        public final int count;     // 当天读数次数
        Day(String d, int p, String t, int c) { date = d; pct = p; time = t; count = c; }
        /** 这条记录的时刻（毫秒）；时间字段缺失时退化为当天 00:00 */
        public long tsMillis() {
            try {
                return java.time.LocalDateTime.of(LocalDate.parse(date, DF),
                                time.isEmpty() ? java.time.LocalTime.MIDNIGHT
                                               : java.time.LocalTime.parse(time, TF))
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e) { return -1; }
        }
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 记录一次读数：更新"最后一次"的值与时间，并把当天计数 +1 */
    public static void record(Context c, int pct) {
        if (pct < 0 || pct > 100) return;
        String key = "d:" + LocalDate.now().format(DF);
        SharedPreferences p = sp(c);
        int cnt = 1;
        String old = p.getString(key, null);
        if (old != null) {
            String[] f = old.split("\\|");
            if (f.length >= 3) {
                try { cnt = Integer.parseInt(f[2]) + 1; } catch (NumberFormatException ignore) { }
            }
        }
        p.edit().putString(key, pct + "|" + LocalTime.now().format(TF) + "|" + cnt).apply();
    }

    /** 取某一天的记录；没有则返回 null */
    public static Day get(Context c, String date) {
        String v = sp(c).getString("d:" + date, null);
        if (v == null) return null;
        String[] f = v.split("\\|");
        if (f.length == 0) return null;
        int pct;
        try { pct = Integer.parseInt(f[0]); } catch (NumberFormatException e) { return null; }
        if (pct < 0 || pct > 100) return null;
        String t = f.length >= 2 ? f[1] : "";
        int cnt = 1;
        if (f.length >= 3) { try { cnt = Integer.parseInt(f[2]); } catch (NumberFormatException ignore) { } }
        return new Day(date, pct, t, cnt);
    }

    /** 有记录的天数 */
    public static int countDays(Context c) {
        return sp(c).getAll().size();
    }

    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }

    /** 最近 n 天的日期字符串，倒序（下标 0 = 今天） */
    public static String[] lastDays(int n) {
        String[] a = new String[n];
        LocalDate d = LocalDate.now();
        for (int i = 0; i < n; i++) a[i] = d.minusDays(i).format(DF);
        return a;
    }

    /**
     * 上一次读到满电（≥100%）的时刻，毫秒；没有则 -1。
     * 按天表每天只留最后一次读数，所以这里取的是「那天最后一次读数就是满电」的最后一天。
     * 它是周期起点的种子 —— 时间序列表是 2.6.0 才加的，之前的满电只存在这里。
     */
    public static long lastFullTs(Context c) {
        long best = -1;
        for (String d : allDates(c)) {
            Day day = get(c, d);
            if (day == null || day.pct < 100) continue;
            long ms = day.tsMillis();
            if (ms > best) best = ms;
        }
        return best;
    }

    /** 全部有记录的日期，按时间升序 */
    public static java.util.List<String> allDates(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : sp(c).getAll().keySet()) {
            if (k.startsWith("d:")) out.add(k.substring(2));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 导出为 CSV 文本（带 UTF-8 BOM，Excel 直接打开不乱码） */
    public static String csv(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append(c.getString(R.string.csv_header)).append("\r\n");
        for (String d : allDates(c)) {
            Day day = get(c, d);
            if (day == null) continue;
            sb.append(day.date).append(',')
              .append(day.pct).append(',')
              .append(day.time).append(',')
              .append(day.count).append("\r\n");
        }
        return sb.toString();
    }
}
