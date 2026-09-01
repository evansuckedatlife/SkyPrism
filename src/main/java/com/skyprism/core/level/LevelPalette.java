package com.skyprism.core.level;

import java.util.Objects;

/**
 * The one object the render layer asks for a level tag's colour.
 *
 * <p>It bundles a {@link LevelColorMode} with the ramp, table and shimmer that mode
 * might need, so the Minecraft-side code never branches on mode itself: it holds a
 * palette, calls {@link #colorFor(int, long)}, and paints what comes back. Rebuilding
 * this object is how a config change takes effect -- palettes are immutable, so a
 * half-applied setting can never be observed mid-frame.</p>
 *
 * <p><b>The chroma override wins.</b> When the shimmer is on and the level clears the
 * threshold, the animated colour replaces the mode's static answer entirely rather
 * than tinting it. That is the point of the feature: the tag reads as "this player is
 * beyond the top of your palette" and no static hex can say that.</p>
 *
 * <p><b>Why {@link #isChromatic(int)} exists separately.</b> The TAB list caches a
 * rendered component per player and only re-renders what changed. A static tag can
 * stay cached indefinitely; a shimmering one must be rebuilt every frame. That check
 * runs for every player in the list on every frame, so it is two field reads and a
 * comparison -- no palette lookup, no time source -- and it is contractually false
 * whenever chroma is disabled, so turning the shimmer off restores a fully static,
 * fully cacheable TAB.</p>
 *
 * <p>Immutable and thread-safe.</p>
 */
public final class LevelPalette {

    private final LevelColorMode mode;
    private final GradientRamp ramp;
    private final BracketTable table;
    private final boolean chromaEnabled;
    private final int chromaMinLevel;
    private final ChromaClock chroma;

    /**
     * @param mode           which colouring strategy applies; never null
     * @param ramp           the gradient; required only when {@code mode} is
     *                       {@link LevelColorMode#GRADIENT}, may be null otherwise
     * @param table          the bracket table; required only when {@code mode} is
     *                       {@link LevelColorMode#BRACKETS}, may be null otherwise.
     *                       {@link LevelColorMode#VANILLA} deliberately ignores it and
     *                       uses {@link PalettePresets#vanillaBrackets()}, so "vanilla"
     *                       cannot be quietly redefined by a stale config
     * @param chromaEnabled  whether the shimmer applies at all
     * @param chromaMinLevel the lowest level that shimmers, inclusive; ignored when
     *                       {@code chromaEnabled} is false
     * @param chroma         the shimmer; required only when {@code chromaEnabled}
     * @throws NullPointerException if a component the configuration actually needs is
     *                              null -- failing here beats rendering a black tag and
     *                              leaving the user to guess which setting is empty
     */
    public LevelPalette(LevelColorMode mode, GradientRamp ramp, BracketTable table,
                        boolean chromaEnabled, int chromaMinLevel, ChromaClock chroma) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (mode == LevelColorMode.GRADIENT) {
            Objects.requireNonNull(ramp, "ramp is required in GRADIENT mode");
        }
        if (mode == LevelColorMode.BRACKETS) {
            Objects.requireNonNull(table, "table is required in BRACKETS mode");
        }
        if (chromaEnabled) {
            Objects.requireNonNull(chroma, "chroma is required when chromaEnabled");
        }
        this.ramp = ramp;
        this.table = table;
        this.chromaEnabled = chromaEnabled;
        this.chromaMinLevel = chromaMinLevel;
        this.chroma = chroma;
    }

    /**
     * The default palette: {@link PalettePresets#defaultBrackets()} with no shimmer.
     *
     * <p>The same mode and the same table the shipped config names, so a client that never loaded
     * a config file and one that loaded the defaults draw identical tags. They used to differ --
     * this fell back to {@code vanillaPlus()} while the config asked for the wider ramp -- which
     * made the first frames after a failed config read a different colour from every frame after
     * it. That is why the mode moved here in lockstep when the shipped default changed from a
     * gradient to a table: leaving this on {@link LevelColorMode#GRADIENT} would have rebuilt
     * exactly the same bug in a new place.
     *
     * <p>{@link PalettePresets#defaultRamp()} still rides along in the unused ramp slot, so a
     * caller that flips the mode without reloading a config gets the shipped gradient rather
     * than a null.
     *
     * @return a palette safe to use before any config has been loaded
     */
    public static LevelPalette defaults() {
        return new LevelPalette(LevelColorMode.BRACKETS, PalettePresets.defaultRamp(),
            PalettePresets.defaultBrackets(), false, Integer.MAX_VALUE, null);
    }

    /**
     * The colour to draw a level tag in.
     *
     * @param level  the SkyBlock level from the tag
     * @param millis the frame's timestamp; only read when {@link #isChromatic(int)} is
     *               true, so a caller with a static palette may pass anything
     * @return packed {@code 0xRRGGBB}
     */
    public int colorFor(int level, long millis) {
        if (isChromatic(level)) {
            // The level doubles as the hue phase so that two shimmering names in the
            // same TAB list are offset from each other instead of pulsing in unison.
            return chroma.colorAt(millis, level);
        }
        return switch (mode) {
            case GRADIENT -> ramp.colorAt(level);
            case BRACKETS -> table.colorAt(level);
            case VANILLA -> PalettePresets.vanillaBrackets().colorAt(level);
        };
    }

    /**
     * Whether this level animates, and therefore whether a cached render of it must be
     * thrown away each frame.
     *
     * @param level the SkyBlock level from the tag
     * @return true only when the shimmer is enabled and {@code level} is at or above
     *         the threshold; always false when the shimmer is off
     */
    public boolean isChromatic(int level) {
        return chromaEnabled && level >= chromaMinLevel;
    }

    /** The configured mode. */
    public LevelColorMode mode() {
        return mode;
    }

    /** The gradient, or null when the configuration carries none. */
    public GradientRamp ramp() {
        return ramp;
    }

    /** The bracket table, or null when the configuration carries none. */
    public BracketTable table() {
        return table;
    }

    /** Whether the shimmer is switched on at all. */
    public boolean chromaEnabled() {
        return chromaEnabled;
    }

    /** The lowest shimmering level, meaningful only when {@link #chromaEnabled()}. */
    public int chromaMinLevel() {
        return chromaMinLevel;
    }

    /** The shimmer, or null when it is switched off. */
    public ChromaClock chroma() {
        return chroma;
    }

    @Override
    public String toString() {
        return "LevelPalette[" + mode + (chromaEnabled ? ", chroma>=" + chromaMinLevel : "") + "]";
    }
}
