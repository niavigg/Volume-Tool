package com.xuhao.volumetool;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

/**
 * 全局「调哪一路音量」：媒体 / 铃声（两路，来回切）。
 * 通知栏、首页、状态栏磁贴、悬浮窗**共用这一个选择**（存在 SharedPreferences 里），
 * 切换时发一条应用内广播，所有界面立刻同步刷新——绝不会出现"通知栏已经是铃声、
 * 首页还在调媒体"的情况。
 */
public final class Streams {

    public static final String PREFS = "volume_tool_prefs";
    public static final String KEY_STREAM = "current_stream";
    /** 应用内广播：音量类型变了 */
    public static final String ACTION_CHANGED = "com.xuhao.volumetool.STREAM_CHANGED";

    public static final int MEDIA = 0;
    public static final int RING = 1;
    public static final int COUNT = 2;

    private static final int[] TYPES = {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
    };

    private Streams() {}

    public static int current(Context c) {
        int i = prefs(c).getInt(KEY_STREAM, MEDIA);
        return (i < 0 || i >= COUNT) ? MEDIA : i;
    }

    /** 给 AudioManager 用的 streamType */
    public static int streamType(Context c) {
        return TYPES[current(c)];
    }

    /** 直接选中某一路（首页按钮 / 状态栏磁贴用），广播同步 */
    public static int set(Context c, int idx) {
        int target = (idx < 0 || idx >= COUNT) ? MEDIA : idx;
        prefs(c).edit().putInt(KEY_STREAM, target).apply();
        try {
            c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
        } catch (Exception ignored) {}
        return target;
    }

    /** 切到另一路（媒体 ⇄ 铃声），广播同步，返回新序号 */
    public static int next(Context c) {
        return set(c, (current(c) + 1) % COUNT);
    }

    /** 当前类型名字：媒体 / 铃声 */
    public static String label(Context c) {
        return label(c, current(c));
    }

    /** 另一路的类型名字（通知按钮上显示「→铃声」用） */
    public static String nextLabel(Context c) {
        return label(c, (current(c) + 1) % COUNT);
    }

    public static String label(Context c, int idx) {
        return idx == RING ? c.getString(R.string.stream_ring)
                           : c.getString(R.string.stream_media);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
