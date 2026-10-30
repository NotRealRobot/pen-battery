package com.playbuddy.penbattery;

import android.Manifest;
import android.app.LocaleManager;
import android.content.Intent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.view.View;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * 主容器：内容区（Fragment）+ 底部悬浮毛玻璃导航。
 * 职责边界：本类只管 **GATT 生命周期、权限、导航**；三个页面各自订阅 State 并渲染。
 */
public class MainActivity extends AppCompatActivity {

    private PenGatt ownGatt;                  // 全进程唯一一条 GATT
    private BottomNavigationView nav;
    private int lastTab = R.id.nav_battery;      // "设置"不是页面，弹完要回到原来那一页
    private boolean pickShown;              // 未定目标时只提醒一次
    private boolean prompted;              // 本次"没定下设备"只弹一次提示条
    private static final long NO_TARGET_MS = 8_000L;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable noTargetPrompt = new Runnable() {
        @Override public void run() {
            if (State.btOn && !State.hasTarget) promptPick();
        }
    };
    /**
     * 蓝牙开关变化时立刻重判状态。API 31+ 只能动态注册（Manifest 里静态声明收不到），
     * 注册失败也不影响正确性 —— onResume 与下拉刷新走的是同一条 syncMode()。
     */
    private final BroadcastReceiver btState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (st == BluetoothAdapter.STATE_ON || st == BluetoothAdapter.STATE_OFF) syncMode();
        }
    };

    private final BatteryFragment fBattery = new BatteryFragment();
    private final HistoryFragment fHistory = new HistoryFragment();
    private final AboutFragment fAbout = new AboutFragment();

    /** 导出：系统文件选择器 */
    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                Intent d = r.getData();
                if (r.getResultCode() != RESULT_OK || d == null || d.getData() == null) return;
                String msg = ExportHelper.write(this, d.getData());
                Snackbar.make(findViewById(R.id.container), msg, Snackbar.LENGTH_LONG).show();
            });

    /** 权限：拿到结果后再决定走哪条链路 */
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    map -> {
                        syncMode();
                        // 权限不全时明确提示（否则 GATT 静默不启动，用户会以为坏了）
                        boolean all = Boolean.TRUE.equals(map.get(Manifest.permission.BLUETOOTH_CONNECT));
                        if (!all) {
                            Snackbar.make(findViewById(R.id.container),
                                    getString(R.string.msg_need_perm), Snackbar.LENGTH_LONG).show();
                        }
                    });

    private final PenGatt.Sink sink = new PenGatt.Sink() {
        @Override public void onPct(int pct, String source) {
            History.record(MainActivity.this, pct);
            Samples.record(MainActivity.this, pct);      // 曲线与运行时长的数据源
            State.pct = pct;
            State.source = source;
            State.lastTime = now();
            State.fire();
        }
        @Override public void onStatus(String text) { State.status = text; State.fire(); }
        @Override public void onConnected(boolean c) { State.connected = c; State.fire(); }
        @Override public void onSubscription(boolean level) { State.levelSub = level; State.fire(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(this::switchTo);
        setupGlass();                                 // 毛玻璃交给 BlurView 库（官方推荐配置）

        if (b == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, fBattery).commit();
        }
        requestPermsIfNeeded();
    }

    /** 四种可选界面语言：tag 用 BCP 47，名字各用其本身的写法 */
    private static final String[] LANG_TAGS = {"en", "zh-CN", "zh-TW", "zh-HK"};
    private static final int[] LANG_LABELS = {
            R.string.lang_en, R.string.lang_zh, R.string.lang_zh_tw, R.string.lang_zh_hk};

    /**
     * 设置 = 目前只有语言。列表永远是这四种语言，没有额外的"跟随系统"选项 ——
     * 「跟随系统」是**语言本身的一个特征**：系统默认语言就在这四种里时，它自己那一项
     * 一直带着这个说法（不管当前钉死在哪种语言）；系统默认语言不在这四种里时
     * （如日语，界面只会落到英文），四项都只写语言名，不出现这个说法。
     * 点语言 = 钉死；点带"跟随系统"的那一项 = 清空覆盖（系统语言以后再变、界面跟着变），
     * 两者当前效果相同，但后者让界面继续跟着系统走。
     * 语言名用各语言自己的写法（资源里的 lang_*），不能走 Locale.getDisplayLanguage：
     * 它按 language 字段（zh）取，脚本与地区都被丢掉，三种中文会显示成同一个"中文"。
     * 系统语言必须走 LocaleManager.getSystemLocales()：`Resources.getSystem()` 的配置会跟着
     * 应用语言覆盖一起变（实测 App 钉死 zh-CN、系统 en-US 时它报 zh-CN），用它会让
     * 「跟随系统」永远落在刚选的那一项上 —— 2.8.3 就是这么错的。
     */
    private void showSettings() {
        String sysTag = supported(systemLanguageTag());

        LocaleListCompat now = AppCompatDelegate.getApplicationLocales();
        // 没有覆盖时实际生效的就是系统语言；系统语言不支持，落到英文默认资源
        String curTag = now.isEmpty() ? sysTag : supported(now.toLanguageTags());

        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> tags = new java.util.ArrayList<>();
        for (int i = 0; i < LANG_TAGS.length; i++) {
            labels.add(LANG_TAGS[i].equals(sysTag)
                    ? getString(R.string.lang_system_of, getString(LANG_LABELS[i]))
                    : getString(LANG_LABELS[i]));
            tags.add(LANG_TAGS[i]);
        }
        int cur = tags.indexOf(curTag);
        if (cur < 0) cur = 0;      // 系统语言不在这四种里 ⇒ 界面落到的就是 0 号英文（values/ 默认资源）

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_lang_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), cur, (d, w) -> {
                    d.dismiss();
                    String g = tags.get(w);
                    // 带"跟随系统"的那一项 = 不钉死，清空覆盖
                    AppCompatDelegate.setApplicationLocales(g.equals(sysTag)
                            ? LocaleListCompat.getEmptyLocaleList()
                            : LocaleListCompat.forLanguageTags(g));
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    /** 系统设置里的语言（不受本应用的 per-app 语言覆盖影响） */
    private String systemLanguageTag() {
        LocaleManager lm = getSystemService(LocaleManager.class);
        LocaleList sl = lm == null ? null : lm.getSystemLocales();
        if (sl != null && !sl.isEmpty()) return sl.get(0).toLanguageTag();
        return Resources.getSystem().getConfiguration().getLocales().get(0).toLanguageTag();
    }

    /**
     * 把任何语言标签归到 LANG_TAGS 里的一个；不认识的返回原标签（调用方据此判断
     * 系统语言不在四种之内）。
     * zh-Hant 不带地区时按台湾处理 —— AOSP 给 zh-Hant 的默认地区就是 TW，
     * 资源侧 `values-b+zh+Hant` 也按台湾用字兜底，两边口径一致；zh-HK / zh-MO 才是香港。
     */
    private static String supported(String tag) {
        if (tag == null || tag.isEmpty()) return "en";
        String t = tag.replace('_', '-');
        if (t.equals("en") || t.startsWith("en-")) return "en";
        if (!(t.equals("zh") || t.startsWith("zh-"))) return t;
        if (t.contains("Hant") || t.contains("-TW") || t.contains("-HK") || t.contains("-MO")) {
            return (t.contains("-HK") || t.contains("-MO")) ? "zh-HK" : "zh-TW";
        }
        return "zh-CN";                                          // zh / zh-Hans / zh-SG…
    }

    private boolean switchTo(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_settings) {
            showSettings();
            nav.post(() -> nav.setSelectedItemId(lastTab));   // 选中态不能留在"设置"上
            return false;
        }
        lastTab = id;
        Fragment f;
        if (id == R.id.nav_history) f = fHistory;
        else if (id == R.id.nav_about) f = fAbout;
        else f = fBattery;
        getSupportFragmentManager().beginTransaction().replace(R.id.container, f).commit();
        return true;
    }

    // ---------------------------------------------------- 毛玻璃（BlurView 库）
    /**
     * 用 Dimezis/BlurView v3 替代我手写的整条玻璃链路。
     * ★ 官方规则：要被模糊的内容必须包在 **BlurTarget** 里，且该 BlurTarget
     *   不能包含指向它的 BlurView —— 我们的胶囊与 contentRoot 是兄弟节点，天然满足。
     * ★ setFrameClearDrawable：先把底图画满（否则"底图透明"的老问题会再犯）。
     * ★ 圆角：显式 outline + setClipToOutline（比依赖 drawable 的 outline 可靠）。
     */
    private void setupGlass() {
        eightbitlab.com.blurview.BlurView blur =
                findViewById(R.id.navFrame);
        eightbitlab.com.blurview.BlurTarget target =
                findViewById(R.id.contentRoot);
        if (blur == null || target == null) return;

        try {
            eightbitlab.com.blurview.BlurViewFacade facade = blur.setupWith(target);
            // "清底"用根布局自己的背景（colorSurface），保证底图不透明
            android.view.View root = (android.view.View) blur.getParent();
            Drawable clear = (root != null) ? root.getBackground() : null;
            if (clear != null) facade.setFrameClearDrawable(clear);
            // 模糊半径：用户要的是"能看穿"的清透玻璃，不是磨砂。实测标定阶梯
            // 20f→12f→9f→16f（偏糊）→5f（方向对，用户要再往回加一点）→ 8f。
            // 注意与蒙版是两回事：半径只管"糊多糊少"，透明度由 nav_glass_scrim 管。
            facade.setBlurRadius(8f);
            facade.setBlurAutoUpdate(true);     // 内容一变就重算（帧同步，无延迟）

            // 28dp 圆角 + 裁剪
            final float r = 28f * getResources().getDisplayMetrics().density;
            blur.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(@NonNull View view, @NonNull android.graphics.Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            blur.setClipToOutline(true);
        } catch (Throwable t) {
            // 兜底：库不可用就退回半透明圆角背景，绝不让 App 崩
            android.util.Log.d("PenBattery", "BlurView setup failed -> " + t);
            blur.setBackground(androidx.core.content.res.ResourcesCompat.getDrawable(
                    getResources(), R.drawable.bg_nav_floating, getTheme()));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        try {
            registerReceiver(btState, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Exception ignore) { }
    }

    @Override protected void onResume() {
        super.onResume();
        syncMode();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(btState); } catch (Exception ignore) { }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (ownGatt != null) { ownGatt.stop(); ownGatt = null; }
        super.onDestroy();
    }

    // ---------------------------------------------------- 权限
    private boolean hasBtPerm() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermsIfNeeded() {
        if (hasBtPerm()) { syncMode(); return; }
        permLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT});
    }

    // ---------------------------------------------------- GATT 生命周期
    /** 系统"允许打开蓝牙"弹窗：由**用户**点允许，我们不替他开关（安全约定） */
    void askEnableBluetooth() {
        try {
            btEnable.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Throwable nf) {
                toast(getString(R.string.need_bt_first));
            }
        }
    }

    private final ActivityResultLauncher<Intent> btEnable =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    r -> syncMode());   // 用户点了允许 ⇒ 立刻重判一次

    boolean btOn() {
        BluetoothAdapter a = adapter();
        return a != null && a.isEnabled();
    }

    private BluetoothAdapter adapter() {
        android.bluetooth.BluetoothManager m =
                (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }
    /** 应用在前台期间维持唯一一条 GATT 连接；断线由 PenGatt 按退避策略自动重连 */
    void syncMode() {
        if (!hasBtPerm()) { State.status = getString(R.string.status_idle); State.fire(); return; }
        BluetoothAdapter ad = adapter();
        State.btOn = ad != null && ad.isEnabled();
        if (!State.btOn) {                      // 蓝牙没开 ⇒ 这条提示优先于"未连接"
            State.supported = false;
            if (ownGatt != null) { ownGatt.stop(); ownGatt = null; }
            State.connected = false; State.levelSub = false;
            State.status = getString(R.string.status_bt_off);
            State.fire();
            return;
        }
        State.hasTarget = PenTarget.resolve(this) != null;
        if (!State.hasTarget) {
            // 还没定下设备：不遮罩、不立刻打扰；电量条照旧显示"加载中"，
            // 等 8 秒还没连上才弹一次提示条（用户 10-14 定的节奏）。
            State.supported = false;
            State.status = getString(R.string.status_no_target); State.fire();
            if (!prompted) ui.postDelayed(noTargetPrompt, NO_TARGET_MS);
            return;
        }
        ui.removeCallbacks(noTargetPrompt); prompted = false;   // 定下来了 ⇒ 下次再丢还得重新提醒
        State.supported = PenTarget.isSupported(this);
        if (!State.supported) {
            // 不是这支笔 ⇒ 根本不发起 GATT：连了也读不到，还可能把别的设备的数值写进记录
            if (ownGatt != null) { ownGatt.stop(); ownGatt = null; }
            State.connected = false; State.levelSub = false; State.pct = -1;
            State.source = ""; State.lastTime = "";
            State.status = getString(R.string.status_unsupported);   // 只说现状；可点词交给遮罩里那一个
            State.fire();
            return;
        }
        if (ownGatt == null) ownGatt = new PenGatt(this, sink);
        ownGatt.start();
    }

    /**
     * 已配对列表定不出唯一目标（0 台或多台且无名字命中）时让用户点一次。
     * 一次前台期只提醒一条，别刷屏。
     */
    private void promptPick() {
        if (pickShown || prompted) return;
        pickShown = true; prompted = true;
        Snackbar.make(findViewById(R.id.container), R.string.status_no_target,
                        Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.action_pick, v -> pickTarget(null))
                .show();
    }

    /**
     * 设备选择框：关于页的「切换连接设备」和"定不出目标"的提醒都走这里。
     * `after` 给调用方刷新自己显示用（关于页选完要立刻换掉那一行）。
     */
    void pickTarget(@Nullable Runnable after) {
        pickShown = false;
        if (!btOn()) {                 // 用户的逻辑：蓝牙都没开，就不该让他去挑一台"连不上"的设备
            askEnableBluetooth();
            return;
        }
        java.util.List<android.bluetooth.BluetoothDevice> l = PenTarget.candidates(this);
        if (l.isEmpty()) { toast(getString(R.string.status_no_bonded)); return; }
        String saved = PenTarget.address(this);
        String[] items = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            String cur = (saved != null && saved.equals(PenTarget.addressOf(l.get(i))))
                    ? getString(R.string.current_suffix) : "";
            items[i] = PenTarget.label(l.get(i)) + cur;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_pick_title)
                .setItems(items, (d, w) -> {
                    PenTarget.pick(this, l.get(w));
                    syncMode();
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    // ---------------------------------------------------- 供页面调用
    /** 下拉刷新：走本 Activity 持有的这条连接 */
    void refreshNow() {
        syncMode();   // 先重判权限/蓝牙/目标，否则下拉刷新只会转圈、状态文字不动
        if (ownGatt != null) ownGatt.readNow("手动下拉刷新");
    }

    void exportHistory() { exportLauncher.launch(ExportHelper.buildIntent()); }

    void toast(String s) {
        Snackbar.make(findViewById(R.id.container), s, Snackbar.LENGTH_SHORT).show();
    }

    static String now() {
        return java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
