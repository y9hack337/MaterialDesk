package io.github.mekhontsev.magicdesk;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DesktopItemViewFactory {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;

    DesktopItemViewFactory(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    View app(final AppItem app) {
        return app(app, app.label);
    }

    View app(final AppItem app, final String label) {
        final LinearLayout item = iconContainer();
        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        item.addView(icon, iconParams());
        addLabel(item, label);
        return item;
    }

    /** Selection is the view's activated state; see {@link #iconContainer()}. */
    View file(final DesktopFile file) {
        final LinearLayout item = iconContainer();
        final ImageView icon = new ImageView(mActivity);
        icon.setScaleType(file.thumbnail == null
                ? ImageView.ScaleType.CENTER_INSIDE
                : ImageView.ScaleType.CENTER_CROP);
        final DesktopFolderShortcut folderShortcut = file.folderShortcut();
        final DesktopApplicationShortcut applicationShortcut =
                file.applicationShortcut();
        final DesktopWebShortcut webShortcut = file.webShortcut();
        if (folderShortcut != null) {
            icon.setImageResource(R.drawable.ic_desktop_folder_link);
            if (!folderShortcut.available) {
                icon.setAlpha(0.55f);
                item.setAlpha(0.72f);
            }
        } else if (applicationShortcut != null) {
            icon.setImageDrawable(DesktopApplicationIconResolver.resolve(
                    mActivity, applicationShortcut));
        } else if (webShortcut != null) {
            icon.setImageResource(R.drawable.ic_desktop_web_link);
        } else if (file.thumbnail != null) {
            icon.setImageBitmap(file.thumbnail);
            icon.setBackground(DesktopUiFactory.filled(
                    DesktopUiFactory.COLOR_PANEL_ALT, dp(DesktopUiFactory.SHAPE_SMALL_DP)));
            icon.setClipToOutline(true);
        } else {
            icon.setImageResource(FileIconResolver.forFile(
                    file.directory, file.mimeType));
        }
        icon.setContentDescription(file.displayName());
        if (file.thumbnail != null && MediaThumbnails.isVideo(file.mimeType)) {
            item.addView(withPlayBadge(icon), iconParams());
        } else {
            item.addView(icon, iconParams());
        }
        addLabel(item, file.displayName());
        return item;
    }

    /** Marks a video preview with a small play badge. */
    private View withPlayBadge(final ImageView preview) {
        final android.widget.FrameLayout frame = new android.widget.FrameLayout(mActivity);
        frame.addView(preview, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        frame.addView(playBadge(mActivity, mUi, desktopDp(18, 14)),
                new android.widget.FrameLayout.LayoutParams(
                        desktopDp(18, 14), desktopDp(18, 14), Gravity.CENTER));
        return frame;
    }

    /** Round translucent play badge shared by desktop and file previews. */
    static ImageView playBadge(
            final android.content.Context context,
            final DesktopUiFactory ui,
            final int size) {
        final ImageView badge = new ImageView(context);
        badge.setImageResource(R.drawable.ic_play);
        badge.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        badge.setBackground(DesktopUiFactory.filled(0x99000000, size));
        final int padding = Math.max(1, size / 5);
        badge.setPadding(padding, padding, padding, padding);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return badge;
    }

    View overflow(final int hiddenCount) {
        final LinearLayout item = iconContainer();
        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_desktop_folder);
        item.addView(icon, iconParams());
        addLabel(item, mActivity.getString(
                R.string.desktop_more_files, Integer.valueOf(hiddenCount)));
        return item;
    }

    private LinearLayout iconContainer() {
        final LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        item.setPadding(
                desktopDp(8, 5),
                desktopDp(6, 4),
                desktopDp(8, 5),
                desktopDp(6, 4));
        item.setClickable(true);
        item.setFocusable(true);
        item.setDefaultFocusHighlightEnabled(false);
        item.setBackground(selectionBackground());
        return item;
    }

    /** Material selection: a tonal fill for selected items, hover state layers. */
    private android.graphics.drawable.StateListDrawable selectionBackground() {
        final int radius = dp(DesktopUiFactory.SHAPE_MEDIUM_DP);
        final int selected = DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_ACCENT, 0.28f);
        final android.graphics.drawable.StateListDrawable background =
                new android.graphics.drawable.StateListDrawable();
        background.addState(new int[] {android.R.attr.state_activated},
                mUi.rounded(selected, radius, DesktopUiFactory.withAlpha(
                        DesktopUiFactory.COLOR_ACCENT, 0.7f)));
        background.addState(new int[] {android.R.attr.state_focused},
                mUi.rounded(DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.12f),
                        radius, DesktopUiFactory.COLOR_ACCENT));
        background.addState(new int[] {android.R.attr.state_pressed},
                DesktopUiFactory.filled(DesktopUiFactory.withAlpha(
                        DesktopUiFactory.COLOR_TEXT, 0.18f), radius));
        background.addState(new int[] {android.R.attr.state_hovered},
                DesktopUiFactory.filled(DesktopUiFactory.withAlpha(
                        DesktopUiFactory.COLOR_TEXT, 0.12f), radius));
        background.addState(new int[0],
                DesktopUiFactory.filled(android.graphics.Color.TRANSPARENT, radius));
        return background;
    }

    private LinearLayout.LayoutParams iconParams() {
        return new LinearLayout.LayoutParams(
                desktopDp(44, 34), desktopDp(44, 34));
    }

    private void addLabel(
            final LinearLayout item,
            final CharSequence text) {
        final TextView label = new TextView(mActivity);
        label.setText(text);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(mActivity.isCompactDesktopPreview() ? 10 : 12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(2), 0, dp(1), 0xE6000000);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, 0);
        item.addView(label, params);
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
