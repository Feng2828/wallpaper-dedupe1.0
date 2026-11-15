package com.workbuddy.wallpapergedupe.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 按需异步解码 SAF 缩略图，带内存 LRU 缓存；不缓存整张原图。 */
public class ThumbnailLoader {

    private final Context app;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache;

    public ThumbnailLoader(Context ctx) {
        app = ctx.getApplicationContext();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void load(Uri uri, ImageView iv) {
        final String key = uri.toString();
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageBitmap(null);
        pool.execute(() -> {
            final Bitmap thumb = decode(uri, 320);
            if (thumb != null) {
                cache.put(key, thumb);
            }
            iv.post(() -> {
                if (key.equals(iv.getTag())) {
                    iv.setImageBitmap(thumb);
                }
            });
        });
    }

    private Bitmap decode(Uri uri, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Throwable t) {
            return null;
        }
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) {
            return null;
        }
        int sample = 1;
        while (Math.max(w, h) / sample > target) {
            sample <<= 1;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, opt);
        } catch (Throwable t) {
            return null;
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
