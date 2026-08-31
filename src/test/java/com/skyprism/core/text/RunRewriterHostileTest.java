package com.skyprism.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adversarial second opinion on {@link RunRewriter}.
 *
 * <p>The module's own suite checks hand-picked shapes. This one checks the rewriter
 * against a deliberately dumb, independently written oracle -- label every character with
 * the span that owns it, then group -- over an <em>exhaustive</em> enumeration of small
 * inputs, plus the hostile edges the hand-picked cases skip: zero-width spans mixed with
 * real ones, spans handed over in every order, unpaired surrogates, restylers that return
 * null, mutable input lists, and a line far longer than any chat message.</p>
 *
 * <p>Exhaustive beats random here because the interesting bugs in a split-and-splice loop
 * live at coincidences -- a span that starts exactly where a run starts, that ends exactly
 * where the next one begins, with an empty run wedged between -- and those coincidences are
 * rare under a uniform generator but guaranteed under enumeration.</p>
 */
class RunRewriterHostileTest {

    private static final BiFunction<String, Span, String> MARK = (style, span) -> style + "#" + span.payload();
    private static final BiFunction<String, Span, String> KEEP = (style, span) -> style;

    private static StyledRun<String> run(String text, String style) {
        return new StyledRun<>(text, style);
    }

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

    // ------------------------------------------------------------------ the oracle

    /**
     * The obviously-correct implementation: paint every character with the index of the
     * span that owns it, then walk each run grouping equal labels. Quadratic and
     * allocation-happy, which is exactly why it is only ever a test.
     */
    private static List<StyledRun<String>> oracle(List<StyledRun<String>> runs,
                                                  List<Span> spans,
                                                  BiFunction<String, Span, String> restyler) {
        List<Span> sorted = new ArrayList<>();
        for (Span s : spans) {
            if (!s.isEmpty()) {
                sorted.add(s);
            }
        }
        sorted.sort(Comparator.comparingInt(Span::start));
        int total = RunText.length(runs);
        int[] owner = new int[total];
        Arrays.fill(owner, -1);
        for (int i = 0; i < sorted.size(); i++) {
            for (int c = sorted.get(i).start(); c < sorted.get(i).end(); c++) {
                owner[c] = i;
            }
        }
        List<StyledRun<String>> out = new ArrayList<>();
        int offset = 0;
        for (StyledRun<String> r : runs) {
            int length = r.length();
            if (length == 0) {
                out.add(r);
                continue;
            }
            int i = 0;
            while (i < length) {
                int label = owner[offset + i];
                int j = i + 1;
                while (j < length && owner[offset + j] == label) {
                    j++;
                }
                String style = label < 0 ? r.style() : restyler.apply(r.style(), sorted.get(label));
                if (i == 0 && j == length) {
                    out.add(label < 0 ? r : r.withStyle(style));
                } else {
                    out.add(new StyledRun<>(r.text().substring(i, j), style));
                }
                i = j;
            }
            offset += length;
        }
        return out;
    }

    /** How many restyler calls the oracle would make: one per covered fragment. */
    private static int oracleFragmentCalls(List<StyledRun<String>> runs, List<Span> spans) {
        int calls = 0;
        for (StyledRun<String> r : oracle(runs, spans, MARK)) {
            if (r.style() != null && r.style().indexOf('#') >= 0) {
                calls++;
            }
        }
        return calls;
    }

    /** Compares text, styles and instance identity against the oracle. */
    private static void agreesWithOracle(List<StyledRun<String>> runs,
                                         List<Span> spans,
                                         BiFunction<String, Span, String> restyler,
                                         String label) {
        List<StyledRun<String>> expected = oracle(runs, spans, restyler);
        List<StyledRun<String>> actual = RunRewriter.restyle(runs, spans, restyler);
        assertEquals(RunText.flatten(runs), RunText.flatten(actual), label + ": text changed");
        assertEquals(describe(expected), describe(actual), label + ": segmentation or styles differ");
        assertEquals(expected.size(), actual.size(), label + ": fragment count differs");
        for (int i = 0; i < expected.size(); i++) {
            for (StyledRun<String> source : runs) {
                if (expected.get(i) == source) {
                    assertSame(source, actual.get(i), label + ": run " + i + " should be the same instance");
                }
            }
        }
    }

