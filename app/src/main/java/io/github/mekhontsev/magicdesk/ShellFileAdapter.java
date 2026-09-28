package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShellFileAdapter extends BaseAdapter {
    interface ClickListener {
        void onClick(ShellFileInfo file, int metaState, long eventTime);
    }

    interface SelectionListener {
        void onSelectionChanged(ShellFileInfo file, boolean selected);
    }

    interface ContextListener {
        boolean onContextClick(View anchor, ShellFileInfo file);
    }

    interface LongClickListener {
        boolean onLongClick(
                View anchor, ShellFileInfo file, int metaState);
    }

    interface DropListener {
        boolean onDrop(DragEvent event, String destinationPath);
    }

    interface ApplicationDropListener {
        boolean onDrop(
                DragEvent event,
                ShellFileInfo file,
                DesktopApplicationShortcut shortcut);
    }

    /** Column widths shared with the file manager's table header. */
    static final int CHECKBOX_DP = 44;
    static final int ICON_DP = 40;
    static final int SIZE_COLUMN_DP = 120;
    static final int MODIFIED_COLUMN_DP = 176;
    static final int MENU_DP = 44;

    private final Context mContext;
    private final DesktopUiFactory mUi;
    private final ClickListener mClickListener;
    private final SelectionListener mListener;
    private final ContextListener mContextListener;
    private final LongClickListener mLongClickListener;
    private final DropListener mDropListener;
    private final ApplicationDropListener mApplicationDropListener;
    private final List<ShellFileInfo> mFiles = new ArrayList<>();
    private final Set<String> mSelected = new HashSet<>();
    private final Map<String, DesktopEntry> mDesktopEntries =
            new LinkedHashMap<>();
    private FileManagerLayoutMode mLayoutMode =
            FileManagerLayoutMode.LIST;
    private boolean mShowLocation;
    /** Wide windows show Size and Modified as table columns. */
    private boolean mColumns;

    ShellFileAdapter(
            final Context context,
            final DesktopUiFactory ui,
            final ClickListener clickListener,
            final SelectionListener listener,
            final ContextListener contextListener,
            final LongClickListener longClickListener,
            final DropListener dropListener,
            final ApplicationDropListener applicationDropListener) {
        mContext = context;
        mUi = ui;
        mClickListener = clickListener;
        mListener = listener;
        mContextListener = contextListener;
        mLongClickListener = longClickListener;
        mDropListener = dropListener;
        mApplicationDropListener = applicationDropListener;
    }

    void set(
            final List<ShellFileInfo> files,
            final Set<String> selected,
            final Map<String, DesktopEntry> desktopEntries) {
        mFiles.clear();
        mFiles.addAll(files);
        mSelected.clear();
        mSelected.addAll(selected);
        mDesktopEntries.clear();
        mDesktopEntries.putAll(desktopEntries);
        notifyDataSetChanged();
    }

    void setLayoutMode(final FileManagerLayoutMode layoutMode) {
        if (mLayoutMode == layoutMode) {
            return;
        }
        mLayoutMode = layoutMode;
        notifyDataSetChanged();
    }

    void setColumns(final boolean columns) {
        if (mColumns == columns) {
            return;
        }
        mColumns = columns;
        notifyDataSetChanged();
    }

    boolean hasColumns() {
        return mColumns;
    }

    void setSelection(final Set<String> selected) {
        mSelected.clear();
        mSelected.addAll(selected);
    }

    void refreshSelection(final View view) {
        if (view == null || !(view.getTag() instanceof ItemView)) {
            return;
        }
        applySelection((ItemView) view.getTag());
    }

    void setShowLocation(final boolean showLocation) {
        if (mShowLocation == showLocation) {
            return;
        }
        mShowLocation = showLocation;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mFiles.size();
    }

    @Override
    public ShellFileInfo getItem(final int position) {
        return mFiles.get(position);
    }

    @Override
    public long getItemId(final int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return FileManagerLayoutMode.values().length;
    }

    @Override
    public int getItemViewType(final int position) {
        return mLayoutMode.ordinal();
    }

    @Override
    public View getView(
            final int position,
            final View recycled,
            final ViewGroup parent) {
        final ItemView item = recycled != null
                && recycled.getTag() instanceof ItemView
                && ((ItemView) recycled.getTag()).layoutMode == mLayoutMode
                && ((ItemView) recycled.getTag()).columns == mColumns
                ? (ItemView) recycled.getTag()
                : createItemView();
        final ShellFileInfo file = getItem(position);
        final DesktopEntry desktopEntry =
                mDesktopEntries.get(file.absolutePath);
        final DesktopFolderShortcut folderShortcut =
                desktopEntry instanceof DesktopFolderShortcut
                        ? (DesktopFolderShortcut) desktopEntry : null;
        final DesktopApplicationShortcut applicationShortcut =
                desktopEntry instanceof DesktopApplicationShortcut
                        ? (DesktopApplicationShortcut) desktopEntry : null;
        final DesktopWebShortcut webShortcut =
                desktopEntry instanceof DesktopWebShortcut
                        ? (DesktopWebShortcut) desktopEntry : null;
        item.file = file;
        applySelection(item);
        item.icon.clearColorFilter();
        if (folderShortcut != null) {
            item.icon.setImageResource(R.drawable.ic_desktop_folder_link);
        } else if (applicationShortcut != null) {
            item.icon.setImageDrawable(DesktopApplicationIconResolver.resolve(
                    mContext, applicationShortcut));
        } else if (webShortcut != null) {
            item.icon.setImageResource(R.drawable.ic_desktop_web_link);
        } else {
            item.icon.setImageResource(
                    FileIconResolver.forFile(file.directory, file.mimeType));
        }
        item.icon.setAlpha(folderShortcut == null
                || folderShortcut.available ? 1f : 0.45f);
        bindPreview(item, file, desktopEntry == null);
        final String displayName = desktopEntry == null
                ? file.name : desktopEntry.name;
        item.icon.setContentDescription(displayName);
        item.name.setText(displayName);
        item.name.setTypeface(file.directory || desktopEntry != null
                ? DesktopUiFactory.medium() : Typeface.DEFAULT);
        if (item.size != null) {
            item.size.setText(file.directory ? "\u2014" : FileSizeFormatter.format(file.size));
            item.modified.setText(DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(file.modified)));
        }
        if (item.menu != null) {
            item.menu.setOnClickListener(view -> mContextListener.onContextClick(view, file));
        }
        if (item.details != null && mColumns && !mShowLocation && desktopEntry == null) {
            item.details.setText(typeLabel(file));
        } else if (item.details != null) {
            item.details.setText(mShowLocation
                    ? file.absolutePath
                    : folderShortcut != null
                            ? folderShortcut.targetPath
                            : applicationShortcut != null
                                    ? applicationDetails(applicationShortcut)
                                    : webShortcut != null
                                            ? webShortcut.url
                                            : details(file));
        }
        item.metaState = 0;
        item.eventTime = 0L;
        new DeferredContextDragGesture(
                item.root,
                true,
                true,
                new DeferredContextDragGesture.Listener() {
                    @Override
                    public boolean onStartDrag(
                            final View target, final MotionEvent event) {
                        return mLongClickListener.onLongClick(
                                target, file, event.getMetaState());
                    }

                    @Override
                    public void onShowContextMenu(final View target) {
                        mContextListener.onContextClick(target, file);
                    }

                    @Override
                    public boolean onTap(
                            final View target, final MotionEvent event) {
                        mClickListener.onClick(
                                file, event.getMetaState(), event.getEventTime());
                        return true;
                    }

                    @Override
                    public void onPointerEvent(final MotionEvent event) {
                        final int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN
                                || action == MotionEvent.ACTION_BUTTON_PRESS
                                || action == MotionEvent.ACTION_UP) {
                            item.metaState = event.getMetaState();
                            item.eventTime = event.getEventTime();
                        }
                    }
                });
        item.root.setOnClickListener(view ->
                mClickListener.onClick(
                        file,
                        item.metaState,
                        android.os.SystemClock.uptimeMillis()));
        item.root.setOnContextClickListener(view ->
                mContextListener.onContextClick(view, file));
        if (file.directory || folderShortcut != null) {
            item.root.setOnDragListener((view, event) -> handleFolderDrag(
                    item,
                    file,
                    folderShortcut == null
                            ? file.absolutePath
                            : folderShortcut.targetPath,
                    event));
        } else if (applicationShortcut != null
                && applicationShortcut.hasExecLaunch()
                && DesktopExecTemplate.acceptsArguments(
                        applicationShortcut.exec)) {
            item.root.setOnDragListener((view, event) ->
                    handleApplicationDrag(
                            item, file, applicationShortcut, event));
        } else {
            item.root.setOnDragListener(null);
        }
        return item.root;
    }

    /**
     * Replaces the type icon with a photo or video preview once it loads.
     * Recycled rows keep their type icon until their own preview is ready.
     */
    private void bindPreview(
            final ItemView item,
            final ShellFileInfo file,
            final boolean ordinaryFile) {
        item.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        item.icon.setBackground(null);
        item.icon.setClipToOutline(false);
        if (item.playBadge != null) {
            item.playBadge.setVisibility(View.GONE);
        }
        if (!ordinaryFile || file.directory || file.symbolicLink || !file.readable
                || !file.isRegularFile() || !MediaThumbnails.supports(file.mimeType)) {
            ThumbnailLoader.clear(item.icon);
            return;
        }
        final int sizePx = dp(item.layoutMode == FileManagerLayoutMode.GRID ? 96 : 64);
        ThumbnailLoader.request(
                item.icon,
                MediaThumbnails.key(file.absolutePath, file.size, file.modified, sizePx),
                file.mimeType,
                sizePx,
                () -> ShellAccess.openVerifiedShellFile(file, "r"),
                bitmap -> {
                    item.icon.setImageBitmap(bitmap);
                    item.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    item.icon.setBackground(DesktopUiFactory.filled(
                            DesktopUiFactory.COLOR_PANEL_ALT, dp(DesktopUiFactory.SHAPE_SMALL_DP)));
                    item.icon.setClipToOutline(true);
                    if (item.playBadge != null) {
                        item.playBadge.setVisibility(
                                MediaThumbnails.isVideo(file.mimeType) ? View.VISIBLE : View.GONE);
                    }
                });
    }

    private void applySelection(final ItemView item) {
        if (item.file == null) {
            return;
        }
        final boolean selected = mSelected.contains(
                item.file.absolutePath);
        if (item.checkbox != null) {
            item.checkbox.setOnCheckedChangeListener(null);
            item.checkbox.setChecked(selected);
            item.checkbox.setOnCheckedChangeListener((button, checked) ->
                    mListener.onSelectionChanged(item.file, checked));
        }
        item.root.setActivated(selected);
    }

    /** Drop-target highlight reuses the selected appearance. */
    private void setDropTarget(final ItemView item, final boolean target) {
        if (target) {
            item.root.setActivated(true);
        } else {
            applySelection(item);
        }
    }

    private boolean handleFolderDrag(
            final ItemView item,
            final ShellFileInfo folder,
            final String destinationPath,
            final DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getClipDescription() != null;
            case DragEvent.ACTION_DRAG_ENTERED:
                setDropTarget(item, true);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                setDropTarget(item, false);
                return true;
            case DragEvent.ACTION_DROP:
                setDropTarget(item, false);
                return mDropListener.onDrop(event, destinationPath);
            default:
                return true;
        }
    }

    private boolean handleApplicationDrag(
            final ItemView item,
            final ShellFileInfo file,
            final DesktopApplicationShortcut shortcut,
            final DragEvent event) {
        final FileDragPayload payload = FileDragPayload.from(event);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getClipDescription() != null
                        && (payload == null
                                || !payload.absolutePaths.contains(
                                        file.absolutePath));
            case DragEvent.ACTION_DRAG_ENTERED:
                setDropTarget(item, true);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                applySelection(item);
                return true;
            case DragEvent.ACTION_DROP:
                applySelection(item);
                return mApplicationDropListener.onDrop(
                        event, file, shortcut);
            default:
                return true;
        }
    }

    private ItemView createItemView() {
        return mLayoutMode == FileManagerLayoutMode.GRID
                ? createGridItem() : createListItem();
    }

    private ItemView createListItem() {
        final LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(0, dp(6), 0, dp(6));
        root.setMinimumHeight(dp(64));
        prepareRoot(root);
        final CheckBox checkbox = new CheckBox(mContext);
        checkbox.setFocusable(false);
        checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(
                DesktopUiFactory.COLOR_MUTED));
        root.addView(checkbox, new LinearLayout.LayoutParams(
                dp(CHECKBOX_DP), dp(44)));
        final ImageView icon = new ImageView(mContext);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final FrameLayout iconFrame = new FrameLayout(mContext);
        iconFrame.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final ImageView listBadge = DesktopItemViewFactory.playBadge(mContext, mUi, dp(16));
        listBadge.setVisibility(View.GONE);
        iconFrame.addView(listBadge, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        final LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(ICON_DP), dp(ICON_DP));
        iconParams.setMarginEnd(dp(12));
        root.addView(iconFrame, iconParams);
        final LinearLayout labels = new LinearLayout(mContext);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        final TextView name = new TextView(mContext);
        name.setTextColor(DesktopUiFactory.COLOR_TEXT);
        name.setTextSize(16f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        final TextView details = new TextView(mContext);
        details.setTextColor(DesktopUiFactory.COLOR_MUTED);
        details.setTextSize(13f);
        details.setSingleLine(true);
        details.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        labels.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView size = null;
        TextView modified = null;
        ImageButton menu = null;
        if (mColumns) {
            size = column(SIZE_COLUMN_DP);
            root.addView(size, new LinearLayout.LayoutParams(
                    dp(SIZE_COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
            modified = column(MODIFIED_COLUMN_DP);
            root.addView(modified, new LinearLayout.LayoutParams(
                    dp(MODIFIED_COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
            menu = new ImageButton(mContext);
            menu.setImageResource(R.drawable.ic_more);
            menu.setImageTintList(android.content.res.ColorStateList.valueOf(
                    DesktopUiFactory.COLOR_TEXT));
            menu.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            menu.setBackground(mUi.stateLayerBackground(dp(DesktopUiFactory.SHAPE_FULL_DP)));
            menu.setFocusable(false);
            menu.setContentDescription(mContext.getString(R.string.file_manager_options));
            root.addView(menu, new LinearLayout.LayoutParams(dp(MENU_DP), dp(MENU_DP)));
        }
        final ItemView item = finishItemView(root, checkbox, icon, name, details,
                FileManagerLayoutMode.LIST);
        item.playBadge = listBadge;
        item.size = size;
        item.modified = modified;
        item.menu = menu;
        return item;
    }

    private TextView column(final int widthDp) {
        final TextView column = new TextView(mContext);
        column.setTextColor(DesktopUiFactory.COLOR_MUTED);
        column.setTextSize(14f);
        column.setSingleLine(true);
        column.setEllipsize(TextUtils.TruncateAt.END);
        column.setPadding(dp(4), 0, dp(4), 0);
        return column;
    }

    private ItemView createGridItem() {
        final LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(8), dp(10), dp(8), dp(8));
        root.setMinimumHeight(dp(124));
        prepareRoot(root);

        final FrameLayout iconArea = new FrameLayout(mContext);
        final ImageView icon = new ImageView(mContext);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final FrameLayout.LayoutParams iconParams =
                new FrameLayout.LayoutParams(dp(56), dp(56));
        iconParams.gravity = Gravity.CENTER;
        iconArea.addView(icon, iconParams);
        final ImageView gridBadge = DesktopItemViewFactory.playBadge(mContext, mUi, dp(22));
        gridBadge.setVisibility(View.GONE);
        iconArea.addView(gridBadge, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        root.addView(iconArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        final TextView name = new TextView(mContext);
        name.setTextColor(DesktopUiFactory.COLOR_TEXT);
        name.setTextSize(13f);
        name.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        nameParams.topMargin = dp(6);
        root.addView(name, nameParams);
        final ItemView item = finishItemView(root, null, icon, name, null,
                FileManagerLayoutMode.GRID);
        item.playBadge = gridBadge;
        return item;
    }

    private void prepareRoot(final ViewGroup root) {
        root.setClickable(true);
        root.setFocusable(true);
        root.setDefaultFocusHighlightEnabled(false);
        // Material rows: rounded state layers, tonal fill when selected.
        final int radius = dp(DesktopUiFactory.SHAPE_LARGE_DP);
        final android.graphics.drawable.StateListDrawable background =
                new android.graphics.drawable.StateListDrawable();
        background.addState(new int[] {android.R.attr.state_activated},
                DesktopUiFactory.filled(DesktopUiFactory.COLOR_SECONDARY_CONTAINER, radius));
        background.addState(new int[] {android.R.attr.state_pressed},
                DesktopUiFactory.filled(DesktopUiFactory.withAlpha(
                        DesktopUiFactory.COLOR_TEXT, 0.12f), radius));
        background.addState(new int[] {android.R.attr.state_focused},
                mUi.rounded(DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.08f),
                        radius, DesktopUiFactory.COLOR_ACCENT));
        background.addState(new int[] {android.R.attr.state_hovered},
                DesktopUiFactory.filled(DesktopUiFactory.COLOR_PANEL_ALT, radius));
        background.addState(new int[0], DesktopUiFactory.filled(Color.TRANSPARENT, radius));
        root.setBackground(background);
    }

    private ItemView finishItemView(
            final ViewGroup root,
            final CheckBox checkbox,
            final ImageView icon,
            final TextView name,
            final TextView details,
            final FileManagerLayoutMode layoutMode) {
        final ItemView item = new ItemView(
                root, checkbox, icon, name, details, layoutMode, mColumns);
        root.setTag(item);
        return item;
    }

    private String details(final ShellFileInfo file) {
        final String type = file.symbolicLink
                ? "link" : file.directory ? "folder" : file.mimeType;
        final String size = file.directory ? ""
                : "  " + FileSizeFormatter.format(file.size);
        final String modified = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(file.modified));
        return type + size + "  " + modified;
    }

    /** Short type label for the table's second line. */
    private String typeLabel(final ShellFileInfo file) {
        if (file.symbolicLink) {
            return mContext.getString(R.string.file_manager_type_link);
        }
        if (file.directory) {
            return mContext.getString(R.string.file_manager_type_folder);
        }
        final int dot = file.name.lastIndexOf('.');
        if (dot > 0 && dot < file.name.length() - 1) {
            return mContext.getString(R.string.file_manager_type_file,
                    file.name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT));
        }
        return file.mimeType == null || file.mimeType.isEmpty()
                ? mContext.getString(R.string.file_manager_type_generic) : file.mimeType;
    }

    private static String applicationDetails(
            final DesktopApplicationShortcut shortcut) {
        return shortcut.launchTarget == null
                ? shortcut.exec : shortcut.launchTarget.packageName;
    }

    private int dp(final int value) {
        return Math.round(value * mContext.getResources()
                .getDisplayMetrics().density);
    }

    private static final class ItemView {
        final ViewGroup root;
        final CheckBox checkbox;
        final ImageView icon;
        final TextView name;
        final TextView details;
        final FileManagerLayoutMode layoutMode;
        final boolean columns;
        TextView size;
        TextView modified;
        ImageButton menu;
        ImageView playBadge;
        ShellFileInfo file;
        int metaState;
        long eventTime;

        ItemView(
                final ViewGroup root,
                final CheckBox checkbox,
                final ImageView icon,
                final TextView name,
                final TextView details,
                final FileManagerLayoutMode layoutMode,
                final boolean columns) {
            this.root = root;
            this.checkbox = checkbox;
            this.icon = icon;
            this.name = name;
            this.details = details;
            this.layoutMode = layoutMode;
            this.columns = columns;
        }
    }
}
