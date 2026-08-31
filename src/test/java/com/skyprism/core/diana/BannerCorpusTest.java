package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.LootDrop.MagicFind;
import com.skyprism.core.diana.LootParser.BannerMatch;
import com.skyprism.core.diana.LootParser.Shape;
import com.skyprism.core.loot.combat.MobRareDropDetector;
import com.skyprism.core.loot.events.RareDropBanner;
import com.skyprism.core.loot.gathering.BannerLines;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

/**
 * The whole captured drop-banner corpus, as one table, asserted end to end.
 *
 * <p><b>Why a table and not a method per line.</b> The defect this suite exists to prevent is one
 * shape quietly claiming another's lines -- a bracketed drop read as an item named "(", a Crop Fever
 * sentence read as an item name, an aqua item swallowing its own Magic Find bracket. Those only show
 * up when every line is checked against every expectation, including which <em>shape</em> claimed
 * it, so the shape is part of each row.
 *
 * <p><b>Provenance.</b> Every {@code line} below is a captured Hypixel line reproduced from a
 * reference mod's own regression corpus (SkyHanni {@code beta}: {@code RareDropMessages.kt},
 * {@code ChatFilter.kt}, {@code DungeonChatFilter.kt}, {@code CropFeverTracker.kt},
 * {@code PestProfitTracker.kt}; Skyblocker {@code main}: {@code RareDropSpecialEffects.java},
 * {@code SkyBlockIcons.java}) or from this project's own {@code LootSourceRegistry} samples. Rows
 * whose colour codes are reconstructed rather than captured say so in the label and assert nothing
 * that depends on them.
 *
 * <p>Section signs and both Magic Find glyphs are escapes, so the file's encoding cannot change what
 * is asserted -- and the parser is required not to care which glyph, or neither, arrived.
 */
@DisplayName("The banner corpus: every captured shape, one parser, Magic Find carried through")
class BannerCorpusTest {

    private static final String S = "§";

    /** The Magic Find icon Hypixel uses today: a private-use codepoint from its own stat font. */
    private static final String ICON_NEW = String.valueOf((char) 0xE01A);

    /** The glyph it used before that. Both must work, and so must neither. */
    private static final String ICON_OLD = "✯";

    private final LootParser parser = new LootParser();

    /**
     * One captured line and everything the parser must say about it.
     *
     * @param magicFind the expected reading, or {@code null} for "the server reported none" --
     *                  which is a different assertion from a reading of zero
     */
    private record Row(String label, String line, Shape shape, String item, String color,
                       int count, boolean rare, MagicFind magicFind) {
    }

    private static Row row(String label, String line, Shape shape, String item, String color,
                           int count, boolean rare, MagicFind magicFind) {
        return new Row(label, line, shape, item, color, count, rare, magicFind);
    }

    // ================================================================= the table

