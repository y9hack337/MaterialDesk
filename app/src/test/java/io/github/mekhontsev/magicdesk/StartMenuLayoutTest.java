package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StartMenuLayoutTest {
    @Test
    public void narrowPhoneAndDesktopHaveIndependentGridWidth() {
        assertEquals(3, StartMenuLayout.columns(332));
        assertEquals(6, StartMenuLayout.columns(520));
    }

    @Test
    public void largeViewportUsesAvailableWidth() {
        assertEquals(23, StartMenuLayout.columns(2000));
    }

    @Test
    public void unmeasuredViewportRemainsValid() {
        assertEquals(1, StartMenuLayout.columns(0));
    }
}
