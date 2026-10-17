package com.playbuddy.penbattery;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内共享状态 + 监听器广播：GATT 回调写它，三个页面读它。
 * setter 一律由调用方切回主线程后再调。
 *
 * 注：曾有 pwr / pwrSub（0x2A1A 电源状态），实测固件恒返回 0x00 ⇒ 整体删除。
 */
public final class State {

    public interface Listener { void onStateChanged(); }

    public static volatile int pct = -1;               // -1 = 未读到
    public static volatile boolean connected = false;
    public static volatile boolean levelSub = false;    // 0x2A19 通知是否已订上    /** 当前目标是否本应用适配的型号；false ⇒ 三个页面的功能模块遮罩、不再写入记录 */
    public static volatile boolean supported = true;
    /** 本机蓝牙是否开着；关着的时候提示优先级高于"未连接" */
    public static volatile boolean btOn = true;

    public static volatile String source = "";          // "读取" / "通知"
    public static volatile String status = "";          // 空串由 statusText() 兜底；判定看 statusRes
    /** 当前状态文字的 string 资源 id（0=无）。换语言后不能再拿中文前缀比对 */
    public static volatile int statusRes = 0;
    public static volatile String lastTime = "";

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void add(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    public static void remove(Listener l) { LISTENERS.remove(l); }

    /** 已经定下一台目标设备了吗（没定下来不算"设备无效"，见 shouldMask） */
    public static volatile boolean hasTarget = false;

    /**
     * 遮罩口径（用户 10-14 定死）：蓝牙开着 **且** 已定下目标 **且** 目标不是这支笔。
     * 蓝牙没开、或还没定下设备 ⇒ 都不遮罩：前者提示「点我打开蓝牙」，后者照旧显示
     * "加载中"进度条，等一会儿还没连上才弹一次提示条。
     */
    public static boolean shouldMask() { return btOn && hasTarget && !supported; }

    /** 由持有 GATT 的一方在主线程调用 */
    public static void fire() {
        for (Listener l : LISTENERS) l.onStateChanged();
    }

}
