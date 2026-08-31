package com.skyprism.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for {@link TextClean}. Section signs are written as \u00A7
 * escapes so no source-encoding setting can change what is being asserted.
 */
class TextCleanTest {
    private static final String S = "\u00A7";
    /** U+1F600 GRINNING FACE, a supplementary code point stored as a surrogate pair. */
    private static final String EMOJI = "\uD83D\uDE00";
    /** U+00A0 NO-BREAK SPACE, which Hypixel mixes into rank and emblem strings. */
    private static final String NBSP = "\u00A0";

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("text with no codes comes back equal, and stripping it again changes nothing")
    void plainTextIsUnchanged() {
        String plain = "Mining Speed 1,234";
        String stripped = TextClean.stripFormatting(plain);
        assertEquals(plain, stripped, "text without codes must survive intact");
        assertEquals(stripped, TextClean.stripFormatting(stripped), "stripping is idempotent");
        assertEquals("Mining Speed 1,234", TextClean.clean(plain));
        assertEquals(-1, stripped.indexOf(TextClean.SECTION), "no section sign can be introduced");
    }

    @Test
    @DisplayName("codes are stripped at the start, middle and end of a line")
    void codesAreStrippedAnywhere() {
        assertEquals("Enchanted Book", TextClean.stripFormatting(S + "9Enchanted Book"));
        assertEquals("Wither Cloak Sword", TextClean.stripFormatting("Wither " + S + "5Cloak Sword"));
        assertEquals("Combat 24", TextClean.stripFormatting("Combat 24" + S + "r"));
    }

    @Test
    @DisplayName("consecutive codes and bold+colour combos are stripped as pairs")
    void consecutiveCodesAreStripped() {
        assertEquals("SKYBLOCK", TextClean.stripFormatting(S + "l" + S + "6SKYBLOCK"));
        assertEquals("Legendary Dragon",
                TextClean.stripFormatting(S + "6" + S + "lLegendary " + S + "r" + S + "6Dragon"));
    }

    @Test
    @DisplayName("uppercase codes are codes too")
    void uppercaseCodesAreStripped() {
        assertEquals("RARE DROP", TextClean.stripFormatting(S + "6" + S + "LRARE DROP"));
        assertEquals("reset", TextClean.stripFormatting(S + "Rreset"));
    }

    @Test
    @DisplayName("a trailing lone section sign is not a code and is kept")
    void trailingLoneSectionSignIsKept() {
        assertEquals("Coins" + S, TextClean.stripFormatting("Coins" + S));
        assertEquals(S, TextClean.stripFormatting(S));
    }

    @Test
    @DisplayName("clean collapses the whitespace that stripping leaves behind")
    void cleanCollapsesWhitespace() {
        assertEquals("Bank Balance", TextClean.clean("  Bank" + S + "r" + S + "6   Balance  "));
        assertEquals("Zealot Kills 1 234", TextClean.clean("Zealot\tKills\n1 " + S + "a  234"));
    }

    @Test
    @DisplayName("empty and whitespace-only input")
    void emptyInput() {
        assertEquals("", TextClean.stripFormatting(""));
        assertEquals("", TextClean.clean(""));
        assertEquals("", TextClean.clean(S + "r   " + S + "8  "));
    }

    @Test
    @DisplayName("null in, null out")
    void nullInNullOut() {
        assertNull(TextClean.stripFormatting(null));
        assertNull(TextClean.clean(null));
    }

    @Test
    @DisplayName("a verbatim Hypixel Diana spawn line cleans to plain text")
    void realDianaSpawnLine() {
        String raw = S + "c" + S + "lOh! " + S + "r" + S + "eYou dug out a " + S + "5Minos Inquisitor"
                + S + "r" + S + "e!";
        assertEquals("Oh! You dug out a Minos Inquisitor!", TextClean.clean(raw));
    }

