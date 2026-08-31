package com.skyprism.core.loot.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The universal banner, held to the real lines it exists to fix.
 *
 * <p>Three of the cases below are regression tests for defects that were <em>confirmed by running
 * the shipped Diana pattern against real captured Hypixel lines</em>, not deduced: the bracketed
 * shape yielding an item named "(", the two-space {@code VERY RARE} variants not matching at all,
 * and the missing reset on Garden pest drops. Each is the kind of bug that produces a working-looking
 * feature, so each gets an assertion that names the wrong answer as well as the right one.
 *
 * <p>Section signs are {@code \u00A7} escapes so the file's encoding cannot change what is tested.
 */
@DisplayName("RareDropBanner: every shape Hypixel actually sends, and nothing else")
class RareDropBannerTest {

    @Nested
    @DisplayName("the plain shape")
    class Plain {

        @Test
        @DisplayName("an ordinary rare drop with a magic-find tail")
        void ordinaryDrop() {
            RareDropBanner.Banner b = require(
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Judgement Core \u00A7r\u00A7b(+\u00A7r\u00A7b168% \u00A7r\u00A7b✯ Magic Find\u00A7r\u00A7b)");
            assertEquals("RARE DROP!", b.banner());
            assertEquals("Judgement Core", b.item());
            assertEquals("9", b.color());
            assertEquals(1, b.count());
            assertFalse(b.pet());
            assertTrue(b.rare());
        }

        @Test
        @DisplayName("a pet drop, with and without a tail")
        void petDrops() {
            RareDropBanner.Banner withTail = require(
                    "\u00A76\u00A7lPET DROP! \u00A7r\u00A75Baby Yeti \u00A7r\u00A7b(+\u00A7r\u00A7b168% \u00A7r\u00A7b✯ Magic Find\u00A7r\u00A7b)");
            assertEquals("Baby Yeti", withTail.item());
            assertEquals("5", withTail.color());
            assertTrue(withTail.pet());

            RareDropBanner.Banner bare = require("\u00A76\u00A7lPET DROP! \u00A7r\u00A76Rat");
            assertEquals("Rat", bare.item());
            assertEquals("6", bare.color());
            assertTrue(bare.pet());
        }

        @Test
        @DisplayName("the Garden pest shape: no reset after the banner, count as a trailing run")
        void gardenPestShape() {
            // The shipped Diana pattern requires a section-r immediately after the banner and reads
            // counts only as a leading "Nx ", so this real line matched nothing at all.
            RareDropBanner.Banner b = require("\u00A76\u00A7lRARE DROP! \u00A79Mutant Nether Wart \u00A78x9 \u00A7e(\u00A7e+134)");
            assertEquals("Mutant Nether Wart", b.item());
            assertEquals("9", b.color());
            assertEquals(9, b.count());
        }

        @Test
        @DisplayName("a leading multiplier is split off the name")
        void leadingCount() {
            RareDropBanner.Banner b = require("\u00A76\u00A7lRARE DROP! \u00A7r\u00A793x Enchanted Ancient Claw");
            assertEquals("Enchanted Ancient Claw", b.item());
            assertEquals(3, b.count());
        }

        @Test
        @DisplayName("an item name split across a same-colour reset survives whole")
        void nameCrossesSameColourReset() {
            RareDropBanner.Banner b = require(
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Arachne's \u00A7r\u00A79Keeper Fragment \u00A7r\u00A7b(+123% ✯ Magic Find)");
            assertEquals("Arachne's Keeper Fragment", b.item());
        }
    }

    @Nested
    @DisplayName("the bracketed shape, which the shipped pattern silently corrupts")
    class Bracketed {

        @Test
        @DisplayName("a sackable slayer drop is the item, not an open bracket")
        void sackDropIsNotAnOpenBracket() {
            RareDropBanner.Banner b = require(
                    "\u00A7b\u00A7lRARE DROP! \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A79Revenant Viscera\u00A7r\u00A77) (+123% ✯ Magic Find)");
            assertEquals("Revenant Viscera", b.item());
            assertEquals("9", b.color());
            assertEquals(1, b.count());
        }

        @Test
        @DisplayName("VERY RARE and CRAZY RARE are followed by TWO spaces and must still match")
        void doubleSpaceTiers() {
            RareDropBanner.Banner veryRare = require(
                    "\u00A75\u00A7lVERY RARE DROP!  \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A75Revenant Catalyst\u00A7r\u00A77) (+123% ✯ Magic Find)");
            assertEquals("VERY RARE DROP!", veryRare.banner());
            assertEquals("Revenant Catalyst", veryRare.item());

            RareDropBanner.Banner crazy = require(
                    "\u00A7d\u00A7lCRAZY RARE DROP!  \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A7fPocket Espresso Machine\u00A7r\u00A77) (+1% ✯ Magic Find)");
            assertEquals("CRAZY RARE DROP!", crazy.banner());
            assertEquals("Pocket Espresso Machine", crazy.item());
        }

        @Test
        @DisplayName("a stacked sack drop yields the count and the bare name")
        void stackedSackDrop() {
            RareDropBanner.Banner b = require(
                    "\u00A7b\u00A7lRARE DROP! \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A772x \u00A7r\u00A7f\u00A7r\u00A79Foul Flesh\u00A7r\u00A77) (+123% ✯ Magic Find)");
            assertEquals("Foul Flesh", b.item());
            assertEquals(2, b.count());
            assertEquals("9", b.color());
        }

        @Test
        @DisplayName("an enchanted book arrives with no colour at all and must not be dropped")
        void uncolouredBracketedDrop() {
            RareDropBanner.Banner b = require(
                    "\u00A79\u00A7lVERY RARE DROP!  \u00A7r\u00A77(\u00A7r\u00A7fMana Steal I\u00A7r\u00A77) (+1% ✯ Magic Find)");
            assertEquals("Mana Steal I", b.item());
            assertNull(b.color(), "no colour run is present, so none should be invented");
        }
    }

    @Nested
    @DisplayName("what must never match")
    class Rejections {

        @Test
        @DisplayName("a player quoting a banner in chat cannot spin anybody's machine")
        void playerAuthoredLines() {
            assertNoMatch("\u00A7b[MVP\u00A7r\u00A76+\u00A7r\u00A7b] Notch\u00A7f: \u00A7rRARE DROP! Crown of Greed");
            assertNoMatch("\u00A79Party \u00A78> \u00A7bSteve\u00A7f: \u00A7rI just got a RARE DROP! Minos Relic");
            assertNoMatch("\u00A79Party \u00A78> \u00A7bSteve\u00A7f: \u00A7rVERY RARE DROP!  \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A75Sorrow\u00A7r\u00A77)");
        }

        @Test
        @DisplayName("somebody else's drop is a third-person sentence and is rejected")
        void thirdPartyDrops() {
            assertNoMatch("\u00A7aSteve \u00A7r\u00A7ehas obtained \u00A7r\u00A7a\u00A7r\u00A79Judgement Core\u00A7r\u00A7e!");
            assertNoMatch("\u00A76\u00A7lRARE REWARD! \u00A7r\u00A7bLeebys \u00A7r\u00A7efound a \u00A7r\u00A76Recombobulator 3000 "
                    + "\u00A7r\u00A7ein their Obsidian Chest\u00A7r\u00A7e!");
            assertNoMatch("\u00A7c\u00A7lBONUS LOOT! \u00A7r\u00A7eThey also received \u00A7r\u00A7817x \u00A7r\u00A75Wise Dragon Fragment "
                    + "\u00A7r\u00A7efrom their sacrifice!");
            assertTrue(RareDropBanner.isThirdPartyLine("\u00A7aSteve \u00A7r\u00A7ehas obtained \u00A7r\u00A79Sorrow\u00A7r\u00A7e!"));
        }

        @Test
        @DisplayName("a Diana treasure payout belongs to the shipped path, not to this parser")
        void dianaTreasureIsNotABanner() {
            assertNoMatch("\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out a \u00A7r\u00A79Griffin Feather\u00A7r\u00A7e!");
            assertNoMatch("\u00A76\u00A7lWow! \u00A7r\u00A7eYou dug out \u00A7r\u00A762,500 coins\u00A7r\u00A7e!");
            // Even with an unexpected tail, which is the case the guard exists for: without the
            // lookahead this would report an item literally named "You dug out a".
            assertNoMatch("\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out a \u00A7r\u00A79Griffin Feather\u00A7r\u00A7e! \u00A7r\u00A7b(+123%)");
        }

        @Test
        @DisplayName("other sources' banners are not in this family")
        void otherBannerWords() {
            assertNoMatch("\u00A76 \u00A7r\u00A76\u00A7lTROPHY FISH! \u00A7r\u00A7fYou caught a \u00A7r\u00A79Lavahorse \u00A7r\u00A76\u00A7lGOLD\u00A7r\u00A7f!");
            assertNoMatch("\u00A79\u00A7lRARE! \u00A7r\u00A75Snow Suit Helmet \u00A7r\u00A7egift with \u00A7r\u00A7aGrazma\u00A7r\u00A7e!");
            assertNoMatch("\u00A7d\u00A7lHOPPITY'S HUNT \u00A7r\u00A7dYou found a \u00A7r\u00A79Chocolate Lunch Egg \u00A7r\u00A7dhere\u00A7r\u00A7d!");
            assertNoMatch("FROZEN TREASURE! You found Glacial Talisman!");
            assertNoMatch("\u00A76\u00A7lRARE CROP! \u00A7aCane Knot \u00A7e(\u00A7e+139.5)");
        }

        @Test
        @DisplayName("null, empty and junk are non-events, not exceptions")
        void degenerateInput() {
            assertNoMatch(null);
            assertNoMatch("");
            assertNoMatch("RARE DROP!");
            assertFalse(RareDropBanner.isThirdPartyLine(null));
        }

        @Test
        @DisplayName("a very long line does not take the chat thread down")
        void longLineIsLinear() {
            // The possessive item run is what makes this deterministic; a lazy run plus a trailing
            // whitespace group was quadratic and took seconds on a 20k-character line.
            String junk = "a ".repeat(20_000);
            long start = System.nanoTime();
            RareDropBanner.match("\u00A76\u00A7lRARE DROP! \u00A7r\u00A79" + junk);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMillis < 500L, "matching took " + elapsedMillis + "ms");
        }
    }

    private static RareDropBanner.Banner require(String line) {
        Optional<RareDropBanner.Banner> banner = RareDropBanner.match(line);
        assertTrue(banner.isPresent(), "expected a banner match for: " + line);
        return banner.get();
    }

    private static void assertNoMatch(String line) {
        assertTrue(RareDropBanner.match(line).isEmpty(), "should not have matched: " + line);
    }
}
