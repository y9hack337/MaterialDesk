package io.github.mekhontsev.magicdesk;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Phone-side touch surface for every external MagicDesk display.
 *
 * <p>Gestures follow Windows Precision Touchpad conventions; see
 * {@link TouchpadGestureRecognizer}. Closing requires holding the close
 * button or pressing Back twice, and edge swipes first reveal hidden system
 * bars, so an accidental gesture does not leave the touchpad.
 *
 * <p>The phone screen is pure black. Its few lit controls dim with the
 * backlight after a short idle period and shift by a few pixels over time;
 * see {@link TouchpadScreenGuard}.
 */
public final class MagicDeskTouchpadActivity extends Activity {
    private static final String TAG = "MagicDeskTouchpad";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    /** Deliberate hold required by the close button. */
    private static final long HOLD_TO_CLOSE_MILLIS = 700L;
    private static final long HOLD_RELEASE_ANIMATION_MILLIS = 150L;
    /** Longest contact still treated as a two-, three- or four-finger tap. */
    private static final long MULTI_FINGER_TAP_TIMEOUT_MILLIS = 350L;
    /** Three-finger travel that selects a swipe direction. */
    private static final float SWIPE_DISTANCE_DP = 56.0f;
    /** Further three-finger travel that advances the Alt+Tab selection. */
    private static final float SWITCH_STEP_DP = 80.0f;
    private static final String EXTRA_TARGET_DISPLAY_ID =
            "io.github.mekhontsev.magicdesk.extra.TOUCHPAD_DISPLAY_ID";
    private static final Object STATE_LOCK = new Object();
    private static WeakReference<MagicDeskTouchpadActivity> sVisibleActivity =
            new WeakReference<>(null);
    private static int sRequestedDisplayId = Display.INVALID_DISPLAY;

    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;
    private int mTargetDisplayId = Display.INVALID_DISPLAY;
    /** Surface drag and the Left button share one primary-button press. */
    private int mPrimaryHolds;
    private final TouchpadExitGuard mExitGuard = new TouchpadExitGuard();
    private Toast mHint;
    private final Handler mProtectionHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDimAfterIdle = this::dimControls;
    private final Runnable mShiftPixels = this::shiftPixels;
    /** Lit controls that dim together while the touchpad is idle. */
    private final java.util.List<View> mDimmableViews = new java.util.ArrayList<>();
    private View mShiftRoot;
    private View mSurfaceHint;
    private boolean mDimmed;
    private int mShiftStep;
    private FrameLayout mContentContainer;
    private ImageButton mHelpButton;
    private TouchSurface mSurfaceView;
    private ScrollView mHelpView;
    private OnBackInvokedCallback mBackCallback;
    private PopupWindow mTouchSurface;
    private boolean mStarted;
    private final java.util.List<View> mDesktopActions = new java.util.ArrayList<>();

    static void refreshInputControls() {
        final MagicDeskTouchpadActivity activity;
        synchronized (STATE_LOCK) { activity = sVisibleActivity.get(); }
        if (activity != null) { activity.runOnUiThread(activity::updateDesktopActions); }
    }

    private void updateDesktopActions() {
        final boolean desktop = DesktopRuntimeBridge.getDesktopTarget(mTargetDisplayId) != null;
        for (final View view : mDesktopActions) {
            view.setEnabled(desktop);
            view.setAlpha(desktop ? 1f : 0.4f);
        }
    }

    static void open(final Context context, final int displayId) {
        if (context == null || displayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        synchronized (STATE_LOCK) {
            sRequestedDisplayId = displayId;
        }
        final Intent intent = createLaunchIntent(context, displayId);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        DesktopShellActivity.setLaunchWindowingMode(
                options, WINDOWING_MODE_FULLSCREEN);
        context.startActivity(intent, options.toBundle());
    }

    static Intent createLaunchIntent(final Context context, final int displayId) {
        return new Intent(context, MagicDeskTouchpadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TARGET_DISPLAY_ID, displayId);
    }

    static boolean isRequested(final int displayId) {
        synchronized (STATE_LOCK) {
            return sRequestedDisplayId == displayId;
        }
    }

    static boolean isVisible(final int displayId) {
        synchronized (STATE_LOCK) {
            final MagicDeskTouchpadActivity activity = sVisibleActivity.get();
            return activity != null
                    && !activity.isFinishing()
                    && !activity.isDestroyed()
                    && activity.mTargetDisplayId == displayId;
        }
    }

    static boolean startIfRequested(
            final Context context,
            final int displayId) {
        if (!isRequested(displayId)) {
            return false;
        }
        open(context, displayId);
        return true;
    }

    static boolean bringRequestedTaskToFront(
            final Context context,
            final int displayId) {
        if (context == null || !isRequested(displayId)) {
            return false;
        }
        final ActivityManager activityManager =
                context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return false;
        }
        final ComponentName expected = new ComponentName(
                context, MagicDeskTouchpadActivity.class);
        for (final ActivityManager.AppTask appTask
                : activityManager.getAppTasks()) {
            final ActivityManager.RecentTaskInfo taskInfo =
                    appTask.getTaskInfo();
            if (!expected.equals(taskInfo.topActivity)
                    && !expected.equals(taskInfo.baseActivity)) {
                continue;
            }
            appTask.moveToFront();
            return true;
        }
        return false;
    }