    @Test
    @DisplayName("a well-formed line is a fixed point: stripping and cleaning it again changes nothing")
    void strippingIsAFixedPointWhenNoSectionSignSurvives() {
        String[] realLines = {
            S + "6" + S + "lSKYBLOCK" + S + "r " + EMOJI,
            S + "c" + S + "lOh! " + S + "r" + S + "eYou dug out a " + S + "5Minos Inquisitor" + S + "r" + S + "e!",
            S + "eYou dug out a Griffin Burrow! " + S + "r" + S + "7(3/4)",
            S + "x" + S + "F" + S + "F" + S + "5" + S + "5" + S + "5" + S + "5" + "Legendary " + S + "r" + S + "6Dragon",
            "[451] Playername",
        };
        for (String raw : realLines) {
            String once = TextClean.stripFormatting(raw);
            assertEquals(-1, once.indexOf(TextClean.SECTION),
                    "this line was chosen because no section sign should survive it: " + once);
            assertEquals(once, TextClean.stripFormatting(once), "strip must be a fixed point for " + once);
            String cleaned = TextClean.clean(raw);
            assertEquals(cleaned, TextClean.clean(cleaned), "clean must be a fixed point for " + cleaned);
        }
    }

    @Test
    @DisplayName("a surviving literal section sign makes a SECOND strip lossy - a known, documented limit")
    void strippingIsNotIdempotentOnceALiteralSectionSignSurvives() {
        // First pass is correct and matches what the vanilla client renders: the leading section
        // sign is content (the char after it is not a code), then "SECTION r" is a real reset code.
        assertEquals(S + "F", TextClean.stripFormatting(S + S + "rF"));
        // Second pass is NOT a no-op, because "SECTION F" now reads as a colour code. This is not an
        // accident and it is not fixable without an escape a plain String has nowhere to put, so it
        // is pinned here: if anyone ever makes strip a true fixed point, this test must be revisited
        // deliberately rather than quietly deleted.
        assertEquals("", TextClean.stripFormatting(S + "F"));

        // The same trap through clean(), which is what a parser would actually call: cleaning once
        // keeps the "6", cleaning the already-clean result silently eats it.
        String once = TextClean.clean("Zealot " + S + S + "r6 Kills");
        assertEquals("Zealot " + S + "6 Kills", once);
        assertEquals("Zealot Kills", TextClean.clean(once), "the second clean is the lossy one");
    }

    /**
     * The three defects an independent review proved against the previous
     * "consume whatever follows a section sign" implementation. Every test here
     * fails on that implementation and passes on this one.
     */
    @Nested
    @DisplayName("regressions: the three proven defects")
    class ProvenDefects {

        @Test
        @DisplayName("defect 1: a section sign before an emoji must not eat its high surrogate")
        void surrogatePairIsNotSplit() {
            String in = "Loot: " + S + EMOJI;
            String out = TextClean.stripFormatting(in);

            // Old behaviour dropped the high surrogate and left a lone low surrogate.
            assertEquals("Loot: " + S + EMOJI, out, "a malformed code must not consume half a code point");
            assertEquals(1, out.codePoints().filter(Character::isSupplementaryCodePoint).count(),
                    "the emoji must survive as one whole code point");
            assertFalse(hasUnpairedSurrogate(out), "output must contain no unpaired surrogate");
        }

        @Test
        @DisplayName("defect 1b: a real code before an emoji is stripped and the emoji survives whole")
        void validCodeBeforeEmojiIsStripped() {
            String out = TextClean.stripFormatting(S + "a" + EMOJI + S + "r");
            assertEquals(EMOJI, out);
            assertFalse(hasUnpairedSurrogate(out));
        }

        @Test
        @DisplayName("defect 2: a section sign before a space must not destroy the word boundary")
        void whitespaceAfterALoneSectionSignSurvives() {
            // Old behaviour welded the two words together into "ab".
            assertEquals("a" + S + " b", TextClean.stripFormatting("a" + S + " b"));
            assertEquals("Zealot" + S + " Kills", TextClean.clean("Zealot" + S + " Kills"));
            assertEquals("Purse: " + S + "!1", TextClean.stripFormatting(S + "6Purse: " + S + "!1"));
        }

        @Test
        @DisplayName("defect 3: non-breaking spaces are collapsed and trimmed like any other space")
        void nonBreakingSpacesAreCollapsedAndTrimmed() {
            // Old behaviour left every NBSP exactly where it was, so literal comparison failed.
            assertEquals("Bank Balance", TextClean.clean(NBSP + "Bank" + NBSP + NBSP + "Balance" + NBSP));
            assertEquals("", TextClean.clean(NBSP + NBSP));
            assertEquals("Rare Drop", TextClean.clean(S + "6Rare" + NBSP + " " + S + "eDrop" + NBSP));
            assertEquals(-1, TextClean.clean(NBSP + "x" + NBSP).indexOf('\u00A0'),
                    "no non-breaking space may survive cleaning");
        }
    }

