package com.xuhao.volumetool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * 保活闹钟接收器：保活服务被划掉/杀掉后，闹钟叫醒它把服务拉回来，
 * 并且如果悬浮窗之前是开着的，顺带恢复悬浮窗。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // 1) 拉回保活前台服务
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (Exception ignored) {}

        // 2) 悬浮窗之前是开着的就恢复
        SharedPreferences sp = context.getSharedPreferences(
                OverlayService.PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(OverlayService.KEY_ENABLED, false)
                && !OverlayService.RUNNING
                && Settings.canDrawOverlays(context)) {
            try {
                context.startService(new Intent(context, OverlayService.class));
            } catch (Exception ignored) {}
        }
    }
}