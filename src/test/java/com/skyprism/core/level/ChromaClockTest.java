package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for {@link ChromaClock}. Every case here would be impossible to
 * write if the class read the wall clock itself, which is exactly why it does not.
 */
class ChromaClockTest {

    private static ChromaClock oneHertz() {
        return new ChromaClock(1.0, 1.0, 0.5);
    }

    @Test
    @DisplayName("the same inputs always give the same colour")
    void deterministic() {
        var clock = oneHertz();
        int first = clock.colorAt(1_234L, 17);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, clock.colorAt(1_234L, 17));
        }
        assertEquals(clock.colorAt(0L, 0), oneHertz().colorAt(0L, 0), "two equal clocks agree");
    }

    @Test
    @DisplayName("colorAt(t) equals colorAt(t + one period), bit for bit")
    void cyclicAtOnePeriod() {
        var clock = oneHertz();
        long period = (long) clock.periodMillis();
        assertEquals(1000L, period);
        for (long t = 0; t < period; t += 37) {
            assertEquals(clock.colorAt(t, 0), clock.colorAt(t + period, 0), "drift at t=" + t);
            assertEquals(clock.colorAt(t, 0), clock.colorAt(t + period * 1_000_000L, 0),
                "drift far along the timeline at t=" + t);
        }
    }

    @Test
    @DisplayName("cyclicity holds for a faster clock too")
    void cyclicAtTwoHertz() {
        var clock = new ChromaClock(2.0, 0.9, 0.6);
        long period = (long) clock.periodMillis();
        assertEquals(500L, period);
        for (long t = 0; t < period; t += 13) {
            assertEquals(clock.colorAt(t, 90), clock.colorAt(t + period, 90), "drift at t=" + t);
        }
    }

    @Test
    @DisplayName("negative timestamps wrap into the cycle rather than mirroring it")
    void negativeTimestampsWrap() {
        var clock = oneHertz();
        assertEquals(clock.colorAt(250L, 0), clock.colorAt(-750L, 0));
        assertEquals(clock.colorAt(0L, 0), clock.colorAt(-1000L, 0));
    }

    @Test
    @DisplayName("the phase offset is in degrees and wraps at 360")
    void phaseOffsetWraps() {
        var clock = oneHertz();
        assertEquals(clock.colorAt(400L, 0), clock.colorAt(400L, 360));
        assertEquals(clock.colorAt(400L, 45), clock.colorAt(400L, 405));
        assertEquals(clock.colorAt(400L, 45), clock.colorAt(400L, -315));
    }

    @Test
    @DisplayName("a phase offset of 180 degrees is the same as advancing half a period")
    void phaseOffsetEqualsTimeShift() {
        var clock = oneHertz();
        assertEquals(clock.colorAt(500L, 0), clock.colorAt(0L, 180));
    }

    @Test
    @DisplayName("the sweep actually visits many different colours")
    void sweepIsColourful() {
        var clock = oneHertz();
        var seen = new HashSet<Integer>();
        for (long t = 0; t < 1000; t += 10) {
            seen.add(clock.colorAt(t, 0));
        }
        assertTrue(seen.size() > 50, "expected a wide sweep, saw " + seen.size() + " colours");
    }

    @Test
    @DisplayName("zero saturation is a constant grey at the configured lightness")
    void zeroSaturationIsGrey() {
        var grey = new ChromaClock(1.0, 0.0, 0.5);
        int expected = grey.colorAt(0L, 0);
        assertEquals((expected >> 16) & 0xFF, (expected >> 8) & 0xFF);
        assertEquals((expected >> 8) & 0xFF, expected & 0xFF);
        for (long t = 0; t < 1000; t += 97) {
            assertEquals(expected, grey.colorAt(t, 0), "grey must not change over time");
        }
    }

    @Test
    @DisplayName("lightness 0 is black and lightness 1 is white whatever the hue")
    void lightnessExtremes() {
        var black = new ChromaClock(1.0, 1.0, 0.0);
        var white = new ChromaClock(1.0, 1.0, 1.0);
        for (long t = 0; t < 1000; t += 111) {
            assertEquals(0x000000, black.colorAt(t, 0));
            assertEquals(0xFFFFFF, white.colorAt(t, 0));
        }
    }

    @Test
    @DisplayName("every output packs into 24 bits")
    void outputStaysInGamut() {
        var clock = new ChromaClock(0.75, 1.0, 0.65);
        for (long t = 0; t < 4000; t += 7) {
            assertEquals(0, clock.colorAt(t, (int) t) & ~0xFFFFFF);
        }
    }

    @Test
    @DisplayName("nonsense settings are rejected at construction")
    void badSettingsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(0.0, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(-1.0, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(Double.NaN, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class,
            () -> new ChromaClock(Double.POSITIVE_INFINITY, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(1.0, 1.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(1.0, -0.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(1.0, 1.0, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(1.0, 1.0, Double.NaN));
    }

    @Test
    @DisplayName("the settings are readable back")
    void accessors() {
        var clock = new ChromaClock(0.5, 0.8, 0.6);
        assertEquals(0.5, clock.cyclesPerSecond());
        assertEquals(0.8, clock.saturation());
        assertEquals(0.6, clock.lightness());
        assertEquals(2000.0, clock.periodMillis());
    }

    @Test
    @DisplayName("the 360-degree phase wrap is exact at EVERY timestamp, not just lucky ones")
    void phaseOffsetWrapsExactlyEverywhere() {
        // Offsets that are exact binary fractions of 360 (0, 45, 180) survive a naive
        // floating-point wrap by luck; a full sweep of the cycle is what catches a
        // hue that is reconstructed as 1.4 - 1.0 instead of 0.4.
        var clock = oneHertz();
        for (long t = 0; t < 1000; t++) {
            int base = clock.colorAt(t, 0);
            assertEquals(base, clock.colorAt(t, 360), "offset 360 drifted at t=" + t);
            assertEquals(base, clock.colorAt(t, -360), "offset -360 drifted at t=" + t);
            assertEquals(base, clock.colorAt(t, 720), "offset 720 drifted at t=" + t);
        }
    }

    @Test
    @DisplayName("an arbitrary offset wraps exactly too, at every timestamp")
    void arbitraryPhaseOffsetWrapsExactly() {
        var clock = new ChromaClock(2.0, 0.85, 0.55);
        for (long t = 0; t < 500; t++) {
            int base = clock.colorAt(t, 137);
            assertEquals(base, clock.colorAt(t, 137 + 360), "at t=" + t);
            assertEquals(base, clock.colorAt(t, 137 - 360), "at t=" + t);
        }
    }

    @Test
    @DisplayName("extreme phase offsets wrap without overflowing")
    void extremePhaseOffsets() {
        var clock = oneHertz();
        assertEquals(clock.colorAt(100L, Math.floorMod(Integer.MAX_VALUE, 360)),
            clock.colorAt(100L, Integer.MAX_VALUE));
        assertEquals(clock.colorAt(100L, Math.floorMod(Integer.MIN_VALUE, 360)),
            clock.colorAt(100L, Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("a rate so slow the period overflows to infinity is rejected, not silently frozen")
    void absurdlySlowRateRejected() {
        // 1000.0 / 1e-320 overflows a double. The old guard only checked the rate, so the
        // period became Infinity: periodMillis() lied and the shimmer stopped moving.
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(1e-320, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ChromaClock(Double.MIN_VALUE, 1.0, 0.5));
    }

    @Test
    @DisplayName("any clock that constructs has a finite, positive, usable period")
    void everyAcceptedRateHasAFinitePeriod() {
        double[] rates = {1e-3, 0.05, 0.25, 1.0, 4.0, 60.0, 1000.0};
        for (double rate : rates) {
            var clock = new ChromaClock(rate, 1.0, 0.5);
            assertTrue(Double.isFinite(clock.periodMillis()) && clock.periodMillis() > 0.0,
                "rate " + rate + " gave period " + clock.periodMillis());
            // and the shimmer actually moves across that period
            assertTrue(clock.colorAt(0L, 0) != clock.colorAt((long) (clock.periodMillis() / 3), 0)
                    || clock.periodMillis() < 3.0,
                "shimmer frozen at rate " + rate);
        }
    }
}
