package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.app.Activity;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Shared Start contents. Each host owns a separate instance and launch destination. */
final class StartMenuContent {
    interface Host {
        ApplicationCatalog.Snapshot catalog();
        List<AppItem> apps();
        default List<DesktopApplicationRepository.Entry> desktopApplications() {
            return java.util.Collections.emptyList();
        }
        default boolean hasRunningSection() { return false; }
        default List<StartMenuEntry> runningEntries() { return List.of(); }
        default String runningAppsError() { return ""; }
        default List<StartMenuEntry> entries(int section) {
            if (section == MENU_RUNNING) return runningEntries();
            return catalog().applications(apps(), desktopApplications());
        }
        default void onSectionShown(int section) { }
        default List<StartMenuEntry> searchEntries(int section) { return entries(MENU_APPS); }
        DesktopAutomationUiRegistry automation();
        void open(StartMenuEntry result);
        void dismiss();
        default void appContext(View view, AppItem app) { }
        default void fileContext(View view, DesktopFile file) { }
        default void populateTools(LinearLayout parent, int spacing, boolean capture) { }
        default void requestSearchFocus() { }
        default boolean mouseTouch(MotionEvent event) { return false; }
        default boolean mouseMotion(MotionEvent event) { return false; }
    }

    static final int MENU_RECENT = 0;
    static final int MENU_APPS = 1;
    static final int MENU_TOOLS = 2;
    static final int MENU_CAPTURE = 3;
    static final int MENU_RUNNING = 4;
    /** Search results scroll, so the limit only bounds ranking work. */
    private static final int SEARCH_RESULT_LIMIT = 30;

    private final Activity mActivity;
    private final Host mHost;
    private final StartMenuScope mScope;
    private final DesktopUiFactory mUi;
    private final StartSearchController mSearchController;
    private final ApplicationCatalog mCatalog;
    private final Runnable mCatalogListener = this::catalogChanged;
    private ApplicationCatalog.Snapshot mObservedCatalog;

    private LinearLayout mPanel;
    private LinearLayout mContent;
    private LinearLayout mBody;
    private EditText mSearch;
    private LinearLayout mSearchRow;
    private StartLaunchControls mLaunchControls;
    private boolean mFocusable = true;
    /** Like Android's application drawer, Start opens on every application. */
    private int mMode = MENU_APPS;
    private int mSearchSelection;
    private String mSearchQuery = "";
    private int mColumns = 3;
    private boolean mPrepared, mReleased;
    private boolean mLaunchOptionsVisible;
    /** The grid scrolls continuously; its position survives catalog refreshes. */
    private GridView mGrid;
    private int mGridFirstPosition;
    /** Marks recyclable application tiles. */
    private static final Object TILE_TAG = new Object();
    private int mSurface;
    private int mOnSurface;
    private int mSearchFill;

    StartMenuContent(
            final Activity activity,
            final DesktopUiFactory ui,
            final StartMenuScope scope,
            final Host host) {
        mHost = host;
        mScope = scope;
        mActivity = activity;
        mCatalog = ApplicationCatalog.get(activity);
        mUi = ui;
        mSearchController = new StartSearchController(
                activity, scope,
                this::onSearchResultsChanged);
    }

