package com.xuhao.volumetool;

import android.content.Context;
import android.media.AudioManager;

/** 音量统一入口：调哪一路（媒体/铃声）由 {@link Streams} 决定，全局一致。 */
public final class VolumeHelper {

    /** 调试用，抓日志：adb logcat -s VT-Volume:VT-Overlay:VT-Notif:VT-Keep */
    private static final String TAG = "VT-Volume";

    private VolumeHelper() {}

    private static AudioManager am(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    public static int getMax(Context c) {
        return am(c).getStreamMaxVolume(Streams.streamType(c));
    }

    public static int getVolume(Context c) {
        return am(c).getStreamVolume(Streams.streamType(c));
    }

    public static boolean isMuted(Context c) {
        try {
            return am(c).isStreamMute(Streams.streamType(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * dir: +1 或 -1；showUi: 是否显示系统音量浮窗。
     *
     * 这里刻意不用 adjustStreamVolume 的相对步进：部分 ROM 会把 ADJUST_LOWER 吞掉
     * （表现就是「音量 −」按了没反应）。改成先读当前值、算出目标值，再 setStreamVolume
     * 精确定位，加和减走同一套逻辑，谁都不会失效。
     */
    public static void step(Context c, int dir, boolean showUi) {
        int stream = Streams.streamType(c);
        AudioManager m = am(c);
        int ui = showUi ? AudioManager.FLAG_SHOW_UI : 0;

        int before = get(m, stream);
        int max = getMax(m, stream);
        int min = getMin(m, stream);
        int target = Math.max(min, Math.min(max, before + dir));

        if (target == before) {   // 已经在端点，没什么可调的
            android.util.Log.d(TAG, "step dir=" + dir + " already at edge " + before + "/" + max);
            VolumeNotifier.post(c);
            return;
        }

        // 静音状态下先解除静音，否则调了听不出变化
        try {
            if (m.isStreamMute(stream)) m.setStreamMute(stream, false);
        } catch (Throwable ignored) {}

        // 逐级尝试，以"写完之后读回来真的变了"为准。
        // 实测 OPPO/ColorOS 上 setStreamVolume 会被静默吞掉（不报错但值不变），
        // adjustStreamVolume 才真的生效；别的 ROM 也可能反过来。这里不猜，一种不行换下一种。
        String how;
        if (trySet(m, stream, target, ui)) {
            how = "set";
        } else if (tryAdjust(m, stream, dir, ui)) {
            how = "adjust";
        } else if (ui == 0 && trySet(m, stream, target, AudioManager.FLAG_SHOW_UI)) {
            how = "set+ui";
        } else if (ui == 0 && tryAdjust(m, stream, dir, AudioManager.FLAG_SHOW_UI)) {
            how = "adjust+ui";
        } else {
            how = "FAILED";
        }

        android.util.Log.d(TAG, "step dir=" + dir + " stream=" + stream + " " + before
                + "->" + target + " after=" + get(m, stream) + " how=" + how);
        VolumeNotifier.post(c);   // 调完立刻刷通知，不等广播
    }

    // ==================== 带校验的底层写入 ====================

    private static int get(AudioManager m, int stream) {
        try { return m.getStreamVolume(stream); } catch (Throwable t) { return -1; }
    }

    private static int getMax(AudioManager m, int stream) {
        try { return m.getStreamMaxVolume(stream); } catch (Throwable t) { return -1; }
    }

    private static int getMin(AudioManager m, int stream) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try { return m.getStreamMinVolume(stream); } catch (Throwable t) { /* 用 0 兜底 */ }
        }
        return 0;
    }

    /** 写进去以后立刻读回来比对：没真的变就当这种写法在本机无效 */
    private static boolean trySet(AudioManager m, int stream, int v, int flags) {
        try {
            m.setStreamVolume(stream, v, flags);
            return get(m, stream) == v;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 相对步进，同样以"值真的动了"为准 */
    private static boolean tryAdjust(AudioManager m, int stream, int dir, int flags) {
        int before = get(m, stream);
        try {
            m.adjustStreamVolume(stream,
                    dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, flags);
            return get(m, stream) != before;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setVolume(Context c, int vol, boolean showUi) {
        int stream = Streams.streamType(c);
        AudioManager m = am(c);
        int ui = showUi ? AudioManager.FLAG_SHOW_UI : 0;
        int before = get(m, stream);
        String how;
        if (before == vol) {
            how = "noop";
        } else if (trySet(m, stream, vol, ui)) {
            how = "set";
        } else if (ui == 0 && trySet(m, stream, vol, AudioManager.FLAG_SHOW_UI)) {
            how = "set+ui";
        } else {
            how = "FAILED";
        }
        android.util.Log.d(TAG, "setVolume " + before + "->" + vol + " after=" + get(m, stream)
                + " how=" + how);
        VolumeNotifier.post(c);
    }

    public static void setMuted(Context c, boolean mute) {
        int stream = Streams.streamType(c);
        AudioManager m = am(c);
        String how = null;
        try {
            m.setStreamMute(stream, mute);
            if (isMuted(c) == mute) how = "mute-api";
        } catch (Throwable ignored) {}

        if (how == null) {
            // 有的 ROM 不支持/不生效 setStreamMute：退回成"置 0"和"恢复一档"
            if (mute) {
                if (trySet(m, stream, 0, 0) || trySet(m, stream, 0, AudioManager.FLAG_SHOW_UI)) {
                    how = "zero";
                }
            } else if (get(m, stream) == 0) {
                if (trySet(m, stream, 1, 0) || tryAdjust(m, stream, +1, 0)) {
                    how = "restore";
                }
            } else {
                how = "already";
            }
            if (how == null) how = "FAILED";
        }
        android.util.Log.d(TAG, "setMuted " + mute + " how=" + how + " vol=" + get(m, stream));
        VolumeNotifier.post(c);
    }
}