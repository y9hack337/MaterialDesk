package io.github.mekhontsev.magicdesk;

/**
 * Momentum after a two-finger scroll, as on precision touchpads.
 *
 * <p>Velocity is in wheel detents per second and decays exponentially. The
 * caller advances it once per display frame and cancels it on the next touch;
 * this class never schedules or waits.
 */
final class TouchpadKineticScroll {
    /** Slower lifts end without momentum. */
    static final float MIN_START_VELOCITY = 4.0f;
    /** Momentum stops once it no longer moves content visibly. */
    static final float STOP_VELOCITY = 0.35f;
    /** Exponential decay rate per second; about 0.7 s of visible glide. */
    static final float DECAY_PER_SECOND = 4.0f;
    /** Bounds a flick so one gesture cannot scroll unboundedly far. */
    static final float MAX_VELOCITY = 120.0f;

    private float mVertical;
    private float mHorizontal;
    private boolean mActive;

    /** Starts momentum; returns false when the lift was too slow. */
    boolean start(final float vertical, final float horizontal) {
        final float speed = (float) Math.hypot(vertical, horizontal);
        if (speed < MIN_START_VELOCITY) {
            cancel();
            return false;
        }
        final float scale = speed > MAX_VELOCITY ? MAX_VELOCITY / speed : 1.0f;
        mVertical = vertical * scale;
        mHorizontal = horizontal * scale;
        mActive = true;
        return true;
    }

    /**
     * Advances by {@code seconds}; returns the {vertical, horizontal} detents
     * to scroll for this frame, or null once momentum has ended.
     */
    float[] step(final float seconds) {
        if (!mActive) {
            return null;
        }
        final float dt = Math.max(0.0f, Math.min(seconds, 0.05f));
        final float decay = (float) Math.exp(-DECAY_PER_SECOND * dt);
        // Integrate the exponential exactly over the frame.
        final float travel = DECAY_PER_SECOND == 0.0f ? dt
                : (1.0f - decay) / DECAY_PER_SECOND;
        final float[] delta = {mVertical * travel, mHorizontal * travel};
        mVertical *= decay;
        mHorizontal *= decay;
        if (Math.hypot(mVertical, mHorizontal) < STOP_VELOCITY) {
            mActive = false;
        }
        return delta;
    }

    void cancel() {
        mActive = false;
        mVertical = 0.0f;
        mHorizontal = 0.0f;
    }

    boolean isActive() {
        return mActive;
    }
}
