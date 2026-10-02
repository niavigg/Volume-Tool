package com.xuhao.volumetool;

/** 快捷设置磁贴：音量 +（点一下媒体音量加一格）。 */
public class VolumeUpTileService extends VolumeTileBase {

    @Override
    protected int direction() {
        return +1;
    }
}