    private static final List<Row> CORPUS = List.of(

            // ---------------------------------------------- the plain shape, every MF form

            row("plain drop, split-run Magic Find with the current icon",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Judgement Core "
                            + S + "r" + S + "b(+" + S + "r" + S + "b168% " + S + "r" + S + "b"
                            + ICON_NEW + " Magic Find" + S + "r" + S + "b)",
                    Shape.PLAIN, "Judgement Core", "9", 1, true, new MagicFind(168, true)),

            row("the same line with NO percent sign -- Hypixel sends both",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Judgement Core "
                            + S + "r" + S + "b(+" + S + "r" + S + "b168 " + S + "r" + S + "b"
                            + ICON_NEW + " Magic Find" + S + "r" + S + "b)",
                    Shape.PLAIN, "Judgement Core", "9", 1, true, new MagicFind(168, false)),

            row("the legacy star glyph, otherwise identical",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Judgement Core "
                            + S + "r" + S + "b(+" + S + "r" + S + "b208% " + S + "r" + S + "b"
                            + ICON_OLD + " Magic Find" + S + "r" + S + "b)",
                    Shape.PLAIN, "Judgement Core", "9", 1, true, new MagicFind(208, true)),

            row("DUNGEONS: no icon at all, and a trailing '!' inside the bracket",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Hunk of Blue Ice "
                            + S + "r" + S + "b(+240% Magic Find!)",
                    Shape.PLAIN, "Hunk of Blue Ice", "9", 1, true, new MagicFind(240, true)),

            row("a pet drop with a Magic Find tail",
                    S + "6" + S + "lPET DROP! " + S + "r" + S + "5Baby Yeti "
                            + S + "r" + S + "b(+" + S + "r" + S + "b168% " + S + "r" + S + "b"
                            + ICON_OLD + " Magic Find" + S + "r" + S + "b)",
                    Shape.PLAIN, "Baby Yeti", "5", 1, true, new MagicFind(168, true)),

            row("a pet drop with no suffix at all: reported nothing, which is not zero",
                    S + "6" + S + "lPET DROP! " + S + "r" + S + "6Rat",
                    Shape.PLAIN, "Rat", "6", 1, true, null),

            row("a reported Magic Find of zero, which IS a reading",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Judgement Core "
                            + S + "r" + S + "b(+0% " + ICON_NEW + " Magic Find)",
                    Shape.PLAIN, "Judgement Core", "9", 1, true, new MagicFind(0, true)),

            row("PET LUCK wears the same banner and the same brackets: not Magic Find",
                    S + "6" + S + "lPET DROP! " + S + "r" + S + "5Slug " + S + "6(" + S + "6+1300☘)",
                    Shape.PLAIN, "Slug", "5", 1, true, null),

            row("Garden pest drop: no reset after the banner, trailing count, fortune tail",
                    S + "6" + S + "lRARE DROP! " + S + "9Mutant Nether Wart " + S + "8x9 "
                            + S + "e(" + S + "e+134)",
                    Shape.PLAIN, "Mutant Nether Wart", "9", 9, true, null),

            row("a stack and a Magic Find on the same line",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Enchanted Gold " + S + "8x16 "
                            + S + "r" + S + "b(+168% " + ICON_NEW + " Magic Find)",
                    Shape.PLAIN, "Enchanted Gold", "9", 16, true, new MagicFind(168, true)),

            row("a leading multiplier is split off the name",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "93x Enchanted Ancient Claw",
                    Shape.PLAIN, "Enchanted Ancient Claw", "9", 3, true, null),

            row("an item name split across a same-colour reset survives whole",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Arachne's " + S + "r" + S + "9"
                            + "Keeper Fragment " + S + "r" + S + "b(+123% " + ICON_OLD + " Magic Find)",
                    Shape.PLAIN, "Arachne's Keeper Fragment", "9", 1, true, new MagicFind(123, true)),

            row("DIVINE rarity is aqua, so the tail used to be welded into the name",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "bAurora Helmet "
                            + S + "r" + S + "b(+" + S + "r" + S + "b168% " + S + "r" + S + "b"
                            + ICON_NEW + " Magic Find" + S + "r" + S + "b)",
                    Shape.PLAIN, "Aurora Helmet", "b", 1, true, new MagicFind(168, true)),

            row("a bold item name -- two codes before the name, which used to be a silent miss",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "6" + S + "lDivan's Alloy "
                            + S + "r" + S + "b(+168% " + ICON_NEW + " Magic Find)",
                    Shape.PLAIN, "Divan's Alloy", "6", 1, true, new MagicFind(168, true)),

            row("a leading space before the codes, as Hypixel really indents some lines",
                    S + "6 " + S + "r" + S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Hunter Ring "
                            + S + "r" + S + "b(+123% " + ICON_OLD + " Magic Find)",
                    Shape.PLAIN, "Hunter Ring", "9", 1, true, new MagicFind(123, true)),

            row("every remaining banner word in the family",
                    S + "6" + S + "lINSANE DROP! " + S + "r" + S + "dMythological Dye",
                    Shape.PLAIN, "Mythological Dye", "d", 1, true, null),

            row("VERY RARE DROP! is followed by TWO spaces",
                    S + "5" + S + "lVERY RARE DROP!  " + S + "r" + S + "5Revenant Catalyst",
                    Shape.PLAIN, "Revenant Catalyst", "5", 1, true, null),

            row("CRAZY RARE DROP! likewise",
                    S + "d" + S + "lCRAZY RARE DROP!  " + S + "r" + S + "dPocket Espresso Machine",
                    Shape.PLAIN, "Pocket Espresso Machine", "d", 1, true, null),

            // ------------------------------------------------------ the bracketed shape

            row("a sackable slayer drop is the item, never an item named '('",
                    S + "b" + S + "lRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f"
                            + S + "r" + S + "9Revenant Viscera" + S + "r" + S + "7) (+123% "
                            + ICON_NEW + " Magic Find)",
                    Shape.BRACKETED, "Revenant Viscera", "9", 1, true, new MagicFind(123, true)),

            row("a stacked sack drop: the count lives inside the bracket",
                    S + "b" + S + "lRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f"
                            + S + "r" + S + "72x " + S + "r" + S + "f" + S + "r" + S + "9Foul Flesh"
                            + S + "r" + S + "7) (+123% " + ICON_NEW + " Magic Find)",
                    Shape.BRACKETED, "Foul Flesh", "9", 2, true, new MagicFind(123, true)),

            row("a two-space VERY RARE in the bracketed shape",
                    S + "5" + S + "lVERY RARE DROP!  " + S + "r" + S + "7(" + S + "r" + S + "f"
                            + S + "r" + S + "5Revenant Catalyst" + S + "r" + S + "7) (+123% "
                            + ICON_NEW + " Magic Find)",
                    Shape.BRACKETED, "Revenant Catalyst", "5", 1, true, new MagicFind(123, true)),

            row("an enchanted book carries no colour run at all, and none is invented",
                    S + "9" + S + "lVERY RARE DROP!  " + S + "r" + S + "7(" + S + "r" + S + "f"
                            + "Mana Steal I" + S + "r" + S + "7) (+1% " + ICON_NEW + " Magic Find)",
                    Shape.BRACKETED, "Mana Steal I", null, 1, true, new MagicFind(1, true)),

            row("the bracketed Magic Find tail is unstyled -- a matcher anchored on aqua misses it",
                    S + "d" + S + "lCRAZY RARE DROP!  " + S + "r" + S + "7(" + S + "r" + S + "f"
                            + S + "r" + S + "fPocket Espresso Machine" + S + "r" + S + "7) (+1% "
                            + ICON_OLD + " Magic Find)",
                    Shape.BRACKETED, "Pocket Espresso Machine", "f", 1, true, new MagicFind(1, true)),

            // -------------------------------------------------------- the sentence shape

            row("Garden Crop Fever: the reward is inside an English sentence (COLOURS INFERRED)",
                    S + "9" + S + "lRARE DROP! " + S + "r" + S + "aYou dropped 48x "
                            + "Enchanted Melon Slice!",
                    Shape.SENTENCE, "Enchanted Melon Slice", "a", 48, true, null),

            row("its UNCOMMON sibling parses, and is NOT flagged rare (COLOURS INFERRED)",
                    S + "a" + S + "lUNCOMMON DROP! " + S + "r" + S + "aYou dropped 24x "
                            + "Enchanted Melon Slice!",
                    Shape.SENTENCE, "Enchanted Melon Slice", "a", 24, false, null)
    );

