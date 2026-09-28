package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;

import org.junit.Test;

public final class CalendarMonthTest {
    @Test
    public void mondayWeekStartsWithTheLeadingDaysOfThePreviousMonth() {
        // September 2026 begins on a Tuesday.
        final CalendarMonth.Day[] days =
                new CalendarMonth(2026, Calendar.SEPTEMBER, Calendar.MONDAY).days();
        assertEquals(42, days.length);
        assertEquals(new CalendarMonth.Day(2026, Calendar.AUGUST, 31, false), days[0]);
        assertEquals(new CalendarMonth.Day(2026, Calendar.SEPTEMBER, 1, true), days[1]);
        assertEquals(new CalendarMonth.Day(2026, Calendar.SEPTEMBER, 30, true), days[30]);
        assertFalse(days[41].inMonth());
    }

    @Test
    public void sundayWeekShiftsTheSameMonth() {
        final CalendarMonth.Day[] days =
                new CalendarMonth(2026, Calendar.SEPTEMBER, Calendar.SUNDAY).days();
        assertEquals(new CalendarMonth.Day(2026, Calendar.AUGUST, 30, false), days[0]);
        assertEquals(new CalendarMonth.Day(2026, Calendar.SEPTEMBER, 1, true), days[2]);
    }

    @Test
    public void monthStartingOnTheFirstWeekdayHasNoLeadingDays() {
        // June 2026 begins on a Monday.
        final CalendarMonth.Day[] days =
                new CalendarMonth(2026, Calendar.JUNE, Calendar.MONDAY).days();
        assertTrue(days[0].inMonth());
        assertEquals(1, days[0].day());
    }

    @Test
    public void navigationCrossesYears() {
        final CalendarMonth december = new CalendarMonth(2026, Calendar.JANUARY, Calendar.MONDAY)
                .plusMonths(-1);
        assertEquals(2025, december.year);
        assertEquals(Calendar.DECEMBER, december.month);
        final CalendarMonth january = december.plusMonths(1);
        assertEquals(2026, january.year);
        assertEquals(Calendar.JANUARY, january.month);
    }

    @Test
    public void weekdaysFollowTheLocaleFirstDay() {
        assertArrayEquals(new int[] {
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        }, new CalendarMonth(2026, 0, Calendar.MONDAY).weekdays());
        assertEquals(Calendar.SATURDAY,
                new CalendarMonth(2026, 0, Calendar.SATURDAY).weekdays()[0]);
    }

    @Test
    public void leapFebruaryIsComplete() {
        final CalendarMonth.Day[] days =
                new CalendarMonth(2028, Calendar.FEBRUARY, Calendar.MONDAY).days();
        int inMonth = 0;
        for (final CalendarMonth.Day day : days) {
            if (day.inMonth()) {
                inMonth++;
            }
        }
        assertEquals(29, inMonth);
    }
}
