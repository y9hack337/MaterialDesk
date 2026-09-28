package io.github.mekhontsev.magicdesk;

import android.appwidget.AppWidgetHostView;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DesktopWorkspaceController {
    private static final String TAG = "MagicDeskWorkspace";
    private static final String FILE_PREFIX = "file:";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final DesktopItemViewFactory mViews;
    private final DesktopFolderController mFolder;
    private final DesktopWidgetController mWidgets;
    private final ItemActivationPolicy mItemActivation;
    private final FileOpenWithController mOpenWith;
    private final AndroidContentActionGateway mContentActions;
    private final ExecutorService mContentWorker =
            Executors.newSingleThreadExecutor(runnable ->
                    new Thread(runnable, "MagicDeskContentAction"));
    private final ContentRequestScope mContentRequests = new ContentRequestScope(mContentWorker);

    private DesktopGridLayout mGrid;
    private List<AppItem> mApps = new ArrayList<>();
    private List<DesktopFile> mFiles = new ArrayList<>();
    private final Map<String, DesktopPlacement> mRenderedPlacements =
            new LinkedHashMap<>();
    private int mLastCapacity;
    private int mEditingWidgetId = -1;
    /** Selected file items in selection order; the marquee may add several. */
    private final Set<String> mSelectedFileItemIds = new LinkedHashSet<>();
    private DesktopSelectionMarquee mMarquee;
    private Set<String> mMarqueeBase = new LinkedHashSet<>();

    DesktopWorkspaceController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mViews = new DesktopItemViewFactory(activity, ui);
        mItemActivation = new ItemActivationPolicy(
                MagicDeskSettings.load().openFilesWithSingleClick,
                ViewConfiguration.getDoubleTapTimeout());
        mOpenWith = new FileOpenWithController(
                activity, mContentWorker, activity::showDesktopDialog);
        mContentActions = new AndroidContentActionGateway(activity);
        mFolder = new DesktopFolderController(
                activity,
                new DesktopFileRepository(activity),
                this::onFilesChanged,
                activity::onDesktopMetadataChanged);
        mWidgets = new DesktopWidgetController(
                activity, ui, this::onWidgetsChanged);
    }

    void saveInstanceState(final Bundle outState) {
        mWidgets.saveInstanceState(outState);
    }

    void restoreInstanceState(final Bundle state) {
        mWidgets.restoreInstanceState(state);
    }

    DesktopGridLayout createGrid() {
        final DesktopGridLayout grid = new DesktopGridLayout(
                mActivity,
                desktopDp(112, 82),
                desktopDp(102, 78));
        grid.setListener(new DesktopGridLayout.Listener() {
            @Override
            public void onGridSizeChanged(final int columns, final int rows) {
                final int capacity = columns * rows;
                render(mApps);
                if (capacity > mLastCapacity) {
                    mFolder.refresh(true, capacity);
                }
                mLastCapacity = capacity;
            }

            @Override
            public void onItemDropped(
                    final String itemId,
                    final int column,
                    final int row) {
                moveItem(itemId, column, row);
            }

            @Override
            public boolean onExternalDrop(final DragEvent event) {
                return importDroppedFiles(event);
            }
        });
        mGrid = grid;
        mMarquee = new DesktopSelectionMarquee(
                ViewConfiguration.get(mActivity).getScaledTouchSlop());
        return grid;
    }

    /**
     * Draws a rubber-band selection over empty desktop space. Returns true
     * while the gesture is a marquee, so the caller skips tap and long press.
     */
    boolean handleSelectionTouch(final MotionEvent event) {
        if (mGrid == null || mMarquee == null) {
            return false;
        }
        final int[] origin = new int[2];
        mGrid.getLocationOnScreen(origin);
        final float x = event.getRawX() - origin[0];
        final float y = event.getRawY() - origin[1];
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (x < 0 || y < 0 || x >= mGrid.getWidth() || y >= mGrid.getHeight()) {
                    return false;
                }
                mMarquee.down(x, y);
                // Ctrl extends the existing selection, as in desktop file managers.
                mMarqueeBase = (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0
                        ? new LinkedHashSet<>(mSelectedFileItemIds) : new LinkedHashSet<>();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!mMarquee.move(x, y)) {
                    return false;
                }
                final int[] bounds = mMarquee.bounds();
                mGrid.setSelectionBounds(bounds);
                final Set<String> selection = new LinkedHashSet<>(mMarqueeBase);
                for (final String itemId : mGrid.itemIdsWithin(bounds)) {
                    if (itemId.startsWith(FILE_PREFIX)) {
                        selection.add(itemId);
                    }
                }
                setSelection(selection);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mGrid.setSelectionBounds(null);
                return mMarquee.end();
            default:
                return mMarquee.isActive();
        }
    }

    boolean isSelecting() {
        return mMarquee != null && mMarquee.isActive();
    }

    private void setSelection(final Set<String> itemIds) {
        if (mSelectedFileItemIds.equals(itemIds)) {
            return;
        }
        mSelectedFileItemIds.clear();
        mSelectedFileItemIds.addAll(itemIds);
        mItemActivation.reset();
        applySelectionState();
    }

    /** Updates selection visuals in place, without rebuilding desktop items. */
    private void applySelectionState() {
        if (mGrid == null) {
            return;
        }
        for (int index = 0; index < mGrid.getChildCount(); index++) {
            final View child = mGrid.getChildAt(index);
            final DesktopGridLayout.LayoutParams params =
                    (DesktopGridLayout.LayoutParams) child.getLayoutParams();
            child.setActivated(mSelectedFileItemIds.contains(params.itemId));
        }
    }

    void start() {
        mFolder.start();
        mWidgets.start();
    }

    void stop() {
        mFolder.stop();
        mWidgets.stop();
    }

    void release() {
        mContentRequests.close();
        mOpenWith.close();
        mContentWorker.shutdownNow();
        mFolder.release();
        mWidgets.release();
        mGrid = null;
    }

    boolean handleActivityResult(
            final int requestCode,
            final int resultCode,
            final Intent data) {
        return mWidgets.handleActivityResult(
                requestCode, resultCode, data);
    }

    void render(final List<AppItem> apps) {
        mApps = apps == null ? new ArrayList<>() : new ArrayList<>(apps);
        if (mGrid == null
                || mGrid.getColumnCount() <= 0
                || mGrid.getRowCount() <= 0) {
            return;
        }
        final Map<String, GlobalDesktopPlacement> storedPlacements =
                DesktopLayoutStore.snapshot();
        final List<Entry> entries = collectEntries(storedPlacements);
        final List<DesktopPlacementEngine.Request> requests =
                new ArrayList<>();
        addRequests(entries, storedPlacements, requests, true);
        addRequests(entries, storedPlacements, requests, false);
        final Map<String, DesktopPlacement> arranged =
                DesktopPlacementEngine.arrange(
                        requests,
                        mGrid.getColumnCount(),
                        mGrid.getRowCount());

        final List<Entry> hiddenFiles = new ArrayList<>();
        for (final Entry entry : entries) {
            if (entry.file != null && !arranged.containsKey(entry.itemId)) {
                hiddenFiles.add(entry);
            }
        }
        DesktopPlacement overflowPlacement = null;
        if (!hiddenFiles.isEmpty()) {
            for (int index = entries.size() - 1; index >= 0; index--) {
                final Entry entry = entries.get(index);
                if (entry.file == null) {
                    continue;
                }
                overflowPlacement = arranged.remove(entry.itemId);
                if (overflowPlacement != null) {
                    hiddenFiles.add(entry);
                    break;
                }
            }
        }

        mRenderedPlacements.clear();
        mRenderedPlacements.putAll(arranged);
        if (overflowPlacement != null) {
            mRenderedPlacements.put("folder:overflow", overflowPlacement);
        }

        mGrid.removeAllViews();
        for (final Entry entry : entries) {
            final DesktopPlacement placement = arranged.get(entry.itemId);
            if (placement != null) {
                addEntryView(entry, placement);
            }
        }
        if (overflowPlacement != null) {
            final View overflow = mViews.overflow(hiddenFiles.size());
            overflow.setOnClickListener(view -> openFolder());
            mGrid.addItem(overflow, "folder:overflow", overflowPlacement);
        }
        saveArrangedPlacements(storedPlacements, entries, arranged);
    }

    void refreshSettings(final MagicDeskSettings.Values settings) {
        if (settings != null) {
            mItemActivation.setSingleClick(
                    settings.openFilesWithSingleClick);
        }
    }

    boolean isDesktopShortcut(final AppItem app) {
        return findDefaultApplicationShortcut(app) != null;
    }

    void toggleDesktopShortcut(final AppItem app) {
        if (app == null) {
            return;
        }
        final DesktopFile existing = findDefaultApplicationShortcut(app);
        if (existing != null) {
            deleteApplicationShortcut(existing, app.label);
            return;
        }
        final Intent intent = app.launchTarget.resolve(
                mActivity.getPackageManager());
        if (intent == null) {
            mActivity.setErrorStatus(
                    "APP-LAUNCH-002",
                    mActivity.getString(
                            R.string.status_launch_failed,
                            "no launcher activity"));
            return;
        }
        createApplicationShortcut(
                app,
                app.label,
                intent,
                true);
    }

    void addDesktopShortcut(
            final AppItem app,
            final AppShortcutAction action) {
        if (app == null || action == null) {
            return;
        }
        storeApplicationShortcut(new DesktopApplicationShortcut(
                action.label,
                app.packageName,
                "",
                app.launchTarget,
                "",
                DesktopLaunchMode.AUTO,
                false,
                DesktopExecBackend.SHELL,
                false,
                "",
                DesktopMimeTypes.empty(),
                action.id).withApplication(app.identity));
    }

    private DesktopFile findDefaultApplicationShortcut(final AppItem app) {
        if (app == null) {
            return null;
        }
        for (final DesktopFile file : mFiles) {
            final DesktopApplicationShortcut shortcut =
                    file.applicationShortcut();
            if (shortcut != null
                    && (shortcut.application == null
                            || app.identity.equals(shortcut.application))
                    && app.launchTarget.equals(shortcut.launchTarget)
                    && shortcut.defaultLaunch) {
                return file;
            }
        }
        return null;
    }

    private void createApplicationShortcut(
            final AppItem app,
            final String name,
            final Intent intent,
            final boolean defaultLaunch) {
        if (app == null || intent == null) {
            return;
        }
        final String intentUri = intent.toUri(Intent.URI_INTENT_SCHEME);
        final DesktopApplicationShortcut shortcut = new DesktopApplicationShortcut(
                name,
                app.packageName,
                DesktopEntryFile.applicationExec(intentUri),
                app.launchTarget,
                intentUri,
                DesktopLaunchMode.AUTO,
                defaultLaunch,
                DesktopExecBackend.SHELL,
                false);
        storeApplicationShortcut(shortcut.withApplication(app.identity));
    }

    private void storeApplicationShortcut(
            final DesktopApplicationShortcut shortcut) {
        mFolder.createApplicationShortcut(shortcut, created ->
                mActivity.setStatus(mActivity.getString(
                        R.string.status_desktop_shortcut_added,
                        shortcut.name)));
    }

    private void deleteApplicationShortcut(
            final DesktopFile file,
            final String label) {
        final String itemId = fileItemId(file.relativePath);
        mFolder.delete(file, () -> {
            DesktopLayoutStore.remove(itemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_shortcut_removed,
                    label));
        });
    }

    void openFolder() {
        openDirectory(null);
    }

    void refreshFolder(final boolean force) {
        final int capacity = mGrid == null
                ? 0 : mGrid.getColumnCount() * mGrid.getRowCount();
        mFolder.refresh(force, capacity);
    }

    void addWidget() {
        mWidgets.addWidget();
    }

    boolean hasWidgets(final String packageName) {
        return mWidgets.hasWidgets(packageName);
    }

    void addWidgets(final String packageName) {
        mWidgets.addWidgets(packageName);
    }

    void configureWidget(final int appWidgetId) {
        mWidgets.configure(appWidgetId);
    }

    void removeWidget(final int appWidgetId) {
        if (!mWidgets.owns(appWidgetId)) return;
        final String itemId = widgetItemId(appWidgetId);
        DesktopLayoutStore.remove(itemId);
        mWidgets.remove(appWidgetId);
    }

    void resizeWidget(
            final int appWidgetId,
            final int columnDelta,
            final int rowDelta) {
        if (!mWidgets.owns(appWidgetId)) return;
        if (mGrid == null) {
            return;
        }
        final String itemId = widgetItemId(appWidgetId);
        final Map<String, GlobalDesktopPlacement> storedPlacements =
                DesktopLayoutStore.snapshot();
        final GlobalDesktopPlacement stored =
                storedPlacements.get(itemId);
        DesktopPlacement placement = stored == null
                ? mRenderedPlacements.get(itemId)
                : stored.resolve(
                        mGrid.getColumnCount(), mGrid.getRowCount());
        if (placement == null) {
            placement = new DesktopPlacement(0, 0, 1, 1);
        }
        final int columns = mGrid.getColumnCount();
        final int rows = mGrid.getRowCount();
        int minimumColumns = 1;
        int minimumRows = 1;
        int maximumColumns = columns;
        int maximumRows = rows;
        for (final DesktopWidgetController.WidgetEntry widget
                : mWidgets.widgets()) {
            if (widget.appWidgetId != appWidgetId) {
                continue;
            }
            minimumColumns = Math.min(columns, initialSpan(
                    widget.info.minResizeWidth,
                    mGrid.getCellWidth()));
            minimumRows = Math.min(rows, initialSpan(
                    widget.info.minResizeHeight,
                    mGrid.getCellHeight()));
            if (widget.info.maxResizeWidth > 0) {
                maximumColumns = Math.min(
                        columns,
                        initialSpan(
                                widget.info.maxResizeWidth,
                                mGrid.getCellWidth()));
            }
            if (widget.info.maxResizeHeight > 0) {
                maximumRows = Math.min(
                        rows,
                        initialSpan(
                                widget.info.maxResizeHeight,
                                mGrid.getCellHeight()));
            }
            break;
        }
        final DesktopPlacement resized = placement.withSpan(
                Math.max(minimumColumns, Math.min(
                        maximumColumns,
                        placement.columnSpan + columnDelta)),
                Math.max(minimumRows, Math.min(
                        maximumRows,
                        placement.rowSpan + rowDelta)));
        final GlobalDesktopPlacement global =
                GlobalDesktopPlacement.from(resized, columns, rows);
        DesktopLayoutStore.update(
                placements -> placements.put(itemId, global));
        render(mApps);
    }

    void beginWidgetMove(final int appWidgetId) {
        if (!mWidgets.owns(appWidgetId)) return;
        mEditingWidgetId = appWidgetId;
        render(mApps);
    }

    void cancelEditMode() {
        if (mEditingWidgetId < 0) {
            return;
        }
        mEditingWidgetId = -1;
        render(mApps);
    }

    List<DesktopApplicationRepository.Entry> desktopApplications() {
        return DesktopApplicationRepository.fromDesktopFiles(mFiles);
    }

    void openFile(final DesktopFile file) {
        final DesktopFolderShortcut folderShortcut = file.folderShortcut();
        if (folderShortcut != null) {
            openFolderShortcut(folderShortcut);
            return;
        }
        final DesktopApplicationShortcut applicationShortcut =
                file.applicationShortcut();
        if (applicationShortcut != null) {
            openApplicationShortcut(
                    applicationShortcut,
                    desktopAbsolutePath(file),
                    DesktopLaunchArguments.empty());
            return;
        }
        final DesktopWebShortcut webShortcut = file.webShortcut();
        if (webShortcut != null) {
            openWebShortcut(webShortcut);
            return;
        }
        if (file.directory) {
            openDirectory(file.relativePath);
            return;
        }
        openFile(file, false);
    }

    void openFileWith(final DesktopFile file) {
        if (file == null || file.directory) {
            return;
        }
        openFile(file, true);
    }

    private void openFile(
            final DesktopFile file, final boolean alwaysAsk) {
        mActivity.hideAllPanels();
        final AndroidContentPayload content = AndroidContentPayload.uris(
                file.name,
                List.of(new AndroidContentPayload.UriItem(
                        file.uri,
                        file.mimeType == null ? "*/*" : file.mimeType)),
                List.of(),
                AndroidContentPayload.Origin.APPLICATION);
        final Intent intent = AndroidContentIntentAdapter.open(content)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        final DesktopLaunchArguments arguments =
                DesktopLaunchArguments.files(
                        List.of(desktopAbsolutePath(file)));
        mOpenWith.open(
                intent,
                arguments,
                alwaysAsk,
                new FileOpenWithController.Launcher() {
                    @Override
                    public void launchAndroid(final Intent selected) {
                        launchFileIntent(selected, file);
                    }

                    @Override
                    public void launchDesktop(
                            final DesktopApplicationShortcut shortcut,
                            final DesktopLaunchArguments selectedArguments,
                            final String desktopFilePath) {
                        openApplicationShortcut(
                                shortcut,
                                desktopFilePath,
                                selectedArguments);
                    }

                    @Override
                    public void noHandler() {
                        mActivity.setErrorStatus(
                                "FILES-003",
                                mActivity.getString(R.string.status_desktop_file_failed, file.name),
                                "mime=" + file.mimeType + " no handler", null);
                    }

                    @Override
                    public void failed(final Throwable error) {
                        showFileActionError(file, ShellAccess.usefulMessage(error), error);
                    }
                });
    }

    private void launchFileIntent(
            final Intent intent, final DesktopFile file) {
        final int displayId = mActivity.getCurrentDisplayId();
        mContentWorker.execute(() -> {
            try {
                final AndroidIntegrationRequest request =
                        AndroidIntegrationRequest.activity(
                                intent,
                                file.name,
                                DesktopLaunchPresentation.automatic(),
                                false,
                                "",
                                false);
                final DesktopAutomationResult result =
                        new AndroidIntegrationGateway(mActivity).execute(
                                AndroidDesktopAction.request(
                                        "open-file", "desktop", request),
                                displayId);
                if (!result.success) {
                    showFileActionError(file, result.message, null);
                }
            } catch (Exception error) {
                showFileActionError(
                        file, ShellAccess.usefulMessage(error), error);
            }
        });
    }

    void shareFile(final DesktopFile file) {
        if (file == null || file.directory) {
            return;
        }
        mActivity.hideAllPanels();
        final AndroidContentPayload content = AndroidContentPayload.uris(
                file.name,
                List.of(new AndroidContentPayload.UriItem(
                        file.uri,
                        file.mimeType == null ? "*/*" : file.mimeType)),
                List.of(),
                AndroidContentPayload.Origin.APPLICATION);
        final int displayId = mActivity.getCurrentDisplayId();
        mContentWorker.execute(() -> {
            try {
                final DesktopAutomationResult result =
                        new AndroidIntegrationGateway(mActivity).shareContent(
                                content, displayId);
                if (!result.success) {
                    showFileActionError(file, result.message, null);
                }
            } catch (Exception error) {
                showFileActionError(
                        file, ShellAccess.usefulMessage(error), error);
            }
        });
    }

    private void showFileActionError(
            final DesktopFile file,
            final String detail,
            final Throwable error) {
        mActivity.runOnUiThread(() -> {
            Log.w(TAG, "Cannot open desktop file " + file.uri, error);
            mActivity.setErrorStatus(
                    "FILES-003",
                    mActivity.getString(
                            R.string.status_desktop_file_failed,
                            file.name),
                    detail,
                    error);
        });
    }

    void copyFiles(final List<DesktopFile> files, final boolean move) {
        final List<String> paths = new ArrayList<>();
        for (final DesktopFile file : files) {
            paths.add(desktopAbsolutePath(file));
        }
        FileClipboardInterop.storeDesktopFiles(
                mActivity,
                files,
                paths,
                move
                        ? FileOperationClipboard.Mode.MOVE
                        : FileOperationClipboard.Mode.COPY);
        mActivity.setStatus(files.size() == 1
                ? mActivity.getString(move
                        ? R.string.status_desktop_item_cut
                        : R.string.status_desktop_item_copied)
                : mActivity.getResources().getQuantityString(move
                        ? R.plurals.status_desktop_items_cut
                        : R.plurals.status_desktop_items_copied,
                        files.size(), Integer.valueOf(files.size())));
    }

    /**
     * Files a context-menu command applies to: the whole selection when the
     * clicked item belongs to it, otherwise only the clicked item.
     */
    List<DesktopFile> operationTargets(final DesktopFile file) {
        final List<DesktopFile> selection = selectedFiles();
        return selection.size() > 1
                && mSelectedFileItemIds.contains(fileItemId(file.relativePath))
                ? selection : List.of(file);
    }

    void pasteFiles() {
        final FileClipboardInterop.PasteSource source =
                FileClipboardInterop.resolvePaste(mActivity);
        if (source.kind == FileClipboardInterop.PasteKind.INTERNAL_PATHS) {
            mFolder.transferPaths(
                    source.files.paths,
                    !source.files.isMove(),
                    source.files.isMove()
                            ? source.files.generation : -1L);
        } else if (source.kind
                == FileClipboardInterop.PasteKind.ANDROID_CONTENT) {
            mFolder.importContent(source.content, null);
        }
    }

    void openClipboardContent() {
        executeClipboardAction(true);
    }

    void shareClipboardContent() {
        executeClipboardAction(false);
    }

    private void executeClipboardAction(final boolean open) {
        mActivity.hideAllPanels();
        final int displayId = mActivity.getCurrentDisplayId();
        mContentWorker.execute(() -> {
            final DesktopAutomationResult result = open
                    ? mContentActions.openClipboard(displayId)
                    : mContentActions.shareClipboard(displayId);
            mActivity.runOnUiThread(() -> {
                if (mActivity.isActivityUnavailable() || result.success) {
                    return;
                }
                mActivity.setErrorStatus(
                        result.errorCode.isEmpty()
                                ? "CLIPBOARD-002" : result.errorCode,
                        result.message);
            });
        });
    }

    boolean handleKeyboardCommand(final FileKeyboardCommand command) {
        final List<DesktopFile> selection = selectedFiles();
        final DesktopFile selected = selection.size() == 1 ? selection.get(0) : null;
        switch (command) {
            case SELECT_ALL:
                final Set<String> all = new LinkedHashSet<>();
                for (final DesktopFile file : mFiles) {
                    if (mRenderedPlacements.containsKey(fileItemId(file.relativePath))) {
                        all.add(fileItemId(file.relativePath));
                    }
                }
                setSelection(all);
                return true;
            case COPY:
                if (!selection.isEmpty()) {
                    copyFiles(selection, false);
                    return true;
                }
                break;
            case CUT:
                if (!selection.isEmpty()) {
                    copyFiles(selection, true);
                    return true;
                }
                break;
            case PASTE:
                if (FileClipboardInterop.canPaste(mActivity)) {
                    pasteFiles();
                    return true;
                }
                break;
            case OPEN:
                if (!selection.isEmpty()) {
                    for (final DesktopFile file : selection) {
                        openFile(file);
                    }
                    return true;
                }
                break;
            case RENAME:
                if (selected != null) {
                    mActivity.renameDesktopFile(selected);
                    return true;
                }
                break;
            case DELETE:
                if (!selection.isEmpty()) {
                    mActivity.confirmDeleteDesktopFiles(selection);
                    return true;
                }
                break;
            case CLEAR_SELECTION:
                if (!selection.isEmpty()) {
                    clearFileSelection();
                    return true;
                }
                break;
            case REFRESH:
                mFolder.refresh(true, Math.max(1, mLastCapacity));
                return true;
            default:
                break;
        }
        return false;
    }

    void copyFilePath(final DesktopFile file) {
        final DesktopFolderShortcut folderShortcut = file.folderShortcut();
        final DesktopWebShortcut webShortcut = file.webShortcut();
        final String target = folderShortcut != null
                ? folderShortcut.targetPath
                : webShortcut != null
                        ? webShortcut.url
                        : desktopAbsolutePath(file);
        final AndroidClipboardGateway.OperationResult copied =
                AndroidClipboardGateway.get(mActivity).writeText(
                        file.displayName(), target, false);
        if (copied.successful) {
            mActivity.setStatus(R.string.file_manager_path_copied);
        }
    }

    void showFileProperties(final DesktopFile file) {
        mFolder.inspect(file, info -> {
            if (file.folderShortcut() == null) {
                mActivity.showDesktopFileProperties(info);
            } else {
                mActivity.showDesktopFolderShortcutProperties(
                        info, file.folderShortcut());
            }
        });
    }

    void installApk(final DesktopFile file) {
        mActivity.setStatus(R.string.file_manager_installing);
        mFolder.installApk(file);
    }

    void runScript(final DesktopFile file) {
        final Intent intent = CommandConsoleActivity.createScriptIntent(
                mActivity,
                desktopAbsolutePath(file));
        openBuiltInWindow(
                intent,
                CommandConsoleActivity.launchTarget(),
                R.string.console_title,
                desktopAbsolutePath(file));
    }

    void setWallpaper(final DesktopFile file) {
        mActivity.setStatus(R.string.file_manager_setting_wallpaper);
        mFolder.setWallpaper(file);
    }

    private void openDirectory(final String relativePath) {
        final String path = relativePath == null || relativePath.length() == 0
                ? ShellDesktopDirectory.ABSOLUTE_PATH
                : ShellDesktopDirectory.ABSOLUTE_PATH + "/" + relativePath;
        openBuiltInWindow(
                FileManagerActivity.createIntent(mActivity, path),
                BuiltInDesktopAppCatalog.filesTarget(),
                R.string.file_manager_title,
                path);
    }

    private void openFolderShortcut(
            final DesktopFolderShortcut shortcut) {
        if (!shortcut.available) {
            mActivity.setStatus(R.string.desktop_shortcut_unavailable);
        }
        openBuiltInWindow(
                FileManagerActivity.createIntent(
                        mActivity, shortcut.targetPath),
                BuiltInDesktopAppCatalog.filesTarget(),
                R.string.file_manager_title,
                shortcut.targetPath);
    }

    private void openApplicationShortcut(
            final DesktopApplicationShortcut shortcut,
            final String desktopFilePath,
            final DesktopLaunchArguments arguments) {
        if (!mActivity.launchDesktopShortcut(
                shortcut, arguments, desktopFilePath)) {
            mActivity.setStatus(R.string.desktop_shortcut_unavailable);
        }
    }

    private void openWebShortcut(final DesktopWebShortcut shortcut) {
        if (!mActivity.launchDesktopWebShortcut(shortcut)) {
            mActivity.setStatus(R.string.desktop_shortcut_unavailable);
        }
    }

    private void openBuiltInWindow(
            final Intent intent,
            final AppLaunchTarget target,
            final int titleResId,
            final String detail) {
        mActivity.hideAllPanels();
        try {
            mActivity.launchInternalWindow(
                    intent,
                    target,
                    mActivity.getString(titleResId));
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot open desktop window at " + detail, error);
            mActivity.setErrorStatus(
                    "FILES-003",
                    mActivity.getString(
                            R.string.status_desktop_folder_open_failed),
                    "path=" + detail,
                    error);
        }
    }

    void createFile(final String name, final boolean directory) {
        mFolder.create(name, directory, created -> mActivity.setStatus(
                mActivity.getString(
                        R.string.status_desktop_entry_created,
                        created.name)));
    }

    void renameFile(final DesktopFile file, final String newName) {
        final String previousItemId = fileItemId(file.relativePath);
        if (mSelectedFileItemIds.remove(previousItemId)) {
            mItemActivation.reset();
        }
        mFolder.rename(file, newName, renamed -> {
            final String newItemId = fileItemId(renamed.relativePath);
            DesktopLayoutStore.rename(previousItemId, newItemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_entry_renamed,
                    renamed.name));
        });
    }

    void deleteFile(final DesktopFile file) {
        final String itemId = fileItemId(file.relativePath);
        if (mSelectedFileItemIds.remove(itemId)) {
            mItemActivation.reset();
        }
        mFolder.delete(file, () -> {
            DesktopLayoutStore.remove(itemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_entry_deleted,
                    file.name));
        });
    }

    void resetDisplayProfile() {
        render(mApps);
    }

    private List<Entry> collectEntries(
            final Map<String, GlobalDesktopPlacement> storedPlacements) {
        final List<Entry> entries = new ArrayList<>();
        for (final DesktopWidgetController.WidgetEntry widget
                : mWidgets.widgets()) {
            final GlobalDesktopPlacement stored =
                    storedPlacements.get(widget.itemId());
            final int columnSpan = stored == null
                    ? initialSpan(widget.info.minWidth, mGrid.getCellWidth())
                    : stored.columnSpan;
            final int rowSpan = stored == null
                    ? initialSpan(widget.info.minHeight, mGrid.getCellHeight())
                    : stored.rowSpan;
            entries.add(Entry.widget(
                    widget.itemId(), widget, columnSpan, rowSpan));
        }
        for (final DesktopFile file : mFiles) {
            final DesktopApplicationShortcut shortcut =
                    file.applicationShortcut();
            final AppItem app = shortcut == null
                    || shortcut.launchTarget == null
                    || (shortcut.application != null && shortcut.application.profileSerialNumber
                            != mActivity.appProfile().serialNumber)
                    ? null
                    : mActivity.findOrLoadApp(
                            mApps, shortcut.application == null
                                    ? mActivity.appProfile().application(shortcut.launchTarget.packageName)
                                    : shortcut.application,
                            shortcut.launchTarget);
            entries.add(app == null
                    ? Entry.file(fileItemId(file.relativePath), file)
                    : Entry.app(
                            fileItemId(file.relativePath), app, file));
        }
        return entries;
    }

    private void addRequests(
            final List<Entry> entries,
            final Map<String, GlobalDesktopPlacement> storedPlacements,
            final List<DesktopPlacementEngine.Request> requests,
            final boolean placed) {
        for (int priority = 0; priority <= 2; priority++) {
            for (final Entry entry : entries) {
                if (entry.placementPriority() != priority) {
                    continue;
                }
                final GlobalDesktopPlacement stored =
                        storedPlacements.get(entry.itemId);
                if ((stored != null) != placed) {
                    continue;
                }
                final DesktopPlacement preferred = stored == null
                        ? null : stored.resolve(
                                mGrid.getColumnCount(),
                                mGrid.getRowCount());
                requests.add(new DesktopPlacementEngine.Request(
                        entry.itemId,
                        entry.columnSpan,
                        entry.rowSpan,
                        preferred));
            }
        }
    }

    private void addEntryView(
            final Entry entry,
            final DesktopPlacement placement) {
        final View view;
        if (entry.app != null) {
            final DesktopApplicationShortcut shortcut =
                    entry.file.applicationShortcut();
            view = mViews.app(entry.app, shortcut.name);
            view.setActivated(mSelectedFileItemIds.contains(entry.itemId));
            view.setOnClickListener(target -> {
                mActivity.hideAllPanels();
                mActivity.launchDesktopShortcut(
                        shortcut,
                        DesktopLaunchArguments.empty(),
                        desktopAbsolutePath(entry.file));
            });
            mActivity.registerDraggableDesktopAppContextTarget(
                    view, entry.app, entry.file);
            enableDrag(view, entry.itemId, entry.file, true);
        } else if (entry.file != null) {
            view = mViews.file(entry.file);
            view.setActivated(mSelectedFileItemIds.contains(entry.itemId));
            view.setOnClickListener(target ->
                    activateFile(entry.itemId, entry.file));
            mActivity.registerDraggableFileContextTarget(view, entry.file);
            enableDrag(view, entry.itemId, entry.file, true);
        } else {
            final AppWidgetHostView widgetView =
                    mWidgets.createView(entry.widget);
            mWidgets.updateSize(
                    widgetView,
                    placement,
                    mGrid.getCellWidth(),
                    mGrid.getCellHeight());
            final FrameLayout frame = new DesktopWidgetContainer(mActivity);
            frame.addView(widgetView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            if (mEditingWidgetId == entry.widget.appWidgetId) {
                final View moveLayer = new View(mActivity);
                moveLayer.setClickable(true);
                moveLayer.setBackground(mUi.rounded(
                        0x11000000,
                        mUi.dp(4),
                        DesktopUiFactory.COLOR_ACCENT));
                enableDrag(moveLayer, entry.itemId, null, false);
                frame.addView(moveLayer, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            mActivity.registerWidgetContextTarget(
                    frame,
                    entry.widget.appWidgetId,
                    entry.widget.info.label,
                    entry.widget.info.configure != null,
                    entry.widget.info.resizeMode);
            view = frame;
        }
        mGrid.addItem(view, entry.itemId, placement);
        // addItem installs the default reorder listener, so specialized drop
        // targets must be attached after the view joins the grid.
        if (entry.file != null && entry.file.folderShortcut() != null) {
            enableFolderShortcutDrop(
                    view, entry.itemId, entry.file.folderShortcut());
        } else if (entry.file != null
                && entry.file.applicationShortcut() != null) {
            enableApplicationShortcutDrop(
                    view,
                    entry.itemId,
                    entry.file,
                    entry.file.applicationShortcut());
        }
    }

    private void enableFolderShortcutDrop(
            final View view,
            final String itemId,
            final DesktopFolderShortcut shortcut) {
        final float restingAlpha = view.getAlpha();
        view.setOnDragListener((target, event) -> {
            final FileDragPayload payload = FileDragPayload.from(event);
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (!shortcut.available
                            || event.getLocalState()
                                    instanceof DesktopGridLayout.DragToken
                            || (payload != null
                                    && itemId.equals(payload.desktopItemId))) {
                        return false;
                    }
                    return payload != null || event.getClipDescription() != null;
                case DragEvent.ACTION_DRAG_ENTERED:
                    target.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    target.setAlpha(restingAlpha);
                    return true;
                case DragEvent.ACTION_DROP:
                    target.setAlpha(restingAlpha);
                    return importDroppedFiles(
                            event,
                            shortcut.targetPath,
                            shortcut.name);
                default:
                    return true;
            }
        });
    }

    private void enableApplicationShortcutDrop(
            final View view,
            final String itemId,
            final DesktopFile file,
            final DesktopApplicationShortcut shortcut) {
        final boolean acceptsExec = shortcut != null
                && shortcut.hasExecLaunch()
                && DesktopExecTemplate.acceptsArguments(shortcut.exec);
        final boolean acceptsAndroid = shortcut != null
                && shortcut.launchTarget != null;
        if (!acceptsExec && !acceptsAndroid) {
            return;
        }
        final float restingAlpha = view.getAlpha();
        view.setOnDragListener((target, event) -> {
            final FileDragPayload payload = FileDragPayload.from(event);
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (event.getLocalState()
                                    instanceof DesktopGridLayout.DragToken
                            || (payload != null
                            && itemId.equals(payload.desktopItemId))
                            || event.getClipDescription() == null) {
                        return false;
                    }
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    target.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    target.setAlpha(restingAlpha);
                    return true;
                case DragEvent.ACTION_DROP:
                    target.setAlpha(restingAlpha);
                    if (acceptsExec) {
                        final DesktopLaunchArguments arguments =
                                DesktopDragLaunchArguments.from(event);
                        if (arguments.isEmpty()) {
                            return false;
                        }
                        openApplicationShortcut(
                                shortcut,
                                desktopAbsolutePath(file),
                                arguments);
                        return true;
                    }
                    final AndroidContentPayload content =
                            AndroidContentPayload.fromClipData(
                                    event.getClipData(),
                                    AndroidContentPayload.Origin.DRAG);
                    if (content.isEmpty()) {
                        return false;
                    }
                    final DragAndDropPermissions permissions =
                            mActivity.requestDragAndDropPermissions(event);
                    deliverDroppedContent(
                            shortcut.launchTarget, content, permissions);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void deliverDroppedContent(
            final AppLaunchTarget target,
            final AndroidContentPayload content,
            final DragAndDropPermissions permissions) {
        final int displayId = mActivity.getCurrentDisplayId();
        AndroidDesktopActionDispatcher.deliverContent(
                mContentRequests, mActivity, content, target,
                DesktopLaunchPresentation.automatic(), displayId,
                () -> {
                    if (permissions != null) {
                        permissions.release();
                    }
                }, result -> {
                    if (!result.success) {
                        mActivity.setErrorStatus(
                            "CONTENT-DROP-001",
                            result.message,
                            "package=" + target.packageName,
                            null);
                    }
                });
    }

    private void activateFile(
            final String itemId, final DesktopFile file) {
        if (mItemActivation.shouldActivate(
                itemId, SystemClock.uptimeMillis())) {
            mSelectedFileItemIds.clear();
            applySelectionState();
            openFile(file);
            return;
        }
        if (mSelectedFileItemIds.size() != 1 || !mSelectedFileItemIds.contains(itemId)) {
            mSelectedFileItemIds.clear();
            mSelectedFileItemIds.add(itemId);
            applySelectionState();
        }
    }

    void clearFileSelection() {
        mItemActivation.reset();
        if (!mSelectedFileItemIds.isEmpty()) {
            mSelectedFileItemIds.clear();
            applySelectionState();
        }
    }

    /** Selected files in selection order. */
    private List<DesktopFile> selectedFiles() {
        final List<DesktopFile> selected = new ArrayList<>();
        for (final String itemId : mSelectedFileItemIds) {
            for (final DesktopFile file : mFiles) {
                if (itemId.equals(fileItemId(file.relativePath))) {
                    selected.add(file);
                    break;
                }
            }
        }
        return selected;
    }

    private void enableDrag(
            final View view,
            final String itemId,
            final DesktopFile file,
            final boolean deferContextMenu) {
        new DeferredContextDragGesture(
                view,
                false,
                deferContextMenu,
                new DeferredContextDragGesture.Listener() {
                    @Override
                    public boolean onStartDrag(
                            final View target, final MotionEvent event) {
                mItemActivation.reset();
                // Dragging a selected item carries the whole selection.
                final List<DesktopFile> dragged = file == null
                        ? List.of() : operationTargets(file);
                final List<String> paths = new ArrayList<>();
                for (final DesktopFile draggedFile : dragged) {
                    paths.add(desktopAbsolutePath(draggedFile));
                }
                final FileDragPayload filePayload = file == null
                        ? null : new FileDragPayload(
                                paths,
                                itemId,
                                (event.getMetaState()
                                        & KeyEvent.META_CTRL_ON) != 0);
                final List<AndroidContentPayload.UriItem> shareable =
                        shareableItems(dragged);
                final ClipData data = dragData(
                        itemId, dragged, filePayload, shareable);
                final int flags = file == null
                        ? 0 : FileDragPayload.dragFlags(!shareable.isEmpty());
                return target.startDragAndDrop(
                        data,
                        new View.DragShadowBuilder(target),
                        filePayload == null
                                ? new DesktopGridLayout.DragToken(itemId)
                                : filePayload,
                        flags);
                    }

                    @Override
                    public void onShowContextMenu(final View target) {
                        mActivity.showRegisteredContextMenu(target);
                    }

                    @Override
                    public boolean onTap(
                            final View target, final MotionEvent event) {
                        return target.performClick();
                    }
                });
    }

    private ClipData dragData(
            final String itemId,
            final List<DesktopFile> files,
            final FileDragPayload payload,
            final List<AndroidContentPayload.UriItem> shareable) {
        if (files.isEmpty()) {
            return AndroidContentPayload.text(
                    mActivity.getString(R.string.desktop_drag_label),
                    itemId,
                    false,
                    AndroidContentPayload.Origin.DRAG).toClipData();
        }
        return payload.clipData(files.size() == 1 && !shareable.isEmpty()
                ? files.get(0).name
                : mActivity.getString(R.string.desktop_drag_label), shareable);
    }

    /**
     * Content URIs other applications may read, or none: Android receives a
     * drag only when every item is a shareable file, never a partial set.
     */
    private static List<AndroidContentPayload.UriItem> shareableItems(
            final List<DesktopFile> files) {
        if (files.isEmpty() || files.size() > AndroidContentPayload.MAX_URI_ITEMS) {
            return List.of();
        }
        final List<AndroidContentPayload.UriItem> items = new ArrayList<>();
        for (final DesktopFile file : files) {
            if (file.directory) {
                return List.of();
            }
            items.add(new AndroidContentPayload.UriItem(file.uri, file.mimeType));
        }
        return items;
    }

    private boolean importDroppedFiles(final DragEvent event) {
        return importDroppedFiles(
                event, ShellDesktopDirectory.ABSOLUTE_PATH, null);
    }

    private boolean importDroppedFiles(
            final DragEvent event,
            final String destination,
            final String destinationLabel) {
        final FileDragPayload payload = FileDragPayload.from(event);
        if (payload != null) {
            final List<String> paths = payload.pathsForDestination(
                    destination);
            if (!paths.isEmpty()) {
                mFolder.transferPaths(
                        paths,
                        payload.copy,
                        destination,
                        destinationLabel);
            }
            return true;
        }
        final AndroidContentPayload content =
                AndroidContentPayload.fromClipData(
                        event.getClipData(),
                        AndroidContentPayload.Origin.DRAG);
        if (content.isEmpty()) {
            return false;
        }
        DragAndDropPermissions permissions = null;
        try {
            permissions = mActivity.requestDragAndDropPermissions(event);
        } catch (RuntimeException error) {
            Log.d(TAG, "Drag URI permission was not granted", error);
        }
        mFolder.importContent(
                content,
                permissions,
                destination,
                destinationLabel);
        return true;
    }

    private static String desktopAbsolutePath(final DesktopFile file) {
        return ShellDesktopDirectory.ABSOLUTE_PATH
                + "/" + file.relativePath;
    }

    private void moveItem(
            final String itemId,
            final int column,
            final int row) {
        final Map<String, GlobalDesktopPlacement> storedPlacements =
                DesktopLayoutStore.snapshot();
        DesktopPlacement current = mRenderedPlacements.get(itemId);
        if (current == null) {
            final GlobalDesktopPlacement stored =
                    storedPlacements.get(itemId);
            current = stored == null ? null : stored.resolve(
                    mGrid.getColumnCount(), mGrid.getRowCount());
        }
        if (current == null) {
            current = new DesktopPlacement(column, row, 1, 1);
        }
        final List<DesktopPlacement> occupied = new ArrayList<>();
        for (final Map.Entry<String, DesktopPlacement> entry
                : mRenderedPlacements.entrySet()) {
            if (!itemId.equals(entry.getKey())) {
                occupied.add(entry.getValue());
            }
        }
        final DesktopPlacement placement =
                DesktopPlacementEngine.findNearestFree(
                        occupied,
                        mGrid.getColumnCount(),
                        mGrid.getRowCount(),
                        current.columnSpan,
                        current.rowSpan,
                        column,
                        row);
        if (placement == null) {
            return;
        }
        final GlobalDesktopPlacement global = GlobalDesktopPlacement.from(
                placement,
                mGrid.getColumnCount(),
                mGrid.getRowCount());
        DesktopLayoutStore.update(
                placements -> placements.put(itemId, global));
        mEditingWidgetId = -1;
        render(mApps);
    }

    private void saveArrangedPlacements(
            final Map<String, GlobalDesktopPlacement> storedPlacements,
            final List<Entry> entries,
            final Map<String, DesktopPlacement> arranged) {
        boolean hasNewPlacement = false;
        for (final Entry entry : entries) {
            if (!storedPlacements.containsKey(entry.itemId)
                    && arranged.containsKey(entry.itemId)) {
                hasNewPlacement = true;
                break;
            }
        }
        if (!hasNewPlacement) {
            return;
        }
        DesktopLayoutStore.update(placements -> {
            for (final Entry entry : entries) {
                if (storedPlacements.containsKey(entry.itemId)) {
                    continue;
                }
                final DesktopPlacement placement =
                        arranged.get(entry.itemId);
                final GlobalDesktopPlacement global =
                        GlobalDesktopPlacement.from(
                                placement,
                                mGrid.getColumnCount(),
                                mGrid.getRowCount());
                if (global != null) {
                    placements.put(entry.itemId, global);
                }
            }
        });
    }

    private void onFilesChanged(
            final List<DesktopFile> files,
            final boolean successfulRead) {
        mFiles = new ArrayList<>(files);
        if (successfulRead) {
            final Set<String> liveFiles = new HashSet<>();
            for (final DesktopFile file : files) {
                liveFiles.add(fileItemId(file.relativePath));
            }
            if (mSelectedFileItemIds.retainAll(liveFiles)) {
                mItemActivation.reset();
            }
            final Map<String, GlobalDesktopPlacement> storedPlacements =
                    DesktopLayoutStore.snapshot();
            boolean hasStaleFiles = false;
            for (final String itemId : storedPlacements.keySet()) {
                if (itemId.startsWith(FILE_PREFIX)
                        && !liveFiles.contains(itemId)) {
                    hasStaleFiles = true;
                    break;
                }
            }
            if (hasStaleFiles) {
                DesktopLayoutStore.update(placements ->
                        placements.entrySet().removeIf(entry ->
                                entry.getKey().startsWith(FILE_PREFIX)
                                        && !liveFiles.contains(
                                                entry.getKey())));
            }
        }
        render(mApps);
    }

    private void onWidgetsChanged() {
        render(mApps);
    }

    private static String fileItemId(final String relativePath) {
        return FILE_PREFIX + relativePath;
    }

    private static String widgetItemId(final int appWidgetId) {
        return "widget:" + appWidgetId;
    }

    private static int initialSpan(
            final int minimumPixels,
            final int cellPixels) {
        return Math.max(
                1,
                (Math.max(1, minimumPixels) + Math.max(1, cellPixels) - 1)
                        / Math.max(1, cellPixels));
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }

    private static final class Entry {
        final String itemId;
        final AppItem app;
        final DesktopFile file;
        final DesktopWidgetController.WidgetEntry widget;
        final int columnSpan;
        final int rowSpan;

        private Entry(
                final String itemId,
                final AppItem app,
                final DesktopFile file,
                final DesktopWidgetController.WidgetEntry widget,
                final int columnSpan,
                final int rowSpan) {
            this.itemId = itemId;
            this.app = app;
            this.file = file;
            this.widget = widget;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }

        static Entry app(
                final String itemId,
                final AppItem app,
                final DesktopFile file) {
            return new Entry(itemId, app, file, null, 1, 1);
        }

        static Entry file(final String itemId, final DesktopFile file) {
            return new Entry(itemId, null, file, null, 1, 1);
        }

        static Entry widget(
                final String itemId,
                final DesktopWidgetController.WidgetEntry widget,
                final int columnSpan,
                final int rowSpan) {
            return new Entry(
                    itemId, null, null, widget, columnSpan, rowSpan);
        }

        int placementPriority() {
            if (widget != null) {
                return 0;
            }
            return app != null ? 1 : 2;
        }
    }
}