    // =========================================================== the table, executed

    @TestFactory
    @DisplayName("every captured line decomposes to exactly the right drop")
    List<DynamicTest> everyCapturedLine() {
        return CORPUS.stream().map(r -> DynamicTest.dynamicTest(r.label(), () -> {
            BannerMatch match = LootParser.matchBanner(r.line())
                    .orElseThrow(() -> new AssertionError("no match for: " + r.line()));

            assertEquals(r.shape(), match.shape(),
                    "the wrong shape claimed this line, which is how one form swallows another");
            LootDrop drop = match.drop();
            assertEquals(r.item(), drop.itemName(), "item name");
            assertEquals(r.color(), drop.colorCode(), "colour code");
            assertEquals(r.count(), drop.count(), "count");
            assertEquals(r.rare(), drop.rare(), "rare flag");

            if (r.magicFind() == null) {
                assertFalse(drop.magicFindReported(),
                        "this line reports no Magic Find; a value here is invented: "
                                + drop.magicFind());
                assertNull(drop.magicFindText());
            } else {
                assertTrue(drop.magicFindReported(), "the reported Magic Find was thrown away");
                assertEquals(r.magicFind(), drop.magicFind(), "Magic Find reading");
            }

            // parse() is the reel path and must agree with the shared matcher on every one.
            assertEquals(List.of(drop), parser.parse(r.line()),
                    "parse() and matchBanner() disagreed, which is the drift this file prevents");
            assertEquals(drop.magicFind(), parser.parse(r.line()).get(0).magicFind(),
                    "parse() dropped the Magic Find on the floor");
        })).toList();
    }

