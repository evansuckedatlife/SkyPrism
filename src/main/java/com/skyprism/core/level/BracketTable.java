package com.skyprism.core.level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A step table: a sorted list of "from this level onwards, use this colour".
 *
 * <p>This is the discrete counterpart to {@link GradientRamp}. Hypixel's own level
 * prefix is exactly this shape -- 13 tiers, one every 40 levels -- so reproducing it
 * faithfully gives both {@link LevelColorMode#VANILLA} and the reference the
 * gradient modes get compared against. Users who find a per-level gradient too
 * noisy edit a table instead, where a colour means a recognisable band rather than
 * a precise number.</p>
 *
 * <p>A level below the lowest bracket clamps to that lowest bracket rather than
 * throwing or returning a sentinel: level 0 is a real level, and a table a user
 * happened to start at 10 should still colour a brand-new player.</p>
 *
 * <p>Immutable, thread-safe, one binary search per lookup.</p>
 */
public final class BracketTable {

    /**
     * One tier.
     *
     * @param minLevel the lowest level this colour applies to, inclusive
     * @param rgb      packed {@code 0xRRGGBB}; bits above bit 23 are discarded
     */
    public record Bracket(int minLevel, int rgb) {
        public Bracket {
            rgb &= 0xFFFFFF;
        }
    }

    private final List<Bracket> brackets;
    private final int[] mins;
    private final int[] colors;

    /**
     * @param brackets at least one bracket, in any order; duplicated {@code minLevel}
     *                 values are rejected for the same reason as gradient stops: two
     *                 answers for one level is a typo, not a preference
     * @throws NullPointerException     if {@code brackets} or any element is null
     * @throws IllegalArgumentException if empty, or if two brackets share a minLevel
     */
    public BracketTable(List<Bracket> brackets) {
        Objects.requireNonNull(brackets, "brackets");
        if (brackets.isEmpty()) {
            throw new IllegalArgumentException("a bracket table needs at least one bracket");
        }

        var sorted = new ArrayList<>(brackets);
        for (Bracket b : sorted) {
            Objects.requireNonNull(b, "bracket");
        }
        sorted.sort(Comparator.comparingInt(Bracket::minLevel));

        this.mins = new int[sorted.size()];
        this.colors = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            Bracket b = sorted.get(i);
            if (i > 0 && b.minLevel() == mins[i - 1]) {
                throw new IllegalArgumentException("duplicate bracket at level " + b.minLevel());
            }
            mins[i] = b.minLevel();
            colors[i] = b.rgb();
        }
        this.brackets = List.copyOf(sorted);
    }

    /**
     * Terse factory for presets and tests: alternating {@code minLevel, rgb} pairs.
     *
     * @param minThenRgb an even-length run of minLevel and packed colour values
     * @return the table those pairs describe
     */
    public static BracketTable of(int... minThenRgb) {
        if (minThenRgb.length % 2 != 0) {
            throw new IllegalArgumentException("expected minLevel,rgb pairs");
        }
        var list = new ArrayList<Bracket>(minThenRgb.length / 2);
        for (int i = 0; i < minThenRgb.length; i += 2) {
            list.add(new Bracket(minThenRgb[i], minThenRgb[i + 1]));
        }
        return new BracketTable(list);
    }

    /**
     * The colour for a level: the highest bracket whose {@code minLevel} is less than
     * or equal to it, or the lowest bracket when the level is below them all.
     *
     * @param level any level, including negative values
     * @return packed {@code 0xRRGGBB}
     */
    public int colorAt(int level) {
        int idx = Arrays.binarySearch(mins, level);
        if (idx >= 0) {
            return colors[idx];
        }
        int hi = -idx - 1;
        return hi == 0 ? colors[0] : colors[hi - 1];
    }

    /** The brackets, sorted ascending by minLevel, immutable. */
    public List<Bracket> brackets() {
        return brackets;
    }

    @Override
    public String toString() {
        return "BracketTable" + brackets;
    }
}
