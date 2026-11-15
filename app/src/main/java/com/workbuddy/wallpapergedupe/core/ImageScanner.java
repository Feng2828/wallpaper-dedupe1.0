package com.workbuddy.wallpapergedupe.core;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import com.workbuddy.wallpapergedupe.model.ImageEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 两种图片来源：
 *  A. SAF 目录树（主）：ACTION_OPEN_DOCUMENT_TREE 授权后递归遍历，无需任何存储权限。
 *  B. MediaStore 全部图片（备）：用于 SAF 无法进入 Android/data 时的兜底。
 */
public final class ImageScanner {

    private static final String TAG = "WD/Scanner";
    /** 单次最多扫描的图片数，防止 O(n^2) 比对在手机上过慢；可在 MainActivity 调整。 */
    public static final int DEFAULT_MAX = 2500;

    public interface Progress {
        void onProgress(String message, int done, int total);
    }

    private static final String[] DOC_PROJ = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private ImageScanner() {
    }

    public static List<ImageEntry> scanTrees(Context ctx, List<Uri> trees, int maxCount, Progress progress) {
        List<ImageEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ContentResolver cr = ctx.getContentResolver();
        for (Uri tree : trees) {
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                if (rootId == null) {
                    continue;
                }
                walk(cr, tree, rootId, "", 0, maxCount, out, seen, progress);
            } catch (Throwable t) {
                // 某些厂商 DocumentsProvider 会拒绝特定子目录，跳过即可，不影响整体
                Log.w(TAG, "tree skipped: " + tree + " -> " + t.getMessage());
            }
            if (out.size() >= maxCount) {
                break;
            }
        }
        if (progress != null) {
            progress.onProgress("目录扫描完成，共发现 " + out.size() + " 张图片", out.size(), Math.max(1, out.size()));
        }
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String docId, String relDir,
                             int depth, int maxCount, List<ImageEntry> out,
                             Set<String> seen, Progress progress) {
        if (depth > 12 || out.size() >= maxCount) {
            return;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        List<String> subDirs = new ArrayList<>();
        try {
            // 注意：不要传 sortOrder，相当一部分 DocumentsProvider 不支持排序参数
            c = cr.query(children, DOC_PROJ, null, null, null);
            if (c == null) {
                return;
            }
            int iId = c.getColumnIndex(DOC_PROJ[0]);
            int iName = c.getColumnIndex(DOC_PROJ[1]);
            int iMime = c.getColumnIndex(DOC_PROJ[2]);
            int iSize = c.getColumnIndex(DOC_PROJ[3]);
            int iTime = c.getColumnIndex(DOC_PROJ[4]);

            while (c.moveToNext() && out.size() < maxCount) {
                String id = iId >= 0 ? c.getString(iId) : null;
                if (id == null) {
                    continue;
                }
                String name = iName >= 0 ? c.getString(iName) : id;
                String mime = iMime >= 0 ? c.getString(iMime) : "";

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    subDirs.add(id);
                    continue;
                }
                if (!isImage(mime, name)) {
                    continue;
                }
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                if (!seen.add(fileUri.toString())) {
                    continue;
                }
                long size = (iSize >= 0 && !c.isNull(iSize)) ? c.getLong(iSize) : 0L;
                long time = (iTime >= 0 && !c.isNull(iTime)) ? c.getLong(iTime) : 0L;
                String display = (name == null) ? id : name;
                String hint = join(relDir, display) + "  [docId=" + id + "]";
                out.add(new ImageEntry(fileUri, display, hint, size, time));
                if (progress != null && out.size() % 20 == 0) {
                    progress.onProgress("已发现 " + out.size() + " 张图片…", out.size(), maxCount);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "walk skipped: " + docId + " -> " + t.getMessage());
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }

        for (String sub : subDirs) {
            if (out.size() >= maxCount) {
                break;
            }
            walk(cr, tree, sub, join(relDir, sub), depth + 1, maxCount, out, seen, progress);
        }
    }

    public static List<ImageEntry> scanMediaStore(Context ctx, int maxCount, Progress progress) {
        List<ImageEntry> out = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(collection, proj, null, null,
                    MediaStore.Images.Media._ID + " DESC");
            if (c == null) {
                return out;
            }
            int iId = c.getColumnIndex(proj[0]);
            int iName = c.getColumnIndex(proj[1]);
            int iSize = c.getColumnIndex(proj[2]);
            int iTime = c.getColumnIndex(proj[3]);
            int iBucket = c.getColumnIndex(proj[4]);

            while (c.moveToNext() && out.size() < maxCount) {
                long id = iId >= 0 ? c.getLong(iId) : -1L;
                if (id < 0) {
                    continue;
                }
                String name = iName >= 0 ? c.getString(iName) : ("img_" + id);
                String bucket = (iBucket >= 0 && !c.isNull(iBucket)) ? c.getString(iBucket) : "MediaStore";
                long size = (iSize >= 0 && !c.isNull(iSize)) ? c.getLong(iSize) : 0L;
                long time = (iTime >= 0 && !c.isNull(iTime)) ? c.getLong(iTime) * 1000L : 0L;
                if (size > 0 && size < 10 * 1024L) {
                    continue; // 过滤图标、聊天小图
                }
                Uri uri = ContentUris.withAppendedId(collection, id);
                out.add(new ImageEntry(uri, name, bucket + "/" + name + "  [MediaStore id=" + id + "]", size, time));
                if (progress != null && out.size() % 20 == 0) {
                    progress.onProgress("已发现 " + out.size() + " 张图片…", out.size(), maxCount);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore scan failed: " + t.getMessage());
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (progress != null) {
            progress.onProgress("媒体库扫描完成，共 " + out.size() + " 张图片", out.size(), Math.max(1, out.size()));
        }
        return out;
    }

    private static boolean isImage(String mime, String name) {
        if (mime != null && mime.startsWith("image/")) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".bmp")
                || n.endsWith(".heic") || n.endsWith(".heif") || n.endsWith(".gif");
    }

    private static String join(String dir, String name) {
        if (dir == null || dir.length() == 0) {
            return name;
        }
        return dir + "/" + name;
    }
}
