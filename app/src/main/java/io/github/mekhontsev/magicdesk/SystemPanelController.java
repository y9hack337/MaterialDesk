package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class SystemPanelController {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;

    private LinearLayout mPanel;
    /** Last loaded Android tiles; shown at once, then refreshed off-thread. */
    private java.util.List<AndroidQuickTiles.Tile> mQuickTiles = java.util.List.of();
    private boolean mQuickTilesLoaded;

    SystemPanelController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    LinearLayout createPanel() {
        final LinearLayout panel = new LinearLayout(mActivity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(4), dp(12), dp(12));
        panel.setBackground(mUi.menuSurface());
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(final View view) {
                        mActivity.setHardwarePanelVisible(true);
                    }

                    @Override
                    public void onViewDetachedFromWindow(final View view) {
                        mActivity.setHardwarePanelVisible(false);
                    }
                });
        mPanel = panel;
        return panel;
    }

    void toggle() {
        final DesktopPanelWindowController panels = mActivity.panels();
        if (panels == null || mPanel == null) {
            return;
        }
        if (panels.isShowing(mPanel)) {
            mActivity.hideAllPanels();
            return;
        }
        mActivity.captureInteractionStackForPanel();
        render();
        refreshQuickTiles();

        final Rect area = mActivity.getDesktopPanelAreaBounds();
        final int areaWidth = area.width();
        final int areaHeight = area.height();
        final int width = mUi.menuWidth(areaWidth, dp(8));
        final int maxHeight = Math.max(1,
                areaHeight - dp(16));
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        final int height = Math.min(maxHeight, mPanel.getMeasuredHeight());
        if (!panels.show(
                mPanel,
                ShellPanelPlacement.anchored(width, height,
                        ShellSurface.RIGHT | ShellSurface.BOTTOM, 0, 0, dp(8), dp(8)),
                false,
                mActivity.getString(R.string.section_quick_controls))) {
            mActivity.setErrorStatus(
                    "PANEL-001",
                    mActivity.getString(
                            R.string.status_desktop_panel_unavailable));
        }
    }

    private void render() {
        mPanel.removeAllViews();

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(mActivity);
        title.setText(R.string.section_quick_controls);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final ImageButton settings = mUi.menuIconButton(
                R.drawable.ic_settings, R.string.action_settings);
        settings.setOnClickListener(view -> mActivity.openSettings());
        final LinearLayout.LayoutParams settingsParams =
                new LinearLayout.LayoutParams(
                        dp(48), dp(48));
        header.addView(settings, settingsParams);

        final ImageButton close = mUi.menuIconButton(
                R.drawable.ic_close, R.string.action_close);
        close.setOnClickListener(view -> mActivity.hideAllPanels());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(48), dp(48)));
        mActivity.registerAutomationUiElement(settings,
                "quick_controls.settings", "button", mActivity.getString(R.string.action_settings));
        mActivity.registerAutomationUiElement(close,
                "quick_controls.close", "button", mActivity.getString(R.string.action_close));
        mPanel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, 0);
        addQuickTiles(content);
        mActivity.populateSystemControls(content, dp(10));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    /** Android's own Quick Settings application tiles, such as a VPN. */
    private void addQuickTiles(final LinearLayout content) {
        mUi.addControlSection(content, R.string.quick_tiles_title, dp(10));
        if (!ShellAccess.isReady()) {
            content.addView(note(R.string.quick_tiles_unavailable));
            return;
        }
        if (mQuickTilesLoaded && mQuickTiles.isEmpty()) {
            content.addView(note(R.string.quick_tiles_empty));
        }
        final android.widget.GridLayout grid = new android.widget.GridLayout(mActivity);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        for (final AndroidQuickTiles.Tile tile : mQuickTiles) {
            final android.widget.GridLayout.LayoutParams params =
                    new android.widget.GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(52);
            params.columnSpec = android.widget.GridLayout.spec(
                    android.widget.GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(tileView(tile), params);
        }
        content.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final android.widget.Button phone = mUi.actionButton(
                R.string.quick_tiles_open_phone, DesktopUiFactory.COLOR_PANEL_ALT);
        phone.setOnClickListener(view -> DesktopOperations.executeSerialized(() -> {
            try {
                AndroidQuickTiles.expandOnPhone();
            } catch (java.io.IOException error) {
                showTileError(error);
            }
        }));
        final LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        phoneParams.setMargins(dp(3), dp(6), dp(3), 0);
        content.addView(phone, phoneParams);
        mActivity.registerAutomationUiElement(phone, "quick_controls.android_quick_settings",
                "button", mActivity.getString(R.string.quick_tiles_open_phone));
    }

    private View tileView(final AndroidQuickTiles.Tile tile) {
        final LinearLayout view = new LinearLayout(mActivity);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(14), 0, dp(12), 0);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(mUi.interactiveRounded(DesktopUiFactory.COLOR_PANEL_FOCUS,
                dp(DesktopUiFactory.SHAPE_FULL_DP), DesktopUiFactory.COLOR_ACCENT));
        final android.widget.ImageView icon = new android.widget.ImageView(mActivity);
        icon.setImageDrawable(tile.icon());
        if (tile.tintIcon()) {
            icon.setColorFilter(COLOR_TEXT);
        }
        view.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        final TextView label = new TextView(mActivity);
        label.setText(tile.label());
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(dp(10), 0, 0, 0);
        view.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        view.setContentDescription(tile.label());
        view.setTooltipText(tile.label());
        view.setOnClickListener(clicked -> DesktopOperations.executeSerialized(() -> {
            try {
                AndroidQuickTiles.click(tile.component());
                mActivity.runOnUiThread(() -> {
                    if (!mActivity.isActivityUnavailable()) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.quick_tiles_pressed, tile.label()));
                    }
                });
            } catch (java.io.IOException error) {
                showTileError(error);
            }
        }));
        mActivity.registerAutomationUiElement(view,
                "quick_controls.tile." + DesktopAutomationUiRegistry.identitySegment(tile.component()),
                "button", tile.label());
        return view;
    }

    private TextView note(final int text) {
        final TextView note = new TextView(mActivity);
        note.setText(text);
        note.setTextColor(DesktopUiFactory.COLOR_MUTED);
        note.setTextSize(12);
        note.setPadding(dp(4), 0, dp(4), dp(6));
        return note;
    }

    /** Loads tiles off the UI thread; re-renders only when the set changed. */
    private void refreshQuickTiles() {
        if (!ShellAccess.isReady()) {
            return;
        }
        DesktopOperations.executeSerialized(() -> {
            final java.util.List<AndroidQuickTiles.Tile> tiles;
            try {
                tiles = AndroidQuickTiles.load(mActivity);
            } catch (java.io.IOException error) {
                return;
            }
            mActivity.runOnUiThread(() -> {
                final boolean changed = !mQuickTilesLoaded
                        || !AndroidQuickTiles.sameComponents(mQuickTiles, tiles);
                mQuickTiles = tiles;
                mQuickTilesLoaded = true;
                final DesktopPanelWindowController panels = mActivity.panels();
                if (changed && !mActivity.isActivityUnavailable() && panels != null
                        && mPanel != null && panels.isShowing(mPanel)) {
                    // Reopen at the new measured height.
                    mActivity.hideAllPanels();
                    toggle();
                }
            });
        });
    }

    private void showTileError(final java.io.IOException error) {
        mActivity.runOnUiThread(() -> {
            if (!mActivity.isActivityUnavailable()) {
                mActivity.setErrorStatus("QUICK-TILE-001",
                        mActivity.getString(R.string.quick_tiles_failed,
                                ShellAccess.usefulMessage(error)));
            }
        });
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
