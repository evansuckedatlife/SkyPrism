package com.skyprism.core.level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A multi-stop colour ramp keyed by SkyBlock level, interpolated perceptually.
 *
 * <p>A ramp is defined by a handful of anchor levels with an exact hex each; every
 * level in between is blended in Oklab (see {@link Oklab} for why not sRGB). That
 * gives the feature its headline behaviour -- a distinct, evenly spaced colour per
 * level -- from a config small enough for a human to edit by hand.</p>
 *
 * <p><b>Guarantees callers rely on:</b> a level sitting exactly on a stop renders as
 * exactly that stop's hex, never a rounding-drifted neighbour; and levels below the
 * first stop or above the last clamp to the end colours rather than extrapolating
 * into nonsense. Clamping matters because SkyBlock levels have no hard cap -- a ramp
 * written today for 0..500 must still return something sane at level 700.</p>
 *
 * <p>Instances are immutable and thread-safe; {@link #colorAt(int)} is a binary
 * search plus at most one blend, cheap enough for a per-frame TAB redraw.</p>
 */
public final class GradientRamp {

    /**
     * One anchor of the ramp.
     *
     * @param level the SkyBlock level this colour is pinned to
     * @param rgb   packed {@code 0xRRGGBB}; bits above bit 23 are discarded so a
     *              caller passing an alpha-carrying int still gets a canonical value
     */
    public record Stop(int level, int rgb) {
        public Stop {
            rgb &= 0xFFFFFF;
        }
    }

    private final List<Stop> stops;
    private final int[] levels;
    private final int[] colors;

    /**
     * @param stops at least one stop, in any order; duplicated levels are rejected
     *              because two colours pinned to one level has no defined meaning and
     *              silently dropping one would hide a config typo
     * @throws NullPointerException     if {@code stops} or any element is null
     * @throws IllegalArgumentException if empty, or if two stops share a level
     */
    public GradientRamp(List<Stop> stops) {
        Objects.requireNonNull(stops, "stops");
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a gradient needs at least one stop");
        }

        var sorted = new ArrayList<>(stops);
        for (Stop s : sorted) {
            Objects.requireNonNull(s, "stop");
        }
        sorted.sort(Comparator.comparingInt(Stop::level));

        this.levels = new int[sorted.size()];
        this.colors = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            Stop s = sorted.get(i);
            if (i > 0 && s.level() == levels[i - 1]) {
                throw new IllegalArgumentException("duplicate gradient stop at level " + s.level());
            }
            levels[i] = s.level();
            colors[i] = s.rgb();
        }
        this.stops = List.copyOf(sorted);
    }

    /**
     * Terse factory for presets and tests: alternating {@code level, rgb} pairs.
     *
     * @param levelThenRgb an even-length run of level and packed colour values
     * @return the ramp those pairs describe
     */
    public static GradientRamp of(int... levelThenRgb) {
        if (levelThenRgb.length % 2 != 0) {
            throw new IllegalArgumentException("expected level,rgb pairs");
        }
        var list = new ArrayList<Stop>(levelThenRgb.length / 2);
        for (int i = 0; i < levelThenRgb.length; i += 2) {
            list.add(new Stop(levelThenRgb[i], levelThenRgb[i + 1]));
        }
        return new GradientRamp(list);
    }

    /**
     * The colour for a level.
     *
     * @param level any level, including negative or absurdly high values
     * @return packed {@code 0xRRGGBB}: the exact stop colour on a stop, the clamped
     *         end colour outside the ramp, otherwise the Oklab blend of the two
     *         surrounding stops
     */
    public int colorAt(int level) {
        int idx = Arrays.binarySearch(levels, level);
        if (idx >= 0) {
            return colors[idx];
        }
        int hi = -idx - 1;
        if (hi == 0) {
            return colors[0];
        }
        if (hi == levels.length) {
            return colors[levels.length - 1];
        }
        int lo = hi - 1;
        // Widened to long deliberately: a ramp whose stops straddle zero can span more
        // than Integer.MAX_VALUE, and the int subtraction then wraps negative, flipping
        // the blend fraction and snapping the whole middle of the ramp to one endpoint.
        long span = (long) levels[hi] - (long) levels[lo];
        double t = (double) ((long) level - (long) levels[lo]) / (double) span;
        return Oklab.mix(colors[lo], colors[hi], t);
    }

    /** The stops, sorted ascending by level, immutable. */
    public List<Stop> stops() {
        return stops;
    }

    @Override
    public String toString() {
        return "GradientRamp" + stops;
    }
}
