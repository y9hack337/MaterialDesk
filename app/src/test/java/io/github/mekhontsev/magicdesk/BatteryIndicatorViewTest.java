package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BatteryIndicatorViewTest {
    @Test
    public void toneFollowsChargeAndLowLevels() {
        assertEquals(BatteryIndicatorView.Tone.UNKNOWN, BatteryIndicatorView.tone(-1, false));
        assertEquals(BatteryIndicatorView.Tone.CHARGING, BatteryIndicatorView.tone(5, true));
        assertEquals(BatteryIndicatorView.Tone.CRITICAL, BatteryIndicatorView.tone(10, false));
        assertEquals(BatteryIndicatorView.Tone.LOW, BatteryIndicatorView.tone(20, false));
        assertEquals(BatteryIndicatorView.Tone.NORMAL, BatteryIndicatorView.tone(21, false));
    }

    @Test
    public void fillKeepsASliverAboveZeroAndIsBounded() {
        assertEquals(0.0f, BatteryIndicatorView.fillFraction(0), 0.0f);
        assertEquals(0.08f, BatteryIndicatorView.fillFraction(1), 0.0001f);
        assertEquals(0.5f, BatteryIndicatorView.fillFraction(50), 0.0001f);
        assertEquals(1.0f, BatteryIndicatorView.fillFraction(100), 0.0001f);
    }

    @Test
    public void idleGlyphIsCenteredAndTheBoltShiftsItOnlyWhileCharging() {
        // 60 px wide, 32 px body, 2 px cap: 13 px on each side.
        assertEquals(13.0f, BatteryIndicatorView.bodyLeft(60, 32, 2, 0), 0.0f);
        // A 12 px bolt joins the centered group on the left.
        assertEquals(19.0f, BatteryIndicatorView.bodyLeft(60, 32, 2, 12), 0.0f);
    }
}
