package com.skyprism.core.util;

/**
 * The single source of "what time is it" for every stateful part of the core.
 *
 * <p>Nearly all of this mod's logic is time-shaped: a slot machine spins for a
 * while and then eases to a stop, a chroma shimmer advances by a phase per
 * frame, a burrow chain expires. Reading the clock directly from those classes
 * would make them testable only by sleeping, which is slow and flaky. Taking a
 * {@code Clock} instead lets a test drive a two-second animation through its
 * entire arc in microseconds with {@link FixedClock}, and lets the Minecraft
 * adapters pass {@link SystemClock#INSTANCE} without any of the core knowing a
 * game exists.</p>
 *
 * <p>Implementations must be safe to call from any thread and must never return
 * a smaller value than a previous call returned.</p>
 */
@FunctionalInterface
public interface Clock {
    /**
     * The current reading in milliseconds.
     *
     * <p>This is a duration between two readings, not a date: only differences
     * are meaningful, and no implementation promises any relationship to the
     * Unix epoch or to the user's wall clock.</p>
     *
     * @return a non-decreasing millisecond timestamp
     */
    long millis();
}
