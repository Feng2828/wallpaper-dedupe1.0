package com.workbuddy.wallpapergedupe.core;

import com.workbuddy.wallpapergedupe.model.DuplicateGroup;
import com.workbuddy.wallpapergedupe.model.Feature;
import com.workbuddy.wallpapergedupe.model.ImageEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 相似度评分 + 并查集分组。
 *
 * 性能设计：两两比对是 O(n^2)，所以分两级。
 *   1) 粗筛层：只做约 45 次 64bit 位计数，绝大多数图片对在这里被直接淘汰；
 *   2) 精细层：只有粗筛达标的候选才计算分块匹配、1024 维 NCC、色彩与宽高比。
 * 2500 张约为 310 万对，粗筛在手机上是秒级到十几秒级。
 */
public final class SimilarityEngine {

    public interface Progress {
        void onProgress(String message, int done, int total);
    }

    private SimilarityEngine() {
    }

    private static float hashSim(long a, long b) {
        return 1.0f - Long.bitCount(a ^ b) / 64.0f;
    }

    /**
     * 返回 0..1 的相似度。threshold 传 0 表示关闭粗筛（用于组内展示时重算）。
     */
    public static float score(Feature a, Feature b, float threshold) {
        if (a == null || b == null) {
            return 0.0f;
        }

        // --- 粗筛层 ---
        float primary = Math.max(
                Math.max(hashSim(a.fullHash, b.fullHash), hashSim(a.normHash, b.normHash)),
                Math.max(hashSim(a.flipHash, b.fullHash), hashSim(a.dHash, b.dHash)));

        // 全图 vs 裁剪视图的双向交叉匹配：识别「由某张大图裁出来的小图」
        float cross = 0.0f;
        for (int i = 0; i < a.cropHashes.length; i++) {
            cross = Math.max(cross, hashSim(a.fullHash, b.cropHashes[i]));
            cross = Math.max(cross, hashSim(b.fullHash, a.cropHashes[i]));
            cross = Math.max(cross, hashSim(a.normHash, b.cropHashes[i]));
            cross = Math.max(cross, hashSim(b.normHash, a.cropHashes[i]));
            cross = Math.max(cross, hashSim(a.cropHashes[i], b.cropHashes[i]));
        }
        cross *= 0.96f; // 裁剪匹配略打折，抑制误报
        boolean cropped = cross > primary + 0.02f;
        float best = Math.max(primary, cross);

        if (best < threshold - 0.26f) {
            return 0.0f;
        }

        // --- 精细层 ---
        float grid = gridSim(a.gridHashes, b.gridHashes);
        float ncc = Math.max(0.0f, ncc(a.normVector, b.normVector));
        float aspect = 1.0f - Math.min(1.0f, Math.abs(a.aspectLog - b.aspectLog) / 1.1f);
        float color = colorSim(a.colorHist, b.colorHist);

        float s;
        if (cropped) {
            // 裁剪变体：像素不再对齐，NCC 几乎失效，主要依赖「全图↔裁剪」结构哈希。
            // 系数经典型场景数值校准：同时覆盖纯裁剪与「压缩 + 裁剪」复合变体。
            s = 0.72f * best + 0.14f * grid + 0.03f * ncc + 0.08f * aspect + 0.03f * color;
        } else {
            s = 0.40f * best + 0.18f * grid + 0.24f * ncc + 0.10f * aspect + 0.08f * color;
        }
        return s > 1.0f ? 1.0f : (s < 0.0f ? 0.0f : s);
    }

    /** 同位置分块相似度 + 允许错位的最佳分块匹配，兼顾裁剪/偏移。 */
    private static float gridSim(long[] a, long[] b) {
        float same = 0.0f;
        for (int i = 0; i < 9; i++) {
            same += hashSim(a[i], b[i]);
        }
        same /= 9.0f;
        float best = 0.0f;
        for (int i = 0; i < 9; i++) {
            float m = 0.0f;
            for (int j = 0; j < 9; j++) {
                m = Math.max(m, hashSim(a[i], b[j]));
            }
            best += m;
        }
        best /= 9.0f;
        return Math.max(same, best * 0.92f);
    }

