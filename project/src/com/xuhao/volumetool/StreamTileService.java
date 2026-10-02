package com.xuhao.volumetool;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 状态栏快捷设置磁贴：「媒体 / 铃声」二选一，点一下就换。
 * 它本身不存状态——读的是 {@link Streams} 那份共享状态，
 * 所以在通知里切到铃声，这块磁贴下次展开就显示铃声，不会各说各话。
 */
public class StreamTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        Streams.next(this);            // 媒体 ⇄ 铃声，其余界面由广播同步
        update();
        try { VolumeNotifier.post(this); } catch (Exception ignored) {}
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;

        int vol = VolumeHelper.getVolume(this);
        int max = VolumeHelper.getMax(this);
        boolean muted = VolumeHelper.isMuted(this);

        tile.setLabel(getString(R.string.tile_stream_label));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 副标题直接写清楚「现在调的是谁 + 当前音量」
            tile.setSubtitle(muted
                    ? getString(R.string.tile_subtitle_muted, Streams.label(this))
                    : getString(R.string.tile_subtitle, Streams.label(this), vol, max));
        }
        // 调铃声时点亮，调媒体时普通色，一眼能看出当前在哪一路
        tile.setState(Streams.current(this) == Streams.RING
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
