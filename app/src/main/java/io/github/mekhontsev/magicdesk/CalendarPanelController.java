package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.StateListDrawable;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;

/**
 * Material calendar flyout opened from the taskbar clock: the full date, a
 * navigable month grid in the locale's week order, and Today / Open calendar.
 */
final class CalendarPanelController {
    private final Context mContext;
    private final DesktopUiFactory mUi;
    private final Runnable mHidePanels;
    private final Runnable mCaptureInteractionStack;
    private final Runnable mOpenCalendar;
    private final Runnable mPanelUnavailable;

    private LinearLayout mPanel;
    private TextView mHeadline;
    private TextView mMonthTitle;
    private LinearLayout mWeekdays;
    private LinearLayout mWeeks;
    private CalendarMonth mMonth;
    /** Selected date as {year, month, day}; today until the user picks a day. */
    private int[] mSelected;

    CalendarPanelController(
            final Context context,
            final DesktopUiFactory ui,
            final Runnable hidePanels,
            final Runnable captureInteractionStack,
            final Runnable openCalendar,
            final Runnable panelUnavailable) {
        mContext = context;
        mUi = ui;
        mHidePanels = hidePanels;
        mCaptureInteractionStack = captureInteractionStack;
        mOpenCalendar = openCalendar;
        mPanelUnavailable = panelUnavailable;
    }

    LinearLayout createPanel() {
        final LinearLayout panel = new LinearLayout(mContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        panel.setBackground(mUi.panelSurface());
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        mHeadline = new TextView(mContext);
        mHeadline.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mHeadline.setTextSize(22);
        mHeadline.setSingleLine(true);
        mHeadline.setPadding(dp(4), 0, dp(4), dp(12));
        panel.addView(mHeadline, matchWrap());

        final LinearLayout monthRow = new LinearLayout(mContext);
        monthRow.setGravity(Gravity.CENTER_VERTICAL);
        mMonthTitle = new TextView(mContext);
        mMonthTitle.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mMonthTitle.setTextSize(15);
        mMonthTitle.setTypeface(DesktopUiFactory.medium());
        mMonthTitle.setPadding(dp(4), 0, 0, 0);
        monthRow.addView(mMonthTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final ImageButton previous = mUi.menuIconButton(
                R.drawable.ic_chevron_right, R.string.calendar_previous_month);
        previous.setRotation(180f);
        previous.setOnClickListener(view -> showMonth(mMonth.plusMonths(-1)));
        monthRow.addView(previous, new LinearLayout.LayoutParams(dp(40), dp(40)));
        final ImageButton next = mUi.menuIconButton(
                R.drawable.ic_chevron_right, R.string.calendar_next_month);
        next.setOnClickListener(view -> showMonth(mMonth.plusMonths(1)));
        monthRow.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));
        panel.addView(monthRow, matchWrap());

        mWeekdays = new LinearLayout(mContext);
        final LinearLayout.LayoutParams weekdaysParams = matchWrap();
        weekdaysParams.setMargins(0, dp(8), 0, dp(4));
        panel.addView(mWeekdays, weekdaysParams);

        mWeeks = new LinearLayout(mContext);
        mWeeks.setOrientation(LinearLayout.VERTICAL);
        panel.addView(mWeeks, matchWrap());

        final LinearLayout actions = new LinearLayout(mContext);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        final Button today = mUi.actionButton(R.string.action_today, DesktopUiFactory.COLOR_PANEL_ALT);
        today.setOnClickListener(view -> {
            mSelected = null;
            showMonth(currentMonth());
        });
        actions.addView(today, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        final Button open = mUi.actionButton(R.string.action_open_calendar, DesktopUiFactory.COLOR_ACCENT);
        open.setOnClickListener(view -> mOpenCalendar.run());
        final LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        openParams.setMarginStart(dp(8));
        actions.addView(open, openParams);
        final LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(actions, actionsParams);
        mPanel = panel;
        return panel;
    }

    void toggle(
            final DesktopPanelWindowController panels,
            final Rect contentBounds) {
        if (panels == null || mPanel == null) {
            return;
        }
        if (panels.isShowing(mPanel)) {
            mHidePanels.run();
            return;
        }
        mCaptureInteractionStack.run();
        // The date may have changed since the flyout was last open.
        mSelected = null;
        showMonth(currentMonth());
        final int width = Math.max(1, Math.min(dp(360), contentBounds.width() - dp(16)));
        final int maxHeight = Math.max(1, contentBounds.height() - dp(16));
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        final int height = Math.min(maxHeight, mPanel.getMeasuredHeight());
        if (!panels.show(
                mPanel, ShellPanelPlacement.anchored(width, height,
                        ShellSurface.RIGHT | ShellSurface.BOTTOM, 0, 0, dp(8), dp(8)),
                false, "MagicDesk calendar")) {
            mPanelUnavailable.run();
        }
    }

    private CalendarMonth currentMonth() {
        final Calendar now = Calendar.getInstance();
        return new CalendarMonth(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.getFirstDayOfWeek());
    }

    private void showMonth(final CalendarMonth month) {
        mMonth = month;
        final Calendar now = Calendar.getInstance();
        final Locale locale = Locale.getDefault();
        mHeadline.setText(DateFormat.format(
                DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), now));
        final Calendar first = Calendar.getInstance();
        first.clear();
        first.set(month.year, month.month, 1);
        mMonthTitle.setText(DateFormat.format(
                DateFormat.getBestDateTimePattern(locale, "LLLLyyyy"), first));

        mWeekdays.removeAllViews();
        final String[] names = new DateFormatSymbols(locale).getShortWeekdays();
        for (final int weekday : month.weekdays()) {
            final TextView label = new TextView(mContext);
            final String name = names[weekday];
            label.setText(name.length() > 2 ? name.substring(0, 2) : name);
            label.setTextColor(DesktopUiFactory.COLOR_MUTED);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            mWeekdays.addView(label, new LinearLayout.LayoutParams(
                    0, dp(28), 1));
        }

        mWeeks.removeAllViews();
        final CalendarMonth.Day[] days = month.days();
        final int todayYear = now.get(Calendar.YEAR);
        final int todayMonth = now.get(Calendar.MONTH);
        final int todayDay = now.get(Calendar.DAY_OF_MONTH);
        for (int week = 0; week < CalendarMonth.WEEKS; week++) {
            final LinearLayout row = new LinearLayout(mContext);
            for (int column = 0; column < CalendarMonth.DAYS_PER_WEEK; column++) {
                final CalendarMonth.Day day =
                        days[week * CalendarMonth.DAYS_PER_WEEK + column];
                row.addView(dayCell(day,
                        day.sameDate(todayYear, todayMonth, todayDay),
                        mSelected != null && day.sameDate(mSelected[0], mSelected[1], mSelected[2])),
                        new LinearLayout.LayoutParams(0, dp(40), 1));
            }
            mWeeks.addView(row, matchWrap());
        }
    }

