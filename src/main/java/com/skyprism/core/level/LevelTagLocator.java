package com.skyprism.core.level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds SkyBlock level prefixes -- {@code [451]} -- inside already-plain text.
 *
 * <p>This is the highest-risk parser in the mod and is deliberately paranoid. A
 * false positive repaints something that is not a level (a rank tag, a burrow
 * counter, a party slot count) and is immediately visible as a bug to every player
 * in the lobby; a false negative just leaves the tag in Hypixel's own colour, which
 * is merely disappointing. The rules below therefore err hard towards rejecting.</p>
 *
 * <h2>What counts as a tag</h2>
 * <ol>
 *   <li>Literally {@code '['}, one or more ASCII digits, {@code ']'} -- nothing else
 *       between the brackets. This alone rejects {@code [MVP+]}, {@code [VIP]},
 *       {@code [Lv100]}, {@code [Healer]}, {@code [6/8]}, {@code [12}&#x2726;{@code ]},
 *       {@code [451x]}, {@code [x451]}, {@code [ 451 ]}, {@code [-5]} and {@code []}.</li>
 *   <li><b>No leading zeros.</b> {@code [0]} is accepted (level 0 is a real level and
 *       Hypixel colours it grey), but {@code [0451]} is rejected. Hypixel never pads,
 *       so a zero-padded number is something else's formatting and not ours to touch.</li>
 *   <li><b>At most {@value #MAX_DIGITS} digits</b>, so a hostile or nonsense run such as
 *       {@code [99999999999]} simply fails to match rather than overflowing --
 *       {@link Integer#parseInt} is never reached with a value it cannot hold, and
 *       {@code find} never throws {@link NumberFormatException}.</li>
 *   <li>The parsed value must fall inside the configured sanity range. The standard
 *       range is {@value #STANDARD_MIN}..{@value #STANDARD_MAX}; the live cap today is
 *       far lower, but headroom costs nothing and Hypixel raises it over time.</li>
 *   <li><b>Boundary rule.</b> The code point immediately before {@code '['} and the one
 *       immediately after {@code ']'} must each be absent (string edge) or must not be
 *       a letter or a digit. Code point, not {@code char}: a supplementary-plane letter
 *       such as U+1D400 blocks the match just as {@code 'x'} does. Hypixel always emits the tag either at the start of the
 *       line or after a space, and always follows it with a space, so this costs no
 *       real matches while rejecting a bracket buried in a word such as
 *       {@code x[451]y}. A tag ending the string with nothing after it is accepted:
 *       TAB entries and name-only renders legitimately end there.</li>
 * </ol>
 *
 * <p>A bare {@code 451} with no brackets never matches -- the brackets are the whole
 * signal that this is a prefix and not someone's damage number.</p>
 *
 * <p>Prefix <i>emblems</i> (the diamond-like symbols earned every ten levels) render to
 * the <i>right</i> of the player name and are not bracketed digits, so nothing here can
 * reach them. That is by design and must stay that way.</p>
 *
 * <p>Matches are reported left to right and are non-overlapping. Adjacent tags
 * {@code [1][2]} both match: the {@code ']'} sitting between them is not a letter or a
 * digit, so each satisfies the boundary rule.</p>
 *
 * <p>Input must already be formatting-stripped (see
 * {@code com.skyprism.core.util.TextClean#stripFormatting}); a stray section sign would
 * otherwise sit between the boundary character and the bracket and change the answer.</p>
 *
 * <p>Instances are immutable and safe to share across threads.</p>
 */
public final class LevelTagLocator {

    /** Lowest level the standard locator accepts. Hypixel's own first tier starts at 0. */
    public static final int STANDARD_MIN = 0;

    /** Highest level the standard locator accepts -- generous headroom over the live cap. */
    public static final int STANDARD_MAX = 1000;

    /**
     * Digit-count ceiling. Nine digits is the widest run that always fits an
     * {@code int}, which is what makes parsing unconditionally safe.
     */
    public static final int MAX_DIGITS = 9;

    /**
     * The two lookarounds are the boundary rule; the {@code 0|[1-9]\d{0,8}} alternation
     * is the no-leading-zeros and nine-digit rules. Range checking is left to
     * {@link #find(String)} so the reason a candidate was dropped stays readable.
     *
     * <p>The {@code {1,2}} on the lookbehind is load-bearing and must not be "simplified"
     * away. Java sizes a lookbehind in {@code char}s, not in code points, so a plain
     * {@code (?<![\p{L}\p{N}])} steps back exactly one {@code char} -- which, when the
     * preceding character is a supplementary-plane letter such as U+1D400 MATHEMATICAL
     * BOLD CAPITAL A, lands on its low surrogate. A lone low surrogate is category
     * {@code Cs}, not {@code L}, so the guard silently passed and {@code "}&#x1D400;{@code [451]"}
     * matched. Allowing the condition to start one {@code char} earlier lets
     * {@code \p{L}} read the whole code point. The lookahead needs no such help: matching
     * forwards already reads a full code point. Widening the window cannot create new
     * rejections for BMP text, because a two-{@code char} run of letters or digits ending
     * at the bracket already contains a one-{@code char} one.
     */
    private static final Pattern TAG = Pattern.compile(
            "(?<![\\p{L}\\p{N}]{1,2})\\[(0|[1-9][0-9]{0," + (MAX_DIGITS - 1) + "})\\](?![\\p{L}\\p{N}])");

    private final int minLevel;
    private final int maxLevel;

    /**
     * @param minLevel lowest accepted level, inclusive; must be >= 0
     * @param maxLevel highest accepted level, inclusive; must be >= {@code minLevel}
     * @throws IllegalArgumentException if the range is negative or inverted
     */
    public LevelTagLocator(int minLevel, int maxLevel) {
        if (minLevel < 0) {
            throw new IllegalArgumentException("minLevel must be >= 0 but was " + minLevel);
        }
        if (maxLevel < minLevel) {
            throw new IllegalArgumentException(
                    "maxLevel " + maxLevel + " must be >= minLevel " + minLevel);
        }
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
    }

    /** The locator every feature should use unless it has a reason not to: {@value #STANDARD_MIN}..{@value #STANDARD_MAX}. */
    public static LevelTagLocator standard() {
        return new LevelTagLocator(STANDARD_MIN, STANDARD_MAX);
    }

    /** Lowest level this locator accepts, inclusive. */
    public int minLevel() {
        return minLevel;
    }

    /** Highest level this locator accepts, inclusive. */
    public int maxLevel() {
        return maxLevel;
    }

    /**
     * Locates every level tag in a line of already-plain text.
     *
     * @param plain formatting-stripped text; {@code null} is treated as empty
     * @return an immutable list, never {@code null}, ordered by position and
     *         non-overlapping; empty when the line holds no level tag
     */
    public List<LevelTag> find(String plain) {
        if (plain == null || plain.length() < 3) {
            return List.of();
        }

        List<LevelTag> found = null;
        Matcher m = TAG.matcher(plain);
        while (m.find()) {
            int level = Integer.parseInt(m.group(1)); // width-capped by TAG, cannot overflow
            if (level < minLevel || level > maxLevel) {
                continue;
            }
            if (found == null) {
                found = new ArrayList<>(2);
            }
            found.add(new LevelTag(m.start(), m.end(), level, m.start(1), m.end(1)));
        }
        return found == null ? List.of() : Collections.unmodifiableList(found);
    }

    /**
     * The first tag in the line, for the common case of a chat message whose only tag
     * is the speaker's own prefix.
     *
     * @param plain formatting-stripped text; {@code null} is treated as empty
     * @return the leftmost tag, or {@code null} when there is none
     */
    public LevelTag findFirst(String plain) {
        List<LevelTag> all = find(plain);
        return all.isEmpty() ? null : all.get(0);
    }

    @Override
    public String toString() {
        return "LevelTagLocator[" + minLevel + ".." + maxLevel + "]";
    }
}
