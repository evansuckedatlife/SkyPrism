package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bare-JVM tests for {@link LevelPalette}, including the VANILLA fidelity check. */
class LevelPaletteTest {

    private static LevelPalette vanilla() {
        return new LevelPalette(LevelColorMode.VANILLA, null, null, false, 0, null);
    }

    @Test
    @DisplayName("VANILLA reproduces the Hypixel tier colours at the boundary levels")
    void vanillaMatchesHypixelBoundaries() {
        var p = vanilla();
        assertEquals(0xAAAAAA, p.colorFor(0, 0L), "level 0 is gray");
        assertEquals(0xAAAAAA, p.colorFor(39, 0L), "39 is still the gray tier");
        assertEquals(0xFFFFFF, p.colorFor(40, 0L), "40 flips to white");
        assertEquals(0xFF5555, p.colorFor(479, 0L), "479 is still the red tier");
        assertEquals(0xAA0000, p.colorFor(480, 0L), "480 flips to dark red");
        assertEquals(0xAA0000, p.colorFor(500, 0L), "past the top tier the colour holds");
    }

    @Test
    @DisplayName("VANILLA reproduces all 13 tiers and changes colour only every 40 levels")
    void vanillaMatchesEveryTier() {
        int[] tiers = {0xAAAAAA, 0xFFFFFF, 0xFFFF55, 0x55FF55, 0x00AA00, 0x55FFFF, 0x00AAAA,
            0x5555FF, 0xFF55FF, 0xAA00AA, 0xFFAA00, 0xFF5555, 0xAA0000};
        var p = vanilla();
        for (int i = 0; i < tiers.length; i++) {
            int base = i * 40;
            assertEquals(tiers[i], p.colorFor(base, 0L), "tier start " + base);
            assertEquals(tiers[i], p.colorFor(base + 39, 0L), "tier end " + (base + 39));
        }
    }

    @Test
    @DisplayName("VANILLA ignores whatever ramp and table the config happens to carry")
    void vanillaIgnoresSuppliedPalettes() {
        var p = new LevelPalette(LevelColorMode.VANILLA,
            GradientRamp.of(0, 0x00FF00, 500, 0x00FF00),
            BracketTable.of(0, 0x00FF00),
            false, 0, null);
        assertEquals(0xAAAAAA, p.colorFor(0, 0L));
        assertEquals(0xFFAA00, p.colorFor(400, 0L));
    }

    @Test
    @DisplayName("GRADIENT delegates to the ramp and BRACKETS to the table")
    void modesDelegate() {
        var ramp = GradientRamp.of(0, 0x000000, 100, 0xFFFFFF);
        var table = BracketTable.of(0, 0x111111, 50, 0x222222);

        var gradient = new LevelPalette(LevelColorMode.GRADIENT, ramp, table, false, 0, null);
        var brackets = new LevelPalette(LevelColorMode.BRACKETS, ramp, table, false, 0, null);

        for (int level = 0; level <= 120; level += 7) {
            assertEquals(ramp.colorAt(level), gradient.colorFor(level, 0L));
            assertEquals(table.colorAt(level), brackets.colorFor(level, 0L));
        }
        assertNotEquals(gradient.colorFor(75, 0L), brackets.colorFor(75, 0L));
    }

