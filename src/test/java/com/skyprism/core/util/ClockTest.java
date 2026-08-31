package com.skyprism.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link Clock} abstraction and its two implementations.
 *
 * <p>Nothing here sleeps. {@link SystemClock} is checked for the properties that
 * matter -- non-negative and non-decreasing -- rather than for any particular
 * elapsed value, because asserting on real elapsed time is exactly the flakiness
 * {@link FixedClock} exists to avoid.</p>
 */
class ClockTest {

    @Nested
    @DisplayName("FixedClock")
    class Fixed {

        @Test
        @DisplayName("reads back its start value and defaults to zero")
        void startValue() {
            assertEquals(0L, new FixedClock().millis());
            assertEquals(5_000L, new FixedClock(5_000L).millis());
            assertEquals(-250L, new FixedClock(-250L).millis(), "a negative origin is allowed");
        }

        @Test
        @DisplayName("advance moves the reading forward and accumulates")
        void advanceAccumulates() {
            FixedClock clock = new FixedClock(1_000L);
            clock.advance(250L);
            assertEquals(1_250L, clock.millis());
            clock.advance(750L);
            assertEquals(2_000L, clock.millis());
            clock.advance(0L);
            assertEquals(2_000L, clock.millis(), "advancing by zero is a no-op, not an error");
        }

        @Test
        @DisplayName("advance returns this so a test can chain its arrange step")
        void advanceIsChainable() {
            FixedClock clock = new FixedClock(0L);
            assertSame(clock, clock.advance(10L));
            assertSame(clock, clock.set(99L));
            assertEquals(120L, new FixedClock(100L).advance(10L).advance(10L).millis());
        }

        @Test
        @DisplayName("advancing backwards is rejected, because Clock promises monotonicity")
        void advanceBackwardsThrows() {
            FixedClock clock = new FixedClock(1_000L);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> clock.advance(-1L));
            assertTrue(thrown.getMessage().contains("backwards"), "the message must say why");
            assertEquals(1_000L, clock.millis(), "a rejected advance must not have moved the clock");
        }

        @Test
        @DisplayName("advancing past Long.MAX_VALUE is rejected instead of wrapping the clock backwards")
        void advanceOverflowIsRejected() {
            FixedClock clock = new FixedClock(Long.MAX_VALUE);
            // Two's-complement addition wraps: this used to land on Long.MIN_VALUE, i.e. the clock
            // jumping backwards by the width of the whole type -- the exact state advance() promises
            // can never happen.
            assertThrows(ArithmeticException.class, () -> clock.advance(1L));
            assertEquals(Long.MAX_VALUE, clock.millis(), "a rejected advance must not have moved the clock");

            FixedClock near = new FixedClock(Long.MAX_VALUE - 10L);
            assertThrows(ArithmeticException.class, () -> near.advance(1_000L));
            assertEquals(Long.MAX_VALUE - 10L, near.millis());

            // The exact boundary is still allowed: landing on MAX_VALUE is a legal reading.
            assertEquals(Long.MAX_VALUE, new FixedClock(Long.MAX_VALUE - 10L).advance(10L).millis());
        }

        @Test
        @DisplayName("the overflow guard does not fire on a huge but legal advance from a negative origin")
        void hugeAdvanceFromNegativeOriginIsAllowed() {
            // Long.MIN_VALUE + Long.MAX_VALUE is -1: legal, and a naive guard would reject it.
            assertEquals(-1L, new FixedClock(Long.MIN_VALUE).advance(Long.MAX_VALUE).millis());
            assertEquals(0L, new FixedClock(-5_000L).advance(5_000L).millis());
        }

        @Test
        @DisplayName("concurrent advances from several threads do not lose updates")
        void concurrentAdvancesDoNotLoseUpdates() throws Exception {
            int threads = 8;
            int perThread = 20_000;
            FixedClock shared = new FixedClock(0L);
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(threads);
            var pool = Executors.newFixedThreadPool(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                shared.advance(1L);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(finished.await(30, TimeUnit.SECONDS), "the advancing threads did not finish");
            } finally {
                pool.shutdownNow();
            }
            // "now += ms" on a volatile field is a read-modify-write, not an atomic one. Against that
            // implementation this lands around a third of the expected total.
            assertEquals((long) threads * perThread, shared.millis(),
                    "advances were lost to a race; the reading is not updated atomically");
        }

