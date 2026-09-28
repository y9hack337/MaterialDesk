package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelectionMarqueeTest {
    @Test
    public void pressAloneKeepsTapAndLongPressMeaning() {
        final DesktopSelectionMarquee marquee = new DesktopSelectionMarquee(8.0f);
        marquee.down(100, 100);
        assertFalse(marquee.move(104, 103));
        assertFalse(marquee.end());
    }

    @Test
    public void travelBeyondSlopStartsSelection() {
        final DesktopSelectionMarquee marquee = new DesktopSelectionMarquee(8.0f);
        marquee.down(100, 100);
        assertTrue(marquee.move(130, 140));
        assertTrue(marquee.isActive());
        assertTrue(marquee.end());
        assertFalse(marquee.isArmed());
    }

    @Test
    public void boundsAreNormalizedInEveryDirection() {
        final DesktopSelectionMarquee marquee = new DesktopSelectionMarquee(8.0f);
        marquee.down(300, 200);
        marquee.move(120, 50);
        assertArrayEquals(new int[] {120, 50, 300, 200}, marquee.bounds());
    }

    @Test
    public void moveWithoutPressDoesNothing() {
        final DesktopSelectionMarquee marquee = new DesktopSelectionMarquee(8.0f);
        assertFalse(marquee.move(500, 500));
    }

    @Test
    public void intersectionSelectsTouchedItemsOnly() {
        final int[] bounds = {100, 100, 200, 200};
        assertTrue(DesktopSelectionMarquee.intersects(bounds, 190, 190, 260, 260));
        assertTrue(DesktopSelectionMarquee.intersects(bounds, 0, 0, 400, 400));
        assertFalse(DesktopSelectionMarquee.intersects(bounds, 200, 100, 260, 160));
        assertFalse(DesktopSelectionMarquee.intersects(bounds, 0, 0, 100, 100));
    }
}