    @Test
    @DisplayName("isChromatic is false at every level when chroma is disabled")
    void isChromaticFalseWhenDisabled() {
        var p = new LevelPalette(LevelColorMode.GRADIENT, PalettePresets.vanillaPlus(), null,
            false, 0, new ChromaClock(1.0, 1.0, 0.5));
        for (int level = -100; level <= 2000; level++) {
            assertFalse(p.isChromatic(level), "level " + level + " must not be chromatic");
        }
        assertFalse(p.isChromatic(Integer.MAX_VALUE));
        assertFalse(p.isChromatic(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("a disabled shimmer never changes a colour over time")
    void disabledChromaIsTimeIndependent() {
        var p = new LevelPalette(LevelColorMode.GRADIENT, PalettePresets.rainbow(), null,
            false, 0, new ChromaClock(1.0, 1.0, 0.5));
        int at400 = p.colorFor(400, 0L);
        for (long t = 0; t < 5000; t += 137) {
            assertEquals(at400, p.colorFor(400, t));
        }
    }

    @Test
    @DisplayName("isChromatic switches on exactly at the threshold, not one below it")
    void isChromaticThresholdIsInclusive() {
        var p = new LevelPalette(LevelColorMode.GRADIENT, PalettePresets.vanillaPlus(), null,
            true, 400, new ChromaClock(1.0, 1.0, 0.5));
        assertFalse(p.isChromatic(399));
        assertTrue(p.isChromatic(400));
        assertTrue(p.isChromatic(401));
        assertTrue(p.isChromatic(10_000));
    }

    @Test
    @DisplayName("above the threshold the colour animates; below it stays static")
    void chromaOverridesOnlyAboveTheThreshold() {
        var ramp = PalettePresets.vanillaPlus();
        var p = new LevelPalette(LevelColorMode.GRADIENT, ramp, null,
            true, 400, new ChromaClock(1.0, 1.0, 0.6));

        int belowAtZero = p.colorFor(399, 0L);
        assertEquals(ramp.colorAt(399), belowAtZero);
        assertEquals(belowAtZero, p.colorFor(399, 500L), "a static level cannot move");

        assertNotEquals(p.colorFor(400, 0L), p.colorFor(400, 300L), "a chromatic level must move");
    }

    @Test
    @DisplayName("the level doubles as a hue phase so two shimmering tags differ")
    void chromaPhaseVariesByLevel() {
        var p = new LevelPalette(LevelColorMode.VANILLA, null, null,
            true, 0, new ChromaClock(1.0, 1.0, 0.5));
        assertNotEquals(p.colorFor(400, 1000L), p.colorFor(430, 1000L));
    }

    @Test
    @DisplayName("a chromatic colour is periodic in time like the clock it wraps")
    void chromaticColourIsPeriodic() {
        var clock = new ChromaClock(1.0, 1.0, 0.5);
        var p = new LevelPalette(LevelColorMode.VANILLA, null, null, true, 0, clock);
        for (long t = 0; t < 1000; t += 61) {
            assertEquals(p.colorFor(450, t), p.colorFor(450, t + 1000L));
        }
    }

    @Test
    @DisplayName("a component the chosen configuration needs may not be null")
    void requiredComponentsValidated() {
        assertThrows(NullPointerException.class,
            () -> new LevelPalette(null, null, null, false, 0, null));
        assertThrows(NullPointerException.class,
            () -> new LevelPalette(LevelColorMode.GRADIENT, null, null, false, 0, null));
        assertThrows(NullPointerException.class,
            () -> new LevelPalette(LevelColorMode.BRACKETS, null, null, false, 0, null));
        assertThrows(NullPointerException.class,
            () -> new LevelPalette(LevelColorMode.VANILLA, null, null, true, 0, null));
    }

    @Test
    @DisplayName("an unused component may be null")
    void unusedComponentsMayBeNull() {
        var g = new LevelPalette(LevelColorMode.GRADIENT, PalettePresets.mono(), null, false, 0, null);
        assertEquals(LevelColorMode.GRADIENT, g.mode());
        assertEquals(0x555555, g.colorFor(0, 0L));

        var b = new LevelPalette(LevelColorMode.BRACKETS, null, PalettePresets.fineBrackets(),
            false, 0, null);
        assertEquals(LevelColorMode.BRACKETS, b.mode());
        assertEquals(0xAAAAAA, b.colorFor(0, 0L));
    }

    @Test
    @DisplayName("defaults() is the shipped default bracket table, static, with the shimmer off")
    void defaultsAreSaneBeforeAnyConfigLoads() {
        var p = LevelPalette.defaults();
        assertEquals(LevelColorMode.BRACKETS, p.mode());
        assertFalse(p.chromaEnabled());
        assertFalse(p.isChromatic(Integer.MAX_VALUE));

        // Pinned to the table itself rather than to two literal colours. The no-config fallback
        // and the shipped config must agree -- they did not, once, and every client that failed
        // to read its config drew a different gradient from every client that read it fine. A
        // literal here would have gone on passing through exactly that bug.
        //
        // The mode is half of that agreement and is asserted above: when the shipped default
        // moved from a gradient to a table, a defaults() left on GRADIENT would have reproduced
        // the same bug with the same symptoms. BracketTable has no equals(), so identity is the
        // check -- both sides are meant to be the one shared singleton, not two equal copies.
        assertSame(PalettePresets.defaultBrackets(), p.table());
        for (int level = 0; level <= 700; level += 10) {
            assertEquals(PalettePresets.defaultBrackets().colorAt(level), p.colorFor(level, 0L),
                "level " + level);
        }
        // The unused slot still carries the shipped gradient rather than a null, so flipping
        // the mode on this palette cannot NPE.
        assertEquals(PalettePresets.defaultRamp(), p.ramp());
    }

    @Test
    @DisplayName("the accessors report back what was configured")
    void accessors() {
        var ramp = PalettePresets.aurora();
        var table = PalettePresets.fineBrackets();
        var clock = new ChromaClock(0.4, 0.9, 0.6);
        var p = new LevelPalette(LevelColorMode.BRACKETS, ramp, table, true, 350, clock);
        assertEquals(LevelColorMode.BRACKETS, p.mode());
        assertEquals(ramp, p.ramp());
        assertEquals(table, p.table());
        assertTrue(p.chromaEnabled());
        assertEquals(350, p.chromaMinLevel());
        assertEquals(clock, p.chroma());
    }
}