        @Test
        @DisplayName("set is the deliberate escape hatch and may go in either direction")
        void setGoesEitherWay() {
            FixedClock clock = new FixedClock(1_000L);
            clock.set(9_000L);
            assertEquals(9_000L, clock.millis());
            clock.set(-1L);
            assertEquals(-1L, clock.millis());
        }

        @Test
        @DisplayName("toString names the class and the reading, for readable test failures")
        void readableToString() {
            assertEquals("FixedClock[42ms]", new FixedClock(42L).toString());
        }
    }

    @Nested
    @DisplayName("SystemClock")
    class Real {

        @Test
        @DisplayName("the shared instance is genuinely the only way to get one")
        void sharedInstance() {
            // Asserting INSTANCE == INSTANCE is a tautology that would hold for any field at all.
            // The property worth pinning is that no caller can make a second clock with a different
            // origin, which is what would make two modules' elapsed times incomparable.
            assertNotNull(SystemClock.INSTANCE);
            assertEquals(0, SystemClock.class.getConstructors().length,
                    "SystemClock must expose no public constructor");
            assertTrue(Modifier.isFinal(SystemClock.class.getModifiers()), "SystemClock must be final");
            assertTrue(Clock.class.isInstance(SystemClock.INSTANCE));
        }

        @Test
        @DisplayName("the clock actually advances, and no faster than real elapsed time")
        void clockAdvancesWithRealTime() {
            Clock clock = SystemClock.INSTANCE;
            // A clock stuck at a constant passes every monotonicity check ever written, so measure
            // progress against an independent reading instead. Spin rather than sleep, with a hard
            // bound so a broken clock fails the assertion instead of hanging the suite.
            long originNanos = System.nanoTime();
            long start = clock.millis();
            long now = start;
            while (now - start < 3L && System.nanoTime() - originNanos < 2_000_000_000L) {
                now = clock.millis();
            }
            long independentElapsed = (System.nanoTime() - originNanos) / 1_000_000L;
            assertTrue(now - start >= 3L, "the clock never advanced in two seconds of spinning");
            assertTrue(now - start <= independentElapsed + 2L,
                    "the clock ran ahead of real time: reported " + (now - start)
                            + "ms against an independent " + independentElapsed + "ms");
        }

        @Test
        @DisplayName("readings are non-negative and never go backwards")
        void monotonicAndNonNegative() {
            Clock clock = SystemClock.INSTANCE;
            long previous = clock.millis();
            assertTrue(previous >= 0L, "the first reading must not be negative, was " + previous);

            for (int i = 0; i < 100_000; i++) {
                long now = clock.millis();
                assertTrue(now >= previous, "clock went backwards: " + previous + " then " + now);
                previous = now;
            }
        }

        @Test
        @DisplayName("elapsed time between two readings is measurable and sane")
        void elapsedTimeIsMeasurable() {
            Clock clock = SystemClock.INSTANCE;
            long start = clock.millis();
            // Busy work rather than a sleep: the harness must stay in the millisecond range.
            long sink = 0L;
            for (int i = 0; i < 20_000_000; i++) {
                sink += i;
            }
            long elapsed = clock.millis() - start;
            assertTrue(sink != Long.MIN_VALUE, "keep the loop from being optimised away");
            assertTrue(elapsed >= 0L, "elapsed time cannot be negative, was " + elapsed);
            assertTrue(elapsed < 60_000L, "20M additions should not take a minute, took " + elapsed + "ms");
        }
    }

    @Test
    @DisplayName("Clock has exactly one abstract method, so a test can always inline one as a lambda")
    void clockIsFunctional() {
        Clock always = () -> 7L;
        assertEquals(7L, always.millis());
        // The lambda above only proves the interface compiles as functional today. Pin the reason:
        // adding a second abstract method would break every inline stub across the whole core.
        long abstractMethods = java.util.Arrays.stream(Clock.class.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .count();
        assertEquals(1L, abstractMethods, "Clock must stay a single-method interface");
    }
}