    @TestFactory
    @DisplayName("no line in the corpus is claimed by a shape it does not belong to")
    List<DynamicTest> noShapeSwallowsAnother() {
        return CORPUS.stream().map(r -> DynamicTest.dynamicTest(r.label(), () -> {
            for (Shape other : Shape.values()) {
                if (other == r.shape()) {
                    continue;
                }
                assertFalse(LootParser.matchBanner(r.line())
                                .map(m -> m.shape() == other).orElse(false),
                        other + " claimed a line that belongs to " + r.shape());
            }
            // The item name is never a fragment of the machinery around it.
            String name = LootParser.matchBanner(r.line()).orElseThrow().drop().itemName();
            assertFalse(name.startsWith("("), "an open bracket reached a reel: " + name);
            assertFalse(name.startsWith("You "), "an English sentence reached a reel: " + name);
            assertFalse(name.contains("Magic Find"), "the stat was welded into the name: " + name);
            assertFalse(name.isBlank(), "a blank name reached a reel");
        })).toList();
    }

    // ================================================================ the negatives

    @Nested
    @DisplayName("what must never be claimed")
    class Negatives {

        private void assertNothing(String line) {
            assertTrue(LootParser.matchBanner(line).isEmpty(), "matchBanner claimed: " + line);
            assertTrue(new LootParser().parse(line).isEmpty(), "parse claimed: " + line);
            assertTrue(RareDropBanner.match(line).isEmpty(), "RareDropBanner claimed: " + line);
        }

        @Test
        @DisplayName("Magic Find on a line that is not a drop at all: the kill-combo buff grant")
        void killComboBuff() {
            // The single most important negative for this feature. A free-standing Magic Find
            // matcher run over chat fires here and staples 3% onto whatever drop is on screen.
            assertNothing(S + "a" + S + "l+5 Kill Combo " + S + "r" + S + "8+" + S + "r" + S + "b3% "
                    + S + "r" + S + "b" + ICON_NEW + " Magic Find");
            assertNothing(S + "a" + S + "l+10 Kill Combo " + S + "r" + S + "8+" + S + "r" + S + "b5% "
                    + S + "r" + S + "b" + ICON_OLD + " Magic Find");
        }

