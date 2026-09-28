package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Material You file manager: a navigation rail, a pill address bar, a
 * toolbar of tonal actions and a rounded content card with a sortable table.
 * Colors are the shared dynamic roles in {@link DesktopUiFactory}.
 */
final class FileManagerView {
    interface Listener {
        void onBack();
        void onForward();
        void onUp();
        void onRefresh();
        void onNavigate(String path);
        void onItemClick(
                ShellFileInfo file, int metaState, long eventTime);
        void onSelectionChanged(ShellFileInfo file, boolean selected);
        void onSelectAll(boolean selected);
        boolean onContextMenu(View anchor, ShellFileInfo file);
        boolean onBackgroundContextMenu(
                View anchor, float rawX, float rawY);
        boolean onStartDrag(
                View source, ShellFileInfo file, int metaState);
        boolean onDrop(DragEvent event, String destinationPath);
        boolean onApplicationDrop(
                DragEvent event,
                ShellFileInfo file,
                DesktopApplicationShortcut shortcut);
        void onNewWindow();
        void onNewFile();
        void onNewFolder();
        void onCopy();
        void onCut();
        void onPaste();
        void onRename();
        void onDelete();
        void onProperties();
        void onShare();
        void onOpenWith();
        void onOpenConsole();
        void onOpenTerminal();
        void onShowHiddenChanged(boolean showHidden);
        void onSortChanged(int sortMode);
        void onSortDirectionChanged(boolean ascending);
        void onViewModeChanged(FileManagerLayoutMode layoutMode);
        void onFilterChanged(String query);
        void onRecursiveSearchRequested();
    }

    /** A navigation-rail destination. */
    private static final class Place {
        final int label;
        final int icon;
        final String path;
        View view;

        Place(final int label, final int icon, final String path) {
            this.label = label;
            this.icon = icon;
            this.path = path;
        }
    }

    private static final String STORAGE = "/storage/emulated/0";

    private final Context mContext;
    private final Listener mListener;
    private final DesktopUiFactory mUi;
    private final LinearLayout mRoot;
    private final EditText mPath;
    private final ListView mList;
    private final GridView mGrid;
    private final ShellFileAdapter mAdapter;
    private final TextView mEmpty;
    private final TextView mStatus;
    private final TextView mTitle;
    private final FileManagerStatus mStatusState = new FileManagerStatus();
    private final LinearLayout mFilterPanel;
    private final EditText mFilter;
    private final ImageButton mBack;
    private final ImageButton mForward;
    private final ImageButton mUp;
    private final View mCopy;
    private final View mCut;
    private final View mPaste;
    private final View mDelete;
    private final View mProperties;
    private final View mShare;
    private final View mOpenWith;
    private final LinearLayout mHeader;
    private final CheckBox mSelectAll;
    private final TextView mSortName;
    private final TextView mSortSize;
    private final TextView mSortModified;
    private final ImageButton mListMode;
    private final ImageButton mGridMode;
    private final LinearLayout mRail;
    private final List<Place> mPlaces = new ArrayList<>();
    private final List<TextView> mRailLabels = new ArrayList<>();
    private FileManagerLayoutMode mLayoutMode;
    private boolean mSortAscending = true;
    private int mSortMode = ShellFileSystem.SORT_NAME;
    private boolean mItemsAvailable;
    private boolean mShowHidden;
    private boolean mTerminalVisible;
    private boolean mShellReady = true;
    private boolean mRailExpanded;
    private int mSelectionCount;
    private int mFileCount;
    private LinearLayout mSaveBar;
    private TextView mSaveName;
    private Button mSaveHere;
    private ImageButton mCancelSave;
    private ViewGroup mMain;

