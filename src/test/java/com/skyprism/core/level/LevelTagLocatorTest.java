package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mod's safety net. Recolouring the level prefix means rewriting text Hypixel
 * sent, so every wrong match is visible to every player in the lobby and every
 * missed match is a silently dead feature. The tables below are deliberately
 * over-large and split into an accept group and a reject group; any future field
 * report should be added here as a row before anything is changed in the locator.
 *
 * <p>All inputs are written the way callers deliver them -- already run through
 * {@code TextClean.stripFormatting} -- so no section signs appear. Non-ASCII
 * look-alikes are written as \\u escapes so the source encoding cannot change what
 * is being asserted.</p>
 */
class LevelTagLocatorTest {

    private final LevelTagLocator locator = LevelTagLocator.standard();

    // ----------------------------------------------------------------- accept

    /** input, expected start, expected end (exclusive), expected level. */
    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of("[451] Notch", 0, 5, 451),
                Arguments.of("[7] x", 0, 3, 7),
                Arguments.of("[451] Notch: hello", 0, 5, 451),
                Arguments.of("[0] FreshAccount", 0, 3, 0),
                Arguments.of("[1] Beginner", 0, 3, 1),
                Arguments.of("[40] FirstTierChange", 0, 4, 40),
                Arguments.of("[480] LastHypixelTier", 0, 5, 480),
                Arguments.of("[1000] AtSanityCeiling", 0, 6, 1000),
                Arguments.of("[451]", 0, 5, 451),
                Arguments.of("[451] [MVP+] Notch: hi", 0, 5, 451),
                Arguments.of("Party > [451] Notch: hi", 8, 13, 451),
                Arguments.of("Guild > [88] Herobrine", 8, 12, 88),
                Arguments.of("  [12] LeadingSpaces", 2, 6, 12),
                Arguments.of("\t[88] Tabbed", 1, 5, 88),
                Arguments.of("[42]: colon straight after the tag", 0, 4, 42),
                Arguments.of("[10]-dashed", 0, 4, 10),
                Arguments.of("(from) [37] Bob", 7, 11, 37),
                Arguments.of("To [301] Alex: gg", 3, 8, 301),
                Arguments.of("[451] Notch ✦", 0, 5, 451),
                Arguments.of("x [451] y", 2, 7, 451));
    }

    @ParameterizedTest(name = "[{index}] accepts {0}")
    @MethodSource("accepted")
    @DisplayName("real level prefixes are located with exact spans")
    void acceptsRealTags(String input, int start, int end, int level) {
        List<LevelTag> tags = locator.find(input);

        assertEquals(1, tags.size(), () -> "expected exactly one tag in " + input + " but got " + tags);
        LevelTag tag = tags.get(0);
        assertAll(
                () -> assertEquals(start, tag.start(), "start"),
                () -> assertEquals(end, tag.end(), "end"),
                () -> assertEquals(level, tag.level(), "level"),
                () -> assertEquals(start + 1, tag.digitsStart(), "digitsStart"),
                () -> assertEquals(end - 1, tag.digitsEnd(), "digitsEnd"),
                () -> assertEquals("[" + level + "]", tag.textIn(input), "tag text"),
                () -> assertEquals(String.valueOf(level),
                        input.substring(tag.digitsStart(), tag.digitsEnd()), "digit text"));
    }

    // ----------------------------------------------------------------- reject

    @ParameterizedTest(name = "[{index}] rejects {0}")
    @DisplayName("anything that is not a bracketed in-range level is left untouched")
    @ValueSource(strings = {
            // Rank tags: the most dangerous look-alikes, they sit right beside the level.
            "[MVP+] Notch: hi",
            "[MVP++] Notch: hi",
            "[MVP] Notch",
            "[VIP] Notch",
            "[VIP+] Notch",
            "[YOUTUBE] Notch",
            "[ADMIN] Notch",
            // Dungeon classes and mob level labels.
            "[Healer] Notch",
            "[Mage] Notch",
            "[Tank] Notch",
            "[Berserk] Notch",
            "[Lv100] Zombie",
            "[Lv1] Sheep",
            // Counters and decorated brackets.
            "[6/8] party members",
            "[3/5] Griffin Burrow",
            "[12✦] cosmetic",
            "[451✦]",
            "[]",
            "[ ]",
            // Malformed digit runs.
            "[ 451 ] Notch",
            "[ 451] Notch",
            "[451 ] Notch",
            "[451x] Notch",
            "[x451] Notch",
            "[4.51] Notch",
            "[4,51] Notch",
            "[-5] Notch",
            "[+5] Notch",
            // Leading zeros: Hypixel never pads, so padding means it is not our tag.
            "[0451] Notch",
            "[00] Notch",
            "[007] Notch",
            // Too wide to be a level, and wide enough to overflow a naive parse.
            "[99999999999] Notch",
            "[2147483648] Notch",
            // Outside the standard sanity range.
            "[1001] Notch",
            "[2000] Notch",
            "[123456] Notch",
            // Missing or mismatched brackets -- a bare number is never a tag.
            "451 Notch",
            "451] Notch",
            "[451 Notch",
            "]451[ Notch",
            "level 451 reached",
            // Glued into a word: not a prefix, so not ours.
            "x[451]y",
            "x[451] Notch",
            "[451]y Notch",
            "abc[7]def",
            // Non-ASCII digits must not sneak through: Arabic-Indic and fullwidth.
            "[٤٥١] Notch",
            "[４５１] Notch"
    })
    void rejectsLookAlikes(String input) {
        assertEquals(List.of(), locator.find(input), () -> "must not match: " + input);
    }

    // --------------------------------------------------------- multiple tags

    @Nested
    @DisplayName("multiple tags in one line")
    class MultipleTags {

        @Test
        @DisplayName("two speakers in one relayed line are both found, in order")
        void twoTagsInOrder() {
            String line = "[451] Notch -> [12] Steve";
            List<LevelTag> tags = locator.find(line);

            assertEquals(2, tags.size());
            assertEquals(451, tags.get(0).level());
            assertEquals(0, tags.get(0).start());
            assertEquals(12, tags.get(1).level());
            assertEquals(15, tags.get(1).start());
            assertEquals(19, tags.get(1).end());
        }

        @Test
        @DisplayName("adjacent tags [1][2] both match: ']' is neither a letter nor a digit")
        void adjacentTags() {
            List<LevelTag> tags = locator.find("[1][2]");

            assertEquals(2, tags.size());
            assertEquals(new LevelTag(0, 3, 1, 1, 2), tags.get(0));
            assertEquals(new LevelTag(3, 6, 2, 4, 5), tags.get(1));
        }

        @Test
        @DisplayName("an out-of-range candidate is skipped without hiding a later valid tag")
        void outOfRangeDoesNotStopTheScan() {
            List<LevelTag> tags = locator.find("[5000] fake [300] real");

            assertEquals(1, tags.size());
            assertEquals(300, tags.get(0).level());
            assertEquals(12, tags.get(0).start());
        }

        @Test
        @DisplayName("reported spans never overlap and are strictly increasing")
        void spansAreOrderedAndDisjoint() {
            List<LevelTag> tags = locator.find("[1] a [22] b [333] c [4] d");

            assertEquals(4, tags.size());
            for (int i = 1; i < tags.size(); i++) {
                assertTrue(tags.get(i - 1).end() <= tags.get(i).start(),
                        "tag " + i + " overlaps its predecessor");
            }
        }
    }

    // ------------------------------------------------------------ edge cases

    @Nested
    @DisplayName("edges and defensive behaviour")
    class Edges {

        @Test
        @DisplayName("null and too-short input yield an empty list, never null and never a throw")
        void nullAndShortInput() {
            assertEquals(List.of(), locator.find(null));
            assertEquals(List.of(), locator.find(""));
            assertEquals(List.of(), locator.find("["));
            assertEquals(List.of(), locator.find("[]"));
            assertEquals(List.of(), locator.find("[1"));
        }

        @Test
        @DisplayName("an absurd digit run does not throw NumberFormatException")
        void hugeDigitRunIsSafe() {
            String huge = "[" + "9".repeat(400) + "] Notch";
            assertEquals(List.of(), locator.find(huge));
            assertEquals(List.of(), locator.find("[99999999999]"));
        }

        @Test
        @DisplayName("the returned list rejects mutation by a caller")
        void returnedListIsImmutable() {
            List<LevelTag> tags = locator.find("[451] Notch");
            assertEquals(1, tags.size(), "guard: this must be the non-empty path, not List.of()");
            LevelTag tag = tags.get(0);
            assertAll(
                    () -> assertThrows(UnsupportedOperationException.class, tags::clear),
                    () -> assertThrows(UnsupportedOperationException.class, () -> tags.add(tag)),
                    () -> assertThrows(UnsupportedOperationException.class, () -> tags.set(0, tag)),
                    () -> assertThrows(UnsupportedOperationException.class, () -> tags.remove(0)));
        }

        @Test
        @DisplayName("repeated calls on one instance are independent and equal")
        void repeatedCallsAreIndependent() {
            String line = "[451] Notch -> [12] Steve";
            List<LevelTag> first = locator.find(line);
            List<LevelTag> second = locator.find(line);

            assertEquals(first, second);
            assertNotSame(first, second, "each call must build its own list");
            assertEquals(2, locator.find(line).size(), "a third call is unaffected by the first two");
        }

        @Test
        @DisplayName("toString names the configured range")
        void toStringShowsRange() {
            assertEquals("LevelTagLocator[0..1000]", locator.toString());
            assertEquals("LevelTagLocator[100..200]", new LevelTagLocator(100, 200).toString());
        }

        @Test
        @DisplayName("an empty result is the shared empty list, so a quiet chat line allocates nothing")
        void emptyResultIsShared() {
            assertSame(locator.find("hello world"), locator.find("nothing here"));
        }

        @Test
        @DisplayName("findFirst returns the leftmost tag, or null when there is none")
        void findFirstBehaviour() {
            LevelTag first = locator.findFirst("[451] Notch -> [12] Steve");
            assertNotNull(first);
            assertEquals(451, first.level());
            assertNull(locator.findFirst("[MVP+] Notch"));
            assertNull(locator.findFirst(null));
        }
    }

    // ---------------------------------------------------------- sanity range

    @Nested
    @DisplayName("sanity range")
    class Range {

        @Test
        @DisplayName("standard() is 0..1000, inclusive at both ends")
        void standardBounds() {
            assertEquals(0, locator.minLevel());
            assertEquals(1000, locator.maxLevel());
            assertEquals(1, locator.find("[0] a").size());
            assertEquals(1, locator.find("[1000] a").size());
            assertEquals(0, locator.find("[1001] a").size());
        }

        @Test
        @DisplayName("a narrowed locator filters by value, not by shape")
        void narrowedRange() {
            var narrow = new LevelTagLocator(100, 200);
            assertEquals(0, narrow.find("[99] a").size());
            assertEquals(1, narrow.find("[100] a").size());
            assertEquals(1, narrow.find("[200] a").size());
            assertEquals(0, narrow.find("[201] a").size());
        }

        @Test
        @DisplayName("an impossible range is rejected at construction")
        void badRangeRejected() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTagLocator(-1, 100)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTagLocator(500, 100)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new LevelTagLocator(Integer.MIN_VALUE, 0)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new LevelTagLocator(0, Integer.MIN_VALUE)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new LevelTagLocator(Integer.MAX_VALUE, 0)));
        }

        @Test
        @DisplayName("a degenerate one-value range is legal and pins a single level")
        void singleValueRange() {
            var pinned = new LevelTagLocator(451, 451);
            assertEquals(1, pinned.find("[450] a [451] b [452] c").size());
            assertEquals(451, pinned.findFirst("[450] a [451] b [452] c").level());
            assertEquals(0, pinned.find("[450] a").size());
        }

        /**
         * The brief calls out {@code [4511]}: it is well-formed and only the sanity range
         * decides its fate, so the same string must flip with the range and nothing else.
         */
        @Test
        @DisplayName("[4511] is shape-legal and range-rejected, and a wider locator accepts it")
        void rangeIsTheOnlyThingRejectingAWellFormedTag() {
            assertEquals(List.of(), locator.find("[4511] Notch"));
            assertEquals(4511, new LevelTagLocator(0, 10_000).findFirst("[4511] Notch").level());
        }

        @Test
        @DisplayName("Integer.MAX_VALUE as a ceiling is accepted and still width-capped")
        void maxIntCeiling() {
            var wide = new LevelTagLocator(0, Integer.MAX_VALUE);
            assertEquals(Integer.MAX_VALUE, wide.maxLevel());
            assertEquals(1, wide.find("[999999999] a").size());
            assertEquals(List.of(), wide.find("[2147483647] a"));
        }
    }

    // ------------------------------------------------------- hostile input

    /**
     * Deliberately abusive input. Chat text is attacker-controlled -- any player in the
     * lobby can type anything a Minecraft client will send -- so the locator has to be
     * total: no throw, no quadratic blow-up, and no match that violates its own
     * documented boundary rule.
     */
    @Nested
    @DisplayName("hostile input")
    class Hostile {

        /**
         * U+1D400 MATHEMATICAL BOLD CAPITAL A (category Lu) and U+1D7CE MATHEMATICAL
         * BOLD DIGIT ZERO (category Nd) are a letter and a digit that live outside the
         * BMP, so each occupies two {@code char}s. The boundary rule is written in terms
         * of characters-as-code-points, and the lookahead side already honours that, so
         * the lookbehind side must too or the rule is enforced on only one end of the tag.
         */
        @Test
        @DisplayName("a supplementary-plane letter or digit before the tag blocks the match")
        void supplementaryBoundaryBefore() {
            assertAll(
                    () -> assertEquals(List.of(), locator.find("𝐀[451] x"),
                            "supplementary letter before '['"),
                    () -> assertEquals(List.of(), locator.find("𝟎[451] x"),
                            "supplementary digit before '['"),
                    () -> assertEquals(List.of(), locator.find("hi 𝐀[7]"),
                            "supplementary letter before '[' mid-line"),
                    () -> assertEquals(List.of(), locator.find("[451]𝐀"),
                            "supplementary letter after ']' (already correct, kept as the mirror)"),
                    () -> assertEquals(List.of(), locator.find("[451]𝟎"),
                            "supplementary digit after ']'"));
        }

        /**
         * The fix for the case above must not become "reject anything supplementary":
         * a supplementary symbol is not a letter or a digit, so it stays a legal boundary.
         */
        @Test
        @DisplayName("a supplementary symbol is still a legal boundary on both sides")
        void supplementarySymbolIsStillABoundary() {
            assertEquals(1, locator.find("🎉[451] x").size(), "emoji before '['");
            assertEquals(1, locator.find("[451]🎉").size(), "emoji after ']'");
        }

        @Test
        @DisplayName("lone surrogates and other broken text do not throw")
        void brokenTextIsSurvivable() {
            assertAll(
                    () -> assertEquals(1, locator.find("\uD835[451]").size(), "lone high surrogate"),
                    () -> assertEquals(1, locator.find("\uDC00[451]").size(), "lone low surrogate"),
                    () -> assertEquals(List.of(), locator.find("𝐀𝐀")),
                    () -> assertEquals(List.of(), locator.find(" ")));
        }

        @Test
        @DisplayName("whitespace-only and blank input yield nothing")
        void whitespaceOnly() {
            assertAll(
                    () -> assertEquals(List.of(), locator.find("   ")),
                    () -> assertEquals(List.of(), locator.find("\t\t\t")),
                    () -> assertEquals(List.of(), locator.find("\n\n\n")),
                    () -> assertEquals(List.of(), locator.find("  ")));
        }

        @Test
        @DisplayName("line breaks are boundaries, so a multi-line blob still finds every tag")
        void lineBreaksAreBoundaries() {
            List<LevelTag> tags = locator.find("[451] Notch\n[12] Steve\r\n[7] Alex");

            assertEquals(3, tags.size());
            assertEquals(451, tags.get(0).level());
            assertEquals(12, tags.get(1).level());
            assertEquals(7, tags.get(2).level());
        }

        /**
         * 40 000 tags in one string. The point is not the wall clock -- timing assertions
         * are flaky on a shared machine -- but that a pathological line neither throws,
         * nor drops tags, nor takes so long that the test suite visibly stalls.
         */
        @Test
        @DisplayName("a very long line is scanned correctly end to end")
        void veryLongLine() {
            int repeats = 40_000;
            String line = "[451] Notch ".repeat(repeats);
            List<LevelTag> tags = locator.find(line);

            assertEquals(repeats, tags.size());
            assertEquals(0, tags.get(0).start());
            assertEquals(12 * (repeats - 1), tags.get(repeats - 1).start());
        }

        @Test
        @DisplayName("a 100 000-digit run inside brackets is rejected without throwing")
        void absurdDigitRun() {
            assertEquals(List.of(), locator.find("[" + "9".repeat(100_000) + "] Notch"));
        }

        /**
         * 50 000 nested brackets. Only the innermost pair is a tag -- every outer
         * {@code '['} is followed by another {@code '['} rather than a digit -- and the
         * regex must reach that conclusion without backtracking into next week.
         */
        @Test
        @DisplayName("deeply nested brackets yield exactly the innermost tag, fast")
        void deeplyNestedBrackets() {
            int depth = 50_000;
            List<LevelTag> tags = locator.find("[".repeat(depth) + "451" + "]".repeat(depth));

            assertEquals(1, tags.size());
            assertEquals(depth - 1, tags.get(0).start());
            assertEquals(451, tags.get(0).level());
        }

        /**
         * The locator is documented as immutable and shareable. Its only mutable state
         * would be a cached Matcher, which is exactly the mistake this test exists to
         * catch: a shared Matcher would produce torn or missing results under load.
         */
        @Test
        @DisplayName("one shared instance is safe to hammer from several threads")
        void sharedInstanceIsThreadSafe() throws Exception {
            String line = "[451] Notch -> [12] Steve -> [7] Alex";
            List<LevelTag> expected = locator.find(line);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int t = 0; t < 4; t++) {
                    futures.add(pool.submit(() -> {
                        for (int i = 0; i < 5_000; i++) {
                            if (!expected.equals(locator.find(line))) {
                                return false;
                            }
                        }
                        return true;
                    }));
                }
                for (var f : futures) {
                    assertTrue(f.get(), "a concurrent find() disagreed with the single-threaded result");
                }
            } finally {
                pool.shutdownNow();
            }
        }

        /**
         * Characterisation, not endorsement. The locator contracts for
         * already-formatting-stripped input; these rows record what actually happens if
         * an adapter forgets, so the failure mode is documented rather than discovered live.
         */
        @Test
        @DisplayName("a leftover section sign is not defended against -- documented, not endorsed")
        void sectionSignIsTheCallersProblem() {
            assertEquals(List.of(), locator.find("§a[451] Notch"),
                    "the colour letter itself happens to block the match");
            assertEquals(1, locator.find("§[451] Notch").size(),
                    "a bare section sign does NOT block it -- strip formatting before calling");
        }

        /**
         * Pins the boundary rule's deliberate looseness so that tightening it to
         * "whitespace or string edge only" is a conscious change with a failing test,
         * not an accident. '_' is the one that matters: Minecraft usernames contain it.
         */
        @Test
        @DisplayName("any non-letter non-digit is a boundary, including '_' and '.'")
        void punctuationIsABoundary() {
            assertAll(
                    () -> assertEquals(1, locator.find("Notch_[451]").size(), "underscore before"),
                    () -> assertEquals(1, locator.find(".[451]").size(), "dot before"),
                    () -> assertEquals(1, locator.find("[[451]]").size(), "brackets around"),
                    () -> assertEquals(1, locator.find("[451]:").size(), "colon after"),
                    () -> assertEquals(1, locator.find("[451]-").size(), "dash after"),
                    () -> assertEquals(1, locator.find("[451].").size(), "dot after"));
        }
    }

    // ------------------------------------------------------------ digit width

    /**
     * {@code MAX_DIGITS} is the whole reason {@link Integer#parseInt} is called without a
     * try/catch. If anyone widens it, these tests are what turns a silent
     * {@code NumberFormatException} in the middle of a chat render into a red build.
     */
    @Nested
    @DisplayName("digit-width ceiling")
    class DigitWidth {

        private final LevelTagLocator wide = new LevelTagLocator(0, Integer.MAX_VALUE);

        @Test
        @DisplayName("no digit run of any width can make find() throw")
        void noWidthThrows() {
            for (int width = 1; width <= 40; width++) {
                String line = "[" + "9".repeat(width) + "] x";
                int w = width;
                assertDoesNotThrow(() -> wide.find(line), () -> "width " + w);
            }
        }

        @Test
        @DisplayName("runs up to MAX_DIGITS match; one digit wider never does")
        void ceilingIsExactlyMaxDigits() {
            assertEquals(9, LevelTagLocator.MAX_DIGITS, "MAX_DIGITS moved; re-check the overflow proof");
            for (int width = 1; width <= LevelTagLocator.MAX_DIGITS; width++) {
                String digits = "9".repeat(width);
                List<LevelTag> tags = wide.find("[" + digits + "] x");
                assertEquals(1, tags.size(), () -> "width " + digits.length() + " should match");
                assertEquals(Integer.parseInt(digits), tags.get(0).level());
            }
            for (int width = LevelTagLocator.MAX_DIGITS + 1; width <= 20; width++) {
                String line = "[" + "9".repeat(width) + "] x";
                assertEquals(List.of(), wide.find(line), () -> "too wide: " + line);
            }
        }

        @Test
        @DisplayName("the widest accepted run is still inside int range")
        void widestRunFitsAnInt() {
            List<LevelTag> tags = wide.find("[999999999] x");
            assertEquals(1, tags.size());
            assertEquals(999_999_999, tags.get(0).level());
            assertTrue(tags.get(0).level() < Integer.MAX_VALUE);
            assertEquals(List.of(), wide.find("[2147483647] x"), "10 digits, rejected on width alone");
        }
    }

    // -------------------------------------------------------------- LevelTag

    @Nested
    @DisplayName("LevelTag invariants")
    class TagRecord {

        @Test
        @DisplayName("length covers the brackets and textIn echoes the match")
        void lengthAndText() {
            LevelTag tag = locator.findFirst("hi [451] Notch");
            assertEquals(5, tag.length());
            assertEquals("[451]", tag.textIn("hi [451] Notch"));
        }

        @Test
        @DisplayName("a digit span outside the tag span is a programming error")
        void inconsistentSpansRejected() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTag(0, 5, 451, 0, 4)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTag(0, 5, 451, 1, 5)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTag(-1, 5, 451, 1, 4)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTag(0, 5, -1, 1, 4)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTag(5, 3, 1, 6, 2)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new LevelTag(0, 5, 451, 3, 3)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new LevelTag(0, 5, Integer.MIN_VALUE, 1, 4)));
        }

        @Test
        @DisplayName("two tags found in the same place are equal and hash alike")
        void valueSemantics() {
            LevelTag a = locator.findFirst("[451] Notch");
            LevelTag b = new LevelTag(0, 5, 451, 1, 4);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, new LevelTag(0, 5, 452, 1, 4));
            assertNotEquals(a, locator.findFirst("hi [451] Notch"));
        }
    }
}