    @Nested
    @DisplayName("the six-code RGB form")
    class RgbForm {

        @Test
        @DisplayName("a complete RGB sequence is removed as one unit, hex digits and all")
        void completeRgbSequenceIsRemoved() {
            String in = S + "x" + S + "F" + S + "F" + S + "5" + S + "5" + S + "5" + S + "5" + "Legendary";
            assertEquals("Legendary", TextClean.stripFormatting(in));
        }

        @Test
        @DisplayName("the uppercase marker works and lowercase hex digits are accepted")
        void uppercaseMarkerAndLowercaseDigits() {
            String in = "a" + S + "X" + S + "a" + S + "a" + S + "0" + S + "0" + S + "a" + S + "a" + "b";
            assertEquals("ab", TextClean.stripFormatting(in));
        }

        @Test
        @DisplayName("a truncated RGB sequence is not a unit; the marker survives and the pairs fall back to legacy codes")
        void truncatedRgbSequenceKeepsTheMarker() {
            assertEquals(S + "x", TextClean.stripFormatting(S + "x" + S + "F" + S + "F"));
            assertEquals(S + "x", TextClean.stripFormatting(S + "x"));
        }

        @Test
        @DisplayName("the length boundary: a tail that ends exactly at the last char is still a unit, one char short is not")
        void rgbTailEndsExactlyAtTheEndOfTheString() {
            String full = S + "x" + S + "0" + S + "0" + S + "0" + S + "0" + S + "0" + S + "0";
            assertEquals(14, full.length(), "the marker plus six pairs is fourteen chars");
            assertEquals("", TextClean.stripFormatting(full), "a tail flush with the end of the string is complete");

            // One character short: the sixth pair is a bare section sign, so this is not a unit.
            // An off-by-one in the length guard would either read past the end or accept this.
            String short1 = full.substring(0, full.length() - 1);
            assertEquals(S + "x" + S, TextClean.stripFormatting(short1));
        }

        @Test
        @DisplayName("an RGB sequence followed by more text keeps that text")
        void rgbSequenceFollowedByText() {
            String in = S + "x" + S + "0" + S + "0" + S + "0" + S + "0" + S + "0" + S + "0" + " Sphinx" + S + "r";
            assertEquals(" Sphinx", TextClean.stripFormatting(in));
            assertEquals("Sphinx", TextClean.clean(in));
        }
    }

    @Nested
    @DisplayName("stripFormattingWithOffsets: the same strip, plus where each char came from")
    class Offsets {

        /** Every shape the stripper distinguishes, in one place, so the agreement test covers them all. */
        private static final String[] CORPUS = {
            "",
            "plain text",
            S,
            S + S,
            S + "r",
            S + "aRed" + S + "r",
            "lead" + S + "b" + "tail",
            S + "b[451]" + S + "f Zephyr",
            S + " space is not a code",
            S + EMOJI + " a section sign before an emoji is not a code",
            S + "x" + S + "f" + S + "f" + S + "0" + S + "0" + S + "f" + S + "f" + "RGB",
            S + "x" + S + "f" + S + "f" + S + "0" + S + "0" + S + "f" + S + "g" + "not a tail",
            S + "x" + S + "f" + S + "f" + S + "0" + S + "0" + S + "f",
            "trailing sign" + S,
            NBSP + S + "7  spaced  " + NBSP,
            EMOJI + S + "d" + EMOJI,
        };

        @Test
        @DisplayName("null in, null out, exactly like the string form")
        void nullIsNull() {
            assertNull(TextClean.stripFormattingWithOffsets(null));
        }

        @Test
        @DisplayName("the stripped text always equals stripFormatting's, so the rule has one owner")
        void agreesWithStripFormatting() {
            for (String in : CORPUS) {
                assertEquals(TextClean.stripFormatting(in),
                        TextClean.stripFormattingWithOffsets(in).stripped(),
                        "disagreed on [" + in + "]");
            }
        }

        @Test
        @DisplayName("the projection points at the very characters that were kept")
        void projectionRecoversEveryCharacter() {
            for (String in : CORPUS) {
                var r = TextClean.stripFormattingWithOffsets(in);
                assertEquals(r.stripped().length(), r.sourceIndex().length,
                        "one entry per surviving char, for [" + in + "]");
                for (int i = 0; i < r.length(); i++) {
                    assertEquals(r.stripped().charAt(i), in.charAt(r.sourceIndexOf(i)),
                            "char " + i + " of [" + in + "] came from somewhere else");
                }
            }
        }

