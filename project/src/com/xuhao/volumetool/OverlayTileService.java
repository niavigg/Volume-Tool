package com.xuhao.volumetool;

import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：全局开关音量悬浮窗。
 * 任何页面下拉快捷设置都能点它开/关悬浮窗，不用再回主界面。
 */
public class OverlayTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (OverlayService.RUNNING) {
            getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(OverlayService.KEY_ENABLED, false)
                    .putBoolean(OverlayService.KEY_OFF_MANUALLY, true)
                    .apply();
            stopService(new Intent(this, OverlayService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                // 没权限先去系统设置授权；回来后 onStartListening 会再刷状态
                try {
                    startActivityAndCollapse(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
                return;
            }
            getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(OverlayService.KEY_ENABLED, true)
                    .putBoolean(OverlayService.KEY_OFF_MANUALLY, false)
                    .apply();
            startService(new Intent(this, OverlayService.class));
        }
        // 服务异步起/停，稍等再刷磁贴状态
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::update, 400);
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(getString(R.string.tile_overlay_label));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(OverlayService.RUNNING
                    ? getString(R.string.tile_overlay_on)
                    : getString(R.string.tile_overlay_off));
        }
        tile.setState(OverlayService.RUNNING ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}