    FileManagerView(
            final Context context,
            final Listener listener,
            final FileManagerLayoutMode initialLayoutMode) {
        mContext = context;
        mListener = listener;
        mUi = new DesktopUiFactory(context);
        mLayoutMode = initialLayoutMode;
        final boolean wide = context.getResources().getConfiguration()
                .screenWidthDp >= 720;
        mRailExpanded = wide;

        mRoot = new LinearLayout(context);
        mRoot.setOrientation(LinearLayout.HORIZONTAL);
        mRoot.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        mRoot.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        // Navigation rail.
        mRail = new LinearLayout(context);
        mRail.setOrientation(LinearLayout.VERTICAL);
        mRail.setPadding(dp(12), dp(12), dp(12), dp(12));
        final ImageButton menu = iconButton(R.drawable.ic_menu,
                R.string.file_manager_navigation, view -> setRailExpanded(!mRailExpanded));
        final LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        menuParams.setMargins(dp(4), 0, 0, dp(12));
        mRail.addView(menu, menuParams);
        final LinearLayout places = new LinearLayout(context);
        places.setOrientation(LinearLayout.VERTICAL);
        addPlace(places, R.string.file_manager_desktop, R.drawable.ic_show_desktop, STORAGE + "/Desktop");
        addPlace(places, R.string.file_manager_downloads, R.drawable.ic_download, STORAGE + "/Download");
        addPlace(places, R.string.file_manager_home, R.drawable.ic_storage, STORAGE);
        addPlace(places, R.string.file_manager_shell_access, R.drawable.ic_folder_open, "/");
        addRailDivider(places);
        addPlace(places, R.string.file_manager_images, R.drawable.ic_desktop_file_image, STORAGE + "/Pictures");
        addPlace(places, R.string.file_manager_camera, R.drawable.ic_camera, STORAGE + "/DCIM");
        addPlace(places, R.string.file_manager_videos, R.drawable.ic_video, STORAGE + "/Movies");
        addPlace(places, R.string.file_manager_audio, R.drawable.ic_audio, STORAGE + "/Music");
        addPlace(places, R.string.file_manager_documents, R.drawable.ic_desktop_file_document, STORAGE + "/Documents");
        final ScrollView placesScroll = new ScrollView(context);
        placesScroll.setVerticalScrollBarEnabled(false);
        placesScroll.addView(places, matchWrap());
        mRail.addView(placesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mRoot.addView(mRail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout main = new LinearLayout(context);
        mMain = main;
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(4), dp(12), dp(12), dp(8));
        mRoot.addView(main, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Top bar: history pill, address pill and overflow.
        final LinearLayout top = horizontal();
        final LinearLayout history = horizontal();
        history.setBackground(DesktopUiFactory.filled(
                DesktopUiFactory.COLOR_PANEL, dp(DesktopUiFactory.SHAPE_FULL_DP)));
        history.setPadding(dp(4), 0, dp(4), 0);
        mBack = iconButton(R.drawable.ic_file_back, R.string.file_manager_back,
                view -> listener.onBack());
        mForward = iconButton(R.drawable.ic_file_forward, R.string.file_manager_forward,
                view -> listener.onForward());
        mUp = iconButton(R.drawable.ic_file_up, R.string.file_manager_up,
                view -> listener.onUp());
        history.addView(mBack, iconParams());
        history.addView(mForward, iconParams());
        history.addView(mUp, iconParams());
        top.addView(history, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));

        final LinearLayout address = horizontal();
        address.setBackground(DesktopUiFactory.filled(
                DesktopUiFactory.COLOR_PANEL, dp(DesktopUiFactory.SHAPE_FULL_DP)));
        address.setPadding(dp(16), 0, dp(6), 0);
        final ImageView location = new ImageView(context);
        location.setImageResource(R.drawable.ic_folder_open);
        location.setColorFilter(DesktopUiFactory.COLOR_MUTED);
        address.addView(location, new LinearLayout.LayoutParams(dp(22), dp(22)));
        mPath = new EditText(context);
        mPath.setSingleLine(true);
        mPath.setHint(R.string.file_manager_path_hint);
        mPath.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mPath.setHintTextColor(DesktopUiFactory.COLOR_MUTED);
        mPath.setTextSize(15f);
        mPath.setSelectAllOnFocus(false);
        mPath.setBackground(null);
        mPath.setPadding(dp(12), 0, dp(8), 0);
        mPath.setOnEditorActionListener((view, actionId, event) -> {
            navigateFromAddress();
            return true;
        });
        address.addView(mPath, new LinearLayout.LayoutParams(0, dp(48), 1f));
        address.addView(iconButton(R.drawable.ic_search, R.string.file_manager_search,
                view -> listener.onRecursiveSearchRequested()), iconParams());
        final LinearLayout.LayoutParams addressParams =
                new LinearLayout.LayoutParams(0, dp(56), 1f);
        addressParams.setMargins(dp(8), 0, dp(8), 0);
        top.addView(address, addressParams);
        final ImageButton overflow = iconButton(R.drawable.ic_more,
                R.string.file_manager_options, this::showOverflowMenu);
        overflow.setBackground(interactive(DesktopUiFactory.COLOR_PANEL,
                dp(DesktopUiFactory.SHAPE_FULL_DP)));
        top.addView(overflow, new LinearLayout.LayoutParams(dp(56), dp(56)));
        main.addView(top, matchWrap());

        mFilterPanel = horizontal();
        mFilterPanel.setVisibility(View.GONE);
        mFilterPanel.setBackground(DesktopUiFactory.filled(
                DesktopUiFactory.COLOR_PANEL, dp(DesktopUiFactory.SHAPE_FULL_DP)));
        mFilterPanel.setPadding(dp(16), 0, dp(6), 0);
        mFilter = new EditText(context);
        mFilter.setSingleLine(true);
        mFilter.setHint(R.string.file_manager_filter_hint);
        mFilter.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mFilter.setHintTextColor(DesktopUiFactory.COLOR_MUTED);
        mFilter.setBackground(null);
        mFilter.setPadding(dp(4), 0, dp(4), 0);
        mFilter.addTextChangedListener(new TextWatcher() {
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
                listener.onFilterChanged(text.toString());
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        mFilterPanel.addView(mFilter, new LinearLayout.LayoutParams(0, dp(44), 1f));
        mFilterPanel.addView(iconButton(R.drawable.ic_close, R.string.file_manager_filter_clear,
                view -> clearFilter()), iconParams());
        final LinearLayout.LayoutParams filterParams = matchWrap();
        filterParams.setMargins(0, dp(8), 0, 0);
        main.addView(mFilterPanel, filterParams);

        // Toolbar: New, tonal actions and the view switch.
        final LinearLayout toolbarRow = horizontal();
        final LinearLayout actions = horizontal();
        final View newButton = pill(R.drawable.ic_add, R.string.file_manager_new, true,
                this::showNewMenu);
        actions.addView(newButton, pillParams());
        mCut = pill(R.drawable.ic_file_cut, R.string.file_manager_cut, false,
                view -> listener.onCut());
        mCopy = pill(R.drawable.ic_file_copy, R.string.file_manager_copy, false,
                view -> listener.onCopy());
        mPaste = pill(R.drawable.ic_file_paste, R.string.file_manager_paste, false,
                view -> listener.onPaste());
        mDelete = pill(R.drawable.ic_file_delete, R.string.action_delete, false,
                view -> listener.onDelete());
        mProperties = pill(R.drawable.ic_file_properties, R.string.file_manager_info, false,
                view -> listener.onProperties());
        mShare = pill(R.drawable.ic_share, R.string.file_manager_share, false,
                view -> listener.onShare());
        mOpenWith = pill(R.drawable.ic_file_open_with, R.string.file_manager_open_with, false,
                view -> listener.onOpenWith());
        final View more = pill(R.drawable.ic_more, R.string.file_manager_more, false,
                this::showMoreMenu);
        for (final View action : new View[] {
                mCut, mCopy, mPaste, mDelete, mProperties, mShare, mOpenWith, more}) {
            actions.addView(action, pillParams());
        }
        final HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actions, wrapWrap());
        toolbarRow.addView(actionsScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final LinearLayout modes = horizontal();
        modes.setBackground(DesktopUiFactory.filled(
                DesktopUiFactory.COLOR_PANEL, dp(DesktopUiFactory.SHAPE_FULL_DP)));
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        mListMode = iconButton(R.drawable.ic_view_list, R.string.file_manager_view_list,
                view -> selectLayoutMode(FileManagerLayoutMode.LIST));
        mGridMode = iconButton(R.drawable.ic_view_grid, R.string.file_manager_view_grid,
                view -> selectLayoutMode(FileManagerLayoutMode.GRID));
        modes.addView(mListMode, new LinearLayout.LayoutParams(dp(56), dp(44)));
        modes.addView(mGridMode, new LinearLayout.LayoutParams(dp(56), dp(44)));
        final LinearLayout.LayoutParams modesParams = wrapWrap();
        modesParams.setMarginStart(dp(8));
        toolbarRow.addView(modes, modesParams);
        final LinearLayout.LayoutParams toolbarParams = matchWrap();
        toolbarParams.setMargins(0, dp(12), 0, dp(12));
        main.addView(toolbarRow, toolbarParams);

        // Content card.
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(DesktopUiFactory.filled(
                DesktopUiFactory.COLOR_PANEL, dp(DesktopUiFactory.SHAPE_EXTRA_LARGE_DP)));
        card.setPadding(dp(16), dp(16), dp(16), dp(8));
        card.setClipToOutline(true);
        mTitle = new TextView(context);
        mTitle.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mTitle.setTextSize(24f);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mTitle.setPadding(dp(4), 0, dp(4), dp(12));
        card.addView(mTitle, matchWrap());

        mHeader = horizontal();
        mHeader.setPadding(0, 0, 0, dp(4));
        mSelectAll = new CheckBox(context);
        mSelectAll.setButtonTintList(ColorStateList.valueOf(DesktopUiFactory.COLOR_MUTED));
        mSelectAll.setContentDescription(context.getString(R.string.file_manager_select_all));
        mSelectAll.setOnClickListener(view -> listener.onSelectAll(mSelectAll.isChecked()));
        mHeader.addView(mSelectAll, new LinearLayout.LayoutParams(
                dp(ShellFileAdapter.CHECKBOX_DP), dp(40)));
        mSortName = sortHeader(R.string.file_manager_sort_name, ShellFileSystem.SORT_NAME);
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(dp(ShellFileAdapter.ICON_DP + 12));
        mHeader.addView(mSortName, nameParams);
        mSortSize = sortHeader(R.string.file_manager_sort_size, ShellFileSystem.SORT_SIZE);
        mHeader.addView(mSortSize, new LinearLayout.LayoutParams(
                dp(ShellFileAdapter.SIZE_COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        mSortModified = sortHeader(R.string.file_manager_sort_modified, ShellFileSystem.SORT_MODIFIED);
        mHeader.addView(mSortModified, new LinearLayout.LayoutParams(
                dp(ShellFileAdapter.MODIFIED_COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        mHeader.addView(new View(context), new LinearLayout.LayoutParams(
                dp(ShellFileAdapter.MENU_DP), 1));
        card.addView(mHeader, matchWrap());

        final FrameLayout listFrame = new FrameLayout(context);
        mList = new ListView(context);
        mList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        mList.setDividerHeight(dp(4));
        mList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        mList.setBackgroundColor(Color.TRANSPARENT);
        mList.setClipToPadding(false);
        mList.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        mList.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        mAdapter = new ShellFileAdapter(
                context,
                mUi,
                listener::onItemClick,
                listener::onSelectionChanged,
                listener::onContextMenu,
                listener::onStartDrag,
                listener::onDrop,
                listener::onApplicationDrop);
        mAdapter.setLayoutMode(initialLayoutMode);
        mAdapter.setColumns(wide);
        mList.setAdapter(mAdapter);
        mGrid = new GridView(context);
        mGrid.setNumColumns(GridView.AUTO_FIT);
        mGrid.setColumnWidth(dp(120));
        mGrid.setHorizontalSpacing(dp(8));
        mGrid.setVerticalSpacing(dp(8));
        mGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        mGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        mGrid.setClipToPadding(false);
        mGrid.setBackgroundColor(Color.TRANSPARENT);
        mGrid.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        mGrid.setAdapter(mAdapter);
        installBackgroundContextTarget(mList, listener);
        installBackgroundContextTarget(mGrid, listener);
        listFrame.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                return event.getClipDescription() != null;
            }
            if (event.getAction() == DragEvent.ACTION_DROP) {
                return listener.onDrop(event, null);
            }
            return true;
        });
        listFrame.addView(mList, matchMatch());
        listFrame.addView(mGrid, matchMatch());
        mEmpty = new TextView(context);
        mEmpty.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mEmpty.setTextSize(16f);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setVisibility(View.GONE);
        installBackgroundContextTarget(mEmpty, listener);
        listFrame.addView(mEmpty, matchMatch());
        card.addView(listFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        main.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mStatus = new TextView(context);
        mStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mStatus.setTextSize(12f);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        mStatus.setSingleLine(true);
        mStatus.setPadding(dp(8), 0, dp(8), 0);
        main.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        setRailExpanded(mRailExpanded);
        renderLayoutMode();
        renderSortHeader();
        updateItemVisibility();
        updateSelection(0, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void installBackgroundContextTarget(
            final View view, final Listener listener) {
        final float[] lastPosition = new float[2];
        view.setOnTouchListener((target, event) -> {
            lastPosition[0] = event.getRawX();
            lastPosition[1] = event.getRawY();
            return false;
        });
        view.setOnGenericMotionListener((target, event) -> {
            lastPosition[0] = event.getRawX();
            lastPosition[1] = event.getRawY();
            if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                    && event.getActionButton()
                            == MotionEvent.BUTTON_SECONDARY) {
                return listener.onBackgroundContextMenu(
                        target, event.getRawX(), event.getRawY());
            }
            return false;
        });
        view.setOnContextClickListener(target ->
                listener.onBackgroundContextMenu(
                        target, lastPosition[0], lastPosition[1]));
        view.setOnLongClickListener(target ->
                listener.onBackgroundContextMenu(
                        target, lastPosition[0], lastPosition[1]));
    }

    View root() {
        return mRoot;
    }

    void setPath(final String path) {
        if (!mPath.hasFocus()) {
            mPath.setText(path);
            mPath.setSelection(mPath.length());
        }
        final String name = path == null ? "" : path;
        final int slash = name.lastIndexOf('/');
        mTitle.setText(name.equals("/") || name.isEmpty()
                ? name : name.substring(slash + 1));
        final Place current = currentPlace(name);
        for (final Place place : mPlaces) {
            place.view.setSelected(place == current);
        }
    }

    /** The rail destination containing the path, by longest matching prefix. */
    private Place currentPlace(final String path) {
        Place best = null;
        for (final Place place : mPlaces) {
            final boolean contains = path.equals(place.path)
                    || path.startsWith(place.path.endsWith("/")
                            ? place.path : place.path + "/");
            if (contains && (best == null || place.path.length() > best.path.length())) {
                best = place;
            }
        }
        return best;
    }

    void setFiles(
            final List<ShellFileInfo> files,
            final Set<String> selectedPaths,
            final Map<String, DesktopEntry> desktopEntries) {
        mAdapter.set(files, selectedPaths, desktopEntries);
        mItemsAvailable = !files.isEmpty();
        mFileCount = files.size();
        mEmpty.setText(R.string.file_manager_empty);
        mEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        updateItemVisibility();
        renderSelectAll();
    }

    void updateSelection(final Set<String> selectedPaths) {
        mAdapter.setSelection(selectedPaths);
        refreshVisibleSelection(mList);
        refreshVisibleSelection(mGrid);
    }

    private void refreshVisibleSelection(final ViewGroup items) {
        for (int index = 0; index < items.getChildCount(); index++) {
            mAdapter.refreshSelection(items.getChildAt(index));
        }
    }

    void setSearchResults(final boolean searchResults) {
        mAdapter.setShowLocation(searchResults);
    }

    void setLoading() {
        mItemsAvailable = false;
        mEmpty.setText(R.string.file_manager_loading);
        mEmpty.setVisibility(View.VISIBLE);
        updateItemVisibility();
    }

    void setStatus(final String text) {
        mStatusState.message(text);
        renderStatus();
    }

    void setSummary(final String text) {
        mStatusState.summary(text);
        renderStatus();
    }

    void clearStatus() {
        mStatusState.clearMessage();
        renderStatus();
    }

    private void renderStatus() {
        mStatus.setText(mStatusState.text());
        mStatus.setTooltipText(mStatusState.text());
    }

    void setNavigationEnabled(
            final boolean back, final boolean forward, final boolean up) {
        mBack.setEnabled(back);
        mForward.setEnabled(forward);
        mUp.setEnabled(up);
    }

    void setSaveAction(final String label, final boolean enabled, final Runnable save, final Runnable cancel) {
        if (mSaveBar == null && label == null) return;
        if (mSaveBar == null) {
            mSaveBar = horizontal();
            mSaveBar.setBackground(DesktopUiFactory.filled(
                    DesktopUiFactory.COLOR_SECONDARY_CONTAINER, dp(DesktopUiFactory.SHAPE_FULL_DP)));
            mSaveBar.setPadding(dp(16), 0, dp(6), 0);
            mSaveName = new TextView(mContext);
            mSaveName.setTextColor(DesktopUiFactory.COLOR_ON_SECONDARY_CONTAINER);
            mSaveName.setSingleLine(true);
            mSaveName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            mSaveBar.addView(mSaveName, new LinearLayout.LayoutParams(0, dp(48), 1));
            mSaveName.setGravity(Gravity.CENTER_VERTICAL);
            mSaveHere = mUi.actionButton(R.string.file_manager_save_here, DesktopUiFactory.COLOR_ACCENT);
            mSaveBar.addView(mSaveHere, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
            mCancelSave = iconButton(R.drawable.ic_close, android.R.string.cancel, view -> { });
            mSaveBar.addView(mCancelSave, iconParams());
            final LinearLayout.LayoutParams params = matchWrap();
            params.setMargins(0, dp(8), 0, 0);
            mMain.addView(mSaveBar, 1, params);
        }
        mSaveBar.setVisibility(label == null ? View.GONE : View.VISIBLE);
        mSaveName.setText(label);
        mSaveHere.setEnabled(enabled);
        mSaveHere.setOnClickListener(view -> save.run());
        mCancelSave.setOnClickListener(view -> cancel.run());
    }

    void updateSelection(final int count, final boolean hasClipboard) {
        final boolean any = count > 0;
        final boolean single = count == 1;
        mSelectionCount = count;
        mCopy.setEnabled(any);
        mCut.setEnabled(any);
        mPaste.setEnabled(hasClipboard);
        mDelete.setEnabled(any);
        mProperties.setEnabled(single);
        mShare.setEnabled(single);
        mOpenWith.setEnabled(single);
        renderSelectAll();
    }

    private void renderSelectAll() {
        mSelectAll.setChecked(mFileCount > 0 && mSelectionCount >= mFileCount);
    }

    void setTerminalVisible(final boolean visible) {
        mTerminalVisible = visible;
    }

    void setShellReady(final boolean ready) {
        mShellReady = ready;
        mPath.setEnabled(ready);
        mList.setEnabled(ready);
        mGrid.setEnabled(ready);
    }

    void focusPath() {
        mPath.requestFocus();
        mPath.selectAll();
    }

    void focusFilter() {
        mFilterPanel.setVisibility(View.VISIBLE);
        mFilter.requestFocus();
        mFilter.selectAll();
    }

    void clearFilter() {
        mFilter.setText("");
        mFilterPanel.setVisibility(View.GONE);
    }

    void setShowHidden(final boolean showHidden) {
        mShowHidden = showHidden;
    }

    void setSortAscending(final boolean ascending) {
        mSortAscending = ascending;
        renderSortHeader();
    }

    private void showNewMenu(final View anchor) {
        final PopupMenu menu = new PopupMenu(mContext, anchor);
        final Menu items = menu.getMenu();
        items.add(R.string.action_new_folder).setOnMenuItemClickListener(item -> {
            mListener.onNewFolder();
            return true;
        });
        items.add(R.string.action_new_file).setOnMenuItemClickListener(item -> {
            mListener.onNewFile();
            return true;
        });
        items.add(R.string.file_manager_new_window).setEnabled(mShellReady)
                .setOnMenuItemClickListener(item -> {
                    mListener.onNewWindow();
                    return true;
                });
        menu.show();
    }

    private void showMoreMenu(final View anchor) {
        final PopupMenu menu = new PopupMenu(mContext, anchor);
        final Menu items = menu.getMenu();
        items.add(R.string.action_rename).setEnabled(mSelectionCount == 1)
                .setOnMenuItemClickListener(item -> {
                    mListener.onRename();
                    return true;
                });
        items.add(R.string.file_manager_console).setOnMenuItemClickListener(item -> {
            mListener.onOpenConsole();
            return true;
        });
        if (mTerminalVisible) {
            items.add(R.string.file_manager_terminal).setOnMenuItemClickListener(item -> {
                mListener.onOpenTerminal();
                return true;
            });
        }
        menu.show();
    }

    private void showOverflowMenu(final View anchor) {
        final PopupMenu menu = new PopupMenu(mContext, anchor);
        final Menu items = menu.getMenu();
        items.add(R.string.action_refresh).setEnabled(mShellReady)
                .setOnMenuItemClickListener(item -> {
                    mListener.onRefresh();
                    return true;
                });
        items.add(R.string.file_manager_filter).setOnMenuItemClickListener(item -> {
            focusFilter();
            return true;
        });
        items.add(R.string.file_manager_show_hidden).setCheckable(true).setChecked(mShowHidden)
                .setOnMenuItemClickListener(item -> {
                    mShowHidden = !mShowHidden;
                    mListener.onShowHiddenChanged(mShowHidden);
                    return true;
                });
        items.add(R.string.file_manager_new_window).setEnabled(mShellReady)
                .setOnMenuItemClickListener(item -> {
                    mListener.onNewWindow();
                    return true;
                });
        menu.show();
    }

    private void selectLayoutMode(final FileManagerLayoutMode layoutMode) {
        if (mLayoutMode == layoutMode) {
            return;
        }
        mLayoutMode = layoutMode;
        mAdapter.setLayoutMode(layoutMode);
        renderLayoutMode();
        updateItemVisibility();
        mListener.onViewModeChanged(layoutMode);
    }

    private void renderLayoutMode() {
        final boolean list = mLayoutMode == FileManagerLayoutMode.LIST;
        styleSegment(mListMode, list);
        styleSegment(mGridMode, !list);
    }

    private void styleSegment(final ImageButton button, final boolean selected) {
        button.setBackground(interactive(
                selected ? DesktopUiFactory.COLOR_ACCENT : Color.TRANSPARENT,
                dp(DesktopUiFactory.SHAPE_FULL_DP)));
        button.setImageTintList(ColorStateList.valueOf(selected
                ? DesktopUiFactory.COLOR_ON_ACCENT : DesktopUiFactory.COLOR_TEXT));
        button.setSelected(selected);
    }

    private void updateItemVisibility() {
        mList.setVisibility(mItemsAvailable
                && mLayoutMode == FileManagerLayoutMode.LIST
                ? View.VISIBLE : View.GONE);
        mGrid.setVisibility(mItemsAvailable
                && mLayoutMode == FileManagerLayoutMode.GRID
                ? View.VISIBLE : View.GONE);
        mHeader.setVisibility(mLayoutMode == FileManagerLayoutMode.LIST
                && mAdapter.hasColumns() ? View.VISIBLE : View.GONE);
    }

    private TextView sortHeader(final int label, final int sortMode) {
        final TextView header = new TextView(mContext);
        header.setText(label);
        header.setTextSize(14f);
        header.setTextColor(DesktopUiFactory.COLOR_MUTED);
        header.setTypeface(DesktopUiFactory.medium());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(8), dp(4), dp(8));
        header.setClickable(true);
        header.setFocusable(true);
        header.setBackground(mUi.stateLayerBackground(dp(DesktopUiFactory.SHAPE_SMALL_DP)));
        header.setTag(Integer.valueOf(sortMode));
        header.setOnClickListener(view -> {
            if (mSortMode == sortMode) {
                mListener.onSortDirectionChanged(!mSortAscending);
            } else {
                mSortMode = sortMode;
                mListener.onSortChanged(sortMode);
                renderSortHeader();
            }
        });
        return header;
    }

    private void renderSortHeader() {
        for (final TextView header : new TextView[] {mSortName, mSortSize, mSortModified}) {
            if (header == null) {
                continue;
            }
            final boolean active = ((Integer) header.getTag()).intValue() == mSortMode;
            final Drawable arrow = active ? mContext.getDrawable(mSortAscending
                    ? R.drawable.ic_arrow_up : R.drawable.ic_arrow_down).mutate() : null;
            if (arrow != null) {
                arrow.setTint(DesktopUiFactory.COLOR_TEXT);
                arrow.setBounds(0, 0, dp(18), dp(18));
            }
            header.setCompoundDrawablesRelative(null, null, arrow, null);
            header.setCompoundDrawablePadding(dp(6));
            header.setTextColor(active ? DesktopUiFactory.COLOR_TEXT : DesktopUiFactory.COLOR_MUTED);
            header.setContentDescription(header.getText() + (active ? ", " + mContext.getString(
                    mSortAscending ? R.string.file_manager_sort_ascending
                            : R.string.file_manager_sort_descending) : ""));
        }
    }

    private void addPlace(
            final LinearLayout parent,
            final int label,
            final int icon,
            final String path) {
        final Place place = new Place(label, icon, path);
        final LinearLayout row = horizontal();
        row.setPadding(dp(16), 0, dp(20), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setDefaultFocusHighlightEnabled(false);
        row.setContentDescription(mContext.getString(label));
        row.setTooltipText(mContext.getString(label));
        final int radius = dp(DesktopUiFactory.SHAPE_FULL_DP);
        final StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_selected},
                DesktopUiFactory.filled(DesktopUiFactory.COLOR_SECONDARY_CONTAINER, radius));
        background.addState(new int[] {android.R.attr.state_pressed},
                DesktopUiFactory.filled(DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.12f), radius));
        background.addState(new int[] {android.R.attr.state_focused},
                mUi.rounded(Color.TRANSPARENT, radius, DesktopUiFactory.COLOR_ACCENT));
        background.addState(new int[] {android.R.attr.state_hovered},
                DesktopUiFactory.filled(DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.08f), radius));
        background.addState(new int[0], DesktopUiFactory.filled(Color.TRANSPARENT, radius));
        row.setBackground(background);
        final ColorStateList content = new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_selected}, new int[0]},
                new int[] {DesktopUiFactory.COLOR_ON_SECONDARY_CONTAINER, DesktopUiFactory.COLOR_TEXT});
        final ImageView image = new ImageView(mContext);
        image.setImageResource(icon);
        image.setImageTintList(content);
        image.setDuplicateParentStateEnabled(true);
        row.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));
        final TextView text = new TextView(mContext);
        text.setText(label);
        text.setTextSize(15f);
        text.setTextColor(content);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setDuplicateParentStateEnabled(true);
        text.setPadding(dp(16), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mRailLabels.add(text);
        row.setOnClickListener(view -> mListener.onNavigate(path));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, dp(2), 0, dp(2));
        parent.addView(row, params);
        place.view = row;
        mPlaces.add(place);
    }

