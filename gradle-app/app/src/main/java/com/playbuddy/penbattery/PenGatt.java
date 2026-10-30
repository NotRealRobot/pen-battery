package com.playbuddy.penbattery;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/**
 * GATT 引擎：读笔的电量（Battery Service 0x180F / Battery Level 0x2A19）。
 *
 * 说明：这支笔的服务里还有一个 0x2A1A（Battery Power State），但**固件恒返回 0x00**、
 * 不提供任何有效内容（实测：可读可通知、无错误码、值恒为 0x00）⇒ 已按用户要求
 * **彻底移除电源状态相关内容**，不再订阅、不再读取、界面也不再显示。
 *
 * ★ 每个进程只应存在一个实例：由 MainActivity 持有，避免两处各连一条。
 * ★ 所有回调统一在主线程触发（内部 post），调用方不必关心线程。
 * ★ 修过的坑：读链守卫不能写成 "step != IDLE 就返回" ——
 *   它正是从 onDescriptorWrite 里调用的，那样会被自己挡掉。
 * ★ 权限：`BLUETOOTH_CONNECT` 由 MainActivity 在拿到 GATT 之前确认，本类只在已授予时被调用；
 *   整类静音 lint 的 MissingPermission —— 它看不到这条跨方法的保证。
 */
@SuppressLint("MissingPermission")
public final class PenGatt {

    public interface Sink {
        void onPct(int pct, String source);
        void onStatus(String text);
        void onConnected(boolean connected);
        /** 电量通知通道是否已订阅上 */
        void onSubscription(boolean level);
    }

    private static final String TAG = "PenBattery";

    // 目标设备地址来自 PenTarget（运行时解析 + 本地记住），不再是编译期常量
    static final UUID SVC_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID CHR_LEVEL   = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long POLL_MS = 60_000L;
    private static final long CCCD_TIMEOUT_MS = 3_000L;

    private static final int S_IDLE = 0, S_CCCD = 1, S_READ = 2;

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cLevel;
    private boolean subLevel;
    private int step = S_IDLE;
    private long lastConnAt;
    private boolean stopped = true;

