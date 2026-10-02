package com.xuhao.volumetool;

/** 快捷设置磁贴：音量 −（点一下媒体音量减一格）。 */
public class VolumeDownTileService extends VolumeTileBase {

    @Override
    protected int direction() {
        return -1;
    }
}