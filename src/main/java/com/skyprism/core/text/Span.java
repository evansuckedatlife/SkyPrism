package com.skyprism.core.text;

/**
 * A half-open range {@code [start, end)} of the <em>flattened</em> text, tagged with a
 * caller-defined integer.
 *
 * <p>Offsets are indices into {@link RunText#flatten(java.util.List)}, not into any one
 * run, so a matcher can be written against a plain string and stay completely unaware of
 * how the component tree happened to be chopped up. That is the whole point: Hypixel
 * splits {@code "[451] Player"} across a different number of components depending on
 * emblems, rank, and whether the message came from a party, and no matcher should have
 * to care.</p>
 *
 * <p>{@code payload} is opaque here. The level-prefix feature stores the parsed level in
 * it so the restyler can pick a gradient colour without re-parsing the digits; a feature
 * with nothing to carry uses {@link #of(int, int)} and gets zero.</p>
 *
 * <p>Inverted and negative ranges are rejected at construction rather than ignored later:
 * they can only come from arithmetic that has already gone wrong, and failing at the
 * source points at the matcher instead of at the rewriter.</p>
 *
 * @param start   first included index, {@code >= 0}
 * @param end     first excluded index, {@code >= start}; {@code end == start} is a legal
 *                zero-width span that the rewriter treats as a no-op
 * @param payload arbitrary caller data handed back to the restyler untouched
 */
public record Span(int start, int end, int payload) {

    public Span {
        if (start < 0) {
            throw new IllegalArgumentException("span start must be >= 0 but was " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException("inverted span: [" + start + ", " + end + ")");
        }
    }

    /**
     * A span with no payload.
     *
     * @param start first included index
     * @param end   first excluded index
     * @return the span, payload 0
     */
    public static Span of(int start, int end) {
        return new Span(start, end, 0);
    }

    /** @return how many {@code char}s the span covers */
    public int length() {
        return end - start;
    }

    /** @return true when the span covers no characters at all */
    public boolean isEmpty() {
        return end == start;
    }
}
