package io.github.mekhontsev.magicdesk;

import android.view.MotionEvent;

/**
 * Interprets phone touch contacts with Windows Precision Touchpad semantics.
 *
 * <p>The recognizer is independent of Android views. Callers report the
 * contact count and centroid of every touch event and receive semantic
 * pointer operations. Times are event timestamps; the recognizer never waits.
 */
final class TouchpadGestureRecognizer {
    /** Semantic shell actions requested by three- and four-finger gestures. */
    enum Action {
        START,
        NOTIFICATIONS,
        TASK_VIEW,
        SHOW_DESKTOP
    }

    /** Receives recognized operations. Returning false ends the operation. */
    interface Sink {
        boolean movePointer(float deltaX, float deltaY);

        boolean click(int button);

        boolean setPrimaryPressed(boolean pressed);

        /** Wheel steps; positive vertical is up and positive horizontal is right. */
        boolean scroll(float vertical, float horizontal);

        /**
         * Two-finger scrolling ended by lifting; the sink may add momentum,
         * only along the axis the scroll was locked to.
         */
        void scrollReleased(boolean horizontal);

        boolean perform(Action action);

        /** A two-finger pinch began at the pointer. */
        boolean pinchStarted();

        /** Finger spread relative to the start of the pinch; above 1 zooms in. */
        boolean pinch(float scale);

        void pinchEnded();

        /** Laptop-style two-finger flick: back when the fingers move right. */
        boolean navigate(boolean back);

        boolean switchApplication(boolean reverse);

        void finishApplicationSwitch();

        void cancelApplicationSwitch();

        void gestureFeedback();
    }

    static final class Config {
        final float touchSlop;
        final float scrollStep;
        final float swipeDistance;
        final float switchStep;
        final long multiTapTimeoutMillis;
        final long doubleTapTimeoutMillis;
        final float doubleTapSlop;

        Config(
                final float touchSlop,
                final float scrollStep,
                final float swipeDistance,
                final float switchStep,
                final long multiTapTimeoutMillis,
                final long doubleTapTimeoutMillis,
                final float doubleTapSlop) {
            this.touchSlop = touchSlop;
            this.scrollStep = Math.max(1.0f, scrollStep);
            this.swipeDistance = swipeDistance;
            this.switchStep = Math.max(1.0f, switchStep);
            this.multiTapTimeoutMillis = multiTapTimeoutMillis;
            this.doubleTapTimeoutMillis = doubleTapTimeoutMillis;
            this.doubleTapSlop = doubleTapSlop;
        }
    }

    private enum Mode {
        /** No gesture has passed its slop; the contact may still be a tap. */
        UNDECIDED,
        POINTER,
        DRAG,
        SCROLL,
        PINCH,
        SWITCHING,
        /** The gesture completed or became irrelevant; wait for all fingers up. */
        CONSUMED
    }

    private enum ScrollAxis { NONE, VERTICAL, HORIZONTAL }

    private final Config mConfig;
    private final Sink mSink;

    private Mode mMode = Mode.UNDECIDED;
    private ScrollAxis mScrollAxis = ScrollAxis.NONE;
    private int mMaxContacts;
    private int mContacts;
    private long mDownTime;
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private float mLastY;
    /** Centroid displacement accumulated since the current contact count began. */
    private float mSegmentX;
    private float mSegmentY;
    private float mTravel;
    private float mSwitchAnchor;
    private boolean mLongPressed;
    private boolean mTapDragArmed;
    private boolean mPrimaryPressed;
    private boolean mActive;
    /** A long-press drag holds the button but ignores jitter within the slop. */
    private boolean mDragSlopPending;
    private float mDragAnchorX;
    private float mDragAnchorY;
    /** Mean finger distance from the centroid; NaN when unknown. */
    private float mSpread = Float.NaN;
    private float mSegmentSpread = Float.NaN;
    /** Signed horizontal travel of the current two-finger scroll. */
    private float mScrollTravelX;
    private float mScrollTravelY;

    /** Finger spread when the current pinch began. */
    private float mPinchStartSpread = Float.NaN;
    /**
     * A pinch changes the finger spread about as much as it moves the centroid
     * even with one finger resting; parallel scrolling barely changes it.
     */
    private static final float PINCH_SPREAD_RATIO = 0.6f;
    /**
     * Back/Forward needs a deliberate flick: the whole gesture is a fast,
     * long, almost purely horizontal sweep. Ordinary horizontal scrolling,
     * even quick, stays scrolling.
     */
    private static final long NAVIGATION_FLICK_MILLIS = 300L;
    private static final float NAVIGATION_DISTANCE_SWIPES = 2.5f;
    private static final float NAVIGATION_MAX_VERTICAL_RATIO = 0.35f;

    private boolean mNavigationEnabled = true;

