package com.skyprism.core.config;

import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.level.BracketTable;
import com.skyprism.core.level.GradientRamp;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.core.level.PalettePresets;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.core.util.TextClean;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Every user-facing setting SkyPrism has, as plain mutable data.
 *
 * <p><b>Why this class looks nothing like the rest of the core.</b> Everywhere else in
 * {@code com.skyprism.core} the types are immutable records with validating
 * constructors. This one is a bag of public fields with no validation at all, on
 * purpose: it is the shape Gson reads and writes, and it is the shape a config screen
 * binds two-way controls to. A validating constructor here would mean a hand-edited
 * file with one bad number throws on load and the player loses every other setting they
 * ever chose. So the invariants live in {@link #sanitized()} instead, which repairs
 * rather than rejects, and every consumer in the mod is expected to work from a
 * sanitized copy.
 *
 * <p><b>Defaults live in the field initialisers.</b> Gson calls the no-argument
 * constructor before binding, so a field absent from the JSON simply keeps the value
 * written here. That is what makes adding a setting in a later release a non-event: old
 * files gain the new field's default with no migration and no null check at the use
 * site. Only a field written explicitly as {@code null}, or one Gson could not bind,
 * arrives broken -- and that is exactly the case {@link #sanitized()} handles.
 *
 * <p>Each group publishes its own clamp bounds as public constants. The config screen
 * must build its sliders from those same constants, otherwise the screen and the
 * sanitiser can disagree about what is legal and the user gets a value silently snapped
 * back the moment they save.
 *
 * <p>Not thread-safe, and deliberately so: mutate it on one thread, then publish a
 * {@link #sanitized()} copy for readers.
 */
public final class SkyPrismConfig {

    /**
     * Schema version this build writes and understands.
     *
     * <p>Bumped whenever the JSON shape changes in a way a straight Gson bind would get
     * wrong -- a renamed field, a changed unit, a re-keyed enum. Adding a field does not
     * need a bump, because absent fields already fall back to their initialisers.
     *
     * <p>v3 is the exception that proves that rule. {@code levels.chromaSaturation} and
     * {@code levels.chromaLightness} are new fields, so a straight bind of a v2 file would
     * work -- it would simply hand that file whatever this build's defaults are. That is
     * correct only for as long as the defaults still equal the constants the adapter used
     * to hard-code. The bump exists so {@link ConfigMigrations} can write those old
     * constants into the file instead, which pins what a v2 install was actually
     * rendering and lets a later release retune the defaults for new installs alone.
     *
     * <p>v4 is the bump {@link LootSettings} needed. On its own a new group is an ordinary
     * additive change, but this one <em>widens what the mod does</em>: a v3 install is a Diana-only
     * mod, and binding a v3 file straight into a v4 build would arm thirty further sources on a
     * player who never asked for any of them. Worse, a player who had turned the slot machine off
     * in v3 did so with {@code diana.enabled}, a field that in v4 governs Diana alone -- so a
     * straight bind hands them back the thing they switched off, on content they have never seen
     * it on. {@link ConfigMigrations} carries that decision across instead.
     */
    public static final int CONFIG_VERSION = 4;

    /** Schema version of the file this was read from; see {@link ConfigMigrations}. */
    public int configVersion = CONFIG_VERSION;

    /** Extra logging of parse decisions, for a user who is reporting a mis-detected tag. */
    public boolean debugLogging = false;

    /** Level-prefix recolouring. */
    public LevelSettings levels = new LevelSettings();

    /** Diana kill detection and loot capture. */
    public DianaSettings diana = new DianaSettings();

    /** Every other chance-based activity in SkyBlock, one switch and one policy each. */
    public LootSettings loot = new LootSettings();

    /** Where and how large the slot machine draws. */
    public HudSettings hud = new HudSettings();

    /** The noises the slot machine makes. */
    public SoundSettings sounds = new SoundSettings();

    /**
     * A config with every setting at its shipped value.
     *
     * @return a fresh, fully populated instance the caller may mutate freely
     */
    public static SkyPrismConfig defaults() {
        return new SkyPrismConfig();
    }

    /**
     * A repaired deep copy: every number clamped, every null replaced, every
     * unrecognised enum dropped, every table ordered and de-duplicated.
     *
     * <p>The receiver is left untouched, so a config screen can keep editing the live
     * object while the mod runs off the corrected snapshot.
     *
     * <p><b>This method never throws.</b> Not "should not" -- never. It is the single
     * point every load path and every screen-apply funnels through, and an exception
     * here would either crash the game on world join or trap the player in a config
     * screen that cannot be closed. Each group sanitises defensively on its own, and the
     * whole thing sits behind a last-resort catch that falls back to {@link #defaults()}:
     * losing a customised palette is bad, an unrecoverable boot loop is worse.
     *
     * @return a corrected copy, safe to hand to any consumer without further checking
     */
    public SkyPrismConfig sanitized() {
        try {
            var out = new SkyPrismConfig();
            out.configVersion = clamp(configVersion, 1, CONFIG_VERSION);
            out.debugLogging = debugLogging;
            out.levels = (levels == null ? new LevelSettings() : levels).sanitizedCopy();
            out.diana = (diana == null ? new DianaSettings() : diana).sanitizedCopy();
            out.loot = (loot == null ? new LootSettings() : loot).sanitizedCopy();
            out.hud = (hud == null ? new HudSettings() : hud).sanitizedCopy();
            out.sounds = (sounds == null ? new SoundSettings() : sounds).sanitizedCopy();
            return out;
        } catch (RuntimeException unexpected) {
            return new SkyPrismConfig();
        }
    }

    /**
     * A deep copy with nothing corrected.
     *
     * <p>Exists for the config screen's cancel button: it needs the settings exactly as
     * they were before the user started fiddling, including any value the sanitiser
     * would have snapped, so that pressing cancel really does restore what was there.
     *
     * @return an independent copy; mutating it cannot affect the receiver
     */
    public SkyPrismConfig copy() {
        var out = new SkyPrismConfig();
        out.configVersion = configVersion;
        out.debugLogging = debugLogging;
        out.levels = levels == null ? new LevelSettings() : levels.copy();
        out.diana = diana == null ? new DianaSettings() : diana.copy();
        out.loot = loot == null ? new LootSettings() : loot.copy();
        out.hud = hud == null ? new HudSettings() : hud.copy();
        out.sounds = sounds == null ? new SoundSettings() : sounds.copy();
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SkyPrismConfig c
                && configVersion == c.configVersion
                && debugLogging == c.debugLogging
                && Objects.equals(levels, c.levels)
                && Objects.equals(diana, c.diana)
                && Objects.equals(loot, c.loot)
                && Objects.equals(hud, c.hud)
                && Objects.equals(sounds, c.sounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(configVersion, debugLogging, levels, diana, loot, hud, sounds);
    }

    @Override
    public String toString() {
        return "SkyPrismConfig[v" + configVersion + ", " + levels + ", " + diana
                + ", " + loot + ", " + hud + ", " + sounds + "]";
    }

    // -------------------------------------------------- the one place to ask about a source

    /**
     * Everything the mod needs to know about one {@link LootSource}, resolved.
     *
     * <p>Detectors, the roll engine and the HUD all ask this rather than reading fields, because
     * the answer is assembled from up to four places -- the global master, the category switch, the
     * source's own entry and the shipped registry default -- and Diana is assembled from a
     * different set of them entirely. Spreading that across call sites is how a source ends up
     * armed on one code path and silent on another.
     *
     * @param source       the source described
     * @param enabled      whether it may spin at all right now
     * @param policy       when it may spin, once enabled
     * @param jackpotItems the drops that earn the celebration
     */
    public record EffectiveSource(LootSource source, boolean enabled, RollPolicy policy,
                                  Set<String> jackpotItems) {

        public EffectiveSource {
            jackpotItems = Set.copyOf(jackpotItems);
        }

        /** Whether this source can produce a roll at all as configured. */
        public boolean armed() {
            return enabled && policy.armed();
        }
    }

    /**
     * The resolved settings for one source.
     *
     * <h2>Diana is answered from {@code diana}, not from {@code loot}</h2>
     * <p>{@link LootSource#DIANA_MYTHOLOGICAL} reads {@link DianaSettings} and nothing else: its
     * enablement is {@code diana.enabled}, its jackpot list is {@code diana.jackpotItems}, and the
     * global {@code loot.enabled} master does not touch it. That is not tidiness, it is the
     * no-regression rule made structural. The Diana path is the only one verified against the live
     * server, {@code DianaController} reads {@code config.diana} directly, and if this method
     * folded a new master switch into Diana's answer then this method and that controller could
     * disagree -- which is precisely the class of bug that would look like Diana breaking.
     *
     * <p>The general master is therefore documented, in the screen and in {@code docs/CONFIG.md},
     * as governing every source <em>except</em> Diana, which keeps its own switch on its own tab.
     *
     * @param source any source; null yields a disabled result rather than throwing
     * @return the resolved answer, never null
     */
    public EffectiveSource effectiveSource(LootSource source) {
        if (source == null) {
            return new EffectiveSource(LootSource.DIANA_MYTHOLOGICAL, false, RollPolicy.NEVER,
                    Set.of());
        }
        if (source == LootSource.DIANA_MYTHOLOGICAL) {
            DianaSettings d = diana == null ? new DianaSettings() : diana;
            Set<String> items = d.jackpotItems == null ? Set.of() : Set.copyOf(d.jackpotItems);
            return new EffectiveSource(source, d.enabled, RollPolicy.ALWAYS, items);
        }
        LootSettings settings = loot == null ? new LootSettings() : loot;
        SourceSettings entry = settings.peek(source).orElseGet(SourceSettings::new);
        boolean on = settings.enabled
                && settings.categoryEnabled(LootSourceCategory.of(source))
                && entry.enabled;
        return new EffectiveSource(source, on, entry.effectivePolicy(source),
                entry.effectiveJackpotItems(source));
    }

    /**
     * Whether a source may spin at all.
     *
     * @param source any source
     * @return true when the master, the category and the source itself all say yes
     */
    public boolean lootEnabled(LootSource source) {
        return effectiveSource(source).enabled();
    }

    /**
     * When a source may spin, ignoring whether it is enabled.
     *
     * @param source any source
     * @return the player's choice if they made one, otherwise the shipped default
     */
    public RollPolicy lootPolicy(LootSource source) {
        return effectiveSource(source).policy();
    }

    /**
     * Whether a drop from a source earns the jackpot flourish.
     *
     * @param source   any source
     * @param itemName a drop name, with or without formatting codes
     * @return true if the name is on that source's effective jackpot list
     */
    public boolean isLootJackpot(LootSource source, String itemName) {
        if (source == null || itemName == null) {
            return false;
        }
        if (source == LootSource.DIANA_MYTHOLOGICAL) {
            return diana != null && diana.isJackpot(itemName);
        }
        LootSettings settings = loot == null ? new LootSettings() : loot;
        return settings.peek(source).orElseGet(SourceSettings::new).isJackpot(source, itemName);
    }

    // ---------------------------------------------------------------- levels

    /**
     * Feature one: replacing Hypixel's thirteen forty-level colour tiers on the
     * {@code [451]} prefix.
     *
     * <p>The three sources of colour -- a shipped gradient preset, a hand-written stop
     * list, a hand-written bracket table -- all live here at once rather than in a tagged
     * union. A player switching between {@link LevelColorMode#GRADIENT} and
     * {@link LevelColorMode#BRACKETS} to compare them would otherwise lose whichever table
     * they were not currently looking at, which is a miserable way to tune a palette.
     */
    public static final class LevelSettings {

        /**
         * The {@link #gradientPreset} value meaning "ignore the shipped ramps and use
         * {@link #customStops}". Kept as a reserved key inside the same field so the
         * screen needs one dropdown rather than a dropdown plus a redundant toggle.
         */
        public static final String CUSTOM_PRESET = "custom";

        /**
         * The ramp a fresh install draws with, and the preset substituted when the
         * configured name is missing or unrecognised.
         *
         * <p>Read off {@link PalettePresets#DEFAULT_PRESET_NAME} rather than spelled out
         * again here, so the name in the file and the ramp it resolves to cannot drift.
         */
        public static final String DEFAULT_PRESET = PalettePresets.DEFAULT_PRESET_NAME;

        /** Hard floor for any configured level, matching {@link LevelTagLocator}. */
        public static final int LEVEL_FLOOR = 0;

        /**
         * Hard ceiling for any configured level: the largest value the locator's
         * nine-digit pattern can produce, so the config can never ask for a range the
         * detector is incapable of matching.
         */
        public static final int LEVEL_CEILING = 999_999_999;

        /** Slowest shimmer: one hue cycle per hundred seconds, effectively a crawl. */
        public static final double MIN_CHROMA_CPS = 0.01;

        /** Fastest shimmer. Past this it stops reading as colour and starts reading as a strobe. */
        public static final double MAX_CHROMA_CPS = 10.0;

        /** Slowest shimmer refresh, in updates per second. */
        public static final int MIN_CHROMA_HZ = 1;

        /** Fastest shimmer refresh. Beyond a monitor's refresh rate it is wasted work. */
        public static final int MAX_CHROMA_HZ = 240;

        /** A shimmer with all the colour taken out of it: the sweep becomes a moving grey. */
        public static final double MIN_CHROMA_SATURATION = 0.0;

        /** Fully vivid shimmer. */
        public static final double MAX_CHROMA_SATURATION = 1.0;

        /**
         * Black. Legal so a slider has a real end, but the hue stops being visible well
         * before here; the effect simply disappears into the chat background.
         */
        public static final double MIN_CHROMA_LIGHTNESS = 0.0;

        /** White, with the same caveat as {@link #MIN_CHROMA_LIGHTNESS} at the other end. */
        public static final double MAX_CHROMA_LIGHTNESS = 1.0;

        /**
         * Shipped saturation, and the value substituted for a NaN. Vivid enough to read as
         * "this is animated" while staying legible against chat's dark background. This is
         * the constant the adapter hard-coded up to schema v2.
         */
        public static final double DEFAULT_CHROMA_SATURATION = 0.90;

        /**
         * Shipped lightness, and the value substituted for a NaN. Mid-way through the
         * 0.5-0.7 band {@link com.skyprism.core.level.ChromaClock} documents as
         * chat-legible; hard-coded in the adapter up to schema v2.
         */
        public static final double DEFAULT_CHROMA_LIGHTNESS = 0.62;

        /**
         * Cap on hand-written stops and brackets. A file that somehow arrives with
         * thousands of entries would make every TAB redraw a longer binary search and the
         * config screen unscrollable; a palette a human wrote never needs more than this.
         */
        public static final int MAX_TABLE_ENTRIES = 64;

        /** Master switch for the whole recolour. Off leaves Hypixel's own colours alone. */
        public boolean enabled = true;

        /** Which of the three colour sources is live. */
        public LevelColorMode mode = LevelColorMode.GRADIENT;

        /**
         * A key of {@link PalettePresets#gradients()}, or {@link #CUSTOM_PRESET}.
         * Consulted only in {@link LevelColorMode#GRADIENT}.
         */
        public String gradientPreset = DEFAULT_PRESET;

        /**
         * The user's own ramp, used when {@link #gradientPreset} is {@link #CUSTOM_PRESET}.
         *
         * <p>Seeded from the default preset rather than from a fixed one, because the
         * screen's {@code custom} entry is meant to be "what you are looking at, now
         * editable": seeding it from a ramp the player never selected would make switching
         * to custom look like the palette had been thrown away.
         */
        public List<GradientRamp.Stop> customStops =
                new ArrayList<>(PalettePresets.defaultRamp().stops());

        /** The user's own step table, used in {@link LevelColorMode#BRACKETS}. */
        public List<BracketTable.Bracket> brackets =
                new ArrayList<>(PalettePresets.fineBrackets().brackets());

        /** Whether high-level tags get the animated hue sweep on top of their base colour. */
        public boolean chromaEnabled = false;

        /**
         * Lowest level that shimmers. Defaults to 400 because a shimmer everybody has is
         * not a flex, and 400 is roughly where the level itself stops being common.
         */
        public int chromaMinLevel = 400;

        /** Full trips around the hue wheel per second. */
        public double chromaCyclesPerSecond = 0.35;

        /**
         * How often the shimmer colour is recomputed. Decoupled from the frame rate
         * because a 400 fps client would otherwise rebuild forty TAB entries four hundred
         * times a second to produce changes nobody can see.
         */
        public int chromaUpdateHz = 30;

        /**
         * How vivid the shimmer is, {@link #MIN_CHROMA_SATURATION}..{@link
         * #MAX_CHROMA_SATURATION}.
         *
         * <p>A setting rather than a constant because it is the one shimmer knob a player
         * judges purely by eye, and because at full vividness the effect reads as a toy: a
         * player who wanted a calmer shimmer previously had no move except turning the
         * whole thing off.
         */
        public double chromaSaturation = DEFAULT_CHROMA_SATURATION;

        /**
         * How light the shimmer is, {@link #MIN_CHROMA_LIGHTNESS}..{@link
         * #MAX_CHROMA_LIGHTNESS}.
         *
         * <p>Near either end the hue sweep stops showing at all -- the tag just goes black
         * or white and appears to freeze -- so the useful band is roughly 0.5 to 0.7, which
         * is where {@link #DEFAULT_CHROMA_LIGHTNESS} sits. Both ends stay legal because a
         * clamp that rejected them would have to guess which way the player meant to go.
         */
        public double chromaLightness = DEFAULT_CHROMA_LIGHTNESS;

        /**
         * Only recolour while the player is actually inside Hypixel SkyBlock.
         *
         * <p>Defaults to {@code true}, and the default is the load-bearing part.
         * {@code LevelTagLocator} is deliberately paranoid about the <em>shape</em> of the token
         * it matches -- no leading zeros, a digit cap, a letter-or-digit boundary rule, a
         * configurable level range -- but it has no way to know what server the token came from,
         * and there is no positional constraint either: this hook sees every game message and
         * every TAB row on every server. Without a server check, a teammate typing "we need [2]
         * more" in a Bedwars lobby, a shared coordinate "[500] [70]", or another mod's "[3]
         * updates available" all get repainted with the SkyBlock level ramp -- a false positive
         * that, in the locator's own words, "is immediately visible as a bug to every player in
         * the lobby". The SkyBlock level prefix only exists inside SkyBlock, so this is the
         * feature's natural scope rather than a restriction on it.
         *
         * <p>The signal is the SkyBlock half of the Diana gate: the sidebar objective's title, at
         * the same 0.5 Hz poll the Diana feature already pays for, so the check costs one boolean
         * read per call and nothing else. Turn it off to recolour bracketed numbers everywhere,
         * which is occasionally what someone wants and is never what someone expects.
         */
        public boolean onlyOnSkyBlock = true;

        /** Recolour the prefix where it appears in chat lines. */
        public boolean applyToChat = true;

        /** Recolour the prefix in the TAB player list. */
        public boolean applyToTabList = true;

        /**
         * Recolour the prefix on above-head name tags in the world.
         *
         * <p>Defaults to {@code false} because it is not confirmed that Hypixel renders the
         * SkyBlock level prefix above heads at all: the wiki documents the prefix as appearing
         * in TAB and in chat only. The mixin is built and works either way, but defaulting it
         * on would mean paying for a name-tag transform on every rendered player for a tag that
         * may never be there. Flip this default once it has been confirmed in-game on Hypixel.
         */
        public boolean applyToNameTags = false;

        /**
         * Whether the square brackets take the level colour too, or stay as Hypixel drew
         * them so that only the digits change.
         *
         * <p>Defaults to {@code true}: the whole tag carries the colour. This default was
         * argued the other way first -- Hypixel itself draws the brackets dim and colours
         * only the number, so matching that made SkyPrism a drop-in restyle rather than a
         * new look -- and then it was put in front of the person actually looking at it on
         * a live server, who preferred the fully coloured tag. That judgement wins over the
         * argument.
         *
         * <p>Setting it to {@code false} reproduces Hypixel's own styling: a dim {@code [},
         * the number in the level colour, a dim {@code ]}.
         */
        public boolean recolourBrackets = true;

        /**
         * Lowest number accepted as a level tag; below this, {@code [12]} is left alone as
         * ordinary text.
         */
        public int minLevel = LevelTagLocator.STANDARD_MIN;

        /** Highest number accepted as a level tag. */
        public int maxLevel = LevelTagLocator.STANDARD_MAX;

        /**
         * The ramp {@link LevelColorMode#GRADIENT} should draw with.
         *
         * <p>Falls back to the default preset rather than throwing if these settings have
         * not been sanitised and the stop list is unusable: a render path is the worst
         * possible place to discover a bad config.
         *
         * @return a usable ramp, always
         */
        public GradientRamp resolveRamp() {
            try {
                if (CUSTOM_PRESET.equals(gradientPreset)) {
                    return new GradientRamp(customStops);
                }
                GradientRamp preset = PalettePresets.gradients().get(gradientPreset);
                return preset != null ? preset : PalettePresets.defaultRamp();
            } catch (RuntimeException unusable) {
                return PalettePresets.defaultRamp();
            }
        }

        /**
         * The step table to draw with: the user's own in {@link LevelColorMode#BRACKETS},
         * Hypixel's thirteen tiers in {@link LevelColorMode#VANILLA}.
         *
         * @return a usable table, always
         */
        public BracketTable resolveTable() {
            if (mode == LevelColorMode.VANILLA) {
                return PalettePresets.vanillaBrackets();
            }
            try {
                return new BracketTable(brackets);
            } catch (RuntimeException unusable) {
                return PalettePresets.fineBrackets();
            }
        }

        /**
         * A tag detector for the configured sanity range.
         *
         * @return the locator for {@link #minLevel}..{@link #maxLevel}, falling back to
         *         {@link LevelTagLocator#standard()} if that range is inverted or negative
         */
        public LevelTagLocator resolveLocator() {
            try {
                return new LevelTagLocator(minLevel, maxLevel);
            } catch (RuntimeException inverted) {
                return LevelTagLocator.standard();
            }
        }

        LevelSettings sanitizedCopy() {
            var out = new LevelSettings();
            out.enabled = enabled;
            out.mode = mode == null ? LevelColorMode.GRADIENT : mode;
            out.gradientPreset = sanitizePreset(gradientPreset);
            out.customStops = sanitizeStops(customStops);
            out.brackets = sanitizeBrackets(brackets);
            out.chromaEnabled = chromaEnabled;
            out.chromaMinLevel = clamp(chromaMinLevel, LEVEL_FLOOR, LEVEL_CEILING);
            out.chromaCyclesPerSecond =
                    clamp(chromaCyclesPerSecond, MIN_CHROMA_CPS, MAX_CHROMA_CPS, 0.35);
            out.chromaUpdateHz = clamp(chromaUpdateHz, MIN_CHROMA_HZ, MAX_CHROMA_HZ);
            out.chromaSaturation = clamp(chromaSaturation, MIN_CHROMA_SATURATION,
                    MAX_CHROMA_SATURATION, DEFAULT_CHROMA_SATURATION);
            out.chromaLightness = clamp(chromaLightness, MIN_CHROMA_LIGHTNESS,
                    MAX_CHROMA_LIGHTNESS, DEFAULT_CHROMA_LIGHTNESS);
            out.applyToChat = applyToChat;
            out.applyToTabList = applyToTabList;
            out.applyToNameTags = applyToNameTags;
            out.recolourBrackets = recolourBrackets;
            out.onlyOnSkyBlock = onlyOnSkyBlock;

            int lo = clamp(minLevel, LEVEL_FLOOR, LEVEL_CEILING);
            int hi = clamp(maxLevel, LEVEL_FLOOR, LEVEL_CEILING);
            // An inverted range is repaired by widening to span both ends rather than by
            // snapping one onto the other: whichever bound the user last edited, the levels
            // they were plainly trying to include stay included.
            out.minLevel = Math.min(lo, hi);
            out.maxLevel = Math.max(lo, hi);
            return out;
        }

        LevelSettings copy() {
            var out = new LevelSettings();
            out.enabled = enabled;
            out.mode = mode;
            out.gradientPreset = gradientPreset;
            out.customStops = customStops == null ? new ArrayList<>() : new ArrayList<>(customStops);
            out.brackets = brackets == null ? new ArrayList<>() : new ArrayList<>(brackets);
            out.chromaEnabled = chromaEnabled;
            out.chromaMinLevel = chromaMinLevel;
            out.chromaCyclesPerSecond = chromaCyclesPerSecond;
            out.chromaUpdateHz = chromaUpdateHz;
            out.chromaSaturation = chromaSaturation;
            out.chromaLightness = chromaLightness;
            out.applyToChat = applyToChat;
            out.applyToTabList = applyToTabList;
            out.applyToNameTags = applyToNameTags;
            out.recolourBrackets = recolourBrackets;
            out.onlyOnSkyBlock = onlyOnSkyBlock;
            out.minLevel = minLevel;
            out.maxLevel = maxLevel;
            return out;
        }

        /**
         * Normalises a preset name the way a hand-edited file is likely to have spelled it
         * -- stray case, a space instead of an underscore -- and falls back to the default
         * for anything still unrecognised, since a name that resolves to nothing would
         * leave the gradient mode with no colours at all.
         */
        private static String sanitizePreset(String raw) {
            if (raw == null) {
                return DEFAULT_PRESET;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (key.equals(CUSTOM_PRESET) || PalettePresets.gradients().containsKey(key)) {
                return key;
            }
            return DEFAULT_PRESET;
        }

        /**
         * Sorts, clamps and de-duplicates a stop list, and substitutes the default ramp if
         * nothing usable survives. {@link GradientRamp} rejects a duplicated level
         * outright, and clamping can itself collide two stops, so the de-duplication has to
         * happen after the clamp rather than before it. First stop at a level wins.
         */
        private static List<GradientRamp.Stop> sanitizeStops(List<GradientRamp.Stop> raw) {
            var out = new ArrayList<GradientRamp.Stop>();
            if (raw != null) {
                var ordered = new ArrayList<GradientRamp.Stop>(raw.size());
                for (GradientRamp.Stop s : raw) {
                    if (s != null) {
                        ordered.add(new GradientRamp.Stop(
                                clamp(s.level(), LEVEL_FLOOR, LEVEL_CEILING), s.rgb()));
                    }
                }
                ordered.sort(Comparator.comparingInt(GradientRamp.Stop::level));
                for (GradientRamp.Stop s : ordered) {
                    if (out.size() >= MAX_TABLE_ENTRIES) {
                        break;
                    }
                    if (out.isEmpty() || out.get(out.size() - 1).level() != s.level()) {
                        out.add(s);
                    }
                }
            }
            return out.isEmpty() ? new ArrayList<>(PalettePresets.defaultRamp().stops()) : out;
        }

        /** The bracket-table twin of {@link #sanitizeStops(List)}; same reasoning throughout. */
        private static List<BracketTable.Bracket> sanitizeBrackets(List<BracketTable.Bracket> raw) {
            var out = new ArrayList<BracketTable.Bracket>();
            if (raw != null) {
                var ordered = new ArrayList<BracketTable.Bracket>(raw.size());
                for (BracketTable.Bracket b : raw) {
                    if (b != null) {
                        ordered.add(new BracketTable.Bracket(
                                clamp(b.minLevel(), LEVEL_FLOOR, LEVEL_CEILING), b.rgb()));
                    }
                }
                ordered.sort(Comparator.comparingInt(BracketTable.Bracket::minLevel));
                for (BracketTable.Bracket b : ordered) {
                    if (out.size() >= MAX_TABLE_ENTRIES) {
                        break;
                    }
                    if (out.isEmpty() || out.get(out.size() - 1).minLevel() != b.minLevel()) {
                        out.add(b);
                    }
                }
            }
            return out.isEmpty() ? new ArrayList<>(PalettePresets.fineBrackets().brackets()) : out;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LevelSettings s
                    && enabled == s.enabled
                    && mode == s.mode
                    && Objects.equals(gradientPreset, s.gradientPreset)
                    && Objects.equals(customStops, s.customStops)
                    && Objects.equals(brackets, s.brackets)
                    && chromaEnabled == s.chromaEnabled
                    && chromaMinLevel == s.chromaMinLevel
                    && Double.compare(chromaCyclesPerSecond, s.chromaCyclesPerSecond) == 0
                    && chromaUpdateHz == s.chromaUpdateHz
                    && Double.compare(chromaSaturation, s.chromaSaturation) == 0
                    && Double.compare(chromaLightness, s.chromaLightness) == 0
                    && applyToChat == s.applyToChat
                    && applyToTabList == s.applyToTabList
                    && applyToNameTags == s.applyToNameTags
                    && recolourBrackets == s.recolourBrackets
                    && onlyOnSkyBlock == s.onlyOnSkyBlock
                    && minLevel == s.minLevel
                    && maxLevel == s.maxLevel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, mode, gradientPreset, customStops, brackets, chromaEnabled,
                    chromaMinLevel, chromaCyclesPerSecond, chromaUpdateHz, chromaSaturation,
                    chromaLightness, applyToChat, applyToTabList, applyToNameTags,
                    recolourBrackets, onlyOnSkyBlock, minLevel, maxLevel);
        }

        @Override
        public String toString() {
            return "LevelSettings[" + mode + " " + gradientPreset + ", " + minLevel + ".." + maxLevel
                    + (chromaEnabled ? ", chroma>=" + chromaMinLevel : "") + "]";
        }
    }

    // ----------------------------------------------------------------- diana

    /**
     * Feature two: what counts as a kill worth spinning for, and how long its drops are
     * still believed to belong to it.
     *
     * <p>The reel timings live here rather than being hard-coded in the roll engine
     * because {@link SlotRollConfig} validates them and a config screen has to be able to
     * offer them. {@link #toRollConfig()} is the bridge, and the clamps below are chosen
     * so that bridge can never hand the engine an argument the record rejects.
     */
    public static final class DianaSettings {

        /**
         * Shortest loot window. Under a quarter second the ordinary drop lines that follow
         * a kill would fall outside it and the reels would lock onto nothing.
         */
        public static final long MIN_LOOT_WINDOW_MILLIS = 250L;

        /** Longest loot window. Past thirty seconds the next kill's drops start bleeding in. */
        public static final long MAX_LOOT_WINDOW_MILLIS = 30_000L;

        /** Longest free-spin phase before the first reel locks. */
        public static final long MAX_SPIN_MILLIS = 10_000L;

        /** Longest gap between one reel locking and the next. */
        public static final long MAX_STAGGER_MILLIS = 2_000L;

        /** Longest hold on the finished result. */
        public static final long MAX_SETTLE_MILLIS = 15_000L;

        /** Longest fade-out. */
        public static final long MAX_FADE_MILLIS = 5_000L;

        /** Longest gold wash before the jackpot reels start moving again. */
        public static final long MAX_JACKPOT_INTRO_MILLIS = 5_000L;

        /** Longest jackpot re-spin before the first column lands. */
        public static final long MAX_JACKPOT_SPIN_MILLIS = 10_000L;

        /** Longest gap between one jackpot column landing and the next. */
        public static final long MAX_JACKPOT_STAGGER_MILLIS = 2_000L;

        /** Longest hold on the finished three of a kind. */
        public static final long MAX_JACKPOT_HOLD_MILLIS = 15_000L;

        /** Cap on the jackpot list, purely so a pathological file cannot bloat the screen. */
        public static final int MAX_JACKPOT_ITEMS = 128;

        /** Master switch for kill detection and the slot machine. */
        public boolean enabled = true;

        /**
         * Which creatures spin the reels. Empty is legal and means "never spin", which is a
         * different statement from {@code enabled = false}: parsing still runs, so a player
         * can keep the chat-side behaviour without the animation.
         */
        public Set<MythologicalCreature> triggers =
                new LinkedHashSet<>(MythologicalCreature.defaultTriggers());

        /**
         * Ignore kills credited to other players nearby. On by default because a busy Hub
         * would otherwise spin the machine for loot the player never received.
         */
        public boolean onlyMyBurrows = true;

        /** How long after a kill drops are still attributed to it. */
        public long lootWindowMillis = 3_000L;

        /** Columns on the machine. */
        public int reelCount = 3;

        /** Free-spin time before the leftmost reel locks. */
        public long spinMillis = 1_200L;

        /** Extra delay per reel, so they stop left to right. */
        public long lockStaggerMillis = 250L;

        /** How long the finished result is held still. */
        public long settleMillis = 2_500L;

        /** How long the result takes to fade out afterwards. */
        public long fadeMillis = 500L;

        /**
         * How long the gold takes to wash in, over reels that are already turning again.
         *
         * <p>These four timings drive a second act that only a jackpot ever plays, and that
         * begins only once the ordinary roll has finished honestly: the reels lock on the
         * real drops with no hint of gold, and only then does the machine throw every column
         * back into motion, wash gold over them as they go, and land them one at a time on the
         * same item. The suspense bonus this replaced was spent inside the ordinary spin, which
         * meant a jackpot roll looked different from its first second and gave the surprise
         * away.
         *
         * <p>This one measures the colour ramp, not a pause: the reels break loose at the start
         * of it, so the machine turns gold while it is already spinning. Zero makes the gold
         * snap on instead of arriving.
         */
        public long jackpotIntroMillis = 600L;

        /** How much longer the reels turn after the wash is complete, before the first lands. */
        public long jackpotSpinMillis = 900L;

        /** Extra delay per reel in the second act, so the three of a kind lands column by column. */
        public long jackpotLockStaggerMillis = 280L;

        /** How long the three of a kind is held before the fade. */
        public long jackpotHoldMillis = 2_200L;

        /**
         * Item names that trigger the jackpot flourish, matched case-insensitively against
         * a formatting-stripped drop name.
         *
         * <p>A name list rather than a rarity flag, because rarity is not in the chat line:
         * Hypixel's {@code RARE DROP!} banner marks the line, not the item, and several
         * genuinely valuable Diana drops arrive on an unmarked one. The shipped set is a
         * starting point the player is expected to edit, not a claim about drop tables.
         */
        public Set<String> jackpotItems = new LinkedHashSet<>(List.of(
                "Daedalus Stick",
                "Crown of Greed",
                "Minos Relic",
                "Dwarf Turtle Shelmet",
                "Antique Remedies",
                "Washed-up Souvenir"));

        /**
         * Hide the drop lines from chat once the machine has captured them, so the reels
         * are the announcement rather than a duplicate of it.
         */
        public boolean suppressDropChatLines = false;

        /**
         * Islands the feature is allowed to run on, matched against the sidebar's area line.
         *
         * <p><b>Empty means "any island"</b>, which is the shipped default and the polarity
         * {@code DianaGate} documents: a default, unconfigured gate has to work everywhere rather
         * than silently work nowhere. It is offered because the gate has always had an area
         * condition and, until this field existed, nothing ever set it -- three class javadocs
         * described a four-condition gate that was really a three-condition one, and
         * {@code HypixelContext} computed the area every two seconds and pushed it into a check
         * that could not use it.
         *
         * <p>Filling it in is the way to stop loot from unrelated content being credited to Diana:
         * the parser recognises Hypixel's banners server-wide, so a slayer or dungeon "RARE DROP!"
         * landing inside a stale spawn's five-minute lifetime is otherwise offered to the reels and
         * written into the tally. Entries are formatting-stripped, whitespace-collapsed and matched
         * case-insensitively, so "Hub" and "&#167;7 hub " are the same entry.
         */
        public Set<String> allowedAreas = new LinkedHashSet<>();

        /**
         * These timings in the shape the roll engine wants them.
         *
         * @return a {@link SlotRollConfig}; safe on any config, because every argument is
         *         clamped into the record's accepted range on the way through
         */
        public SlotRollConfig toRollConfig() {
            return new SlotRollConfig(
                    clamp(reelCount, SlotRollConfig.MIN_REELS, SlotRollConfig.MAX_REELS),
                    clamp(spinMillis, 0L, MAX_SPIN_MILLIS),
                    clamp(lockStaggerMillis, 0L, MAX_STAGGER_MILLIS),
                    clamp(lootWindowMillis, MIN_LOOT_WINDOW_MILLIS, MAX_LOOT_WINDOW_MILLIS),
                    clamp(settleMillis, 0L, MAX_SETTLE_MILLIS),
                    clamp(fadeMillis, 0L, MAX_FADE_MILLIS),
                    clamp(jackpotIntroMillis, 0L, MAX_JACKPOT_INTRO_MILLIS),
                    clamp(jackpotSpinMillis, 0L, MAX_JACKPOT_SPIN_MILLIS),
                    clamp(jackpotLockStaggerMillis, 0L, MAX_JACKPOT_STAGGER_MILLIS),
                    clamp(jackpotHoldMillis, 0L, MAX_JACKPOT_HOLD_MILLIS));
        }

        /**
         * Whether a drop should set off the jackpot flourish.
         *
         * <p>Both sides are cleaned, not just the drop. {@link #sanitizedCopy()} already
         * strips the stored entries, but the config screen matches against the live
         * instance while the player is still typing -- and a name pasted straight out of
         * chat arrives carrying the colour codes it was printed with. Cleaning only the
         * drop meant that pasted entry silently matched nothing.
         *
         * @param itemName an item name, with or without formatting codes
         * @return true if it matches a jackpot entry ignoring case, surrounding space and
         *         colour codes; false for null, blank, or anything unlisted
         */
        public boolean isJackpot(String itemName) {
            if (itemName == null || jackpotItems == null) {
                return false;
            }
            String needle = TextClean.clean(itemName);
            if (needle == null || needle.isBlank()) {
                return false;
            }
            for (String entry : jackpotItems) {
                String candidate = TextClean.clean(entry);
                if (candidate != null && candidate.equalsIgnoreCase(needle)) {
                    return true;
                }
            }
            return false;
        }

        DianaSettings sanitizedCopy() {
            var out = new DianaSettings();
            out.enabled = enabled;
            out.triggers = sanitizeTriggers(triggers);
            out.onlyMyBurrows = onlyMyBurrows;
            out.lootWindowMillis =
                    clamp(lootWindowMillis, MIN_LOOT_WINDOW_MILLIS, MAX_LOOT_WINDOW_MILLIS);
            out.reelCount = clamp(reelCount, SlotRollConfig.MIN_REELS, SlotRollConfig.MAX_REELS);
            out.spinMillis = clamp(spinMillis, 0L, MAX_SPIN_MILLIS);
            out.lockStaggerMillis = clamp(lockStaggerMillis, 0L, MAX_STAGGER_MILLIS);
            out.settleMillis = clamp(settleMillis, 0L, MAX_SETTLE_MILLIS);
            out.fadeMillis = clamp(fadeMillis, 0L, MAX_FADE_MILLIS);
            out.jackpotIntroMillis = clamp(jackpotIntroMillis, 0L, MAX_JACKPOT_INTRO_MILLIS);
            out.jackpotSpinMillis = clamp(jackpotSpinMillis, 0L, MAX_JACKPOT_SPIN_MILLIS);
            out.jackpotLockStaggerMillis =
                    clamp(jackpotLockStaggerMillis, 0L, MAX_JACKPOT_STAGGER_MILLIS);
            out.jackpotHoldMillis = clamp(jackpotHoldMillis, 0L, MAX_JACKPOT_HOLD_MILLIS);
            out.jackpotItems = sanitizeNameSet(jackpotItems);
            out.suppressDropChatLines = suppressDropChatLines;
            out.allowedAreas = sanitizeNameSet(allowedAreas);
            return out;
        }

        DianaSettings copy() {
            var out = new DianaSettings();
            out.enabled = enabled;
            out.triggers = triggers == null ? new LinkedHashSet<>() : new LinkedHashSet<>(triggers);
            out.onlyMyBurrows = onlyMyBurrows;
            out.lootWindowMillis = lootWindowMillis;
            out.reelCount = reelCount;
            out.spinMillis = spinMillis;
            out.lockStaggerMillis = lockStaggerMillis;
            out.settleMillis = settleMillis;
            out.fadeMillis = fadeMillis;
            out.jackpotIntroMillis = jackpotIntroMillis;
            out.jackpotSpinMillis = jackpotSpinMillis;
            out.jackpotLockStaggerMillis = jackpotLockStaggerMillis;
            out.jackpotHoldMillis = jackpotHoldMillis;
            out.jackpotItems =
                    jackpotItems == null ? new LinkedHashSet<>() : new LinkedHashSet<>(jackpotItems);
            out.suppressDropChatLines = suppressDropChatLines;
            out.allowedAreas =
                    allowedAreas == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedAreas);
            return out;
        }

        /**
         * Drops nulls -- which is exactly what Gson leaves behind for a creature name this
         * build does not know -- and re-orders to enum order, so the written file is stable
         * no matter what order the screen's checkboxes were ticked in. A set that was null
         * entirely is a missing field rather than a user choice, so it returns to defaults;
         * a set the user emptied stays empty.
         */
        private static Set<MythologicalCreature> sanitizeTriggers(Set<MythologicalCreature> raw) {
            if (raw == null) {
                return new LinkedHashSet<>(MythologicalCreature.defaultTriggers());
            }
            var out = new LinkedHashSet<MythologicalCreature>();
            for (MythologicalCreature c : MythologicalCreature.values()) {
                if (raw.contains(c)) {
                    out.add(c);
                }
            }
            return out;
        }

        /**
         * Strips colour codes and surrounding space off each entry and drops what is left
         * blank, so a name pasted straight out of chat matches the same way a typed one
         * does. Insertion order is kept because it is the order the screen lists them in.
         *
         * <p>Shared by {@link #jackpotItems} and {@link #allowedAreas}: both are player-typed or
         * player-pasted name lists with exactly the same hazards, and one bounded, cleaning copy
         * of that rule is better than two that can drift.
         */
        private static Set<String> sanitizeNameSet(Set<String> raw) {
            if (raw == null) {
                return new LinkedHashSet<>();
            }
            var out = new LinkedHashSet<String>();
            for (String s : raw) {
                if (out.size() >= MAX_JACKPOT_ITEMS) {
                    break;
                }
                String cleaned = TextClean.clean(s);
                if (cleaned != null && !cleaned.isBlank()) {
                    out.add(cleaned);
                }
            }
            return out;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DianaSettings s
                    && enabled == s.enabled
                    && Objects.equals(triggers, s.triggers)
                    && onlyMyBurrows == s.onlyMyBurrows
                    && lootWindowMillis == s.lootWindowMillis
                    && reelCount == s.reelCount
                    && spinMillis == s.spinMillis
                    && lockStaggerMillis == s.lockStaggerMillis
                    && settleMillis == s.settleMillis
                    && fadeMillis == s.fadeMillis
                    && jackpotIntroMillis == s.jackpotIntroMillis
                    && jackpotSpinMillis == s.jackpotSpinMillis
                    && jackpotLockStaggerMillis == s.jackpotLockStaggerMillis
                    && jackpotHoldMillis == s.jackpotHoldMillis
                    && Objects.equals(jackpotItems, s.jackpotItems)
                    && suppressDropChatLines == s.suppressDropChatLines
                    && Objects.equals(allowedAreas, s.allowedAreas);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, triggers, onlyMyBurrows, lootWindowMillis, reelCount,
                    spinMillis, lockStaggerMillis, settleMillis, fadeMillis, jackpotIntroMillis,
                    jackpotSpinMillis, jackpotLockStaggerMillis, jackpotHoldMillis,
                    jackpotItems, suppressDropChatLines, allowedAreas);
        }

        @Override
        public String toString() {
            return "DianaSettings[" + reelCount + " reels, window " + lootWindowMillis
                    + "ms, triggers=" + triggers + "]";
        }
    }

    // ------------------------------------------------------------------ loot

    /**
     * Feature three: the same slot machine, on every other chance-based activity in SkyBlock.
     *
     * <h2>Why this is not just sixty more fields</h2>
     * <p>SkyBlock's chance-based events do not share a cadence, and that single fact drives the
     * whole shape of this group. A Kuudra clear, a Glacite corpse and a slayer boss are minutes
     * apart and the player is already waiting on each of them; an ordinary sea creature, a Pristine
     * proc and a Bronze trophy fish arrive several times a <em>second</em> during a grind. One rule
     * for both gives either a machine that never fires or a strobe. So each source carries its own
     * {@link RollPolicy}, and the shipped default for each is a researched judgement about cadence
     * rather than about rarity -- see {@code LootSourceRegistry} for the per-source reasoning and
     * {@code docs/CONFIG.md} for the table.
     *
     * <h2>Three layers of switch, and why each one earns its place</h2>
     * <ul>
     *   <li>{@link #enabled} -- one thing to turn off when the whole idea is too much. It does not
     *       touch Diana; see {@link SkyPrismConfig#effectiveSource(LootSource)}.</li>
     *   <li>{@link #disabledCategories} -- "the fishing rolls are too chatty" is a sentence players
     *       actually say, and it should not require finding six switches.</li>
     *   <li>{@link #sources} -- the individual say, for the one source somebody cares about.</li>
     * </ul>
     * All three must agree before a source may spin, which makes the coarse controls genuinely
     * coarse: switching a category back on cannot resurrect a source the player disabled by name.
     *
     * <h2>The map is sparse on purpose</h2>
     * <p>Only sources the player has an opinion about are stored. {@link #sanitizedCopy()} drops
     * every entry that says nothing, so a fresh install writes {@code "sources": {}} and a heavily
     * customised one writes the handful of lines that were actually edited. A file that listed all
     * sixty-odd sources would be four hundred lines restating the code, and it would freeze this
     * build's defaults into the file forever -- which is exactly what must not happen, because
     * some of those defaults will need correcting in a later release.
     */
    public static final class LootSettings {

        /**
         * Master switch for every source except Diana.
         *
         * <p>Diana is excluded deliberately and that exclusion is load-bearing rather than
         * cosmetic: {@code DianaController} reads {@code diana.enabled} directly, so a master that
         * claimed to cover Diana would be a claim this class could not keep. The screen says so in
         * as many words, and the category switches below are the way to silence everything new
         * without touching the shipped path.
         */
        public boolean enabled = true;

        /**
         * Hide drop lines from chat once the reels have captured them, mod-wide.
         *
         * <p>The Diana group has a field of the same name that governs the Diana path; this one is
         * for everything else. Two fields rather than one because the two paths hide different
         * lines and a player may reasonably want the machine to be the announcement for a Kuudra
         * chest while leaving their Diana chat exactly as it has always looked.
         */
        public boolean suppressDropChatLines = false;

        /**
         * Categories switched off wholesale.
         *
         * <p>Stored as the set of <em>disabled</em> ones so that the shipped state is an empty
         * collection and a category added in a later release is on by default -- the same polarity
         * argument {@code diana.allowedAreas} documents. {@link LootSourceCategory#DIANA} is
         * removed by {@link #sanitizedCopy()}: Diana's switch is {@code diana.enabled}, and a
         * second one here could only disagree with it.
         */
        public Set<LootSourceCategory> disabledCategories = new LinkedHashSet<>();

        /**
         * Per-source opinions, keyed by {@link LootSource}.
         *
         * <p>Sparse: a source absent from this map is entirely at its shipped defaults. Gson
         * delivers {@code null} for a key this build does not recognise, which is how a config
         * written by a newer release survives being read by an older one; those entries are
         * dropped rather than crashing the load.
         */
        public Map<LootSource, SourceSettings> sources = new LinkedHashMap<>();

        /**
         * Whether a category is switched on.
         *
         * @param category the category; null reads as on, because a null cannot have been disabled
         * @return true unless the player has switched the whole category off
         */
        public boolean categoryEnabled(LootSourceCategory category) {
            if (category == null || category == LootSourceCategory.DIANA) {
                // Diana never answers from here; see the field javadoc.
                return true;
            }
            return disabledCategories == null || !disabledCategories.contains(category);
        }

        /**
         * Switches a whole category on or off.
         *
         * @param category the category; {@link LootSourceCategory#DIANA} and null are ignored
         * @param on       whether the category may spin
         */
        public void setCategoryEnabled(LootSourceCategory category, boolean on) {
            if (category == null || category == LootSourceCategory.DIANA) {
                return;
            }
            if (disabledCategories == null) {
                disabledCategories = new LinkedHashSet<>();
            }
            if (on) {
                disabledCategories.remove(category);
            } else {
                disabledCategories.add(category);
            }
        }

        /**
         * The stored entry for a source, without creating one.
         *
         * <p>For read paths, which must not make the config grow just by looking at it.
         *
         * @param source the source; null yields empty
         * @return the entry if the player has an opinion about this source
         */
        public Optional<SourceSettings> peek(LootSource source) {
            if (source == null || sources == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(sources.get(source));
        }

        /**
         * The entry for a source, creating and storing a blank one if there is none.
         *
         * <p>For the settings screen, which needs something to bind a control to before the player
         * has expressed any opinion at all. A blank entry costs nothing on disk, because
         * {@link #sanitizedCopy()} drops every entry that is still blank when the screen closes.
         *
         * @param source the source; null yields a detached entry that is not stored
         * @return the entry, never null
         */
        public SourceSettings settingsFor(LootSource source) {
            if (source == null) {
                return new SourceSettings();
            }
            if (sources == null) {
                sources = new LinkedHashMap<>();
            }
            return sources.computeIfAbsent(source, key -> new SourceSettings());
        }

        /**
         * Forgets every stored opinion about a category, returning it to shipped defaults.
         *
         * <p>The only way back to "no opinion" once a control has been touched: the screen writes
         * an explicit policy the moment the dropdown moves, and {@link SourceSettings} promises
         * never to rewrite one. Deleting the entry is the honest undo, and it is what the reset
         * button on each category tab does.
         *
         * @param category the category to clear; null and {@link LootSourceCategory#DIANA} are
         *                 ignored, the latter because nothing about Diana is stored here
         */
        public void resetCategory(LootSourceCategory category) {
            if (category == null || category == LootSourceCategory.DIANA || sources == null) {
                return;
            }
            for (LootSource source : LootSourceCategory.sources(category)) {
                sources.remove(source);
            }
            setCategoryEnabled(category, true);
        }

        /**
         * How many sources are armed right now, for the screen's summary line and for tests.
         *
         * @return the count of sources that would spin on their next trigger, Diana excluded
         */
        public int armedSourceCount() {
            int count = 0;
            for (LootSource source : LootSource.values()) {
                if (source == LootSource.DIANA_MYTHOLOGICAL) {
                    continue;
                }
                SourceSettings entry = peek(source).orElseGet(SourceSettings::new);
                if (enabled
                        && categoryEnabled(LootSourceCategory.of(source))
                        && entry.enabled
                        && entry.effectivePolicy(source).armed()) {
                    count++;
                }
            }
            return count;
        }

        LootSettings sanitizedCopy() {
            var out = new LootSettings();
            out.enabled = enabled;
            out.suppressDropChatLines = suppressDropChatLines;
            out.disabledCategories = sanitizeCategories(disabledCategories);
            out.sources = sanitizeSources(sources);
            return out;
        }

        LootSettings copy() {
            var out = new LootSettings();
            out.enabled = enabled;
            out.suppressDropChatLines = suppressDropChatLines;
            out.disabledCategories = disabledCategories == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(disabledCategories);
            out.sources = new LinkedHashMap<>();
            if (sources != null) {
                for (Map.Entry<LootSource, SourceSettings> entry : sources.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        out.sources.put(entry.getKey(), entry.getValue().copy());
                    }
                }
            }
            return out;
        }

        /**
         * Drops nulls -- which is what Gson leaves for a category name this build does not know --
         * drops {@link LootSourceCategory#DIANA}, and re-orders to enum order so the written file
         * is stable however the screen's tick boxes were clicked.
         */
        private static Set<LootSourceCategory> sanitizeCategories(Set<LootSourceCategory> raw) {
            var out = new LinkedHashSet<LootSourceCategory>();
            if (raw == null) {
                return out;
            }
            for (LootSourceCategory category : LootSourceCategory.values()) {
                if (category.configurable() && raw.contains(category)) {
                    out.add(category);
                }
            }
            return out;
        }

        /**
         * Repairs every entry, drops the ones that say nothing, and writes the survivors in enum
         * order.
         *
         * <p>Dropping blank entries is what keeps the file sparse, and enum ordering is what makes
         * a save that changed nothing produce a byte-identical file -- the property
         * {@code ConfigCodec} relies on so a config under version control stays quiet.
         *
         * <p>{@link LootSource#DIANA_MYTHOLOGICAL} is removed unconditionally. Diana's settings
         * live in {@code diana}, and an entry here could only ever be a second opinion that
         * nothing reads.
         */
        private static Map<LootSource, SourceSettings> sanitizeSources(
                Map<LootSource, SourceSettings> raw) {
            var out = new LinkedHashMap<LootSource, SourceSettings>();
            if (raw == null) {
                return out;
            }
            for (LootSource source : LootSource.values()) {
                if (source == LootSource.DIANA_MYTHOLOGICAL) {
                    continue;
                }
                SourceSettings entry = raw.get(source);
                if (entry == null) {
                    continue;
                }
                SourceSettings clean = entry.sanitizedCopy(source);
                if (!clean.isDefault()) {
                    out.put(source, clean);
                }
            }
            return out;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LootSettings s
                    && enabled == s.enabled
                    && suppressDropChatLines == s.suppressDropChatLines
                    && Objects.equals(disabledCategories, s.disabledCategories)
                    && Objects.equals(sources, s.sources);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, suppressDropChatLines, disabledCategories, sources);
        }

        @Override
        public String toString() {
            return "LootSettings[" + (enabled ? "on" : "off")
                    + ", " + armedSourceCount() + " armed"
                    + (disabledCategories == null || disabledCategories.isEmpty()
                            ? "" : ", off: " + disabledCategories)
                    + ", " + (sources == null ? 0 : sources.size()) + " customised]";
        }
    }

    // ------------------------------------------------------------------- hud

    /**
     * Where the slot machine draws.
     *
     * <p>The position is a 0..1 fraction of the window rather than a pixel offset, so a
     * machine parked in the lower right stays in the lower right when the player
     * fullscreens, changes GUI scale or plugs in a different monitor. Pixel offsets are
     * why so many HUD mods put their widget off-screen the first time a laptop is docked.
     */
    public static final class HudSettings {

        /** Smallest usable scale; below this the item names stop being readable. */
        public static final double MIN_SCALE = 0.25;

        /** Largest scale, past which the machine covers most of a 1080p window. */
        public static final double MAX_SCALE = 4.0;

        /** Master switch for drawing the machine at all. */
        public boolean enabled = true;

        /** Horizontal position of the anchor point, as a fraction of window width. */
        public double x = 0.5;

        /** Vertical position of the anchor point, as a fraction of window height. */
        public double y = 0.25;

        /** Uniform scale multiplier applied on top of the GUI scale. */
        public double scale = 1.0;

        /** Which point of the widget {@link #x} and {@link #y} pin; see {@link HudAnchor}. */
        public HudAnchor anchor = HudAnchor.TOP_CENTER;

        /** Draw a translucent panel behind the reels so they stay legible over bright terrain. */
        public boolean drawBackground = true;

        /** Opacity of that panel: 0 fully transparent, 1 fully opaque. */
        public double backgroundOpacity = 0.55;

        /** Print the creature's name above the reels, so a screenshot explains itself. */
        public boolean showCreatureName = true;

        /**
         * Print each drop's name in small text under its sprite, inside the reel window.
         *
         * <p>On by default, and that default is the considered one rather than the timid one. A
         * Griffin Feather and a pair of Enchanted Ancient Claws are both a small brown shape at
         * 16x16, several Diana drops legitimately share a base item, and a drop the mod has no
         * icon mapped for would otherwise be an unreadable placeholder. The caption is what makes
         * the sprite mean something. Turning it off is for a player who already knows every icon
         * and wants the machine clean.
         */
        public boolean showDropNames = true;

        HudSettings sanitizedCopy() {
            var out = new HudSettings();
            out.enabled = enabled;
            out.x = clamp(x, 0.0, 1.0, 0.5);
            out.y = clamp(y, 0.0, 1.0, 0.25);
            out.scale = clamp(scale, MIN_SCALE, MAX_SCALE, 1.0);
            out.anchor = anchor == null ? HudAnchor.TOP_CENTER : anchor;
            out.drawBackground = drawBackground;
            out.backgroundOpacity = clamp(backgroundOpacity, 0.0, 1.0, 0.55);
            out.showCreatureName = showCreatureName;
            out.showDropNames = showDropNames;
            return out;
        }

        HudSettings copy() {
            var out = new HudSettings();
            out.enabled = enabled;
            out.x = x;
            out.y = y;
            out.scale = scale;
            out.anchor = anchor;
            out.drawBackground = drawBackground;
            out.backgroundOpacity = backgroundOpacity;
            out.showCreatureName = showCreatureName;
            out.showDropNames = showDropNames;
            return out;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof HudSettings s
                    && enabled == s.enabled
                    && Double.compare(x, s.x) == 0
                    && Double.compare(y, s.y) == 0
                    && Double.compare(scale, s.scale) == 0
                    && anchor == s.anchor
                    && drawBackground == s.drawBackground
                    && Double.compare(backgroundOpacity, s.backgroundOpacity) == 0
                    && showCreatureName == s.showCreatureName
                    && showDropNames == s.showDropNames;
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, x, y, scale, anchor, drawBackground, backgroundOpacity,
                    showCreatureName, showDropNames);
        }

        @Override
        public String toString() {
            return "HudSettings[" + anchor + " @ " + x + "," + y + " x" + scale + "]";
        }
    }

    // ---------------------------------------------------------------- sounds

    /**
     * The noises the machine makes.
     *
     * <p>Kept as its own group with its own master switch because muting the mod and
     * disabling the mod are different requests: a player recording video wants the reels
     * on screen and silent.
     */
    public static final class SoundSettings {

        /** Master switch for every sound the mod plays. */
        public boolean enabled = true;

        /** Multiplier applied on top of Minecraft's own volume sliders, 0..1. */
        public double volume = 0.7;

        /** The rapid click while the reels spin. */
        public boolean reelTicks = true;

        /** The flourish when a jackpot item lands. */
        public boolean jackpotSound = true;

        SoundSettings sanitizedCopy() {
            var out = new SoundSettings();
            out.enabled = enabled;
            out.volume = clamp(volume, 0.0, 1.0, 0.7);
            out.reelTicks = reelTicks;
            out.jackpotSound = jackpotSound;
            return out;
        }

        SoundSettings copy() {
            var out = new SoundSettings();
            out.enabled = enabled;
            out.volume = volume;
            out.reelTicks = reelTicks;
            out.jackpotSound = jackpotSound;
            return out;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SoundSettings s
                    && enabled == s.enabled
                    && Double.compare(volume, s.volume) == 0
                    && reelTicks == s.reelTicks
                    && jackpotSound == s.jackpotSound;
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, volume, reelTicks, jackpotSound);
        }

        @Override
        public String toString() {
            return "SoundSettings[" + (enabled ? "on" : "off") + " @" + volume + "]";
        }
    }

    // ---------------------------------------------------------------- clamps

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static long clamp(long value, long lo, long hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /**
     * Clamps a double, substituting {@code fallback} for NaN.
     *
     * <p>NaN needs its own case because it compares false against every bound, so a plain
     * min/max clamp passes it straight through to a renderer that then draws nothing at
     * all -- the hardest kind of config bug for a player to describe.
     */
    private static double clamp(double value, double lo, double hi, double fallback) {
        if (Double.isNaN(value)) {
            return fallback;
        }
        return Math.max(lo, Math.min(hi, value));
    }
}
