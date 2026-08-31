package com.skyprism.core.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock that only moves when a test tells it to.
 *
 * <p>This is the reason {@link Clock} exists. With it a test can assert exactly
 * what a reel shows 850ms into a spin, or that a burrow times out on the tick
 * after its deadline rather than the tick before, without sleeping and without
 * any tolerance windows -- the two classic sources of flaky animation tests.</p>
 *
 * <p>It lives in main rather than test sources so that every module's tests can
 * share one test double instead of each writing its own slightly different one.
 * Nothing in production should construct it.</p>
 *
 * <p><b>Thread safety.</b> The reading is held in an {@link AtomicLong} rather
 * than a {@code volatile long}. That is not paranoia: {@code now += ms} on a
 * volatile field is a read-modify-write, not an atomic one, so two threads
 * advancing the same clock silently lose updates -- measured at roughly
 * two-thirds of 160,000 advances lost across eight threads. {@link Clock}'s
 * contract says implementations must be safe to call from any thread, and a
 * test that drives a parse thread and a render thread against one shared clock
 * would otherwise fail intermittently for a reason that has nothing to do with
 * the code under test.</p>
 */
public final class FixedClock implements Clock {
    private final AtomicLong now;

    /** A clock reading zero. */
    public FixedClock() {
        this(0L);
    }

    /**
     * A clock reading {@code start}.
     *
     * @param start the initial reading in milliseconds; may be any value, including negative,
     *              so a test can start from an arbitrary point on the timeline
     */
    public FixedClock(long start) {
        this.now = new AtomicLong(start);
    }

    @Override
    public long millis() {
        return now.get();
    }

    /**
     * Moves the clock forward, atomically.
     *
     * <p>Rejects a negative step: {@link Clock} promises monotonicity, and a test
     * that quietly rewound the clock would be exercising a state production code
     * can never reach. Use {@link #set(long)} if a jump backwards is genuinely
     * what is being tested.</p>
     *
     * <p>It also rejects a step that would carry the reading past
     * {@link Long#MAX_VALUE}. Two's-complement addition wraps silently, so
     * {@code new FixedClock(Long.MAX_VALUE).advance(1)} used to land on
     * {@link Long#MIN_VALUE} -- the clock jumping backwards by the width of the
     * whole type, which is exactly the state the negative-step check exists to
     * prevent. Failing loudly at the boundary keeps the monotonicity promise
     * unconditional rather than true only for values a test happens to pick.</p>
     *
     * @param ms milliseconds to advance, must not be negative
     * @return this clock, so calls can be chained into a test's arrange step
     * @throws IllegalArgumentException if {@code ms} is negative
     * @throws ArithmeticException if the resulting reading would overflow {@code long}
     */
    public FixedClock advance(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("cannot advance a clock backwards by " + ms + "ms; use set(long)");
        }
        now.getAndUpdate(current -> {
            // ms is non-negative here, so an overflow is only possible from a positive reading,
            // and the guard itself cannot overflow because both sides stay non-negative.
            if (current > 0 && ms > Long.MAX_VALUE - current) {
                throw new ArithmeticException(
                        "advancing " + current + "ms by " + ms + "ms would overflow long and wrap the clock backwards");
            }
            return current + ms;
        });
        return this;
    }

    /**
     * Sets the reading outright, in either direction.
     *
     * <p>The deliberate escape hatch from {@link #advance(long)}'s monotonicity
     * check, for the rare test that wants to prove a consumer survives a clock
     * that misbehaves.</p>
     *
     * @param ms the new reading in milliseconds
     * @return this clock, so calls can be chained
     */
    public FixedClock set(long ms) {
        now.set(ms);
        return this;
    }

    @Override
    public String toString() {
        return "FixedClock[" + now.get() + "ms]";
    }
}