        @Test
        @DisplayName("a player typing a banner cannot spin anybody's machine, in any shape")
        void playerTypedBanners() {
            assertNothing("RARE DROP! Crown of Greed");
            assertNothing("VERY RARE DROP!  Crown of Greed");
            assertNothing("UNCOMMON DROP! Enchanted Melon Slice");
            assertNothing("PET DROP! Baby Yeti");
            assertNothing("   RARE DROP! Crown of Greed");
            assertNothing(S + "bBob" + S + "f: RARE DROP! Cropie (+97) lol");
            assertNothing(S + "9Party " + S + "8> " + S + "bSteve" + S + "f: " + S + "rVERY RARE DROP!  "
                    + S + "r" + S + "7(" + S + "r" + S + "f" + S + "r" + S + "5Sorrow" + S + "r" + S + "7)");
            assertNothing(S + "b[MVP" + S + "r" + S + "6+" + S + "r" + S + "b] Notch" + S + "f: "
                    + S + "rRARE DROP! " + S + "r" + S + "9Judgement Core "
                    + S + "r" + S + "b(+168% " + ICON_NEW + " Magic Find)");
        }

        @Test
        @DisplayName("the colour-stripped Crop Fever line is refused, deliberately")
        void colourStrippedSentence() {
            // SkyHanni matches this form because it works on cleaned text. Admitting it here would
            // also admit a player typing it, and the leading-code rule is worth more than the line.
            assertNothing("RARE DROP! You dropped 48x Enchanted Melon Slice!");
            assertNothing("UNCOMMON DROP! You dropped 24x Enchanted Melon Slice!");
        }

        @Test
        @DisplayName("somebody else's loot is refused, whatever the sentence")
        void thirdPartyLoot() {
            assertNothing(S + "aSteve " + S + "r" + S + "ehas obtained " + S + "r" + S + "a"
                    + S + "r" + S + "9Judgement Core" + S + "r" + S + "e!");
            assertNothing(S + "6" + S + "lRARE REWARD! " + S + "r" + S + "bLeebys "
                    + S + "r" + S + "efound a " + S + "r" + S + "6Recombobulator 3000 "
                    + S + "r" + S + "ein their Obsidian Chest" + S + "r" + S + "e!");
            assertNothing(S + "c" + S + "lBONUS LOOT! " + S + "r" + S + "eThey also received "
                    + S + "r" + S + "817x " + S + "r" + S + "5Wise Dragon Fragment "
                    + S + "r" + S + "efrom their sacrifice!");
            assertTrue(LootParser.isThirdPartyLine(
                    S + "aSteve " + S + "r" + S + "ehas obtained " + S + "r" + S + "9Sorrow"
                            + S + "r" + S + "e!"));
            assertFalse(LootParser.isThirdPartyLine(null));
        }

        @Test
        @DisplayName("a broadcast wearing a real banner word is still refused")
        void broadcastWithABannerWord() {
            // Constructed, not captured: Hypixel is not known to broadcast this family at all. The
            // point is that if it ever did, the ownership check runs before any pattern.
            assertNothing(S + "6" + S + "lRARE DROP! " + S + "r" + S + "bSteve " + S + "r"
                    + S + "ehas obtained " + S + "r" + S + "9Judgement Core" + S + "r" + S + "e!");
        }

        @Test
        @DisplayName("other families' banners are not in this alternation")
        void otherFamilies() {
            assertNothing(S + "6" + S + "lRARE CROP! " + S + "aCane Knot " + S + "e(" + S + "e+139.5)");
            assertNothing("FLOOR DROP! You found Litterbug Shard on the ground!");
            assertNothing("CAPTURE! You caught a Strongarm and gained a Strongarm Shard!");
            assertNothing("FUSION! You obtained Bolt Shard x2! NEW!");
            assertNothing(S + "6 " + S + "r" + S + "6" + S + "lTROPHY FISH! " + S + "r" + S + "fYou "
                    + "caught a " + S + "r" + S + "9Lavahorse " + S + "r" + S + "6" + S + "lGOLD"
                    + S + "r" + S + "f!");
            assertNothing(S + "eYou received " + S + "a7x Enchanted Potato " + S + "efor killing a "
                    + S + "2Locust" + S + "e!");
            assertNothing("    " + S + "r" + S + "dGold Essence " + S + "r" + S + "8x3");
        }

