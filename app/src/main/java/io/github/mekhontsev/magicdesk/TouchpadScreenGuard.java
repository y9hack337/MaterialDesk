package io.github.mekhontsev.magicdesk;

/**
 * Burn-in and battery policy for the phone touchpad.
 *
 * <p>The touchpad is used while looking at another display, so its phone
 * screen is pure black and its few lit controls dim after a short idle period.
 * Lit controls also move by a few pixels on a slow cycle so no pixel shows the
 * same content for hours. Scheduling belongs to the Activity; this class only
 * owns the policy values and the shift pattern.
 */
final class TouchpadScreenGuard {
    /** Idle time after which controls dim and the backlight is lowered. */
    static final long IDLE_DIM_MILLIS = 8_000L;
    /** Interval between pixel-shift steps. */
    static final long SHIFT_INTERVAL_MILLIS = 60_000L;
    /** Largest pixel-shift offset from the original position, in dp. */
    static final int MAX_SHIFT_DP = 3;
    /** Opacity of controls while dimmed; they remain faintly findable. */
    static final float DIMMED_ALPHA = 0.18f;
    /** Window backlight override while dimmed (0..1). */
    static final float DIMMED_BRIGHTNESS = 0.02f;

    /** Visits every cell of a 3x3 grid, never jumping more than one cell. */
    private static final int[][] SHIFT_PATTERN = {
        {0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1},
        {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private TouchpadScreenGuard() {
    }

    static int shiftSteps() {
        return SHIFT_PATTERN.length;
    }

    /** Returns the {x, y} offset in pixels for a shift step. */
    static int[] shiftOffset(final int step, final int maxShiftPx) {
        final int[] cell = SHIFT_PATTERN[Math.floorMod(step, SHIFT_PATTERN.length)];
        return new int[] {cell[0] * maxShiftPx, cell[1] * maxShiftPx};
    }
}