    // The touch observer only enables IME display; EditText retains click handling.
    @SuppressLint("ClickableViewAccessibility")
    LinearLayout create() {
        final LinearLayout menu = new LinearLayout(mActivity) {
            // Child application windows bypass the host Activity dispatch path.
            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                if (mHost.mouseTouch(event)) {
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(
                    final MotionEvent event) {
                if (mHost.mouseMotion(event)) {
                    return true;
                }
                return super.dispatchGenericMotionEvent(event);
            }

            @Override
            public void onWindowFocusChanged(final boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus && mScope == StartMenuScope.DESKTOP) {
                    StartMenuContent.this.focusSearch();
                }
            }
        };
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(18), dp(8), dp(18), dp(12));
        // The drawer uses the same dynamic surface roles as every other
        // MagicDesk surface; the phone Start keeps its host's background.
        final boolean drawer = mScope == StartMenuScope.DESKTOP;
        mSurface = drawer ? DesktopUiFactory.COLOR_PANEL : DesktopUiFactory.COLOR_BACKGROUND;
        mOnSurface = DesktopUiFactory.COLOR_TEXT;
        mSearchFill = DesktopUiFactory.COLOR_PANEL_FOCUS;
        if (drawer) {
            menu.setBackground(mUi.rounded(
                    mSurface, dp(DesktopUiFactory.SHAPE_EXTRA_LARGE_DP), mSurface));
            final View handle = new View(mActivity);
            handle.setBackground(DesktopUiFactory.filled(
                    DesktopUiFactory.withAlpha(mOnSurface, 0.45f),
                    dp(DesktopUiFactory.SHAPE_FULL_DP)));
            final LinearLayout.LayoutParams handleParams =
                    new LinearLayout.LayoutParams(dp(32), dp(4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.setMargins(0, 0, 0, dp(14));
            menu.addView(handle, handleParams);
        } else {
            menu.setPadding(dp(18), dp(18), dp(18), dp(12));
        }
        mSearch = new EditText(mActivity);
        final int searchHint = mScope == StartMenuScope.APPLICATIONS
                ? R.string.search_phone_apps_hint : R.string.search_apps_hint;
        mSearch.setHint(searchHint);
        mSearch.setHintTextColor(DesktopUiFactory.withAlpha(mOnSurface, 0.7f));
        mSearch.setTextColor(mOnSurface);
        mSearch.setTextSize(15);
        mSearch.setSingleLine(true);
        mSearch.setShowSoftInputOnFocus(false);
        mSearch.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mSearch.setShowSoftInputOnFocus(true);
            }
            return false;
        });
        // Material search bar: the pill belongs to the row, which also holds
        // the trailing launch-options action.
        mSearch.setPadding(dp(4), dp(8), dp(8), dp(8));
        mSearch.setBackground(null);
        final android.graphics.drawable.Drawable searchIcon =
                mActivity.getDrawable(R.drawable.ic_search).mutate();
        searchIcon.setTint(mOnSurface);
        searchIcon.setBounds(0, 0, dp(20), dp(20));
        mSearch.setCompoundDrawablesRelative(searchIcon, null, null, null);
        mSearch.setCompoundDrawablePadding(dp(12));
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    final CharSequence text,
                    final int start,
                    final int count,
                    final int after) {
            }

            @Override
            public void onTextChanged(
                    final CharSequence text,
                    final int start,
                    final int before,
                    final int count) {
                mSearchQuery = text == null ? "" : text.toString();
                mSearchSelection = 0;
                mSearchController.update(
                        mSearchQuery,
                        entries(mMode, true));
                renderBody();
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        mSearch.setOnKeyListener((view, keyCode, event) ->
                handleSearchKey(keyCode, event));
        mSearch.setOnClickListener(view -> {
            focusSearch();
            mSearch.setShowSoftInputOnFocus(true);
            final android.view.inputmethod.InputMethodManager keyboard =
                    mActivity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) {
                keyboard.showSoftInput(mSearch,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        mHost.automation().register(
                mSearch,
                "start.search",
                "text_field",
                mActivity.getString(searchHint));

        mContent = new LinearLayout(mActivity);
        mSearchRow = new LinearLayout(mActivity);
        mSearchRow.setGravity(Gravity.CENTER_VERTICAL);
        mSearchRow.setPadding(dp(16), 0, dp(6), 0);
        mSearchRow.setBackground(DesktopUiFactory.filled(
                mSearchFill, dp(DesktopUiFactory.SHAPE_FULL_DP)));
        mSearchRow.addView(mSearch, new LinearLayout.LayoutParams(0, dp(52), 1));
        final android.widget.ImageButton launchOptions = mUi.menuIconButton(
                R.drawable.ic_more, R.string.start_launch_options);
        launchOptions.setImageTintList(android.content.res.ColorStateList.valueOf(mOnSurface));
        launchOptions.setOnClickListener(view -> {
            mLaunchOptionsVisible = !mLaunchOptionsVisible;
            render();
        });
        mHost.automation().register(launchOptions, "start.launch_options", "button",
                mActivity.getString(R.string.start_launch_options));
        mSearchRow.addView(launchOptions, new LinearLayout.LayoutParams(dp(40), dp(40)));
        mLaunchControls = new StartLaunchControls(mActivity, mUi, mHost.automation(), this::destinationChanged);
        mContent.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        menu.addView(mContent, contentParams);
        mPanel = menu;
        mHost.automation().register(
                menu, "panel.start", "panel", "Start");
        return menu;
    }

    void render() {
        if (mContent == null) {
            return;
        }
        rememberGridScroll();
        mContent.removeAllViews();
        mBody = null;

        if (!isUtilityMode(mMode) && mSearch != null) {
            mContent.addView(mSearchRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        }

        final LinearLayout tabs = new LinearLayout(mActivity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        addTab(tabs, R.string.section_apps, MENU_APPS);
        addTab(tabs, R.string.section_recent, MENU_RECENT);
        if (mHost.hasRunningSection()) {
            addTab(tabs, R.string.section_running, MENU_RUNNING);
        }
        if (mScope == StartMenuScope.DESKTOP) {
            addTab(tabs, R.string.section_tools, MENU_TOOLS);
        }
        final LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tabsParams.setMargins(0, dp(12), 0, 0);
        mContent.addView(tabs, tabsParams);

        if (mMode == MENU_TOOLS) {
            addTools();
            return;
        }
        if (mMode == MENU_CAPTURE) {
            addCapture();
            return;
        }

        if (mLaunchOptionsVisible && mSearch != null) {
            final LinearLayout.LayoutParams optionsParams =
                    new LinearLayout.LayoutParams(-1, dp(52));
            optionsParams.setMargins(0, dp(8), 0, 0);
            mContent.addView(mLaunchControls.view(), optionsParams);
        }

        mBody = new LinearLayout(mActivity);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mBody.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            final float density = mActivity.getResources().getDisplayMetrics().density;
            final int columns = StartMenuLayout.columns(Math.round((right - left) / density));
            if (right > left && columns != mColumns) {
                mColumns = columns;
                view.post(() -> {
                    if (view == mBody && view.isAttachedToWindow()) {
                        renderBody();
                    }
                });
            }
        });
        final LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        bodyParams.setMargins(0, dp(6), 0, 0);
        mContent.addView(mBody, bodyParams);
        renderBody();
    }

    void showSection(final int mode) {
        mMode = mode;
        mGridFirstPosition = 0;
        mSearchSelection = 0;
        mSearchQuery = "";
        if (mSearch != null && mSearch.length() > 0) {
            mSearch.setText("");
        }
        prepare(mFocusable);
        mHost.onSectionShown(mode);
    }

    boolean isUtilityVisible() {
        return isUtilityMode(mMode);
    }

    void prepare(final boolean focusable) {
        mFocusable = focusable;
        mLaunchControls.refresh();
        mSearch.setShowSoftInputOnFocus(false);
        mSearchController.update(
                mSearchQuery, entries(mMode, true));
        if (!mPrepared) {
            mPrepared = true;
            mCatalog.subscribe(mCatalogListener);
            mCatalog.refresh();
            mObservedCatalog = mCatalog.snapshot();
            RecentApplications.refresh(mActivity, () -> {
                if (mReleased || !mPrepared) return;
                mSearchController.update(mSearchQuery, entries(mMode, true));
                render();
            });
        }
        render();
    }

    private List<StartMenuEntry> entries(int section, boolean search) {
        if (section == MENU_RUNNING) return mHost.runningEntries();
        if (!search && section == MENU_RECENT) return RecentApplications.entries(mLaunchControls.recentScope())
                .stream().map(entry -> StartMenuEntry.recent(entry, mHost.apps())).toList();
        if ((search || section == MENU_APPS) && !mCatalog.snapshot().android().ready()) return List.of();
        List<StartMenuEntry> result = new ArrayList<>(search ? mHost.searchEntries(section) : mHost.entries(section));
        if (search || section == MENU_APPS) {
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (StartMenuEntry entry : result) keys.add(entry.stableKey());
            if (search) for (var application : mCatalog.snapshot().termux().entries()) {
                StartMenuEntry entry = StartMenuEntry.desktopApplication(application);
                if (keys.add(entry.stableKey())) result.add(entry);
            }
            if (search) for (var recent : RecentApplications.entries(mLaunchControls.recentScope())) {
                boolean inCatalog = !recent.sourcePath().isEmpty() && result.stream().anyMatch(entry ->
                        entry.desktopApplication != null && entry.desktopApplication.desktopFilePath.equals(recent.sourcePath()));
                boolean androidApp = recent.shortcut().defaultLaunch && result.stream().anyMatch(entry ->
                        entry.app != null && entry.app.reference != null && entry.app.reference.equals(
                                AppReference.forTarget(recent.shortcut().application, recent.shortcut().launchTarget)));
                StartMenuEntry entry = StartMenuEntry.recent(recent, mHost.apps());
                if (!inCatalog && !androidApp && keys.add(entry.stableKey())) result.add(entry);
            }
            return ApplicationCatalog.sortedUnique(result);
        }
        return result;
    }

    private void destinationChanged() {
        mGridFirstPosition = 0;
        mSearchSelection = 0;
        mSearchController.update(mSearchQuery, entries(mMode, true));
        renderBody();
    }

    private void catalogChanged() {
        if (mReleased || !mPrepared) return;
        final var next = mCatalog.snapshot();
        final var previous = mObservedCatalog;
        mObservedCatalog = next;
        if (previous != null && previous.android().entries() == next.android().entries()
                && previous.android().ready() == next.android().ready()
                && previous.termux().entries() == next.termux().entries()
                && (next.android().ready() || (previous.android().loading() == next.android().loading()
                        && previous.android().error().equals(next.android().error())))) {
            if (previous.termuxIcons() != next.termuxIcons()) refreshIcons(mBody);
            return;
        }
        mSearchController.update(mSearchQuery, entries(mMode, true));
        renderBody();
    }

    void pause() {
        mPrepared = false;
        mCatalog.unsubscribe(mCatalogListener);
        mLaunchControls.dismiss();
        mSearch.setShowSoftInputOnFocus(false);
        mSearchController.pause();
    }

    void release() {
        mReleased = true;
        mPrepared = false;
        mCatalog.unsubscribe(mCatalogListener);
        mLaunchControls.dismiss();
        mSearchController.close();
    }

    StartDisplaySelector.Target destination() { return mLaunchControls.target(); }
    DesktopLaunchPresentation presentation() { return mLaunchControls.presentation(); }

    void focusSearch() {
        if (!mFocusable || isUtilityMode(mMode) || mSearch == null) {
            return;
        }
        mSearch.requestFocus();
        mSearch.setSelection(mSearch.length());
    }

    private void renderBody() {
        if (mBody == null) {
            return;
        }
        rememberGridScroll();
        mBody.removeAllViews();

        final var android = mCatalog.snapshot().android();
        if ((mMode == MENU_APPS || !mSearchQuery.trim().isEmpty()) && !android.ready()) {
            final TextView status = new TextView(mActivity);
            status.setText(android.error().isEmpty()
                    ? mActivity.getString(R.string.apps_loading) : android.error());
            status.setTextColor(DesktopUiFactory.withAlpha(mOnSurface, 0.75f));
            status.setTextSize(14);
            status.setGravity(Gravity.CENTER);
            mBody.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
            mHost.automation().register(status, "start.catalog", "status", status.getText().toString());
            return;
        }

        if (!mSearchQuery.trim().isEmpty()) {
            renderSearchResults();
            return;
        }

        final List<StartMenuEntry> menuApps = entries(mMode, false);
        final String recentError = mMode == MENU_RECENT ? RecentApplications.error(mLaunchControls.recentScope())
                : mMode == MENU_RUNNING ? mHost.runningAppsError() : "";
        if (menuApps.isEmpty() || !recentError.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(recentError.isEmpty() ? mActivity.getString(mMode == MENU_RECENT
                    ? R.string.recent_apps_empty
                    : R.string.status_no_apps) : recentError);
            empty.setTextColor(DesktopUiFactory.withAlpha(mOnSurface, 0.75f));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mBody.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        // A recycling grid builds only visible tiles, so opening and
        // scrolling stay smooth with hundreds of applications.
        final GridView grid = new GridView(mActivity);
        grid.setNumColumns(getColumnCount());
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(8));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(false);
        grid.setVerticalScrollBarEnabled(true);
        grid.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        grid.setScrollbarFadingEnabled(false);
        grid.setScrollBarSize(dp(4));
        grid.setVerticalScrollbarThumbDrawable(DesktopUiFactory.filled(
                DesktopUiFactory.withAlpha(mOnSurface, 0.45f),
                dp(DesktopUiFactory.SHAPE_FULL_DP)));
        grid.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return menuApps.size(); }
            @Override public Object getItem(final int position) { return menuApps.get(position); }
            @Override public long getItemId(final int position) { return position; }
            @Override public View getView(final int position, final View recycled,
                    final android.view.ViewGroup parent) {
                final StartMenuEntry entry = menuApps.get(position);
                if (recycled instanceof LinearLayout tile && tile.getTag() == TILE_TAG) {
                    bindAppTile(tile, entry);
                    return tile;
                }
                return createAppTile(entry);
            }
        });
        mBody.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        mGrid = grid;
        final int restorePosition = mGridFirstPosition;
        if (restorePosition > 0 && restorePosition < menuApps.size()) {
            grid.setSelection(restorePosition);
        }
    }

    private void rememberGridScroll() {
        if (mGrid != null && mGrid.isAttachedToWindow()) {
            mGridFirstPosition = mGrid.getFirstVisiblePosition();
        }
        mGrid = null;
    }

    private void addTab(final LinearLayout tabs, final int textResId, final int mode) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        params.setMarginEnd(dp(8));
        tabs.addView(createTab(textResId, mode), params);
    }

    /** Material filter chip for a Start section. */
    private TextView createTab(final int textResId, final int mode) {
        final boolean selected = tabSelected(mode);
        final TextView chip = new TextView(mActivity);
        chip.setText(textResId);
        chip.setTextSize(13);
        chip.setTypeface(DesktopUiFactory.medium());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setDefaultFocusHighlightEnabled(false);
        final int radius = dp(DesktopUiFactory.SHAPE_FULL_DP);
        // Material filter chips: the selected chip is the secondary container.
        final int fill = selected
                ? DesktopUiFactory.COLOR_SECONDARY_CONTAINER : Color.TRANSPARENT;
        final int outline = selected
                ? fill : DesktopUiFactory.COLOR_OUTLINE_VARIANT;
        chip.setTextColor(selected
                ? DesktopUiFactory.COLOR_ON_SECONDARY_CONTAINER : DesktopUiFactory.COLOR_MUTED);
        final StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused},
                mUi.rounded(fill, radius, mOnSurface));
        background.addState(new int[] {android.R.attr.state_pressed},
                mUi.rounded(DesktopUiFactory.withAlpha(mOnSurface, 0.3f), radius, outline));
        background.addState(new int[] {android.R.attr.state_hovered},
                mUi.rounded(DesktopUiFactory.withAlpha(mOnSurface, selected ? 0.28f : 0.1f),
                        radius, outline));
        background.addState(new int[0], mUi.rounded(fill, radius, outline));
        chip.setBackground(background);
        chip.setOnClickListener(view -> {
            mMode = mode;
            mGridFirstPosition = 0;
            if (isUtilityMode(mode)) {
                mSearchQuery = "";
                if (mSearch != null && mSearch.length() > 0) {
                    mSearch.setText("");
                }
            }
            if (!mFocusable && !isUtilityMode(mode)) {
                mPanel.post(mHost::requestSearchFocus);
                return;
            }
            prepare(mFocusable);
            mHost.onSectionShown(mode);
        });
        mHost.automation().register(
                chip,
                "start.tab." + modeName(mode),
                "tab",
                chip.getText());
        return chip;
    }

    private void addTools() {
        final LinearLayout tools = new LinearLayout(mActivity);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(0, dp(14), 0, 0);
        mHost.populateTools(tools, dp(10), false);

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(tools, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mContent.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void addCapture() {
        final LinearLayout capture = new LinearLayout(mActivity);
        capture.setOrientation(LinearLayout.VERTICAL);
        capture.setPadding(0, dp(14), 0, 0);
        mHost.populateTools(capture, dp(10), true);

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(capture, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mContent.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean tabSelected(final int mode) {
        return mMode == mode
                || (mode == MENU_TOOLS && mMode == MENU_CAPTURE);
    }

    private static boolean isUtilityMode(final int mode) {
        return mode == MENU_TOOLS
                || mode == MENU_CAPTURE;
    }

    private StateListDrawable entryBackground(final int radius) {
        // Material drawer entries are unboxed; only selection and keyboard
        // focus draw an outline, so every state keeps the same geometry.
        final StateListDrawable background = new StateListDrawable();
        for (final int state : new int[] {android.R.attr.state_selected, android.R.attr.state_focused}) {
            background.addState(new int[] {state}, mUi.rounded(
                    DesktopUiFactory.COLOR_SECONDARY_CONTAINER, dp(radius), DesktopUiFactory.COLOR_ACCENT));
        }
        final int pressed = DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.18f);
        background.addState(new int[] {android.R.attr.state_pressed}, mUi.rounded(
                pressed, dp(radius), pressed));
        final int hovered = DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.08f);
        background.addState(new int[] {android.R.attr.state_hovered}, mUi.rounded(
                hovered, dp(radius), hovered));
        background.addState(new int[0], mUi.rounded(
                Color.TRANSPARENT, dp(radius), Color.TRANSPARENT));
        return background;
    }

    private View createAppTile(final StartMenuEntry application) {
        final LinearLayout tile = new LinearLayout(mActivity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(10), dp(4), dp(8));
        tile.setBackground(entryBackground(12));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setTag(TILE_TAG);

        final ImageView icon = new ImageView(mActivity);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        final TextView label = new TextView(mActivity);
        label.setTextColor(mOnSurface);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(8), 0, 0);
        tile.addView(label, labelParams);
        bindAppTile(tile, application);
        return tile;
    }

    /** Binds a new or recycled tile to one entry. */
    private void bindAppTile(final LinearLayout tile, final StartMenuEntry application) {
        final AppItem app = application.app;
        tile.setOnClickListener(view -> mHost.open(application));
        tile.setOnLongClickListener(null);
        tile.setOnContextClickListener(null);
        bindContextMenu(tile, application);
        mHost.automation().register(
                tile,
                "start.app."
                        + DesktopAutomationUiRegistry.identitySegment(
                                application.stableKey()),
                "application",
                application.label,
                app == null ? "" : app.packageName,
                application.task == null ? -1 : application.task.taskId);
        bindIcon((ImageView) tile.getChildAt(0), application);
        ((TextView) tile.getChildAt(1)).setText(application.label);
        tile.setContentDescription(application.label + ", " + application.detail);
    }


    private void renderSearchResults() {
        final List<StartMenuEntry> matches =
                mSearchController.results(getSearchResultLimit());
        if (matches.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(R.string.search_no_results);
            empty.setTextColor(DesktopUiFactory.withAlpha(mOnSurface, 0.75f));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mBody.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        final int visibleCount = matches.size();
        if (mSearchSelection >= visibleCount) {
            mSearchSelection = visibleCount - 1;
        }
        final LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, 0);
        for (int index = 0; index < visibleCount; index++) {
            list.addView(
                    createSearchRow(
                            matches.get(index),
                            index == mSearchSelection),
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(58)));
        }
        final ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mBody.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean handleSearchKey(
            final int keyCode,
            final KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        final List<StartMenuEntry> matches =
                mSearchController.results(getSearchResultLimit());
        final int visibleCount = matches.size();
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !matches.isEmpty()) {
            mSearchSelection = Math.min(
                    visibleCount - 1, mSearchSelection + 1);
            renderBody();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !matches.isEmpty()) {
            mSearchSelection = Math.max(0, mSearchSelection - 1);
            renderBody();
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                && !matches.isEmpty()) {
            final StartMenuEntry result = matches.get(
                    Math.min(mSearchSelection, matches.size() - 1));
            openSearchResult(result);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            mHost.dismiss();
            return true;
        }
        return false;
    }

    private View createSearchRow(
            final StartMenuEntry result,
            final boolean selected) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        row.setBackground(entryBackground(7));
        row.setSelected(selected);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> openSearchResult(result));
        if (result.app != null) {
            mHost.automation().register(
                    row,
                    "start.search.app."
                            + DesktopAutomationUiRegistry.identitySegment(
                                    result.stableKey()),
                    "application",
                    result.label,
                    result.app.packageName,
                    -1);
        } else if (result.desktopApplication != null
                && result.desktopApplication.desktopFile != null) {
            mHost.automation().register(
                    row,
                    "start.search.command."
                            + DesktopAutomationUiRegistry.identitySegment(
                                    result.desktopApplication.desktopFilePath),
                    "application",
                    result.label);
        } else {
            mHost.automation().register(
                    row,
                    "start.search.result."
                            + DesktopAutomationUiRegistry.identitySegment(
                                    result.stableKey()),
                    "search_result",
                    result.label);
        }

        bindContextMenu(row, result);

        final ImageView icon = new ImageView(mActivity);
        bindIcon(icon, result);
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        final LinearLayout labels = new LinearLayout(mActivity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        final TextView name = new TextView(mActivity);
        name.setText(result.label);
        name.setTextColor(mOnSurface);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        final TextView detail = new TextView(mActivity);
        detail.setText(result.detail);
        detail.setTextColor(DesktopUiFactory.withAlpha(mOnSurface, 0.7f));
        detail.setTextSize(11);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private int searchIcon(final StartMenuEntry result) {
        if (result.kind == StartMenuEntry.Kind.TERMINALS) { return R.drawable.ic_file_console; }
        if (result.file != null) {
            return FileIconResolver.forFile(
                    result.file.directory,
                    result.file.mimeType);
        }
        if (result.builtIn != null) {
            final AppLaunchTarget target = result.builtIn.launchTarget;
            if (BuiltInDesktopAppCatalog.filesTarget().equals(target)) {
                return R.drawable.ic_desktop_folder;
            }
            if (BuiltInDesktopAppCatalog.consoleTarget().equals(target)) {
                return R.drawable.ic_file_console;
            }
            if (BuiltInDesktopAppCatalog.taskManagerTarget().equals(target)) {
                return R.drawable.ic_sections;
            }
            return R.drawable.ic_settings;
        }
        if (result.action == StartMenuEntry.Action.SCREENSHOT) {
            return R.drawable.ic_camera;
        }
        if (result.action == StartMenuEntry.Action.SCREEN_RECORDING) {
            return R.drawable.ic_video;
        }
        return R.drawable.ic_show_desktop;
    }

    private void bindIcon(final ImageView icon, final StartMenuEntry entry) {
        icon.setTag(entry);
        if (entry.app != null) { icon.setImageDrawable(entry.app.icon); }
        else if (entry.builtIn != null) { icon.setImageResource(searchIcon(entry)); }
        else if (entry.desktopApplication != null) {
            icon.setImageDrawable(DesktopApplicationIconResolver.resolve(
                    mActivity, entry.desktopApplication.shortcut));
        } else { icon.setImageResource(searchIcon(entry)); }
    }

    private void refreshIcons(View view) {
        if (view instanceof ImageView icon && icon.getTag() instanceof StartMenuEntry entry) {
            if (entry.desktopApplication != null) bindIcon(icon, entry);
        } else if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) refreshIcons(group.getChildAt(i));
    }

    private void bindContextMenu(View view, StartMenuEntry entry) {
        if (entry.app != null) mHost.appContext(view, entry.app);
        else if (entry.desktopApplication != null) {
            var application = entry.desktopApplication;
            if (application.desktopFile != null) mHost.fileContext(view, application.desktopFile);
            else if (application.shortcut.execBackend == DesktopExecBackend.TERMUX) {
                view.setOnLongClickListener(anchor -> {
                    final boolean userShortcut = (entry.recent == null
                            || entry.recent.termuxPackage().equals(IntegrationPackage.TERMUX.selected()))
                            && mCatalog.snapshot().termux().entries().stream().anyMatch(current -> current.userShortcut
                                    && current.desktopFilePath.equals(application.desktopFilePath));
                    final boolean scale = application.shortcut.graphics != null
                            && !application.desktopFilePath.isBlank();
                    if (!userShortcut && !scale) return false;
                    final android.widget.PopupMenu menu = new android.widget.PopupMenu(mActivity, anchor);
                    if (scale) menu.getMenu().add(R.string.app_presentation_scale)
                            .setOnMenuItemClickListener(item -> {
                                GraphicalScaleDialog.show(mActivity, entry.label, entry.recent == null
                                        ? IntegrationPackage.TERMUX.selected() : entry.recent.termuxPackage(),
                                        application.desktopFilePath);
                                return true;
                            });
                    if (userShortcut) menu.getMenu().add(R.string.action_delete_shortcut)
                            .setOnMenuItemClickListener(item -> {
                                TermuxShortcutDialog.confirmDelete(mActivity, entry);
                                return true;
                            });
                    menu.show();
                    return true;
                });
                view.setOnContextClickListener(View::performLongClick);
            }
        }
    }

    private static String modeName(final int mode) {
        switch (mode) {
            case MENU_RUNNING:
                return "running";
            case MENU_RECENT:
                return "recent";
            case MENU_APPS:
                return "apps";
            case MENU_TOOLS:
                return "tools";
            case MENU_CAPTURE:
                return "capture";
            default:
                return Integer.toString(mode);
        }
    }

    private void openSearchResult(final StartMenuEntry result) {
        mHost.open(result);
    }

    private int getSearchResultLimit() {
        return SEARCH_RESULT_LIMIT;
    }

    private void onSearchResultsChanged() {
        if (mBody != null && !mSearchQuery.trim().isEmpty()) {
            renderBody();
        }
    }

    private int getColumnCount() {
        return mColumns;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