        @Test
        @DisplayName("degenerate input is a non-event, not an exception")
        void degenerate() {
            assertNothing(null);
            assertNothing("");
            assertNothing("RARE DROP!");
            assertNothing(" ");
            assertNothing(S);
            assertNothing(S + S + S + S);
            assertNothing(S + "6" + S + "lRARE DROP! " + S + "r" + S + "9");
        }
    }

    // ============================================================ one implementation

    @Nested
    @DisplayName("there is one parser, and the adapter is an adapter")
    class Unification {

        @Test
        @DisplayName("RareDropBanner returns exactly what LootParser decided, Magic Find included")
        void adapterAgreesWithTheParser() {
            for (Row r : CORPUS) {
                if (r.shape() == Shape.SENTENCE) {
                    continue; // policy: sentence drops are loot, not an arming event
                }
                LootDrop drop = LootParser.matchBanner(r.line()).orElseThrow().drop();
                RareDropBanner.Banner banner = RareDropBanner.match(r.line())
                        .orElseThrow(() -> new AssertionError("adapter lost: " + r.line()));
                assertEquals(drop.itemName(), banner.item(), r.label());
                assertEquals(drop.colorCode(), banner.color(), r.label());
                assertEquals(drop.count(), banner.count(), r.label());
                assertEquals(drop.rare(), banner.rare(), r.label());
                assertEquals(drop.magicFind(), banner.magicFind(), r.label());
                assertEquals(drop, banner.drop(), r.label());
            }
        }

        @Test
        @DisplayName("the adapter refuses sentence drops so a fever window cannot strobe the bus")
        void adapterRefusesSentenceDrops() {
            String fever = S + "9" + S + "lRARE DROP! " + S + "r" + S + "aYou dropped 48x "
                    + "Enchanted Melon Slice!";
            assertTrue(RareDropBanner.match(fever).isEmpty(),
                    "each drop inside a Crop Fever must not fire a source; the fever start is the roll");
            // ...but the item is still available to the reels through the parser.
            assertEquals("Enchanted Melon Slice", new LootParser().parse(fever).get(0).itemName());
        }

        @Test
        @DisplayName("UNCOMMON DROP! decomposes, and is not a rare drop")
        void uncommonIsInTheVocabularyButNotRare() {
            assertFalse(LootParser.isRareBanner("UNCOMMON DROP!"));
            assertTrue(LootParser.isRareBanner("RARE DROP!"));
            assertTrue(LootParser.isRareBanner("PET DROP!"));
            assertTrue(LootParser.isRareBanner("VERY RARE DROP!"));
            assertTrue(LootParser.isRareBanner("CRAZY RARE DROP!"));
            assertTrue(LootParser.isRareBanner("INSANE DROP!"));
            assertFalse(LootParser.isRareBanner(null));
        }

        @Test
        @DisplayName("Diana's treasure shapes stay with parse() and out of the shared matcher")
        void treasureStaysWithDiana() {
            String feather = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                    + S + "r" + S + "9Griffin Feather" + S + "r" + S + "e!";
            String coins = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out "
                    + S + "r" + S + "62,500 coins" + S + "r" + S + "e!";

            assertEquals(new LootDrop("Griffin Feather", "9", 1, true),
                    new LootParser().parse(feather).get(0));
            assertEquals(new LootDrop("Coins", "6", 2500, false),
                    new LootParser().parse(coins).get(0));
            assertFalse(new LootParser().parse(feather).get(0).magicFindReported(),
                    "a treasure dig never reports a Magic Find");

            assertTrue(LootParser.matchBanner(feather).isEmpty(),
                    "the shared matcher must never claim a treasure dig");
            assertTrue(LootParser.matchBanner(coins).isEmpty());
            assertTrue(RareDropBanner.match(feather).isEmpty());
        }