    // ------------------------------------------------------- exhaustive enumeration

    @Test
    @DisplayName("exhaustive: every split of a 5-char line against every disjoint span set matches a dumb oracle")
    void exhaustiveAgreementWithOracle() {
        String text = "abcde";
        List<List<Span>> spanSets = allDisjointSpanSets(text.length());
        assertEquals(89, spanSets.size(), "enumeration of disjoint span sets over 5 chars");
        List<List<StyledRun<String>>> runLists = allRunSplits(text);
        assertEquals(324, runLists.size(), "enumeration of run splits with empty runs interleaved");

        int cases = 0;
        for (List<StyledRun<String>> runs : runLists) {
            for (List<Span> spans : spanSets) {
                agreesWithOracle(runs, spans, MARK, "MARK " + describe(runs) + " " + spans);
                agreesWithOracle(runs, spans, KEEP, "KEEP " + describe(runs) + " " + spans);
                cases++;
            }
        }
        assertEquals(324 * 89, cases, "every split crossed with every span set");
    }

    @Test
    @DisplayName("exhaustive: the restyler fires exactly once per covered fragment, never once more")
    void exhaustiveRestylerCallCount() {
        String text = "abcd";
        for (List<StyledRun<String>> runs : allRunSplits(text)) {
            for (List<Span> spans : allDisjointSpanSets(text.length())) {
                AtomicInteger calls = new AtomicInteger();
                RunRewriter.restyle(runs, spans, (style, span) -> {
                    calls.incrementAndGet();
                    return style + "#" + span.payload();
                });
                assertEquals(oracleFragmentCalls(runs, spans), calls.get(),
                        "wrong call count for " + describe(runs) + " " + spans);
            }
        }
    }

    @Test
    @DisplayName("exhaustive: span order in the input list never changes the result")
    void exhaustiveSpanOrderIrrelevant() {
        String text = "abcde";
        Random random = new Random(0xBADC0FFEL);
        for (List<StyledRun<String>> runs : allRunSplits(text)) {
            for (List<Span> spans : allDisjointSpanSets(text.length())) {
                String inOrder = describe(RunRewriter.restyle(runs, spans, MARK));
                List<Span> reversed = new ArrayList<>(spans);
                Collections.reverse(reversed);
                assertEquals(inOrder, describe(RunRewriter.restyle(runs, reversed, MARK)), "reversed");
                List<Span> shuffled = new ArrayList<>(spans);
                Collections.shuffle(shuffled, random);
                assertEquals(inOrder, describe(RunRewriter.restyle(runs, shuffled, MARK)), "shuffled");
            }
        }
    }

    /** Every set of disjoint, non-empty half-open intervals inside {@code [0, n)}. */
    private static List<List<Span>> allDisjointSpanSets(int n) {
        List<List<Span>> out = new ArrayList<>();
        collectSpanSets(n, 0, new ArrayList<>(), out);
        return out;
    }

    private static void collectSpanSets(int n, int from, List<Span> current, List<List<Span>> out) {
        if (from >= n) {
            out.add(List.copyOf(current));
            return;
        }
        collectSpanSets(n, from + 1, current, out); // leave `from` uncovered
        for (int end = from + 1; end <= n; end++) {
            current.add(new Span(from, end, current.size() + 1));
            collectSpanSets(n, end, current, out);
            current.remove(current.size() - 1);
        }
    }

