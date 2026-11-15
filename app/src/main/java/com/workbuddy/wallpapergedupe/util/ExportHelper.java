package com.workbuddy.wallpapergedupe.util;

import android.content.Context;

import com.workbuddy.wallpapergedupe.model.DuplicateGroup;
import com.workbuddy.wallpapergedupe.model.ImageEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 只负责生成纯文本清单。整个项目里没有任何 deleteDocument / delete / write 到用户图片的调用。
 */
public final class ExportHelper {

    private ExportHelper() {
    }

    public static String report(List<DuplicateGroup> groups, float threshold) {
        StringBuilder sb = new StringBuilder();
        sb.append("壁纸查重 · 待删除清单（人工核对用）\n");
        sb.append("========================================\n");
        sb.append("生成时间   : ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()))
                .append('\n');
        sb.append("相似度阈值 : ").append(String.format(Locale.CHINA, "%.0f%%", threshold * 100)).append('\n');
        sb.append("设备适配   : Redmi K60E / HyperOS 2\n\n");
        sb.append("【重要声明】\n");
        sb.append("本应用不具备删除文件的能力：未申请任何写入/删除权限，代码中不含任何删除调用。\n");
        sb.append("本文件只是一份文本记录，请自行在文件管理器中逐项核对后，再决定是否删除。\n");
        sb.append("SAF 不提供真实绝对路径，清单中的 content URI 与 documentId 可用于精确定位；\n");
        sb.append("若使用支持 SAF 的文件管理器，也可直接按「位置提示 + 文件名」搜索。\n\n");

        int idx = 0;
        int groupCount = 0;
        long bytes = 0L;
        for (DuplicateGroup g : groups) {
            java.util.List<ImageEntry> marked = new java.util.ArrayList<>();
            for (ImageEntry e : g.items) {
                if (e.marked) {
                    marked.add(e);
                }
            }
            if (marked.isEmpty()) {
                continue;
            }
            groupCount++;
            sb.append("【第 ").append(g.index).append(" 组】共 ").append(g.items.size())
                    .append(" 张 · 平均相似度 ")
                    .append(String.format(Locale.CHINA, "%.1f%%", g.avgScore * 100)).append('\n');
            if (g.keeper != null) {
                sb.append("  建议保留   : ").append(g.keeper.name)
                        .append(" (").append(g.keeper.width).append('×').append(g.keeper.height).append(")\n");
                sb.append("  保留项 URI : ").append(g.keeper.uri).append('\n');
            }
            if (marked.size() == g.items.size()) {
                sb.append("  ⚠ 警告：本组所有图片都被勾选，删除后将不保留任何副本，请务必复核！\n");
            }
            for (ImageEntry e : marked) {
                idx++;
                bytes += Math.max(0L, e.size);
                sb.append("  ").append(idx).append(". ").append(e.name).append('\n');
                sb.append("     content URI      : ").append(e.uri).append('\n');
                sb.append("     位置提示         : ").append(e.pathHint).append('\n');
                sb.append("     尺寸             : ").append(e.width).append('×').append(e.height).append('\n');
                sb.append("     大小             : ").append(formatSize(e.size)).append('\n');
                sb.append("     与建议保留项相似度: ")
                        .append(String.format(Locale.CHINA, "%.1f%%", e.simToKeeper * 100)).append('\n');
                if (e == g.keeper) {
                    sb.append("     备注             : 这是系统建议保留项，但被用户手动勾选\n");
                }
            }
            sb.append('\n');
        }

        sb.append("----------------------------------------\n");
        sb.append("合计：").append(groupCount).append(" 组 / ").append(idx)
                .append(" 个待删除候选，约 ").append(formatSize(bytes)).append(" 可回收空间（按文件大小估算）\n");
        if (idx == 0) {
            sb.append("当前没有任何勾选，清单为空。\n");
        }
        sb.append("再次提醒：本应用不会删除任何文件，删除动作需要你手动完成。\n");
        return sb.toString();
    }

    /** 返回「所有图片都被勾选」的组号，用于导出前的安全提醒；无则返回空串。 */
    public static String fullyMarkedGroupNumbers(List<DuplicateGroup> groups) {
        StringBuilder sb = new StringBuilder();
        for (DuplicateGroup g : groups) {
            int n = 0;
            for (ImageEntry e : g.items) {
                if (e.marked) {
                    n++;
                }
            }
            if (n > 0 && n == g.items.size()) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(g.index);
            }
        }
        return sb.toString();
    }

    public static int countMarked(List<DuplicateGroup> groups) {
        int n = 0;
        for (DuplicateGroup g : groups) {
            for (ImageEntry e : g.items) {
                if (e.marked) {
                    n++;
                }
            }
        }
        return n;
    }

    public static long markedBytes(List<DuplicateGroup> groups) {
        long b = 0L;
        for (DuplicateGroup g : groups) {
            for (ImageEntry e : g.items) {
                if (e.marked) {
                    b += Math.max(0L, e.size);
                }
            }
        }
        return b;
    }

    /** 应用私有目录的备用副本，路径为 Android/data/包名/files/reports/，卸载即清除。 */
    public static File writeLocalCopy(Context ctx, String text) throws IOException {
        File dir = new File(ctx.getExternalFilesDir(null), "reports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建 reports 目录");
        }
        File f = new File(dir, "pending_delete_list.txt");
        write(new FileOutputStream(f), text);
        return f;
    }

    public static void write(OutputStream os, String text) throws IOException {
        Writer w = new OutputStreamWriter(os, "UTF-8");
        try {
            w.write('\ufeff'); // BOM，避免部分 Windows 记事本打开中文乱码
            w.write(text);
            w.flush();
        } finally {
            w.close();
        }
    }

    public static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
    }

    public static String formatSize(long b) {
        if (b <= 0L) {
            return "未知";
        }
        if (b < 1024L) {
            return b + " B";
        }
        if (b < 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f KB", b / 1024.0);
        }
        return String.format(Locale.CHINA, "%.2f MB", b / (1024.0 * 1024.0));
    }
}
