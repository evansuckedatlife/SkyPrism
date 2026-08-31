package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bare-JVM tests for {@link GradientRamp}. */
class GradientRampTest {

    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;

    @Test
    @DisplayName("the endpoints are exactly the first and last stop colours")
    void endpointsAreExact() {
        var ramp = GradientRamp.of(0, RED, 100, BLUE);
        assertEquals(RED, ramp.colorAt(0));
        assertEquals(BLUE, ramp.colorAt(100));
    }

    @Test
    @DisplayName("every interior stop renders as exactly its own hex")
    void interiorStopsAreExact() {
        var ramp = GradientRamp.of(0, 0xAAAAAA, 40, 0xFFFFFF, 80, 0xFFFF55, 120, 0x55FF55);
        assertEquals(0xAAAAAA, ramp.colorAt(0));
        assertEquals(0xFFFFFF, ramp.colorAt(40));
        assertEquals(0xFFFF55, ramp.colorAt(80));
        assertEquals(0x55FF55, ramp.colorAt(120));
    }

    @Test
    @DisplayName("levels below the first stop and above the last clamp to the end colours")
    void clampsOutsideTheRange() {
        var ramp = GradientRamp.of(50, RED, 100, BLUE);
        assertEquals(RED, ramp.colorAt(49));
        assertEquals(RED, ramp.colorAt(0));
        assertEquals(RED, ramp.colorAt(-1000));
        assertEquals(BLUE, ramp.colorAt(101));
        assertEquals(BLUE, ramp.colorAt(5000));
        assertEquals(BLUE, ramp.colorAt(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("a single-stop ramp is a constant at every level")
    void singleStopIsConstant() {
        var ramp = GradientRamp.of(200, 0x123456);
        assertEquals(0x123456, ramp.colorAt(200));
        assertEquals(0x123456, ramp.colorAt(0));
        assertEquals(0x123456, ramp.colorAt(-50));
        assertEquals(0x123456, ramp.colorAt(100000));
        assertEquals(1, ramp.stops().size());
    }

    @Test
    @DisplayName("stops supplied out of order behave identically to sorted ones")
    void stopsAreSortedInternally() {
        var jumbled = GradientRamp.of(120, 0x55FF55, 0, 0xAAAAAA, 80, 0xFFFF55, 40, 0xFFFFFF);
        var sorted = GradientRamp.of(0, 0xAAAAAA, 40, 0xFFFFFF, 80, 0xFFFF55, 120, 0x55FF55);

        assertEquals(List.of(0, 40, 80, 120),
            jumbled.stops().stream().map(GradientRamp.Stop::level).toList());
        for (int level = -10; level <= 140; level++) {
            assertEquals(sorted.colorAt(level), jumbled.colorAt(level), "mismatch at level " + level);
        }
    }

    @Test
    @DisplayName("duplicate stop levels are rejected rather than silently resolved")
    void duplicateLevelsRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> GradientRamp.of(0, RED, 100, BLUE, 100, 0x00FF00));
        assertTrue(ex.getMessage().contains("100"), ex.getMessage());

        assertThrows(IllegalArgumentException.class, () -> GradientRamp.of(7, RED, 7, RED));
    }

    @Test
    @DisplayName("an empty or null stop list is rejected")
    void emptyAndNullRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GradientRamp(List.of()));
        assertThrows(NullPointerException.class, () -> new GradientRamp(null));
        assertThrows(NullPointerException.class,
            () -> new GradientRamp(Arrays.asList(new GradientRamp.Stop(0, RED), null)));
        assertThrows(IllegalArgumentException.class, () -> GradientRamp.of(0, RED, 40));
    }

    @Test
    @DisplayName("the interior is a monotone walk, never a repeat of an endpoint")
    void interiorInterpolates() {
        var ramp = GradientRamp.of(0, RED, 100, BLUE);
        int previous = ramp.colorAt(0);
        int distinct = 1;
        for (int level = 1; level < 100; level++) {
            int c = ramp.colorAt(level);
            assertNotEquals(RED, c, "level " + level + " should not still be the start colour");
            assertNotEquals(BLUE, c, "level " + level + " should not already be the end colour");
            if (c != previous) {
                distinct++;
                previous = c;
            }
        }
        assertTrue(distinct > 50, "a 100-level ramp should give many distinct shades, got " + distinct);
    }

    @Test
    @DisplayName("stops() is sorted, immutable, and mutating the input list afterwards is harmless")
    void stopsAreDefensivelyCopied() {
        var input = new java.util.ArrayList<GradientRamp.Stop>();
        input.add(new GradientRamp.Stop(100, BLUE));
        input.add(new GradientRamp.Stop(0, RED));
        var ramp = new GradientRamp(input);

        input.clear();
        assertEquals(2, ramp.stops().size());
        assertEquals(0, ramp.stops().get(0).level());
        assertEquals(RED, ramp.colorAt(0));
        assertThrows(UnsupportedOperationException.class,
            () -> ramp.stops().add(new GradientRamp.Stop(5, 0)));
    }

    @Test
    @DisplayName("a stop discards alpha or stray high bits")
    void stopMasksHighBits() {
        assertEquals(0xFF3366, new GradientRamp.Stop(10, 0xFF_FF3366).rgb());
        assertEquals(0xFF3366, GradientRamp.of(10, 0xFF_FF3366).colorAt(10));
    }

    @Test
    @DisplayName("a level exactly midway blends both endpoints rather than snapping to one")
    void midpointIsABlend() {
        var ramp = GradientRamp.of(0, 0x000000, 100, 0xFFFFFF);
        int mid = ramp.colorAt(50);
        int r = (mid >> 16) & 0xFF;
        assertTrue(r > 80 && r < 200, "midpoint grey was " + Integer.toHexString(mid));
    }

    @Test
    @DisplayName("stops further apart than an int can span still blend instead of snapping")
    void widelySeparatedStopsDoNotOverflow() {
        // A span wider than Integer.MAX_VALUE makes the naive (hi - lo) difference wrap
        // negative, which flips the blend fraction and snaps the whole ramp to one end.
        var ramp = GradientRamp.of(Integer.MIN_VALUE, RED, Integer.MAX_VALUE, BLUE);
        int mid = ramp.colorAt(0);
        assertNotEquals(RED, mid, "the middle of the span must not still be the start colour");
        assertNotEquals(BLUE, mid, "the middle of the span must not already be the end colour");

        // Red must fall and blue must rise monotonically as the level climbs.
        long[] samples = {Integer.MIN_VALUE, -1_500_000_000L, -500_000_000L, 0L,
            500_000_000L, 1_500_000_000L, Integer.MAX_VALUE};
        int previousRed = 256;
        int previousBlue = -1;
        for (long sample : samples) {
            int c = ramp.colorAt((int) sample);
            int r = (c >> 16) & 0xFF;
            int b = c & 0xFF;
            assertTrue(r <= previousRed, "red should not climb at level " + sample);
            assertTrue(b >= previousBlue, "blue should not fall at level " + sample);
            previousRed = r;
            previousBlue = b;
        }
    }

    @Test
    @DisplayName("a black-to-white ramp spanning more than an int is mid grey in the middle")
    void wideSpanMidpointIsMidGrey() {
        var ramp = GradientRamp.of(-1_500_000_000, 0x000000, 1_500_000_000, 0xFFFFFF);
        int mid = ramp.colorAt(0);
        int r = (mid >> 16) & 0xFF;
        assertTrue(r > 80 && r < 200,
            "midpoint of a 3-billion-level span was " + Integer.toHexString(mid));
    }
}