        @Test
        @DisplayName("the two cheap yes/no callers read the same vocabulary as the parser")
        void theBooleanCallersShareTheVocabulary() {
            for (Row r : CORPUS) {
                assertEquals(LootParser.matchBanner(r.line()).orElseThrow().banner(),
                        LootParser.bannerWordOf(r.line()),
                        "the cheap read and the full read must name the same banner: " + r.label());
                assertTrue(BannerLines.isRareDropBanner(r.line()),
                        "BannerLines must not disagree with the parser about the family: "
                                + r.label());
            }
        }

        @Test
        @DisplayName("no caller accepts a banner nobody can decompose -- the old UNCOMMON split")
        void uncommonNoLongerArmsWhatCannotBeRead() {
            String uncommon = S + "a" + S + "lUNCOMMON DROP! " + S + "r" + S + "aEnchanted Melon "
                    + "Slice " + S + "8x24";
            assertTrue(BannerLines.isRareDropBanner(uncommon),
                    "the arming test has always accepted UNCOMMON");
            assertEquals("Enchanted Melon Slice",
                    LootParser.matchBanner(uncommon).orElseThrow().drop().itemName(),
                    "...and now the parser can decompose it, instead of the roll settling on "
                            + "\"No Drop\"");
            assertFalse(LootParser.matchBanner(uncommon).orElseThrow().drop().rare());
        }

        @Test
        @DisplayName("an entirely unformatted line is refused by every caller, not just the parser")
        void bareLinesAreRefusedEverywhere() {
            String bare = "RARE DROP! Crown of Greed";
            assertTrue(LootParser.matchBanner(bare).isEmpty());
            assertNull(LootParser.bannerWordOf(bare));
            assertFalse(LootParser.looksLikeBanner(bare));
            assertFalse(BannerLines.isRareDropBanner(bare),
                    "the copy this class absorbed matched here, and its caller has no colon guard");
            assertTrue(new MobRareDropDetector().onChat(bare, 1L).isEmpty());
        }

        @Test
        @DisplayName("the catch-all still fires on a shape the parser cannot yet name")
        void theCatchAllDoesNotRequireADecomposition() {
            // Bracketed, but with the closing grey bracket truncated away: matchBanner cannot read
            // it, and losing the roll entirely would be the worse of the two wrong answers.
            String truncated = S + "b" + S + "lRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f"
                    + S + "r" + S + "9Revenant Viscera";
            assertTrue(LootParser.matchBanner(truncated).isEmpty(),
                    "the reels must not be given an item named \"(\"");
            assertTrue(new MobRareDropDetector().onChat(truncated, 1L).isPresent(),
                    "but the machine still spins, captioned with the source's own name");
        }
    }

    // ================================================== the Magic Find reader in isolation

    @Nested
    @DisplayName("the Magic Find reader")
    class MagicFindReader {

        private Optional<MagicFind> read(String line) {
            return LootParser.matchBanner(line).map(m -> m.drop().magicFind());
        }

        private String withTail(String tail) {
            return S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Sorrow " + tail;
        }

        @Test
        @DisplayName("a comma-grouped reading is read as a number")
        void commaGrouped() {
            assertEquals(new MagicFind(1234, true),
                    read(withTail(S + "r" + S + "b(+1,234% " + ICON_NEW + " Magic Find)")).orElseThrow());
        }

        @Test
        @DisplayName("a reading too large for an int saturates rather than throwing")
        void absurdReadingSaturates() {
            // Thirteen characters is the bound on the digit group, so this is the largest reading
            // the pattern will accept -- and it is still an order of magnitude past Integer.MAX.
            assertEquals(Integer.MAX_VALUE,
                    read(withTail(S + "r" + S + "b(+9,999,999,999% Magic Find)"))
                            .orElseThrow().value());
        }

