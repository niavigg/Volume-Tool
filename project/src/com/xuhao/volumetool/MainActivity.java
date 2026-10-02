package com.xuhao.volumetool;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 主界面：底部导航三页（音量 / 设置 / 关于）。
 * <p>
 * 三页布局直接 include 进 activity_main，靠切换 ScrollView 的 visibility 显示，
 * 不用 ViewPager / Fragment —— 全项目零第三方依赖，只靠 android.jar 就能编译。
 * <p>
 * 职责：
 * <ul>
 *   <li>音量页：读数、调节对象切换、加减按钮（长按连发）、悬浮窗开关</li>
 *   <li>设置页：权限状态、保活状态、快捷操作说明</li>
 *   <li>关于页：版本、设备、功能与实现方式、开源信息</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1001;
    private static final long HOLD_DELAY_MS = 400;
    private static final long HOLD_REPEAT_MS = 120;

    /** 三页容器 */
    private View[] pages = new View[3];
    /** 底部三个 tab */
    private View[] tabs = new View[3];
    private int curPage = 0;

    // ---- 音量页 ----
    private TextView tvStreamName;
    private TextView tvVolumeNow;
    private TextView tvVolumeMax;
    private TextView tvMuted;
    private ProgressBar pbVolume;
    private Button btnStreamMedia;
    private Button btnStreamRing;
    private Switch swOverlay;
    private TextView tvOverlaySub;

    // ---- 设置页 ----
    private View dotPermOverlay, dotPermNotif, dotPermBattery, dotKeepAlive;
    private TextView tvPermOverlaySub, tvPermNotifSub, tvPermBatterySub, tvKeepAliveSub;
    private TextView tvPermOverlayAction, tvPermNotifAction, tvPermBatteryAction, tvKeepAliveAction;

    // ---- 关于页 ----
    private TextView tvAppVer;
    private TextView tvDeviceInfo;

    private BroadcastReceiver volumeWatcher;
    private AlertDialog permDialog;

    /** 抑制 Switch 的回调：代码里 setChecked 时不该再触发一次开关动作 */
    private boolean swProgrammatic = false;

    /**
     * 长按连发的重复任务。
     * 必须用 static 嵌套类 + 显式传入 MainActivity：捕获外部实例产生的
     * this$0 合成字段会触发 build-tools 34.0.0 自带 R8 8.2.2 的 NPE。
     */
    private static final class RepeatStep implements Runnable {
        private final MainActivity a;
        private final Handler h;
        private final int dir;

        RepeatStep(MainActivity a, Handler h, int dir) {
            this.a = a;
            this.h = h;
            this.dir = dir;
        }

        @Override
        public void run() {
            VolumeHelper.step(a, dir, true);
            a.refreshVolumeUi();
            h.postDelayed(this, HOLD_REPEAT_MS);
        }
    }

    /** 音量变化 / 调节对象切换时刷新界面（static 嵌套类，理由同上）。 */
    private static final class RefreshReceiver extends BroadcastReceiver {
        private final MainActivity a;

        RefreshReceiver(MainActivity a) { this.a = a; }

        @Override
        public void onReceive(Context ctx, Intent intent) {
            VolumeNotifier.post(a);
            a.refreshVolumeUi();
            // 无论在哪儿（通知按钮 / 磁贴 / 首页）切了「媒体·铃声」，首页这里立刻跟着高亮
            if (intent != null && Streams.ACTION_CHANGED.equals(intent.getAction())) {
                a.syncStreamUi();
            }
        }    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindPages();
        bindVolumePage();
        bindSettingsPage();
        bindAboutPage();

        // 一进来就把保活服务拉起来（常驻前台服务 = 防杀的根本）
        startKeepAlive();

        volumeWatcher = new RefreshReceiver(this);
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(Streams.ACTION_CHANGED);
        f.addAction(OverlayService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeWatcher, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volumeWatcher, f);
        }
    }

    // ==================== 页面框架 ====================

    private void bindPages() {
        pages[0] = findViewById(R.id.pageVolume);
        pages[1] = findViewById(R.id.pageSettings);
        pages[2] = findViewById(R.id.pageAbout);

        tabs[0] = findViewById(R.id.tabVolume);
        tabs[1] = findViewById(R.id.tabSettings);
        tabs[2] = findViewById(R.id.tabAbout);

        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(v -> showPage(idx));
        }
        showPage(0);
    }

    /** 只显示一页，其余隐藏；tab 的 selected 状态驱动图标与文字换色 */
    private void showPage(int idx) {
        curPage = idx;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) {
                pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            }
            if (tabs[i] != null) {
                tabs[i].setSelected(i == idx);
            }
        }
        if (idx == 0) refreshVolumeUi();
        if (idx == 1) refreshSettingsUi();
    }

    // ==================== 第 1 页：音量 ====================

    private void bindVolumePage() {
        tvStreamName = findViewById(R.id.tvStreamName);
        tvVolumeNow = findViewById(R.id.tvVolumeNow);
        tvVolumeMax = findViewById(R.id.tvVolumeMax);
        tvMuted = findViewById(R.id.tvMuted);
        pbVolume = findViewById(R.id.pbVolume);

        btnStreamMedia = findViewById(R.id.btnStreamMedia);
        btnStreamRing = findViewById(R.id.btnStreamRing);
        btnStreamMedia.setOnClickListener(v -> pickStream(Streams.MEDIA));
        btnStreamRing.setOnClickListener(v -> pickStream(Streams.RING));

        bindHoldButton(findViewById(R.id.btnUp), +1);
        bindHoldButton(findViewById(R.id.btnDown), -1);

        swOverlay = findViewById(R.id.swOverlay);
        tvOverlaySub = findViewById(R.id.tvOverlaySub);
        swOverlay.setOnCheckedChangeListener((btn, checked) -> {
            if (swProgrammatic) return;
            setOverlayEnabled(checked);
        });

        refreshVolumeUi();
    }

    /** 把当前音量和选中对象画到界面上 */
    private void refreshVolumeUi() {
        if (tvVolumeNow == null) return;   // 布局还没 inflate
        int vol = VolumeHelper.getVolume(this);
        int max = VolumeHelper.getMax(this);
        boolean muted = VolumeHelper.isMuted(this);

        tvVolumeNow.setText(String.valueOf(vol));
        tvVolumeMax.setText("/ " + max);
        tvMuted.setVisibility(muted ? View.VISIBLE : View.GONE);
        tvStreamName.setText(Streams.label(this));

        if (max > 0) {
            pbVolume.setMax(max);
            pbVolume.setProgress(vol);
        }
        syncStreamUi();

        // 悬浮窗开关：以服务的真实运行状态为准，避免和磁贴/通知里的开关打架
        boolean on = OverlayService.RUNNING;
        swProgrammatic = true;
        swOverlay.setChecked(on);
        swProgrammatic = false;
        tvOverlaySub.setText(on ? R.string.overlay_sub_on : R.string.overlay_sub_off);
    }

    /**
     * 点一下 = 调 1 格；按住 = 每 120ms 连调。
     * <p>
     * <b>这里踩过坑</b>：OnTouchListener 的 ACTION_DOWN 里调一次，OnClickListener 里又调一次，
     * 而触摸监听返回 false 会把事件继续往下传给点击监听 —— 结果手指点一下跳两格。
     * 所以用一个 consumed 标记：DOWN 已经处理过的那次，onClick 就跳过。
     * 触摸监听必须保持返回 false，否则按钮的按压态和水波纹会没了。
     */
    private void bindHoldButton(Button b, final int dir) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final RepeatStep repeater = new RepeatStep(this, handler, dir);
        final boolean[] consumed = new boolean[1];

        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                consumed[0] = true;          // 这一下我处理了，onClick 别再调
                VolumeHelper.step(this, dir, true);
                refreshVolumeUi();
                handler.postDelayed(repeater, HOLD_DELAY_MS);
            } else if (e.getAction() == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(repeater);
                // 注意：这里不能清 consumed —— onClick 是在 UP 之后才触发的
            } else if (e.getAction() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(repeater);
                consumed[0] = false;         // 被取消了不会有 click，把标记放掉
            }
            return false;
        });
        b.setOnClickListener(v -> {
            if (consumed[0]) {              // 已经由触摸链路调过，忽略这次
                consumed[0] = false;
                return;
            }
            // 走这里说明不是触摸触发的（比如无障碍服务的点击），照常调一格
            VolumeHelper.step(this, dir, true);
            refreshVolumeUi();
        });
    }

    // ==================== 第 2 页：设置 ====================

    private void bindSettingsPage() {
        dotPermOverlay = findViewById(R.id.dotPermOverlay);
        dotPermNotif = findViewById(R.id.dotPermNotif);
        dotPermBattery = findViewById(R.id.dotPermBattery);
        dotKeepAlive = findViewById(R.id.dotKeepAlive);

        tvPermOverlaySub = findViewById(R.id.tvPermOverlaySub);
        tvPermNotifSub = findViewById(R.id.tvPermNotifSub);
        tvPermBatterySub = findViewById(R.id.tvPermBatterySub);
        tvKeepAliveSub = findViewById(R.id.tvKeepAliveSub);

        tvPermOverlayAction = findViewById(R.id.tvPermOverlayAction);
        tvPermNotifAction = findViewById(R.id.tvPermNotifAction);
        tvPermBatteryAction = findViewById(R.id.tvPermBatteryAction);
        tvKeepAliveAction = findViewById(R.id.tvKeepAliveAction);

        findViewById(R.id.rowPermOverlay).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.rowPermNotif).setOnClickListener(v -> requestNotif());
        findViewById(R.id.rowPermBattery).setOnClickListener(v -> requestBatteryExempt());
        findViewById(R.id.rowKeepAlive).setOnClickListener(v -> {
            startKeepAlive();
            v.postDelayed(this::refreshSettingsUi, 500);
        });
    }

    /** 刷新设置页的三条权限 + 保活状态 */
    private void refreshSettingsUi() {
        if (dotPermOverlay == null) return;

        boolean no = needOverlay();
        boolean nn = needNotif();
        boolean nb = needBatteryExempt();

        applyPermRow(dotPermOverlay, tvPermOverlaySub, tvPermOverlayAction, no);
        applyPermRow(dotPermNotif, tvPermNotifSub, tvPermNotifAction, nn);
        applyPermRow(dotPermBattery, tvPermBatterySub, tvPermBatteryAction, nb);

        boolean alive = KeepAliveService.ALIVE;
        dotKeepAlive.setBackgroundResource(alive ? R.drawable.dot_ok : R.drawable.dot_bad);
        tvKeepAliveSub.setText(alive ? R.string.keepalive_desc_on : R.string.keepalive_desc_off);
        tvKeepAliveAction.setText(alive ? "" : getString(R.string.action_restart));
    }

    /**
     * 一条权限行：缺权限 = 红点 + 灰色「未授权」副标题 + 蓝色「去授权」；
     * 已授权 = 绿点 + 「已授权」副标题，右侧按钮清空。
     */
    private void applyPermRow(View dot, TextView sub, TextView action, boolean missing) {
        dot.setBackgroundResource(missing ? R.drawable.dot_bad : R.drawable.dot_ok);
        if (missing) {
            sub.setText(R.string.perm_state_missing);
            action.setText(R.string.action_grant);
        } else {
            sub.setText(R.string.perm_state_ok);
            action.setText("");
        }
    }

    // ==================== 第 3 页：关于 ====================

    private void bindAboutPage() {
        tvAppVer = findViewById(R.id.tvAppVer);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);

        String ver = "?";
        int code = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = Build.VERSION.SDK_INT >= 28
                    ? (int) pi.getLongVersionCode()
                    : pi.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {}
        tvAppVer.setText(getString(R.string.ver_fmt, ver, code));

        tvDeviceInfo.setText(Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onResume() {
        super.onResume();
        // 每次进应用（包括从系统设置页返回）都重新检查并索要权限
        ensurePermissions();
        if (!needNotif()) {
            VolumeNotifier.post(this);
        }
        startKeepAlive();
        refreshVolumeUi();          // 期间音量可能被别处改过，进来先对齐
        refreshSettingsUi();
        autoStartOverlayIfReady();
        // 自动开起悬浮窗是异步的（服务起来后 RUNNING 才变 true），晚一点再刷一次开关状态
        swOverlay.postDelayed(this::refreshVolumeUi, 800);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 避免 Activity 销毁时窗口泄漏
        if (permDialog != null && permDialog.isShowing()) {
            permDialog.dismiss();
        }
        permDialog = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (volumeWatcher != null) {
            try { unregisterReceiver(volumeWatcher); } catch (Exception ignored) {}
        }
    }

    // ==================== 权限 ====================

    private boolean needOverlay() {
        return !Settings.canDrawOverlays(this);
    }

    /** 通知权限是 Android 13（API 33）才有的；低版本系统自动授予 */
    private boolean needNotif() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED;
    }

    /** 刷新权限状态并在缺权限时弹窗索要 */
    private void ensurePermissions() {
        final boolean no = needOverlay();
        final boolean nn = needNotif();
        refreshSettingsUi();
        if (no || nn) {
            askDialog(no, nn);
        }
    }

    private void askDialog(boolean no, boolean nn) {
        if (permDialog != null && permDialog.isShowing()) return;

        StringBuilder sb = new StringBuilder();
        if (no) sb.append(getString(R.string.perm_need_overlay)).append("\n\n");
        if (nn) sb.append(getString(R.string.perm_need_notif));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.perm_dialog_title)
                .setMessage(sb.toString().trim())
                .setNegativeButton(R.string.perm_later, null);
        if (no) {
            b.setPositiveButton(R.string.perm_go_overlay, (d, w) -> openOverlaySettings());
            if (nn) {
                b.setNeutralButton(R.string.perm_go_notif, (d, w) -> requestNotif());
            }
        } else {
            b.setPositiveButton(R.string.perm_go_notif, (d, w) -> requestNotif());
        }

        permDialog = b.create();
        permDialog.show();
    }

    /** 悬浮窗权限只能跳系统设置页手动给 */
    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception ignored) {}
        }
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIF) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            VolumeNotifier.post(this);
        }
        refreshSettingsUi();
    }

    // ==================== 悬浮窗 ====================

    private SharedPreferences prefs() {
        return getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    /** 开关悬浮窗。以用户意图为准，同时记下「是否手动关过」避免下次进应用又自动开 */
    private void setOverlayEnabled(boolean enable) {
        if (enable && needOverlay()) {
            // 没权限先去授权，回来再点一次
            swProgrammatic = true;
            swOverlay.setChecked(false);
            swProgrammatic = false;
            openOverlaySettings();
            return;
        }
        prefs().edit()
                .putBoolean(OverlayService.KEY_ENABLED, enable)
                .putBoolean(OverlayService.KEY_OFF_MANUALLY, !enable)
                .apply();
        if (enable) {
            startService(new Intent(this, OverlayService.class));
        } else {
            stopService(new Intent(this, OverlayService.class));
        }
        // 服务是异步起来的，稍等一下再刷新开关状态
        swOverlay.postDelayed(this::refreshVolumeUi, 400);
    }

    /**
     * 授权过悬浮窗权限就自动把悬浮窗开起来（除非用户手动关过）。
     * 这样它从一授权起就是全局可用的，不用专门切回主界面点开关。
     */
    private void autoStartOverlayIfReady() {
        if (needOverlay()) return;                       // 还没授权
        if (OverlayService.RUNNING) return;              // 已经开着
        if (prefs().getBoolean(OverlayService.KEY_OFF_MANUALLY, false)) return; // 用户手动关过
        prefs().edit().putBoolean(OverlayService.KEY_ENABLED, true).apply();
        startService(new Intent(this, OverlayService.class));
    }

    // ==================== 后台保活 ====================

    /** 拉起保活前台服务：常驻通知 + 开机自启 + 被划掉自动回来 */
    private void startKeepAlive() {
        if (KeepAliveService.ALIVE) return;
        try {
            startForegroundService(new Intent(this, KeepAliveService.class));
        } catch (Exception ignored) {}
    }

    // ==================== 电池白名单 ====================

    /** 电池优化白名单没加入 = 系统在后台可能杀它（防杀关键一步） */
    private boolean needBatteryExempt() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return !pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void requestBatteryExempt() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    // ==================== 调节对象（媒体 / 铃声） ====================

    /** 选中某一路：写进 SharedPreferences + 广播，通知栏/状态栏/悬浮窗全部同步 */
    private void pickStream(int idx) {
        Streams.set(this, idx);
        syncStreamUi();
        refreshVolumeUi();
        VolumeNotifier.post(this);
    }

    /**
     * 高亮当前选中的那一路。
     * 用 setSelected + XML 里的 selector 换背景和文字色，不在代码里动 background，
     * 这样按钮形状和按压反馈都还在（之前直接 setBackgroundColor 就是这么把界面玩坏的）。
     */
    private void syncStreamUi() {
        if (btnStreamMedia == null) return;   // 布局还没 inflate
        int cur = Streams.current(this);
        btnStreamMedia.setSelected(cur == Streams.MEDIA);
        btnStreamRing.setSelected(cur == Streams.RING);
        tvStreamName.setText(Streams.label(this));
    }
}