    private long mLastTapTime = Long.MIN_VALUE;
    private float mLastTapX;
    private float mLastTapY;

    TouchpadGestureRecognizer(final Config config, final Sink sink) {
        mConfig = config;
        mSink = sink;
    }

    /** Dispatches an Android touch event by centroid. */
    void onTouchEvent(final MotionEvent event) {
        final int action = event.getActionMasked();
        final int liftedIndex = action == MotionEvent.ACTION_POINTER_UP
                ? event.getActionIndex() : -1;
        float sumX = 0.0f;
        float sumY = 0.0f;
        int count = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index == liftedIndex) {
                continue;
            }
            sumX += event.getX(index);
            sumY += event.getY(index);
            count++;
        }
        final float x = count == 0 ? event.getX() : sumX / count;
        final float y = count == 0 ? event.getY() : sumY / count;
        float spreadSum = 0.0f;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index != liftedIndex) {
                spreadSum += (float) Math.hypot(event.getX(index) - x, event.getY(index) - y);
            }
        }
        final float spread = count < 2 ? Float.NaN : spreadSum / count;
        final long time = event.getEventTime();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                down(x, y, time);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                contactsChanged(count, x, y, spread);
                break;
            case MotionEvent.ACTION_MOVE:
                move(count, x, y, spread);
                break;
            case MotionEvent.ACTION_UP:
                up(time);
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
            default:
                break;
        }
    }

    void down(final float x, final float y, final long time) {
        if (mActive) {
            cancel();
        }
        mActive = true;
        mMode = Mode.UNDECIDED;
        mScrollAxis = ScrollAxis.NONE;
        mContacts = 1;
        mMaxContacts = 1;
        mDownTime = time;
        mDownX = x;
        mDownY = y;
        mLastX = x;
        mLastY = y;
        mSegmentX = 0.0f;
        mSegmentY = 0.0f;
        mTravel = 0.0f;
        mLongPressed = false;
        // Windows tap-and-drag: a second contact soon after a tap, near it,
        // becomes a held primary button once it moves.
        mTapDragArmed = mLastTapTime != Long.MIN_VALUE
                && time - mLastTapTime <= mConfig.doubleTapTimeoutMillis
                && Math.hypot(x - mLastTapX, y - mLastTapY)
                        <= mConfig.doubleTapSlop;
    }

    void contactsChanged(final int contacts, final float x, final float y) {
        contactsChanged(contacts, x, y, Float.NaN);
    }

    void contactsChanged(
            final int contacts, final float x, final float y, final float spread) {
        if (!mActive) {
            return;
        }
        mSpread = spread;
        mSegmentSpread = spread;
        if (mMode == Mode.PINCH) {
            // A lifted or added finger ends the pinch; the rest is ignored.
            endPinch();
            mMode = Mode.CONSUMED;
        }
        if (contacts > mContacts) {
            mMaxContacts = Math.max(mMaxContacts, contacts);
            if (mMode == Mode.DRAG) {
                releasePrimary();
                mMode = Mode.CONSUMED;
            } else if (mMode == Mode.POINTER) {
                // Adding fingers after pointer motion starts a new gesture
                // instead of reinterpreting the motion that already happened.
                mMode = Mode.UNDECIDED;
            }
            mLongPressed = false;
            mTapDragArmed = false;
        }
        mContacts = contacts;
        mLastX = x;
        mLastY = y;
        mSegmentX = 0.0f;
        mSegmentY = 0.0f;
        mSwitchAnchor = 0.0f;
    }

    void move(final int contacts, final float x, final float y) {
        move(contacts, x, y, Float.NaN);
    }

    void move(final int contacts, final float x, final float y, final float spread) {
        if (!mActive || contacts != mContacts) {
            if (mActive) {
                contactsChanged(contacts, x, y, spread);
            }
            return;
        }
        final float previousSpread = mSpread;
        mSpread = spread;
        final float deltaX = x - mLastX;
        final float deltaY = y - mLastY;
        mLastX = x;
        mLastY = y;
        mSegmentX += deltaX;
        mSegmentY += deltaY;
        mTravel += (float) Math.hypot(deltaX, deltaY);
        if (mMode == Mode.CONSUMED) {
            return;
        }
        if (contacts == 1) {
            moveOneFinger(deltaX, deltaY);
        } else if (contacts == 2) {
            moveTwoFingers(deltaX, deltaY, previousSpread);
        } else {
            moveManyFingers();
        }
    }

    private void moveOneFinger(final float deltaX, final float deltaY) {
        if (mMaxContacts > 1) {
            // A finger left over from a multi-finger gesture never moves the pointer.
            return;
        }
        if (mMode == Mode.UNDECIDED) {
            if (mTravel <= mConfig.touchSlop) {
                return;
            }
            if (mLongPressed || mTapDragArmed) {
                if (!mSink.setPrimaryPressed(true)) {
                    mMode = Mode.CONSUMED;
                    return;
                }
                mPrimaryPressed = true;
                mMode = Mode.DRAG;
            } else {
                mMode = Mode.POINTER;
            }
            // Motion inside the slop is discarded so taps never jitter the pointer.
            return;
        }
        if (mMode == Mode.DRAG && mDragSlopPending) {
            if (Math.hypot(mLastX - mDragAnchorX, mLastY - mDragAnchorY)
                    <= mConfig.touchSlop) {
                return;
            }
            // Past the slop the held button drags; the slop itself is dropped.
            mDragSlopPending = false;
            return;
        }
        if ((mMode == Mode.POINTER || mMode == Mode.DRAG)
                && !mSink.movePointer(deltaX, deltaY)) {
            if (mMode == Mode.DRAG) {
                releasePrimary();
            }
            mMode = Mode.CONSUMED;
        }
    }

    private void moveTwoFingers(
            final float deltaX, final float deltaY, final float previousSpread) {
        if (mMode == Mode.UNDECIDED) {
            final float centroidTravel = (float) Math.hypot(mSegmentX, mSegmentY);
            final float spreadChange = Float.isNaN(mSpread) || Float.isNaN(mSegmentSpread)
                    ? 0.0f : Math.abs(mSpread - mSegmentSpread);
            if (spreadChange <= mConfig.touchSlop && centroidTravel <= mConfig.touchSlop) {
                return;
            }
            if (spreadChange >= centroidTravel * PINCH_SPREAD_RATIO) {
                // Fingers moving apart or together zoom rather than scroll.
                mPinchStartSpread = mSegmentSpread;
                if (!mSink.pinchStarted()) {
                    mMode = Mode.CONSUMED;
                    return;
                }
                mMode = Mode.PINCH;
                pinchTo(mSpread);
                return;
            }
            mMode = Mode.SCROLL;
            // Precision touchpads rail-lock scrolling to the dominant axis.
            mScrollAxis = Math.abs(mSegmentX) > Math.abs(mSegmentY)
                    ? ScrollAxis.HORIZONTAL : ScrollAxis.VERTICAL;
            mScrollTravelX = 0.0f;
            mScrollTravelY = 0.0f;
            scrollBy(mSegmentX, mSegmentY);
            return;
        }
        if (mMode == Mode.SCROLL) {
            scrollBy(deltaX, deltaY);
        } else if (mMode == Mode.PINCH && mSpread != previousSpread) {
            pinchTo(mSpread);
        }
    }

    private void pinchTo(final float spread) {
        if (Float.isNaN(spread) || Float.isNaN(mPinchStartSpread)
                || spread <= 0.0f || mPinchStartSpread <= 0.0f) {
            return;
        }
        if (!mSink.pinch(spread / mPinchStartSpread)) {
            endPinch();
            mMode = Mode.CONSUMED;
        }
    }

    private void endPinch() {
        if (!Float.isNaN(mPinchStartSpread)) {
            mPinchStartSpread = Float.NaN;
            mSink.pinchEnded();
        }
    }

    private void scrollBy(final float deltaX, final float deltaY) {
        mScrollTravelX += deltaX;
        mScrollTravelY += deltaY;
        final float vertical = mScrollAxis == ScrollAxis.VERTICAL
                ? -deltaY / mConfig.scrollStep : 0.0f;
        final float horizontal = mScrollAxis == ScrollAxis.HORIZONTAL
                ? deltaX / mConfig.scrollStep : 0.0f;
        if (!mSink.scroll(vertical, horizontal)) {
            mMode = Mode.CONSUMED;
        }
    }

    private void moveManyFingers() {
        if (mMode == Mode.UNDECIDED) {
            final float absX = Math.abs(mSegmentX);
            final float absY = Math.abs(mSegmentY);
            if (Math.max(absX, absY) < mConfig.swipeDistance) {
                return;
            }
            if (absY >= absX) {
                mMode = Mode.CONSUMED;
                if (mSink.perform(mSegmentY < 0.0f
                        ? Action.TASK_VIEW : Action.SHOW_DESKTOP)) {
                    mSink.gestureFeedback();
                }
                return;
            }
            final boolean reverse = mSegmentX < 0.0f;
            if (!mSink.switchApplication(reverse)) {
                mMode = Mode.CONSUMED;
                return;
            }
            mSink.gestureFeedback();
            mMode = Mode.SWITCHING;
            mSwitchAnchor = mSegmentX;
            return;
        }
        if (mMode != Mode.SWITCHING) {
            return;
        }
        // While fingers stay down, each further step moves the Alt+Tab selection.
        while (Math.abs(mSegmentX - mSwitchAnchor) >= mConfig.switchStep) {
            final boolean reverse = mSegmentX < mSwitchAnchor;
            mSwitchAnchor += reverse ? -mConfig.switchStep : mConfig.switchStep;
            if (!mSink.switchApplication(reverse)) {
                mSink.cancelApplicationSwitch();
                mMode = Mode.CONSUMED;
                return;
            }
            mSink.gestureFeedback();
        }
    }

    /** Called by the view's long-press detector; returns true when accepted. */
    boolean longPress() {
        if (!mActive
                || mMaxContacts != 1
                || mMode != Mode.UNDECIDED
                || mTapDragArmed) {
            return false;
        }
        mLongPressed = true;
        // Hold the button like a physical touchpad press-and-hold, so the
        // application sees its own long press (e.g. message text selection).
        if (!mSink.setPrimaryPressed(true)) {
            mMode = Mode.CONSUMED;
            return true;
        }
        mPrimaryPressed = true;
        mMode = Mode.DRAG;
        mDragSlopPending = true;
        mDragAnchorX = mLastX;
        mDragAnchorY = mLastY;
        return true;
    }

    void up(final long time) {
        if (!mActive) {
            return;
        }
        final Mode mode = mMode;
        final int contacts = mMaxContacts;
        final boolean tapped = mode == Mode.UNDECIDED
                && mTravel <= mConfig.touchSlop * Math.max(1, contacts);
        final boolean quick = time - mDownTime <= mConfig.multiTapTimeoutMillis;
        mLastTapTime = Long.MIN_VALUE;
        if (mode == Mode.DRAG) {
            releasePrimary();
        } else if (mode == Mode.SCROLL) {
            if (mNavigationEnabled
                    && mScrollAxis == ScrollAxis.HORIZONTAL
                    && time - mDownTime <= NAVIGATION_FLICK_MILLIS
                    && Math.abs(mScrollTravelX)
                            >= mConfig.swipeDistance * NAVIGATION_DISTANCE_SWIPES
                    && Math.abs(mScrollTravelY)
                            <= Math.abs(mScrollTravelX) * NAVIGATION_MAX_VERTICAL_RATIO) {
                mSink.navigate(mScrollTravelX > 0.0f);
                mSink.gestureFeedback();
            } else {
                mSink.scrollReleased(mScrollAxis == ScrollAxis.HORIZONTAL);
            }
        } else if (mode == Mode.PINCH) {
            endPinch();
        } else if (mode == Mode.SWITCHING) {
            mSink.finishApplicationSwitch();
        } else if (tapped && contacts == 1) {
            // A resting long press only arms dragging; right click is a
            // deliberate two-finger tap.
            if (!mLongPressed) {
                mSink.click(MotionEvent.BUTTON_PRIMARY);
                if (!mTapDragArmed) {
                    mLastTapTime = time;
                    mLastTapX = mDownX;
                    mLastTapY = mDownY;
                }
            }
        } else if (tapped && quick && contacts == 2) {
            mSink.click(MotionEvent.BUTTON_SECONDARY);
        } else if (tapped && quick && contacts == 3) {
            if (mSink.perform(Action.START)) {
                mSink.gestureFeedback();
            }
        } else if (tapped && quick && contacts >= 4) {
            if (mSink.perform(Action.NOTIFICATIONS)) {
                mSink.gestureFeedback();
            }
        }
        reset();
    }

    void cancel() {
        if (!mActive) {
            return;
        }
        if (mMode == Mode.DRAG) {
            releasePrimary();
        } else if (mMode == Mode.SWITCHING) {
            mSink.cancelApplicationSwitch();
        } else if (mMode == Mode.PINCH) {
            endPinch();
        }
        mLastTapTime = Long.MIN_VALUE;
        reset();
    }

    boolean isActive() {
        return mActive;
    }

    /** Whether a quick horizontal two-finger flick is Back / Forward. */
    void setNavigationEnabled(final boolean enabled) {
        mNavigationEnabled = enabled;
    }

    private void releasePrimary() {
        if (mPrimaryPressed) {
            mPrimaryPressed = false;
            mSink.setPrimaryPressed(false);
        }
    }

    private void reset() {
        mActive = false;
        mMode = Mode.UNDECIDED;
        mScrollAxis = ScrollAxis.NONE;
        mContacts = 0;
        mMaxContacts = 0;
        mTravel = 0.0f;
        mSegmentX = 0.0f;
        mSegmentY = 0.0f;
        mSwitchAnchor = 0.0f;
        mLongPressed = false;
        mTapDragArmed = false;
        mDragSlopPending = false;
        mSpread = Float.NaN;
        mSegmentSpread = Float.NaN;
        mPinchStartSpread = Float.NaN;
        mScrollTravelX = 0.0f;
        mScrollTravelY = 0.0f;
    }
}
