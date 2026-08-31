package com.skyprism.core.util;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Minecraft-free helpers for turning chat/scoreboard/tooltip strings into plain
 * comparable text.
 *
 * <p>Hypixel formats almost everything with legacy section-sign codes, so every
 * feature that parses a line has to strip them first. Keeping that here -- with
 * no Minecraft imports -- means the parsing rules can be unit tested on a bare
 * JVM instead of inside a booted game.</p>
 *
 * <p><b>Why a section sign alone is not a code:</b> an earlier version consumed
 * whatever character followed a section sign. That was wrong twice over. A chat
 * line carrying a literal section sign in front of a space ("a&#167; b") lost the
 * space and silently welded two words together, defeating the whole point of
 * {@link #clean(String)}; and a section sign in front of an emoji or any other
 * supplementary code point ate only the high surrogate, leaving an unpaired low
 * surrogate that corrupts every downstream comparison and render. Both are fixed
 * by only treating a section sign as a code when a genuine legacy code character
 * follows it -- everything else is user text and survives verbatim.</p>
 *
 * <p><b>Null policy:</b> every method is null-safe and returns {@code null} for a
 * {@code null} input (null in, null out -- never an empty string, so a missing
 * value stays distinguishable from a blank one).</p>
 */
public final class TextClean {
    /** The legacy formatting prefix, U+00A7. Written as an escape so the file's encoding cannot matter. */
    public static final char SECTION = '\u00A7';

    /**
     * Whitespace collapsing uses {@link Pattern#UNICODE_CHARACTER_CLASS} so that
     * {@code \s} means the Unicode White_Space property rather than the six ASCII
     * spaces. Hypixel and several of its rank/emblem strings emit U+00A0 and other
     * non-breaking or thin spaces; without this flag they were neither collapsed
     * nor stripped, so a "cleaned" line still failed a literal comparison.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Number of characters in the six-code RGB tail that follows a {@code section-x} marker. */
    private static final int RGB_TAIL_LENGTH = 12;

    private TextClean() {
    }

    /**
     * A stripped string plus, for every character left in it, where that character came
     * from in the original.
     *
     * <p>Exists because stripping is only half of what a <em>rewriter</em> needs. Parsing
     * happens on the stripped text -- that is the only form a pattern can match reliably
     * -- but the edit has to land on the original, and by then the two strings no longer
     * share an index. Anything that has to answer "which characters of the raw line did
     * this match cover?" would otherwise have to re-implement the escape rules alongside
     * its own bookkeeping, which is the same rule written twice and free to drift.
     *
     * <p><b>Indices are UTF-16 char indices</b>, matching {@link String#charAt(int)}, not
     * code points. Characters are copied one for one, so both halves of a surrogate pair
     * survive and stay adjacent, and their source indices differ by exactly one.
     *
     * <p><b>The array is not copied on the way out.</b> {@link #sourceIndex()} returns the
     * live array so a caller in a render path can index it without allocating; treat it as
     * read-only. It <em>is</em> copied on the way in, so constructing one from an array you
     * still hold is safe.
     *
     * @param stripped    the text with every formatting code removed; never null
     * @param sourceIndex one entry per char of {@code stripped}, in order, each the index
     *                    in the original string that char was copied from; strictly
     *                    increasing
     */
    public record StripResult(String stripped, int[] sourceIndex) {

        public StripResult {
            Objects.requireNonNull(stripped, "stripped");
            Objects.requireNonNull(sourceIndex, "sourceIndex");
            if (sourceIndex.length != stripped.length()) {
                throw new IllegalArgumentException("sourceIndex has " + sourceIndex.length
                        + " entries but the stripped text has " + stripped.length() + " chars");
            }
            sourceIndex = sourceIndex.clone();
        }

        /** Characters in {@link #stripped()}, which is also the length of the projection. */
        public int length() {
            return stripped.length();
        }

        /**
         * Where a stripped character came from.
         *
         * @param strippedIndex an index into {@link #stripped()}
         * @return the index of that same character in the original string
         * @throws IndexOutOfBoundsException if {@code strippedIndex} is not a valid index
         */
        public int sourceIndexOf(int strippedIndex) {
            return sourceIndex[strippedIndex];
        }

        /**
         * The projection of an <em>exclusive</em> end offset, so a half-open range
         * {@code [from, to)} in the stripped text maps to
         * {@code [sourceIndexOf(from), sourceEndOf(to))} in the original.
         *
         * <p>Not simply {@code sourceIndexOf(to)}: a range can end at the very end of the
         * string, where there is no character to ask about, and mapping the first character
         * <em>after</em> the range would swallow any formatting code sitting between them
         * into the range. Mapping the last character of the range and adding one keeps a
         * code that follows the range outside it, while a code sitting <em>inside</em> the
         * range stays inside it -- which is what a caller restyling a span wants, since a
         * code it covers is invisible either way.
         *
         * @param strippedEndExclusive an end offset, 0..{@link #length()}
         * @return the matching end offset in the original string, exclusive
         * @throws IndexOutOfBoundsException if the offset is outside 0..{@link #length()}
         */
        public int sourceEndOf(int strippedEndExclusive) {
            if (strippedEndExclusive < 0 || strippedEndExclusive > sourceIndex.length) {
                throw new IndexOutOfBoundsException(
                        "end " + strippedEndExclusive + " outside 0.." + sourceIndex.length);
            }
            // An empty range at the start maps to the start; there is no earlier character
            // to derive it from, and any other answer would be invented.
            return strippedEndExclusive == 0 ? 0 : sourceIndex[strippedEndExclusive - 1] + 1;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StripResult r
                    && stripped.equals(r.stripped)
                    && Arrays.equals(sourceIndex, r.sourceIndex);
        }

        @Override
        public int hashCode() {
            return 31 * stripped.hashCode() + Arrays.hashCode(sourceIndex);
        }

        @Override
        public String toString() {
            return "StripResult[" + stripped + " <- " + Arrays.toString(sourceIndex) + "]";
        }
    }


    /**
     * Removes legacy formatting codes, leaving every other character -- including
     * whitespace, emoji and lone section signs -- exactly as it was.
     *
     * <p>Three shapes are recognised:</p>
     * <ul>
     *   <li>a section sign followed by a legacy code character
     *       {@code [0-9a-fk-orA-FK-OR]}: both are dropped;</li>
     *   <li>a section sign followed by {@code x} or {@code X} and then six
     *       section-prefixed hex digits (the RGB form some proxies and servers
     *       emit): all fourteen characters are dropped as one unit, so the hex
     *       digits never leak into the output as text;</li>
     *   <li>anything else, including a trailing or malformed section sign: kept
     *       verbatim, because it is content rather than formatting.</li>
     * </ul>
     *
     * <p>Text containing no section sign at all is returned without copying.</p>
     *
     * <p><b>Not idempotent when a literal section sign survives.</b> Preserving a
     * malformed section sign is what keeps a word boundary and a code point
     * intact, but it means the result can still contain a section sign, and on a
     * second pass that sign may now sit in front of a different character. So
     * {@code "§§rF"} strips once to {@code "§F"} -- correct, and
     * what the vanilla client renders -- and stripping <em>that</em> again yields
     * {@code ""}, because {@code §F} reads as a colour code. Mojang's own
     * stripper behaves identically; there is no fix that keeps both properties
     * without an escape the {@code String} API has nowhere to put. Strip once,
     * at the boundary where a raw line enters the core, and pass the result on.
     * The output <em>is</em> a fixed point whenever it contains no section sign,
     * which is every well-formed Hypixel line.</p>
     *
     * @param in the raw string, may be null
     * @return the string without formatting codes, or null if {@code in} was null
     */
    public static String stripFormatting(String in) {
        if (in == null) {
            return null;
        }
        if (in.indexOf(SECTION) < 0) {
            return in;
        }

        int length = in.length();
        StringBuilder out = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            char c = in.charAt(i);
            if (c == SECTION && i + 1 < length) {
                char code = in.charAt(i + 1);
                if ((code == 'x' || code == 'X') && hasRgbTail(in, i + 2)) {
                    i += 2 + RGB_TAIL_LENGTH;
                    continue;
                }
                if (isLegacyCode(code)) {
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * {@link #stripFormatting(String)}, plus the index projection back to the input.
     *
     * <p>Same rules, same output text -- {@code stripFormattingWithOffsets(x).stripped()}
     * equals {@code stripFormatting(x)} for every {@code x}, and that is asserted rather
     * than assumed. The only difference is that this one also records, for each surviving
     * character, where it came from, so a caller that parses the stripped text can apply
     * its answer to the original string instead of to the copy.
     *
     * <p>Use this when the raw text is going to be edited or annotated; use
     * {@link #stripFormatting(String)} when it is only going to be read or compared, since
     * that one can hand back the input unchanged when there is nothing to strip, whereas
     * this one always builds the projection.
     *
     * @param in the raw string, may be null
     * @return the stripped text and its projection, or null if {@code in} was null
     */
    public static StripResult stripFormattingWithOffsets(String in) {
        if (in == null) {
            return null;
        }

        int length = in.length();
        StringBuilder out = new StringBuilder(length);
        int[] source = new int[length];
        int kept = 0;
        int i = 0;
        while (i < length) {
            char c = in.charAt(i);
            if (c == SECTION && i + 1 < length) {
                char code = in.charAt(i + 1);
                if ((code == 'x' || code == 'X') && hasRgbTail(in, i + 2)) {
                    i += 2 + RGB_TAIL_LENGTH;
                    continue;
                }
                if (isLegacyCode(code)) {
                    i += 2;
                    continue;
                }
            }
            source[kept++] = i;
            out.append(c);
            i++;
        }
        return new StripResult(out.toString(),
                kept == length ? source : Arrays.copyOf(source, kept));
    }

    /**
     * {@link #stripFormatting(String)}, then collapses every run of Unicode
     * whitespace to a single ASCII space and trims the ends.
     *
     * <p>This is the form to compare against a literal: stripping codes on its
     * own routinely leaves double spaces where a code sat between two words, and
     * Hypixel mixes non-breaking spaces into rank and emblem strings.</p>
     *
     * <p>Text with no section sign and no whitespace at all is returned without
     * copying; anything containing even a single space is rebuilt, because the
     * collapse pass has to rewrite that space. Do not treat the identity of the
     * returned instance as part of the contract.</p>
     *
     * <p>Inherits {@link #stripFormatting(String)}'s idempotence limit: a line
     * carrying a literal section sign is not guaranteed to be a fixed point, so
     * clean once rather than defensively at every layer.</p>
     *
     * @param in the raw string, may be null
     * @return the cleaned string, or null if {@code in} was null
     */
    public static String clean(String in) {
        if (in == null) {
            return null;
        }
        return WHITESPACE_RUN.matcher(stripFormatting(in)).replaceAll(" ").strip();
    }

    /** True for the colour, style and reset characters Mojang's legacy format defines. */
    private static boolean isLegacyCode(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o')
                || (c >= 'K' && c <= 'O')
                || c == 'r'
                || c == 'R';
    }

    /**
     * True when six {@code section + hex digit} pairs start at {@code from}, i.e.
     * the tail of the {@code section-x} RGB encoding.
     */
    private static boolean hasRgbTail(String in, int from) {
        if (from + RGB_TAIL_LENGTH > in.length()) {
            return false;
        }
        for (int i = from; i < from + RGB_TAIL_LENGTH; i += 2) {
            if (in.charAt(i) != SECTION || !isHexDigit(in.charAt(i + 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
