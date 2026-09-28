package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class RegionScreenshotControllerTest {
    @Test
    public void selectionMapsFromOverlayToDisplayPixelsInAnyDragDirection() {
        assertArrayEquals(new int[] {110, 60, 200, 100},
                RegionScreenshotController.cropBounds(1920, 1080, 300, 150, 100, 50, 10, 10));
    }

    @Test
    public void selectionIsClampedToTheCapturedFrame() {
        assertArrayEquals(new int[] {1800, 1000, 120, 80},
                RegionScreenshotController.cropBounds(1920, 1080, 1800, 1000, 2500, 1500, 0, 0));
        assertArrayEquals(new int[] {0, 0, 50, 40},
                RegionScreenshotController.cropBounds(1920, 1080, -30, -20, 50, 40, 0, 0));
    }

    @Test
    public void emptyOrOffFrameSelectionSavesNothing() {
        assertNull(RegionScreenshotController.cropBounds(1920, 1080, 50, 50, 50, 90, 0, 0));
        assertNull(RegionScreenshotController.cropBounds(1920, 1080, 2000, 10, 2100, 90, 0, 0));
    }
}
