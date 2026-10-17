package com.playbuddy.penbattery;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 电量目标的来源：系统已配对列表，运行时解析，本地记住用户的选择。
 *
 * ★ 为什么不再用编译期常量：APK 里的字符串常量藏不住（实测 `classes.dex` 明文可见），
 *   而且写死地址意味着别人装了也用不了。
 * ★ 实测依据（真机：MIUI 14 / Android 13）：MIUI 14 / Android 13 上 `getBondedDevices()` 能看到这支笔，
 *   名字就是 `Dell PN7522W`、type=LE、已配对，所需权限只有 `BLUETOOTH_CONNECT`（本 App 已声明并已授予）。
 */
public final class PenTarget {

    private static final String PREFS = "pen_target";
    private static final String K_MAC = "mac";
    private static final String K_NAME = "name";

    /** 名字关键字（小写包含匹配）。列表里只有耳机和笔时，这一步就能定下来。 */
    private static final String[] KEYS = {"pn7522", "pen", "stylus", "wacom", "dell"};
    /** 唯一经过实测验证的型号。所有结论都来自这支笔 ⇒ 决定各页要不要遮罩 */
    private static final String MODEL = "PN7522";


    private PenTarget() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 用户选定或自动定下的地址；没有则 null */    /** 当前目标是不是本应用适配的型号 */
    public static boolean isSupported(Context c) {
        return name(c).toUpperCase(java.util.Locale.ROOT).contains(MODEL);
    }

    public static String address(Context c) {
        String m = sp(c).getString(K_MAC, null);
        return (m == null || m.isEmpty()) ? null : m;
    }

    public static String name(Context c) {
        return sp(c).getString(K_NAME, "");
    }

    public static void save(Context c, String mac, String name) {
        sp(c).edit().putString(K_MAC, mac).putString(K_NAME, name).apply();
    }

    /** 忘了写错、想换一支笔 */
    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }

    /** 系统里已配对的候选设备；权限被收回或蓝牙未开时返回空表 */
    public static List<BluetoothDevice> candidates(Context c) {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) return out;
        try {
            if (a.getBondedDevices() != null) out.addAll(a.getBondedDevices());
        } catch (SecurityException e) {
            // BLUETOOTH_CONNECT 被用户收回 ⇒ 没有候选，由调用方给提示
        }
        return out;
    }

    /**
     * 只认名字关键字。**故意不做**"列表里只有一台就用它"那种兜底 ——
     * 猜错的代价是连着别人的设备读不到东西、界面却显示"已选中"，比让用户点一次选择框难查得多。
     */
    public static BluetoothDevice autoSelect(Context c) {
        for (BluetoothDevice d : candidates(c)) {
            String n = safeName(d).toLowerCase();
            for (String k : KEYS) if (n.contains(k)) return d;
        }
        return null;
    }

    /** 设备地址（权限被收回时返回空串）——界面比对"当前选中"用 */
    public static String addressOf(BluetoothDevice d) { return safeAddr(d); }

    /**
     * 解析出可用地址：本地记住的优先（但必须仍在配对列表里，否则视为选错/已解绑 ⇒ 重找），
     * 其次自动定。都定不下来就返回 null，由界面提示用户点一次选择框。
     */
    public static String resolve(Context c) {
        String saved = address(c);
        if (saved != null) {
            if (isBonded(c, saved)) return saved;
            clear(c);
        }
        BluetoothDevice d = autoSelect(c);
        if (d == null) return null;
        return pick(c, d) ? address(c) : null;
    }

    /** 用户在列表里点定某台设备 ⇒ 记住它 */
    public static boolean pick(Context c, BluetoothDevice d) {
        String addr = safeAddr(d);
        if (addr.isEmpty()) return false;
        save(c, addr, safeName(d));
        return true;
    }

    private static boolean isBonded(Context c, String mac) {
        for (BluetoothDevice d : candidates(c)) if (mac.equals(safeAddr(d))) return true;
        return false;
    }

    /** 列表项文案：名字 + 完整地址（10-16 用户指示不打码；名字或地址取不到时不留空档） */
    public static String label(BluetoothDevice d) {
        String n = safeName(d), a = safeAddr(d);
        if (n.isEmpty()) return a.isEmpty() ? "—" : a;        // 语言中性
        return a.isEmpty() ? n : n + "  " + a;
    }

    private static String safeName(BluetoothDevice d) {
        try { String s = d.getName(); return s == null ? "" : s; }
        catch (SecurityException e) { return ""; }
    }

    private static String safeAddr(BluetoothDevice d) {
        try { String s = d.getAddress(); return s == null ? "" : s; }
        catch (SecurityException e) { return ""; }
    }
}