    static void release(final int displayId) {
        final MagicDeskTouchpadActivity activity;
        synchronized (STATE_LOCK) {
            if (sRequestedDisplayId == displayId) {
                sRequestedDisplayId = Display.INVALID_DISPLAY;
            }
            activity = sVisibleActivity.get();
        }
        if (activity != null && activity.mTargetDisplayId == displayId) {
            activity.runOnUiThread(activity::finish);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDisplayManager = getSystemService(DisplayManager.class);
        updateTargetDisplay(getIntent());
        final FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(Color.BLACK);
        setContentView(host);
        // Touching this child must not take input focus from the external
        // editor. The ordinary Activity remains a valid phone focus/Back host;
        // INPUT_METHOD_NEEDED keeps the child below Android's keyboard.
        mTouchSurface = new PopupWindow(createContent(),
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, false);
        mTouchSurface.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        mTouchSurface.setIsLaidOutInScreen(true);
        mTouchSurface.setIsClippedToScreen(true);
        mTouchSurface.setAnimationStyle(0);
        applyGestureProtection();
        mBackCallback = this::handleBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateTargetDisplay(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;
        synchronized (STATE_LOCK) {
            sVisibleActivity = new WeakReference<>(this);
        }
        recordAutomationVisibility(true);
        DesktopSelfTestPhoneUiObserver.noteTouchpadStarted(mTargetDisplayId);
        registerDisplayListener();
        finishIfTargetUnavailable();
        showTouchSurface();
        if (mSurfaceView != null) {
            // Settings may have changed while the touchpad was closed.
            mSurfaceView.applySettings(MagicDeskSettings.load());
        }
        prepareCursorTracking();
        wakeControls();
        scheduleProtection(mShiftPixels, TouchpadScreenGuard.SHIFT_INTERVAL_MILLIS);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        showTouchSurface();
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyGestureProtection();
        }
    }

    private void showTouchSurface() {
        final View decor = getWindow().getDecorView();
        if (mStarted && !isFinishing() && mTouchSurface != null
                && !mTouchSurface.isShowing() && decor.getWindowToken() != null) {
            mTouchSurface.showAtLocation(decor, Gravity.TOP | Gravity.LEFT, 0, 0);
        }
    }

    @Override
    protected void onStop() {
        mStarted = false;
        mTouchSurface.dismiss();
        finishPointerDrag();
        mProtectionHandler.removeCallbacks(mDimAfterIdle);
        mProtectionHandler.removeCallbacks(mShiftPixels);
        setBacklightDimmed(false);
        if (mHint != null) {
            mHint.cancel();
            mHint = null;
        }
        DesktopSelfTestPhoneUiObserver.noteTouchpadStopped(mTargetDisplayId);
        synchronized (STATE_LOCK) {
            if (sVisibleActivity.get() == this) {
                sVisibleActivity.clear();
            }
        }
        recordAutomationVisibility(false);
        unregisterDisplayListener();
        super.onStop();
    }

    private void recordAutomationVisibility(final boolean visible) {
        recordAutomationVisibility(visible, mTargetDisplayId);
    }

    private void recordAutomationVisibility(
            final boolean visible,
            final int displayId) {
        try {
            DesktopAutomationEventJournal.record(
                    "ui",
                    visible ? "touchpad_shown" : "touchpad_hidden",
                    true,
                    "display=" + displayId,
                    new org.json.JSONObject()
                            .put("displayId", displayId)
                            .put("visible", visible));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "ui",
                    visible ? "touchpad_shown" : "touchpad_hidden",
                    true,
                    "display=" + displayId);
        }
    }

