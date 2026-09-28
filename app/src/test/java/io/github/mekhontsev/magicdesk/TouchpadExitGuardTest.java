package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TouchpadExitGuardTest {
    @Test
    public void singleBackOnlyAsksForConfirmation() {
        final TouchpadExitGuard guard = new TouchpadExitGuard();

        assertEquals(TouchpadExitGuard.Decision.CONFIRM, guard.onBack(1_000L));
    }

    @Test
    public void secondBackInsideWindowCloses() {
        final TouchpadExitGuard guard = new TouchpadExitGuard();
        guard.onBack(1_000L);

        assertEquals(TouchpadExitGuard.Decision.CLOSE, guard.onBack(2_500L));
    }

    @Test
    public void lateSecondBackRearmsInsteadOfClosing() {
        final TouchpadExitGuard guard = new TouchpadExitGuard();
        guard.onBack(1_000L);

        assertEquals(TouchpadExitGuard.Decision.CONFIRM,
                guard.onBack(1_000L + TouchpadExitGuard.CONFIRM_WINDOW_MILLIS + 1L));
        assertEquals(TouchpadExitGuard.Decision.CLOSE, guard.onBack(3_500L));
    }

    @Test
    public void touchpadUseBetweenBacksKeepsItOpen() {
        final TouchpadExitGuard guard = new TouchpadExitGuard();
        guard.onBack(1_000L);
        guard.onInteraction();

        assertEquals(TouchpadExitGuard.Decision.CONFIRM, guard.onBack(1_500L));
    }

    @Test
    public void closingResetsTheGuard() {
        final TouchpadExitGuard guard = new TouchpadExitGuard();
        guard.onBack(1_000L);
        guard.onBack(1_200L);

        assertEquals(TouchpadExitGuard.Decision.CONFIRM, guard.onBack(1_400L));
    }
}