    /** Every way to cut {@code text} into runs, with every combination of empty runs wedged between. */
    private static List<List<StyledRun<String>>> allRunSplits(String text) {
        List<List<StyledRun<String>>> out = new ArrayList<>();
        int cuts = text.length() - 1;
        for (int mask = 0; mask < (1 << cuts); mask++) {
            List<String> pieces = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < cuts; i++) {
                if ((mask & (1 << i)) != 0) {
                    pieces.add(text.substring(start, i + 1));
                    start = i + 1;
                }
            }
            pieces.add(text.substring(start));
            int gaps = pieces.size() + 1;
            for (int empties = 0; empties < (1 << gaps); empties++) {
                List<StyledRun<String>> runs = new ArrayList<>();
                int id = 0;
                for (int g = 0; g < gaps; g++) {
                    if ((empties & (1 << g)) != 0) {
                        runs.add(new StyledRun<>("", "e" + g));
                    }
                    if (g < pieces.size()) {
                        runs.add(new StyledRun<>(pieces.get(g), "s" + (id++)));
                    }
                }
                out.add(List.copyOf(runs));
            }
        }
        return out;
    }

    // --------------------------------------------------------- validator differential

    /**
     * The rewriter's own property sweep draws span boundaries only at code-point boundaries,
     * so it never once exercises the three validators. This one does the opposite: arbitrary
     * boundaries over text stuffed with paired <em>and</em> unpaired surrogates, with the
     * accept/reject decision predicted by a separate, obvious predicate.
     */
    @Test
    @DisplayName("property: the accept/reject decision matches an independent predicate over hostile unicode")
    void validatorAgreesWithIndependentPredicate() {
        Random random = new Random(0x5AFEC0DEL);
        int rejected = 0;
        int accepted = 0;
        int surrogateRejections = 0;
        for (int iteration = 0; iteration < 6000; iteration++) {
            List<StyledRun<String>> runs = hostileRuns(random);
            int total = RunText.length(runs);
            List<Span> spans = arbitrarySpans(random, total);
            boolean expectThrow = expectsRejection(runs, spans);
            String label = "iteration " + iteration + " runs=" + describe(runs) + " spans=" + spans;
            if (expectThrow) {
                rejected++;
                if (onlySurrogateFault(runs, spans)) {
                    surrogateRejections++;
                }
                assertThrows(IllegalArgumentException.class,
                        () -> RunRewriter.restyle(runs, spans, MARK), label);
            } else {
                accepted++;
                agreesWithOracle(runs, spans, MARK, label);
            }
        }
        assertTrue(rejected > 300, "only " + rejected + " rejections generated");
        assertTrue(accepted > 300, "only " + accepted + " acceptances generated");
        assertTrue(surrogateRejections > 50,
                "only " + surrogateRejections + " rejections were surrogate-only, so that branch is barely covered");
    }

    /** Runs over an alphabet of paired and deliberately unpaired surrogates, with empty runs mixed in. */
    private static List<StyledRun<String>> hostileRuns(Random random) {
        String[] alphabet = {"a", "b", "😀", "\uD83D", "\uDE00", "é"};
        int count = random.nextInt(5);
        List<StyledRun<String>> runs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder text = new StringBuilder();
            int pieces = random.nextInt(4);
            for (int p = 0; p < pieces; p++) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            runs.add(new StyledRun<>(text.toString(), "s" + i));
        }
        return List.copyOf(runs);
    }

    /** Spans with no regard for code points, run boundaries, overlap or the end of the text. */
    private static List<Span> arbitrarySpans(Random random, int total) {
        List<Span> spans = new ArrayList<>();
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            int a = random.nextInt(total + 2);
            int b = random.nextInt(total + 2);
            spans.add(new Span(Math.min(a, b), Math.max(a, b), i + 1));
        }
        return List.copyOf(spans);
    }

    /** True when the documented policy says {@code restyle} must throw. */
    private static boolean expectsRejection(List<StyledRun<String>> runs, List<Span> spans) {
        int total = RunText.length(runs);
        for (Span s : spans) {
            if (s.end() > total) {
                return true;
            }
        }
        return overlaps(spans) || splitsSurrogate(runs, spans);
    }

    /** True when the only thing wrong is a surrogate split, so that branch can be counted. */
    private static boolean onlySurrogateFault(List<StyledRun<String>> runs, List<Span> spans) {
        int total = RunText.length(runs);
        for (Span s : spans) {
            if (s.end() > total) {
                return false;
            }
        }
        return !overlaps(spans) && splitsSurrogate(runs, spans);
    }

    private static List<Span> nonEmptySorted(List<Span> spans) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingInt(Span::start));
        return out;
    }

    private static boolean overlaps(List<Span> spans) {
        List<Span> sorted = nonEmptySorted(spans);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start() < sorted.get(i - 1).end()) {
                return true;
            }
        }
        return false;
    }

    private static boolean splitsSurrogate(List<StyledRun<String>> runs, List<Span> spans) {
        String flat = RunText.flatten(runs);
        List<Integer> cuts = new ArrayList<>();
        int offset = 0;
        cuts.add(0);
        for (StyledRun<String> r : runs) {
            offset += r.length();
            cuts.add(offset);
        }
        for (Span s : nonEmptySorted(spans)) {
            for (int boundary : new int[] {s.start(), s.end()}) {
                if (cuts.contains(boundary) || boundary <= 0 || boundary >= flat.length()) {
                    continue;
                }
                if (Character.isHighSurrogate(flat.charAt(boundary - 1))
                        && Character.isLowSurrogate(flat.charAt(boundary))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------- hostile specifics

    @Test
    @DisplayName("zero-width spans wedged among real ones are dropped without disturbing them")
    void zeroWidthMixedWithRealSpans() {
        List<StyledRun<String>> runs = List.of(run("abc", "a"), run("def", "b"));
        List<Span> spans = Arrays.asList(
                new Span(3, 3, 99), new Span(1, 4, 1), new Span(0, 0, 98),
                new Span(6, 6, 97), new Span(5, 6, 2), new Span(2, 2, 96));
        var out = RunRewriter.restyle(runs, spans, MARK);
        assertEquals("abcdef", RunText.flatten(out));
        assertEquals("a=a|bc=a#1|d=b#1|e=b|f=b#2", describe(out));
    }

    @Test
    @DisplayName("a zero-width span inside a real span is discarded rather than reported as an overlap")
    void zeroWidthInsideRealSpanIsNotAnOverlap() {
        List<StyledRun<String>> runs = List.of(run("abcdef", "s"));
        var out = RunRewriter.restyle(runs, List.of(new Span(1, 5, 1), new Span(3, 3, 2)), MARK);
        assertEquals("a=s|bcde=s#1|f=s", describe(out));
    }

    @Test
    @DisplayName("a restyler returning null blanks the fragment instead of blowing up")
    void restylerMayReturnNull() {
        List<StyledRun<String>> runs = List.of(run("abcd", "s"));
        var out = RunRewriter.restyle(runs, List.of(new Span(1, 3, 0)), (style, span) -> null);
        assertEquals("a=s|bc=null|d=s", describe(out));
        assertEquals("abcd", RunText.flatten(out));
    }

    @Test
    @DisplayName("a null-styled run whose restyler also returns null keeps its instance")
    void nullToNullPreservesInstance() {
        StyledRun<String> only = run("abcd", null);
        var out = RunRewriter.restyle(List.of(only), List.of(new Span(0, 4, 0)), (style, span) -> null);
        assertSame(only, out.get(0));
    }

    @Test
    @DisplayName("an equal-but-distinct style still rebuilds the run, because identity is the contract")
    void equalButDistinctStyleRebuilds() {
        StyledRun<String> only = run("abcd", new String("gray"));
        var out = RunRewriter.restyle(List.of(only), List.of(new Span(0, 4, 0)),
                (style, span) -> new String("gray"));
        assertNotSame(only, out.get(0));
        assertEquals("gray", out.get(0).style());
    }

    @Test
    @DisplayName("neither input list is mutated, not even reordered, when spans arrive unsorted")
    void inputListsAreNeverMutated() {
        List<StyledRun<String>> runs = new ArrayList<>(List.of(run("abcdef", "s")));
        List<Span> spans = new LinkedList<>(List.of(new Span(4, 6, 2), new Span(0, 2, 1)));
        List<Span> spansBefore = List.copyOf(spans);
        List<StyledRun<String>> runsBefore = List.copyOf(runs);
        RunRewriter.restyle(runs, spans, MARK);
        assertEquals(spansBefore, spans, "the span list must not be sorted in place");
        assertEquals(runsBefore, runs, "the run list must not be touched");
    }

    @Test
    @DisplayName("unpaired surrogates are ordinary characters and never trip the surrogate guard")
    void unpairedSurrogatesAreOrdinary() {
        List<StyledRun<String>> runs = List.of(run("a\uD83Db\uDE00c", "s"));
        var out = RunRewriter.restyle(runs, List.of(new Span(1, 2, 1), new Span(3, 4, 2)), MARK);
        assertEquals("a\uD83Db\uDE00c", RunText.flatten(out));
        assertEquals("a=s|\uD83D=s#1|b=s|\uDE00=s#2|c=s", describe(out));
    }

    @Test
    @DisplayName("a boundary between two adjacent emoji is a code point boundary and stays legal")
    void boundaryBetweenTwoEmojiIsLegal() {
        List<StyledRun<String>> runs = List.of(run("😀😀", "s"));
        var out = RunRewriter.restyle(runs, List.of(new Span(0, 2, 1)), MARK);
        assertEquals("😀=s#1|😀=s", describe(out));
    }

    @Test
    @DisplayName("the surrogate guard fires on the second of two spans, not just the first")
    void surrogateGuardScansEverySpan() {
        List<StyledRun<String>> runs = List.of(run("ab😀cd", "s"));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(0, 2, 1), new Span(2, 3, 2)), MARK));
        assertTrue(thrown.getMessage().contains("surrogate"), thrown.getMessage());
    }

    @Test
    @DisplayName("Integer.MAX_VALUE bounds are rejected by the length check, not by an overflow")
    void extremeBoundsRejected() {
        List<StyledRun<String>> runs = List.of(run("abc", "s"));
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(0, Integer.MAX_VALUE, 0)), MARK));
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(Integer.MAX_VALUE, Integer.MAX_VALUE, 0)), MARK));
        assertThrows(IllegalArgumentException.class, () -> new Span(Integer.MIN_VALUE, 0, 0));
        assertEquals(Integer.MAX_VALUE, new Span(0, Integer.MAX_VALUE, 0).length());
    }

    @Test
    @DisplayName("a null run reaches the caller as an NPE even when there is nothing to restyle")
    void nullRunElementRejected() {
        List<StyledRun<String>> withNull = Arrays.asList(run("a", "s"), null);
        assertThrows(NullPointerException.class, () -> RunRewriter.restyle(withNull, List.of(), MARK));
        assertThrows(NullPointerException.class,
                () -> RunRewriter.restyle(withNull, List.of(new Span(0, 1, 0)), MARK));
    }

    @Test
    @DisplayName("the no-op result is unmodifiable too, not just the rewritten one")
    void noOpResultIsAlsoUnmodifiable() {
        var out = RunRewriter.restyle(List.of(run("abc", "s")), List.of(), MARK);
        assertThrows(UnsupportedOperationException.class, () -> out.add(run("x", "y")));
        var allEmptySpans = RunRewriter.restyle(List.of(run("abc", "s")), List.of(new Span(1, 1, 0)), MARK);
        assertThrows(UnsupportedOperationException.class, () -> allEmptySpans.add(run("x", "y")));
    }

    @Test
    @DisplayName("exhaustive: every small span set, overlapping or not, is accepted or refused correctly")
    void exhaustiveOverlapAndZeroWidthDecision() {
        String text = "abcde";
        List<Span> catalogue = new ArrayList<>();
        for (int start = 0; start <= text.length(); start++) {
            for (int end = start; end <= text.length(); end++) {
                catalogue.add(new Span(start, end, start * 10 + end));
            }
        }
        List<List<StyledRun<String>>> runLists = List.of(
                List.of(run("abcde", "s")),
                List.of(run("ab", "s"), run("cde", "t")),
                List.of(run("", "e0"), run("a", "s"), run("", "e1"), run("bcd", "t"), run("e", "u")));

        int checked = 0;
        int refusals = 0;
        for (int i = 0; i < catalogue.size(); i++) {
            for (int j = i; j < catalogue.size(); j++) {
                for (int k = j; k < catalogue.size(); k++) {
                    List<Span> spans = List.of(catalogue.get(i), catalogue.get(j), catalogue.get(k));
                    for (List<StyledRun<String>> runs : runLists) {
                        boolean expectThrow = expectsRejection(runs, spans);
                        if (expectThrow) {
                            refusals++;
                            assertThrows(IllegalArgumentException.class,
                                    () -> RunRewriter.restyle(runs, spans, MARK), spans.toString());
                        } else {
                            agreesWithOracle(runs, spans, MARK, spans.toString());
                        }
                        checked++;
                    }
                }
            }
        }
        assertEquals(1771 * runLists.size(), checked, "every unordered triple over every run split");
        assertTrue(refusals > 1_000, "only " + refusals + " refusals, so the overlap branch is under-exercised");
        assertTrue(checked - refusals > 200,
                "only " + (checked - refusals) + " acceptances, so the happy path is under-exercised");
    }

    @Test
    @DisplayName("a million-char line with 100k spans is rewritten correctly and does not go quadratic")
    void longLineStaysCorrect() {
        int runCount = 100_000;
        List<StyledRun<String>> runs = new ArrayList<>(runCount);
        for (int i = 0; i < runCount; i++) {
            runs.add(run("abcdefghij", "s" + (i % 7)));
        }
        List<Span> spans = new ArrayList<>(runCount - 1);
        for (int i = 0; i < runCount - 1; i++) {
            spans.add(new Span(i * 10 + 3, i * 10 + 12, i)); // straddles every run boundary
        }
        Collections.reverse(spans);

        long start = System.nanoTime();
        var out = RunRewriter.restyle(runs, spans, MARK);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(RunText.flatten(runs), RunText.flatten(out));
        List<StyledRun<String>> expected = oracle(runs, spans, MARK);
        assertEquals(expected.size(), out.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), out.get(i), "fragment " + i);
        }
        // A linear walk finishes in tens of milliseconds; rescanning the span list per run
        // would be 1e10 comparisons here, i.e. tens of seconds even on a quiet machine.
        assertTrue(millis < 15_000, "rewrite took " + millis + "ms, which smells quadratic");
    }

    @Test
    @DisplayName("a line made only of empty runs is returned untouched, instances and all")
    void allEmptyRuns() {
        StyledRun<String> a = run("", "a");
        StyledRun<String> b = run("", "b");
        List<StyledRun<String>> runs = List.of(a, b);
        var out = RunRewriter.restyle(runs, List.of(new Span(0, 0, 0)), MARK);
        assertEquals(2, out.size());
        assertSame(a, out.get(0));
        assertSame(b, out.get(1));
        assertThrows(IllegalArgumentException.class,
                () -> RunRewriter.restyle(runs, List.of(new Span(0, 1, 0)), MARK));
    }
}