        @Test
        @DisplayName("source indices strictly increase, because characters are copied in order")
        void indicesAreStrictlyIncreasing() {
            for (String in : CORPUS) {
                var r = TextClean.stripFormattingWithOffsets(in);
                for (int i = 1; i < r.length(); i++) {
                    assertTrue(r.sourceIndexOf(i) > r.sourceIndexOf(i - 1),
                            "index " + i + " of [" + in + "] did not advance");
                }
            }
        }

        @Test
        @DisplayName("text with no codes projects to the identity")
        void plainTextIsTheIdentity() {
            var r = TextClean.stripFormattingWithOffsets("Sphinx 451");
            assertEquals("Sphinx 451", r.stripped());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, r.sourceIndex());
        }

        @Test
        @DisplayName("an empty string has an empty projection rather than a null one")
        void emptyString() {
            var r = TextClean.stripFormattingWithOffsets("");
            assertEquals("", r.stripped());
            assertEquals(0, r.length());
            assertArrayEquals(new int[0], r.sourceIndex());
            assertEquals(0, r.sourceEndOf(0), "the empty range at the start maps to the start");
        }

        @Test
        @DisplayName("a leading code shifts everything after it by two")
        void leadingCodeShifts() {
            var r = TextClean.stripFormattingWithOffsets(S + "bab");
            assertEquals("ab", r.stripped());
            assertArrayEquals(new int[] {2, 3}, r.sourceIndex());
        }

        @Test
        @DisplayName("a lone section sign is content, so it keeps a slot in the projection")
        void loneSectionSignIsKept() {
            var r = TextClean.stripFormattingWithOffsets("a" + S + " b");
            assertEquals("a" + S + " b", r.stripped());
            assertArrayEquals(new int[] {0, 1, 2, 3}, r.sourceIndex());

            var trailing = TextClean.stripFormattingWithOffsets("end" + S);
            assertEquals("end" + S, trailing.stripped());
            assertEquals(3, trailing.sourceIndexOf(3), "a trailing sign is the last char of both");
        }

        @Test
        @DisplayName("a doubled section sign keeps the one the stripper could not pair")
        void doubledSectionSign() {
            // The first sign pairs with the second, which is not a code character, so
            // nothing is consumed there; the third pairs with 'r' and both go.
            var r = TextClean.stripFormattingWithOffsets(S + S + "r" + "x");
            assertEquals(S + "x", r.stripped());
            assertArrayEquals(new int[] {0, 3}, r.sourceIndex());
        }

        @Test
        @DisplayName("the six-code RGB form is dropped as one fourteen-char unit")
        void rgbFormIsOneUnit() {
            String rgb = S + "x" + S + "f" + S + "a" + S + "0" + S + "0" + S + "f" + S + "f";
            assertEquals(14, rgb.length());
            var r = TextClean.stripFormattingWithOffsets(rgb + "Zephyr");
            assertEquals("Zephyr", r.stripped());
            assertArrayEquals(new int[] {14, 15, 16, 17, 18, 19}, r.sourceIndex(),
                    "the hex digits never appear as text, and never as offsets either");
        }

        @Test
        @DisplayName("a malformed RGB tail is content, and every char of it is projected")
        void malformedRgbTailIsKept() {
            // Sixth pair is a bare 'g', so this is not the RGB form and only the leading
            // section-x is not a code either; all of it survives as text.
            String broken = S + "x" + S + "f" + S + "f" + S + "0" + S + "0" + S + "f" + S + "g";
            var r = TextClean.stripFormattingWithOffsets(broken);
            assertEquals(TextClean.stripFormatting(broken), r.stripped());
            for (int i = 0; i < r.length(); i++) {
                assertEquals(r.stripped().charAt(i), broken.charAt(r.sourceIndexOf(i)));
            }
        }

        @Test
        @DisplayName("a surrogate pair survives whole, both halves adjacent in the projection")
        void surrogatePairsStayIntact() {
            var r = TextClean.stripFormattingWithOffsets(S + "d" + EMOJI + S + "r!");
            assertEquals(EMOJI + "!", r.stripped());
            assertFalse(hasUnpairedSurrogate(r.stripped()));
            assertEquals(2, r.sourceIndexOf(0), "the high surrogate sits after the code");
            assertEquals(3, r.sourceIndexOf(1), "and its low half is the very next char");
            assertEquals(6, r.sourceIndexOf(2), "the '!' is past the reset code");
        }

