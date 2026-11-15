package com.workbuddy.wallpapergedupe.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import com.workbuddy.wallpapergedupe.model.Feature;
import com.workbuddy.wallpapergedupe.model.ImageEntry;

import java.io.InputStream;
import java.util.Arrays;

/**
 * 纯 Java 特征提取，不依赖 OpenCV / NDK / 任何第三方图像库。
 *
 * 思路：把每张图降采样成 32x32 灰度矩阵，从「整体 + 多种裁剪 + 3x3 分块」多个视角
 * 计算感知哈希，再配合均值方差归一化后的灰度向量做精细相关性比对。
 * 这样对压缩、缩放、亮度/对比度（调色）、中心裁剪都具备一定不变性。
 */
public final class FeatureExtractor {

    private static final int N = 32;

    /** DCT 余弦表：cos((2x+1) * u * PI / 2N)，预计算避免每张图几万次 Math.cos。 */
    private static final double[][] COS = new double[8][N];

    static {
        for (int u = 0; u < 8; u++) {
            for (int x = 0; x < N; x++) {
                COS[u][x] = Math.cos((2 * x + 1) * u * Math.PI / (2.0 * N));
            }
        }
    }

    /** 8 种裁剪视图（left, top, right, bottom，归一化坐标）。 */
    private static final float[][] CROPS = new float[][]{
            {0.05f, 0.05f, 0.95f, 0.95f},  // 中心 90%
            {0.10f, 0.10f, 0.90f, 0.90f},  // 中心 80%
            {0.15f, 0.15f, 0.85f, 0.85f},  // 中心 70%
            {0.20f, 0.20f, 0.80f, 0.80f},  // 中心 60%
            {0.00f, 0.00f, 1.00f, 0.82f},  // 去底部（适配不同屏幕高度）
            {0.00f, 0.18f, 1.00f, 1.00f},  // 去顶部
            {0.00f, 0.00f, 0.82f, 1.00f},  // 去右侧
            {0.18f, 0.00f, 1.00f, 1.00f}   // 去左侧
    };

    private FeatureExtractor() {
    }

    /** 成功返回 true 并写入 e.width / e.height / e.feature。解码失败或过小图片返回 false。 */
    public static boolean fill(Context ctx, ImageEntry e) {
        // 第一遍：只读尺寸，不分配像素内存
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(e.uri)) {
            if (in == null) {
                return false;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Throwable t) {
            return false;
        }
        int ow = bounds.outWidth;
        int oh = bounds.outHeight;
        if (ow <= 0 || oh <= 0) {
            return false;
        }
        e.width = ow;
        e.height = oh;
        if (Math.min(ow, oh) < 100) {
            return false; // 图标 / 缩略图 / 头像
        }
        if (e.size > 0 && e.size < 8 * 1024L && Math.max(ow, oh) < 600) {
            return false;
        }

        // 第二遍：按 2 的幂降采样，最长边控制在 192px 左右
        int sample = 1;
        int longest = Math.max(ow, oh);
        while (longest / sample > 192) {
            sample <<= 1;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = null;
        try (InputStream in = ctx.getContentResolver().openInputStream(e.uri)) {
            if (in == null) {
                return false;
            }
            bmp = BitmapFactory.decodeStream(in, null, opt);
        } catch (Throwable t) {
            return false;
        }
        if (bmp == null) {
            return false;
        }

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, N, N, true);
        bmp.recycle();
        int[] px = new int[N * N];
        scaled.getPixels(px, 0, N, 0, 0, N, N);
        scaled.recycle();

        float[][] gray = new float[N][N];
        float[] colorHist = new float[20];
        float[] hsv = new float[3];
        double sum = 0.0;
        double sq = 0.0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            float y = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
            gray[i / N][i % N] = y;
            sum += y;
            sq += (double) y * y;
            if ((i & 3) == 0) { // 每 4 个像素采样一次色彩，节省时间
                Color.RGBToHSV(r, g, b, hsv);
                int hb = Math.min(11, (int) (hsv[0] / 30.0f));
                int sb = Math.min(3, (int) (hsv[1] * 4.0f));
                int vb = Math.min(3, (int) (hsv[2] * 4.0f));
                colorHist[hb] += 1.0f;
                colorHist[12 + sb] += 1.0f;
                colorHist[16 + vb] += 1.0f;
            }
        }
        normalize(colorHist, 0, 12);
        normalize(colorHist, 12, 16);
        normalize(colorHist, 16, 20);

