package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Observes the mouse cursor position on one display through an Android
 * gesture monitor. The monitor only copies events: it never pilfers or
 * consumes them, and it finishes every event at once.
 */
// Only instantiated in the authorized app_process service, never the app sandbox.
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class FrameworkPointerPositionMonitor implements AutoCloseable {
    private final int mDisplayId;
    private final HandlerThread mThread;
    private final InputMonitor mMonitor;
    private final Receiver mReceiver;
    private final Object mLock = new Object();
    private boolean mKnown;
    private float mX;
    private float mY;

    FrameworkPointerPositionMonitor(final int displayId) throws ReflectiveOperationException {
        mDisplayId = displayId;
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        final Object manager = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        final Method monitor = Class.forName("android.hardware.input.IInputManager")
                .getMethod("monitorGestureInput", IBinder.class, String.class, int.class);
        mMonitor = (InputMonitor) monitor.invoke(
                manager, new Binder(), "MagicDesk pointer position", displayId);
        mThread = new HandlerThread("MagicDeskPointerPosition");
        mThread.start();
        mReceiver = new Receiver(mMonitor.getInputChannel(), mThread);
    }

    int displayId() {
        return mDisplayId;
    }

    /** The last mouse position, or null before the mouse moved on this display. */
    float[] position() {
        synchronized (mLock) {
            return mKnown ? new float[] {mX, mY} : null;
        }
    }

    @Override
    public void close() {
        mReceiver.dispose();
        mMonitor.dispose();
        mThread.quitSafely();
    }

    private final class Receiver extends InputEventReceiver {
        Receiver(final InputChannel channel, final HandlerThread thread) {
            super(channel, thread.getLooper());
        }

        @Override
        public void onInputEvent(final InputEvent event) {
            try {
                if (event instanceof MotionEvent motion
                        && motion.isFromSource(InputDevice.SOURCE_MOUSE)
                        && motion.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                    synchronized (mLock) {
                        mX = motion.getX();
                        mY = motion.getY();
                        mKnown = true;
                    }
                }
            } finally {
                finishInputEvent(event, false);
            }
        }
    }
}
