package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public final class TouchpadScreenGuardTest {
    @Test
    public void shiftCycleStartsAtOriginAndVisitsEveryCellOnce() {
        final Set<String> cells = new HashSet<>();
        for (int step = 0; step < TouchpadScreenGuard.shiftSteps(); step++) {
            final int[] offset = TouchpadScreenGuard.shiftOffset(step, 1);
            assertTrue(cells.add(offset[0] + "," + offset[1]));
        }
        assertEquals(9, cells.size());
        assertArrayEquals(new int[] {0, 0}, TouchpadScreenGuard.shiftOffset(0, 6));
    }

    @Test
    public void consecutiveShiftsMoveAtMostOneStepAndStayBounded() {
        final int max = 6;
        for (int step = 0; step < TouchpadScreenGuard.shiftSteps() * 2; step++) {
            final int[] current = TouchpadScreenGuard.shiftOffset(step, max);
            final int[] next = TouchpadScreenGuard.shiftOffset(step + 1, max);
            assertTrue(Math.abs(current[0]) <= max && Math.abs(current[1]) <= max);
            assertTrue(Math.abs(next[0] - current[0]) <= 2 * max);
            assertTrue(Math.abs(next[1] - current[1]) <= 2 * max);
        }
    }

    @Test
    public void negativeStepsWrapIntoThePattern() {
        assertArrayEquals(TouchpadScreenGuard.shiftOffset(
                        TouchpadScreenGuard.shiftSteps() - 1, 2),
                TouchpadScreenGuard.shiftOffset(-1, 2));
    }

    @Test
    public void dimmedStateStaysFindableAndSavesBacklight() {
        assertTrue(TouchpadScreenGuard.DIMMED_ALPHA > 0.0f
                && TouchpadScreenGuard.DIMMED_ALPHA < 0.5f);
        assertTrue(TouchpadScreenGuard.DIMMED_BRIGHTNESS > 0.0f
                && TouchpadScreenGuard.DIMMED_BRIGHTNESS < 0.1f);
        assertTrue(TouchpadScreenGuard.IDLE_DIM_MILLIS
                < TouchpadScreenGuard.SHIFT_INTERVAL_MILLIS);
    }
}
