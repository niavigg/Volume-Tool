package com.xuhao.volumetool;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴基类。
 * 标签固定为「音量 +」/「音量 −」（永不混淆两个磁贴），副标题显示当前音量，
 * 静音或音量为 0 时变灰。子类只负责给出方向。
 */
public abstract class VolumeTileBase extends TileService {

    /** 子类返回 +1 或 -1。 */
    protected abstract int direction();

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        // 第三个参数 true：顺带弹出系统音量条，磁贴收起后也有反馈
        VolumeHelper.step(this, direction(), true);
        update();
        VolumeNotifier.post(this);   // 通知里的数字同步刷新
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;

        String name = Streams.label(this);
        int vol = VolumeHelper.getVolume(this);
        int max = VolumeHelper.getMax(this);
        boolean muted = VolumeHelper.isMuted(this);

        tile.setLabel(getString(
                direction() > 0 ? R.string.tile_up_label : R.string.tile_down_label));
        // setSubtitle 是 API 29 才有的，低版本要跳过
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(muted
                    ? getString(R.string.tile_subtitle_muted, name)
                    : getString(R.string.tile_subtitle, name, vol, max));
        }
        tile.setState(muted || vol <= 0 ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.updateTile();
    }
}