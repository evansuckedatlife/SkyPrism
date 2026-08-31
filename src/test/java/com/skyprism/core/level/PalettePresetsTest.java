package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for {@link PalettePresets}. The vanilla fixtures are asserted
 * against the researched tier table literally, so a future edit that mistypes one
 * hex fails here rather than shipping a subtly wrong "vanilla" mode.
 *
 * <p>The rest of the class is deliberately written against {@link PalettePresets#gradients()}
 * rather than against a hand-listed set of ramps: registering a preset in that map is the
 * whole of adding one, so every structural and legibility rule here applies to a preset
 * added next year without anyone remembering to opt it in.
 */
class PalettePresetsTest {

    /** Hypixel tier colours in tier order, from the verified brief. */
    private static final int[] TIERS = {
        0xAAAAAA, 0xFFFFFF, 0xFFFF55, 0x55FF55, 0x00AA00, 0x55FFFF, 0x00AAAA,
        0x5555FF, 0xFF55FF, 0xAA00AA, 0xFFAA00, 0xFF5555, 0xAA0000
    };

    /**
     * A stand-in for the ground SkyPrism draws its tags on.
     *
     * <p>Chat and the TAB list are both a near-black scrim over whatever the world is doing;
     * over a dark scene they settle around here, and that is the case that decides whether a
     * colour is readable. A bright scene behind the same scrim only ever helps a dark colour.
     */
    private static final int DARK_UI_BACKGROUND = 0x14151C;

    /**
     * The floor every shipped ramp has to clear at every level.
     *
     * <p>Not a WCAG grade -- this is a game HUD, and Hypixel's own {@code 0xAA0000} top tier
     * only manages 2.3:1, so a text-grade 4.5 would fail the ramp whose whole job is to
     * reproduce Hypixel. It is set to catch the failure that actually happens: a navy or a
     * deep violet stop that disappears completely against the TAB panel. {@code 0x000080}
     * scores 1.3 here and {@code 0x4B2E83}, which {@code sunset} used to open on, scored 1.7.
     */
    private static final double MIN_CONTRAST = 2.0;

    /**
     * The much higher bar the shipped default is held to.
     *
     * <p>The default is the one ramp a player never chose, so it has to be readable without
     * anyone having thought about it. {@code spectrum} is built to hold a fixed perceptual
     * lightness across the whole hue sweep, which is exactly what makes a number this high
     * achievable at all.
     */
    private static final double MIN_DEFAULT_CONTRAST = 7.0;

    /** Highest level any shipped ramp is designed for; above it {@link GradientRamp} clamps. */
    private static final int TOP_LEVEL = 600;

    @Test
    @DisplayName("vanillaBrackets is exactly the 13 Hypixel tiers, 40 levels apart")
    void vanillaBracketsMatchTheBrief() {
        var t = PalettePresets.vanillaBrackets();
        assertEquals(13, t.brackets().size());
        for (int i = 0; i < TIERS.length; i++) {
            assertEquals(i * 40, t.brackets().get(i).minLevel(), "boundary " + i);
            assertEquals(TIERS[i], t.brackets().get(i).rgb(), "colour of tier " + i);
        }
        assertEquals(0xAAAAAA, t.colorAt(39));
        assertEquals(0xFFFFFF, t.colorAt(40));
        assertEquals(0xAA0000, t.colorAt(480));
        assertEquals(0xAA0000, t.colorAt(1000));
    }

    @Test
    @DisplayName("vanillaPlus pins the same 13 colours as stops and interpolates between them")
    void vanillaPlusPinsTheTierColours() {
        var ramp = PalettePresets.vanillaPlus();
        assertEquals(13, ramp.stops().size());
        for (int i = 0; i < TIERS.length; i++) {
            assertEquals(TIERS[i], ramp.colorAt(i * 40), "stop " + i);
        }
        assertEquals(0xAAAAAA, ramp.colorAt(-5), "clamps below");
        assertEquals(0xAA0000, ramp.colorAt(900), "clamps above");
        // Between two tiers it must be neither of them.
        int between = ramp.colorAt(20);
        assertTrue(between != 0xAAAAAA && between != 0xFFFFFF,
            "level 20 should be a blend, was " + Integer.toHexString(between));
    }

    @Test
    @DisplayName("fineBrackets is 25 bands 20 levels apart sampled off vanillaPlus")
    void fineBracketsAreTwiceAsFine() {
        var fine = PalettePresets.fineBrackets();
        var ramp = PalettePresets.vanillaPlus();
        assertEquals(25, fine.brackets().size());
        for (int i = 0; i < fine.brackets().size(); i++) {
            int level = i * 20;
            assertEquals(level, fine.brackets().get(i).minLevel());
            assertEquals(ramp.colorAt(level), fine.brackets().get(i).rgb(), "band at " + level);
        }
        // The even bands land on real Hypixel tier colours.
        for (int i = 0; i < TIERS.length; i++) {
            assertEquals(TIERS[i], fine.colorAt(i * 40), "tier " + i);
        }
    }

    // ------------------------------------------------------------- the default

    @Test
    @DisplayName("spectrum is registered under the default name and is what defaultRamp hands back")
    void spectrumIsTheShippedDefault() {
        assertEquals("spectrum", PalettePresets.DEFAULT_PRESET_NAME);
        assertSame(PalettePresets.spectrum(), PalettePresets.defaultRamp());
        assertSame(PalettePresets.spectrum(),
            PalettePresets.gradients().get(PalettePresets.DEFAULT_PRESET_NAME));
        // First in the dropdown, because it is the one most players will keep.
        assertEquals(PalettePresets.DEFAULT_PRESET_NAME,
            PalettePresets.gradients().keySet().iterator().next());
    }

    @Test
    @DisplayName("spectrum sweeps 0..600 in 16 stops and never repeats a colour")
    void spectrumCoversTheWholeLiveRange() {
        var ramp = PalettePresets.spectrum();
        assertEquals(16, ramp.stops().size());
        assertEquals(0, ramp.stops().get(0).level());
        assertEquals(TOP_LEVEL, ramp.stops().get(15).level());
        for (int i = 0; i < ramp.stops().size(); i++) {
            assertEquals(i * 40, ramp.stops().get(i).level(), "stop " + i + " sits on a 40 boundary");
        }
        for (int i = 0; i < ramp.stops().size(); i++) {
            for (int j = i + 1; j < ramp.stops().size(); j++) {
                assertTrue(ramp.stops().get(i).rgb() != ramp.stops().get(j).rgb(),
                    "stops " + i + " and " + j + " are the same colour");
            }
        }
    }

    @Test
    @DisplayName("spectrum: two levels 40 apart are obviously different colours")
    void spectrumSeparatesLevelsFortyApart() {
        var ramp = PalettePresets.spectrum();
        // Oklab is scaled so that ~0.02 is around the point two colours stop being tellable
        // apart side by side. The whole reason this preset exists is that vanillaPlus fails
        // this: it spends levels 120..300 in adjacent greens and aquas.
        double worst = Double.MAX_VALUE;
        int worstAt = -1;
        for (int level = 0; level + 40 <= TOP_LEVEL; level++) {
            double d = oklabDistance(ramp.colorAt(level), ramp.colorAt(level + 40));
            if (d < worst) {
                worst = d;
                worstAt = level;
            }
        }
        assertTrue(worst > 0.03,
            "levels " + worstAt + " and " + (worstAt + 40) + " differ by only " + worst);
    }

    @Test
    @DisplayName("spectrum holds a near-constant perceptual lightness so no band goes muddy")
    void spectrumHoldsItsLightness() {
        var ramp = PalettePresets.spectrum();
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int level = 0; level <= TOP_LEVEL; level++) {
            double l = Oklab.srgbToOklab(ramp.colorAt(level))[0];
            lo = Math.min(lo, l);
            hi = Math.max(hi, l);
        }
        assertTrue(hi - lo < 0.05,
            "lightness wandered from " + lo + " to " + hi + "; the ramp will read as uneven");
    }

    // --------------------------------------------------- rules for every preset

    @Test
    @DisplayName("every registered preset has strictly ascending, gap-free, duplicate-free stops")
    void everyPresetIsStructurallySound() {
        for (Map.Entry<String, GradientRamp> e : PalettePresets.gradients().entrySet()) {
            String name = e.getKey();
            List<GradientRamp.Stop> stops = e.getValue().stops();
            assertTrue(stops.size() >= 2, name + " needs real range");
            assertEquals(0, stops.get(0).level(), name + " must start at level 0");
            // 480 rather than 600: vanillaPlus deliberately ends on Hypixel's own top tier
            // boundary, and clamping covers everything above whatever a ramp's last stop is.
            assertTrue(stops.get(stops.size() - 1).level() >= 480,
                name + " must cover the live level range, ended at "
                    + stops.get(stops.size() - 1).level());
            for (int i = 1; i < stops.size(); i++) {
                assertTrue(stops.get(i).level() > stops.get(i - 1).level(),
                    name + " stop " + i + " does not advance past " + stops.get(i - 1).level());
            }
            assertTrue(stops.get(0).rgb() != stops.get(stops.size() - 1).rgb(),
                name + " starts and ends on the same colour");
        }
    }

    @Test
    @DisplayName("every registered preset walks 0..600 smoothly and stays inside 24 bits")
    void everyPresetIsContinuous() {
        for (Map.Entry<String, GradientRamp> e : PalettePresets.gradients().entrySet()) {
            String name = e.getKey();
            GradientRamp ramp = e.getValue();
            for (int level = -10; level <= TOP_LEVEL + 100; level++) {
                assertEquals(0, ramp.colorAt(level) & ~0xFFFFFF,
                    name + " produced a non-24-bit colour at " + level);
            }
            // No visible seam at a stop boundary: one level's step is always sub-threshold.
            for (int level = 0; level < TOP_LEVEL; level++) {
                double step = oklabDistance(ramp.colorAt(level), ramp.colorAt(level + 1));
                assertTrue(step < 0.02,
                    name + " jumps " + step + " between level " + level + " and " + (level + 1));
            }
        }
    }

    @Test
    @DisplayName("no registered preset has a band that disappears against a dark UI background")
    void everyPresetStaysLegible() {
        for (Map.Entry<String, GradientRamp> e : PalettePresets.gradients().entrySet()) {
            String name = e.getKey();
            GradientRamp ramp = e.getValue();
            double worst = Double.MAX_VALUE;
            int worstAt = -1;
            int worstRgb = 0;
            for (int level = 0; level <= TOP_LEVEL; level++) {
                int rgb = ramp.colorAt(level);
                double k = contrastAgainstDarkUi(rgb);
                if (k < worst) {
                    worst = k;
                    worstAt = level;
                    worstRgb = rgb;
                }
            }
            final double lowest = worst;
            final int lowestAt = worstAt;
            final int lowestRgb = worstRgb;
            assertTrue(lowest >= MIN_CONTRAST, () -> String.format(
                "preset %s is unreadable at level %d (#%06X): contrast %.2f, floor %.2f",
                name, lowestAt, lowestRgb, lowest, MIN_CONTRAST));
        }
    }

    @Test
    @DisplayName("the default preset clears a far higher legibility bar than the floor")
    void theDefaultIsComfortablyLegible() {
        var ramp = PalettePresets.defaultRamp();
        for (int level = 0; level <= TOP_LEVEL; level++) {
            int rgb = ramp.colorAt(level);
            double k = contrastAgainstDarkUi(rgb);
            final int at = level;
            assertTrue(k >= MIN_DEFAULT_CONTRAST, () -> String.format(
                "the default ramp is only %.2f:1 at level %d (#%06X); bar is %.2f",
                k, at, rgb, MIN_DEFAULT_CONTRAST));
        }
    }

    @Test
    @DisplayName("mono has no hue anywhere along the ramp")
    void monoIsGreyThroughout() {
        var mono = PalettePresets.mono();
        for (int level = 0; level <= 500; level += 25) {
            int c = mono.colorAt(level);
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            assertTrue(Math.abs(r - g) <= 2 && Math.abs(g - b) <= 2,
                "level " + level + " was " + Integer.toHexString(c));
        }
    }

    @Test
    @DisplayName("gradients() indexes every preset under a stable snake_case key")
    void gradientIndexIsComplete() {
        var map = PalettePresets.gradients();
        assertEquals(
            List.of("spectrum", "vanilla_plus", "aurora", "ocean", "sunset", "ember",
                "toxic", "neon", "candy", "rainbow", "mono"),
            List.copyOf(map.keySet()));
        assertSame(PalettePresets.spectrum(), map.get("spectrum"));
        assertSame(PalettePresets.vanillaPlus(), map.get("vanilla_plus"));
        assertSame(PalettePresets.aurora(), map.get("aurora"));
        assertSame(PalettePresets.ocean(), map.get("ocean"));
        assertSame(PalettePresets.sunset(), map.get("sunset"));
        assertSame(PalettePresets.ember(), map.get("ember"));
        assertSame(PalettePresets.toxic(), map.get("toxic"));
        assertSame(PalettePresets.neon(), map.get("neon"));
        assertSame(PalettePresets.candy(), map.get("candy"));
        assertSame(PalettePresets.rainbow(), map.get("rainbow"));
        assertSame(PalettePresets.mono(), map.get("mono"));
        // Every accessor above is reachable from the map, and nothing is in the map twice.
        assertEquals(map.size(), List.copyOf(map.values()).stream().distinct().count(),
            "two keys point at the same ramp");
    }

    @Test
    @DisplayName("gradients() is unmodifiable so a caller cannot corrupt the shared index")
    void gradientIndexIsUnmodifiable() {
        var map = PalettePresets.gradients();
        assertThrows(UnsupportedOperationException.class,
            () -> map.put("evil", PalettePresets.mono()));
        assertThrows(UnsupportedOperationException.class, () -> map.remove("aurora"));
    }

    @Test
    @DisplayName("presets are cached singletons, not rebuilt per call")
    void presetsAreSingletons() {
        assertSame(PalettePresets.spectrum(), PalettePresets.spectrum());
        assertSame(PalettePresets.defaultRamp(), PalettePresets.defaultRamp());
        assertSame(PalettePresets.vanillaPlus(), PalettePresets.vanillaPlus());
        assertSame(PalettePresets.vanillaBrackets(), PalettePresets.vanillaBrackets());
        assertSame(PalettePresets.fineBrackets(), PalettePresets.fineBrackets());
        assertSame(PalettePresets.rainbow(), PalettePresets.rainbow());
        assertSame(PalettePresets.ocean(), PalettePresets.ocean());
        assertSame(PalettePresets.ember(), PalettePresets.ember());
        assertSame(PalettePresets.toxic(), PalettePresets.toxic());
        assertSame(PalettePresets.neon(), PalettePresets.neon());
        assertSame(PalettePresets.candy(), PalettePresets.candy());
    }

    // ----------------------------------------------------------------- helpers

    /** Perceptual distance between two packed colours, in Oklab units. */
    private static double oklabDistance(int a, int b) {
        double[] p = Oklab.srgbToOklab(a);
        double[] q = Oklab.srgbToOklab(b);
        return Math.sqrt((p[0] - q[0]) * (p[0] - q[0])
            + (p[1] - q[1]) * (p[1] - q[1])
            + (p[2] - q[2]) * (p[2] - q[2]));
    }

    /** WCAG contrast ratio of a packed colour against {@link #DARK_UI_BACKGROUND}. */
    private static double contrastAgainstDarkUi(int rgb) {
        double a = relativeLuminance(rgb);
        double b = relativeLuminance(DARK_UI_BACKGROUND);
        double hi = Math.max(a, b);
        double lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** WCAG relative luminance: linearised sRGB under the standard channel weights. */
    private static double relativeLuminance(int rgb) {
        return 0.2126 * linear(((rgb >> 16) & 0xFF) / 255.0)
            + 0.7152 * linear(((rgb >> 8) & 0xFF) / 255.0)
            + 0.0722 * linear((rgb & 0xFF) / 255.0);
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
