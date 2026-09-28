package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;

/** Activity launch primitives; no organizer ownership, HOME, or desktop policy. */
final class FrameworkActivityLaunchApi {
    private FrameworkActivityLaunchApi() { }

    static void setWindowingMode(ActivityOptions options, int mode) throws ReflectiveOperationException {
        ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, mode);
    }

    static void setActivityType(ActivityOptions options, int type) throws ReflectiveOperationException {
        ActivityOptions.class.getMethod("setLaunchActivityType", int.class).invoke(options, type);
    }

    static void setTaskDisplayArea(ActivityOptions options, Object areaToken)
            throws ReflectiveOperationException {
        ActivityOptions.class.getMethod("setLaunchTaskDisplayArea",
                FrameworkRuntime.current().windowing().tokenClass()).invoke(options, areaToken);
    }

    static void setTask(ActivityOptions options, int taskId) throws ReflectiveOperationException {
        ActivityOptions.class.getMethod("setLaunchTaskId", int.class).invoke(options, taskId);
    }

    static void avoidMoveToFront(ActivityOptions options) throws ReflectiveOperationException {
        ActivityOptions.class.getMethod("setAvoidMoveToFront").invoke(options);
    }

    private static final int ANIM_CUSTOM = 1;

    /**
     * Opens a new task with MagicDesk's fade-and-settle animation instead of
     * the default task animation, which clips a small, scaling window with
     * the phone's large screen-corner radius and looks like an oval on a
     * desktop display. Unsupported releases keep their default animation.
     */
    static void useDesktopOpenAnimation(final ActivityOptions options) {
        try {
            // Resolve every member first, so a release without one of them
            // never receives a half-configured custom animation.
            final java.lang.reflect.Field type = field("mAnimationType");
            final java.lang.reflect.Field packageName = field("mPackageName");
            final java.lang.reflect.Field enter = field("mCustomEnterResId");
            final java.lang.reflect.Field exit = field("mCustomExitResId");
            // Task launches honor a custom animation only with this override.
            final java.lang.reflect.Field override = field("mOverrideTaskTransition");
            packageName.set(options, BuildConfig.APPLICATION_ID);
            enter.setInt(options, R.anim.desktop_window_open_enter);
            exit.setInt(options, R.anim.desktop_window_open_exit);
            override.setBoolean(options, true);
            type.setInt(options, ANIM_CUSTOM);
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            android.util.Log.w("MagicDeskLaunch", "custom open animation is unavailable",
                    unsupported);
        }
    }

    private static java.lang.reflect.Field field(final String name)
            throws NoSuchFieldException {
        final java.lang.reflect.Field field = ActivityOptions.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ActivityOptions options(final int displayId, final boolean fullscreen)
            throws ReflectiveOperationException {
        if (displayId < 0) { throw new IllegalArgumentException("invalid display"); }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        if (fullscreen) {
            setWindowingMode(options, 1);
            // Null bounds let TaskLaunchParamsModifier restore a saved freeform mode
            // on a freeform-default display, even over an explicit fullscreen request.
            options.setLaunchBounds(new Rect());
        }
        return options;
    }

    static void launch(final Object service, final Intent source, final int displayId,
            final boolean fullscreen) throws ReflectiveOperationException {
        if (source == null || source.getComponent() == null) {
            throw new IllegalArgumentException("an explicit Activity is required");
        }
        final Intent intent = new Intent(source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = options(displayId, fullscreen);
        useDesktopOpenAnimation(options);
        final int result = startActivity(service, intent, options);
        if (result < 0) { throw new IllegalStateException("startActivity returned " + result); }
    }

    static int startActivity(Object service, Intent intent, ActivityOptions options)
            throws ReflectiveOperationException {
        return (Integer) service.getClass().getMethod("startActivity",
                Class.forName("android.app.IApplicationThread"), String.class, String.class,
                Intent.class, String.class, IBinder.class, String.class, Integer.TYPE,
                Integer.TYPE, Class.forName("android.app.ProfilerInfo"), Bundle.class)
                .invoke(service, null, "com.android.shell", null, intent, null, null,
                        null, -1, 0, null, options.toBundle());
    }

    static void send(final Context context, final PendingIntent intent, final int displayId)
            throws ReflectiveOperationException, PendingIntent.CanceledException {
        if (intent == null || !intent.isActivity()) {
            throw new IllegalArgumentException("an Activity PendingIntent is required");
        }
        final ActivityOptions options = options(displayId, true);
        AndroidPendingIntentOptions.allowSenderStart(options, false);
        intent.send(context, 0, null, null, null, null, options.toBundle());
    }

    static void moveTask(final Object service, final int taskId, final int sourceDisplayId,
            final int targetDisplayId, final int userId) throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.requireTask(service, sourceDisplayId, taskId);
        if (HiddenTaskApi.getTaskUserId(task) != userId) {
            throw new IllegalArgumentException("task profile changed");
        }
        if (HiddenTaskApi.getTaskActivityType(task) != FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD) {
            throw new IllegalArgumentException("only application tasks can be transferred");
        }
        // Existing-task launch preserves the Activity instance and lets WM own
        // the display transition. It does not acquire a Desktop organizer.
        final int result = HiddenTaskApi.startActivityFromRecents(
                service, taskId, options(targetDisplayId, true).toBundle());
        if (result < 0) { throw new IllegalStateException("startActivityFromRecents returned " + result); }
    }
}