    /** 归一化互相关（输入已标准化，等价于余弦相似度）。 */
    private static float ncc(float[] a, float[] b) {
        double dot = 0.0;
        double sa = 0.0;
        double sb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            sa += (double) a[i] * a[i];
            sb += (double) b[i] * b[i];
        }
        if (sa <= 0.0 || sb <= 0.0) {
            return 0.0f;
        }
        return (float) (dot / Math.sqrt(sa * sb));
    }

    /** 色彩直方图 L1 距离，理论最大值 6（三段各自归一化）。低权重，不惩罚调色变体。 */
    private static float colorSim(float[] a, float[] b) {
        float d = 0.0f;
        for (int i = 0; i < a.length; i++) {
            d += Math.abs(a[i] - b[i]);
        }
        float s = 1.0f - d / 6.0f;
        return s > 0.0f ? s : 0.0f;
    }

    /**
     * 按阈值把相似度连边，用并查集聚成组。
     * 每组会计算一个「建议保留」项（分辨率优先，其次文件更大），但不会自动勾选任何项。
     */
    public static List<DuplicateGroup> group(List<ImageEntry> items, float threshold,
                                              Progress progress, AtomicBoolean cancel) {
        int n = items.size();
        List<DuplicateGroup> empty = new ArrayList<>();
        if (n < 2) {
            return empty;
        }
        for (int i = 0; i < n; i++) {
            ImageEntry e = items.get(i);
            e.groupIndex = -1;
            e.marked = false;
            e.simToKeeper = 0.0f;
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        int totalPairs = n * (n - 1) / 2;
        int done = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (cancel != null && cancel.get()) {
                    return empty;
                }
                float s = score(items.get(i).feature, items.get(j).feature, threshold);
                if (s >= threshold) {
                    union(parent, i, j);
                }
                done++;
                if (progress != null && (done % 3000 == 0 || done == totalPairs)) {
                    progress.onProgress("比对中 " + done + " / " + totalPairs, done, totalPairs);
                }
            }
        }

        Map<Integer, DuplicateGroup> map = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            DuplicateGroup g = map.get(root);
            if (g == null) {
                g = new DuplicateGroup();
                map.put(root, g);
            }
            g.items.add(items.get(i));
        }

        List<DuplicateGroup> groups = new ArrayList<>();
        for (DuplicateGroup g : map.values()) {
            if (g.items.size() < 2) {
                continue;
            }
            ImageEntry keeper = g.items.get(0);
            for (ImageEntry e : g.items) {
                if (e.pixels() > keeper.pixels()
                        || (e.pixels() == keeper.pixels() && e.size > keeper.size)) {
                    keeper = e;
                }
            }
            g.keeper = keeper;
            final ImageEntry bestOne = keeper;

            float sum = 0.0f;
            int cnt = 0;
            for (ImageEntry e : g.items) {
                if (e == bestOne) {
                    e.simToKeeper = 1.0f;
                    continue;
                }
                e.simToKeeper = score(e.feature, bestOne.feature, 0.0f);
                sum += e.simToKeeper;
                cnt++;
            }
            g.avgScore = cnt > 0 ? sum / cnt : 1.0f;

            Collections.sort(g.items, (x, y) -> {
                if (x == bestOne) {
                    return -1;
                }
                if (y == bestOne) {
                    return 1;
                }
                return Float.compare(y.simToKeeper, x.simToKeeper);
            });
            groups.add(g);
        }

        Collections.sort(groups, (x, y) -> Integer.compare(y.items.size(), x.items.size()));
        for (int i = 0; i < groups.size(); i++) {
            DuplicateGroup g = groups.get(i);
            g.index = i + 1;
            for (ImageEntry e : g.items) {
                e.groupIndex = i;
            }
        }
        return groups;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a);
        int rb = find(p, b);
        if (ra != rb) {
            p[rb] = ra;
        }
    }
}
