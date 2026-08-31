package com.skyprism.core.level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ramps and tables SkyPrism ships with.
 *
 * <p>Every preset is built once into an immutable singleton and handed back by
 * reference. {@link LevelColorMode#VANILLA} resolves through {@link #vanillaBrackets()}
 * on every single tag render, so rebuilding a 13-entry list per lookup would be a
 * needless per-frame allocation.</p>
 *
 * <p><b>On the two vanilla presets:</b> {@link #vanillaBrackets()} is Hypixel's tier
 * table copied exactly -- it is what the mod must produce when a user turns the
 * recolour off, and it is the fixture the other modes are diffed against.
 * {@link #vanillaPlus()} pins the same 13 colours as gradient stops instead, so the
 * ramp still reads as "the level colours everyone knows" while giving each level its
 * own shade between the tiers.</p>
 *
 * <p><b>Why {@link #spectrum()} is the default rather than {@link #vanillaPlus()}.</b>
 * Familiarity was the original argument for shipping Hypixel's own hues, and it lost to
 * the thing a player actually sees: Hypixel's thirteen tiers spend levels 120 through
 * 300 walking green, dark green, aqua, dark aqua, so a whole third of the live range
 * reads as one samey blue-green smear and two players eighty levels apart look alike.
 * {@link #spectrum()} exists to fix exactly that, and {@link #vanillaPlus()} is still one
 * dropdown entry away for anyone who wants the familiar colours back.</p>
 *
 * <p><b>Legibility is a shipped constraint, not a matter of taste.</b> Every colour on
 * every ramp here is drawn as small text over Minecraft's dark chat scrim and the TAB
 * panel, both of which sit near {@code #14151C} once the world behind them is dark. A
 * deep violet or a navy blue simply vanishes there. {@code PalettePresetsTest} measures
 * the WCAG contrast of every level on every registered ramp against that ground and
 * fails below 2.0:1, so a future preset cannot quietly ship a band nobody can read; the
 * default is held to a much higher bar than that on top.</p>
 *
 * <p>The hand-written ramps span 0..600 because that covers the live SkyBlock level range
 * with room above it; {@link GradientRamp} clamps past the ends, so a future level 900
 * keeps the top colour rather than falling off the palette.</p>
 */
public final class PalettePresets {

    /**
     * Config key of the ramp new installs get. Kept here rather than in the config class
     * so the name and the ramp it resolves to cannot drift apart.
     */
    public static final String DEFAULT_PRESET_NAME = "spectrum";

    /** Hypixel's 13 tier colours in tier order, index i covering levels 40*i and up. */
    private static final int[] VANILLA_TIER_RGB = {
        0xAAAAAA, // 0   gray
        0xFFFFFF, // 40  white
        0xFFFF55, // 80  yellow
        0x55FF55, // 120 green
        0x00AA00, // 160 dark green
        0x55FFFF, // 200 aqua
        0x00AAAA, // 240 dark aqua
        0x5555FF, // 280 blue
        0xFF55FF, // 320 light purple
        0xAA00AA, // 360 dark purple
        0xFFAA00, // 400 gold
        0xFF5555, // 440 red
        0xAA0000  // 480 dark red
    };

    /** Levels per Hypixel tier. */
    private static final int TIER_WIDTH = 40;

    /** Levels per fine bracket: half a vanilla tier, giving 25 bands over 0..480. */
    private static final int FINE_WIDTH = 20;

    /**
     * The shipped default: sixteen stops forty levels apart, generated in Oklch.
     *
     * <p>Every stop is {@code L = 0.740}, {@code C = min(0.16, 97% of the in-gamut maximum
     * for that hue)}, with the hue starting at 180 degrees and stepping back 20 degrees per
     * stop, so the ramp travels 300 degrees of the hue wheel between level 0 and level 600:
     * teal, emerald, green, lime, gold, amber, orange, coral, salmon, pink, magenta,
     * orchid, violet, periwinkle, cornflower, sky.</p>
     *
     * <p>Fixing lightness and chroma and moving only hue is the whole design. It is what
     * makes the steps evenly spaced to the eye rather than evenly spaced in a number, and
     * it is why no band goes muddy: the mid-range of {@link #vanillaPlus()} dips through
     * {@code 0x00AA00} and {@code 0x00AAAA}, which are a good deal darker than the white
     * and yellow either side of them, and that lightness lurch is most of what reads as
     * "samey" there. Chroma is capped rather than pushed to the gamut edge because the
     * edge is a different distance away at every hue, and chasing it would put a searing
     * green next to a washed-out blue.</p>
     *
     * <p>Adjacent stops are 20 degrees of hue apart, about 0.045 in Oklab distance -- twice
     * over the threshold where two colours stop being tellable apart -- so the promise that
     * two players forty levels apart look obviously different is measured, not hoped for.
     * The stops are literal hex rather than computed at class-init so the shipped palette
     * is inspectable, copy-pasteable into a custom ramp, and identical on every JVM.</p>
     *
     * <p>Worst measured contrast anywhere on the ramp is 7.3:1 against a {@code #14151C}
     * ground, against 2.3:1 for {@link #vanillaPlus()}.</p>
     */
    private static final GradientRamp SPECTRUM = GradientRamp.of(
        0,   0x1BC5AE,  // teal
        40,  0x23C987,  // emerald
        80,  0x70C35D,  // green
        120, 0x9EB92E,  // lime
        160, 0xC1AC16,  // citron
        200, 0xDB9E16,  // amber
        240, 0xF28F29,  // orange
        280, 0xFD8458,  // coral
        320, 0xFD7F82,  // salmon
        360, 0xFA7BA7,  // rose
        400, 0xEC7FCA,  // magenta
        440, 0xD787E9,  // orchid
        480, 0xBA92FD,  // violet
        520, 0x9BA0FD,  // periwinkle
        560, 0x79ABFD,  // cornflower
        600, 0x3FB5FD); // sky

    private static final GradientRamp VANILLA_PLUS = buildVanillaPlus();

    private static final GradientRamp AURORA = GradientRamp.of(
        0, 0x4A6FA5,
        125, 0x2EC4B6,
        250, 0x7CFF6B,
        375, 0xB18CFF,
        500, 0xFF8FE5);

    // The 0x4B2E83 this ramp used to open on measured 1.7:1 against the chat ground -- a
    // dusk purple that was genuinely invisible on the first hundred levels. The stop was
    // lifted to the same hue at a legible lightness; the rest of the sweep is untouched.
    private static final GradientRamp SUNSET = GradientRamp.of(
        0, 0x7C4FBF,
        125, 0xB23A6E,
        250, 0xFF6B35,
        375, 0xFFB627,
        500, 0xFFF3B0);

    private static final GradientRamp OCEAN = GradientRamp.of(
        0, 0x89F4ED,
        150, 0x1ECFDE,
        300, 0x17ADE2,
        450, 0x0F84F9,
        600, 0x3C5FFB);

    private static final GradientRamp EMBER = GradientRamp.of(
        0, 0xBA0D11,
        150, 0xE62F0C,
        300, 0xFC7A14,
        450, 0xFDB43C,
        600, 0xFEE892);

    private static final GradientRamp TOXIC = GradientRamp.of(
        0, 0x1C8742,
        150, 0x26B63D,
        300, 0x7ADB29,
        450, 0xD5E52A,
        600, 0xFBF8CA);

    private static final GradientRamp NEON = GradientRamp.of(
        0, 0x19BBBC,
        100, 0x1ED980,
        200, 0xBEDF1E,
        300, 0xECB41A,
        400, 0xFC5754,
        500, 0xF518C6,
        600, 0x945DFC);

    private static final GradientRamp CANDY = GradientRamp.of(
        0, 0xADEEDB,
        120, 0xE3E6B0,
        240, 0xFED4BD,
        360, 0xFEC6D6,
        480, 0xE9C6F1,
        600, 0xC5D8FE);

    private static final GradientRamp MONO = GradientRamp.of(
        0, 0x555555,
        250, 0xAAAAAA,
        500, 0xFFFFFF);

    private static final GradientRamp RAINBOW = GradientRamp.of(
        0, 0xFF3B30,
        83, 0xFF9500,
        167, 0xFFD60A,
        250, 0x34C759,
        333, 0x32ADE6,
        417, 0x5856D6,
        500, 0xFF2D95);

    private static final BracketTable VANILLA_BRACKETS = buildVanillaBrackets();
    private static final BracketTable FINE_BRACKETS = buildFineBrackets();

    private static final Map<String, GradientRamp> GRADIENTS = buildGradientIndex();

    private PalettePresets() {
    }

    /**
     * The ramp a fresh install draws with, and the fallback for a config that names
     * something unusable.
     *
     * @return the ramp {@link #DEFAULT_PRESET_NAME} resolves to, currently {@link #spectrum()}
     */
    public static GradientRamp defaultRamp() {
        return SPECTRUM;
    }

    /**
     * An even 300-degree hue sweep across levels 0..600 at fixed perceptual lightness.
     *
     * @return the default ramp: obviously different colours forty levels apart, every one
     *         of them legible on a dark chat background
     */
    public static GradientRamp spectrum() {
        return SPECTRUM;
    }

    /**
     * Hypixel's 13 tier colours as gradient stops, smoothed between them.
     *
     * @return the familiar ramp: tier landmarks everyone already knows, with a distinct
     *         shade per level between them
     */
    public static GradientRamp vanillaPlus() {
        return VANILLA_PLUS;
    }

    /** Cool blue to teal to green to violet to pink, a northern-lights sweep. */
    public static GradientRamp aurora() {
        return AURORA;
    }

    /** Dusk purple through magenta and orange up to pale gold. */
    public static GradientRamp sunset() {
        return SUNSET;
    }

    /** Pale aqua down through teal and azure into a vivid deep blue. */
    public static GradientRamp ocean() {
        return OCEAN;
    }

    /** A coal glowing up: deep red through orange and gold to a pale ash-gold. */
    public static GradientRamp ember() {
        return EMBER;
    }

    /** Dark moss climbing through acid green and chartreuse to a bleached yellow. */
    public static GradientRamp toxic() {
        return TOXIC;
    }

    /** Every stop pushed to the edge of the sRGB gamut: an arcade sign, deliberately loud. */
    public static GradientRamp neon() {
        return NEON;
    }

    /** The same hue sweep as {@link #spectrum()} in chalk: high lightness, barely any chroma. */
    public static GradientRamp candy() {
        return CANDY;
    }

    /** Grey to white: no hue at all, for players who want level legible but not loud. */
    public static GradientRamp mono() {
        return MONO;
    }

    /** A full hue sweep across the level range, red back around to magenta. */
    public static GradientRamp rainbow() {
        return RAINBOW;
    }

    /**
     * Hypixel's tier table, exactly: 13 brackets 40 levels apart starting at 0.
     *
     * @return the table {@link LevelColorMode#VANILLA} resolves through
     */
    public static BracketTable vanillaBrackets() {
        return VANILLA_BRACKETS;
    }

    /**
     * A 25-band table on 20-level boundaries, sampled off {@link #vanillaPlus()}.
     *
     * <p>Every even band lands on a real Hypixel tier colour and every odd one is the
     * perceptual midpoint between two of them, so the table stays recognisable while
     * changing colour twice as often as the server's own. It stays keyed to
     * {@link #vanillaPlus()} rather than following the gradient default: the point of this
     * table is that its landmarks are Hypixel's, and {@link LevelColorMode#BRACKETS} is not
     * the shipped mode anyway.</p>
     *
     * @return the finer-grained default bracket table
     */
    public static BracketTable fineBrackets() {
        return FINE_BRACKETS;
    }

    /**
     * Every gradient preset by config name, in presentation order.
     *
     * <p>The settings screen builds its dropdown straight off this map, so registering a
     * ramp here is the whole of adding a preset. The order is the order the dropdown shows:
     * the default first, then the familiar one, then the rest grouped cool to warm with the
     * two deliberately extreme ramps and the colourless one last.</p>
     *
     * @return an unmodifiable name-to-ramp map; the keys are the tokens a config file
     *         or a chat command uses, so they are lowercase and underscore-separated
     *         and must stay stable across releases
     */
    public static Map<String, GradientRamp> gradients() {
        return GRADIENTS;
    }

    private static GradientRamp buildVanillaPlus() {
        var stops = new ArrayList<GradientRamp.Stop>(VANILLA_TIER_RGB.length);
        for (int i = 0; i < VANILLA_TIER_RGB.length; i++) {
            stops.add(new GradientRamp.Stop(i * TIER_WIDTH, VANILLA_TIER_RGB[i]));
        }
        return new GradientRamp(stops);
    }

    private static BracketTable buildVanillaBrackets() {
        var brackets = new ArrayList<BracketTable.Bracket>(VANILLA_TIER_RGB.length);
        for (int i = 0; i < VANILLA_TIER_RGB.length; i++) {
            brackets.add(new BracketTable.Bracket(i * TIER_WIDTH, VANILLA_TIER_RGB[i]));
        }
        return new BracketTable(brackets);
    }

    private static BracketTable buildFineBrackets() {
        int top = (VANILLA_TIER_RGB.length - 1) * TIER_WIDTH;
        var brackets = new ArrayList<BracketTable.Bracket>();
        for (int level = 0; level <= top; level += FINE_WIDTH) {
            brackets.add(new BracketTable.Bracket(level, VANILLA_PLUS.colorAt(level)));
        }
        return new BracketTable(brackets);
    }

    private static Map<String, GradientRamp> buildGradientIndex() {
        var map = new LinkedHashMap<String, GradientRamp>();
        map.put(DEFAULT_PRESET_NAME, SPECTRUM);
        map.put("vanilla_plus", VANILLA_PLUS);
        map.put("aurora", AURORA);
        map.put("ocean", OCEAN);
        map.put("sunset", SUNSET);
        map.put("ember", EMBER);
        map.put("toxic", TOXIC);
        map.put("neon", NEON);
        map.put("candy", CANDY);
        map.put("rainbow", RAINBOW);
        map.put("mono", MONO);
        return Collections.unmodifiableMap(map);
    }
}
