package com.skyprism.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for {@link RunRewriter}.
 *
 * <p>The style type is {@link String} here purely so a test can read a result at a
 * glance; the Minecraft adapter substitutes its own Style and gets the same behaviour.
 * Every case asserts the flatten invariant, because a rewriter that quietly loses or
 * duplicates a character would be a nightmare to notice in-game.</p>
 */
class RunRewriterTest {

    /** Marks a fragment with the span payload that selected it, keeping the run's own style visible. */
    private static final BiFunction<String, Span, String> MARK = (style, span) -> style + "#" + span.payload();

    /** Changes nothing, so instance-preservation can be observed. */
    private static final BiFunction<String, Span, String> KEEP = (style, span) -> style;

    private static StyledRun<String> run(String text, String style) {
        return new StyledRun<>(text, style);
    }

    /** Restyles and asserts the one invariant that must never break, returning the result. */
    private static List<StyledRun<String>> rewrite(List<StyledRun<String>> runs,
                                                   List<Span> spans,
                                                   BiFunction<String, Span, String> restyler) {
        List<StyledRun<String>> out = RunRewriter.restyle(runs, spans, restyler);
        assertEquals(RunText.flatten(runs), RunText.flatten(out), "flatten(before) must equal flatten(after)");
        return out;
    }

