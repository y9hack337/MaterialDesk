package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

/**
 * Turns a phone-touchpad pinch into a real two-finger touch pinch at the
 * mouse cursor, so every application that zooms by touch zooms by touchpad.
 * Runs in the privileged service; one pinch is active at a time.
 */
final class TouchpadPinchInjector {
    static final int PHASE_PREPARE = 0;
    static final int PHASE_BEGIN = 1;
    static final int PHASE_UPDATE = 2;
    static final int PHASE_END = 3;

    private static final String TAG = "MagicDeskPinch";
    /** Distance between the synthetic fingers when the pinch begins. */
    static final float START_SPAN = 240.0f;
    static final float MIN_HALF_SPAN = 12.0f;

    private final Context mContext;
    private FrameworkPointerPositionMonitor mMonitor;
    private int mDisplayId = Display.INVALID_DISPLAY;
    private long mDownTime;
    private float mCenterX;
    private float mCenterY;
    private boolean mHorizontal;
    private float mMaxHalfSpan;

    TouchpadPinchInjector(final Context context) {
        mContext = context;
    }

    synchronized boolean handle(final int displayId, final int phase, final float scale)
            throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException("missing target display");
        }
        switch (phase) {
            case PHASE_PREPARE:
                monitor(displayId);
                return true;
            case PHASE_BEGIN:
                return begin(displayId);
            case PHASE_UPDATE:
                return mDisplayId == displayId
                        && inject(DesktopPointerInjector.PINCH_MOVE, scale);
            case PHASE_END:
                if (mDisplayId != displayId) {
                    return false;
                }
                try {
                    return inject(DesktopPointerInjector.PINCH_UP, scale);
                } finally {
                    mDisplayId = Display.INVALID_DISPLAY;
                }
            default:
                throw new IllegalArgumentException("unknown pinch phase " + phase);
        }
    }

    synchronized void close() {
        if (mMonitor != null) {
            mMonitor.close();
            mMonitor = null;
        }
    }

    private boolean begin(final int displayId) throws ReflectiveOperationException {
        if (mDisplayId != Display.INVALID_DISPLAY) {
            // A lost end: lift the previous fingers before touching again.
            inject(DesktopPointerInjector.PINCH_UP, 1.0f);
            mDisplayId = Display.INVALID_DISPLAY;
        }
        final Point size = displaySize(displayId);
        final FrameworkPointerPositionMonitor monitor = monitor(displayId);
        final float[] cursor = monitor == null ? null : monitor.position();
        final float[] layout = layout(
                cursor != null ? cursor[0] : size.x / 2.0f,
                cursor != null ? cursor[1] : size.y / 2.0f,
                size.x, size.y);
        mCenterX = layout[0];
        mCenterY = layout[1];
        mHorizontal = layout[2] > 0.0f;
        mMaxHalfSpan = layout[3];
        mDisplayId = displayId;
        mDownTime = SystemClock.uptimeMillis();
        return inject(DesktopPointerInjector.PINCH_DOWN, 1.0f);
    }

    private boolean inject(final int stage, final float scale)
            throws ReflectiveOperationException {
        final float half = halfSpan(scale, mMaxHalfSpan);
        final float dx = mHorizontal ? half : 0.0f;
        final float dy = mHorizontal ? 0.0f : half;
        DesktopPointerInjector.injectTouchPinch(mDisplayId, mDownTime, stage,
                mCenterX - dx, mCenterY - dy, mCenterX + dx, mCenterY + dy);
        return true;
    }

    /**
     * Center and axis for the synthetic fingers: {x, y, horizontal ? 1 : 0,
     * max half span}. The fingers spread along the axis with more room, so
     * both stay on the display.
     */
    static float[] layout(final float x, final float y, final int width, final int height) {
        final float cx = Math.max(0.0f, Math.min(width - 1.0f, x));
        final float cy = Math.max(0.0f, Math.min(height - 1.0f, y));
        final float roomX = Math.min(cx, width - 1.0f - cx);
        final float roomY = Math.min(cy, height - 1.0f - cy);
        final boolean horizontal = roomX >= roomY;
        return new float[] {cx, cy, horizontal ? 1.0f : 0.0f,
                Math.max(MIN_HALF_SPAN, horizontal ? roomX : roomY)};
    }

    static float halfSpan(final float scale, final float maxHalfSpan) {
        final float factor = Float.isNaN(scale) || scale <= 0.0f ? 1.0f : scale;
        final float half = START_SPAN / 2.0f * factor;
        return Math.max(MIN_HALF_SPAN,
                Math.min(Math.max(MIN_HALF_SPAN, maxHalfSpan), half));
    }

    private FrameworkPointerPositionMonitor monitor(final int displayId) {
        if (mMonitor != null && mMonitor.displayId() == displayId) {
            return mMonitor;
        }
        close();
        try {
            mMonitor = new FrameworkPointerPositionMonitor(displayId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Without a position the pinch is centered on the display.
            Log.w(TAG, "pointer position is unavailable on display " + displayId, error);
        }
        return mMonitor;
    }

    private Point displaySize(final int displayId) {
        final Point size = new Point(3840, 2160);
        if (mContext == null) {
            return size;
        }
        final DisplayManager displays = mContext.getSystemService(DisplayManager.class);
        final Display display = displays == null ? null : displays.getDisplay(displayId);
        if (display != null) {
            display.getRealSize(size);
        }
        return size;
    }
}