        // 均值 0 / 方差 1 归一化：抵消整体亮度与对比度调整（调色变体）
        float mean = (float) (sum / (N * N));
        double var = sq / (N * N) - (double) mean * mean;
        float std = (float) Math.sqrt(Math.max(var, 1e-6));
        float[][] norm = new float[N][N];
        float[] vector = new float[N * N];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float v = (gray[y][x] - mean) / std;
                norm[y][x] = v;
                vector[y * N + x] = v;
            }
        }

        Feature f = new Feature();
        f.fullHash = pHash(gray);
        f.normHash = pHash(norm);
        f.flipHash = pHash(flip(gray));
        f.dHash = dHash(gray);
        for (int i = 0; i < CROPS.length; i++) {
            f.cropHashes[i] = pHash(cropView(gray, CROPS[i]));
        }
        for (int gy = 0; gy < 3; gy++) {
            for (int gx = 0; gx < 3; gx++) {
                f.gridHashes[gy * 3 + gx] = dHash(cropView(gray, tileRect(gy, gx)));
            }
        }
        f.normVector = vector;
        f.colorHist = colorHist;
        f.aspectLog = (float) Math.log((double) ow / (double) oh);
        e.feature = f;
        return true;
    }

    /** 64bit DCT 感知哈希：取 8x8 低频系数与中位数比较。 */
    private static long pHash(float[][] g) {
        double[][] row = new double[8][N];
        for (int u = 0; u < 8; u++) {
            for (int x = 0; x < N; x++) {
                double s = 0.0;
                for (int y = 0; y < N; y++) {
                    s += g[y][x] * COS[u][y];
                }
                row[u][x] = s;
            }
        }
        double[] v = new double[64];
        for (int u = 0; u < 8; u++) {
            for (int w = 0; w < 8; w++) {
                double s = 0.0;
                for (int x = 0; x < N; x++) {
                    s += row[u][x] * COS[w][x];
                }
                v[u * 8 + w] = s;
            }
        }
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        double median = (sorted[31] + sorted[32]) / 2.0;
        long bits = 0L;
        for (int i = 0; i < 64; i++) {
            bits <<= 1;
            if (v[i] > median) {
                bits |= 1L;
            }
        }
        return bits;
    }

    /** 64bit 差分哈希（8 行 x 8 次水平比较）。 */
    private static long dHash(float[][] g) {
        long bits = 0L;
        for (int y = 0; y < 8; y++) {
            int sy = Math.min(N - 1, (int) ((y + 0.5f) * N / 8.0f));
            for (int x = 0; x < 8; x++) {
                int sx0 = Math.min(N - 1, (int) ((x + 0.5f) * N / 9.0f));
                int sx1 = Math.min(N - 1, (int) ((x + 1.5f) * N / 9.0f));
                bits = (bits << 1) | (g[sy][sx0] > g[sy][sx1] ? 1L : 0L);
            }
        }
        return bits;
    }

    /** 水平镜像，应对左右翻转变体。 */
    private static float[][] flip(float[][] g) {
        float[][] out = new float[N][N];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                out[y][x] = g[y][N - 1 - x];
            }
        }
        return out;
    }

    /** 按归一化矩形做双线性重采样，输出仍是 N x N。 */
    private static float[][] cropView(float[][] src, float[] r) {
        float[][] out = new float[N][N];
        for (int y = 0; y < N; y++) {
            float sy = (r[1] + (r[3] - r[1]) * (y + 0.5f) / N) * (N - 1);
            int y0 = Math.max(0, Math.min(N - 1, (int) Math.floor(sy)));
            int y1 = Math.min(N - 1, y0 + 1);
            float fy = sy - y0;
            for (int x = 0; x < N; x++) {
                float sx = (r[0] + (r[2] - r[0]) * (x + 0.5f) / N) * (N - 1);
                int x0 = Math.max(0, Math.min(N - 1, (int) Math.floor(sx)));
                int x1 = Math.min(N - 1, x0 + 1);
                float fx = sx - x0;
                float v = src[y0][x0] * (1 - fx) * (1 - fy)
                        + src[y0][x1] * fx * (1 - fy)
                        + src[y1][x0] * (1 - fx) * fy
                        + src[y1][x1] * fx * fy;
                out[y][x] = v;
            }
        }
        return out;
    }

    /** 3x3 分块，四周外扩 4% 以提升裁剪容错。 */
    private static float[] tileRect(int gy, int gx) {
        float w = 1.0f / 3.0f;
        float h = 1.0f / 3.0f;
        return new float[]{
                Math.max(0.0f, gx * w - 0.04f),
                Math.max(0.0f, gy * h - 0.04f),
                Math.min(1.0f, (gx + 1) * w + 0.04f),
                Math.min(1.0f, (gy + 1) * h + 0.04f)
        };
    }

    private static void normalize(float[] a, int from, int to) {
        float sum = 0.0f;
        for (int i = from; i < to; i++) {
            sum += a[i];
        }
        if (sum <= 0.0f) {
            return;
        }
        for (int i = from; i < to; i++) {
            a[i] /= sum;
        }
    }
}
