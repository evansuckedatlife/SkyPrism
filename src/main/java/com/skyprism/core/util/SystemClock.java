package com.skyprism.core.util;

/**
 * The real clock: elapsed milliseconds measured from {@link System#nanoTime()}.
 *
 * <p><b>Why not {@code System.currentTimeMillis()}:</b> the wall clock can jump.
 * NTP corrections, a laptop resuming from sleep and manual timezone or time
 * changes all move it, forwards or backwards, at arbitrary moments. A backwards
 * jump mid-spin would hand the slot machine a negative elapsed time and a
 * forwards jump would teleport an animation to its end. {@code nanoTime} has no
 * relationship to the wall clock at all -- it only ever counts up -- so every
 * duration this clock reports is real elapsed time.</p>
 *
 * <p><b>The reading is not a date.</b> {@link #millis()} counts from the moment
 * this class was first loaded, so the first reading is near zero. Do not persist
 * it, compare it against {@code System.currentTimeMillis()}, or show it to a
 * user as a time of day; use it only to subtract one reading from another.</p>
 *
 * <p>Stateless and thread-safe; use the shared {@link #INSTANCE}.</p>
 */
public final class SystemClock implements Clock {
    /** The shared instance. There is nothing to configure, so there is no reason for a second one. */
    public static final SystemClock INSTANCE = new SystemClock();

    /** Reading taken at class initialisation, so {@link #millis()} starts near zero. */
    private static final long ORIGIN_NANOS = System.nanoTime();

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private SystemClock() {
    }

    /**
     * Milliseconds elapsed since this class was loaded, truncated towards zero.
     *
     * <p>Monotonic by construction: the subtraction is the documented way to
     * compare {@code nanoTime} readings and stays correct across the counter's
     * wraparound, and integer division preserves ordering, so a later call can
     * never return less than an earlier one.</p>
     *
     * @return a non-decreasing, non-negative millisecond reading
     */
    @Override
    public long millis() {
        return (System.nanoTime() - ORIGIN_NANOS) / NANOS_PER_MILLI;
    }

    @Override
    public String toString() {
        return "SystemClock[" + millis() + "ms]";
    }
}
