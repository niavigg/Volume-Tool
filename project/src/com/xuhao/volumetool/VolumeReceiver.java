package com.xuhao.volumetool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收通知栏按钮的广播：调音量 / 静音 / 切换音量类型（媒体 / 铃声）。 */
public class VolumeReceiver extends BroadcastReceiver {

    /** 调试用，抓日志：adb logcat -s VT-Volume:VT-Overlay:VT-Notif:VT-Keep:VT-Recv */
    private static final String TAG = "VT-Recv";

    public static final String ACTION_UP = "com.xuhao.volumetool.ACTION_VOLUME_UP";
    public static final String ACTION_DOWN = "com.xuhao.volumetool.ACTION_VOLUME_DOWN";
    public static final String ACTION_MUTE_TOGGLE = "com.xuhao.volumetool.ACTION_MUTE_TOGGLE";
    public static final String ACTION_STREAM_TOGGLE = "com.xuhao.volumetool.ACTION_STREAM_TOGGLE";

    /** 兜底用：万一 ROM 把 action 弄丢，靠这个 extra 判断方向 */
    public static final String EXTRA_DIR = "dir";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        android.util.Log.d(TAG, "onReceive action=" + a);

        if (ACTION_UP.equals(a)) {
            VolumeHelper.step(context, +1, false);
        } else if (ACTION_DOWN.equals(a)) {
            VolumeHelper.step(context, -1, false);
        } else if (ACTION_MUTE_TOGGLE.equals(a)) {
            VolumeHelper.setMuted(context, !VolumeHelper.isMuted(context));
        } else if (ACTION_STREAM_TOGGLE.equals(a)) {
            Streams.next(context);
            VolumeNotifier.force(context);   // 换路后标题文字必变，强制重贴
        } else {
            // 兜底：action 丢了就按 extra 里的方向调
            int dir = intent.getIntExtra(EXTRA_DIR, 0);
            if (dir > 0) {
                VolumeHelper.step(context, +1, false);
            } else if (dir < 0) {
                VolumeHelper.step(context, -1, false);
            }
        }

        // 点了通知按钮说明用户在用，顺手保活（放最后，别挡住上面的处理）
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (Exception ignored) {}
    }
}