    @Override
    protected void onDestroy() {
        if (mTouchSurface != null) {
            mTouchSurface.dismiss();
        }
        if (mBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    mBackCallback);
            mBackCallback = null;
        }
        super.onDestroy();
    }

    private View createContent() {
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        final LinearLayout root = new LinearLayout(this) {
            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_POINTER_DOWN) {
                    wakeControls();
                }
                return super.dispatchTouchEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMotionEventSplittingEnabled(true);
        // Pure black leaves OLED pixels off; only thin outlines and icons are lit.
        root.setBackgroundColor(Color.BLACK);
        mShiftRoot = root;
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout()
                            | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));

        final HoldToCloseButton close = new HoldToCloseButton(this, ui);
        header.addView(close, new LinearLayout.LayoutParams(
                ui.dp(48), ui.dp(48)));

        final LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(ui.dp(14), 0, ui.dp(8), 0);
        final TextView title = new TextView(this);
        title.setText(R.string.touchpad_title);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(DesktopUiFactory.medium());
        title.setSingleLine(true);
        titles.addView(title);
        final TextView subtitle = new TextView(this);
        subtitle.setText(R.string.touchpad_close_hint);
        subtitle.setTextColor(DesktopUiFactory.COLOR_MUTED);
        subtitle.setTextSize(12);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final ImageButton fullscreen = headerButton(
                ui,
                R.drawable.ic_arrow_up,
                R.string.touchpad_fullscreen_current_window,
                view -> manageActiveWindow(
                        DesktopTaskController.SHORTCUT_FULLSCREEN));
        mDesktopActions.add(fullscreen);
        header.addView(fullscreen, headerButtonParams(ui));

        final ImageButton restore = headerButton(
                ui,
                R.drawable.ic_arrow_down,
                R.string.touchpad_restore_current_window,
                view -> manageActiveWindow(
                        DesktopTaskController.SHORTCUT_RESTORE));
        mDesktopActions.add(restore);
        header.addView(restore, headerButtonParams(ui));

        final ImageButton desktop = headerButton(
                ui,
                R.drawable.ic_show_desktop,
                R.string.touchpad_present_desktop_workspace,
                view -> presentDesktopWorkspace());
        mDesktopActions.add(desktop);
        header.addView(desktop, headerButtonParams(ui));
        updateDesktopActions();

        mHelpButton = headerButton(
                ui,
                R.drawable.ic_help,
                R.string.touchpad_help,
                view -> toggleHelp());
        header.addView(mHelpButton, headerButtonParams(ui));

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        mDimmableViews.add(header);

        mContentContainer = new FrameLayout(this);
        final TouchSurface touchSurface = new TouchSurface(this);
        mSurfaceView = touchSurface;
        touchSurface.setBackground(ui.rounded(
                Color.BLACK,
                ui.dp(DesktopUiFactory.SHAPE_EXTRA_LARGE_DP),
                DesktopUiFactory.COLOR_OUTLINE_VARIANT));
        mDimmableViews.add(touchSurface);
        touchSurface.setContentDescription(getString(R.string.touchpad_title));
        mContentContainer.addView(touchSurface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mSurfaceHint = createSurfaceHint(ui);
        mDimmableViews.add(mSurfaceHint);
        mContentContainer.addView(mSurfaceHint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        final LinearLayout.LayoutParams surfaceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        // Clicks are taps; the whole lower area is touch surface.
        surfaceParams.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        root.addView(mContentContainer, surfaceParams);
        return root;
    }

    /** Faint centered guide; it never receives touches. */
    private View createSurfaceHint(final DesktopUiFactory ui) {
        final LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setClickable(false);
        hint.setFocusable(false);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_touchpad);
        icon.setColorFilter(DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_MUTED, 0.55f));
        hint.addView(icon, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        final TextView text = new TextView(this);
        text.setText(R.string.touchpad_surface_hint);
        text.setTextColor(DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_MUTED, 0.55f));
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        text.setPadding(ui.dp(24), ui.dp(10), ui.dp(24), 0);
        hint.addView(text);
        return hint;
    }

    private ImageButton headerButton(
            final DesktopUiFactory ui,
            final int iconResource,
            final int descriptionResource,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setColorFilter(DesktopUiFactory.COLOR_MUTED);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(outlinedControl(ui, ui.dp(DesktopUiFactory.SHAPE_FULL_DP)));
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        button.setContentDescription(getString(descriptionResource));
        button.setTooltipText(getString(descriptionResource));
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            mExitGuard.onInteraction();
            listener.onClick(view);
        });
        return button;
    }

    /** Black control with a thin outline; only the pressed state is filled. */
    private static StateListDrawable outlinedControl(
            final DesktopUiFactory ui, final int radius) {
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {android.R.attr.state_pressed},
                DesktopUiFactory.filled(
                        DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.16f),
                        radius));
        background.addState(new int[0], ui.rounded(
                Color.BLACK, radius, DesktopUiFactory.COLOR_OUTLINE_VARIANT));
        return background;
    }

    private void scheduleProtection(final Runnable action, final long delayMillis) {
        mProtectionHandler.removeCallbacks(action);
        if (mStarted) {
            RuntimeDelays.schedule(mProtectionHandler, action,
                    RuntimeDelays.Reason.DISPLAY_PROTECTION, delayMillis);
        }
    }

    /** Restores lit controls on any touch and restarts the idle period. */
    private void wakeControls() {
        if (mDimmed) {
            mDimmed = false;
            setBacklightDimmed(false);
            animateDimmables(1.0f, 120L);
        }
        scheduleProtection(mDimAfterIdle, TouchpadScreenGuard.IDLE_DIM_MILLIS);
    }

    private void dimControls() {
        if (!mStarted || mDimmed) {
            return;
        }
        if (mHelpView != null) {
            // Reading help is not idle use.
            scheduleProtection(mDimAfterIdle, TouchpadScreenGuard.IDLE_DIM_MILLIS);
            return;
        }
        mDimmed = true;
        setBacklightDimmed(true);
        animateDimmables(TouchpadScreenGuard.DIMMED_ALPHA, 600L);
    }

    private void animateDimmables(final float alpha, final long duration) {
        for (final View view : mDimmableViews) {
            view.animate().alpha(alpha).setDuration(duration).start();
        }
    }

    private void setBacklightDimmed(final boolean dimmed) {
        final WindowManager.LayoutParams attributes = getWindow().getAttributes();
        final float brightness = dimmed
                ? TouchpadScreenGuard.DIMMED_BRIGHTNESS
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (attributes.screenBrightness != brightness) {
            attributes.screenBrightness = brightness;
            getWindow().setAttributes(attributes);
        }
    }

    /** Moves every lit pixel slightly so static controls cannot burn in. */
    private void shiftPixels() {
        if (!mStarted || mShiftRoot == null) {
            return;
        }
        mShiftStep = (mShiftStep + 1) % TouchpadScreenGuard.shiftSteps();
        final int[] offset = TouchpadScreenGuard.shiftOffset(mShiftStep,
                new DesktopUiFactory(this).dp(TouchpadScreenGuard.MAX_SHIFT_DP));
        mShiftRoot.setTranslationX(offset[0]);
        mShiftRoot.setTranslationY(offset[1]);
        scheduleProtection(mShiftPixels, TouchpadScreenGuard.SHIFT_INTERVAL_MILLIS);
    }

    private static LinearLayout.LayoutParams headerButtonParams(
            final DesktopUiFactory ui) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
        params.setMarginStart(ui.dp(6));
        return params;
    }

    private void manageActiveWindow(final int shortcut) {
        if (DesktopRuntimeBridge.getDesktopTarget(mTargetDisplayId) == null) { return; }
        hideHelp();
        DesktopOperations.manageActiveWindow(shortcut);
    }

    private void presentDesktopWorkspace() {
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(mTargetDisplayId);
        if (target != null && target.workspaceDisplayId == mTargetDisplayId) {
            DesktopOperations.presentDesktopWorkspace(target, null);
        }
    }

    private void toggleHelp() {
        if (mHelpView != null) {
            hideHelp();
            return;
        }
        if (mContentContainer == null) {
            return;
        }
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        mHelpView = TouchpadHelpContent.create(this, ui);
        mContentContainer.addView(mHelpView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mHelpButton.setColorFilter(DesktopUiFactory.COLOR_ACCENT);
    }

    private void hideHelp() {
        if (mHelpView == null) {
            return;
        }
        if (mContentContainer != null) {
            mContentContainer.removeView(mHelpView);
        }
        mHelpView = null;
        if (mHelpButton != null) {
            mHelpButton.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        }
    }

    private void handleBack() {
        if (mHelpView != null) {
            hideHelp();
            return;
        }
        // Edge Back gestures are easy to trigger while using a touchpad, so a
        // single Back never closes it.
        if (mExitGuard.onBack(SystemClock.uptimeMillis())
                == TouchpadExitGuard.Decision.CLOSE) {
            dismissFromUser();
            return;
        }
        showHint(R.string.touchpad_back_again_to_close);
    }

    private void showHint(final int messageResource) {
        if (mHint != null) {
            mHint.cancel();
        }
        mHint = Toast.makeText(this, messageResource, Toast.LENGTH_SHORT);
        mHint.show();
    }

    /** Keeps edge swipes on the touchpad instead of Android navigation. */
    private void applyGestureProtection() {
        final Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private boolean pressPrimary(final boolean pressed) {
        if (pressed) {
            if (mPrimaryHolds++ > 0) {
                return true;
            }
            final boolean accepted = MagicDeskRuntime.setPointerButtonPressed(
                    mTargetDisplayId, MotionEvent.BUTTON_PRIMARY, true);
            if (!accepted) {
                mPrimaryHolds = 0;
            }
            return accepted;
        }
        if (mPrimaryHolds == 0 || --mPrimaryHolds > 0) {
            return true;
        }
        return MagicDeskRuntime.setPointerButtonPressed(
                mTargetDisplayId, MotionEvent.BUTTON_PRIMARY, false);
    }

    private void finishPointerDrag() {
        if (mPrimaryHolds == 0) {
            return;
        }
        mPrimaryHolds = 0;
        MagicDeskRuntime.setPointerButtonPressed(
                mTargetDisplayId,
                MotionEvent.BUTTON_PRIMARY,
                false);
    }

    private void updateTargetDisplay(final Intent intent) {
        final int targetDisplayId = intent == null
                ? Display.INVALID_DISPLAY
                : intent.getIntExtra(
                        EXTRA_TARGET_DISPLAY_ID,
                        Display.INVALID_DISPLAY);
        if (PhoneTouchpadController.isSupported(targetDisplayId)) {
            // Direct notification launches do not pass through open(). Only
            // an accepted, live desktop target can acquire the input request.
            synchronized (STATE_LOCK) {
                sRequestedDisplayId = targetDisplayId;
            }
            MagicDeskRuntime.setPhoneTouchpadRequested(true);
        }
        if (targetDisplayId == mTargetDisplayId) {
            finishIfTargetUnavailable();
            return;
        }

        final int previousDisplayId = mTargetDisplayId;
        final boolean visible;
        synchronized (STATE_LOCK) {
            visible = sVisibleActivity.get() == this;
        }
        if (visible && previousDisplayId > Display.DEFAULT_DISPLAY) {
            recordAutomationVisibility(false, previousDisplayId);
            DesktopSelfTestPhoneUiObserver.noteTouchpadStopped(
                    previousDisplayId);
        }

        finishPointerDrag();
        mTargetDisplayId = targetDisplayId;

        if (visible && targetDisplayId > Display.DEFAULT_DISPLAY) {
            recordAutomationVisibility(true, targetDisplayId);
            DesktopSelfTestPhoneUiObserver.noteTouchpadStarted(
                    targetDisplayId);
        }
        prepareCursorTracking();
        finishIfTargetUnavailable();
    }

    /** Pinches zoom at the cursor, so its position is followed in advance. */
    private void prepareCursorTracking() {
        final int displayId = mTargetDisplayId;
        if (displayId > Display.DEFAULT_DISPLAY) {
            DesktopOperations.executeSerialized(() -> MagicDeskRuntime.pinchPointer(
                    displayId, TouchpadPinchInjector.PHASE_PREPARE, 1.0f));
        }
    }

    private void registerDisplayListener() {
        if (mDisplayManager == null || mDisplayListener != null) {
            return;
        }
        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(final int displayId) {
            }

            @Override
            public void onDisplayRemoved(final int displayId) {
                if (displayId == mTargetDisplayId) {
                    finish();
                }
            }

            @Override
            public void onDisplayChanged(final int displayId) {
                if (displayId == mTargetDisplayId) {
                    finishIfTargetUnavailable();
                }
            }
        };
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
    }

    private void unregisterDisplayListener() {
        if (mDisplayManager != null && mDisplayListener != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        mDisplayListener = null;
    }

    private void finishIfTargetUnavailable() {
        if (mTargetDisplayId <= Display.DEFAULT_DISPLAY
                || !PhoneTouchpadController.isSupported(mTargetDisplayId)
                || mDisplayManager == null
                || mDisplayManager.getDisplay(mTargetDisplayId) == null) {
            clearRequestedDisplay();
            finish();
        }
    }

    private void dismissFromUser() {
        clearRequestedDisplay();
        finish();
    }

    private void clearRequestedDisplay() {
        boolean cleared = false;
        synchronized (STATE_LOCK) {
            if (sRequestedDisplayId == mTargetDisplayId) {
                sRequestedDisplayId = Display.INVALID_DISPLAY;
                cleared = true;
            }
        }
        if (cleared) {
            MagicDeskRuntime.setPhoneTouchpadRequested(false);
        }
    }

    /**
     * Close control that requires a deliberate hold. A short tap only explains
     * how to close; accessibility services close it with a long-click action.
     */
    private final class HoldToCloseButton extends ImageButton {
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mArc = new RectF();
        private final Runnable mCloseAfterHold = this::closeAfterHold;
        private ValueAnimator mProgressAnimator;
        private float mProgress;
        private boolean mHolding;

        HoldToCloseButton(final Context context, final DesktopUiFactory ui) {
            super(context);
            setImageResource(R.drawable.ic_close);
            setColorFilter(DesktopUiFactory.COLOR_MUTED);
            setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            setBackground(outlinedControl(ui, ui.dp(DesktopUiFactory.SHAPE_FULL_DP)));
            setStateListAnimator(null);
            setContentDescription(getString(R.string.touchpad_close_hold_description));
            setTooltipText(getString(R.string.touchpad_close_hold_description));
            setOnClickListener(view -> showHint(R.string.touchpad_hold_to_close));
            setOnLongClickListener(view -> {
                dismissFromUser();
                return true;
            });
            final float stroke = ui.dp(3);
            mTrackPaint.setStyle(Paint.Style.STROKE);
            mTrackPaint.setStrokeWidth(stroke);
            mTrackPaint.setColor(DesktopUiFactory.withAlpha(
                    DesktopUiFactory.COLOR_ACCENT, 0.25f));
            mProgressPaint.setStyle(Paint.Style.STROKE);
            mProgressPaint.setStrokeWidth(stroke);
            mProgressPaint.setStrokeCap(Paint.Cap.ROUND);
            mProgressPaint.setColor(DesktopUiFactory.COLOR_ACCENT);
        }

        @Override
        public boolean onTouchEvent(final MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mHolding = true;
                    setPressed(true);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    animateProgress(1.0f, HOLD_TO_CLOSE_MILLIS);
                    RuntimeDelays.schedule(
                            getHandler(), mCloseAfterHold,
                            RuntimeDelays.Reason.INPUT_GESTURE,
                            HOLD_TO_CLOSE_MILLIS);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mHolding) {
                        releaseHold();
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    releaseHold();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            // A short press only explains how to close; see the click listener.
            return super.performClick();
        }

        @Override
        protected void onDetachedFromWindow() {
            releaseHold();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            final float inset = mTrackPaint.getStrokeWidth() / 2.0f;
            mArc.set(inset, inset, getWidth() - inset, getHeight() - inset);
            if (mHolding || mProgress > 0.0f) {
                canvas.drawOval(mArc, mTrackPaint);
                canvas.drawArc(mArc, -90.0f, 360.0f * mProgress, false, mProgressPaint);
            }
        }

        private void releaseHold() {
            mHolding = false;
            setPressed(false);
            removeCallbacks(mCloseAfterHold);
            animateProgress(0.0f, HOLD_RELEASE_ANIMATION_MILLIS);
        }

        private void closeAfterHold() {
            if (!mHolding) {
                return;
            }
            mHolding = false;
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            dismissFromUser();
        }

        private void animateProgress(final float target, final long duration) {
            if (mProgressAnimator != null) {
                mProgressAnimator.cancel();
            }
            mProgressAnimator = ValueAnimator.ofFloat(mProgress, target);
            mProgressAnimator.setDuration(duration);
            mProgressAnimator.setInterpolator(new LinearInterpolator());
            mProgressAnimator.addUpdateListener(animation -> {
                mProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            mProgressAnimator.start();
        }
    }

    private final class TouchSurface extends View
            implements TouchpadGestureRecognizer.Sink, Choreographer.FrameCallback {
        /** Lifts later than this after the last movement carry no momentum. */
        private static final long MOMENTUM_LIFT_WINDOW_MILLIS = 60L;
        private final GestureDetector mLongPressDetector;
        private final TouchpadGestureRecognizer mRecognizer;
        private final TouchpadKineticScroll mKinetic = new TouchpadKineticScroll();
        private final Rect mExclusion = new Rect();
        private final float mScrollStep;
        /** -1 when scrolling is inverted in Settings. */
        private float mScrollSign = 1.0f;
        private boolean mInputResultLogged;
        /** Smoothed two-finger centroid velocity, in pixels per second. */
        private float mVelocityX;
        private float mVelocityY;
        private float mCentroidX;
        private float mCentroidY;
        private long mCentroidTime = -1L;
        /** Time of the last two-finger movement; lifting later means no momentum. */
        private long mScrollMoveTime = -1L;
        private long mEventTime;
        private long mLastFrameNanos = -1L;

        TouchSurface(final Context context) {
            super(context);
            final ViewConfiguration configuration = ViewConfiguration.get(context);
            final float density = context.getResources().getDisplayMetrics().density;
            final float touchSlop = configuration.getScaledTouchSlop();
            mScrollStep = Math.max(1.0f, touchSlop * 2.0f);
            mRecognizer = new TouchpadGestureRecognizer(
                    new TouchpadGestureRecognizer.Config(
                            touchSlop,
                            mScrollStep,
                            SWIPE_DISTANCE_DP * density,
                            SWITCH_STEP_DP * density,
                            MULTI_FINGER_TAP_TIMEOUT_MILLIS,
                            ViewConfiguration.getDoubleTapTimeout(),
                            configuration.getScaledDoubleTapSlop()),
                    this);
            mLongPressDetector = new GestureDetector(
                    context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(final MotionEvent event) {
                            return true;
                        }

                        @Override
                        public void onLongPress(final MotionEvent event) {
                            if (mRecognizer.longPress()) {
                                performHapticFeedback(
                                        HapticFeedbackConstants.LONG_PRESS);
                            }
                        }
                    });
            setClickable(true);
        }

        @Override
        protected void onLayout(
                final boolean changed,
                final int left,
                final int top,
                final int right,
                final int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // Android honors a bounded exclusion height per edge; the rest of
            // Back protection is the confirmation in handleBack().
            mExclusion.set(0, 0, right - left, bottom - top);
            setSystemGestureExclusionRects(List.of(mExclusion));
        }

        // Taps are recognized by TouchpadGestureRecognizer, which reaches
        // performClick() through click(BUTTON_PRIMARY).
        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(final MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mExitGuard.onInteraction();
                if (mSurfaceHint != null) {
                    // A static guide is a burn-in risk once it has been read.
                    mContentContainer.removeView(mSurfaceHint);
                    mDimmableViews.remove(mSurfaceHint);
                    mSurfaceHint = null;
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // A new touch catches the content, as on a precision touchpad.
                stopMomentum();
            }
            mEventTime = event.getEventTime();
            trackScrollVelocity(event);
            mLongPressDetector.onTouchEvent(event);
            mRecognizer.onTouchEvent(event);
            return true;
        }

        private void trackScrollVelocity(final MotionEvent event) {
            final int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_MOVE || event.getPointerCount() < 2) {
                if (action != MotionEvent.ACTION_UP
                        && action != MotionEvent.ACTION_POINTER_UP) {
                    mCentroidTime = -1L;
                    mScrollMoveTime = -1L;
                    mVelocityX = 0.0f;
                    mVelocityY = 0.0f;
                }
                if (action == MotionEvent.ACTION_POINTER_UP) {
                    // The centroid jumps when a finger lifts; keep the velocity.
                    mCentroidTime = -1L;
                }
                return;
            }
            float x = 0.0f;
            float y = 0.0f;
            for (int index = 0; index < event.getPointerCount(); index++) {
                x += event.getX(index);
                y += event.getY(index);
            }
            x /= event.getPointerCount();
            y /= event.getPointerCount();
            final long time = event.getEventTime();
            if (mCentroidTime >= 0L && time > mCentroidTime) {
                final float seconds = (time - mCentroidTime) / 1000.0f;
                // Exponential smoothing keeps one noisy sample from flinging.
                mVelocityX = 0.6f * mVelocityX + 0.4f * ((x - mCentroidX) / seconds);
                mVelocityY = 0.6f * mVelocityY + 0.4f * ((y - mCentroidY) / seconds);
            }
            mCentroidX = x;
            mCentroidY = y;
            mCentroidTime = time;
            mScrollMoveTime = time;
        }

        @Override
        public void scrollReleased(final boolean horizontalAxis) {
            final boolean recent = mScrollMoveTime >= 0L
                    && mEventTime - mScrollMoveTime <= MOMENTUM_LIFT_WINDOW_MILLIS;
            // Momentum keeps the axis the scroll was locked to, even when
            // the lifting fingers drift across it.
            final float vertical = recent && !horizontalAxis
                    ? -mVelocityY / mScrollStep : 0.0f;
            final float horizontal = recent && horizontalAxis
                    ? mVelocityX / mScrollStep : 0.0f;
            if (mKinetic.start(vertical * mScrollSign, horizontal * mScrollSign)) {
                mLastFrameNanos = -1L;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override
        public void doFrame(final long frameTimeNanos) {
            if (!mKinetic.isActive()) {
                return;
            }
            final float seconds = mLastFrameNanos < 0L
                    ? 1.0f / 60.0f : (frameTimeNanos - mLastFrameNanos) / 1.0e9f;
            mLastFrameNanos = frameTimeNanos;
            final float[] delta = mKinetic.step(seconds);
            if (delta == null || !MagicDeskRuntime.scrollPointer(
                    mTargetDisplayId, delta[0], delta[1])) {
                mKinetic.cancel();
                return;
            }
            if (mKinetic.isActive()) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        void applySettings(final MagicDeskSettings.Values settings) {
            mScrollSign = settings.touchpadInvertScrolling ? -1.0f : 1.0f;
            mRecognizer.setNavigationEnabled(settings.touchpadNavigationSwipe);
        }

        private void stopMomentum() {
            mKinetic.cancel();
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return sendClick(MotionEvent.BUTTON_PRIMARY);
        }

        @Override
        protected void onDetachedFromWindow() {
            mRecognizer.cancel();
            stopMomentum();
            super.onDetachedFromWindow();
        }

        @Override
        public boolean movePointer(final float deltaX, final float deltaY) {
            final boolean accepted = MagicDeskRuntime.movePointer(
                    mTargetDisplayId, deltaX, deltaY);
            reportInputResult("move", accepted);
            return accepted;
        }

        @Override
        public boolean click(final int button) {
            // Primary taps are ordinary view clicks, visible to accessibility.
            return button == MotionEvent.BUTTON_PRIMARY
                    ? performClick() : sendClick(button);
        }

        private boolean sendClick(final int button) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            final boolean accepted = MagicDeskRuntime.clickPointer(
                    mTargetDisplayId, button);
            reportInputResult("click", accepted);
            return accepted;
        }

        @Override
        public boolean setPrimaryPressed(final boolean pressed) {
            final boolean accepted = pressPrimary(pressed);
            if (pressed) {
                reportInputResult("drag", accepted);
            }
            return accepted;
        }

        @Override
        public boolean scroll(final float vertical, final float horizontal) {
            final boolean accepted = MagicDeskRuntime.scrollPointer(
                    mTargetDisplayId, vertical * mScrollSign, horizontal * mScrollSign);
            reportInputResult("scroll", accepted);
            return accepted;
        }

        @Override
        public boolean pinchStarted() {
            final boolean accepted = MagicDeskRuntime.pinchPointer(
                    mTargetDisplayId, TouchpadPinchInjector.PHASE_BEGIN, 1.0f);
            reportInputResult("pinch", accepted);
            return accepted;
        }

        @Override
        public boolean pinch(final float scale) {
            return MagicDeskRuntime.pinchPointer(
                    mTargetDisplayId, TouchpadPinchInjector.PHASE_UPDATE, scale);
        }

        @Override
        public void pinchEnded() {
            MagicDeskRuntime.pinchPointer(
                    mTargetDisplayId, TouchpadPinchInjector.PHASE_END, 1.0f);
        }

        @Override
        public boolean navigate(final boolean back) {
            return MagicDeskRuntime.clickPointer(mTargetDisplayId,
                    back ? MotionEvent.BUTTON_BACK : MotionEvent.BUTTON_FORWARD);
        }

        @Override
        public boolean perform(final TouchpadGestureRecognizer.Action action) {
            final boolean accepted;
            switch (action) {
                case START:
                    accepted = MagicDeskRuntime.showStart(mTargetDisplayId);
                    break;
                case NOTIFICATIONS:
                    accepted = MagicDeskRuntime.toggleNotificationCenter(
                            mTargetDisplayId);
                    break;
                case TASK_VIEW:
                    accepted = MagicDeskRuntime.toggleTaskOverview(mTargetDisplayId);
                    break;
                case SHOW_DESKTOP:
                    accepted = MagicDeskRuntime.toggleDesktopWorkspace(
                            mTargetDisplayId);
                    break;
                default:
                    accepted = false;
                    break;
            }
            Log.i(TAG, "touchpad gesture action=" + action
                    + " accepted=" + accepted
                    + " display=" + mTargetDisplayId);
            return accepted;
        }

        @Override
        public boolean switchApplication(final boolean reverse) {
            return MagicDeskRuntime.advanceAltTab(mTargetDisplayId, reverse);
        }

        @Override
        public void finishApplicationSwitch() {
            MagicDeskRuntime.finishAltTab(mTargetDisplayId);
        }

        @Override
        public void cancelApplicationSwitch() {
            MagicDeskRuntime.cancelAltTab(mTargetDisplayId);
        }

        @Override
        public void gestureFeedback() {
            performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);
        }

        private void reportInputResult(
                final String operation,
                final boolean accepted) {
            if (mInputResultLogged) {
                return;
            }
            mInputResultLogged = true;
            Log.i(TAG, "pointer input operation=" + operation
                    + " accepted=" + accepted
                    + " display=" + mTargetDisplayId);
        }
    }
}
