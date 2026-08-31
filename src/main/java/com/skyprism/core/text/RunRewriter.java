package com.skyprism.core.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Restyles selected ranges of a flattened run list without disturbing anything else.
 *
 * <p>This is the surgical alternative to rebuilding a chat line. Hypixel hangs hover
 * text and click actions off the components it sends -- a player name opens a profile,
 * an item shows its lore -- and those live on the style, not on the text. A mod that
 * recolours "[451]" by re-emitting the line as one fresh component destroys every one of
 * them. So instead the matched spans are cut out of the runs they sit in, their styles
 * are passed through the caller's restyler, and every other run comes back as the exact
 * same object it went in as.</p>
 *
 * <h2>What is guaranteed</h2>
 * <ul>
 *   <li>{@code RunText.flatten(result)} always equals {@code RunText.flatten(input)} --
 *       text is only ever split, never reordered, dropped, added or edited.</li>
 *   <li>The restyler is called once per <em>fragment</em>, so a span crossing three runs
 *       produces three calls, each given that fragment's own original style. Per-run
 *       attributes such as bold or a click event therefore survive a recolour.</li>
 *   <li>Runs no span touches are returned as the identical instance. So is a run a span
 *       covers entirely when the restyler hands back the very same style object.</li>
 *   <li>An empty span list is a no-op: the returned list is equal to the input and holds
 *       the same instances.</li>
 * </ul>
 *
 * <h2>Malformed spans are rejected, not ignored</h2>
 * <p>Every span problem throws {@link IllegalArgumentException}. Negative and inverted
 * ranges are caught by {@link Span} itself; this class additionally rejects spans running
 * past the end of the text, spans that overlap each other, and spans whose boundary would
 * fall between the two halves of a surrogate pair. Silently dropping a bad span would
 * turn a matcher bug into "the colour sometimes does not apply", which is far worse to
 * chase than an exception naming the offending range. Callers on a render path that
 * cannot tolerate a throw should validate their matcher output, not swallow the result.</p>
 *
 * <h2>Surrogate policy</h2>
 * <p>A span boundary landing <em>inside</em> a run between a high and a low surrogate is
 * rejected, because splitting there would hand half of an astral character (an emoji, say)
 * to one style and half to another and render as two replacement glyphs. A pair that the
 * <em>input runs</em> already straddle is left exactly as it is: the damage predates this
 * class, and merging runs to repair it would violate the flatten invariant.</p>
 *
 * <p>Span order in the input list does not matter; the rewriter sorts a private copy.
 * Zero-width spans are bounds-checked and then discarded, since they select no characters
 * and so cannot be overlapped, split at, or restyled.</p>
 */
public final class RunRewriter {

    private RunRewriter() {
    }

    /**
     * Applies {@code restyler} to exactly the characters covered by {@code spans}.
     *
     * @param runs     the input runs, not null, containing no nulls; never modified
     * @param spans    ranges over {@code RunText.flatten(runs)}, not null, containing no
     *                 nulls; may be unsorted, may be empty
     * @param restyler receives a fragment's original style and the whole span that
     *                 selected it (payload included) and returns the replacement style;
     *                 returning the argument unchanged is allowed and preserves instances
     * @param <S>      the style type, never inspected by this class
     * @return an unmodifiable list of runs whose flattened text equals the input's
     * @throws NullPointerException     if any argument or element is null
     * @throws IllegalArgumentException if a span ends past the text, two spans overlap, or
     *                                  a span boundary would split a surrogate pair
     */
    public static <S> List<StyledRun<S>> restyle(List<StyledRun<S>> runs,
                                                 List<Span> spans,
                                                 BiFunction<S, Span, S> restyler) {
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(spans, "spans");
        Objects.requireNonNull(restyler, "restyler");

        int total = RunText.length(runs);
        List<Span> active = new ArrayList<>(spans.size());
        for (Span span : spans) {
            Objects.requireNonNull(span, "span");
            if (span.end() > total) {
                throw new IllegalArgumentException(
                        "span [" + span.start() + ", " + span.end() + ") runs past text length " + total);
            }
            if (!span.isEmpty()) {
                active.add(span);
            }
        }
        if (active.isEmpty()) {
            return List.copyOf(runs);
        }
        active.sort(Comparator.comparingInt(Span::start));
        for (int i = 1; i < active.size(); i++) {
            Span previous = active.get(i - 1);
            Span current = active.get(i);
            if (current.start() < previous.end()) {
                throw new IllegalArgumentException("overlapping spans: [" + previous.start() + ", "
                        + previous.end() + ") and [" + current.start() + ", " + current.end() + ")");
            }
        }
        checkNoSurrogateSplit(runs, active);

        List<StyledRun<S>> out = new ArrayList<>(runs.size() + active.size() * 2);
        int offset = 0;
        int firstLive = 0;
        for (StyledRun<S> run : runs) {
            int length = run.length();
            int runEnd = offset + length;
            while (firstLive < active.size() && active.get(firstLive).end() <= offset) {
                firstLive++;
            }
            if (length == 0 || firstLive == active.size() || active.get(firstLive).start() >= runEnd) {
                out.add(run);
                offset = runEnd;
                continue;
            }
            String text = run.text();
            S style = run.style();
            int cursor = offset;
            int index = firstLive;
            while (cursor < runEnd) {
                Span span = index < active.size() ? active.get(index) : null;
                if (span == null || span.start() >= runEnd) {
                    out.add(new StyledRun<>(text.substring(cursor - offset, length), style));
                    break;
                }
                if (span.start() > cursor) {
                    out.add(new StyledRun<>(text.substring(cursor - offset, span.start() - offset), style));
                    cursor = span.start();
                }
                int fragmentEnd = Math.min(span.end(), runEnd);
                S restyled = restyler.apply(style, span);
                if (cursor == offset && fragmentEnd == runEnd) {
                    out.add(run.withStyle(restyled));
                } else {
                    out.add(new StyledRun<>(text.substring(cursor - offset, fragmentEnd - offset), restyled));
                }
                cursor = fragmentEnd;
                if (span.end() <= runEnd) {
                    index++;
                }
            }
            offset = runEnd;
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Rejects any span boundary that would cut a surrogate pair in half.
     *
     * <p>Only boundaries strictly inside a run can do damage: one at a run boundary cuts
     * where the input already cuts. Because the spans are sorted and disjoint, their
     * starts and ends form one ascending sequence, so a single merged walk over the runs
     * is enough.</p>
     */
    private static <S> void checkNoSurrogateSplit(List<StyledRun<S>> runs, List<Span> active) {
        int[] boundaries = new int[active.size() * 2];
        for (int i = 0; i < active.size(); i++) {
            boundaries[i * 2] = active.get(i).start();
            boundaries[i * 2 + 1] = active.get(i).end();
        }
        int next = 0;
        int offset = 0;
        for (StyledRun<S> run : runs) {
            int runEnd = offset + run.length();
            while (next < boundaries.length && boundaries[next] <= offset) {
                next++;
            }
            while (next < boundaries.length && boundaries[next] < runEnd) {
                int local = boundaries[next] - offset;
                if (Character.isHighSurrogate(run.text().charAt(local - 1))
                        && Character.isLowSurrogate(run.text().charAt(local))) {
                    throw new IllegalArgumentException(
                            "span boundary at " + boundaries[next] + " would split a surrogate pair");
                }
                next++;
            }
            offset = runEnd;
        }
    }
}
