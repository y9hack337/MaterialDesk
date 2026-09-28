package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Material battery glyph for the taskbar, in the style of Android 16: a solid
 * body whose empty part is a translucent track and whose filled part follows
 * the charge, with the percentage in bold, dark digits centered inside. The
 * digits have one color at every level, so they never split where the fill
 * ends.
 */
@SuppressLint("ViewConstructor")
final class BatteryIndicatorView extends View {
    enum Tone { NORMAL, CHARGING, LOW, CRITICAL, UNKNOWN }

    static final int LOW_PERCENT = 20;
    static final int CRITICAL_PERCENT = 10;

    private final float mDensity;
    private final float mBodyWidth;
    private final float mBodyHeight;
    private final float mCapWidth;
    private final float mBoltWidth;
    private final Paint mOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBolt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBody = new RectF();
    private final RectF mLevel = new RectF();
    private final RectF mCap = new RectF();
    private final android.graphics.Rect mTextBounds = new android.graphics.Rect();
    private final Path mBoltPath = new Path();
    private int mPercent = -1;
    private boolean mCharging;
    private boolean mExternalPower;

    BatteryIndicatorView(final Context context, final boolean compact) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mBodyWidth = dp(compact ? 28 : 36);
        mBodyHeight = dp(compact ? 15 : 19);
        mCapWidth = dp(2.5f);
        mBoltWidth = dp(compact ? 7 : 9);
        mOutline.setStyle(Paint.Style.FILL);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(android.graphics.Typeface.create(
                DesktopUiFactory.medium(), 800, false));
        mText.setTextSize(mBodyHeight * 0.74f);
        mBolt.setStyle(Paint.Style.FILL);
    }

    /**
     * {@code percent} is 0..100, or negative when unknown. External power
     * without charging (bypass) uses the accent fill without the bolt.
     */
    void setLevel(final int percent, final boolean charging, final boolean externalPower) {
        if (mPercent == percent && mCharging == charging && mExternalPower == externalPower) {
            return;
        }
        mPercent = percent;
        mCharging = charging;
        mExternalPower = externalPower;
        invalidate();
    }

    static Tone tone(final int percent, final boolean charging) {
        if (percent < 0) {
            return Tone.UNKNOWN;
        }
        if (charging) {
            return Tone.CHARGING;
        }
        if (percent <= CRITICAL_PERCENT) {
            return Tone.CRITICAL;
        }
        return percent <= LOW_PERCENT ? Tone.LOW : Tone.NORMAL;
    }

    /** Filled share of the body; a sliver stays visible above zero. */
    static float fillFraction(final int percent) {
        if (percent <= 0) {
            return 0.0f;
        }
        return Math.max(0.08f, Math.min(1.0f, percent / 100.0f));
    }

    /**
     * Left edge of the body: the glyph (bolt only while charging, body, cap)
     * is centered, so an idle battery never sits off-center beside the space
     * of an absent bolt.
     */
    static float bodyLeft(final float width, final float bodyWidth,
            final float capWidth, final float boltSpace) {
        return (width - (boltSpace + bodyWidth + capWidth)) / 2.0f + boltSpace;
    }

    @Override
    protected void onMeasure(final int widthSpec, final int heightSpec) {
        final int width = Math.round(mBoltWidth + dp(3) + mBodyWidth + mCapWidth)
                + getPaddingLeft() + getPaddingRight();
        final int height = Math.round(mBodyHeight) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final Tone tone = tone(mPercent, mCharging || mExternalPower);
        final int fillColor = switch (tone) {
            case CHARGING -> DesktopUiFactory.COLOR_ACCENT;
            case LOW -> DesktopUiFactory.COLOR_AMBER;
            case CRITICAL -> DesktopUiFactory.COLOR_RED;
            default -> DesktopUiFactory.COLOR_TEXT;
        };
        final float left = bodyLeft(getWidth(), mBodyWidth, mCapWidth,
                mCharging ? mBoltWidth + dp(3) : 0.0f);
        final float top = (getHeight() - mBodyHeight) / 2.0f;
        mBody.set(left, top, left + mBodyWidth, top + mBodyHeight);
        final float radius = dp(5);

        // Empty track, then the charge on top of it.
        mOutline.setColor(DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.5f));
        canvas.drawRoundRect(mBody, radius, radius, mOutline);
        mCap.set(mBody.right + dp(1), top + mBodyHeight * 0.32f,
                mBody.right + mCapWidth, top + mBodyHeight * 0.68f);
        canvas.drawRoundRect(mCap, dp(1.5f), dp(1.5f), mOutline);

        mLevel.set(mBody.left, mBody.top,
                mBody.left + mBody.width() * fillFraction(mPercent), mBody.bottom);
        if (mLevel.width() > 0) {
            mFill.setColor(fillColor);
            canvas.save();
            canvas.clipRect(mLevel);
            canvas.drawRoundRect(mBody, radius, radius, mFill);
            canvas.restore();
        }

        final String label = mPercent < 0 ? "?" : Integer.toString(mPercent);
        // Center the digits' own ink, not the font's ascent and descent.
        mText.getTextBounds(label, 0, label.length(), mTextBounds);
        final float baseline = mBody.centerY() - mTextBounds.exactCenterY();
        mText.setColor(DesktopUiFactory.COLOR_BACKGROUND);
        canvas.drawText(label, mBody.centerX(), baseline, mText);

        if (mCharging) {
            final float boltLeft = mBody.left - dp(3) - mBoltWidth;
            final float boltTop = top - dp(1);
            final float h = mBodyHeight + dp(2);
            final float w = mBoltWidth;
            mBoltPath.reset();
            mBoltPath.moveTo(boltLeft + w * 0.62f, boltTop);
            mBoltPath.lineTo(boltLeft, boltTop + h * 0.58f);
            mBoltPath.lineTo(boltLeft + w * 0.46f, boltTop + h * 0.58f);
            mBoltPath.lineTo(boltLeft + w * 0.36f, boltTop + h);
            mBoltPath.lineTo(boltLeft + w, boltTop + h * 0.40f);
            mBoltPath.lineTo(boltLeft + w * 0.54f, boltTop + h * 0.40f);
            mBoltPath.close();
            mBolt.setColor(DesktopUiFactory.COLOR_ACCENT);
            canvas.drawPath(mBoltPath, mBolt);
        }
    }

    private float dp(final float value) {
        return value * mDensity;
    }
}
