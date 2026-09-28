package io.github.mekhontsev.magicdesk;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.util.Size;

import java.io.IOException;

/**
 * Decodes small previews of photos and videos from a privileged file
 * descriptor. Images honor EXIF orientation; videos use a frame near their
 * start. Results are cached by path, size and modification time, so a folder
 * refresh does not decode unchanged files again.
 */
final class MediaThumbnails {
    /** Opens the media file for reading; each call returns a new descriptor. */
    interface Opener {
        ParcelFileDescriptor open() throws IOException;
    }

    private static final long VIDEO_FRAME_MICROS = 1_000_000L;
    private static final int CACHE_BYTES = 24 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<>(CACHE_BYTES) {
                @Override
                protected int sizeOf(final String key, final Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };

    private MediaThumbnails() {
    }

    static boolean supports(final String mimeType) {
        return isImage(mimeType) || isVideo(mimeType);
    }

    static boolean isVideo(final String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }

    private static boolean isImage(final String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    /** Identity of one version of a file at one preview size. */
    static String key(final String path, final long size, final long modified, final int sizePx) {
        return path + '\u0000' + size + '\u0000' + modified + '\u0000' + sizePx;
    }

    static Bitmap cached(final String key) {
        return CACHE.get(key);
    }

    /** Decodes on the calling (worker) thread; returns null when unsupported. */
    static Bitmap load(
            final String key,
            final String mimeType,
            final int sizePx,
            final Opener opener) {
        final Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Bitmap bitmap = null;
        try {
            if (isImage(mimeType)) {
                bitmap = decodeImage(sizePx, opener);
            } else if (isVideo(mimeType)) {
                bitmap = decodeVideo(sizePx, opener);
            }
        } catch (IOException | RuntimeException error) {
            // Unsupported, protected or partially written media keeps its icon.
            return null;
        }
        if (bitmap != null) {
            CACHE.put(key, bitmap);
        }
        return bitmap;
    }

    private static Bitmap decodeImage(final int sizePx, final Opener opener) throws IOException {
        final ImageDecoder.Source source = ImageDecoder.createSource(() ->
                new AssetFileDescriptor(opener.open(), 0, AssetFileDescriptor.UNKNOWN_LENGTH));
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            final Size size = info.getSize();
            decoder.setTargetSampleSize(sampleSize(
                    Math.min(size.getWidth(), size.getHeight()), sizePx));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
        });
    }

    private static Bitmap decodeVideo(final int sizePx, final Opener opener) throws IOException {
        try (ParcelFileDescriptor descriptor = opener.open()) {
            final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(descriptor.getFileDescriptor());
                Bitmap frame = retriever.getScaledFrameAtTime(VIDEO_FRAME_MICROS,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx * 2, sizePx * 2);
                if (frame == null) {
                    frame = retriever.getScaledFrameAtTime(0L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx * 2, sizePx * 2);
                }
                return frame;
            } finally {
                retriever.release();
            }
        }
    }

    /** Largest power-of-two reduction that keeps the short side at least {@code target}. */
    static int sampleSize(final int shortSide, final int target) {
        int sample = 1;
        while (target > 0 && shortSide / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }
}
