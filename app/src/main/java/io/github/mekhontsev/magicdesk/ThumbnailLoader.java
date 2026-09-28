package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Loads previews for recycled list and grid rows off the UI thread. A request
 * is dropped before decoding once its view shows another file, and a result is
 * applied only if the view still shows the same file.
 */
final class ThumbnailLoader {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "MagicDeskThumbnails");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private ThumbnailLoader() {
    }

    /**
     * Requests {@code key} for {@code target}; {@code apply} runs on the UI
     * thread with the bitmap, synchronously when it is already cached.
     */
    static void request(
            final ImageView target,
            final String key,
            final String mimeType,
            final int sizePx,
            final MediaThumbnails.Opener opener,
            final Consumer<Bitmap> apply) {
        target.setTag(R.id.thumbnail_key, key);
        final Bitmap cached = MediaThumbnails.cached(key);
        if (cached != null) {
            apply.accept(cached);
            return;
        }
        WORKERS.execute(() -> {
            if (!key.equals(target.getTag(R.id.thumbnail_key))) {
                return;
            }
            final Bitmap bitmap = MediaThumbnails.load(key, mimeType, sizePx, opener);
            if (bitmap == null) {
                return;
            }
            target.post(() -> {
                if (key.equals(target.getTag(R.id.thumbnail_key))) {
                    apply.accept(bitmap);
                }
            });
        });
    }

    /** Forgets any pending request for a view that now shows something else. */
    static void clear(final ImageView target) {
        target.setTag(R.id.thumbnail_key, null);
    }
}
