package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaThumbnailsTest {
    @Test
    public void sampleSizeKeepsTheShortSideAtLeastThePreviewSize() {
        assertEquals(1, MediaThumbnails.sampleSize(150, 192));
        assertEquals(1, MediaThumbnails.sampleSize(383, 192));
        assertEquals(2, MediaThumbnails.sampleSize(384, 192));
        assertEquals(8, MediaThumbnails.sampleSize(3024, 192));
        assertEquals(1, MediaThumbnails.sampleSize(3024, 0));
    }

    @Test
    public void onlyPhotosAndVideosHavePreviews() {
        assertTrue(MediaThumbnails.supports("image/jpeg"));
        assertTrue(MediaThumbnails.supports("video/mp4"));
        assertTrue(MediaThumbnails.isVideo("video/webm"));
        assertFalse(MediaThumbnails.isVideo("image/png"));
        assertFalse(MediaThumbnails.supports("application/pdf"));
        assertFalse(MediaThumbnails.supports(null));
    }

    @Test
    public void changedFilesAndSizesDoNotReuseAPreview() {
        final String key = MediaThumbnails.key("/sdcard/a.jpg", 10, 20, 64);
        assertEquals(key, MediaThumbnails.key("/sdcard/a.jpg", 10, 20, 64));
        assertNotEquals(key, MediaThumbnails.key("/sdcard/a.jpg", 11, 20, 64));
        assertNotEquals(key, MediaThumbnails.key("/sdcard/a.jpg", 10, 21, 64));
        assertNotEquals(key, MediaThumbnails.key("/sdcard/a.jpg", 10, 20, 96));
        assertNotEquals(key, MediaThumbnails.key("/sdcard/b.jpg", 10, 20, 64));
    }
}
