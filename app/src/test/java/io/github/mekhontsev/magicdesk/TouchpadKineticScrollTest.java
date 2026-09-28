package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TouchpadKineticScrollTest {
    @Test
    public void slowLiftHasNoMomentum() {
        final TouchpadKineticScroll kinetic = new TouchpadKineticScroll();
        assertFalse(kinetic.start(1.0f, 0.0f));
        assertNull(kinetic.step(0.016f));
    }

    @Test
    public void momentumContinuesInTheSameDirectionAndDecays() {
        final TouchpadKineticScroll kinetic = new TouchpadKineticScroll();
        assertTrue(kinetic.start(-20.0f, 0.0f));
        final float[] first = kinetic.step(0.016f);
        final float[] second = kinetic.step(0.016f);
        assertTrue(first[0] < 0.0f && second[0] < 0.0f);
        assertTrue(Math.abs(second[0]) < Math.abs(first[0]));
        assertEquals(0.0f, first[1], 0.0f);
    }

    @Test
    public void momentumEndsAndTravelsAboutVelocityOverDecay() {
        final TouchpadKineticScroll kinetic = new TouchpadKineticScroll();
        kinetic.start(0.0f, 40.0f);
        float travelled = 0.0f;
        int frames = 0;
        float[] delta;
        while ((delta = kinetic.step(0.016f)) != null) {
            travelled += delta[1];
            frames++;
            assertTrue("momentum never ends", frames < 1_000);
        }
        assertFalse(kinetic.isActive());
        // Total travel of v*e^(-kt) is v/k, minus the tail below STOP_VELOCITY.
        assertEquals(40.0f / TouchpadKineticScroll.DECAY_PER_SECOND, travelled, 0.2f);
    }

    @Test
    public void flicksAreBounded() {
        final TouchpadKineticScroll kinetic = new TouchpadKineticScroll();
        kinetic.start(10_000.0f, 0.0f);
        final float[] delta = kinetic.step(0.016f);
        assertTrue(delta[0] <= TouchpadKineticScroll.MAX_VELOCITY * 0.016f);
    }

    @Test
    public void cancelStopsImmediately() {
        final TouchpadKineticScroll kinetic = new TouchpadKineticScroll();
        kinetic.start(30.0f, 0.0f);
        kinetic.cancel();
        assertNull(kinetic.step(0.016f));
    }
}
