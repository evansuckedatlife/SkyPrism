package com.skyprism.mc.command;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.level.BracketTable;
import com.skyprism.core.level.ChromaClock;
import com.skyprism.core.level.GradientRamp;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.LevelPalette;

/**
 * Turns {@link SkyPrismConfig.LevelSettings} into a {@link LevelPalette}.
 *
 * <p><b>Why this is not in the core.</b> The core deliberately stops one step short: it
 * offers {@code resolveRamp()}, {@code resolveTable()} and {@code resolveLocator()} but no
 * {@code resolvePalette()}, because {@link LevelPalette}'s constructor throws when a mode
 * and its required table disagree, and the core would rather hand the adapter three
 * never-throwing pieces than one that can. Assembling them - and deciding what a
 * nonsensical combination should degrade to on screen rather than in a crash report - is
 * adapter policy, so it lives here.</p>
 *
 * <p>This class is the <em>fallback</em> path. When the level-colour module is wired,
 * {@link SkyPrismServices#level()} hands back the palette the renderers are actually
 * using; this is what {@code /skyprism preview} falls back to when it is not, and what the
 * level module itself may reuse if it wants the same clamping rules.</p>
 */
public final class Palettes {

    private Palettes() {
    }

    /**
     * Builds the palette these settings describe.
     *
     * <p>Never throws. A mode with a missing ramp or table falls back to the core's own
     * defaults through {@code resolveRamp()} / {@code resolveTable()}, and a chroma
     * configuration the {@link ChromaClock} constructor rejects switches chroma off rather
     * than taking the client down - a shimmer that quietly stops is a far better failure
     * than a crash on the first chat message.</p>
     *
     * @param settings the level settings, may be null (treated as defaults)
     * @return a usable palette, never null
     */
    public static LevelPalette fromConfig(SkyPrismConfig.LevelSettings settings) {
        SkyPrismConfig.LevelSettings s =
                settings == null ? SkyPrismConfig.defaults().levels : settings;

        LevelColorMode mode = s.mode == null ? LevelColorMode.GRADIENT : s.mode;
        GradientRamp ramp = mode == LevelColorMode.GRADIENT ? s.resolveRamp() : null;
        BracketTable table = mode == LevelColorMode.BRACKETS ? s.resolveTable() : null;

        boolean chromaOn = s.chromaEnabled;
        ChromaClock chroma = null;
        if (chromaOn) {
            try {
                // Saturation and lightness come from the config, not from constants here.
                // They used to be hard-coded at 0.85/0.62 while ConfigManager built the live
                // palette from levels.chromaSaturation/.chromaLightness (0.90/0.62), so
                // /skyprism preview rendered the shimmer at a different vividness from the
                // chat and TAB it exists to predict. SkyPrismConfig.sanitised() has already
                // clamped both into their legal range, so no re-clamp is needed here.
                chroma = new ChromaClock(s.chromaCyclesPerSecond,
                        s.chromaSaturation, s.chromaLightness);
            } catch (IllegalArgumentException outOfRange) {
                chromaOn = false;
            }
        }

        return new LevelPalette(mode, ramp, table, chromaOn, s.chromaMinLevel, chroma);
    }

    /**
     * Quantises a timestamp to the configured chroma refresh rate.
     *
     * <p>Feeding a palette the raw wall clock makes it produce a new colour on every single
     * frame, which for TAB means invalidating up to 80 memoised components 60 times a
     * second. Rounding the clock down to a refresh step means the palette returns the
     * <em>same</em> colour for every frame inside that step, so the memo holds and the
     * shimmer costs the configured number of recomputes per second and no more. The preview
     * screen uses the same quantisation so it animates exactly as chat will.</p>
     *
     * @param millis   a wall-clock reading
     * @param settings the level settings supplying {@code chromaUpdateHz}
     * @return the timestamp rounded down to the current refresh step
     */
    public static long quantise(long millis, SkyPrismConfig.LevelSettings settings) {
        int hz = settings == null ? SkyPrismConfig.LevelSettings.MIN_CHROMA_HZ : settings.chromaUpdateHz;
        if (hz < SkyPrismConfig.LevelSettings.MIN_CHROMA_HZ) {
            hz = SkyPrismConfig.LevelSettings.MIN_CHROMA_HZ;
        } else if (hz > SkyPrismConfig.LevelSettings.MAX_CHROMA_HZ) {
            hz = SkyPrismConfig.LevelSettings.MAX_CHROMA_HZ;
        }
        long step = Math.max(1L, 1000L / hz);
        return millis - Math.floorMod(millis, step);
    }
}
