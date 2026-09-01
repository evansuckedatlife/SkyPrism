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
 * <p><b>What a fresh install actually draws: {@link #defaultBrackets()}, not a gradient.</b>
 * A ramp that repaints on every single level was the shipped default for four config
 * schemas and the feedback on it was unanimous -- it is too much movement for a number
 * that changes once a week, and it throws away the tier landmarks players already read at
 * a glance. The table keeps Hypixel's own scheme below 480 and doubles its resolution,
 * then spends thirteen colours of its own above 480, which is where Hypixel simply stops:
 * the server's last tier is {@code 0xAA0000} and every level from 480 upwards is that one
 * dark red forever.</p>
 *
 * <p><b>{@link #spectrum()} is still the default gradient</b> -- what
 * {@link #DEFAULT_PRESET_NAME} resolves to, what {@link #defaultRamp()} hands back, and
 * what a user sees the moment they switch the mode to {@link LevelColorMode#GRADIENT}. It
 * exists because Hypixel's thirteen tiers spend levels 120 through 300 walking green, dark
 * green, aqua, dark aqua, so a third of the live range reads as one blue-green smear and
 * two players eighty levels apart look alike. It is held to the highest legibility bar of
 * anything here, and {@link #vanillaPlus()} is one dropdown entry away for anyone who
 * wants nothing but the familiar colours.</p>
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
     * Config key of the gradient new installs carry. Kept here rather than in the config
     * class so the name and the ramp it resolves to cannot drift apart.
     *
     * <p>This names the default <i>ramp</i>, not the default palette: a fresh install runs
     * in {@link LevelColorMode#BRACKETS} on {@link #defaultBrackets()}, and this is the
     * gradient sitting behind the mode switch.</p>
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
     * Levels per SkyPrism band above {@link #HYPIXEL_TOP_TIER_LEVEL}: half a fine bracket.
     *
     * <p>The bands get twice the resolution of the vanilla half because that is where the
     * levels people actually compare are. Below 480 a 20-level band is already finer than
     * anything the server draws; above it, an unmodded client draws one colour for every
     * level that will ever exist, so ten is as coarse as this half can afford to be.</p>
     */
    private static final int BAND_WIDTH = 10;

    /**
     * Where Hypixel's scale runs out: the first level of its last tier, {@code 0xAA0000}.
     *
     * <p>Derived from the tier table rather than written as 480, so the two halves of
     * {@link #defaultBrackets()} cannot drift apart if the tier list is ever corrected.</p>
     */
    private static final int HYPIXEL_TOP_TIER_LEVEL = (VANILLA_TIER_RGB.length - 1) * TIER_WIDTH;

    /**
     * SkyPrism's own thirteen bands, covering levels 480, 490, ... 600 in order.
     *
     * <p>These are the only colours in the shipped table that are not Hypixel's. They live
     * above 480 because that is the whole of the range Hypixel leaves uncoloured -- its last
     * tier is one dark red that every level from 480 to infinity shares -- and because
     * spending new hues below 480 is exactly what players objected to.</p>
     *
     * <p><b>Hue.</b> An even sweep from 350 degrees down to 212, 11.5 degrees per band:
     * rose, magenta, orchid, violet, periwinkle, cornflower, sky, cyan. That arc is roughly
     * the 150 degrees of the wheel Hypixel never visits at a legible lightness, and it
     * terminates at cyan because the next step round is green, which is level 120. 600 is
     * also the default {@code chromaMinLevel}, so the top band and the shimmer's first level
     * are one number rather than two free to drift apart.</p>
     *
     * <p><b>Lightness alternates rather than rises</b>, between {@code L = 0.760} saturated
     * and {@code L = 0.870} pale. This is Hypixel's own grammar -- white, yellow, green,
     * DARK green, aqua, DARK aqua -- inverted, because SkyPrism cannot use the dark leg: it
     * is precisely what makes the vanilla {@code 0xAA00AA} measure 2.85:1 against the chat
     * ground. The alternation is load-bearing, not decorative. At 10-level spacing the hue
     * step alone is far too small to separate neighbours: measured with {@link Oklab}, the
     * same arc at one fixed lightness puts adjacent bands 0.018 apart, under the ~0.02 where
     * two colours stop being tellable apart, while the alternating version measures 0.119 at
     * its tightest -- and 0.109 of that is the lightness swap alone.</p>
     *
     * <p><b>Chroma</b> is {@code min(0.16, 95% of the in-gamut maximum for that L and hue)},
     * the recipe {@link #SPECTRUM} documents: capping rather than chasing the gamut edge is
     * what stops a searing band landing next to a washed-out one, since the edge is a
     * different distance away at every hue.</p>
     *
     * <p><b>The 480 cell is the whole change.</b> Vanilla's last three tiers escalate by
     * going darker and dead-end on the least legible colour on the entire scale, 2.35:1. The
     * rose here sits 0.14 in Oklab from the level-440 red the eye actually compares it
     * against and 0.32 from the dark red an unmodded client draws in its place, so 480 reads
     * as leaving the red corner rather than sinking further into it. Worst contrast anywhere
     * on this half is 7.9:1 against a {@code #14151C} ground, against 2.85:1 for the vanilla
     * half it sits on top of.</p>
     *
     * <p>Literal hex rather than computed at class-init, for the same reasons as
     * {@link #SPECTRUM}: inspectable, copy-pasteable into a custom table, identical on every
     * JVM.</p>
     */
    private static final int[] SKYPRISM_BAND_RGB = {
        0xFB83BF, // 480 rose
        0xFDBDE9, // 490 pale rose
        0xE68AE6, // 500 magenta
        0xEBC3FD, // 510 pale orchid
        0xC598FB, // 520 violet
        0xD5CDFD, // 530 pale violet
        0xA4A8FB, // 540 periwinkle
        0xC4D3FD, // 550 pale periwinkle
        0x82B3FB, // 560 cornflower
        0xB2D9FD, // 570 pale sky
        0x44BEFB, // 580 sky
        0x96E1FD, // 590 pale cyan
        0x27C6DF  // 600 cyan
    };

    /**
     * The widest ramp on offer: sixteen stops forty levels apart, generated in Oklch.
     *
     * <p>This shipped as the default through 1.0.2. It is no longer what a fresh install
     * draws - see {@link #defaultBrackets()} - because recolouring every level in 300
     * degrees of hue reads as too much to players who are used to Hypixel's own tiers.
     * It stays first in the dropdown, and it is still the ramp the gradient invariants
     * in {@code PalettePresetsTest} are tuned against.</p>
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
    private static final BracketTable DEFAULT_BRACKETS = buildDefaultBrackets();

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
     * The table a fresh install draws with: 37 brackets over levels 0..600.
     *
     * <p>Two halves with different jobs. Levels 0..479 are
     * {@link #fineBrackets()}'s bands byte for byte -- every 20 levels, sampled off
     * {@link #vanillaPlus()}, so an even band is a real Hypixel tier colour and an odd one
     * is the perceptual midpoint of two of them. Nothing below 480 is invented; that half is
     * Hypixel's palette at double resolution and no more. Levels 480..600 are
     * {@link #SKYPRISM_BAND_RGB}, thirteen bands 10 levels apart, which is the range Hypixel
     * paints one flat dark red across.</p>
     *
     * <p>The legibility bar is deliberately split, and {@code PalettePresetsTest} enforces
     * both halves: every bracket clears 2.0:1 against a dark UI, and every bracket at or
     * above 480 clears 7.0:1. SkyPrism holds its own colours to the high bar and inherits
     * Hypixel's for the rest -- the floor is the vanilla {@code 0xAA00AA} at level 360,
     * measuring 2.85:1, which cannot be lifted without abandoning the tier hexes this half
     * exists to preserve.</p>
     *
     * <p>37 entries against a {@code MAX_TABLE_ENTRIES} of 64, so a user who opens the table
     * editor on the shipped default still has room to add rows.</p>
     *
     * @return the shipped default bracket table
     */
    public static BracketTable defaultBrackets() {
        return DEFAULT_BRACKETS;
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
     * table is that its landmarks are Hypixel's.</p>
     *
     * <p><b>Frozen.</b> This is the table that shipped as the default through schema v4.
     * {@code ConfigMigrations} compares a stored table against it to decide whether the user
     * ever edited theirs, so it must not move: change a single hex here and every v4 config
     * on every installed client stops being recognised as untouched and keeps a table it
     * never chose. Nothing under {@code src/main} draws with it any more --
     * {@link #defaultBrackets()} is what a fresh install gets -- and its first 24 brackets
     * are reused there verbatim.</p>
     *
     * @return the pre-v5 default bracket table, kept as a fixed comparison value
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
        var brackets = new ArrayList<BracketTable.Bracket>();
        for (int level = 0; level <= HYPIXEL_TOP_TIER_LEVEL; level += FINE_WIDTH) {
            brackets.add(new BracketTable.Bracket(level, VANILLA_PLUS.colorAt(level)));
        }
        return new BracketTable(brackets);
    }

    /**
     * The vanilla half taken from {@link #FINE_BRACKETS} unchanged, then SkyPrism's bands.
     *
     * <p>Copying the brackets rather than re-sampling {@link #VANILLA_PLUS} is the point:
     * the two tables are then identical below 480 by construction, not by two loops that
     * happen to agree today. {@link #FINE_BRACKETS}'s own 480 entry is dropped, because 480
     * is exactly where this table stops being Hypixel's.</p>
     */
    private static BracketTable buildDefaultBrackets() {
        var brackets = new ArrayList<BracketTable.Bracket>(
            FINE_BRACKETS.brackets().size() + SKYPRISM_BAND_RGB.length);
        for (BracketTable.Bracket vanilla : FINE_BRACKETS.brackets()) {
            if (vanilla.minLevel() < HYPIXEL_TOP_TIER_LEVEL) {
                brackets.add(vanilla);
            }
        }
        for (int i = 0; i < SKYPRISM_BAND_RGB.length; i++) {
            brackets.add(new BracketTable.Bracket(
                HYPIXEL_TOP_TIER_LEVEL + i * BAND_WIDTH, SKYPRISM_BAND_RGB[i]));
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
