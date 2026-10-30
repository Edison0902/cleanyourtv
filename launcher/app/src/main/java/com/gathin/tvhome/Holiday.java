package com.gathin.tvhome;

import android.content.res.Resources;

import java.util.Calendar;

/**
 * The festival for a day, shown after the lunar date under the clock: the fixed Gregorian ones, the
 * solar-term Qingming (the sun reaching 15° — the 4th or the 5th of April in this century), the
 * lunar ones off the lunar date, and New Year's Eve as the day before the first of the first.
 */
final class Holiday {

    static final int NONE = 0, NEW_YEAR = 1, QINGMING = 2, LABOUR = 3, NATIONAL = 4, SPRING = 5,
            LANTERN = 6, DRAGON_BOAT = 7, QIXI = 8, MID_AUTUMN = 9, DOUBLE_NINTH = 10, LABA = 11, EVE = 12;

    private Holiday() {}

    /** Which festival falls on this day, or NONE. */
    static int code(Calendar day, int utcOffset) {
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, d = day.get(Calendar.DAY_OF_MONTH);
        if (m == 4 && (d == 4 || d == 5 || d == 6)) {
            // Qingming is the day the sun crosses 15°: the crossing may fall at any hour, so a day
            // is Qingming when the *next* morning's longitude has already passed 15°.
            double deg5 = Lunar.sunLongitudeDegrees(Lunar.jdFromDate(5, 4, y), utcOffset);
            double deg6 = Lunar.sunLongitudeDegrees(Lunar.jdFromDate(6, 4, y), utcOffset);
            double deg7 = Lunar.sunLongitudeDegrees(Lunar.jdFromDate(7, 4, y), utcOffset);
            int qm = deg5 > 15 ? 4 : (deg6 > 15 ? 5 : 6);
            if (d == qm) return QINGMING;
        }
        if (m == 1 && d == 1) return NEW_YEAR;
        if (m == 5 && d == 1) return LABOUR;
        if (m == 10 && d == 1) return NATIONAL;
        int[] l = Lunar.fromSolar(d, m, y, utcOffset);
        if (l[3] == 1) return NONE;  // a leap month carries no festivals
        int lm = l[1], ld = l[0];
        if (lm == 1 && ld == 1) return SPRING;
        if (lm == 1 && ld == 15) return LANTERN;
        if (lm == 5 && ld == 5) return DRAGON_BOAT;
        if (lm == 7 && ld == 7) return QIXI;
        if (lm == 8 && ld == 15) return MID_AUTUMN;
        if (lm == 9 && ld == 9) return DOUBLE_NINTH;
        if (lm == 12 && ld == 8) return LABA;
        if (lm == 12) {
            // New Year's Eve: the last day of the last month — tomorrow opens the first.
            Calendar tom = (Calendar) day.clone();
            tom.add(Calendar.DAY_OF_MONTH, 1);
            int[] t = Lunar.fromSolar(tom.get(Calendar.DAY_OF_MONTH), tom.get(Calendar.MONTH) + 1,
                    tom.get(Calendar.YEAR), utcOffset);
            if (t[1] == 1 && t[0] == 1 && t[3] == 0) return EVE;
        }
        return NONE;
    }

    /** The festival's name, or null. */
    static String name(Resources res, int code) {
        switch (code) {
            case NEW_YEAR: return res.getString(R.string.fest_new_year);
            case QINGMING: return res.getString(R.string.fest_qingming);
            case LABOUR: return res.getString(R.string.fest_labour);
            case NATIONAL: return res.getString(R.string.fest_national);
            case SPRING: return res.getString(R.string.fest_spring);
            case LANTERN: return res.getString(R.string.fest_lantern);
            case DRAGON_BOAT: return res.getString(R.string.fest_dragon_boat);
            case QIXI: return res.getString(R.string.fest_qixi);
            case MID_AUTUMN: return res.getString(R.string.fest_mid_autumn);
            case DOUBLE_NINTH: return res.getString(R.string.fest_double_ninth);
            case LABA: return res.getString(R.string.fest_laba);
            case EVE: return res.getString(R.string.fest_new_years_eve);
            default: return null;
        }
    }
}
