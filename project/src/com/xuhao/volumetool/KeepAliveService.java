package com.xuhao.volumetool;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;

/**
 * 后台保活服务：一个常驻前台服务，把整个应用钉在后台。
 *  · 借用常驻音量通知当前台通知（不额外多一条通知）
 *  · 开机自启：BootReceiver 收到 BOOT_COMPLETED 就把它拉起来
 *  · 防杀：被从最近任务划掉（onTaskRemoved）→ 2 秒后用闹钟自动拉回
 *  · START_STICKY：被系统回收后系统会尝试重建
 *  · 音量一变就刷通知里的数字
 */
public class KeepAliveService extends Service {

    /** 主界面用它显示保活状态 */
    public static volatile boolean ALIVE = false;

    private static final long WATCHDOG_MS = 3000L;   // 悬浮窗 + 通知数字兜底检查间隔

    private BroadcastReceiver volumeWatcher;
    private Handler watchdogHandler;
    private Runnable watchdogTask;
    private int lastVol = -1;
    private int lastMax = -1;
    private boolean lastMuted;

    /** 音量变化监听（和首页/悬浮窗同一方案），实时刷新常驻通知 */
    private static final class VolumeWatcher extends BroadcastReceiver {
        private final KeepAliveService s;

        VolumeWatcher(KeepAliveService s) { this.s = s; }

        @Override
        public void onReceive(Context ctx, Intent intent) {
            try {
                if (intent != null && Streams.ACTION_CHANGED.equals(intent.getAction())) {
                    // 换了「调哪一路」：标题文字变了，强制重贴；
                    // 顺手把几个磁贴叫醒重画（磁贴只在展开快捷设置时才活着，必须主动请它刷新）
                    VolumeNotifier.force(s);
                    s.pokeTiles();
                } else {
                    VolumeNotifier.post(s);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ALIVE = true;
        startForegroundCompat();
        registerVolumeWatcher();
        VolumeNotifier.post(this);
        restoreOverlayIfEnabled();   // 进程被杀后被系统重启时，悬浮窗要跟着回来
        startWatchdog();
    }

    /**
     * 兜底保险：如果系统/ROM 把悬浮窗服务掐了而设置里还是"开启"，这里把它重新拉起来。
     * （悬浮窗自己每 3 秒也会自检一次，两边都盯着，退出主界面就不会莫名其妙消失。）
     */
    private static final class Watchdog implements Runnable {
        private final KeepAliveService s;
        private final Handler h;

        Watchdog(KeepAliveService s, Handler h) { this.s = s; this.h = h; }

        @Override
        public void run() {
            s.pollVolume();               // 通知数字兜底：广播收不到时也能自己发现变了
            s.restoreOverlayIfEnabled();
            h.postDelayed(this, WATCHDOG_MS);
        }
    }

    /**
     * 兜底刷新：有些 ROM 根本不发 VOLUME_CHANGED_ACTION，或者广播到不了后台服务，
     * 通知就会一直停在旧数字上。这里直接读当前音量，跟上次记录的不一样就强制重贴一次。
     * （3 秒一次，读两个 int，开销可以忽略）
     */
    private void pollVolume() {
        try {
            int v = VolumeHelper.getVolume(this);
            int m = VolumeHelper.getMax(this);
            boolean mu = VolumeHelper.isMuted(this);
            if (v != lastVol || m != lastMax || mu != lastMuted) {
                lastVol = v;
                lastMax = m;
                lastMuted = mu;
                VolumeNotifier.force(this);
            }
        } catch (Throwable ignored) {}
    }

    private void startWatchdog() {
        watchdogHandler = new Handler(Looper.getMainLooper());
        watchdogTask = new Watchdog(this, watchdogHandler);
        watchdogHandler.postDelayed(watchdogTask, WATCHDOG_MS);
    }

    private void restoreOverlayIfEnabled() {
        try {
            if (!OverlayService.shouldBeOn(this)) return;
            if (OverlayService.RUNNING) return;
            if (!android.provider.Settings.canDrawOverlays(this)) return;
            startService(new Intent(this, OverlayService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;   // 被系统杀掉后，系统负责重建
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务划掉 → 2 秒后自动拉回（防杀）
        scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        ALIVE = false;
        if (watchdogHandler != null && watchdogTask != null) {
            watchdogHandler.removeCallbacks(watchdogTask);
        }
        if (volumeWatcher != null) {
            try { unregisterReceiver(volumeWatcher); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat() {
        try {
            Notification n = VolumeNotifier.build(this);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(VolumeNotifier.NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(VolumeNotifier.NOTIF_ID, n);
            }
        } catch (Throwable ignored) {
            // 前台起不来也不影响 START_STICKY 保活
        }
    }

    private void registerVolumeWatcher() {
        volumeWatcher = new VolumeWatcher(this);
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(Streams.ACTION_CHANGED);   // 切了媒体/铃声也要立刻重画通知和磁贴
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeWatcher, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volumeWatcher, f);
        }
    }

    /** 请系统让两个音量磁贴重新走一次 onStartListening（副标题里的「铃声 3/7」才跟得上） */
    private void pokeTiles() {
        if (Build.VERSION.SDK_INT < 24) return;
        TileService.requestListeningState(this,
                new ComponentName(this, VolumeUpTileService.class));
        TileService.requestListeningState(this,
                new ComponentName(this, VolumeDownTileService.class));
        TileService.requestListeningState(this,
                new ComponentName(this, StreamTileService.class));
    }

    /** 闹钟把自己拉回来；KeepAliveReceiver 里还会顺带恢复悬浮窗 */
    private void scheduleRestart() {
        try {
            Intent i = new Intent(this, KeepAliveReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            // 用非精确闹钟，Android 12+ 不需要 SCHEDULE_EXACT_ALARM 权限
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000, pi);
        } catch (Exception ignored) {}
    }
}