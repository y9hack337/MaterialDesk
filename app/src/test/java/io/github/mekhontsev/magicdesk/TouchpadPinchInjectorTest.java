package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TouchpadPinchInjectorTest {
    @Test
    public void fingersSpreadAlongTheAxisWithMoreRoom() {
        // Centered on 1920 x 1080: horizontal room 959, vertical 539.
        assertArrayEquals(new float[] {960, 540, 1, 959},
                TouchpadPinchInjector.layout(960, 540, 1920, 1080), 0.5f);
        // Near the left edge the vertical axis has more room.
        assertArrayEquals(new float[] {30, 540, 0, 539},
                TouchpadPinchInjector.layout(30, 540, 1920, 1080), 0.01f);
        // A cursor outside the display is clamped onto it.
        assertArrayEquals(new float[] {1919, 0, 1, 12},
                TouchpadPinchInjector.layout(2500, -5, 1920, 1080), 0.01f);
    }

    @Test
    public void spanFollowsTheScaleWithinTheDisplay() {
        final float start = TouchpadPinchInjector.START_SPAN / 2.0f;
        assertEquals(start, TouchpadPinchInjector.halfSpan(1.0f, 900), 0.0f);
        assertEquals(start * 2.0f, TouchpadPinchInjector.halfSpan(2.0f, 900), 0.0f);
        assertEquals(300.0f, TouchpadPinchInjector.halfSpan(10.0f, 300), 0.0f);
        assertEquals(TouchpadPinchInjector.MIN_HALF_SPAN,
                TouchpadPinchInjector.halfSpan(0.01f, 900), 0.0f);
        assertEquals(start, TouchpadPinchInjector.halfSpan(Float.NaN, 900), 0.0f);
    }
}
