package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class TouchpadGestureRecognizerTest {
    private static final float SLOP = 8.0f;
    private static final float SCROLL_STEP = 16.0f;
    private static final float SWIPE = 50.0f;
    private static final float SWITCH_STEP = 80.0f;

    private final RecordingSink mSink = new RecordingSink();
    private final TouchpadGestureRecognizer mRecognizer =
            new TouchpadGestureRecognizer(
                    new TouchpadGestureRecognizer.Config(
                            SLOP, SCROLL_STEP, SWIPE, SWITCH_STEP, 350L, 300L, 40.0f),
                    mSink);

    @Test
    public void oneFingerTapClicksPrimary() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.move(1, 103, 102);
        mRecognizer.up(80);

        assertEquals(List.of("click:" + MotionEvent.BUTTON_PRIMARY), mSink.events);
    }

    @Test
    public void slideMovesPointerAfterSlopWithoutClicking() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.move(1, 110, 100);
        mRecognizer.move(1, 120, 105);
        mRecognizer.up(400);

        assertEquals(List.of("move:10.0,5.0"), mSink.events);
    }

    @Test
    public void restingLongPressHoldsPrimaryForTheApplicationsLongPress() {
        mRecognizer.down(100, 100, 0);
        assertTrue(mRecognizer.longPress());
        mRecognizer.move(1, 103, 102);
        mRecognizer.up(1500);

        // A held primary button, never a right click (that is a two-finger tap).
        assertEquals(List.of("primary:true", "primary:false"), mSink.events);
    }

    @Test
    public void pinchZoomsInAndOut() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.contactsChanged(2, 150, 100, 50);
        mRecognizer.move(2, 150, 100, 75);
        mRecognizer.move(2, 150, 100, 25);
        mRecognizer.up(800);

        assertEquals(List.of("pinchStarted", "pinch:1.5", "pinch:0.5", "pinchEnded"),
                mSink.events);
    }

    @Test
    public void pinchWithOneRestingFingerIsNotAScroll() {
        // One finger still, the other moving 40 px away: the centroid moves
        // 20 px and the spread grows 20 px.
        mRecognizer.down(100, 100, 0);
        mRecognizer.contactsChanged(2, 150, 100, 50);
        mRecognizer.move(2, 170, 100, 70);
        mRecognizer.contactsChanged(1, 100, 100);
        mRecognizer.up(800);

        assertEquals(List.of("pinchStarted", "pinch:1.4", "pinchEnded"), mSink.events);
    }

    @Test
    public void parallelSlideWithSteadySpreadScrolls() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.contactsChanged(2, 150, 100, 50);
        mRecognizer.move(2, 150, 132, 52);
        mRecognizer.up(800);

        assertEquals(List.of("scroll:-2.0,0.0", "scrollReleased:vertical"), mSink.events);
    }

    @Test
    public void quickHorizontalFlickNavigatesBackAndForward() {
        twoFingers(0);
        mRecognizer.move(2, 180, 100);
        mRecognizer.move(2, 270, 105);
        mRecognizer.up(200);
        assertEquals("navigate:back", mSink.events.get(mSink.events.size() - 2));

        mSink.events.clear();
        twoFingers(1_000);
        mRecognizer.move(2, 40, 100);
        mRecognizer.move(2, -50, 100);
        mRecognizer.up(1_200);
        assertEquals("navigate:forward", mSink.events.get(mSink.events.size() - 2));
    }

    @Test
    public void disabledNavigationLeavesAFlickAsScrolling() {
        mRecognizer.setNavigationEnabled(false);
        twoFingers(0);
        mRecognizer.move(2, 180, 100);
        mRecognizer.move(2, 270, 105);
        mRecognizer.up(200);
        assertEquals("scrollReleased:horizontal", mSink.events.get(mSink.events.size() - 1));
        assertFalse(mSink.events.stream().anyMatch(event -> event.startsWith("navigate")));
    }

    @Test
    public void quickShortOrDiagonalScrollDoesNotNavigate() {
        // Fast but short: an ordinary quick horizontal scroll.
        twoFingers(0);
        mRecognizer.move(2, 140, 100);
        mRecognizer.move(2, 190, 101);
        mRecognizer.up(150);
        // Fast and long, but with a large vertical component.
        twoFingers(1_000);
        mRecognizer.move(2, 180, 140);
        mRecognizer.move(2, 280, 180);
        mRecognizer.up(1_150);
        assertFalse(mSink.events.stream().anyMatch(event -> event.startsWith("navigate")));
    }

    @Test
    public void slowHorizontalScrollDoesNotNavigate() {
        twoFingers(0);
        mRecognizer.move(2, 140, 100);
        mRecognizer.move(2, 180, 101);
        mRecognizer.up(900);
        assertEquals("scrollReleased:horizontal", mSink.events.get(mSink.events.size() - 1));
        assertFalse(mSink.events.stream().anyMatch(event -> event.startsWith("navigate")));
    }

    @Test
    public void longPressThenMoveDragsAndReleases() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.longPress();
        mRecognizer.move(1, 112, 100);
        mRecognizer.move(1, 130, 110);
        mRecognizer.up(1200);

        assertEquals(List.of("primary:true", "move:18.0,10.0", "primary:false"),
                mSink.events);
    }

    @Test
    public void doubleTapAndHoldDrags() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.up(60);
        mRecognizer.down(104, 101, 200);
        assertFalse("tap-drag is not also a long press", mRecognizer.longPress());
        mRecognizer.move(1, 120, 101);
        mRecognizer.move(1, 140, 111);
        mRecognizer.up(900);

        assertEquals(List.of(
                "click:" + MotionEvent.BUTTON_PRIMARY,
                "primary:true",
                "move:20.0,10.0",
                "primary:false"), mSink.events);
    }

    @Test
    public void doubleTapWithoutMotionIsDoubleClick() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.up(60);
        mRecognizer.down(102, 100, 200);
        mRecognizer.up(260);
        // A third tap does not arm another drag from the double-click.
        mRecognizer.down(102, 100, 400);
        mRecognizer.move(1, 140, 100);
        mRecognizer.move(1, 160, 100);
        mRecognizer.up(600);

        assertEquals(List.of(
                "click:" + MotionEvent.BUTTON_PRIMARY,
                "click:" + MotionEvent.BUTTON_PRIMARY,
                "move:20.0,0.0"), mSink.events);
    }

    @Test
    public void lateSecondTapDoesNotDrag() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.up(60);
        mRecognizer.down(100, 100, 700);
        mRecognizer.move(1, 120, 100);
        mRecognizer.move(1, 130, 100);
        mRecognizer.up(900);

        assertEquals(List.of(
                "click:" + MotionEvent.BUTTON_PRIMARY,
                "move:10.0,0.0"), mSink.events);
    }

    @Test
    public void twoFingerTapRightClicks() {
        twoFingers(0);
        mRecognizer.contactsChanged(1, 100, 100);
        mRecognizer.up(150);

        assertEquals(List.of("click:" + MotionEvent.BUTTON_SECONDARY), mSink.events);
    }

    @Test
    public void slowTwoFingerRestIsNotATap() {
        twoFingers(0);
        mRecognizer.contactsChanged(1, 100, 100);
        mRecognizer.up(1000);

        assertTrue(mSink.events.isEmpty());
    }

    @Test
    public void twoFingerVerticalSlideScrollsOnlyVertically() {
        twoFingers(0);
        mRecognizer.move(2, 112, 132);
        mRecognizer.move(2, 113, 164);
        mRecognizer.up(500);

        assertEquals(List.of("scroll:-2.0,0.0", "scroll:-2.0,0.0", "scrollReleased:vertical"),
                mSink.events);
    }

    @Test
    public void twoFingerSidewaysSlideScrollsHorizontally() {
        twoFingers(0);
        mRecognizer.move(2, 142, 110);
        mRecognizer.move(2, 110, 112);
        mRecognizer.up(500);

        // Momentum stays horizontal too.
        assertEquals(List.of("scroll:0.0,2.0", "scroll:0.0,-2.0", "scrollReleased:horizontal"),
                mSink.events);
    }

    @Test
    public void remainingFingerAfterScrollDoesNotMovePointer() {
        twoFingers(0);
        mRecognizer.move(2, 110, 140);
        mRecognizer.contactsChanged(1, 100, 150);
        mRecognizer.move(1, 180, 220);
        mRecognizer.up(500);

        assertEquals(List.of("scroll:-2.5,0.0", "scrollReleased:vertical"), mSink.events);
    }

    @Test
    public void threeFingerTapOpensStart() {
        threeFingers(0);
        mRecognizer.up(200);

        assertEquals(List.of("perform:START", "feedback"), mSink.events);
    }

    @Test
    public void fourFingerTapOpensNotifications() {
        threeFingers(0);
        mRecognizer.contactsChanged(4, 110, 110);
        mRecognizer.up(200);

        assertEquals(List.of("perform:NOTIFICATIONS", "feedback"), mSink.events);
    }

    @Test
    public void threeFingerSwipeUpOpensTaskViewOnce() {
        threeFingers(0);
        mRecognizer.move(3, 110, 70);
        mRecognizer.move(3, 110, 40);
        mRecognizer.move(3, 110, -100);
        mRecognizer.up(400);

        assertEquals(List.of("perform:TASK_VIEW", "feedback"), mSink.events);
    }

    @Test
    public void threeFingerSwipeDownShowsDesktop() {
        threeFingers(0);
        mRecognizer.move(3, 110, 170);
        mRecognizer.up(400);

        assertEquals(List.of("perform:SHOW_DESKTOP", "feedback"), mSink.events);
    }

    @Test
    public void threeFingerSideSwipeSwitchesApplicationsUntilLift() {
        threeFingers(0);
        mRecognizer.move(3, 170, 110);
        mRecognizer.move(3, 260, 112);
        mRecognizer.move(3, 170, 112);
        mRecognizer.up(900);

        assertEquals(List.of(
                "switch:false", "feedback",
                "switch:false", "feedback",
                "switch:true", "feedback",
                "finishSwitch"), mSink.events);
    }

    @Test
    public void cancelledApplicationSwitchIsCancelled() {
        threeFingers(0);
        mRecognizer.move(3, 40, 110);
        mRecognizer.cancel();

        assertEquals(List.of("switch:true", "feedback", "cancelSwitch"), mSink.events);
    }

    @Test
    public void rejectedShellActionHasNoFeedback() {
        mSink.acceptActions = false;
        threeFingers(0);
        mRecognizer.up(200);

        assertEquals(List.of("perform:START"), mSink.events);
    }

    @Test
    public void addingFingersDuringDragReleasesPrimary() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.longPress();
        mRecognizer.move(1, 120, 100);
        mRecognizer.contactsChanged(2, 130, 100);
        mRecognizer.move(2, 130, 160);
        mRecognizer.up(900);

        assertEquals(List.of("primary:true", "primary:false"), mSink.events);
    }

    @Test
    public void cancelledDragReleasesPrimary() {
        mRecognizer.down(100, 100, 0);
        mRecognizer.longPress();
        mRecognizer.move(1, 120, 100);
        mRecognizer.cancel();

        assertEquals(List.of("primary:true", "primary:false"), mSink.events);
        assertFalse(mRecognizer.isActive());
    }

    @Test
    public void rejectedMotionStopsPointerGesture() {
        mSink.acceptPointer = false;
        mRecognizer.down(100, 100, 0);
        mRecognizer.move(1, 120, 100);
        mRecognizer.move(1, 130, 100);
        mRecognizer.move(1, 140, 100);
        mRecognizer.up(400);

        assertEquals(List.of("move:10.0,0.0"), mSink.events);
    }

    private void twoFingers(final long time) {
        mRecognizer.down(100, 100, time);
        mRecognizer.contactsChanged(2, 110, 100);
    }

    private void threeFingers(final long time) {
        twoFingers(time);
        mRecognizer.contactsChanged(3, 110, 110);
    }

    private static final class RecordingSink implements TouchpadGestureRecognizer.Sink {
        final List<String> events = new ArrayList<>();
        boolean acceptActions = true;
        boolean acceptPointer = true;

        @Override
        public boolean movePointer(final float deltaX, final float deltaY) {
            events.add("move:" + deltaX + "," + deltaY);
            return acceptPointer;
        }

        @Override
        public boolean click(final int button) {
            events.add("click:" + button);
            return true;
        }

        @Override
        public boolean setPrimaryPressed(final boolean pressed) {
            events.add("primary:" + pressed);
            return true;
        }

        @Override
        public boolean scroll(final float vertical, final float horizontal) {
            events.add("scroll:" + vertical + "," + horizontal);
            return true;
        }

        @Override
        public boolean pinchStarted() {
            events.add("pinchStarted");
            return true;
        }

        @Override
        public boolean pinch(final float scale) {
            events.add("pinch:" + scale);
            return true;
        }

        @Override
        public void pinchEnded() {
            events.add("pinchEnded");
        }

        @Override
        public boolean navigate(final boolean back) {
            events.add(back ? "navigate:back" : "navigate:forward");
            return true;
        }

        @Override
        public void scrollReleased(final boolean horizontal) {
            events.add(horizontal ? "scrollReleased:horizontal" : "scrollReleased:vertical");
        }

        @Override
        public boolean perform(final TouchpadGestureRecognizer.Action action) {
            events.add("perform:" + action);
            return acceptActions;
        }

        @Override
        public boolean switchApplication(final boolean reverse) {
            events.add("switch:" + reverse);
            return true;
        }

        @Override
        public void finishApplicationSwitch() {
            events.add("finishSwitch");
        }

        @Override
        public void cancelApplicationSwitch() {
            events.add("cancelSwitch");
        }

        @Override
        public void gestureFeedback() {
            events.add("feedback");
        }
    }
}