    private final Runnable poll = new Runnable() {
        @Override public void run() { readNow("定时兜底"); }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (step == S_CCCD) {
                Log.i(TAG, "CCCD 超时 → 强制转入读取");
                startReadChain("CCCD 超时兜底");
            }
        }
    };

    public PenGatt(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
    }


    // ------------------------------------------------------------ 生命周期
    public void start() {
        stopped = false;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { st(R.string.gatt_no_adapter); return; }
        if (!ad.isEnabled()) { st(R.string.gatt_bt_off); return; }
        if (ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            st(R.string.gatt_no_perm);
            return;
        }
        String mac = PenTarget.resolve(ctx);      // 本地记住的优先，其次从已配对列表自动定
        if (mac == null) { st(R.string.status_no_target); return; }
        BluetoothDevice pen;
        try { pen = ad.getRemoteDevice(mac); }
        catch (IllegalArgumentException e) { st(R.string.gatt_bad_addr); return; }

        close();
        st(R.string.gatt_connecting);
        gatt = pen.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        Log.i(TAG, "connectGatt 已发起");
    }

    public void stop() {
        stopped = true;
        main.removeCallbacks(poll);
        main.removeCallbacks(watchdog);
        close();
        conn(false);
        sub(false);
        st(R.string.gatt_stopped);
    }

    private void close() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignore) { }
            gatt = null;
        }
        step = S_IDLE;
        subLevel = false;
        cLevel = null;
    }

    /** 触发一次读取（手动唤醒或定时兜底） */
    public void readNow(String why) {
        startReadChain(why);
    }

    private void startReadChain(String why) {
        if (gatt == null || cLevel == null) { Log.i(TAG, "读链未启动（未就绪）"); return; }
        if (step == S_READ) { Log.i(TAG, "读链已在途中，忽略（" + why + "）"); return; }
        Log.i(TAG, "读链开始（" + why + "）");
        step = S_READ;
        boolean ok = gatt.readCharacteristic(cLevel);
        Log.i(TAG, "readCharacteristic(0x2A19) 提交=" + ok);
        if (!ok) { step = S_IDLE; schedulePoll(); }
    }

    private void schedulePoll() {
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
    }

    // ------------------------------------------------------------ 回调 → 主线程
    private void pct(final int p, final String src) {
        main.post(new Runnable() { @Override public void run() { sink.onPct(p, src); } });
    }
    /** 状态文字：一律给资源 id，界面才能跟着语言切换 */
    private void st(final int res, final Object... fmt) {
        State.statusRes = res;
        main.post(() -> { State.status = ctx.getString(res, fmt); sink.onStatus(State.status); });
    }

    private void conn(final boolean c) {
        main.post(new Runnable() { @Override public void run() { sink.onConnected(c); } });
    }
    private void sub(final boolean l) {
        main.post(new Runnable() { @Override public void run() { sink.onSubscription(l); } });
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                lastConnAt = android.os.SystemClock.elapsedRealtime();
                conn(true);
                st(R.string.gatt_discovering);
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.removeCallbacks(poll);
                main.removeCallbacks(watchdog);
                conn(false);
                sub(false);
                step = S_IDLE;
                st(R.string.gatt_disconnected, status);
                // 应用在前台期间断线就自动重连（常驻模式已移除，这是唯一的恢复路径）
                if (!stopped) {
                    // 笔空闲 5~8 分钟会自行断开（固件节电，正常现象）；
                    // "刚连上就断"属异常 ⇒ 退避久一点，别把笔和射频来回折腾。
                    long lived = android.os.SystemClock.elapsedRealtime() - lastConnAt;
                    long delay = lived < 60_000L ? 30_000L : 8_000L;
                    Log.i(TAG, "将在 " + (delay / 1000) + " 秒后重连（上次存活 " + (lived / 1000) + " 秒）");
                    main.postDelayed(new Runnable() {
                        @Override public void run() { if (!stopped) start(); }
                    }, delay);
                }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.i(TAG, "onServicesDiscovered status=" + status);
            BluetoothGattService s = g.getService(SVC_BATTERY);
            if (s == null) { st(R.string.gatt_no_service); return; }
            cLevel = s.getCharacteristic(CHR_LEVEL);
            if (cLevel == null) { st(R.string.gatt_no_char); return; }

            BluetoothGattDescriptor d = cLevel.getDescriptor(CCCD);
            if (d == null) { startReadChain("无 CCCD"); return; }
            step = S_CCCD;
            st(R.string.gatt_subscribing);
            g.setCharacteristicNotification(cLevel, true);
            int rc = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            Log.i(TAG, "writeDescriptor(0x2A19 CCCD) 提交=" + rc + " (0=成功)");
            main.removeCallbacks(watchdog);
            main.postDelayed(watchdog, CCCD_TIMEOUT_MS);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i(TAG, "onDescriptorWrite status=" + status + " (0=成功)");
            if (step == S_CCCD) {
                subLevel = (status == 0);
                sub(subLevel);
                main.removeCallbacks(watchdog);
                startReadChain("订阅完成");
            } else {
                startReadChain("步进异常兜底");
            }
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                   byte[] value, int status) {
            handle(c, value, status, ctx.getString(R.string.src_read));
            step = S_IDLE;
            st(R.string.gatt_ready);
            schedulePoll();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                      byte[] value) {
            handle(c, value, 0, ctx.getString(R.string.src_notify));
        }
    };

    private void handle(BluetoothGattCharacteristic c, byte[] value, int status, String src) {
        if (c == null || value == null || value.length == 0) return;
        UUID u = c.getUuid();
        if (status != 0) { Log.i(TAG, src + " 失败 uuid=" + u + " status=" + status); return; }
        if (CHR_LEVEL.equals(u)) {
            int p = value[0] & 0xFF;
            Log.i(TAG, src + " 电量=0x" + Integer.toHexString(p) + " ⇒ " + p + "%");
            pct(p, src);
        }
    }
}
