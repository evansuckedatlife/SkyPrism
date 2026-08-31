package com.skyprism.core.util;

/**
 * Renders millisecond durations as the short strings a HUD can show.
 *
 * <p>Everything here is built by hand rather than with {@code String.format} or
 * {@code DecimalFormat} because both are locale-sensitive: on a machine set to a
 * European locale they would render the tenths separator as a comma, so the HUD
 * would read "1:23,4" for one player and "1:23.4" for another and any test
 * asserting the string would pass or fail depending on who ran it. A timer is a
 * fixed technical format, not prose, so it is assembled explicitly.</p>
 *
 * <p><b>Truncation, not rounding.</b> Fractions are cut, never rounded up, so a
 * running counter never displays a time it has not reached yet -- a stopwatch
 * that flicks to "1.0s" at 950ms looks broken.</p>
 *
 * <p><b>Negative input</b> is formatted as the magnitude with a leading minus
 * rather than being rejected or clamped to zero: a negative duration in this mod
 * means a deadline has passed or two readings arrived out of order, and showing
 * "-0.4s" makes that visible instead of hiding it behind a fake "0.0".</p>
 */
public final class TimeFormat {
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private TimeFormat() {
    }

    /**
     * A compact clock-style duration with tenths: {@code "0.4"}, {@code "9.8"},
     * {@code "1:23.4"}, {@code "2:05:03.7"}.
     *
     * <p>Units appear only once they are non-zero, so a sub-minute spin timer
     * stays two characters wide instead of padding out to "00:00:00.4", and the
     * lower units are zero-padded once a larger one is present so the string
     * does not change width while it counts down.</p>
     *
     * @param millis the duration; negative renders with a leading {@code '-'}
     * @return the formatted duration, never null
     */
    public static String duration(long millis) {
        boolean negative = millis < 0;
        long value = magnitude(millis);

        long hours = value / MILLIS_PER_HOUR;
        long minutes = (value % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE;
        long seconds = (value % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND;
        long tenths = (value % MILLIS_PER_SECOND) / 100L;

        StringBuilder out = new StringBuilder(12);
        if (negative) {
            out.append('-');
        }
        if (hours > 0) {
            out.append(hours).append(':');
            appendPadded(out, minutes);
            out.append(':');
            appendPadded(out, seconds);
        } else if (minutes > 0) {
            out.append(minutes).append(':');
            appendPadded(out, seconds);
        } else {
            out.append(seconds);
        }
        return out.append('.').append(tenths).toString();
    }

    /**
     * A single-unit approximation for inline labels: {@code "0ms"},
     * {@code "340ms"}, {@code "1.2s"}, {@code "3.5m"}, {@code "2.0h"}.
     *
     * <p>The unit is the largest one the value fills, and sub-second values are
     * whole milliseconds because a tenth of a millisecond is noise. This is for
     * "spun for 1.2s" style text; use {@link #duration(long)} for anything the
     * user watches tick.</p>
     *
     * @param millis the duration; negative renders with a leading {@code '-'}
     * @return the formatted duration, never null
     */
    public static String shortDuration(long millis) {
        boolean negative = millis < 0;
        long value = magnitude(millis);
        String sign = negative ? "-" : "";

        if (value < MILLIS_PER_SECOND) {
            return sign + value + "ms";
        }
        if (value < MILLIS_PER_MINUTE) {
            return sign + oneDecimal(value, MILLIS_PER_SECOND) + "s";
        }
        if (value < MILLIS_PER_HOUR) {
            return sign + oneDecimal(value, MILLIS_PER_MINUTE) + "m";
        }
        return sign + oneDecimal(value, MILLIS_PER_HOUR) + "h";
    }

    /**
     * The absolute value, with {@link Long#MIN_VALUE} clamped to
     * {@link Long#MAX_VALUE}.
     *
     * <p>{@code -Long.MIN_VALUE} overflows back to itself, which would print a
     * negative number after the minus sign. No real duration is anywhere near
     * this, so clamping by one millisecond is the honest cheap fix.</p>
     */
    private static long magnitude(long millis) {
        if (millis == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        return millis < 0 ? -millis : millis;
    }

    /** {@code value / unit} with one truncated fractional digit, e.g. 1250/1000 becomes "1.2". */
    private static String oneDecimal(long value, long unit) {
        long whole = value / unit;
        long tenth = (value % unit) / (unit / 10L);
        return whole + "." + tenth;
    }

    /** Appends {@code value} with a leading zero when it is a single digit. */
    private static void appendPadded(StringBuilder out, long value) {
        if (value < 10) {
            out.append('0');
        }
        out.append(value);
    }
}
