package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.os.BatteryManager;
import android.provider.Settings;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TaskbarController {
    enum ContextArea {
        NONE,
        START,
        BLANK,
        ACTION
    }

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final ContentRequestScope mContentRequests =
            AndroidDesktopActionDispatcher.createContentScope();

    private LinearLayout mTaskbar;
    private View mStartButton;
    private LinearLayout mPins;
    private HorizontalScrollView mTaskViewport;
    /** Mirrors the tray width so the dock is centered on the display. */
    private View mDockBalance;
    /** Leading taskbar entries that are pinned applications. */
    private int mPinnedItemCount;
    /** Content of the rendered icons; unchanged snapshots skip rebuilding. */
    private String mPinSignature = "";
    private TextView mKeyboardLayout;
    private final InputMethodMenuController mInputMethodMenu;
    private final TaskbarOverflowController mOverflow;
    private BatteryIndicatorView mBatteryStatus;
    private ImageButton mSystemButton;
    private ImageButton mPhoneScreenButton;
    private Intent mLastBatteryIntent;
    private boolean mChargeSeparationEnabled;
    private final List<Integer> mTaskOrder = new ArrayList<>();
    private boolean mEdgeHidden;

    TaskbarController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mInputMethodMenu = new InputMethodMenuController(activity, ui);
        mOverflow = new TaskbarOverflowController(
                activity, ui, this::activate);
    }

    LinearLayout create() {
        final LinearLayout taskbar = new LinearLayout(mActivity) {
            private final int mTouchSlop = ViewConfiguration.get(
                    mActivity).getScaledTouchSlop();
            private float mBlankDownX;
            private float mBlankDownY;
            private boolean mBlankLongPressPending;
            private final Runnable mBlankLongPress = () -> {
                if (!mBlankLongPressPending) {
                    return;
                }
                mBlankLongPressPending = false;
                mActivity.captureInteractionStackForPanel();
                mActivity.showTaskbarContextMenu(mBlankDownX, mBlankDownY);
            };

            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                final int action = event.getActionMasked();
                if (mActivity.handleDesktopMouseTouchEvent(event, true)) {
                    cancelBlankLongPress();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelBlankLongPress();
                    if (!isActionAt(event.getX(), event.getY())) {
                        mActivity.hideAllPanels();
                        mActivity.clearInteractionVisibleTasks();
                        mBlankDownX = event.getRawX();
                        mBlankDownY = event.getRawY();
                        mBlankLongPressPending = true;
                        postDelayed(
                                mBlankLongPress,
                                ViewConfiguration.getLongPressTimeout());
                    }
                } else if (action == MotionEvent.ACTION_MOVE
                        && mBlankLongPressPending
                        && (Math.abs(event.getRawX() - mBlankDownX) > mTouchSlop
                                || Math.abs(event.getRawY() - mBlankDownY)
                                        > mTouchSlop)) {
                    cancelBlankLongPress();
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    cancelBlankLongPress();
                }
                return super.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(
                    final MotionEvent event) {
                if (mActivity.handleDesktopMouseGenericEvent(event, true)) {
                    return true;
                }
                return super.dispatchGenericMotionEvent(event);
            }

            @Override
            protected void onDetachedFromWindow() {
                cancelBlankLongPress();
                super.onDetachedFromWindow();
            }

            private void cancelBlankLongPress() {
                mBlankLongPressPending = false;
                removeCallbacks(mBlankLongPress);
            }
        };
        taskbar.setOrientation(LinearLayout.HORIZONTAL);
        taskbar.setGravity(Gravity.CENTER_VERTICAL);
        taskbar.setPadding(
                desktopDp(12, 4),
                desktopDp(6, 3),
                desktopDp(12, 4),
                desktopDp(6, 3));
        taskbar.setBackgroundColor(DesktopUiFactory.COLOR_PANEL);

        mDockBalance = new View(mActivity);
        taskbar.addView(mDockBalance, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT));

        // Material dock: Start opens the application drawer from its leading
        // icon, as on Android's desktop taskbar.
        final ImageButton start = mUi.taskbarIconButton(
                R.drawable.ic_app_drawer,
                R.string.action_start,
                mActivity.isCompactDesktopPreview());
        start.setOnClickListener(view -> mActivity.toggleStartMenu());
        start.setOnLongClickListener(view -> {
            final int[] location = new int[2];
            view.getLocationOnScreen(location);
            mActivity.captureInteractionStackForPanel();
            mActivity.showStartButtonContextMenu(
                    location[0] + view.getWidth() / 2f,
                    location[1] + view.getHeight() / 2f);
            return true;
        });
        mActivity.registerAutomationUiElement(
                start, "taskbar.start", "button",
                mActivity.getString(R.string.action_start));
        mStartButton = start;

        final HorizontalScrollView taskScroll =
                new HorizontalScrollView(mActivity);
        taskScroll.setHorizontalScrollBarEnabled(false);
        taskScroll.setFillViewport(true);
        taskScroll.addOnLayoutChangeListener((view,
                left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft && mPins != null) {
                renderPins(mActivity.getLauncherApps());
            }
        });
        mTaskViewport = taskScroll;
        final LinearLayout dock = new LinearLayout(mActivity);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.addView(start, new LinearLayout.LayoutParams(
                dockItemWidth(),
                LinearLayout.LayoutParams.MATCH_PARENT));
        mPins = new LinearLayout(mActivity);
        mPins.setOrientation(LinearLayout.HORIZONTAL);
        mPins.setGravity(Gravity.CENTER_VERTICAL);
        dock.addView(mPins, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        taskScroll.addView(dock, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        final LinearLayout.LayoutParams pinsParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        pinsParams.setMargins(
                desktopDp(8, 4), 0, desktopDp(8, 4), 0);
        taskbar.addView(taskScroll, pinsParams);

        final LinearLayout tray = new LinearLayout(mActivity);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.addOnLayoutChangeListener((view,
                left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                balanceDock(right - left);
            }
        });
        taskbar.addView(tray, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        final ImageButton screenshot = taskbarButton(
                R.drawable.ic_camera,
                R.string.region_screenshot_title);
        screenshot.setOnClickListener(view -> mActivity.startRegionScreenshot());
        mActivity.registerAutomationUiElement(
                screenshot, "taskbar.screenshot", "button",
                mActivity.getString(R.string.region_screenshot_title));
        addButton(tray, screenshot);

        final ImageButton showDesktop = taskbarButton(
                R.drawable.ic_show_desktop,
                R.string.action_show_desktop);
        showDesktop.setOnClickListener(view ->
                mActivity.toggleDesktopWorkspace());
        mActivity.registerAutomationUiElement(
                showDesktop, "taskbar.show_desktop", "button",
                mActivity.getString(R.string.action_show_desktop));
        addButton(tray, showDesktop);

        final ImageButton taskOverview = taskbarButton(
                R.drawable.ic_file_new_window,
                R.string.action_open_tasks);
        taskOverview.setOnClickListener(view ->
                mActivity.toggleTaskOverview());
        mActivity.registerAutomationUiElement(
                taskOverview, "taskbar.open_tasks", "button",
                mActivity.getString(R.string.action_open_tasks));
        addButton(tray, taskOverview);

        final View notifications =
                mActivity.notifications().createTaskbarButton(
                        mActivity.isCompactDesktopPreview());
        tray.addView(
                notifications,
                new LinearLayout.LayoutParams(
                        desktopDp(46, 38),
                        LinearLayout.LayoutParams.MATCH_PARENT));

        mKeyboardLayout = new TextView(mActivity);
        mKeyboardLayout.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mKeyboardLayout.setTextSize(
                mActivity.isCompactDesktopPreview() ? 11 : 13);
        mKeyboardLayout.setAutoSizeTextTypeUniformWithConfiguration(
                8,
                mActivity.isCompactDesktopPreview() ? 11 : 13,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        mKeyboardLayout.setTypeface(DesktopUiFactory.medium());
        mKeyboardLayout.setGravity(Gravity.CENTER);
        mKeyboardLayout.setClickable(true);
        mKeyboardLayout.setFocusable(true);
        mKeyboardLayout.setBackground(mUi.interactiveRounded(
                DesktopUiFactory.COLOR_PANEL_FOCUS,
                dp(DesktopUiFactory.SHAPE_FULL_DP),
                DesktopUiFactory.COLOR_ACCENT));
        mKeyboardLayout.setOnClickListener(mInputMethodMenu::toggle);
        mKeyboardLayout.setEnabled(
                ShellAccess.isReady());
        final LinearLayout.LayoutParams keyboardParams = new LinearLayout.LayoutParams(
                desktopDp(44, 36),
                desktopDp(30, 24));
        keyboardParams.setMargins(desktopDp(4, 2), 0, desktopDp(4, 2), 0);
        tray.addView(mKeyboardLayout, keyboardParams);
        if (mActivity.isCompactDesktopPreview()) {
            mKeyboardLayout.setVisibility(View.GONE);
        }
        mActivity.registerAutomationUiElement(
                mKeyboardLayout, "taskbar.keyboard_layout", "button",
                mActivity.getString(R.string.keyboard_layout_description,
                        ""));

        mPhoneScreenButton = taskbarButton(
                R.drawable.ic_phone_screen_off,
                R.string.tooltip_phone_screen);
        mPhoneScreenButton.setOnClickListener(view ->
                mActivity.togglePhoneScreen());
        mPhoneScreenButton.setEnabled(false);
        addButton(tray, mPhoneScreenButton);
        if (mActivity.isCompactDesktopPreview()
                || mActivity.getCurrentDisplayId() == Display.DEFAULT_DISPLAY) {
            mPhoneScreenButton.setVisibility(View.GONE);
        }
        mActivity.registerAutomationUiElement(
                mPhoneScreenButton, "taskbar.phone_screen", "button",
                mActivity.getString(R.string.tooltip_phone_screen));

        mSystemButton = taskbarButton(
                R.drawable.ic_quick_controls,
                R.string.section_quick_controls);
        mSystemButton.setOnClickListener(view ->
                mActivity.toggleSystemPanel());
        mActivity.registerAutomationUiElement(
                mSystemButton, "taskbar.quick_controls", "button",
                mActivity.getString(R.string.section_quick_controls));
        addButton(tray, mSystemButton);

        mBatteryStatus = new BatteryIndicatorView(
                mActivity, mActivity.isCompactDesktopPreview());
        mBatteryStatus.setClickable(true);
        mBatteryStatus.setFocusable(true);
        mBatteryStatus.setOnClickListener(view ->
                mActivity.toggleSystemPanel());
        mActivity.registerAutomationUiElement(
                mBatteryStatus, "taskbar.battery", "button",
                mActivity.getString(R.string.battery_status_unknown));
        mBatteryStatus.setBackground(mUi.stateLayerBackground(
                dp(DesktopUiFactory.SHAPE_FULL_DP)));
        tray.addView(mBatteryStatus, new LinearLayout.LayoutParams(
                desktopDp(60, 48),
                LinearLayout.LayoutParams.MATCH_PARENT));

        final TextClock clock = new TextClock(mActivity);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(DesktopUiFactory.COLOR_TEXT);
        clock.setTextSize(mActivity.isCompactDesktopPreview() ? 12 : 15);
        clock.setTypeface(DesktopUiFactory.medium());
        clock.setGravity(Gravity.CENTER);
        clock.setClickable(true);
        clock.setFocusable(true);
        clock.setBackground(mUi.stateLayerBackground(
                dp(DesktopUiFactory.SHAPE_FULL_DP)));
        clock.setContentDescription(
                mActivity.getString(R.string.action_calendar));
        clock.setTooltipText(mActivity.getString(R.string.action_calendar));
        clock.setOnClickListener(view -> mActivity.toggleCalendarPanel());
        mActivity.registerAutomationUiElement(
                clock, "taskbar.clock", "button",
                mActivity.getString(R.string.action_calendar));
        tray.addView(clock, new LinearLayout.LayoutParams(
                desktopDp(64, 50),
                LinearLayout.LayoutParams.MATCH_PARENT));
        mTaskbar = taskbar;
        mActivity.registerAutomationUiElement(
                taskbar, "taskbar", "taskbar", "Taskbar");
        return taskbar;
    }

    void release() {
        mContentRequests.close();
        mEdgeHidden = false;
        mTaskbar = null;
        mStartButton = null;
        mPins = null;
        mTaskViewport = null;
        mDockBalance = null;
        mPinSignature = "";
        mOverflow.release();
        mKeyboardLayout = null;
        mInputMethodMenu.release();
        mBatteryStatus = null;
        mSystemButton = null;
        mPhoneScreenButton = null;
    }

    void setVisible(final boolean visible) {
        final DesktopTaskbarHost taskbarHost = mActivity.taskbarHost();
        if (taskbarHost != null && mTaskbar != null) {
            taskbarHost.setPresented(visible);
        }
    }

    void setEdgeHidden(final boolean hidden) {
        mEdgeHidden = hidden;
        if (mTaskbar != null) {
            mTaskbar.setAlpha(hidden ? 0f : 1f);
        }
    }

    void setPhoneScreenActionEnabled(final boolean enabled) {
        if (mPhoneScreenButton != null) {
            mPhoneScreenButton.setEnabled(enabled);
        }
    }

    void renderPins(final List<AppItem> apps) {
        if (mPins == null) {
            return;
        }
        final List<TaskbarOverflowController.Entry> items =
                collectTaskbarItems(apps);
        final int itemWidth = dockItemWidth();
        // The dock shares the viewport with Start and the pinned divider.
        final int availableWidth = mTaskViewport == null
                ? 0 : Math.max(0, mTaskViewport.getWidth()
                        - dockItemWidth() - dockDividerWidth());
        final int visibleCount = TaskbarOverflowPolicy.visibleItemCount(
                items.size(), availableWidth, itemWidth);
        mOverflow.setItems(items.subList(visibleCount, items.size()));
        // Task snapshots arrive often; rebuilding unchanged icons costs frames
        // and would cancel a click or drag that is in progress.
        final String signature = pinSignature(items, visibleCount);
        if (signature.equals(mPinSignature) && mPins.getChildCount() > 0) {
            return;
        }
        mPinSignature = signature;
        mPins.removeAllViews();
        for (int index = 0; index < visibleCount; index++) {
            if (index == mPinnedItemCount && index > 0) {
                addDockDivider();
            }
            addPin(items.get(index));
        }
        if (visibleCount < items.size()) {
            addOverflowButton();
        }
    }

    /** Everything a rendered taskbar icon or its actions depend on. */
    private String pinSignature(
            final List<TaskbarOverflowController.Entry> items, final int visibleCount) {
        final StringBuilder signature = new StringBuilder()
                .append(visibleCount).append('/').append(mPinnedItemCount);
        for (final TaskbarOverflowController.Entry item : items) {
            signature.append('|').append(System.identityHashCode(item.app));
            final TaskRepository.TaskEntry task = item.task;
            if (task != null) {
                signature.append(':').append(task.taskId)
                        .append(':').append(task.displayId)
                        .append(':').append(task.windowingMode)
                        .append(':').append(task.active)
                        .append(':').append(task.visible);
            }
        }
        return signature.toString();
    }

    private List<TaskbarOverflowController.Entry> collectTaskbarItems(
            final List<AppItem> apps) {
        final List<TaskbarOverflowController.Entry> items = new ArrayList<>();
        final List<AppItem> availableApps = apps == null
                ? new ArrayList<>() : apps;
        final List<AppReference> pinnedApps = mActivity.getPinnedApps();
        final Set<Integer> renderedTaskIds = new HashSet<>();
        final List<TaskRepository.TaskEntry> orderedTasks =
                getOrderedTaskbarTasks();

        mPinnedItemCount = 0;
        for (final AppReference reference : pinnedApps) {
            final AppItem app = LauncherAppRepository.find(
                    availableApps, reference);
            if (app == null) {
                continue;
            }
            final List<TaskRepository.TaskEntry> packageTasks =
                    findTasks(orderedTasks, app);
            if (packageTasks.isEmpty()) {
                items.add(new TaskbarOverflowController.Entry(app, null));
                continue;
            }
            for (final TaskRepository.TaskEntry task : packageTasks) {
                items.add(new TaskbarOverflowController.Entry(BuiltInWindowRegistry.present(mActivity, app, task), task));
                renderedTaskIds.add(Integer.valueOf(task.taskId));
            }
        }

        mPinnedItemCount = items.size();
        for (final TaskRepository.TaskEntry task : orderedTasks) {
            if (renderedTaskIds.contains(
                    Integer.valueOf(task.taskId))) {
                continue;
            }
            final AppItem app = mActivity.findOrLoadApp(
                    availableApps, task);
            if (app != null) {
                items.add(new TaskbarOverflowController.Entry(app, task));
            }
        }
        return items;
    }

    List<AppReference> getPinnedApps() {
        return DesktopPreferences.taskbarApps();
    }

    void togglePinned(final AppItem app) {
        final List<AppReference> pinned = getPinnedApps();
        final boolean nowPinned;
        if (pinned.remove(app.reference)) {
            nowPinned = false;
        } else {
            pinned.add(app.reference);
            nowPinned = true;
        }
        DesktopPreferences.saveTaskbarApps(pinned);
        renderPins(mActivity.getLauncherApps());
        mActivity.renderStartMenuContent();
        mActivity.setStatus(mActivity.getString(
                nowPinned
                        ? R.string.status_app_pinned
                        : R.string.status_app_unpinned,
                app.label));
    }

    private List<TaskRepository.TaskEntry> getOrderedTaskbarTasks() {
        final List<TaskRepository.TaskEntry> liveTasks = new ArrayList<>();
        final Set<Integer> liveTaskIds = new HashSet<>();
        for (final TaskRepository.TaskEntry task :
                mActivity.getTaskSnapshot().tasks) {
            if (!mActivity.isTaskbarTask(task)) {
                continue;
            }
            liveTasks.add(task);
            liveTaskIds.add(Integer.valueOf(task.taskId));
        }

        for (int index = mTaskOrder.size() - 1; index >= 0; index--) {
            if (!liveTaskIds.contains(mTaskOrder.get(index))) {
                mTaskOrder.remove(index);
            }
        }
        for (final TaskRepository.TaskEntry task : liveTasks) {
            final Integer taskId = Integer.valueOf(task.taskId);
            if (!mTaskOrder.contains(taskId)) {
                mTaskOrder.add(taskId);
            }
        }

        final List<TaskRepository.TaskEntry> orderedTasks = new ArrayList<>();
        for (final Integer taskId : mTaskOrder) {
            for (final TaskRepository.TaskEntry task : liveTasks) {
                if (task.taskId == taskId.intValue()) {
                    orderedTasks.add(task);
                    break;
                }
            }
        }
        return orderedTasks;
    }

    private static List<TaskRepository.TaskEntry> findTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final AppItem app) {
        final List<TaskRepository.TaskEntry> result = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (app.matchesTask(task)) {
                result.add(task);
            }
        }
        return result;
    }

    void updateKeyboardLayout() {
        if (mKeyboardLayout == null) {
            return;
        }
        String layoutLabel = Settings.Global.getString(
                mActivity.getContentResolver(),
                DesktopShellActivity.HARDWARE_LAYOUT_LABEL_STATE);
        if (layoutLabel == null || layoutLabel.isEmpty()) {
            layoutLabel = "??";
        }
        final String layoutName = Settings.Global.getString(
                mActivity.getContentResolver(),
                DesktopShellActivity.HARDWARE_LAYOUT_NAME_STATE);
        mKeyboardLayout.setText(layoutLabel);
        final String description = mActivity.getString(
                R.string.keyboard_layout_description,
                layoutName == null || layoutName.isEmpty()
                        ? layoutLabel
                        : layoutName);
        mKeyboardLayout.setContentDescription(description);
        mKeyboardLayout.setTooltipText(description);
    }

    void updatePhoneScreen(
            final boolean phoneScreenOff,
            final boolean visible,
            final boolean phoneScreenControl) {
        if (mPhoneScreenButton == null) {
            return;
        }
        final int actionResId = phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off;
        mPhoneScreenButton.setImageResource(phoneScreenOff
                ? R.drawable.ic_phone_screen_on
                : R.drawable.ic_phone_screen_off);
        mPhoneScreenButton.setColorFilter(
                phoneScreenOff
                        ? DesktopUiFactory.COLOR_ACCENT
                        : DesktopUiFactory.COLOR_TEXT);
        mPhoneScreenButton.setContentDescription(
                mActivity.getString(actionResId));
        mPhoneScreenButton.setTooltipText(
                mActivity.getString(actionResId));
        mPhoneScreenButton.setEnabled(phoneScreenControl);
        mPhoneScreenButton.setAlpha(phoneScreenControl ? 1f : 0.45f);
        mPhoneScreenButton.setVisibility(
                visible && !mActivity.isCompactDesktopPreview()
                        ? View.VISIBLE : View.GONE);
    }

    void updateSystemStatus(final boolean shortcutsReady) {
        if (mSystemButton == null) {
            return;
        }
        final boolean taskControl =
                ShellAccess.isReady();
        // The icon keeps the ordinary taskbar color; a missing keyboard
        // shortcut service is only a small amber badge.
        final int color = taskControl
                ? DesktopUiFactory.COLOR_TEXT
                : DesktopUiFactory.COLOR_MUTED;
        mSystemButton.setForeground(taskControl && !shortcutsReady
                ? attentionBadge() : null);
        final String description = mActivity.getString(
                R.string.system_status_description,
                ShellAccess.statusLabel(),
                mActivity.getString(R.string.state_ready),
                mActivity.getString(shortcutsReady
                        ? R.string.state_ready
                        : R.string.state_unavailable));
        mSystemButton.setColorFilter(color);
        mSystemButton.setContentDescription(description);
        mSystemButton.setTooltipText(description);
    }

    private android.graphics.drawable.Drawable attentionBadge() {
        final android.graphics.drawable.GradientDrawable dot =
                new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(DesktopUiFactory.COLOR_AMBER);
        final android.graphics.drawable.LayerDrawable badge =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {dot});
        badge.setLayerSize(0, dp(7), dp(7));
        badge.setLayerGravity(0, Gravity.TOP | Gravity.END);
        badge.setLayerInsetTop(0, dp(9));
        badge.setLayerInsetEnd(0, dp(9));
        return badge;
    }

    void updateBattery(final Intent battery) {
        if (mBatteryStatus == null || battery == null) {
            return;
        }
        mLastBatteryIntent = battery;
        final int level =
                battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale =
                battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int percent = level < 0 || scale <= 0
                ? -1
                : Math.max(
                        0,
                        Math.min(
                                100,
                                Math.round(level * 100f / scale)));
        final int status = battery.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        final boolean charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING;
        final boolean full =
                status == BatteryManager.BATTERY_STATUS_FULL;
        mBatteryStatus.setLevel(percent, charging, mChargeSeparationEnabled);
        final String state = mActivity.getString(
                charging
                        ? R.string.battery_state_charging
                        : (full
                                ? R.string.battery_state_full
                                : R.string.battery_state_discharging));
        final String description = percent < 0
                ? mActivity.getString(R.string.battery_status_unknown)
                : (mChargeSeparationEnabled
                        ? mActivity.getString(
                                R.string.battery_status_bypass_description,
                                Integer.valueOf(percent))
                        : mActivity.getString(
                        R.string.battery_status_description,
                        Integer.valueOf(percent),
                        state));
        mBatteryStatus.setContentDescription(description);
        mBatteryStatus.setTooltipText(description);
    }

    void updateChargeSeparation(final boolean enabled) {
        mChargeSeparationEnabled = enabled;
        if (mLastBatteryIntent != null) {
            updateBattery(mLastBatteryIntent);
        }
    }

    private void addPin(final TaskbarOverflowController.Entry taskbarItem) {
        mPins.addView(
                createPin(taskbarItem),
                new LinearLayout.LayoutParams(
                        dockItemWidth(),
                        LinearLayout.LayoutParams.MATCH_PARENT));
    }

    /** Separates pinned applications from other running applications. */
    private void addDockDivider() {
        final View divider = new View(mActivity);
        divider.setBackground(DesktopUiFactory.filled(
                DesktopUiFactory.COLOR_OUTLINE_VARIANT, dp(1)));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(1), desktopDp(24, 18));
        params.gravity = Gravity.CENTER_VERTICAL;
        final int margin = (dockDividerWidth() - dp(1)) / 2;
        params.setMargins(margin, 0, margin, 0);
        mPins.addView(divider, params);
    }

    private int dockItemWidth() {
        return desktopDp(48, 36);
    }

    private int dockDividerWidth() {
        return desktopDp(17, 9);
    }

    private void balanceDock(final int trayWidth) {
        if (mDockBalance == null || mTaskbar == null) {
            return;
        }
        // Center the dock on the display unless that would crowd its icons.
        final int width = Math.min(trayWidth, mTaskbar.getWidth() / 4);
        final ViewGroup.LayoutParams params = mDockBalance.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            mDockBalance.post(() -> {
                if (mDockBalance != null) {
                    mDockBalance.setLayoutParams(params);
                }
            });
        }
    }

    private View createPin(
            final TaskbarOverflowController.Entry taskbarItem) {
        final AppItem app = taskbarItem.app;
        final TaskRepository.TaskEntry task = taskbarItem.task;
        final FrameLayout item = new FrameLayout(mActivity);
        item.setBackground(mUi.stateLayerBackground(
                dp(DesktopUiFactory.SHAPE_MEDIUM_DP)));
        item.setClickable(true);
        item.setFocusable(true);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        icon.setPadding(
                desktopDp(8, 5),
                desktopDp(6, 4),
                desktopDp(8, 5),
                desktopDp(10, 7));
        item.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (task != null) {
            // Material running indicator: a dot, lengthened for the focused window.
            final View running = new View(mActivity);
            running.setBackground(DesktopUiFactory.filled(task.active
                    ? DesktopUiFactory.COLOR_ACCENT
                    : DesktopUiFactory.COLOR_MUTED,
                    dp(DesktopUiFactory.SHAPE_FULL_DP)));
            final FrameLayout.LayoutParams runningParams =
                    new FrameLayout.LayoutParams(
                            task.active ? desktopDp(16, 12) : desktopDp(6, 5),
                            dp(3),
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            runningParams.setMargins(0, 0, 0, desktopDp(3, 2));
            item.addView(running, runningParams);
        }

        final String description = task == null
                ? app.label
                : mActivity.getString(
                        R.string.taskbar_running_description,
                        app.label,
                        Integer.valueOf(task.taskId));
        item.setContentDescription(description);
        item.setTooltipText(description);
        item.setOnClickListener(view -> activate(taskbarItem));
        enableContentDrop(item, app, task);
        mActivity.registerContextTarget(item, app, task);
        mActivity.registerAutomationUiElement(
                item,
                task == null
                        ? "taskbar.app."
                                + DesktopAutomationUiRegistry.identitySegment(
                                        app.packageName)
                        : "taskbar.task." + task.taskId,
                "application",
                app.label,
                app.packageName,
                task == null ? -1 : task.taskId);
        return item;
    }

    private void enableContentDrop(
            final View item,
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final float restingAlpha = item.getAlpha();
        item.setOnDragListener((target, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return !(event.getLocalState()
                                    instanceof DesktopGridLayout.DragToken)
                            && event.getClipDescription() != null;
                case DragEvent.ACTION_DRAG_ENTERED:
                    target.setAlpha(0.72f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    target.setAlpha(restingAlpha);
                    return true;
                case DragEvent.ACTION_DROP:
                    target.setAlpha(restingAlpha);
                    final AndroidContentPayload content =
                            AndroidContentPayload.fromClipData(
                                    event.getClipData(),
                                    AndroidContentPayload.Origin.DRAG);
                    if (content.isEmpty()) {
                        return false;
                    }
                    final DragAndDropPermissions permissions =
                            mActivity.requestDragAndDropPermissions(event);
                    deliverContent(app, task, content, permissions);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void deliverContent(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final AndroidContentPayload content,
            final DragAndDropPermissions permissions) {
        final int displayId = mActivity.getCurrentDisplayId();
        if (task != null && !task.isFreeform() && !task.isFullscreen()) {
            if (permissions != null) {
                permissions.release();
            }
            mActivity.setErrorStatus(
                    "CONTENT-DROP-001",
                    "target task has no supported desktop window mode",
                    "package=" + app.packageName,
                    null);
            return;
        }
        final DesktopLaunchPresentation presentation = task == null
                ? DesktopLaunchPresentation.automatic()
                : DesktopLaunchPresentation.forMode(
                        task.isFreeform()
                                ? DesktopLaunchMode.WINDOWED
                                : DesktopLaunchMode.FULLSCREEN)
                        .withPreferredTask(task.taskId);
        AndroidDesktopActionDispatcher.deliverContent(
                mContentRequests,
                mActivity,
                content,
                app.launchTarget,
                presentation,
                displayId,
                () -> {
                    if (permissions != null) {
                        permissions.release();
                    }
                },
                result -> {
                    if (!result.success) {
                        mActivity.setErrorStatus(
                                "CONTENT-DROP-001",
                                result.message,
                                "package=" + app.packageName,
                                null);
                    }
                });
    }

    private void addOverflowButton() {
        mPins.addView(mOverflow.createButton(),
                new LinearLayout.LayoutParams(
                dockItemWidth(),
                LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private void activate(final TaskbarOverflowController.Entry taskbarItem) {
        if (taskbarItem.task != null) {
            mActivity.captureInteractionStackForPanel();
        }
        mActivity.hideAllPanels();
        if (taskbarItem.task == null) {
            mActivity.launchDefault(taskbarItem.app);
        } else {
            mActivity.toggleTaskbarTask(
                    taskbarItem.app,
                    taskbarItem.task);
        }
    }

    private void addButton(
            final LinearLayout tray,
            final ImageButton button) {
        tray.addView(button, new LinearLayout.LayoutParams(
                desktopDp(44, 36),
                LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private ImageButton taskbarButton(
            final int drawableResId,
            final int descriptionResId) {
        return mUi.taskbarIconButton(
                drawableResId,
                descriptionResId,
                mActivity.isCompactDesktopPreview());
    }

    private boolean isActionAt(final float localX, final float localY) {
        if (mTaskbar == null) {
            return false;
        }
        for (int index = 0; index < mTaskbar.getChildCount(); index++) {
            if (isActionViewAt(
                    mTaskbar,
                    mTaskbar.getChildAt(index),
                    localX,
                    localY)) {
                return true;
            }
        }
        return false;
    }

    ContextArea contextAreaAt(final float screenX, final float screenY) {
        if (!containsOnScreen(mTaskbar, screenX, screenY)) {
            return ContextArea.NONE;
        }
        if (containsOnScreen(mStartButton, screenX, screenY)) {
            return ContextArea.START;
        }
        final int[] location = new int[2];
        mTaskbar.getLocationOnScreen(location);
        return isActionAt(screenX - location[0], screenY - location[1])
                ? ContextArea.ACTION
                : ContextArea.BLANK;
    }

    private static boolean containsOnScreen(
            final View view,
            final float screenX,
            final float screenY) {
        if (view == null || !view.isShown()) {
            return false;
        }
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        return screenX >= location[0]
                && screenY >= location[1]
                && screenX < location[0] + view.getWidth()
                && screenY < location[1] + view.getHeight();
    }

    private boolean isActionViewAt(
            final ViewGroup parent,
            final View view,
            final float parentX,
            final float parentY) {
        if (view == null || !view.isShown() || !view.isEnabled()) {
            return false;
        }
        final float localX =
                parentX + parent.getScrollX() - view.getLeft();
        final float localY =
                parentY + parent.getScrollY() - view.getTop();
        if (localX < 0
                || localY < 0
                || localX >= view.getWidth()
                || localY >= view.getHeight()) {
            return false;
        }
        if (view.hasOnClickListeners()) {
            return true;
        }
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        final ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (isActionViewAt(
                    group,
                    group.getChildAt(index),
                    localX,
                    localY)) {
                return true;
            }
        }
        return false;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }

}
