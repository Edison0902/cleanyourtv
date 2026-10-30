package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;

import org.junit.Test;

/** The festival table, as JVM assertions: the astronomy runs without Android. */
public class HolidayTest {

    private Calendar day(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.set(y, m - 1, d, 12, 0, 0);
        return c;
    }

    @Test
    public void gregorianFixed() {
        assertEquals(Holiday.NEW_YEAR, Holiday.code(day(2027, 1, 1), 8));
        assertEquals(Holiday.LABOUR, Holiday.code(day(2026, 5, 1), 8));
        assertEquals(Holiday.NATIONAL, Holiday.code(day(2025, 10, 1), 8));
    }

    @Test
    public void qingmingIsTheSolarTerm() {
        // 2026-04-05 and 2025-04-04: the sun crosses 15° on those days (checked against the published calendar).
        assertEquals(Holiday.QINGMING, Holiday.code(day(2026, 4, 5), 8));
        assertEquals(Holiday.QINGMING, Holiday.code(day(2025, 4, 4), 8));
        assertEquals(Holiday.NONE, Holiday.code(day(2026, 4, 4), 8));
        assertEquals(Holiday.NONE, Holiday.code(day(2025, 4, 5), 8));
    }

    @Test
    public void lunarFestivals() {
        // 2026-02-17 is the first of the first; 2026-09-25 the 15th of the 8th; 2026-06-19 the 5th of the 5th.
        assertEquals(Holiday.SPRING, Holiday.code(day(2026, 2, 17), 8));
        assertEquals(Holiday.MID_AUTUMN, Holiday.code(day(2026, 9, 25), 8));
        assertEquals(Holiday.DRAGON_BOAT, Holiday.code(day(2026, 6, 19), 8));
        assertEquals(Holiday.DOUBLE_NINTH, Holiday.code(day(2025, 10, 29), 8));
    }

    @Test
    public void newYearsEveIsTheDayBeforeTheFirst() {
        // 2027-02-05: the eve of 2027-02-06, the first of the first.
        assertEquals(Holiday.EVE, Holiday.code(day(2027, 2, 5), 8));
        assertEquals(Holiday.NONE, Holiday.code(day(2026, 10, 31), 8));
    }
}