    private void addRailDivider(final LinearLayout parent) {
        final View divider = new View(mContext);
        divider.setBackgroundColor(DesktopUiFactory.COLOR_OUTLINE_VARIANT);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(dp(16), dp(10), dp(16), dp(10));
        parent.addView(divider, params);
    }

    private void setRailExpanded(final boolean expanded) {
        mRailExpanded = expanded;
        for (final TextView label : mRailLabels) {
            label.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        final ViewGroup.LayoutParams params = mRail.getLayoutParams();
        if (params != null) {
            params.width = expanded ? dp(264) : dp(80);
            mRail.setLayoutParams(params);
        }
    }

    private void navigateFromAddress() {
        final String path = mPath.getText().toString().trim();
        if (path.length() > 0) {
            mListener.onNavigate(path);
        }
    }

    /** Material tonal pill with an icon and label; {@code primary} fills it. */
    private View pill(
            final int icon,
            final int label,
            final boolean primary,
            final View.OnClickListener listener) {
        final LinearLayout pill = horizontal();
        pill.setPadding(dp(primary ? 12 : 14), 0, dp(18), 0);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setDefaultFocusHighlightEnabled(false);
        pill.setContentDescription(mContext.getString(label));
        pill.setTooltipText(mContext.getString(label));
        final int container = primary ? DesktopUiFactory.COLOR_ACCENT : DesktopUiFactory.COLOR_PANEL;
        final int onContainer = primary ? DesktopUiFactory.COLOR_ON_ACCENT : DesktopUiFactory.COLOR_TEXT;
        final int radius = dp(DesktopUiFactory.SHAPE_FULL_DP);
        final StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {-android.R.attr.state_enabled},
                DesktopUiFactory.filled(primary
                        ? DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.12f)
                        : DesktopUiFactory.COLOR_PANEL, radius));
        background.addState(new int[] {android.R.attr.state_pressed},
                DesktopUiFactory.filled(DesktopUiFactory.stateLayer(container, onContainer, 0.16f), radius));
        background.addState(new int[] {android.R.attr.state_focused},
                mUi.rounded(DesktopUiFactory.stateLayer(container, onContainer, 0.10f), radius,
                        primary ? DesktopUiFactory.COLOR_TEXT : DesktopUiFactory.COLOR_ACCENT));
        background.addState(new int[] {android.R.attr.state_hovered},
                DesktopUiFactory.filled(DesktopUiFactory.stateLayer(container, onContainer, 0.08f), radius));
        background.addState(new int[0], DesktopUiFactory.filled(container, radius));
        pill.setBackground(background);
        final ColorStateList content = new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                new int[] {DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.38f), onContainer});
        final ImageView image = new ImageView(mContext);
        image.setImageResource(icon);
        image.setImageTintList(content);
        image.setDuplicateParentStateEnabled(true);
        final int iconSize = primary ? 26 : 22;
        pill.addView(image, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));
        final TextView text = new TextView(mContext);
        text.setText(label);
        text.setTextSize(14f);
        text.setTypeface(DesktopUiFactory.medium());
        text.setTextColor(content);
        text.setDuplicateParentStateEnabled(true);
        text.setPadding(dp(8), 0, 0, 0);
        pill.addView(text, wrapWrap());
        if (primary) {
            final ImageView chevron = new ImageView(mContext);
            chevron.setImageResource(R.drawable.ic_arrow_down);
            chevron.setImageTintList(content);
            chevron.setDuplicateParentStateEnabled(true);
            final LinearLayout.LayoutParams chevronParams =
                    new LinearLayout.LayoutParams(dp(18), dp(18));
            chevronParams.setMarginStart(dp(6));
            pill.addView(chevron, chevronParams);
        }
        pill.setOnClickListener(listener);
        return pill;
    }

    private ImageButton iconButton(
            final int drawable,
            final int description,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawable);
        button.setImageTintList(new ColorStateList(
                new int[][]{
                    new int[]{-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[]{DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.38f),
                        DesktopUiFactory.COLOR_TEXT}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(mUi.stateLayerBackground(dp(DesktopUiFactory.SHAPE_FULL_DP)));
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        button.setContentDescription(mContext.getString(description));
        button.setTooltipText(mContext.getString(description));
        button.setOnClickListener(listener);
        return button;
    }

    private StateListDrawable interactive(final int color, final int radius) {
        final StateListDrawable background = new StateListDrawable();
        final int base = color == Color.TRANSPARENT ? DesktopUiFactory.COLOR_PANEL : color;
        background.addState(new int[] {android.R.attr.state_pressed},
                DesktopUiFactory.filled(DesktopUiFactory.stateLayer(base, DesktopUiFactory.COLOR_TEXT, 0.16f), radius));
        background.addState(new int[] {android.R.attr.state_hovered},
                DesktopUiFactory.filled(DesktopUiFactory.stateLayer(base, DesktopUiFactory.COLOR_TEXT, 0.08f), radius));
        background.addState(new int[0], DesktopUiFactory.filled(color, radius));
        return background;
    }

    private LinearLayout horizontal() {
        final LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams iconParams() {
        return new LinearLayout.LayoutParams(dp(44), dp(44));
    }

    private LinearLayout.LayoutParams pillParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        params.setMarginEnd(dp(8));
        return params;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
