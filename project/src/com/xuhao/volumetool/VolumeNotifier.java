package com.xuhao.volumetool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** 常驻通知：内容区显示当前媒体音量，操作按钮可调音量/静音。 */
public final class VolumeNotifier {

    public static final String CHANNEL_ID = "volume_control";
    /** 悬浮窗前台服务复用同一条通知，避免多出一条 */
    public static final int NOTIF_ID = 1001;

    private VolumeNotifier() {}

    public static void ensureChannel(Context c) {
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                c.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.notif_channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** 调试用，抓日志：adb logcat -s VT-Volume:VT-Overlay:VT-Notif:VT-Keep */
    private static final String TAG = "VT-Notif";

    /** 上一次贴出去的内容签名，用来判断"这次到底变没变" */
    private static volatile String lastSig = null;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 刷新通知。做了两件事：
     *  1. 合并：连续多次调用只在主线程真正贴一次（音量大改会同时触发直刷和广播刷）
     *  2. 比对：内容跟上次一模一样就不 notify —— 无意义的重复 notify 会让部分 ROM
     *     直接丢弃后面那次更新，看起来就是"通知不刷新"，这里从源头避免。
     */
    public static void post(Context c) {
        final Context app = c.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(app, false);
        } else {
            MAIN.post(() -> apply(app, false));
        }
    }

    /** 强制贴一次（切了媒体/铃声、轮询发现变了时用），不做内容比对 */
    public static void force(Context c) {
        final Context app = c.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(app, true);
        } else {
            MAIN.post(() -> apply(app, true));
        }
    }

    private static void apply(Context c, boolean force) {
        String name = Streams.label(c);
        int vol = VolumeHelper.getVolume(c);
        int max = VolumeHelper.getMax(c);
        boolean muted = VolumeHelper.isMuted(c);
        String sig = name + "|" + vol + "/" + max + "|" + (muted ? "M" : "-");
        if (!force && sig.equals(lastSig)) {
            android.util.Log.d(TAG, "notif skip (unchanged) " + sig);
            return;
        }
        lastSig = sig;
        android.util.Log.d(TAG, "notif post " + sig + (force ? " [force]" : ""));
        try {
            ensureChannel(c);
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, build(c, name, vol, max, muted));
        } catch (Throwable ignored) {}
    }

    public static void cancel(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancel(NOTIF_ID);
    }

    /** 给前台服务用的：现算一遍当前状态 */
    public static Notification build(Context c) {
        return build(c, Streams.label(c), VolumeHelper.getVolume(c),
                VolumeHelper.getMax(c), VolumeHelper.isMuted(c));
    }

    public static Notification build(Context c, String name, int vol, int max, boolean muted) {
        // FLAG_UPDATE_CURRENT | FLAG_MUTABLE — 比单纯的 IMMUTABLE 兼容性更好
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;

        Intent upIntent = new Intent(c, VolumeReceiver.class).setAction(VolumeReceiver.ACTION_UP);
        Intent downIntent = new Intent(c, VolumeReceiver.class).setAction(VolumeReceiver.ACTION_DOWN);
        Intent muteIntent = new Intent(c, VolumeReceiver.class)
                .setAction(VolumeReceiver.ACTION_MUTE_TOGGLE);
        Intent switchIntent = new Intent(c, VolumeReceiver.class)
                .setAction(VolumeReceiver.ACTION_STREAM_TOGGLE);

        PendingIntent upPi = PendingIntent.getBroadcast(c, 1, upIntent, flags);
        PendingIntent downPi = PendingIntent.getBroadcast(c, 2, downIntent, flags);
        PendingIntent mutePi = PendingIntent.getBroadcast(c, 3, muteIntent, flags);
        PendingIntent switchPi = PendingIntent.getBroadcast(c, 4, switchIntent, flags);

        return new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_volume_up)
                .setContentTitle(
                        muted
                                ? c.getString(R.string.notif_title_muted, name)
                                : c.getString(R.string.notif_title, name, vol, max))
                .setContentText(c.getString(R.string.notif_text, name))
                .setOngoing(true)
                .setOnlyAlertOnce(true)   // 内容变了就更新，但别每次都当新通知提醒一遍
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // 按钮顺序有讲究：通知折叠态只显示前 3 个（系统限制，第 4 个要展开才看得到），
                // 所以把「切换媒体/铃声」排在静音前面，不展开也能切。
                .addAction(
                        new Notification.Action.Builder(
                                        R.drawable.ic_volume_up,
                                        c.getString(R.string.action_up),
                                        upPi)
                                .build())
                .addAction(
                        new Notification.Action.Builder(
                                        R.drawable.ic_volume_down,
                                        c.getString(R.string.action_down),
                                        downPi)
                                .build())
                .addAction(
                        new Notification.Action.Builder(
                                        R.drawable.ic_stream,
                                        c.getString(R.string.action_switch, Streams.nextLabel(c)),
                                        switchPi)
                                .build())
                .addAction(
                        new Notification.Action.Builder(
                                        muted
                                                ? R.drawable.ic_volume_up
                                                : R.drawable.ic_volume_off,
                                        muted
                                                ? c.getString(R.string.action_unmute)
                                                : c.getString(R.string.action_mute),
                                        mutePi)
                                .build())
                .build();
    }
}