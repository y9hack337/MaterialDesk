package io.github.mekhontsev.magicdesk;

/**
 * Protects the phone touchpad from being closed by an accidental Back.
 *
 * <p>A first Back only arms the guard and asks the caller to explain how to
 * close. A second Back within the confirmation window closes the touchpad.
 * Times are caller-supplied uptime timestamps; the guard never waits.
 */
final class TouchpadExitGuard {
    static final long CONFIRM_WINDOW_MILLIS = 2_000L;

    enum Decision {
        /** Explain how to close; the touchpad stays open. */
        CONFIRM,
        CLOSE
    }

    private long mArmedAt = Long.MIN_VALUE;

    Decision onBack(final long uptimeMillis) {
        if (mArmedAt != Long.MIN_VALUE
                && uptimeMillis >= mArmedAt
                && uptimeMillis - mArmedAt <= CONFIRM_WINDOW_MILLIS) {
            mArmedAt = Long.MIN_VALUE;
            return Decision.CLOSE;
        }
        mArmedAt = uptimeMillis;
        return Decision.CONFIRM;
    }

    /** Touchpad use between two Back presses means the first was accidental. */
    void onInteraction() {
        mArmedAt = Long.MIN_VALUE;
    }
}
