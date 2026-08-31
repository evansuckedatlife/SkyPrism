package com.skyprism.core.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.config.SkyPrismConfig.DianaSettings;
import com.skyprism.core.config.SkyPrismConfig.HudSettings;
import com.skyprism.core.config.SkyPrismConfig.LevelSettings;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.level.BracketTable;
import com.skyprism.core.level.GradientRamp;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.PalettePresets;
import com.skyprism.core.util.TextClean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the settings model itself: what the shipped defaults are, and what
 * {@link SkyPrismConfig#sanitized()} does to a file someone has broken.
 *
 * <p>Every "hostile" case here is one a real file can actually be in -- a hand-edited
 * number out of range, a null Gson left behind for an enum name it did not recognise,
 * a whole group written as {@code null}.
 */
class SkyPrismConfigTest {

    /** The section sign, taken from the shared constant so no source encoding can affect it. */
    private static final String S = String.valueOf(TextClean.SECTION);

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("every group is populated and nothing is null")
        void defaultsArePopulated() {
            var c = SkyPrismConfig.defaults();
            assertEquals(SkyPrismConfig.CONFIG_VERSION, c.configVersion);
            assertNotNull(c.levels);
            assertNotNull(c.diana);
            assertNotNull(c.hud);
            assertNotNull(c.sounds);
            assertNotNull(c.levels.mode);
            assertNotNull(c.levels.customStops);
            assertNotNull(c.levels.brackets);
            assertNotNull(c.diana.triggers);
            assertNotNull(c.diana.jackpotItems);
            assertNotNull(c.hud.anchor);
        }

        @Test
        @DisplayName("a fresh install draws the spectrum ramp with the whole tag coloured")
        void theTwoLevelDefaultsAreWhatTheUserChose() {
            var levels = SkyPrismConfig.defaults().levels;
            assertEquals(PalettePresets.DEFAULT_PRESET_NAME, levels.gradientPreset);
            assertEquals(LevelSettings.DEFAULT_PRESET, levels.gradientPreset);
            assertSame(PalettePresets.defaultRamp(), levels.resolveRamp());
            // Both of these were flipped after the mod was looked at on a live server:
            // Hypixel's own tiers were too samey through the middle of the range, and the
            // dim-bracket styling lost to the fully coloured tag. Pinned so neither drifts
            // back on a refactor.
            assertTrue(levels.recolourBrackets, "the whole tag is coloured by default");
            assertEquals(PalettePresets.defaultRamp().stops(), levels.customStops,
                "custom stops start from what the player is already looking at");
        }

        @Test
        @DisplayName("defaults are already sanitized, so a first launch changes nothing")
        void defaultsSurviveSanitizing() {
            assertEquals(SkyPrismConfig.defaults(), SkyPrismConfig.defaults().sanitized());
        }

        @Test
        @DisplayName("two defaults are equal but independent")
        void defaultsAreIndependent() {
            var a = SkyPrismConfig.defaults();
            var b = SkyPrismConfig.defaults();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            a.levels.customStops.clear();
            assertFalse(b.levels.customStops.isEmpty(), "the default stop list must not be shared");
        }

        @Test
        @DisplayName("the Diana triggers default to the rare creatures the enum nominates")
        void triggerDefaultsComeFromTheEnum() {
            assertEquals(new LinkedHashSet<>(MythologicalCreature.defaultTriggers()),
                    SkyPrismConfig.defaults().diana.triggers);
        }
    }

    @Nested
    @DisplayName("sanitized() numeric clamps")
    class Clamps {

        @Test
        @DisplayName("chroma speed and refresh rate clamp to their published bounds")
        void chromaClamps() {
            var low = SkyPrismConfig.defaults();
            low.levels.chromaCyclesPerSecond = -5.0;
            low.levels.chromaUpdateHz = 0;
            var lowClean = low.sanitized();
            assertEquals(LevelSettings.MIN_CHROMA_CPS, lowClean.levels.chromaCyclesPerSecond);
            assertEquals(LevelSettings.MIN_CHROMA_HZ, lowClean.levels.chromaUpdateHz);

            var high = SkyPrismConfig.defaults();
            high.levels.chromaCyclesPerSecond = 5_000.0;
            high.levels.chromaUpdateHz = 100_000;
            var highClean = high.sanitized();
            assertEquals(LevelSettings.MAX_CHROMA_CPS, highClean.levels.chromaCyclesPerSecond);
            assertEquals(LevelSettings.MAX_CHROMA_HZ, highClean.levels.chromaUpdateHz);
        }

        @Test
        @DisplayName("NaN and infinity are replaced rather than passed through")
        void nonFiniteDoublesAreRepaired() {
            var c = SkyPrismConfig.defaults();
            c.levels.chromaCyclesPerSecond = Double.NaN;
            c.hud.x = Double.NaN;
            c.hud.y = Double.POSITIVE_INFINITY;
            c.hud.scale = Double.NEGATIVE_INFINITY;
            c.hud.backgroundOpacity = Double.NaN;
            c.sounds.volume = Double.NaN;

            var clean = c.sanitized();
            assertEquals(0.35, clean.levels.chromaCyclesPerSecond, "NaN speed falls back");
            assertEquals(0.5, clean.hud.x, "NaN position falls back to centre");
            assertEquals(1.0, clean.hud.y, "+inf clamps to the bottom edge");
            assertEquals(HudSettings.MIN_SCALE, clean.hud.scale, "-inf clamps to the smallest scale");
            assertEquals(0.55, clean.hud.backgroundOpacity);
            assertEquals(0.7, clean.sounds.volume);
            assertTrue(Double.isFinite(clean.hud.x) && Double.isFinite(clean.hud.y),
                    "no non-finite value may reach the renderer");
        }

        @Test
        @DisplayName("the HUD position and scale clamp into the window")
        void hudClamps() {
            var c = SkyPrismConfig.defaults();
            c.hud.x = -3.0;
            c.hud.y = 40.0;
            c.hud.scale = 99.0;
            c.hud.backgroundOpacity = 7.5;
            var clean = c.sanitized();
            assertEquals(0.0, clean.hud.x);
            assertEquals(1.0, clean.hud.y);
            assertEquals(HudSettings.MAX_SCALE, clean.hud.scale);
            assertEquals(1.0, clean.hud.backgroundOpacity);
        }

        @Test
        @DisplayName("volume clamps to 0..1")
        void volumeClamps() {
            var loud = SkyPrismConfig.defaults();
            loud.sounds.volume = 12.0;
            assertEquals(1.0, loud.sanitized().sounds.volume);

            var quiet = SkyPrismConfig.defaults();
            quiet.sounds.volume = -1.0;
            assertEquals(0.0, quiet.sanitized().sounds.volume);
        }

        @Test
        @DisplayName("reelCount is forced into 1..5 in both directions")
        void reelCountClamps() {
            var none = SkyPrismConfig.defaults();
            none.diana.reelCount = 0;
            assertEquals(SlotRollConfig.MIN_REELS, none.sanitized().diana.reelCount);

            var many = SkyPrismConfig.defaults();
            many.diana.reelCount = 40;
            assertEquals(SlotRollConfig.MAX_REELS, many.sanitized().diana.reelCount);

            var negative = SkyPrismConfig.defaults();
            negative.diana.reelCount = Integer.MIN_VALUE;
            assertEquals(SlotRollConfig.MIN_REELS, negative.sanitized().diana.reelCount);
        }

        @Test
        @DisplayName("the loot window clamps, and every roll timing stays inside SlotRollConfig")
        void dianaTimingClamps() {
            var c = SkyPrismConfig.defaults();
            c.diana.lootWindowMillis = -1;
            c.diana.spinMillis = Long.MAX_VALUE;
            c.diana.lockStaggerMillis = -900;
            c.diana.settleMillis = Long.MIN_VALUE;
            c.diana.fadeMillis = 999_999;
            c.diana.jackpotIntroMillis = -4;
            c.diana.jackpotSpinMillis = Long.MAX_VALUE;
            c.diana.jackpotLockStaggerMillis = Long.MIN_VALUE;
            c.diana.jackpotHoldMillis = 999_999;

            var d = c.sanitized().diana;
            assertEquals(DianaSettings.MIN_LOOT_WINDOW_MILLIS, d.lootWindowMillis);
            assertEquals(DianaSettings.MAX_SPIN_MILLIS, d.spinMillis);
            assertEquals(0L, d.lockStaggerMillis);
            assertEquals(0L, d.settleMillis);
            assertEquals(DianaSettings.MAX_FADE_MILLIS, d.fadeMillis);
            assertEquals(0L, d.jackpotIntroMillis);
            assertEquals(DianaSettings.MAX_JACKPOT_SPIN_MILLIS, d.jackpotSpinMillis);
            assertEquals(0L, d.jackpotLockStaggerMillis);
            assertEquals(DianaSettings.MAX_JACKPOT_HOLD_MILLIS, d.jackpotHoldMillis);

            var roll = assertDoesNotThrow(d::toRollConfig,
                    "a sanitized config must never build an invalid SlotRollConfig");
            assertEquals(d.reelCount, roll.reelCount());
            assertEquals(d.lootWindowMillis, roll.lootWindowMillis());
        }

        @Test
        @DisplayName("toRollConfig() survives even an unsanitized config")
        void rollConfigNeverThrows() {
            var wild = new DianaSettings();
            wild.reelCount = 900;
            wild.spinMillis = Long.MIN_VALUE;
            wild.lootWindowMillis = Long.MAX_VALUE;
            var roll = assertDoesNotThrow(wild::toRollConfig);
            assertEquals(SlotRollConfig.MAX_REELS, roll.reelCount());
            assertEquals(0L, roll.spinMillis());
            assertEquals(DianaSettings.MAX_LOOT_WINDOW_MILLIS, roll.lootWindowMillis());
        }

        @Test
        @DisplayName("configVersion is clamped into the range this build understands")
        void versionClamps() {
            var ancient = SkyPrismConfig.defaults();
            ancient.configVersion = -12;
            assertEquals(1, ancient.sanitized().configVersion);

            var future = SkyPrismConfig.defaults();
            future.configVersion = 9_999;
            assertEquals(SkyPrismConfig.CONFIG_VERSION, future.sanitized().configVersion);
        }
    }

    @Nested
    @DisplayName("sanitized() level range")
    class LevelRange {

        @Test
        @DisplayName("an inverted range is widened, not snapped, so both edits survive")
        void invertedRangeIsWidened() {
            var c = SkyPrismConfig.defaults();
            c.levels.minLevel = 900;
            c.levels.maxLevel = 100;
            var clean = c.sanitized();
            assertEquals(100, clean.levels.minLevel);
            assertEquals(900, clean.levels.maxLevel);
            assertTrue(clean.levels.minLevel <= clean.levels.maxLevel);
        }

        @Test
        @DisplayName("negative and absurd bounds clamp to the locator's own limits")
        void boundsClamp() {
            var c = SkyPrismConfig.defaults();
            c.levels.minLevel = Integer.MIN_VALUE;
            c.levels.maxLevel = Integer.MAX_VALUE;
            c.levels.chromaMinLevel = -7;
            var clean = c.sanitized();
            assertEquals(LevelSettings.LEVEL_FLOOR, clean.levels.minLevel);
            assertEquals(LevelSettings.LEVEL_CEILING, clean.levels.maxLevel);
            assertEquals(LevelSettings.LEVEL_FLOOR, clean.levels.chromaMinLevel);
        }

        @Test
        @DisplayName("the sanitized range always builds a locator")
        void sanitizedRangeBuildsALocator() {
            var c = SkyPrismConfig.defaults();
            c.levels.minLevel = 700;
            c.levels.maxLevel = -400;
            var clean = c.sanitized();
            var locator = assertDoesNotThrow(clean.levels::resolveLocator);
            assertEquals(0, locator.minLevel());
            assertEquals(700, locator.maxLevel());
        }

        @Test
        @DisplayName("resolveLocator() falls back rather than throwing on a raw inverted range")
        void locatorFallsBack() {
            var raw = new LevelSettings();
            raw.minLevel = 800;
            raw.maxLevel = 3;
            var locator = assertDoesNotThrow(raw::resolveLocator);
            assertEquals(0, locator.minLevel());
            assertEquals(1000, locator.maxLevel());
        }
    }

    @Nested
    @DisplayName("sanitized() gradient stops and brackets")
    class Tables {

        @Test
        @DisplayName("stops come back sorted")
        void stopsAreSorted() {
            var c = SkyPrismConfig.defaults();
            c.levels.customStops = new ArrayList<>(List.of(
                    new GradientRamp.Stop(300, 0x00FF00),
                    new GradientRamp.Stop(0, 0xFF0000),
                    new GradientRamp.Stop(150, 0x0000FF)));
            var stops = c.sanitized().levels.customStops;
            assertEquals(List.of(0, 150, 300), stops.stream().map(GradientRamp.Stop::level).toList());
        }

        @Test
        @DisplayName("an empty stop list is refilled, because a gradient with no colours cannot render")
        void emptyStopsAreRefilled() {
            var c = SkyPrismConfig.defaults();
            c.levels.customStops = new ArrayList<>();
            c.levels.brackets = new ArrayList<>();
            var clean = c.sanitized();
            assertEquals(PalettePresets.defaultRamp().stops(), clean.levels.customStops);
            assertEquals(PalettePresets.fineBrackets().brackets(), clean.levels.brackets);
        }

        @Test
        @DisplayName("null lists and null entries inside them are handled")
        void nullTablesAreHandled() {
            var c = SkyPrismConfig.defaults();
            c.levels.customStops = null;
            c.levels.brackets = new ArrayList<>(Arrays.asList(
                    new BracketTable.Bracket(0, 0x111111), null, new BracketTable.Bracket(50, 0x222222)));
            var clean = c.sanitized();
            assertFalse(clean.levels.customStops.isEmpty());
            assertEquals(2, clean.levels.brackets.size());
        }

        @Test
        @DisplayName("duplicate levels are dropped so GradientRamp cannot reject the result")
        void duplicatesAreDropped() {
            var c = SkyPrismConfig.defaults();
            c.levels.customStops = new ArrayList<>(List.of(
                    new GradientRamp.Stop(100, 0xFF0000),
                    new GradientRamp.Stop(100, 0x00FF00),
                    new GradientRamp.Stop(200, 0x0000FF)));
            c.levels.brackets = new ArrayList<>(List.of(
                    new BracketTable.Bracket(40, 0xAAAAAA),
                    new BracketTable.Bracket(40, 0xBBBBBB)));

            var clean = c.sanitized().levels;
            assertEquals(2, clean.customStops.size());
            assertEquals(0xFF0000, clean.customStops.get(0).rgb(), "the first stop at a level wins");
            assertEquals(1, clean.brackets.size());
            assertDoesNotThrow(() -> new GradientRamp(clean.customStops));
            assertDoesNotThrow(() -> new BracketTable(clean.brackets));
        }

        @Test
        @DisplayName("clamping two out-of-range stops onto the same level still leaves one stop")
        void clampCollisionsAreDeduplicated() {
            var c = SkyPrismConfig.defaults();
            c.levels.customStops = new ArrayList<>(List.of(
                    new GradientRamp.Stop(-500, 0x111111),
                    new GradientRamp.Stop(-9, 0x222222),
                    new GradientRamp.Stop(Integer.MAX_VALUE, 0x333333),
                    new GradientRamp.Stop(Integer.MAX_VALUE - 1, 0x444444)));
            var stops = c.sanitized().levels.customStops;
            assertEquals(List.of(LevelSettings.LEVEL_FLOOR, LevelSettings.LEVEL_CEILING),
                    stops.stream().map(GradientRamp.Stop::level).toList());
            assertDoesNotThrow(() -> new GradientRamp(stops));
        }

        @Test
        @DisplayName("an absurd table is truncated to the published cap")
        void tablesAreCapped() {
            var c = SkyPrismConfig.defaults();
            var many = new ArrayList<GradientRamp.Stop>();
            for (int i = 0; i < 5_000; i++) {
                many.add(new GradientRamp.Stop(i, i));
            }
            c.levels.customStops = many;
            assertEquals(LevelSettings.MAX_TABLE_ENTRIES, c.sanitized().levels.customStops.size());
        }
    }

    @Nested
    @DisplayName("sanitized() enums, presets and sets")
    class EnumsAndSets {

        @Test
        @DisplayName("a null mode or anchor -- what Gson leaves for an unknown name -- is replaced")
        void nullEnumsAreReplaced() {
            var c = SkyPrismConfig.defaults();
            c.levels.mode = null;
            c.hud.anchor = null;
            var clean = c.sanitized();
            assertEquals(LevelColorMode.GRADIENT, clean.levels.mode);
            assertEquals(HudAnchor.TOP_CENTER, clean.hud.anchor);
        }

        @Test
        @DisplayName("an unknown preset name falls back, a sloppily spelled known one is normalised")
        void presetNamesAreRepaired() {
            var unknown = SkyPrismConfig.defaults();
            unknown.levels.gradientPreset = "chartreuse_dream";
            assertEquals(LevelSettings.DEFAULT_PRESET, unknown.sanitized().levels.gradientPreset);

            var sloppy = SkyPrismConfig.defaults();
            sloppy.levels.gradientPreset = "  Vanilla Plus ";
            assertEquals("vanilla_plus", sloppy.sanitized().levels.gradientPreset);

            var nulled = SkyPrismConfig.defaults();
            nulled.levels.gradientPreset = null;
            assertEquals(LevelSettings.DEFAULT_PRESET, nulled.sanitized().levels.gradientPreset);

            var custom = SkyPrismConfig.defaults();
            custom.levels.gradientPreset = LevelSettings.CUSTOM_PRESET;
            assertEquals(LevelSettings.CUSTOM_PRESET, custom.sanitized().levels.gradientPreset);
        }

        @Test
        @DisplayName("a null inside the trigger set is dropped and the rest re-ordered")
        void nullTriggersAreDropped() {
            var c = SkyPrismConfig.defaults();
            var raw = new LinkedHashSet<MythologicalCreature>();
            raw.add(MythologicalCreature.MANTICORE);
            raw.add(null);
            raw.add(MythologicalCreature.MINOTAUR);
            c.diana.triggers = raw;

            var clean = c.sanitized().diana.triggers;
            assertEquals(List.of(MythologicalCreature.MINOTAUR, MythologicalCreature.MANTICORE),
                    List.copyOf(clean), "nulls gone and enum order restored for a stable file");
        }

        @Test
        @DisplayName("a null trigger set returns to defaults but an emptied one stays empty")
        void nullAndEmptyTriggersDiffer() {
            var nulled = SkyPrismConfig.defaults();
            nulled.diana.triggers = null;
            assertEquals(new LinkedHashSet<>(MythologicalCreature.defaultTriggers()),
                    nulled.sanitized().diana.triggers);

            var emptied = SkyPrismConfig.defaults();
            emptied.diana.triggers = new LinkedHashSet<>();
            assertTrue(emptied.sanitized().diana.triggers.isEmpty(),
                    "an empty trigger set is a real choice, not damage");
        }

        @Test
        @DisplayName("jackpot names are stripped of colour codes and blanks are dropped")
        void jackpotNamesAreCleaned() {
            var c = SkyPrismConfig.defaults();
            c.diana.jackpotItems = new LinkedHashSet<>(Arrays.asList(
                    S + "6Daedalus  Stick" + S + "r", "   ", null, "Crown of Greed"));
            var clean = c.sanitized().diana.jackpotItems;
            assertEquals(List.of("Daedalus Stick", "Crown of Greed"), List.copyOf(clean));
        }

        @Test
        @DisplayName("a null jackpot set becomes empty rather than exploding at the first drop")
        void nullJackpotSet() {
            var c = SkyPrismConfig.defaults();
            c.diana.jackpotItems = null;
            var clean = c.sanitized();
            assertTrue(clean.diana.jackpotItems.isEmpty());
            assertFalse(clean.diana.isJackpot("Daedalus Stick"));
        }

        @Test
        @DisplayName("isJackpot ignores case and formatting, and never trips on null")
        void jackpotMatching() {
            var d = SkyPrismConfig.defaults().diana;
            assertTrue(d.isJackpot("Daedalus Stick"));
            assertTrue(d.isJackpot("daedalus stick"));
            assertTrue(d.isJackpot(S + "6" + S + "lDaedalus Stick" + S + "r"));
            assertFalse(d.isJackpot("Griffin Feather"));
            assertFalse(d.isJackpot(null));
            assertFalse(d.isJackpot("   "));
        }
    }

    @Nested
    @DisplayName("sanitized() never throws and never aliases")
    class Robustness {

        @Test
        @DisplayName("every group written as null is rebuilt")
        void nullGroupsAreRebuilt() {
            var c = new SkyPrismConfig();
            c.levels = null;
            c.diana = null;
            c.hud = null;
            c.sounds = null;
            var clean = assertDoesNotThrow(c::sanitized);
            assertEquals(SkyPrismConfig.defaults(), clean);
        }

        @Test
        @DisplayName("the copy is deep: mutating the source cannot reach the sanitized result")
        void sanitizedIsADeepCopy() {
            var c = SkyPrismConfig.defaults();
            var clean = c.sanitized();
            assertNotSame(c.levels, clean.levels);
            assertNotSame(c.levels.customStops, clean.levels.customStops);
            assertNotSame(c.diana.triggers, clean.diana.triggers);

            c.levels.customStops.clear();
            c.diana.triggers.clear();
            c.diana.jackpotItems.clear();
            assertFalse(clean.levels.customStops.isEmpty());
            assertFalse(clean.diana.triggers.isEmpty());
            assertFalse(clean.diana.jackpotItems.isEmpty());
        }

        @Test
        @DisplayName("copy() keeps values the sanitiser would have changed, for the cancel button")
        void copyDoesNotRepair() {
            var c = SkyPrismConfig.defaults();
            c.diana.reelCount = 99;
            c.levels.gradientPreset = "nonsense";
            var snapshot = c.copy();
            assertEquals(99, snapshot.diana.reelCount);
            assertEquals("nonsense", snapshot.levels.gradientPreset);
            assertNotSame(c.diana, snapshot.diana);
        }

        @Test
        @DisplayName("sanitizing twice changes nothing the second time")
        void sanitizeIsIdempotent() {
            var messy = SkyPrismConfig.defaults();
            messy.levels.minLevel = 5_000;
            messy.levels.maxLevel = -1;
            messy.levels.chromaCyclesPerSecond = Double.NaN;
            messy.diana.reelCount = 0;
            messy.hud.anchor = null;
            var once = messy.sanitized();
            assertEquals(once, once.sanitized());
        }
    }

    @Nested
    @DisplayName("palette resolution")
    class Resolution {

        @Test
        @DisplayName("a named preset resolves to the shipped singleton")
        void namedPresetResolves() {
            var levels = new LevelSettings();
            levels.gradientPreset = "aurora";
            assertSame(PalettePresets.aurora(), levels.resolveRamp());
        }

        @Test
        @DisplayName("the custom key resolves to the user's own stops")
        void customPresetResolves() {
            var levels = new LevelSettings();
            levels.gradientPreset = LevelSettings.CUSTOM_PRESET;
            levels.customStops = new ArrayList<>(List.of(
                    new GradientRamp.Stop(0, 0x000000),
                    new GradientRamp.Stop(100, 0xFFFFFF)));
            assertEquals(0x000000, levels.resolveRamp().colorAt(0));
            assertEquals(0xFFFFFF, levels.resolveRamp().colorAt(100));
        }

        @Test
        @DisplayName("an unusable custom ramp falls back instead of throwing at render time")
        void brokenCustomRampFallsBack() {
            var levels = new LevelSettings();
            levels.gradientPreset = LevelSettings.CUSTOM_PRESET;
            levels.customStops = new ArrayList<>();
            assertSame(PalettePresets.defaultRamp(), assertDoesNotThrow(levels::resolveRamp));

            levels.customStops = null;
            assertSame(PalettePresets.defaultRamp(), assertDoesNotThrow(levels::resolveRamp));
        }

        @Test
        @DisplayName("VANILLA resolves to Hypixel's own thirteen tiers whatever else is configured")
        void vanillaModeIgnoresTheUserTable() {
            var levels = new LevelSettings();
            levels.mode = LevelColorMode.VANILLA;
            levels.brackets = new ArrayList<>(List.of(new BracketTable.Bracket(0, 0x123456)));
            assertSame(PalettePresets.vanillaBrackets(), levels.resolveTable());
            assertEquals(0xAAAAAA, levels.resolveTable().colorAt(0));
            assertEquals(0xAA0000, levels.resolveTable().colorAt(480));
        }

        @Test
        @DisplayName("a broken bracket table falls back rather than throwing")
        void brokenTableFallsBack() {
            var levels = new LevelSettings();
            levels.mode = LevelColorMode.BRACKETS;
            levels.brackets = new ArrayList<>();
            assertSame(PalettePresets.fineBrackets(), assertDoesNotThrow(levels::resolveTable));
        }
    }

    @Nested
    @DisplayName("HudAnchor")
    class Anchors {

        @Test
        @DisplayName("a top-left anchored widget sits exactly where the fraction points")
        void topLeftIsUnshifted() {
            double[] at = HudAnchor.TOP_LEFT.topLeft(1920, 1080, 100, 40, 0.5, 0.5);
            assertEquals(960.0, at[0]);
            assertEquals(540.0, at[1]);
        }

        @Test
        @DisplayName("a right-anchored widget stays on screen where a left-anchored one would not")
        void rightAnchorKeepsTheWidgetOnScreen() {
            double[] left = HudAnchor.TOP_LEFT.topLeft(400, 300, 120, 40, 0.98, 0.0);
            double[] right = HudAnchor.TOP_RIGHT.topLeft(400, 300, 120, 40, 0.98, 0.0);
            assertTrue(left[0] + 120 > 400, "the left-anchored widget really does overflow");
            assertTrue(right[0] + 120 <= 400, "the right-anchored one does not");
        }

        @Test
        @DisplayName("centre anchoring subtracts half the widget in both axes")
        void centreAnchorCentres() {
            double[] at = HudAnchor.MIDDLE_CENTER.topLeft(1000, 800, 200, 100, 0.5, 0.5);
            assertEquals(400.0, at[0]);
            assertEquals(350.0, at[1]);
        }
    }

    @Nested
    @DisplayName("shimmer saturation and lightness")
    class ChromaColour {

        @Test
        @DisplayName("the shipped values are the ones the adapter used to hard-code")
        void defaultsMatchTheOldConstants() {
            var levels = SkyPrismConfig.defaults().levels;
            assertEquals(0.90, levels.chromaSaturation);
            assertEquals(0.62, levels.chromaLightness);
            assertEquals(LevelSettings.DEFAULT_CHROMA_SATURATION, levels.chromaSaturation);
            assertEquals(LevelSettings.DEFAULT_CHROMA_LIGHTNESS, levels.chromaLightness);
        }

        @Test
        @DisplayName("both clamp into 0..1, which is the band ChromaClock will accept")
        void clampsIntoTheUnitBand() {
            var low = SkyPrismConfig.defaults();
            low.levels.chromaSaturation = -2.5;
            low.levels.chromaLightness = -0.001;
            var lowClean = low.sanitized().levels;
            assertEquals(LevelSettings.MIN_CHROMA_SATURATION, lowClean.chromaSaturation);
            assertEquals(LevelSettings.MIN_CHROMA_LIGHTNESS, lowClean.chromaLightness);

            var high = SkyPrismConfig.defaults();
            high.levels.chromaSaturation = 42.0;
            high.levels.chromaLightness = 1.0001;
            var highClean = high.sanitized().levels;
            assertEquals(LevelSettings.MAX_CHROMA_SATURATION, highClean.chromaSaturation);
            assertEquals(LevelSettings.MAX_CHROMA_LIGHTNESS, highClean.chromaLightness);
        }

        @Test
        @DisplayName("the ends of the band are legal, because a slider has to be able to reach them")
        void theBoundsThemselvesSurvive() {
            var c = SkyPrismConfig.defaults();
            c.levels.chromaSaturation = 0.0;
            c.levels.chromaLightness = 1.0;
            var clean = c.sanitized().levels;
            assertEquals(0.0, clean.chromaSaturation);
            assertEquals(1.0, clean.chromaLightness);
        }

        @Test
        @DisplayName("NaN falls back to the shipped value rather than through to the renderer")
        void nanFallsBack() {
            var c = SkyPrismConfig.defaults();
            c.levels.chromaSaturation = Double.NaN;
            c.levels.chromaLightness = Double.NaN;
            var clean = c.sanitized().levels;
            assertEquals(LevelSettings.DEFAULT_CHROMA_SATURATION, clean.chromaSaturation);
            assertEquals(LevelSettings.DEFAULT_CHROMA_LIGHTNESS, clean.chromaLightness);
        }

        @Test
        @DisplayName("infinities clamp to the ends rather than staying infinite")
        void infinitiesClamp() {
            var c = SkyPrismConfig.defaults();
            c.levels.chromaSaturation = Double.POSITIVE_INFINITY;
            c.levels.chromaLightness = Double.NEGATIVE_INFINITY;
            var clean = c.sanitized().levels;
            assertEquals(LevelSettings.MAX_CHROMA_SATURATION, clean.chromaSaturation);
            assertEquals(LevelSettings.MIN_CHROMA_LIGHTNESS, clean.chromaLightness);
        }

        @Test
        @DisplayName("copy() keeps an out-of-range value, so cancel really does restore it")
        void copyDoesNotRepair() {
            var c = SkyPrismConfig.defaults();
            c.levels.chromaSaturation = 9.0;
            assertEquals(9.0, c.copy().levels.chromaSaturation);
        }

        @Test
        @DisplayName("the two are part of the settings' identity")
        void equalityNoticesThem() {
            var a = SkyPrismConfig.defaults();
            var b = SkyPrismConfig.defaults();
            b.levels.chromaSaturation = 0.5;
            assertNotEquals(a, b);

            b.levels.chromaSaturation = a.levels.chromaSaturation;
            b.levels.chromaLightness = 0.5;
            assertNotEquals(a, b);
        }
    }

}
