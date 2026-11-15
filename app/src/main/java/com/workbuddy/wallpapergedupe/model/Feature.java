package com.workbuddy.wallpapergedupe.model;

/**
 * 图像特征向量（全部由 32x32 灰度缩略图导出，单张约 4.2 KB，几千张也不会 OOM）。
 *
 * fullHash / normHash / flipHash : 64bit DCT 感知哈希，抗压缩、抗缩放、抗亮度线性变化
 * dHash                          : 64bit 差分哈希，对锐化/边缘变化更敏感，作互补
 * cropHashes[8]                  : 8 种中心/四边裁剪视图的 pHash，用于识别「裁剪变体」
 * gridHashes[9]                  : 3x3 分块 dHash，用于局部结构比对
 * normVector[1024]              : 均值 0 / 方差 1 的归一化灰度，用于归一化互相关 NCC
 * colorHist[20]                 : HSV 色调 12 桶 + 饱和度 4 桶 + 明度 4 桶（低权重）
 * aspectLog                     : log(宽/高)，用于判断是否为同一次裁切
 */
public class Feature {
    public long fullHash;
    public long normHash;
    public long flipHash;
    public long dHash;
    public long[] cropHashes = new long[8];
    public long[] gridHashes = new long[9];
    public float[] normVector = new float[32 * 32];
    public float[] colorHist = new float[20];
    public float aspectLog;
}
