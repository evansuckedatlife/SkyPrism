package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Corpus tests for {@link LootParser}, built from the drop-line shapes recorded in that
 * class's javadoc. Section signs are {@code \u00A7} escapes so the file's encoding cannot
 * change what is asserted; the Magic Find icon is likewise escaped, since Hypixel has moved
 * it between a literal star and a private-use codepoint and the parser must not care.
 */
class LootParserTest {

    private static final String S = "\u00A7";
    /** The Magic Find icon Hypixel used before it moved to a resource-pack codepoint. */
    private static final String MF = "\u272F";

    private final LootParser parser = new LootParser();

    private LootDrop only(String line) {
        List<LootDrop> drops = parser.parse(line);
        assertEquals(1, drops.size(), "expected exactly one drop from: " + line);
        return drops.get(0);
    }

    // ------------------------------------------------- treasure burrow items

    @Test
    @DisplayName("the Griffin Feather treasure line yields the feather, not the sentence")
    void treasureFeather() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "9Griffin Feather" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Griffin Feather", "9", 1, true), only(line));
    }

    @Test
    @DisplayName("other treasure rewards parse the same way")
    void treasureOtherItems() {
        String fragment = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "9Mythos Fragment" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Mythos Fragment", "9", 1, true), only(fragment));

        String braided = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "5Braided Griffin Feather" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Braided Griffin Feather", "5", 1, true), only(braided));

        // "an" rather than "a" -- Hypixel picks the article from the item name.
        String antique = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out an "
                + S + "r" + S + "5Antique Remedies" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Antique Remedies", "5", 1, true), only(antique));
    }

    @Test
    @DisplayName("an uncoloured treasure reward still parses, with a null colour")
    void treasureWithoutColour() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "rMyth the Fish" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Myth the Fish", null, 1, true), only(line));
    }

    // ------------------------------------------------- treasure burrow coins

    @Test
    @DisplayName("coin payouts become a Coins drop whose count is the amount")
    void treasureCoins() {
        String small = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out "
                + S + "r" + S + "62,500 coins" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Coins", "6", 2500, false), only(small));

        String deific = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out "
                + S + "r" + S + "61,000,000 coins" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Coins", "6", 1_000_000, false), only(deific));
    }

    @Test
    @DisplayName("a coin payout is not flagged rare -- the server used 'Wow!', not a rare banner")
    void coinsAreNotRare() {
        String line = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out "
                + S + "r" + S + "625,000 coins" + S + "r" + S + "e!";
        assertEquals(false, only(line).rare());
    }

    // -------------------------------------------------------------- mob drops

    @Test
    @DisplayName("a mob drop with a magic find bracket keeps only the item name")
    void mobDropWithMagicFind() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Dwarf Turtle Shelmet "
                + S + "r" + S + "b(+" + S + "r" + S + "b168% " + S + "r" + S + "b" + MF
                + " Magic Find" + S + "r" + S + "b)";
        assertEquals(new LootDrop("Dwarf Turtle Shelmet", "9", 1, true), only(line));
    }

    @Test
    @DisplayName("the same drop without a magic find bracket parses identically")
    void mobDropWithoutMagicFind() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "5Crown of Greed";
        assertEquals(new LootDrop("Crown of Greed", "5", 1, true), only(line));
    }

    @Test
    @DisplayName("all five banner wordings Hypixel uses are accepted")
    void everyBannerWording() {
        assertEquals("Minos Relic",
                only(S + "6" + S + "lRARE DROP! " + S + "r" + S + "5Minos Relic").itemName());
        assertEquals("Daedalus Stick",
                only(S + "6" + S + "lVERY RARE DROP! " + S + "r" + S + "6Daedalus Stick").itemName());
        assertEquals("Shimmering Wool",
                only(S + "6" + S + "lCRAZY RARE DROP! " + S + "r" + S + "dShimmering Wool").itemName());
        assertEquals("Mythological Dye",
                only(S + "6" + S + "lINSANE DROP! " + S + "r" + S + "dMythological Dye").itemName());
        assertEquals("Griffin",
                only(S + "6" + S + "lPET DROP! " + S + "r" + S + "6Griffin").itemName());
    }

    @Test
    @DisplayName("a bare magic find percentage with no percent sign is still stripped")
    void magicFindWithoutPercentSign() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Crochet Tiger Plushie "
                + S + "r" + S + "b(+" + S + "r" + S + "b168 " + S + "r" + S + "b" + MF
                + " Magic Find" + S + "r" + S + "b)";
        assertEquals("Crochet Tiger Plushie", only(line).itemName());
    }

    @Test
    @DisplayName("a stacked drop splits its leading multiplier into the count")
    void stackedDrop() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "a3x Enchanted Ancient Claw";
        assertEquals(new LootDrop("Enchanted Ancient Claw", "a", 3, true), only(line));
    }

    // -------------------------------------------------------------- negatives

    @ParameterizedTest
    @ValueSource(strings = {
            "\u00A7b[MVP\u00A7r\u00A76+\u00A7r\u00A7b] Notch\u00A7f: \u00A7rRARE DROP! Crown of Greed",
            "\u00A79Party \u00A78> \u00A7bSteve\u00A7f: \u00A7rI just got a RARE DROP! Minos Relic",
            "\u00A7eYou dug out a Griffin Burrow! \u00A7r\u00A77(2/4)",
            "\u00A7c\u00A7lOh! \u00A7r\u00A7eYou dug out a \u00A7r\u00A7cMinos Inquisitor\u00A7r\u00A7e!",
            "\u00A7aYou are now in a party.",
            "RARE DROP! Crown of Greed",
            "",
    })
    @DisplayName("lines that are not drop announcements yield nothing")
    void nonDropLines(String line) {
        assertTrue(parser.parse(line).isEmpty(), "should not have parsed: " + line);
    }

    @Test
    @DisplayName("null yields an empty list rather than throwing out of a chat handler")
    void nullYieldsEmpty() {
        assertTrue(parser.parse(null).isEmpty());
    }

    /**
     * The original version of this test looped over {@code parser.parse(line)} and asserted
     * inside the loop, so it passed whenever the list came back empty -- including against a
     * {@code parse} that did nothing at all. It is spelled out positively now: the feather,
     * and only the feather.
     */
    @Test
    @DisplayName("a treasure line never leaks the sentence fragment 'You dug out a' as an item")
    void treasureNeverLeaksTheSentence() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "9Griffin Feather" + S + "r" + S + "e!";
        assertEquals(List.of(new LootDrop("Griffin Feather", "9", 1, true)), parser.parse(line));
    }

    /**
     * The treasure guard in {@code parse} only recognises a treasure line that ends exactly
     * on the closing {@code section-r section-e !}. These three carry a tail, or lose the
     * final punctuation, so before the {@code (?!You dug out)} lookahead was added to
     * BANNER_DROP each of them fell through to the mob-drop branch and produced a drop whose
     * item name was the English fragment "You dug out a".
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out a \u00A7r\u00A79Griffin Feather"
                    + "\u00A7r\u00A7e! \u00A7r\u00A7b(+\u00A7r\u00A7b15% Magic Find\u00A7r\u00A7b)",
            "\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out a \u00A7r\u00A79Griffin Feather\u00A7r\u00A7e",
            "\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out an \u00A7r\u00A75Antique Remedies\u00A7r\u00A7e! ",
    })
    @DisplayName("a treasure line with a tail yields nothing rather than an item called 'You dug out a'")
    void treasureVariantsNeverBecomeTheSentence(String line) {
        for (LootDrop drop : parser.parse(line)) {
            assertFalse(drop.itemName().startsWith("You dug out"),
                    "the mob-drop branch claimed a treasure sentence: " + drop);
        }
        assertTrue(parser.parse(line).isEmpty(),
                "an undecomposable treasure line is a missed reel, not a wrong one");
    }

    @Test
    @DisplayName("a coin line whose amount is punctuation is not a two-billion-coin payout")
    void malformedCoinAmountIsNotAPayout() {
        // "[\\d,]+" also matched a bare comma, parseAmount then failed on the empty string
        // and saturated, so this line used to announce 2,147,483,647 coins on the reel.
        String line = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out "
                + S + "r" + S + "6, coins" + S + "r" + S + "e!";
        assertTrue(parser.parse(line).isEmpty(), "a malformed amount means 'not a coin line'");
    }

    @Test
    @DisplayName("an amount too large for an int saturates rather than throwing")
    void absurdCoinAmountSaturates() {
        String line = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out "
                + S + "r" + S + "699,999,999,999,999,999,999 coins" + S + "r" + S + "e!";
        assertEquals(Integer.MAX_VALUE, only(line).count());
    }

    /**
     * A lazy item group followed by {@code \\s*} let the two share out a run of spaces every
     * possible way, which is quadratic in the length of the line. A 20,000-character line
     * took 2.3 seconds on the chat thread and a 200,000-character one never finished. The
     * bound below is two orders of magnitude above the fixed cost (about 5ms) and two above
     * the broken one, so it is neither flaky nor slow when it fails.
     */
    @Test
    @DisplayName("a pathological line parses in linear time, not quadratic")
    void longLineDoesNotBacktrackQuadratically() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "9" + " ".repeat(20_000) + "x" + S;
        long startNanos = System.nanoTime();
        assertEquals("x", only(line).itemName());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(elapsedMillis < 1_000L,
                "parsing one chat line took " + elapsedMillis + "ms; the item group is backtracking");
    }

    @Test
    @DisplayName("an embedded newline cannot weld a second line onto the item name")
    void embeddedNewlineYieldsNothing() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "5Crown of Greed\nSteve: hi";
        assertTrue(parser.parse(line).isEmpty(),
                "a component carrying a line break is not a drop announcement");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", "\n", "\u00A7", "\u00A7\u00A7\u00A7\u00A7"})
    @DisplayName("blank and code-only lines yield nothing")
    void blankLinesYieldNothing(String line) {
        assertTrue(parser.parse(line).isEmpty(), "should not have parsed: [" + line + "]");
    }

    @Test
    @DisplayName("a supplementary code point in an item name survives intact, not as half a surrogate pair")
    void supplementaryCodePointsSurvive() {
        String name = "Crown 👑 of Greed";
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "5" + name;
        assertEquals(name, only(line).itemName());
    }

    /**
     * The class javadoc promises one instance can be shared across threads. That is only
     * true while every matcher stays a local, so this would catch a future "cache the
     * Matcher in a field" optimisation.
     */
    @Test
    @DisplayName("one parser instance is safe to share across threads")
    void oneInstanceIsThreadSafe() throws Exception {
        String feather = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "9Griffin Feather" + S + "r" + S + "e!";
        String crown = S + "6" + S + "lRARE DROP! " + S + "r" + S + "5Crown of Greed";
        List<LootDrop> expectedFeather = List.of(new LootDrop("Griffin Feather", "9", 1, true));
        List<LootDrop> expectedCrown = List.of(new LootDrop("Crown of Greed", "5", 1, true));

        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> threads = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            boolean useFeather = t % 2 == 0;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        List<LootDrop> got = parser.parse(useFeather ? feather : crown);
                        if (!got.equals(useFeather ? expectedFeather : expectedCrown)) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(0, failures.get(), "shared parser produced a wrong result under concurrency");
    }

    @Test
    @DisplayName("the parser stays generic: a non-Diana rare drop still parses, gating is the caller's job")
    void foreignRareDropStillParses() {
        String judgement = S + "6" + S + "lRARE DROP! " + S + "r" + S + "5Judgement Core "
                + S + "r" + S + "b(+" + S + "r" + S + "b152% " + S + "r" + S + "b" + MF
                + " Magic Find" + S + "r" + S + "b)";
        assertEquals(new LootDrop("Judgement Core", "5", 1, true), only(judgement));
    }

    @Test
    @DisplayName("item names are whitespace-normalised so they can be matched against config")
    void namesAreNormalised() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "5  Crown of  Greed  ";
        assertEquals("Crown of Greed", only(line).itemName());
    }

    // ============================================================================
    // Shapes the cross-source review found missing. LootParser now feeds all 64 loot
    // sources, not just Diana, so each of these was a wrong answer on somebody's reel.
    // Section signs stay escapes; the Overbloom glyph is built from its code point for
    // the same reason MF is escaped -- Hypixel has already moved one private-use
    // codepoint once, and a literal here would rot with the file's encoding.
    // ============================================================================

    /** The Garden farming-fortune glyph that closes a pest drop's bonus bracket. */
    private static final String OVERBLOOM = String.valueOf((char) 0xE02B);

    // ------------------------------------------- defect 1: the bracketed shape

    @Test
    @DisplayName("a bracketed sack drop yields the item, never an item literally named '('")
    void bracketedDropIsNotAnOpenBracket() {
        String viscera = S + "b" + S + "lRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f"
                + S + "r" + S + "9Revenant Viscera" + S + "r" + S + "7) (+123% "
                + MF + " Magic Find)";
        LootDrop drop = only(viscera);
        assertFalse(drop.itemName().startsWith("("),
                "the plain banner shape claimed a bracketed line: " + drop);
        assertEquals(new LootDrop("Revenant Viscera", "9", 1, true), drop);
    }

    @Test
    @DisplayName("a bracketed drop carries its stack count inside the bracket")
    void bracketedDropWithCount() {
        String flesh = S + "b" + S + "lRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f"
                + S + "r" + S + "72x " + S + "r" + S + "f" + S + "r" + S + "9Foul Flesh"
                + S + "r" + S + "7) (+123% " + MF + " Magic Find)";
        assertEquals(new LootDrop("Foul Flesh", "9", 2, true), only(flesh));
    }

    @Test
    @DisplayName("a bracketed enchanted-book drop carries no colour run at all")
    void bracketedDropWithoutColour() {
        String book = S + "9" + S + "lVERY RARE DROP!  " + S + "r" + S + "7(" + S + "r" + S + "f"
                + "Mana Steal I" + S + "r" + S + "7) (+1% " + MF + " Magic Find)";
        assertEquals(new LootDrop("Mana Steal I", null, 1, true), only(book));
    }

    // ------------------------------------- defect 2: the two-space banner forms

    @Test
    @DisplayName("VERY RARE DROP! and CRAZY RARE DROP! are followed by two spaces")
    void twoSpaceBanners() {
        assertEquals("Revenant Catalyst",
                only(S + "5" + S + "lVERY RARE DROP!  " + S + "r" + S + "5Revenant Catalyst")
                        .itemName());
        assertEquals("Pocket Espresso Machine",
                only(S + "d" + S + "lCRAZY RARE DROP!  " + S + "r" + S + "dPocket Espresso Machine")
                        .itemName());
    }

    @Test
    @DisplayName("a two-space banner in the bracketed shape parses too")
    void twoSpaceBracketedBanner() {
        String catalyst = S + "5" + S + "lVERY RARE DROP!  " + S + "r" + S + "7(" + S + "r" + S + "f"
                + S + "r" + S + "5Revenant Catalyst" + S + "r" + S + "7) (+123% "
                + MF + " Magic Find)";
        assertEquals(new LootDrop("Revenant Catalyst", "5", 1, true), only(catalyst));
    }

    @Test
    @DisplayName("the one-space forms all still parse -- the second space is optional, not required")
    void oneSpaceBannersStillParse() {
        assertEquals("Daedalus Stick",
                only(S + "6" + S + "lVERY RARE DROP! " + S + "r" + S + "6Daedalus Stick").itemName());
        assertEquals("Shimmering Wool",
                only(S + "6" + S + "lCRAZY RARE DROP! " + S + "r" + S + "dShimmering Wool").itemName());
    }

    // ----------------------------- defect 3: the reset Hypixel does not always send

    @Test
    @DisplayName("a Garden pest drop parses although Hypixel sends no reset after the banner")
    void pestDropWithoutReset() {
        String line = S + "6" + S + "lRARE DROP! " + S + "9Mutant Nether Wart "
                + S + "e(" + S + "e+134" + OVERBLOOM + ")";
        assertEquals(new LootDrop("Mutant Nether Wart", "9", 1, true), only(line));
    }

    @Test
    @DisplayName("a pest pet drop with no reset parses the same way")
    void pestPetDropWithoutReset() {
        String line = S + "6" + S + "lPET DROP! " + S + "6Slug " + S + "e(" + S + "e+78"
                + OVERBLOOM + ")";
        assertEquals(new LootDrop("Slug", "6", 1, true), only(line));
    }

    // ------------------------------------- defect 4: the trailing xN count suffix

    @Test
    @DisplayName("a trailing dark-grey xN suffix becomes the count, not part of the discarded tail")
    void trailingCountSuffix() {
        String pest = S + "6" + S + "lRARE DROP! " + S + "9Mutant Nether Wart " + S + "8x9 "
                + S + "e(" + S + "e+134" + OVERBLOOM + ")";
        assertEquals(new LootDrop("Mutant Nether Wart", "9", 9, true), only(pest));

        String claw = S + "6" + S + "lRARE DROP! " + S + "r" + S + "aEnchanted Ancient Claw "
                + S + "8x16";
        assertEquals(new LootDrop("Enchanted Ancient Claw", "a", 16, true), only(claw));
    }

    @Test
    @DisplayName("a leading multiplier still wins over a trailing one rather than multiplying it")
    void leadingCountBeatsTrailingCount() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "a3x Enchanted Ancient Claw "
                + S + "8x16";
        assertEquals(new LootDrop("Enchanted Ancient Claw", "a", 3, true), only(line));
    }

    @Test
    @DisplayName("a malformed trailing count is discarded, not read as a saturated stack")
    void malformedTrailingCountIsIgnored() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "aEnchanted Ancient Claw "
                + S + "8x,";
        assertEquals(new LootDrop("Enchanted Ancient Claw", "a", 1, true), only(line));
    }

    // ------------------- the same class of bug, found by re-reading for it (INFERRED)

    @Test
    @DisplayName("a treasure item parses when the reset before its colour is absent")
    void treasureWithoutReset() {
        String line = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "9Griffin Feather" + S + "r" + S + "e!";
        assertEquals(new LootDrop("Griffin Feather", "9", 1, true), only(line));
    }

    @Test
    @DisplayName("a coin payout parses when the reset before its colour is absent")
    void treasureCoinsWithoutReset() {
        String line = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out " + S + "62,500 coins"
                + S + "r" + S + "e!";
        assertEquals(new LootDrop("Coins", "6", 2500, false), only(line));
    }

    // ------------------------------------------------------- anchoring must survive

    /**
     * Relaxing the reset is exactly the change that could have let unformatted player text
     * through, because the banner would then be reachable with no formatting code in front of
     * it at all. The leading run is one-or-more formatting codes rather than zero-or-more for
     * that reason, and these are what pin it: a player typing a banner into chat, in any of
     * the shapes above, must never spin anyone's machine.
     */
    @Test
    @DisplayName("a banner a player typed still yields nothing, in every relaxed shape")
    void playerTypedBannersStillYieldNothing() {
        String[] lines = {
            "RARE DROP! Crown of Greed",
            "VERY RARE DROP!  Crown of Greed",
            "CRAZY RARE DROP!  " + S + "r" + S + "dShimmering Wool",
            S + "9Party " + S + "8> " + S + "bSteve" + S + "f: " + S + "rVERY RARE DROP!  "
                    + S + "r" + S + "7(" + S + "r" + S + "f" + S + "r" + S + "5Sorrow"
                    + S + "r" + S + "7)",
            S + "b[MVP" + S + "r" + S + "6+" + S + "r" + S + "b] Notch" + S + "f: "
                    + S + "rRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f"
                    + S + "r" + S + "9Revenant Viscera" + S + "r" + S + "7)",
        };
        for (String line : lines) {
            assertTrue(parser.parse(line).isEmpty(), "should not have parsed: " + line);
        }
    }

    @Test
    @DisplayName("the bracketed shape stays linear on a pathological line too")
    void bracketedShapeDoesNotBacktrackQuadratically() {
        String line = S + "b" + S + "lRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f"
                + S + "r" + S + "9" + " ".repeat(20_000) + "x" + S + "r" + S + "7)";
        long startNanos = System.nanoTime();
        assertEquals("x", only(line).itemName());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(elapsedMillis < 1_000L,
                "parsing one bracketed line took " + elapsedMillis + "ms; the item group backtracks");
    }
}