    private View dayCell(
            final CalendarMonth.Day day,
            final boolean today,
            final boolean selected) {
        final TextView cell = new TextView(mContext);
        cell.setText(String.valueOf(day.day()));
        cell.setGravity(Gravity.CENTER);
        cell.setTextSize(13);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setDefaultFocusHighlightEnabled(false);
        final int circle = dp(DesktopUiFactory.SHAPE_FULL_DP);
        final int text;
        final StateListDrawable background = new StateListDrawable();
        if (today) {
            // Material date picker: today is the filled primary circle.
            text = DesktopUiFactory.COLOR_ON_ACCENT;
            cell.setTypeface(DesktopUiFactory.medium());
            background.addState(new int[0], DesktopUiFactory.filled(
                    DesktopUiFactory.COLOR_ACCENT, circle));
        } else {
            text = day.inMonth() ? DesktopUiFactory.COLOR_TEXT
                    : DesktopUiFactory.withAlpha(DesktopUiFactory.COLOR_TEXT, 0.38f);
            background.addState(new int[] {android.R.attr.state_pressed},
                    DesktopUiFactory.filled(DesktopUiFactory.withAlpha(
                            DesktopUiFactory.COLOR_TEXT, 0.12f), circle));
            background.addState(new int[] {android.R.attr.state_hovered},
                    DesktopUiFactory.filled(DesktopUiFactory.withAlpha(
                            DesktopUiFactory.COLOR_TEXT, 0.08f), circle));
            background.addState(new int[0], selected
                    ? mUi.rounded(Color.TRANSPARENT, circle, DesktopUiFactory.COLOR_ACCENT)
                    : DesktopUiFactory.filled(Color.TRANSPARENT, circle));
        }
        cell.setTextColor(text);
        // Keep the circle round inside a wider column.
        cell.setBackground(new android.graphics.drawable.InsetDrawable(
                background, dp(4), 0, dp(4), 0) {
            @Override
            protected void onBoundsChange(final Rect bounds) {
                final int inset = Math.max(0, (bounds.width() - bounds.height()) / 2);
                getDrawable().setBounds(bounds.left + inset, bounds.top,
                        bounds.right - inset, bounds.bottom);
            }
        });
        cell.setOnClickListener(view -> {
            mSelected = new int[] {day.year(), day.month(), day.day()};
            showMonth(day.inMonth() ? mMonth
                    : new CalendarMonth(day.year(), day.month(), mMonth.firstDayOfWeek));
        });
        return cell;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
