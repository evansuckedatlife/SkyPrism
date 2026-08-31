package com.skyprism.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link TimeFormat}, including the locale trap that motivated hand-building the strings. */
class TimeFormatTest {

    @Nested
    @DisplayName("duration")
    class Duration {

        @Test
        @DisplayName("zero and sub-second values show seconds and tenths only")
        void subSecond() {
            assertEquals("0.0", TimeFormat.duration(0L));
            assertEquals("0.0", TimeFormat.duration(99L));
            assertEquals("0.4", TimeFormat.duration(400L));
            assertEquals("0.9", TimeFormat.duration(999L));
        }

        @Test
        @DisplayName("seconds appear unpadded until a minute is reached")
        void seconds() {
            assertEquals("1.0", TimeFormat.duration(1_000L));
            assertEquals("9.8", TimeFormat.duration(9_800L));
            assertEquals("59.9", TimeFormat.duration(59_999L));
        }

        @Test
        @DisplayName("minutes pad the seconds so the string stops changing width")
        void minutes() {
            assertEquals("1:00.0", TimeFormat.duration(60_000L));
            assertEquals("1:23.4", TimeFormat.duration(83_400L));
            assertEquals("59:59.9", TimeFormat.duration(3_599_999L));
        }

        @Test
        @DisplayName("hours pad both the minutes and the seconds")
        void hours() {
            assertEquals("1:00:00.0", TimeFormat.duration(3_600_000L));
            assertEquals("2:05:03.7", TimeFormat.duration(7_503_700L));
            assertEquals("25:00:00.0", TimeFormat.duration(90_000_000L));
        }

        @Test
        @DisplayName("fractions are truncated, never rounded up")
        void truncatesRatherThanRounds() {
            assertEquals("0.9", TimeFormat.duration(999L), "999ms must not read as a full second");
            assertEquals("1:23.4", TimeFormat.duration(83_499L));
            assertEquals("59.9", TimeFormat.duration(59_999L));
        }

        @Test
        @DisplayName("negative durations render the magnitude behind a minus sign")
        void negatives() {
            assertEquals("-0.4", TimeFormat.duration(-400L));
            assertEquals("-1:23.4", TimeFormat.duration(-83_400L));
            assertEquals("-2:05:03.7", TimeFormat.duration(-7_503_700L));
            assertEquals("-0.0", TimeFormat.duration(-1L), "a tiny negative still shows the sign");
        }

        @Test
        @DisplayName("extreme values do not overflow into a mangled string")
        void extremes() {
            String min = TimeFormat.duration(Long.MIN_VALUE);
            assertTrue(min.startsWith("-"), "Long.MIN_VALUE must still print as negative, was " + min);
            assertTrue(min.indexOf('-', 1) < 0, "only one minus sign, was " + min);
            String max = TimeFormat.duration(Long.MAX_VALUE);
            assertTrue(max.indexOf('-') < 0, "Long.MAX_VALUE must be positive, was " + max);
        }
    }

    @Nested
    @DisplayName("shortDuration")
    class Short {

        @Test
        @DisplayName("sub-second values are whole milliseconds")
        void milliseconds() {
            assertEquals("0ms", TimeFormat.shortDuration(0L));
            assertEquals("1ms", TimeFormat.shortDuration(1L));
            assertEquals("340ms", TimeFormat.shortDuration(340L));
            assertEquals("999ms", TimeFormat.shortDuration(999L));
        }

        @Test
        @DisplayName("seconds carry one truncated decimal")
        void seconds() {
            assertEquals("1.0s", TimeFormat.shortDuration(1_000L));
            assertEquals("1.2s", TimeFormat.shortDuration(1_250L));
            assertEquals("1.9s", TimeFormat.shortDuration(1_990L));
            assertEquals("59.9s", TimeFormat.shortDuration(59_999L));
        }

        @Test
        @DisplayName("minutes and hours take over as each unit fills")
        void minutesAndHours() {
            assertEquals("1.0m", TimeFormat.shortDuration(60_000L));
            assertEquals("1.5m", TimeFormat.shortDuration(90_000L));
            assertEquals("59.9m", TimeFormat.shortDuration(3_599_999L));
            assertEquals("1.0h", TimeFormat.shortDuration(3_600_000L));
            assertEquals("2.5h", TimeFormat.shortDuration(9_000_000L));
            assertEquals("25.0h", TimeFormat.shortDuration(90_000_000L), "hours are the largest unit, so they run on");
        }

        @Test
        @DisplayName("negative durations render the magnitude behind a minus sign")
        void negatives() {
            assertEquals("-340ms", TimeFormat.shortDuration(-340L));
            assertEquals("-1.2s", TimeFormat.shortDuration(-1_250L));
            assertEquals("-1.5m", TimeFormat.shortDuration(-90_000L));
            assertEquals("-2.5h", TimeFormat.shortDuration(-9_000_000L));
        }

        @Test
        @DisplayName("Long.MIN_VALUE does not overflow back into a positive magnitude")
        void extremes() {
            String min = TimeFormat.shortDuration(Long.MIN_VALUE);
            assertTrue(min.startsWith("-"), "was " + min);
            assertTrue(min.endsWith("h"), "was " + min);
            assertTrue(min.indexOf('-', 1) < 0, "only one minus sign, was " + min);
        }
    }

    @Test
    @DisplayName("the decimal separator is a dot in every locale, not the platform's comma")
    void formattingIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1:23.4", TimeFormat.duration(83_400L));
            assertEquals("1.2s", TimeFormat.shortDuration(1_250L));
        } finally {
            Locale.setDefault(original);
        }
    }
}