    /** A compact "text=style" rendering of a run list, so failures read as data rather than as objects. */
    private static String describe(List<StyledRun<String>> runs) {
        StringBuilder out = new StringBuilder();
        for (StyledRun<String> r : runs) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(r.text()).append('=').append(r.style());
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- basic shapes

    @Test
    @DisplayName("a span strictly inside one run splits it into three and restyles only the middle")
    void spanInsideOneRun() {
        List<StyledRun<String>> runs = List.of(run("ab[451]cd", "gray"));
        var out = rewrite(runs, List.of(new Span(2, 7, 451)), MARK);
        assertEquals("ab=gray|[451]=gray#451|cd=gray", describe(out));
    }

    @Test
    @DisplayName("a span exactly equal to a run restyles that run and splits nothing")
    void spanEqualToRun() {
        List<StyledRun<String>> runs = List.of(run("[451]", "gray"), run(" Player", "white"));
        var out = rewrite(runs, List.of(new Span(0, 5, 451)), MARK);
        assertEquals("[451]=gray#451| Player=white", describe(out));
        assertEquals(2, out.size());
        assertSame(runs.get(1), out.get(1), "the untouched run must be the same instance");
    }

    @Test
    @DisplayName("a span crossing three runs restyles each fragment against its OWN original style")
    void spanCrossingThreeRuns() {
        List<StyledRun<String>> runs = List.of(
                run("xx[4", "a"),
                run("5", "b"),
                run("1]yy", "c"));
        var out = rewrite(runs, List.of(new Span(2, 7, 451)), MARK);
        assertEquals("xx=a|[4=a#451|5=b#451|1]=c#451|yy=c", describe(out));
    }

    @Test
    @DisplayName("the restyler is invoked once per fragment, not once per span")
    void restylerCalledOncePerFragment() {
        List<StyledRun<String>> runs = List.of(run("abc", "a"), run("def", "b"), run("ghi", "c"));
        AtomicInteger calls = new AtomicInteger();
        rewrite(runs, List.of(new Span(1, 8, 7)), (style, span) -> {
            calls.incrementAndGet();
            return style + "!";
        });
        assertEquals(3, calls.get(), "one call per affected run fragment");
    }

    @Test
    @DisplayName("two disjoint spans inside a single run each get their own payload")
    void twoSpansInOneRun() {
        List<StyledRun<String>> runs = List.of(run("0123456789", "s"));
        var out = rewrite(runs, List.of(new Span(1, 3, 10), new Span(6, 8, 20)), MARK);
        assertEquals("0=s|12=s#10|345=s|67=s#20|89=s", describe(out));
    }

    @Test
    @DisplayName("adjacent spans stay two fragments and are not merged")
    void adjacentSpans() {
        List<StyledRun<String>> runs = List.of(run("abcdef", "s"));
        var out = rewrite(runs, List.of(new Span(1, 3, 1), new Span(3, 5, 2)), MARK);
        assertEquals("a=s|bc=s#1|de=s#2|f=s", describe(out));
    }

    @Test
    @DisplayName("spans may be handed over unsorted; the result is still in text order")
    void spansMayBeUnsorted() {
        List<StyledRun<String>> runs = List.of(run("abcdef", "s"));
        var out = rewrite(runs, List.of(new Span(4, 6, 2), new Span(0, 2, 1)), MARK);
        assertEquals("ab=s#1|cd=s|ef=s#2", describe(out));
    }

    @Test
    @DisplayName("a span anchored at index 0 needs no leading fragment")
    void spanAtIndexZero() {
        List<StyledRun<String>> runs = List.of(run("[9] Name", "s"));
        var out = rewrite(runs, List.of(new Span(0, 3, 9)), MARK);
        assertEquals("[9]=s#9| Name=s", describe(out));
    }

    @Test
    @DisplayName("a span touching the very last character needs no trailing fragment")
    void spanTouchingTheEnd() {
        List<StyledRun<String>> runs = List.of(run("Name ", "s"), run("[9]", "t"));
        var out = rewrite(runs, List.of(new Span(6, 8, 9)), MARK);
        assertEquals("Name =s|[=t|9]=t#9", describe(out));
    }

    @Test
    @DisplayName("a span covering the entire text restyles every run and drops nothing")
    void spanCoveringEverything() {
        List<StyledRun<String>> runs = List.of(run("ab", "a"), run("", "empty"), run("cd", "b"));
        var out = rewrite(runs, List.of(new Span(0, 4, 3)), MARK);
        assertEquals("ab=a#3|=empty|cd=b#3", describe(out));
    }

    // ---------------------------------------------------------------- degenerate inputs

    @Test
    @DisplayName("no spans is a no-op: an equal list holding the very same run instances")
    void emptySpansIsANoOp() {
        List<StyledRun<String>> runs = List.of(run("abc", "a"), run("def", "b"));
        var out = rewrite(runs, List.of(), MARK);
        assertEquals(runs, out);
        assertSame(runs.get(0), out.get(0));
        assertSame(runs.get(1), out.get(1));
    }

    @Test
    @DisplayName("a zero-width span selects nothing and never reaches the restyler")
    void zeroWidthSpanIsIgnored() {
        List<StyledRun<String>> runs = List.of(run("abc", "a"));
        AtomicInteger calls = new AtomicInteger();
        var out = rewrite(runs, List.of(new Span(2, 2, 5)), (style, span) -> {
            calls.incrementAndGet();
            return style + "!";
        });
        assertEquals(0, calls.get());
        assertEquals(runs, out);
        assertSame(runs.get(0), out.get(0), "an empty span must not even split the run");
    }

    @Test
    @DisplayName("a zero-width span at the very end of the text is legal, past it is not")
    void zeroWidthSpanBoundsAreStillChecked() {
        List<StyledRun<String>> runs = List.of(run("abc", "a"));
        rewrite(runs, List.of(new Span(3, 3, 0)), MARK);
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(4, 4, 0)), MARK));
    }

    @Test
    @DisplayName("empty runs in the input survive untouched, in place, as the same instances")
    void emptyRunsSurvive() {
        StyledRun<String> lead = run("", "lead");
        StyledRun<String> mid = run("", "mid");
        StyledRun<String> tail = run("", "tail");
        List<StyledRun<String>> runs = List.of(lead, run("abcd", "s"), mid, run("efgh", "t"), tail);
        var out = rewrite(runs, List.of(new Span(2, 6, 1)), MARK);
        assertEquals("=lead|ab=s|cd=s#1|=mid|ef=t#1|gh=t|=tail", describe(out));
        assertSame(lead, out.get(0));
        assertSame(mid, out.get(3));
        assertSame(tail, out.get(6));
    }

    @Test
    @DisplayName("an entirely empty run list accepts an empty span list")
    void emptyRunList() {
        assertEquals(List.of(), RunRewriter.restyle(List.<StyledRun<String>>of(), List.of(), MARK));
    }

    @Test
    @DisplayName("a run fully covered whose style the restyler leaves alone is returned unchanged")
    void unchangedStyleKeepsTheRunInstance() {
        StyledRun<String> tag = run("[451]", "gray");
        StyledRun<String> name = run(" Player", "white");
        List<StyledRun<String>> runs = List.of(tag, name);
        var out = rewrite(runs, List.of(new Span(0, 5, 451)), KEEP);
        assertSame(tag, out.get(0), "restyler returned the same style, so the run should not be rebuilt");
        assertSame(name, out.get(1));
    }

    @Test
    @DisplayName("a partially covered run is rebuilt even when the style is unchanged, because it must split")
    void partialCoverStillSplits() {
        StyledRun<String> only = run("abcd", "s");
        var out = rewrite(List.of(only), List.of(new Span(0, 2, 0)), KEEP);
        assertEquals(2, out.size());
        assertNotSame(only, out.get(0));
        assertEquals("ab=s|cd=s", describe(out));
    }

    @Test
    @DisplayName("a null style is carried through the restyler like any other style")
    void nullStyleIsCarried() {
        List<StyledRun<String>> runs = List.of(run("abcd", null));
        var out = rewrite(runs, List.of(new Span(1, 3, 2)), MARK);
        assertEquals("a=null|bc=null#2|d=null", describe(out));
    }

    @Test
    @DisplayName("the returned list is unmodifiable, so no caller can corrupt a rewritten line")
    void resultIsUnmodifiable() {
        var out = RunRewriter.restyle(List.of(run("abc", "s")), List.of(new Span(0, 1, 0)), MARK);
        assertThrows(UnsupportedOperationException.class, () -> out.add(run("x", "y")));
    }

    // ---------------------------------------------------------------- rejected input

    @Test
    @DisplayName("negative and inverted ranges are refused by Span itself")
    void spanRejectsNegativeAndInverted() {
        assertThrows(IllegalArgumentException.class, () -> new Span(-1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Span(5, 2, 0));
        assertEquals(3, Span.of(2, 5).length());
        assertTrue(Span.of(2, 2).isEmpty());
        assertEquals(0, Span.of(2, 5).payload());
    }

    @Test
    @DisplayName("a span running past the end of the text is rejected, not clamped")
    void outOfBoundsSpanRejected() {
        List<StyledRun<String>> runs = List.of(run("abc", "s"));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(1, 9, 0)), MARK));
        assertTrue(thrown.getMessage().contains("past text length 3"), thrown.getMessage());
    }

    @Test
    @DisplayName("overlapping spans are rejected, including a duplicate of the same range")
    void overlappingSpansRejected() {
        List<StyledRun<String>> runs = List.of(run("abcdefgh", "s"));
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(1, 5, 0), new Span(3, 7, 0)), MARK));
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(1, 5, 0), new Span(1, 5, 0)), MARK));
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(1, 5, 0), new Span(2, 3, 0)), MARK));
    }

    @Test
    @DisplayName("nulls anywhere are a programming error")
    void nullsRejected() {
        List<StyledRun<String>> runs = List.of(run("abc", "s"));
        assertThrows(NullPointerException.class, () -> RunRewriter.restyle(null, List.of(), MARK));
        assertThrows(NullPointerException.class, () -> RunRewriter.restyle(runs, null, MARK));
        assertThrows(NullPointerException.class, () -> RunRewriter.restyle(runs, List.of(), null));
        assertThrows(NullPointerException.class,
                () -> RunRewriter.restyle(runs, Arrays.asList(new Span(0, 1, 0), null), MARK));
    }

    // ---------------------------------------------------------------- unicode

    @Test
    @DisplayName("a span may sit either side of an astral character as long as it does not cut into it")
    void spanAroundSurrogatePair() {
        String emoji = "😀"; // U+1F600 GRINNING FACE, two chars
        List<StyledRun<String>> runs = List.of(run("ab" + emoji + "cd", "s"));
        var out = rewrite(runs, List.of(new Span(2, 4, 1)), MARK);
        assertEquals("ab=s|" + emoji + "=s#1|cd=s", describe(out));
        var head = rewrite(runs, List.of(new Span(0, 2, 1)), MARK);
        assertEquals("ab=s#1|" + emoji + "cd=s", describe(head));
    }

    @Test
    @DisplayName("a span boundary that would cut a surrogate pair in half is rejected")
    void surrogateSplitRejected() {
        String emoji = "😀";
        List<StyledRun<String>> runs = List.of(run("ab" + emoji + "cd", "s"));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(0, 3, 1)), MARK));
        assertTrue(thrown.getMessage().contains("surrogate"), thrown.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(3, 5, 1)), MARK));
    }

    @Test
    @DisplayName("a pair the input runs already straddle is left alone, not repaired")
    void preExistingStraddleIsPreserved() {
        List<StyledRun<String>> runs = List.of(run("a\uD83D", "s"), run("\uDE00b", "t"));
        var out = rewrite(runs, List.of(new Span(1, 3, 1)), MARK);
        assertEquals("a=s|\uD83D=s#1|\uDE00=t#1|b=t", describe(out));
    }

    @Test
    @DisplayName("combining marks and CJK text ride along without special handling")
    void otherUnicodeIsOrdinary() {
        List<StyledRun<String>> runs = List.of(run("é你好", "s"));
        var out = rewrite(runs, List.of(new Span(2, 4, 1)), MARK);
        assertEquals("é=s|你好=s#1", describe(out));
    }

    // ---------------------------------------------------------------- property test

    @Test
    @DisplayName("property: over 2000 seeded random cases the text is preserved and every char lands on the right style")
    void seededPropertySweep() {
        Random random = new Random(0x5C1B10CCL);
        int spansGenerated = 0;
        int crossRunSpans = 0;
        for (int iteration = 0; iteration < 2000; iteration++) {
            List<StyledRun<String>> runs = randomRuns(random);
            String flat = RunText.flatten(runs);
            List<Span> spans = randomSpans(random, flat);
            spansGenerated += spans.size();
            crossRunSpans += countCrossRun(runs, spans);

            List<StyledRun<String>> out = RunRewriter.restyle(runs, spans, MARK);

            assertEquals(flat, RunText.flatten(out), "iteration " + iteration + ": text changed");
            assertEquals(Arrays.asList(expectedStyles(runs, spans)), Arrays.asList(actualStyles(out)),
                    "iteration " + iteration + ": wrong style landed on some character");
            assertEquals(countEmpty(runs), countEmpty(out),
                    "iteration " + iteration + ": splitting must never invent an empty run");
        }
        // Guard against a generator that quietly degenerates into "no spans, nothing tested".
        assertTrue(spansGenerated > 2000, "sweep produced only " + spansGenerated + " spans");
        assertTrue(crossRunSpans > 200, "sweep produced only " + crossRunSpans + " spans crossing a run boundary");
    }

    /** How many of these spans straddle at least one run boundary, i.e. exercise multi-fragment restyling. */
    private static int countCrossRun(List<StyledRun<String>> runs, List<Span> spans) {
        int crossing = 0;
        for (Span span : spans) {
            int offset = 0;
            for (StyledRun<String> run : runs) {
                int runEnd = offset + run.length();
                if (span.start() < runEnd && runEnd < span.end()) {
                    crossing++;
                    break;
                }
                offset = runEnd;
            }
        }
        return crossing;
    }

    /** Between 0 and 5 runs of 0..6 chars, drawn from an alphabet that includes an astral character. */
    private static List<StyledRun<String>> randomRuns(Random random) {
        int count = random.nextInt(6);
        List<StyledRun<String>> runs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = random.nextInt(7);
            StringBuilder text = new StringBuilder();
            for (int c = 0; c < length; c++) {
                int pick = random.nextInt(6);
                text.append(pick == 5 ? "😀" : (char) ('a' + pick));
            }
            runs.add(new StyledRun<>(text.toString(), "s" + i));
        }
        return runs;
    }

    /**
     * Disjoint, ascending spans whose boundaries are always code-point boundaries of the
     * flattened text, so the surrogate rule is satisfied by construction and the sweep
     * exercises the splitting logic rather than the validator.
     */
    private static List<Span> randomSpans(Random random, String flat) {
        List<Integer> boundaries = new ArrayList<>();
        for (int i = 0; i <= flat.length(); i++) {
            if (i == flat.length() || i == 0 || !Character.isLowSurrogate(flat.charAt(i))) {
                boundaries.add(i);
            }
        }
        List<Span> spans = new ArrayList<>();
        int index = 0;
        int payload = 0;
        while (index < boundaries.size() - 1) {
            index += random.nextInt(3); // sometimes skip, leaving a gap
            if (index >= boundaries.size() - 1) {
                break;
            }
            int end = index + 1 + random.nextInt(Math.min(3, boundaries.size() - index - 1));
            spans.add(new Span(boundaries.get(index), boundaries.get(end), payload++));
            index = end;
        }
        java.util.Collections.shuffle(spans, random);
        return spans;
    }

    /** The style every character should end up with, derived independently of the rewriter. */
    private static String[] expectedStyles(List<StyledRun<String>> runs, List<Span> spans) {
        String[] styles = actualStyles(runs);
        for (Span span : spans) {
            for (int i = span.start(); i < span.end(); i++) {
                styles[i] = MARK.apply(styles[i], span);
            }
        }
        return styles;
    }

    /** Expands a run list into one style entry per character. */
    private static String[] actualStyles(List<StyledRun<String>> runs) {
        String[] styles = new String[RunText.length(runs)];
        int at = 0;
        for (StyledRun<String> run : runs) {
            for (int i = 0; i < run.length(); i++) {
                styles[at++] = run.style();
            }
        }
        return styles;
    }

    private static long countEmpty(List<StyledRun<String>> runs) {
        return runs.stream().filter(StyledRun::isEmpty).count();
    }
}
