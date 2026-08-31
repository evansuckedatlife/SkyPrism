package com.skyprism.core.level;

/**
 * One located SkyBlock level prefix, e.g. the {@code [451]} Hypixel renders to the
 * left of a player's name in chat and TAB.
 *
 * <p>Two ranges are carried instead of one because the two things a caller might
 * want to recolour are different: some users want the whole tag including the
 * square brackets tinted, others want the brackets left in Hypixel's grey and only
 * the number gradient-coloured. Recomputing the digit span from the outer span at
 * the call site would mean every Minecraft adapter re-deriving "start + 1" and
 * silently drifting from whatever the locator actually matched, so the locator
 * hands both spans over as facts.</p>
 *
 * <p>Both ranges are half-open ({@code [start, end)}), matching
 * {@link String#substring(int, int)} and {@code MutableText} slicing, so an adapter
 * can splice without any off-by-one arithmetic of its own.</p>
 *
 * @param start       index of the opening {@code '['} in the source string
 * @param end         index one past the closing {@code ']'}
 * @param level       the parsed level, guaranteed inside the locator's sanity range
 * @param digitsStart index of the first digit, i.e. {@code start + 1}
 * @param digitsEnd   index one past the last digit, i.e. {@code end - 1}
 */
public record LevelTag(int start, int end, int level, int digitsStart, int digitsEnd) {

    public LevelTag {
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0 but was " + start);
        }
        if (digitsStart <= start || digitsEnd >= end || digitsStart >= digitsEnd) {
            throw new IllegalArgumentException(
                    "digit span " + digitsStart + ".." + digitsEnd
                            + " must sit strictly inside tag span " + start + ".." + end);
        }
        if (level < 0) {
            throw new IllegalArgumentException("level must be >= 0 but was " + level);
        }
    }

    /** Total character length of the tag, brackets included. */
    public int length() {
        return end - start;
    }

    /**
     * The exact tag text as it appeared, e.g. {@code "[451]"}.
     *
     * <p>Only meaningful when {@code plain} is the same string the tag was found in;
     * it exists so tests and log lines can echo what matched without the caller
     * re-slicing by hand.</p>
     *
     * @param plain the string this tag was located in
     * @return the substring covered by {@code start..end}
     */
    public String textIn(String plain) {
        return plain.substring(start, end);
    }
}
