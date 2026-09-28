package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material You design system for MagicDesk's own shell surfaces.
 *
 * <p>Color fields are Material 3 dark color roles. They start as the Material
 * baseline scheme and {@link #applySystemPalette} replaces them with Android's
 * wallpaper-derived dynamic scheme. Native WMShell captions keep their own
 * system styling; these roles only style MagicDesk chrome.
 */
public final class DesktopUiFactory {
    /** Material surface. */
    static int COLOR_BACKGROUND = 0xFF141218;
    /** Material surface container. */
    static int COLOR_PANEL = 0xFF211F26;
    /** Material surface container high. */
    public static int COLOR_PANEL_ALT = 0xFF2B2930;
    /** Material surface container highest. */
    static int COLOR_PANEL_FOCUS = 0xFF36343B;
    /** Material on-surface. */
    public static int COLOR_TEXT = 0xFFE6E0E9;
    /** Material on-surface variant. */
    public static int COLOR_MUTED = 0xFFCAC4D0;
    /** Material primary. */
    public static int COLOR_ACCENT = 0xFFD0BCFF;
    static int COLOR_ON_ACCENT = 0xFF381E72;
    static int COLOR_ACCENT_CONTAINER = 0xFF4F378B;
    static int COLOR_ON_ACCENT_CONTAINER = 0xFFEADDFF;
    static int COLOR_SECONDARY_CONTAINER = 0xFF4A4458;
    static int COLOR_ON_SECONDARY_CONTAINER = 0xFFE8DEF8;
    static int COLOR_OUTLINE_VARIANT = 0xFF49454F;
    /** Material error. */
    static final int COLOR_RED = 0xFFF2B8B5;
    static final int COLOR_ERROR_CONTAINER = 0xFF8C1D18;
    static final int COLOR_ON_ERROR_CONTAINER = 0xFFF9DEDC;
    /** Attention state; Material has no warning role, so this stays fixed. */
    static final int COLOR_AMBER = 0xFFFFB95C;

    /** Material shape scale, in dp. */
    static final int SHAPE_SMALL_DP = 8;
    static final int SHAPE_MEDIUM_DP = 12;
    static final int SHAPE_LARGE_DP = 16;
    static final int SHAPE_EXTRA_LARGE_DP = 28;
    /** Larger than any control; GradientDrawable clamps it to a pill. */
    static final int SHAPE_FULL_DP = 999;

    /** Material state-layer opacities. */
    static final float STATE_HOVER = 0.08f;
    static final float STATE_FOCUS = 0.10f;
    static final float STATE_PRESSED = 0.10f;

    private static final int MENU_ITEM_HEIGHT_DP = 48;
    private static final int MENU_MAX_WIDTH_DP = 360;

    /**
     * Reads Android's dynamic dark color roles. Called on the main thread when
     * the process starts and when the configuration (and so the wallpaper
     * palette) changes; views created afterwards use the new roles.
     */
    static void applySystemPalette(final Context context) {
        final Resources resources = context.getResources();
        try {
            COLOR_BACKGROUND = resources.getColor(
                    android.R.color.system_surface_dark, null);
            COLOR_PANEL = resources.getColor(
                    android.R.color.system_surface_container_dark, null);
            COLOR_PANEL_ALT = resources.getColor(
                    android.R.color.system_surface_container_high_dark, null);
            COLOR_PANEL_FOCUS = resources.getColor(
                    android.R.color.system_surface_container_highest_dark, null);
            COLOR_TEXT = resources.getColor(
                    android.R.color.system_on_surface_dark, null);
            COLOR_MUTED = resources.getColor(
                    android.R.color.system_on_surface_variant_dark, null);
            COLOR_ACCENT = resources.getColor(
                    android.R.color.system_primary_dark, null);
            COLOR_ON_ACCENT = resources.getColor(
                    android.R.color.system_on_primary_dark, null);
            COLOR_ACCENT_CONTAINER = resources.getColor(
                    android.R.color.system_primary_container_dark, null);
            COLOR_ON_ACCENT_CONTAINER = resources.getColor(
                    android.R.color.system_on_primary_container_dark, null);
            COLOR_SECONDARY_CONTAINER = resources.getColor(
                    android.R.color.system_secondary_container_dark, null);
            COLOR_ON_SECONDARY_CONTAINER = resources.getColor(
                    android.R.color.system_on_secondary_container_dark, null);
            COLOR_OUTLINE_VARIANT = resources.getColor(
                    android.R.color.system_outline_variant_dark, null);
        } catch (Resources.NotFoundException error) {
            // Firmware without dynamic color roles keeps the Material baseline.
        }
    }

    /** Composites a Material state layer of {@code overlay} over {@code base}. */
    static int stateLayer(final int base, final int overlay, final float opacity) {
        final float keep = 1.0f - opacity;
        return Color.argb(
                Color.alpha(base),
                Math.round(Color.red(base) * keep + Color.red(overlay) * opacity),
                Math.round(Color.green(base) * keep + Color.green(overlay) * opacity),
                Math.round(Color.blue(base) * keep + Color.blue(overlay) * opacity));
    }

    static int withAlpha(final int color, final float alpha) {
        return (Math.round(Color.alpha(color) * alpha) << 24) | (color & 0x00FFFFFF);
    }

    /** Material title and label weight; Typeface caches the family. */
    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private final Context mContext;

    DesktopUiFactory(final Context context) {
        mContext = context;
    }

    public int dp(final int value) {
        return Math.round(value
                * mContext.getResources().getDisplayMetrics().density);
    }

    int desktopDp(
            final int normalValue,
            final int compactValue,
            final boolean compact) {
        return dp(compact ? compactValue : normalValue);
    }

    public TextView sectionTitle(final int titleResId) {
        final TextView title = new TextView(mContext);
        title.setText(titleResId);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    public void addControlSection(
            final LinearLayout parent, final int titleResId, final int spacing) {
        if (parent.getChildCount() > 0) {
            final View divider = new View(mContext);
            divider.setBackgroundColor(COLOR_OUTLINE_VARIANT);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            params.setMargins(0, spacing, 0, spacing);
            parent.addView(divider, params);
        }
        final TextView title = sectionTitle(titleResId);
        title.setTextSize(14);
        title.setTextColor(COLOR_ACCENT);
        title.setTypeface(medium());
        title.setAccessibilityHeading(true);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        parent.addView(title, params);
    }

    public Button actionButton(final int textResId, final int accentColor) {
        return actionButton(mContext.getString(textResId), accentColor);
    }

    /**
     * Material 3 pill button. {@code accentColor} selects the emphasis:
     * accent is the ordinary filled tonal action, red a destructive tonal
     * action, amber an attention tonal action, and anything else a neutral,
     * lower-emphasis button.
     */
    Button actionButton(final String text, final int accentColor) {
        if (accentColor == COLOR_ACCENT) {
            return pillButton(text, COLOR_SECONDARY_CONTAINER,
                    COLOR_ON_SECONDARY_CONTAINER, COLOR_ACCENT);
        }
        if (accentColor == COLOR_RED) {
            return pillButton(text, COLOR_ERROR_CONTAINER,
                    COLOR_ON_ERROR_CONTAINER, COLOR_RED);
        }
        if (accentColor == COLOR_AMBER) {
            return pillButton(text, COLOR_SECONDARY_CONTAINER, COLOR_AMBER, COLOR_AMBER);
        }
        return pillButton(text, COLOR_PANEL_FOCUS, COLOR_TEXT, COLOR_ACCENT);
    }

    /** Material segmented-button choice; the selected segment is filled primary. */
    Button segmentButton(final int textResId, final boolean selected) {
        return selected
                ? pillButton(mContext.getString(textResId),
                        COLOR_ACCENT, COLOR_ON_ACCENT, COLOR_TEXT)
                : pillButton(mContext.getString(textResId),
                        COLOR_PANEL_FOCUS, COLOR_MUTED, COLOR_ACCENT);
    }

    /**
     * Material 3 switch drawn by MagicDesk, so vendor themes cannot restyle
     * it: a 52 x 32 dp pill track and a thumb that fills when checked.
     */
    public void styleSwitch(final android.widget.Switch toggle) {
        final int radius = dp(SHAPE_FULL_DP);
        final StateListDrawable track = new StateListDrawable();
        final GradientDrawable disabled = filled(withAlpha(COLOR_TEXT, 0.12f), radius);
        disabled.setSize(dp(52), dp(32));
        final GradientDrawable checked = filled(COLOR_ACCENT, radius);
        checked.setSize(dp(52), dp(32));
        final GradientDrawable unchecked = filled(COLOR_PANEL_FOCUS, radius);
        unchecked.setStroke(dp(2), COLOR_MUTED);
        unchecked.setSize(dp(52), dp(32));
        track.addState(new int[] {-android.R.attr.state_enabled}, disabled);
        track.addState(new int[] {android.R.attr.state_checked}, checked);
        track.addState(new int[0], unchecked);
        toggle.setTrackDrawable(track);
        toggle.setTrackTintList(null);

        final GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.WHITE);
        final GradientDrawable frame = new GradientDrawable();
        // Only sizes the thumb; without a transparent color the thumb tint
        // would fill it as an opaque square.
        frame.setColor(Color.TRANSPARENT);
        frame.setSize(dp(26), dp(32));
        final android.graphics.drawable.LayerDrawable thumb =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {frame, dot});
        thumb.setLayerInset(1, dp(4), dp(7), dp(4), dp(7));
        toggle.setThumbDrawable(thumb);
        toggle.setThumbTintList(new ColorStateList(
                new int[][] {
                    new int[] {-android.R.attr.state_enabled},
                    new int[] {android.R.attr.state_checked},
                    new int[0]
                },
                new int[] {withAlpha(COLOR_TEXT, 0.38f), COLOR_ON_ACCENT, COLOR_MUTED}));
        toggle.setSwitchMinWidth(dp(52));
        toggle.setShowText(false);
        toggle.setBackground(null);
    }

    /** Background for a selectable choice drawn outside {@link #segmentButton}. */
    public GradientDrawable segmentBackground(final boolean selected) {
        return selected
                ? rounded(COLOR_SECONDARY_CONTAINER, dp(SHAPE_FULL_DP), COLOR_ACCENT)
                : rounded(COLOR_PANEL_FOCUS, dp(SHAPE_FULL_DP), COLOR_PANEL_FOCUS);
    }

    private Button pillButton(
            final String text,
            final int container,
            final int content,
            final int focusRing) {
        final Button button = new Button(mContext);
        button.setText(text);
        button.setAllCaps(false);
        button.setTypeface(medium());
        button.setTextColor(new ColorStateList(
                new int[][] {
                    new int[] {-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[] {withAlpha(COLOR_TEXT, 0.38f), content}));
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        final int radius = dp(SHAPE_FULL_DP);
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {-android.R.attr.state_enabled},
                filled(withAlpha(COLOR_TEXT, 0.12f), radius));
        background.addState(
                new int[] {android.R.attr.state_pressed},
                filled(stateLayer(container, content, STATE_PRESSED), radius));
        background.addState(
                new int[] {android.R.attr.state_focused},
                outlined(stateLayer(container, content, STATE_FOCUS), radius, focusRing));
        background.addState(
                new int[] {android.R.attr.state_hovered},
                filled(stateLayer(container, content, STATE_HOVER), radius));
        background.addState(new int[0], filled(container, radius));
        button.setBackground(background);
        return button;
    }

    Button menuItem(final int textResId, final int emphasisColor) {
        return menuItem(mContext.getString(textResId), emphasisColor);
    }

    Button menuItem(final String text, final int emphasisColor) {
        final Button button = new Button(mContext);
        styleMenuItem(button, text, emphasisColor);
        return button;
    }

    android.widget.CheckBox menuCheckBox(final String text, final boolean checked) {
        final android.widget.CheckBox button = new android.widget.CheckBox(mContext);
        styleMenuItem(button, text, COLOR_TEXT);
        button.setButtonTintList(ColorStateList.valueOf(COLOR_TEXT));
        button.setChecked(checked);
        return button;
    }

    private void styleMenuItem(final Button button, final String text, final int emphasisColor) {
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        final int enabledColor = emphasisColor == COLOR_RED
                || emphasisColor == COLOR_AMBER
                ? emphasisColor : COLOR_TEXT;
        button.setTextColor(new ColorStateList(
                new int[][] {
                    new int[] {-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[] {withAlpha(COLOR_TEXT, 0.38f), enabledColor}));
        button.setBackground(menuItemBackground());
    }

    ImageButton menuIconButton(
            final int drawableResId,
            final int descriptionResId) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawableResId);
        button.setImageTintList(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                new int[] {withAlpha(COLOR_TEXT, 0.38f), COLOR_MUTED}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(stateLayerBackground(dp(SHAPE_FULL_DP)));
        button.setContentDescription(mContext.getString(descriptionResId));
        button.setTooltipText(mContext.getString(descriptionResId));
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        return button;
    }

    Button controlAction(final int textResId, final int iconResId, final int emphasisColor) {
        final Button button = menuItem(textResId, emphasisColor);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setTextSize(14);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setCompoundDrawableTintList(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                new int[] {COLOR_MUTED, emphasisColor == COLOR_RED ? COLOR_RED : COLOR_TEXT}));
        setControlIcon(button, iconResId);
        button.setCompoundDrawablePadding(dp(10));
        return button;
    }

    void setControlIcon(final Button button, final int iconResId) {
        final Drawable icon = mContext.getDrawable(iconResId).mutate();
        icon.setBounds(0, 0, dp(22), dp(22));
        button.setCompoundDrawablesRelative(icon, null, null, null);
    }

    TextView menuHeader(
            final CharSequence text,
            final TextUtils.TruncateAt ellipsize) {
        final TextView title = new TextView(mContext);
        title.setText(text);
        title.setTextColor(COLOR_MUTED);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setEllipsize(ellipsize);
        if (ellipsize == TextUtils.TruncateAt.START) {
            title.setSingleLine(true);
        } else {
            title.setMaxLines(2);
        }
        title.setPadding(dp(10), dp(4), dp(10), dp(4));
        return title;
    }

    GradientDrawable menuSurface() {
        return rounded(COLOR_PANEL, dp(SHAPE_LARGE_DP), COLOR_OUTLINE_VARIANT);
    }

    /** Large floating panel, such as Start or the notification center. */
    GradientDrawable panelSurface() {
        return rounded(COLOR_PANEL, dp(SHAPE_EXTRA_LARGE_DP), COLOR_OUTLINE_VARIANT);
    }

    int menuItemHeight() {
        return dp(MENU_ITEM_HEIGHT_DP);
    }

    int menuWidth(final int availableWidth, final int horizontalMargin) {
        final int boundedWidth = Math.max(1, availableWidth - horizontalMargin * 2);
        return Math.min(dp(MENU_MAX_WIDTH_DP), boundedWidth);
    }

    private StateListDrawable menuItemBackground() {
        return stateLayerBackground(dp(SHAPE_MEDIUM_DP));
    }

    /**
     * Transparent Material state layer. Translucent overlays composite onto
     * whichever surface hosts the control; keyboard focus uses the secondary
     * container so it remains visible without a pointer.
     */
    StateListDrawable stateLayerBackground(final int radius) {
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {-android.R.attr.state_enabled},
                filled(Color.TRANSPARENT, radius));
        background.addState(
                new int[] {android.R.attr.state_pressed},
                filled(withAlpha(COLOR_TEXT, STATE_PRESSED + STATE_HOVER), radius));
        background.addState(
                new int[] {android.R.attr.state_focused},
                filled(COLOR_SECONDARY_CONTAINER, radius));
        background.addState(
                new int[] {android.R.attr.state_hovered},
                filled(withAlpha(COLOR_TEXT, STATE_HOVER), radius));
        background.addState(
                new int[0],
                filled(Color.TRANSPARENT, radius));
        return background;
    }

    static GradientDrawable filled(
            final int color,
            final int radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable outlined(
            final int color,
            final int radius,
            final int strokeColor) {
        final GradientDrawable drawable = filled(color, radius);
        drawable.setStroke(dp(2), strokeColor);
        return drawable;
    }

    /** Material 3 panel-header button: 40 dp tall, 14 sp, sized to its label. */
    Button headerButton(final int textResId, final int accentColor) {
        final Button button = actionButton(mContext.getString(textResId), accentColor);
        button.setTextSize(14);
        button.setMinWidth(0);
        button.setMinimumWidth(dp(72));
        button.setMinHeight(0);
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(20), 0, dp(20), 0);
        return button;
    }

    static LinearLayout.LayoutParams headerButtonParams(final int height, final int marginStart) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, height);
        params.setMarginStart(marginStart);
        return params;
    }

    Button smallButton(final int textResId, final int accentColor) {
        return smallButton(mContext.getString(textResId), accentColor);
    }

    Button smallButton(final String text, final int accentColor) {
        final Button button = actionButton(text, accentColor);
        button.setTextSize(11);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(4), dp(2), dp(4), dp(2));
        return button;
    }

    ImageButton taskbarIconButton(
            final int drawableResId,
            final int descriptionResId,
            final boolean compact) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawableResId);
        button.setColorFilter(COLOR_TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(
                desktopDp(10, 7, compact),
                desktopDp(10, 7, compact),
                desktopDp(10, 7, compact),
                desktopDp(10, 7, compact));
        button.setBackground(stateLayerBackground(dp(SHAPE_FULL_DP)));
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        button.setContentDescription(mContext.getString(descriptionResId));
        button.setTooltipText(mContext.getString(descriptionResId));
        return button;
    }

    StateListDrawable interactiveRounded(
            final int color,
            final int radius,
            final int accentColor) {
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {android.R.attr.state_pressed},
                filled(stateLayer(color, COLOR_TEXT, STATE_PRESSED), radius));
        background.addState(
                new int[] {android.R.attr.state_focused},
                outlined(stateLayer(color, COLOR_TEXT, STATE_FOCUS), radius, accentColor));
        background.addState(
                new int[] {android.R.attr.state_hovered},
                filled(stateLayer(color, COLOR_TEXT, STATE_HOVER), radius));
        background.addState(
                new int[0],
                filled(color, radius));
        return background;
    }

    public GradientDrawable rounded(
            final int color,
            final int radius,
            final int strokeColor) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }
}
