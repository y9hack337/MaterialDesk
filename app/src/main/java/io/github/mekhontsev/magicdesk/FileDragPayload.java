package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.DragEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Process-local file drag data shared by the desktop and Files windows. */
final class FileDragPayload {
    static final String MIME_TYPE =
            "application/vnd.io.github.mekhontsev.magicdesk.files";
    /** Private drags carry their paths to other MagicDesk windows in this Intent. */
    private static final String ACTION_FILE_DRAG =
            "io.github.mekhontsev.magicdesk.action.FILE_DRAG";
    private static final String EXTRA_PATHS = "paths";
    private static final String EXTRA_COPY = "copy";

    final List<String> absolutePaths;
    final String desktopItemId;
    final boolean copy;

    FileDragPayload(
            final List<String> absolutePaths,
            final String desktopItemId,
            final boolean copy) {
        if (absolutePaths == null || absolutePaths.isEmpty()) {
            throw new IllegalArgumentException("missing dragged paths");
        }
        this.absolutePaths = Collections.unmodifiableList(
                new ArrayList<>(absolutePaths));
        this.desktopItemId = desktopItemId;
        this.copy = copy;
    }

    /**
     * The dragged MagicDesk files. Android delivers the local state only to
     * the window that started the drag; drops in other MagicDesk windows
     * rebuild the paths from the ClipData (available on ACTION_DROP).
     */
    static FileDragPayload from(final DragEvent event) {
        final Object state = event == null ? null : event.getLocalState();
        if (state instanceof FileDragPayload) {
            return (FileDragPayload) state;
        }
        if (event == null || event.getAction() != DragEvent.ACTION_DROP) {
            return null;
        }
        final ClipDescription description = event.getClipDescription();
        if (description == null || !description.hasMimeType(MIME_TYPE)) {
            return null;
        }
        return fromClipData(MagicDeskApplication.applicationContext(), event.getClipData());
    }

    private static FileDragPayload fromClipData(final Context context, final ClipData clip) {
        if (context == null || clip == null || clip.getItemCount() == 0) {
            return null;
        }
        final Intent intent = clip.getItemAt(0).getIntent();
        if (intent != null && ACTION_FILE_DRAG.equals(intent.getAction())) {
            final String[] paths = intent.getStringArrayExtra(EXTRA_PATHS);
            return paths == null || paths.length == 0 ? null
                    : new FileDragPayload(List.of(paths), null,
                            intent.getBooleanExtra(EXTRA_COPY, false));
        }
        // Shareable drags carry MagicDesk's own content URIs; map them back.
        final List<String> paths = new ArrayList<>(clip.getItemCount());
        for (int index = 0; index < clip.getItemCount(); index++) {
            final String path = localPath(context, clip.getItemAt(index).getUri());
            if (path == null) {
                return null;
            }
            paths.add(path);
        }
        return new FileDragPayload(paths, null, false);
    }

    private static String localPath(final Context context, final Uri uri) {
        if (uri == null) {
            return null;
        }
        try {
            if (DesktopFileUri.authority(context).equals(uri.getAuthority())) {
                return ShellDesktopDirectory.ABSOLUTE_PATH + "/"
                        + DesktopFileUri.parse(context, uri);
            }
            if (ShellFileGrantStore.authority(context).equals(uri.getAuthority())) {
                return ShellFileGrantStore.resolve(context, uri).info.absolutePath;
            }
        } catch (IllegalArgumentException expiredOrForeign) {
            return null;
        }
        return null;
    }

    ClipData clipData(
            final CharSequence label,
            final List<AndroidContentPayload.UriItem> shareableItems) {
        if (shareableItems != null && !shareableItems.isEmpty()) {
            if (shareableItems.size() > AndroidContentPayload.MAX_URI_ITEMS) {
                throw new IllegalArgumentException("too many dragged URIs");
            }
            if (shareableItems.size() != absolutePaths.size()) {
                throw new IllegalArgumentException("drag URIs must cover the entire selection");
            }
            for (final AndroidContentPayload.UriItem item : shareableItems) {
                if (item == null) {
                    throw new IllegalArgumentException("missing dragged URI");
                }
            }
        }
        if (shareableItems == null || shareableItems.isEmpty()) {
            // Private drags reach only MagicDesk windows (see dragFlags), so
            // the paths may travel in the clip for drops in other windows.
            final Intent intent = new Intent(ACTION_FILE_DRAG)
                    .putExtra(EXTRA_PATHS, absolutePaths.toArray(new String[0]))
                    .putExtra(EXTRA_COPY, copy);
            return new ClipData(new ClipDescription(
                    label == null || label.length() == 0 ? "MagicDesk files" : label,
                    new String[] {MIME_TYPE, ClipDescription.MIMETYPE_TEXT_INTENT}),
                    new ClipData.Item(intent));
        }
        return concreteTypesFirst(AndroidContentPayload.drag(
                label, shareableItems, MIME_TYPE).toClipData());
    }

    /**
     * Lists the files' own MIME types first. Some applications inspect only
     * the first declared type when deciding whether to accept a drop.
     */
    private static ClipData concreteTypesFirst(final ClipData clip) {
        final ClipDescription description = clip.getDescription();
        final List<String> concrete = new ArrayList<>();
        final List<String> transport = new ArrayList<>();
        for (int index = 0; index < description.getMimeTypeCount(); index++) {
            final String type = description.getMimeType(index);
            if (MIME_TYPE.equals(type)
                    || ClipDescription.MIMETYPE_TEXT_URILIST.equals(type)) {
                transport.add(type);
            } else {
                concrete.add(type);
            }
        }
        concrete.addAll(transport);
        final ClipData ordered = new ClipData(
                new ClipDescription(description.getLabel(), concrete.toArray(new String[0])),
                clip.getItemAt(0));
        for (int index = 1; index < clip.getItemCount(); index++) {
            ordered.addItem(clip.getItemAt(index));
        }
        return ordered;
    }

    static int dragFlags(final boolean shareableContent) {
        if (shareableContent) {
            return View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ;
        }
        // Android 14 cannot restrict cross-window drag to our UID. Keep private
        // payloads within their source window instead of exposing them globally.
        return android.os.Build.VERSION.SDK_INT >= 35
                ? View.DRAG_FLAG_GLOBAL_SAME_APPLICATION : 0;
    }

    List<String> pathsForDestination(final String destination) {
        if (copy) {
            return absolutePaths;
        }
        final String normalizedDestination =
                ShellFilePathPolicy.normalizeShellAbsolute(destination);
        final List<String> paths = new ArrayList<>(absolutePaths.size());
        for (final String path : absolutePaths) {
            if (!normalizedDestination.equals(
                    ShellFilePathPolicy.shellParent(path))) {
                paths.add(path);
            }
        }
        return paths;
    }
}
