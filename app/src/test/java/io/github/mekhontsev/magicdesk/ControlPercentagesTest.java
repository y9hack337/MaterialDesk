package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ControlPercentagesTest {
    @Test
    public void volumeStepsAreShownAsPercentages() {
        assertEquals(0, DesktopAudioPanelController.percent(0, 160));
        assertEquals(50, DesktopAudioPanelController.percent(80, 160));
        assertEquals(100, DesktopAudioPanelController.percent(160, 160));
        assertEquals(100, DesktopAudioPanelController.percent(15, 15));
        assertEquals(0, DesktopAudioPanelController.percent(3, 0));
    }

    @Test
    public void interfaceScaleIsRelativeToAndroidsBaselineDensity() {
        assertEquals(100, DesktopControlsController.scalePercent(160));
        assertEquals(150, DesktopControlsController.scalePercent(240));
        assertEquals(75, DesktopControlsController.scalePercent(120));
    }
}
