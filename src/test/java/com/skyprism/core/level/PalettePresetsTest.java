package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.config.SkyPrismConfig;
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
     * The much higher bar SkyPrism's own colours are held to.
     *
     * <p>The default is the one palette a player never chose, so it has to be readable without
     * anyone having thought about it. Both {@code spectrum} and the 480+ half of the shipped
     * table are built to hold a controlled perceptual lightness across their hue sweep, which
     * is exactly what makes a number this high achievable at all.
     */
    private static final double MIN_DEFAULT_CONTRAST = 7.0;

    /** Where Hypixel's scale runs out and SkyPrism's own bands take over. */
    private static final int TOP_TIER_LEVEL = 480;

    /**
     * The floor every pair of brackets involving a SkyPrism colour holds -- <b>every</b> pair,
     * near or far, not just neighbours.
     *
     * <p>This bar is the whole point of the class. Two separate releases shipped a table whose
     * adjacent brackets were all comfortably apart and whose distant ones were not, because
     * the only separation this file ever measured was between neighbours. 1.0.3's level-590
     * pale cyan sat 0.0869 from the level-200 aqua -- nineteen rows away, so nothing looked at
     * it -- and a player reported them as the same colour.
     *
     * <p>0.1142 is what the search actually reached with six bands above 480 under the
     * progression and contrast constraints; the bar sits a hair under it, so a hue, lightness
     * or chroma edit that meaningfully closes the table up fails while the design as shipped
     * has no slack it did not earn. For scale, ~0.02 is roughly where two colours stop being
     * tellable apart side by side and ~0.10 is where they stop reading as different colours at
     * a glance in a chat line.
     */
    private static final double MIN_TABLE_SEPARATION = 0.113;

    /**
     * The floor the vanilla-only pairs hold, which is lower and not SkyPrism's to raise.
     *
     * <p>The 24 brackets below 480 are Hypixel's tier hexes and the midpoints between them,
     * reproduced on purpose. Their tightest pair anywhere is levels 80 and 100 at 0.0844, both
     * sitting on the server's own yellow-to-green run. This bar exists to catch a bracket that
     * got dropped, duplicated or mistyped, not to grade colours the mod deliberately inherited.
     */
    private static final double MIN_VANILLA_SEPARATION = 0.084;

    /** Highest level any shipped ramp is designed for; above it {@link GradientRamp} clamps. */
    private static final int TOP_LEVEL = 600;

    /** Levels per bracket, both halves of the shipped table. */
    private static final int BAND_WIDTH = 20;

    /** The last boundary in the shipped table; {@link BracketTable} clamps above it. */
    private static final int TOP_BAND_LEVEL = 580;

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

    // ------------------------------------------------- the shipped default table

    @Test
    @DisplayName("defaultBrackets is 30 entries on one 20-level cadence: 24 vanilla, then 6 SkyPrism")
    void defaultTableHasTheShippedShape() {
        var table = PalettePresets.defaultBrackets();
        var brackets = table.brackets();
        assertEquals(30, brackets.size(), "24 below 480 plus 6 from 480 to 580");
        assertTrue(brackets.size() <= SkyPrismConfig.LevelSettings.MAX_TABLE_ENTRIES,
            "the shipped table must fit inside the cap the config parser enforces, so a user "
                + "who opens the table editor on it can still add rows");

        assertEquals(0, brackets.get(0).minLevel());
        assertEquals(TOP_BAND_LEVEL, brackets.get(brackets.size() - 1).minLevel(),
            "the table terminates at 580; a seventh band at 600 would have to be placed in the "
                + "cyan corner the level-590 bug lived in");

        // One cadence, the whole way up. The 480+ half used to step every 10, which is the
        // resolution that made the collisions unavoidable -- there is not enough unclaimed
        // hue left, once Hypixel's 13 tiers are on the table, to place 13 bands legibly.
        for (int i = 0; i < brackets.size(); i++) {
            assertEquals(i * BAND_WIDTH, brackets.get(i).minLevel(), "bracket " + i + " boundary");
        }

        // 600 is the default chromaMinLevel and it is above the last boundary, so the shimmer
        // starts on top of a clamped band rather than on a band of its own.
        assertEquals(brackets.get(brackets.size() - 1).rgb(), table.colorAt(TOP_LEVEL),
            "600 must clamp onto the top band");
        assertEquals(table.colorAt(TOP_LEVEL), table.colorAt(9000), "and so must everything above");
    }

    @Test
    @DisplayName("everything below 480 is fineBrackets byte for byte -- no new colours down there")
    void defaultTableInheritsTheVanillaHalfUnchanged() {
        // pil4: "everything before 480 should stay as it was before, and only after the custom
        // ones". This is that promise as an assertion. The bands are not re-sampled off
        // vanillaPlus by a second loop that happens to agree; they are the same objects.
        var fine = PalettePresets.fineBrackets().brackets();
        var shipped = PalettePresets.defaultBrackets().brackets();
        for (int i = 0; i < 24; i++) {
            assertEquals(fine.get(i).minLevel(), shipped.get(i).minLevel(), "boundary " + i);
            assertEquals(fine.get(i).rgb(), shipped.get(i).rgb(),
                "band " + (i * 20) + " strayed from the vanilla hexlist");
        }
        // The even bands are still real Hypixel tier colours, all the way to the red at 440.
        for (int i = 0; i * 40 < TOP_TIER_LEVEL; i++) {
            assertEquals(TIERS[i], PalettePresets.defaultBrackets().colorAt(i * 40), "tier " + i);
        }
        // 479 is the last vanilla level, and it draws the last vanilla band: the midpoint
        // between the 440 red and the dark red, exactly as fineBrackets does.
        assertEquals(PalettePresets.fineBrackets().colorAt(479),
            PalettePresets.defaultBrackets().colorAt(479));
        assertEquals(0xD43330, PalettePresets.defaultBrackets().colorAt(479));
    }

    @Test
    @DisplayName("480 leaves the red corner instead of dead-ending in it")
    void theFourEightyCellIsTheWholeChange() {
        int shipped = PalettePresets.defaultBrackets().colorAt(TOP_TIER_LEVEL);

        // What an unmodded client draws at 480, and the least legible colour on Hypixel's whole
        // scale: vanilla escalates 320..480 by going darker and then stops there forever.
        for (int tier : TIERS) {
            assertTrue(contrastAgainstDarkUi(0xAA0000) <= contrastAgainstDarkUi(tier),
                "premise check: no vanilla tier is harder to read than the 480 dark red");
        }
        double fromDarkRed = oklabDistance(shipped, 0xAA0000);
        assertTrue(fromDarkRed >= 0.30,
            "480 is only " + fromDarkRed + " from the dark red it replaces");

        // And it has to read as new against the colour the eye actually compares it to: the
        // level-440 red sitting one band below it in this same table.
        double fromRed = oklabDistance(shipped, 0xFF5555);
        assertTrue(fromRed >= 0.13, "480 is only " + fromRed + " from the 440 red above it");
        assertEquals(0xFF5555, PalettePresets.defaultBrackets().colorAt(440), "premise check");
    }

    @Test
    @DisplayName("NO two brackets anywhere in the default table blur together -- far pairs included")
    void noTwoBracketsAnywhereInTheTableBlurTogether() {
        // This is the assertion the class was missing for two releases. Level 590 and level 200
        // are nineteen rows apart, so every neighbour-only check ever written here passed on
        // them while a player was looking at two identical-looking numbers in chat. A tag does
        // not know which bracket it is next to; it is compared against every other tag on the
        // screen, so the invariant has to be over every pair in the table.
        var brackets = PalettePresets.defaultBrackets().brackets();
        for (int i = 0; i < brackets.size(); i++) {
            for (int j = i + 1; j < brackets.size(); j++) {
                BracketTable.Bracket lo = brackets.get(i);
                BracketTable.Bracket hi = brackets.get(j);
                boolean bothInherited =
                    lo.minLevel() < TOP_TIER_LEVEL && hi.minLevel() < TOP_TIER_LEVEL;
                double bar = bothInherited ? MIN_VANILLA_SEPARATION : MIN_TABLE_SEPARATION;
                double d = oklabDistance(lo.rgb(), hi.rgb());
                assertTrue(d >= bar, () -> String.format(
                    "brackets %d (#%06X) and %d (#%06X) are only %.4f apart; bar is %.4f%s",
                    lo.minLevel(), lo.rgb(), hi.minLevel(), hi.rgb(), d, bar,
                    bothInherited ? " (both inherited from Hypixel)" : ""));
            }
        }
    }

    @Test
    @DisplayName("the whole-table bar is exactly what the 1.0.3 colours would have failed")
    void theSeparationBarWouldHaveCaughtTheColoursThatShipped() {
        // A bar nothing can fail is not a bar. These are the literal hexes 1.0.3 drew, checked
        // against the floor the current table holds, so the test above is demonstrably tight
        // enough to have stopped the release that caused this.
        //
        // Level 590 pale cyan against the level-200 aqua: what the player actually reported.
        double reported = oklabDistance(0x96E1FD, 0x55FFFF);
        assertTrue(reported < MIN_TABLE_SEPARATION, () -> String.format(
            "the 1.0.3 level-590 (#96E1FD) sat %.4f from the level-200 aqua and the bar is now "
                + "%.4f; if that no longer fails, the bar has been loosened past the bug", reported,
            MIN_TABLE_SEPARATION));

        // And the closest pair 1.0.3 shipped at all -- levels 550 and 570, two apart, sharing a
        // lightness, separated by nothing but 23 degrees of hue.
        double tightest = oklabDistance(0xC4D3FD, 0xB2D9FD);
        assertTrue(tightest < reported, "premise check: 550/570 was the tightest pair of the two");
        assertTrue(tightest < MIN_TABLE_SEPARATION,
            () -> "the 1.0.3 550/570 pair measured " + tightest);

        // Neither retired colour survives into the shipped table under any level.
        for (BracketTable.Bracket b : PalettePresets.defaultBrackets().brackets()) {
            assertTrue(b.rgb() != 0x96E1FD && b.rgb() != 0xC4D3FD,
                "a retired 1.0.3 band colour is still in the table at " + b.minLevel());
        }
    }

    @Test
    @DisplayName("above 480 the bands sweep one way in hue and alternate lightness across it")
    void theBandsAreAProgressionAndNotAScatter() {
        var brackets = PalettePresets.defaultBrackets().brackets();
        var bands = brackets.subList(24, brackets.size());
        assertEquals(6, bands.size());

        // A plain max-min search over legible colours reaches a slightly higher floor than this
        // table does, and produces an olive next to an azure next to a pink: mutually distinct
        // and no progression at all. These two assertions are the constraint that rules that
        // out -- hue advances in one direction in roughly even steps, and lightness alternates.
        double smallest = Double.MAX_VALUE;
        double largest = 0;
        for (int i = 1; i < bands.size(); i++) {
            final double step = descendingHueStep(bands.get(i - 1).rgb(), bands.get(i).rgb());
            assertTrue(step > 10 && step < 60, () -> String.format(
                "hue step %.1f degrees: the sweep reversed, wrapped, or lurched", step));
            smallest = Math.min(smallest, step);
            largest = Math.max(largest, step);
        }
        final double lo = smallest;
        final double hi = largest;
        assertTrue(hi / lo <= 2.0, () -> String.format(
            "the hue steps range from %.1f to %.1f degrees; that unevenness reads as a scatter",
            lo, hi));

        // Lightness alternates strictly: pale, saturated, pale, saturated. This is Hypixel's
        // own grammar inverted, and it is load-bearing, not decorative -- see below.
        for (int i = 2; i < bands.size(); i++) {
            double a = Oklab.srgbToOklab(bands.get(i - 2).rgb())[0];
            double b = Oklab.srgbToOklab(bands.get(i - 1).rgb())[0];
            double c = Oklab.srgbToOklab(bands.get(i).rgb())[0];
            assertTrue(Math.signum(b - a) == -Math.signum(c - b),
                "the lightness alternation flattened at band " + i);
        }

        // The counterfactual, measured rather than asserted: rebuild the identical hue and
        // chroma sweep at one flat lightness and the neighbours collapse to 0.042, a third of
        // the floor the table holds. Every band on this half owes most of its separation to the
        // swing, so flattening it would reopen the bug whatever the hues said.
        double flatL = 0;
        for (BracketTable.Bracket b : bands) {
            flatL += Oklab.srgbToOklab(b.rgb())[0] / bands.size();
        }
        double worstFlat = Double.MAX_VALUE;
        for (int i = 1; i < bands.size(); i++) {
            worstFlat = Math.min(worstFlat,
                oklabDistance(atLightness(bands.get(i - 1).rgb(), flatL),
                    atLightness(bands.get(i).rgb(), flatL)));
        }
        final double flat = worstFlat;
        assertTrue(flat < MIN_TABLE_SEPARATION / 2, () -> String.format(
            "flattened to one lightness the same arc measures %.4f between neighbours", flat));
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
    @DisplayName("the default gradient clears a far higher legibility bar than the floor")
    void theDefaultGradientIsComfortablyLegible() {
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
    @DisplayName("the default table: every bracket clears the floor, and SkyPrism's own clear 7.0")
    void theDefaultTableIsLegibleOnBothItsHalves() {
        // The split is the honest statement of what this table is. SkyPrism wrote the colours
        // above 480 and holds them to the same bar as spectrum; below 480 it is reproducing
        // Hypixel's hexes on purpose, and those cannot clear 7.0 -- 0xAA00AA at level 360
        // measures 2.85 and is the table's floor. Lifting it would mean abandoning the vanilla
        // list, which is the one thing users asked us not to do.
        for (BracketTable.Bracket b : PalettePresets.defaultBrackets().brackets()) {
            double k = contrastAgainstDarkUi(b.rgb());
            double bar = b.minLevel() >= TOP_TIER_LEVEL ? MIN_DEFAULT_CONTRAST : MIN_CONTRAST;
            assertTrue(k >= bar, () -> String.format(
                "default table bracket at %d (#%06X) is %.2f:1; bar for that half is %.2f",
                b.minLevel(), b.rgb(), k, bar));
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
        assertSame(PalettePresets.defaultBrackets(), PalettePresets.defaultBrackets());
        assertSame(PalettePresets.rainbow(), PalettePresets.rainbow());
        assertSame(PalettePresets.ocean(), PalettePresets.ocean());
        assertSame(PalettePresets.ember(), PalettePresets.ember());
        assertSame(PalettePresets.toxic(), PalettePresets.toxic());
        assertSame(PalettePresets.neon(), PalettePresets.neon());
        assertSame(PalettePresets.candy(), PalettePresets.candy());
    }

    // ----------------------------------------------------------------- helpers

    /**
     * How far the hue wheel turns going from {@code a} to {@code b} in the sweep's direction.
     *
     * <p>Returned in degrees on 0..360 so a wrap past zero -- the 480 salmon sits at 23 degrees
     * and the 500 rose at 346 -- reads as a 37-degree step rather than a 323-degree jump.
     */
    private static double descendingHueStep(int a, int b) {
        double[] p = Oklab.srgbToOklab(a);
        double[] q = Oklab.srgbToOklab(b);
        double ha = Math.toDegrees(Math.atan2(p[2], p[1]));
        double hb = Math.toDegrees(Math.atan2(q[2], q[1]));
        double step = (ha - hb) % 360.0;
        return step < 0 ? step + 360.0 : step;
    }

    /** The same hue and chroma at a different perceptual lightness. */
    private static int atLightness(int rgb, double lightness) {
        double[] lab = Oklab.srgbToOklab(rgb);
        return Oklab.oklabToSrgb(lightness, lab[1], lab[2]);
    }

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
