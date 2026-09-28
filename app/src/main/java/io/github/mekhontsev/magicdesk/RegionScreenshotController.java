package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Snipping-tool screenshot: the display is captured first and shown frozen
 * in a full-output overlay, so the overlay itself never appears in the image
 * and no settling delay is needed. Dragging selects the area; the toolbar's
 * Full screen button, Enter or a click without dragging keeps the whole
 * screen; Esc, the toolbar's close button or a right click cancels. The result
 * is saved to Pictures/Screenshots through MediaStore and copied to the
 * clipboard.
 */
final class RegionScreenshotController {
    private static final String DIRECTORY =
            Environment.DIRECTORY_PICTURES + "/Screenshots";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    /** The panel root: the frozen selection surface and its toolbar. */
    private FrameLayout mOverlay;
    private SelectionView mSelection;
    private boolean mCapturing;

    RegionScreenshotController(final DesktopShellActivity activity, final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void start() {
        final DesktopPanelWindowController current = mActivity.panels();
        if (mOverlay != null && (current == null || !current.isShowing(mOverlay))) {
            // Another panel or a display change closed the overlay.
            mOverlay = null;
        }
        if (mCapturing || mOverlay != null) {
            return;
        }
        if (!ShellAccess.isReady()) {
            mActivity.setErrorStatus("SCREENSHOT-002",
                    mActivity.getString(R.string.region_screenshot_unavailable));
            return;
        }
        mActivity.hideAllPanels();
        mCapturing = true;
        final int displayId = mActivity.getCurrentDisplayId();
        final Context context = mActivity.getApplicationContext();
        DesktopOperations.executeSerialized(() -> {
            Bitmap frame = null;
            String failure = null;
            try {
                final CaptureService.Image image = new CaptureService(context).capture(
                        new CaptureRequest(CaptureRequest.Target.DISPLAY, displayId, null));
                frame = BitmapFactory.decodeByteArray(image.png(), 0, image.png().length);
                if (frame == null) {
                    failure = "capture could not be decoded";
                }
            } catch (IOException | RuntimeException error) {
                failure = ShellAccess.usefulMessage(error);
            }
            final Bitmap captured = frame;
            final String error = failure;
            mActivity.runOnUiThread(() -> {
                mCapturing = false;
                if (mActivity.isActivityUnavailable()) {
                    if (captured != null) captured.recycle();
                    return;
                }
                if (captured == null) {
                    mActivity.setErrorStatus("SCREENSHOT-002", mActivity.getString(
                            R.string.region_screenshot_failed, error));
                    return;
                }
                showOverlay(captured);
            });
        });
    }

    private void showOverlay(final Bitmap frame) {
        final DesktopPanelWindowController panels = mActivity.panels();
        if (panels == null) {
            frame.recycle();
            return;
        }
        final SelectionView selection = new SelectionView(mActivity, frame);
        final FrameLayout overlay = new FrameLayout(mActivity);
        overlay.addView(selection, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        final FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        toolbarParams.topMargin = mUi.dp(24);
        overlay.addView(createToolbar(selection), toolbarParams);
        mOverlay = overlay;
        mSelection = selection;
        if (!panels.show(overlay, ShellPanelPlacement.fullOutput(), true,
                mActivity.getString(R.string.region_screenshot_title))) {
            dismiss();
            return;
        }
        selection.requestFocus();
    }

    /** Material toolbar: the hint, Full screen and Cancel. */
    private View createToolbar(final SelectionView selection) {
        final LinearLayout toolbar = new LinearLayout(mActivity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(mUi.dp(20), mUi.dp(6), mUi.dp(6), mUi.dp(6));
        toolbar.setBackground(mUi.rounded(DesktopUiFactory.COLOR_PANEL,
                mUi.dp(DesktopUiFactory.SHAPE_FULL_DP), DesktopUiFactory.COLOR_OUTLINE_VARIANT));
        toolbar.setElevation(mUi.dp(6));
        final TextView hint = new TextView(mActivity);
        hint.setText(R.string.region_screenshot_hint);
        hint.setTextColor(DesktopUiFactory.COLOR_MUTED);
        hint.setTextSize(13);
        toolbar.addView(hint);
        final Button full = mUi.actionButton(
                R.string.region_screenshot_full, DesktopUiFactory.COLOR_ACCENT);
        full.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_fullscreen, 0, 0, 0);
        full.setCompoundDrawablePadding(mUi.dp(8));
        full.setCompoundDrawableTintList(ColorStateList.valueOf(
                DesktopUiFactory.COLOR_ON_SECONDARY_CONTAINER));
        full.setOnClickListener(view -> finish(null));
        final LinearLayout.LayoutParams fullParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, mUi.dp(40));
        fullParams.setMarginStart(mUi.dp(16));
        toolbar.addView(full, fullParams);
        mActivity.registerAutomationUiElement(full, "screenshot.full_screen", "button",
                mActivity.getString(R.string.region_screenshot_full));
        final ImageButton close = mUi.menuIconButton(
                R.drawable.ic_close, R.string.region_screenshot_cancel);
        close.setOnClickListener(view -> dismiss());
        final LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(mUi.dp(40), mUi.dp(40));
        closeParams.setMarginStart(mUi.dp(4));
        toolbar.addView(close, closeParams);
        mActivity.registerAutomationUiElement(close, "screenshot.cancel", "button",
                mActivity.getString(R.string.region_screenshot_cancel));
        selection.mToolbar = toolbar;
        return toolbar;
    }

    private void finish(final RectF selection) {
        final SelectionView overlay = mSelection;
        if (mOverlay == null || overlay == null) {
            return;
        }
        final Bitmap frame = overlay.mFrame;
        final int[] origin = new int[2];
        overlay.getLocationOnScreen(origin);
        final Bitmap cropped = crop(frame, selection, origin[0], origin[1]);
        dismissOverlayOnly();
        if (cropped != frame) {
            // A whole-frame crop is the frame itself; it is recycled after saving.
            frame.recycle();
        }
        if (cropped == null) {
            return;
        }
        final ContentResolver resolver = mActivity.getContentResolver();
        DesktopOperations.executeSerialized(() -> {
            Uri saved = null;
            String failure = null;
            try {
                saved = save(resolver, cropped);
            } catch (IOException | RuntimeException error) {
                failure = ShellAccess.usefulMessage(error);
            } finally {
                cropped.recycle();
            }
            final Uri uri = saved;
            final String error = failure;
            mActivity.runOnUiThread(() -> {
                if (mActivity.isActivityUnavailable()) {
                    return;
                }
                if (uri == null) {
                    mActivity.setErrorStatus("SCREENSHOT-003", mActivity.getString(
                            R.string.region_screenshot_failed, error));
                    return;
                }
                final ClipboardManager clipboard =
                        mActivity.getSystemService(ClipboardManager.class);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newUri(resolver,
                            mActivity.getString(R.string.region_screenshot_title), uri));
                }
                mActivity.setStatus(mActivity.getString(R.string.region_screenshot_saved));
            });
        });
    }

    /**
     * Crops the frozen frame; {@code selection} is in overlay coordinates and
     * the overlay's screen origin maps it to display pixels. An empty
     * selection keeps the whole frame.
     */
    static Bitmap crop(final Bitmap frame, final RectF selection,
            final int originX, final int originY) {
        final int[] bounds = selection == null
                ? new int[] {0, 0, frame.getWidth(), frame.getHeight()}
                : cropBounds(frame.getWidth(), frame.getHeight(), selection.left,
                        selection.top, selection.right, selection.bottom, originX, originY);
        if (bounds == null) {
            return null;
        }
        return Bitmap.createBitmap(frame, bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    /** {x, y, width, height} in frame pixels, or null when nothing remains. */
    static int[] cropBounds(final int frameWidth, final int frameHeight,
            final float x1, final float y1, final float x2, final float y2,
            final int originX, final int originY) {
        final int left = clamp(Math.round(Math.min(x1, x2)) + originX, frameWidth);
        final int top = clamp(Math.round(Math.min(y1, y2)) + originY, frameHeight);
        final int right = clamp(Math.round(Math.max(x1, x2)) + originX, frameWidth);
        final int bottom = clamp(Math.round(Math.max(y1, y2)) + originY, frameHeight);
        return right - left < 1 || bottom - top < 1
                ? null : new int[] {left, top, right - left, bottom - top};
    }

    private static int clamp(final int value, final int size) {
        return Math.max(0, Math.min(size, value));
    }

    private static Uri save(final ContentResolver resolver, final Bitmap bitmap)
            throws IOException {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, DIRECTORY);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        final Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore refused the screenshot");
        }
        try (OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("screenshot could not be written");
            }
        } catch (IOException | RuntimeException error) {
            resolver.delete(uri, null, null);
            throw error;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    void dismiss() {
        final SelectionView selection = mSelection;
        dismissOverlayOnly();
        if (selection != null) {
            selection.mFrame.recycle();
        }
    }

    private void dismissOverlayOnly() {
        final View overlay = mOverlay;
        mOverlay = null;
        mSelection = null;
        final DesktopPanelWindowController panels = mActivity.panels();
        if (overlay != null && panels != null) {
            panels.hide(overlay);
        }
    }

    /** Frozen capture with a dimmed surround and a live selection rectangle. */
    @SuppressLint("ViewConstructor")
    private final class SelectionView extends View {
        private final Bitmap mFrame;
        private final Paint mDim = new Paint();
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mSelection = new RectF();
        private final RectF mLabelRect = new RectF();
        private final int[] mOrigin = new int[2];
        private final float mSlop;
        /** Hidden while dragging so it never covers the selection. */
        private View mToolbar;
        private boolean mDragging;
        private boolean mSelected;

        SelectionView(final Context context, final Bitmap frame) {
            super(context);
            mFrame = frame;
            mSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
            mDim.setColor(0x80000000);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(mUi.dp(2));
            mStroke.setColor(DesktopUiFactory.COLOR_ACCENT);
            mLabel.setColor(DesktopUiFactory.COLOR_ON_ACCENT);
            mLabel.setTextSize(mUi.dp(13));
            mLabel.setTypeface(DesktopUiFactory.medium());
            mLabelBackground.setColor(DesktopUiFactory.COLOR_ACCENT);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setContentDescription(context.getString(R.string.region_screenshot_hint));
            setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        dismiss();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        finish(null);
                    }
                    return true;
                }
                return false;
            });
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(final MotionEvent event) {
            if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismiss();
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mSelection.set(event.getX(), event.getY(), event.getX(), event.getY());
                    mDragging = true;
                    mSelected = false;
                    setToolbarVisible(false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mDragging) {
                        mSelection.right = event.getX();
                        mSelection.bottom = event.getY();
                        mSelected |= Math.abs(mSelection.width()) > mSlop
                                || Math.abs(mSelection.height()) > mSlop;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mDragging) {
                        mDragging = false;
                        // A click without dragging keeps the whole screen.
                        finish(mSelected ? new RectF(mSelection) : null);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mDragging = false;
                    mSelected = false;
                    setToolbarVisible(true);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        private void setToolbarVisible(final boolean visible) {
            if (mToolbar != null) {
                mToolbar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            }
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            getLocationOnScreen(mOrigin);
            // Align the frozen frame with the real screen underneath.
            canvas.drawBitmap(mFrame, -mOrigin[0], -mOrigin[1], null);
            if (!mSelected) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), mDim);
                return;
            }
            final float left = Math.min(mSelection.left, mSelection.right);
            final float top = Math.min(mSelection.top, mSelection.bottom);
            final float right = Math.max(mSelection.left, mSelection.right);
            final float bottom = Math.max(mSelection.top, mSelection.bottom);
            canvas.drawRect(0, 0, getWidth(), top, mDim);
            canvas.drawRect(0, bottom, getWidth(), getHeight(), mDim);
            canvas.drawRect(0, top, left, bottom, mDim);
            canvas.drawRect(right, top, getWidth(), bottom, mDim);
            canvas.drawRect(left, top, right, bottom, mStroke);
            final String size = Math.round(right - left) + " × " + Math.round(bottom - top);
            drawLabel(canvas, size, left, Math.max(mUi.dp(28), top - mUi.dp(8)));
        }

        private void drawLabel(final Canvas canvas, final String text,
                final float left, final float baseline) {
            final float padding = mUi.dp(8);
            mLabelRect.set(left, baseline + mLabel.ascent() - padding / 2,
                    left + mLabel.measureText(text) + padding * 2,
                    baseline + mLabel.descent() + padding / 2);
            canvas.drawRoundRect(mLabelRect, mUi.dp(6), mUi.dp(6), mLabelBackground);
            canvas.drawText(text, left + padding, baseline, mLabel);
        }
    }
}
