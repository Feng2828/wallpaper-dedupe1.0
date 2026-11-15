package com.workbuddy.wallpapergedupe.model;

import android.net.Uri;

/**
 * 一张被扫描到的壁纸。只持有元数据与 content:// URI，不持有 Bitmap。
 */
public class ImageEntry {
    public final Uri uri;
    public final String name;
    /** SAF 不给真实绝对路径，这里记录目录提示 + documentId，便于人工定位。 */
    public final String pathHint;

    public long size;
    public long modified;
    public int width;
    public int height;

    /** 由 FeatureExtractor 填充的特征向量。 */
    public Feature feature;

    /** 用户是否勾选进「待删除清单」。默认 false，程序不会替用户勾选。 */
    public boolean marked;
    /** 与本组建议保留项的相似度 0..1。 */
    public float simToKeeper;
    public int groupIndex = -1;

    public ImageEntry(Uri uri, String name, String pathHint, long size, long modified) {
        this.uri = uri;
        this.name = (name == null || name.length() == 0) ? "(未命名)" : name;
        this.pathHint = pathHint == null ? "" : pathHint;
        this.size = size;
        this.modified = modified;
    }

    public long pixels() {
        return (long) Math.max(0, width) * Math.max(0, height);
    }
}
