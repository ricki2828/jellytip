package com.jellytip.phone;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Crests, headshots and venue photos for the native player: fetched once, decoded at their display size, kept in a
 * small LRU (plan "Live panel: pop, context, performance": images cached at display size). The view's tag guards
 * against a late answer landing on a view that has moved on to another image.
 */
final class ImageCache {

    private static final LruCache<String, Bitmap> cache =
        new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
    private static final ExecutorService io = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private ImageCache() {}

    /** [url] absolute or a companion path (resolved against [apiBase]); [px] = the long side it shows at. */
    static void load(ImageView into, String url, String apiBase, String token, int px) {
        if (url == null || url.isEmpty()) {
            into.setTag(null);
            into.setImageDrawable(null);
            return;
        }
        String base = apiBase == null ? "" : apiBase.replaceAll("/+$", "") + "/";
        final String abs = url.startsWith("http://") || url.startsWith("https://") ? url : base + url.replaceAll("^/+", "");
        final String key = abs + "@" + px;
        if (key.equals(into.getTag())) return; // already showing (or on its way)
        into.setTag(key);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        into.setImageDrawable(null);
        io.execute(() -> {
            Bitmap bm = MatchStyle.download(abs, token, base, px);
            if (bm == null) return;
            cache.put(key, bm);
            main.post(() -> {
                if (key.equals(into.getTag())) into.setImageBitmap(bm);
            });
        });
    }
}
