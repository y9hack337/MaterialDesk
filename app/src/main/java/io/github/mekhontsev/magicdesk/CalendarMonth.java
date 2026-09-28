package io.github.mekhontsev.magicdesk;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * One month laid out in six locale-ordered weeks for the calendar flyout.
 * Pure date arithmetic; the panel owns presentation and the current date.
 */
final class CalendarMonth {
    static final int WEEKS = 6;
    static final int DAYS_PER_WEEK = 7;

    /** A grid cell; {@code month} is 0-based as in {@link Calendar}. */
    record Day(int year, int month, int day, boolean inMonth) {
        boolean sameDate(final int otherYear, final int otherMonth, final int otherDay) {
            return year == otherYear && month == otherMonth && day == otherDay;
        }
    }

    final int year;
    final int month;
    final int firstDayOfWeek;

    CalendarMonth(final int year, final int month, final int firstDayOfWeek) {
        final GregorianCalendar normalized = new GregorianCalendar(year, month, 1);
        this.year = normalized.get(Calendar.YEAR);
        this.month = normalized.get(Calendar.MONTH);
        this.firstDayOfWeek = firstDayOfWeek;
    }

    CalendarMonth plusMonths(final int months) {
        return new CalendarMonth(year, month + months, firstDayOfWeek);
    }

    /** Weekday constants in display order, starting at the locale's first day. */
    int[] weekdays() {
        final int[] order = new int[DAYS_PER_WEEK];
        for (int index = 0; index < DAYS_PER_WEEK; index++) {
            order[index] = (firstDayOfWeek - 1 + index) % DAYS_PER_WEEK + 1;
        }
        return order;
    }

    /** Six full weeks covering the month, with leading and trailing days. */
    Day[] days() {
        final GregorianCalendar cursor = new GregorianCalendar(year, month, 1);
        final int lead = (cursor.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek
                + DAYS_PER_WEEK) % DAYS_PER_WEEK;
        cursor.add(Calendar.DAY_OF_MONTH, -lead);
        final Day[] days = new Day[WEEKS * DAYS_PER_WEEK];
        for (int index = 0; index < days.length; index++) {
            days[index] = new Day(
                    cursor.get(Calendar.YEAR),
                    cursor.get(Calendar.MONTH),
                    cursor.get(Calendar.DAY_OF_MONTH),
                    cursor.get(Calendar.MONTH) == month);
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return days;
    }
}
