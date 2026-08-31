package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for {@link Oklab}. The round-trip cases are the load-bearing ones:
 * if a colour cannot survive sRGB to Oklab and back, every gradient built on it is
 * quietly wrong by an amount nobody will trace to this class.
 */
class OklabTest {

    private static void assertChannelsWithin(int expected, int actual, int tolerance) {
        for (int shift = 16; shift >= 0; shift -= 8) {
            int e = (expected >> shift) & 0xFF;
            int a = (actual >> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= tolerance,
                () -> String.format("channel drift: expected %06X got %06X", expected, actual));
        }
    }

    @Test
    @DisplayName("primaries, black and white survive the round trip within 1 per channel")
    void roundTripPrimaries() {
        int[] cases = {0xFF0000, 0x00FF00, 0x0000FF, 0x000000, 0xFFFFFF};
        for (int rgb : cases) {
            double[] lab = Oklab.srgbToOklab(rgb);
            assertChannelsWithin(rgb, Oklab.oklabToSrgb(lab[0], lab[1], lab[2]), 1);
        }
    }

    @Test
    @DisplayName("Hypixel's own tier colours survive the round trip within 1 per channel")
    void roundTripHypixelTiers() {
        int[] tiers = {0xAAAAAA, 0xFFFF55, 0x55FF55, 0x00AA00, 0x55FFFF, 0x00AAAA,
            0x5555FF, 0xFF55FF, 0xAA00AA, 0xFFAA00, 0xFF5555, 0xAA0000};
        for (int rgb : tiers) {
            double[] lab = Oklab.srgbToOklab(rgb);
            assertChannelsWithin(rgb, Oklab.oklabToSrgb(lab[0], lab[1], lab[2]), 1);
        }
    }

    @Test
    @DisplayName("black is L=0 and white is L=1, both neutral on a and b")
    void anchorsHaveExpectedCoordinates() {
        double[] black = Oklab.srgbToOklab(0x000000);
        assertEquals(0.0, black[0], 1e-9);
        assertEquals(0.0, black[1], 1e-9);
        assertEquals(0.0, black[2], 1e-9);

        double[] white = Oklab.srgbToOklab(0xFFFFFF);
        assertEquals(1.0, white[0], 1e-6);
        assertEquals(0.0, white[1], 1e-4);
        assertEquals(0.0, white[2], 1e-4);
    }

    @Test
    @DisplayName("alpha or stray high bits are ignored on the way in")
    void highBitsIgnored() {
        double[] opaque = Oklab.srgbToOklab(0xFF3366);
        double[] withAlpha = Oklab.srgbToOklab(0xFF_FF3366);
        assertEquals(opaque[0], withAlpha[0], 1e-12);
        assertEquals(opaque[1], withAlpha[1], 1e-12);
        assertEquals(opaque[2], withAlpha[2], 1e-12);
    }

    @Test
    @DisplayName("out-of-gamut Oklab coordinates clamp instead of wrapping")
    void outOfGamutClamps() {
        int tooBright = Oklab.oklabToSrgb(4.0, 0.0, 0.0);
        assertEquals(0xFFFFFF, tooBright);
        int tooDark = Oklab.oklabToSrgb(-2.0, 0.0, 0.0);
        assertEquals(0x000000, tooDark);
        int wild = Oklab.oklabToSrgb(0.5, 3.0, -3.0);
        assertEquals(0, wild & ~0xFFFFFF, "result must stay inside 24 bits");
    }

    @Test
    @DisplayName("mix returns its endpoints bit-for-bit, including out of range and NaN t")
    void mixEndpointsAreExact() {
        int a = 0x123456;
        int b = 0xFEDCBA;
        assertEquals(a, Oklab.mix(a, b, 0.0));
        assertEquals(b, Oklab.mix(a, b, 1.0));
        assertEquals(a, Oklab.mix(a, b, -5.0));
        assertEquals(b, Oklab.mix(a, b, 42.0));
        assertEquals(a, Oklab.mix(a, b, Double.NaN));
    }

    @Test
    @DisplayName("mix is symmetric: t from A equals 1-t from B")
    void mixIsSymmetric() {
        int a = 0x55FF55;
        int b = 0x5555FF;
        for (double t = 0.1; t < 1.0; t += 0.1) {
            assertChannelsWithin(Oklab.mix(a, b, t), Oklab.mix(b, a, 1.0 - t), 1);
        }
    }

    @Test
    @DisplayName("mix of a colour with itself is that colour at every t")
    void mixOfIdenticalColours() {
        int c = 0x7F3FBF;
        for (double t = 0.0; t <= 1.0; t += 0.125) {
            assertChannelsWithin(c, Oklab.mix(c, c, t), 1);
        }
    }

    @Test
    @DisplayName("blending blue into yellow does not collapse to grey the way sRGB does")
    void midpointStaysColourful() {
        int mid = Oklab.mix(0x5555FF, 0xFFFF55, 0.5);
        double[] lab = Oklab.srgbToOklab(mid);
        double chroma = Math.hypot(lab[1], lab[2]);
        assertTrue(chroma > 0.02, "midpoint chroma was " + chroma);
        assertNotEquals(0x5555FF, mid);
        assertNotEquals(0xFFFF55, mid);
    }

    @Test
    @DisplayName("mix output always packs into 24 bits")
    void mixStaysInGamut() {
        for (double t = 0.05; t < 1.0; t += 0.05) {
            assertEquals(0, Oklab.mix(0x000000, 0xFFFFFF, t) & ~0xFFFFFF);
            assertEquals(0, Oklab.mix(0xFF0000, 0x00FF00, t) & ~0xFFFFFF);
        }
    }
}