        @Test
        @DisplayName("a reading longer than the bounded digit group is absent, not truncated")
        void overlongReadingIsAbsentRatherThanWrong() {
            // Reporting "99,999,999,99" as the value would be a wrong number on the one screen
            // that exists to state a number. Nobody has 99 trillion Magic Find.
            assertNull(read(withTail(S + "r" + S + "b(+99,999,999,999,999% Magic Find)"))
                    .orElse(null));
        }

        @Test
        @DisplayName("a tail that is not a Magic Find is not read as one")
        void lookAlikeTails() {
            assertNull(read(withTail(S + "e(" + S + "e+134)")).orElse(null), "farming fortune");
            assertNull(read(withTail(S + "6(" + S + "6+1300☘)")).orElse(null), "pet luck");
            assertNull(read(withTail(S + "7(" + S + "7+5 Strength)")).orElse(null), "another stat");
            assertNull(read(withTail(S + "b(+97% ✴ SkyHanni User Luck)")).orElse(null),
                    "a mod's own injected line, which Hypixel never sends");
            assertNull(read(withTail(S + "7(Cocoaleech)")).orElse(null), "the pest vinyl literal");
        }

        @Test
        @DisplayName("a malformed reading is absent rather than wrong")
        void malformedReading() {
            assertNull(read(withTail(S + "b(+% Magic Find)")).orElse(null));
            assertNull(read(withTail(S + "b(+ Magic Find)")).orElse(null));
            assertNull(read(withTail(S + "bMagic Find)")).orElse(null));
        }

        @Test
        @DisplayName("the reading survives on a line that also carries a count")
        void readingWithCount() {
            LootDrop drop = LootParser.matchBanner(
                            S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Enchanted Gold "
                                    + S + "8x16 " + S + "r" + S + "b(+168% Magic Find)")
                    .orElseThrow().drop();
            assertEquals(16, drop.count());
            assertEquals(new MagicFind(168, true), drop.magicFind());
        }
    }

    // ================================================================== robustness

    @Nested
    @DisplayName("robustness the chat thread depends on")
    class Robustness {

        @Test
        @DisplayName("a comma-only count is not a stack of two billion")
        void commaOnlyCount() {
            LootDrop drop = LootParser.matchBanner(
                            S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Mutant Nether Wart"
                                    + S + "8x,")
                    .orElseThrow().drop();
            assertEquals(1, drop.count(),
                    "a punctuation-only count used to saturate to Integer.MAX_VALUE");
        }

        @Test
        @DisplayName("an embedded newline cannot weld a second line onto the name")
        void embeddedNewline() {
            assertTrue(LootParser.matchBanner(
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "5Crown of Greed\nSteve: hi")
                    .isEmpty());
        }

        @Test
        @DisplayName("a pathological line stays linear, in every shape")
        void pathologicalLines() {
            String spaces = " ".repeat(20_000);
            String[] lines = {
                S + "6" + S + "lRARE DROP! " + S + "r" + S + "9" + spaces + "x" + S,
                S + "b" + S + "lRARE DROP! " + S + "r" + S + "7(" + S + "r" + S + "f" + S + "r"
                        + S + "9" + spaces + "x" + S + "r" + S + "7)",
                S + "6" + S + "lRARE DROP! " + S + "r" + S + "9You dropped 4x " + spaces + "x!",
                S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Sorrow " + S + "r" + S + "b(+1% "
                        + spaces + " Magic Find)",
            };
            for (String line : lines) {
                long startNanos = System.nanoTime();
                LootParser.matchBanner(line);
                long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
                assertTrue(elapsedMillis < 1_000L,
                        "matching took " + elapsedMillis + "ms; something is backtracking");
            }
        }

        @Test
        @DisplayName("a supplementary code point in a name survives, not as half a surrogate pair")
        void supplementaryCodePoints() {
            String name = "Crown 👑 of Greed";
            assertEquals(name, LootParser.matchBanner(
                            S + "6" + S + "lRARE DROP! " + S + "r" + S + "5" + name)
                    .orElseThrow().drop().itemName());
        }
    }
}
