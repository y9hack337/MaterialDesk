package io.github.mekhontsev.magicdesk;

/**
 * Rubber-band selection over empty desktop space.
 *
 * <p>A press on empty space only arms the marquee, so taps and long presses
 * keep their meaning. It becomes active once the pointer travels beyond the
 * touch slop. Coordinates are local to the desktop grid.
 */
final class DesktopSelectionMarquee {
    private final float mSlop;
    private float mStartX;
    private float mStartY;
    private float mX;
    private float mY;
    private boolean mArmed;
    private boolean mActive;

    DesktopSelectionMarquee(final float slop) {
        mSlop = slop;
    }

    void down(final float x, final float y) {
        mStartX = x;
        mStartY = y;
        mX = x;
        mY = y;
        mArmed = true;
        mActive = false;
    }

    /** Returns true while the gesture is a marquee selection. */
    boolean move(final float x, final float y) {
        if (!mArmed) {
            return false;
        }
        mX = x;
        mY = y;
        if (!mActive && Math.hypot(x - mStartX, y - mStartY) > mSlop) {
            mActive = true;
        }
        return mActive;
    }

    /** Ends the gesture; returns true when it had become a marquee. */
    boolean end() {
        final boolean wasActive = mActive;
        mArmed = false;
        mActive = false;
        return wasActive;
    }

    boolean isArmed() {
        return mArmed;
    }

    boolean isActive() {
        return mActive;
    }

    /** Normalized {left, top, right, bottom} of the current rectangle. */
    int[] bounds() {
        return new int[] {
            Math.round(Math.min(mStartX, mX)),
            Math.round(Math.min(mStartY, mY)),
            Math.round(Math.max(mStartX, mX)),
            Math.round(Math.max(mStartY, mY))
        };
    }

    static boolean intersects(
            final int[] bounds,
            final int left,
            final int top,
            final int right,
            final int bottom) {
        return bounds[0] < right && left < bounds[2]
                && bounds[1] < bottom && top < bounds[3];
    }
}
