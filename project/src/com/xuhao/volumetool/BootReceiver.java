package com.xuhao.volumetool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * 开机自启：开机/应用更新后拉起保活前台服务（应用就活了）、
 * 恢复常驻通知，如果悬浮窗之前是开着的也恢复悬浮窗。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }

        // 1) 保活服务（开机自启的核心）：BOOT_COMPLETED 是 Android 允许的拉起时机
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (Exception ignored) {}

        // 2) 常驻通知
        VolumeNotifier.post(context);

        // 3) 悬浮窗（之前开着才恢复）
        SharedPreferences sp = context.getSharedPreferences(
                OverlayService.PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(OverlayService.KEY_ENABLED, false)
                && Settings.canDrawOverlays(context)) {
            try {
                context.startService(new Intent(context, OverlayService.class));
            } catch (Exception ignored) {}
        }
    }
}