        @Test
        @DisplayName("a section sign in front of an emoji is content, not a code that eats half of it")
        void sectionSignBeforeEmoji() {
            var r = TextClean.stripFormattingWithOffsets(S + EMOJI);
            assertEquals(S + EMOJI, r.stripped());
            assertArrayEquals(new int[] {0, 1, 2}, r.sourceIndex());
            assertFalse(hasUnpairedSurrogate(r.stripped()));
        }

        @Test
        @DisplayName("sourceEndOf maps a half-open range back onto the original substring")
        void sourceEndOfMapsARange() {
            String raw = "hi " + S + "b[451]" + S + "r rest";
            var r = TextClean.stripFormattingWithOffsets(raw);
            assertEquals("hi [451] rest", r.stripped());

            int from = r.stripped().indexOf('[');
            int to = r.stripped().indexOf(']') + 1;
            assertEquals("[451]", raw.substring(r.sourceIndexOf(from), r.sourceEndOf(to)),
                    "the mapped range covers the tag and nothing else");
        }

        @Test
        @DisplayName("a code sitting inside a range falls inside it; one just past it does not")
        void codeInsideVersusAfterARange() {
            String raw = "[4" + S + "e51]" + S + "r tail";
            var r = TextClean.stripFormattingWithOffsets(raw);
            assertEquals("[451] tail", r.stripped());

            int end = r.sourceEndOf(5);
            assertEquals("[4" + S + "e51]", raw.substring(0, end),
                    "the code between the digits is swallowed, which is invisible");
            assertTrue(raw.startsWith(S + "r", end), "the reset after the tag stays outside it");
        }

        @Test
        @DisplayName("sourceEndOf accepts both ends of the range and rejects anything past them")
        void sourceEndOfBounds() {
            var r = TextClean.stripFormattingWithOffsets("ab" + S + "c");
            assertEquals("ab", r.stripped());
            assertEquals(0, r.sourceEndOf(0));
            assertEquals(2, r.sourceEndOf(2), "the end of the stripped text, not of the original");
            assertThrows(IndexOutOfBoundsException.class, () -> r.sourceEndOf(3));
            assertThrows(IndexOutOfBoundsException.class, () -> r.sourceEndOf(-1));
        }

        @Test
        @DisplayName("an index outside the stripped text is refused rather than answered")
        void sourceIndexOfBounds() {
            var r = TextClean.stripFormattingWithOffsets("ab");
            assertThrows(IndexOutOfBoundsException.class, () -> r.sourceIndexOf(2));
            assertThrows(IndexOutOfBoundsException.class, () -> r.sourceIndexOf(-1));
        }

        @Test
        @DisplayName("the record refuses a projection that cannot describe its own string")
        void constructorChecksTheLengths() {
            assertThrows(IllegalArgumentException.class,
                    () -> new TextClean.StripResult("abc", new int[] {0, 1}));
            assertThrows(NullPointerException.class,
                    () -> new TextClean.StripResult(null, new int[0]));
            assertThrows(NullPointerException.class,
                    () -> new TextClean.StripResult("", null));
        }

        @Test
        @DisplayName("the array is copied in, so a caller cannot rewrite a result it already handed over")
        void constructorCopiesTheArray() {
            int[] mine = {0, 1, 2};
            var r = new TextClean.StripResult("abc", mine);
            mine[0] = 99;
            assertEquals(0, r.sourceIndexOf(0));
        }

        @Test
        @DisplayName("equality is by value, not by array identity")
        void equalsIsByValue() {
            var a = new TextClean.StripResult("ab", new int[] {0, 3});
            var b = new TextClean.StripResult("ab", new int[] {0, 3});
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, new TextClean.StripResult("ab", new int[] {0, 4}));
            assertNotEquals(a, new TextClean.StripResult("ac", new int[] {0, 3}));
        }

        @Test
        @DisplayName("the array handed back is the live one, so indexing it costs nothing")
        void accessorDoesNotCopy() {
            var r = TextClean.stripFormattingWithOffsets("abc");
            assertSame(r.sourceIndex(), r.sourceIndex());
        }
    }

}
