package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Corpus tests for {@link DianaPatterns}.
 *
 * <p>Every line here is built the way Hypixel builds it: the exclamation, the sentence and
 * the closing punctuation are fixed, and the creature's own colour code comes from
 * {@link MythologicalCreature#colorCode()}. Section signs are {@code \u00A7} escapes so
 * no source-encoding setting can change what is being asserted.
 */
class DianaPatternsTest {

    private static final String S = "\u00A7";

    /**
     * The seven random exclamations Hypixel prefixes a spawn with, verbatim from the
     * pattern's own alternation.
     */
    private static final List<String> EXCLAMATIONS =
            List.of("Oh", "Uh oh", "Yikes", "Oi", "Good Grief", "Danger", "Woah");

    /** Builds the spawn line exactly as the server sends it for a singular creature. */
    private static String spawnLine(String exclamation, MythologicalCreature creature) {
        return S + "c" + S + "l" + exclamation + "! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + creature.colorCode() + creature.displayName() + S + "r" + S + "e!";
    }

    // ---------------------------------------------------------------- spawns

    @ParameterizedTest
    @EnumSource(MythologicalCreature.class)
    @DisplayName("every one of the twelve creatures is recognised from its own spawn line")
    void everyCreatureSpawnIsRecognised(MythologicalCreature creature) {
        String line = spawnLine("Oh", creature);
        assertEquals(Optional.of(creature), DianaPatterns.matchSpawn(line), line);
    }

    @ParameterizedTest
    @EnumSource(MythologicalCreature.class)
    @DisplayName("all seven random exclamations work for every creature")
    void everyExclamationWorks(MythologicalCreature creature) {
        for (String exclamation : EXCLAMATIONS) {
            String line = spawnLine(exclamation, creature);
            assertEquals(Optional.of(creature), DianaPatterns.matchSpawn(line), line);
        }
    }

    @Test
    @DisplayName("multi-word creature names survive the [\\\\w\\\\s]+ capture intact")
    void multiWordNamesAreCapturedWhole() {
        // The likeliest regression in this whole module: a name class that stops at the
        // first space would truncate eight of the twelve creatures to their first word.
        List<MythologicalCreature> multiWord = List.of(
                MythologicalCreature.MINOS_INQUISITOR,
                MythologicalCreature.KING_MINOS,
                MythologicalCreature.GAIA_CONSTRUCT,
                MythologicalCreature.SIAMESE_LYNXES,
                MythologicalCreature.STRANDED_NYMPH,
                MythologicalCreature.CRETAN_BULL,
                MythologicalCreature.MINOS_CHAMPION,
                MythologicalCreature.MINOS_HUNTER);

        for (MythologicalCreature creature : multiWord) {
            assertTrue(creature.displayName().contains(" "), creature + " should be multi-word");
            var match = DianaPatterns.SPAWN.matcher(spawnLine("Yikes", creature));
            assertTrue(match.matches(), creature + " spawn line should match");
            assertEquals(creature.displayName(), match.group("creatureType"),
                    creature + " must be captured whole, not truncated at the space");
        }
    }

    @Test
    @DisplayName("the plural 'Siamese Lynxes' line has no article and still matches")
    void pluralSpawnWithoutArticle() {
        String line = S + "c" + S + "lOi! " + S + "r" + S + "eYou dug out "
                + S + "r" + S + "2Siamese Lynxes" + S + "r" + S + "e!";
        assertEquals(Optional.of(MythologicalCreature.SIAMESE_LYNXES), DianaPatterns.matchSpawn(line));
    }

    @Test
    @DisplayName("a spawn line with no colour run at all still matches")
    void spawnWithoutColourRun() {
        String line = S + "c" + S + "lWoah! " + S + "r" + S + "eYou dug out a Minotaur" + S + "r" + S + "e!";
        assertEquals(Optional.of(MythologicalCreature.MINOTAUR), DianaPatterns.matchSpawn(line));
    }

    @Test
    @DisplayName("a well-formed spawn line naming an unknown creature yields empty, not a guess")
    void unknownCreatureDegradesToEmpty() {
        String line = S + "c" + S + "lDanger! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "cHydra of Lerna" + S + "r" + S + "e!";
        assertTrue(DianaPatterns.SPAWN.matcher(line).matches(), "shape is a valid spawn line");
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(line));
    }

    // ------------------------------------------------------- spawn negatives

    @Test
    @DisplayName("a party message quoting a spawn line must not match")
    void partyQuotedSpawnIsRejected() {
        String quoted = S + "9Party " + S + "8> " + S + "b[MVP" + S + "r" + S + "c+" + S + "b] Steve"
                + S + "f: " + S + "r" + spawnLine("Oh", MythologicalCreature.MINOS_INQUISITOR);
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(quoted),
                "anchoring is the only thing stopping another player spinning your HUD");
    }

    @Test
    @DisplayName("ordinary player chat must not match anything")
    void ordinaryChatIsRejected() {
        String chat = S + "b[MVP" + S + "r" + S + "6+" + S + "b] Notch" + S + "f: "
                + S + "roh! you dug out a minos inquisitor lol";
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(chat));
        assertEquals(Optional.empty(), DianaPatterns.matchBurrowDig(chat));
        assertFalse(DianaPatterns.isTreasureDig(chat));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You dug out a hole in the ground",
            "I love it when You dug out is in a sentence",
            "Oh! You dug out a Minos Inquisitor!",
    })
    @DisplayName("plain text merely containing the phrase must not match")
    void plainTextContainingThePhraseIsRejected(String line) {
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(line));
        assertFalse(DianaPatterns.isTreasureDig(line));
    }

    @Test
    @DisplayName("a mob-kill line from another event must not match")
    void foreignEventLineIsRejected() {
        String slayerSpawn = S + "5" + S + "lSLAYER QUEST STARTED!" + S + "r";
        String mythicMobKill = S + "6" + S + "lRARE DROP! " + S + "r" + S + "5Judgement Core "
                + S + "r" + S + "b(+" + S + "r" + S + "b152% " + S + "r" + S + "b\u272F Magic Find" + S + "r" + S + "b)";
        String voidgloom = S + "c" + S + "lYikes! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "5Voidgloom Seraph" + S + "r" + S + "e!";

        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(slayerSpawn));
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(mythicMobKill));
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(voidgloom),
                "shape is right but the creature is not a Diana creature");
        assertFalse(DianaPatterns.isTreasureDig(mythicMobKill),
                "a mob drop banner is not a treasure dig");
    }

    @Test
    @DisplayName("null is safe everywhere")
    void nullIsSafe() {
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(null));
        assertEquals(Optional.empty(), DianaPatterns.matchBurrowDig(null));
        assertFalse(DianaPatterns.isTreasureDig(null));
    }

    // ---------------------------------------------------------- burrow digs

    @Test
    @DisplayName("every step of a burrow chain reports its counter")
    void burrowChainSteps() {
        for (int step = 1; step <= 3; step++) {
            String line = S + "eYou dug out a Griffin Burrow! " + S + "r" + S + "7(" + step + "/4)";
            Optional<BurrowDig> dig = DianaPatterns.matchBurrowDig(line);
            assertTrue(dig.isPresent(), line);
            assertEquals(new BurrowDig(false, step, 4), dig.get());
        }
    }

    @Test
    @DisplayName("the chain-finished variant is flagged, not inferred from current == max")
    void chainFinishedVariant() {
        String finished = S + "eYou finished the Griffin burrow chain! " + S + "r" + S + "7(4/4)";
        assertEquals(Optional.of(new BurrowDig(true, 4, 4)), DianaPatterns.matchBurrowDig(finished));

        String stalled = S + "eYou dug out a Griffin Burrow! " + S + "r" + S + "7(4/4)";
        assertEquals(Optional.of(new BurrowDig(false, 4, 4)), DianaPatterns.matchBurrowDig(stalled),
                "a 4/4 ordinary dig is not the end of a chain");
    }

    @Test
    @DisplayName("chains of other lengths are read, not hard-coded to 4")
    void chainLengthIsNotHardCoded() {
        String line = S + "eYou finished the Griffin burrow chain! " + S + "r" + S + "7(6/6)";
        assertEquals(Optional.of(new BurrowDig(true, 6, 6)), DianaPatterns.matchBurrowDig(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You dug out a Griffin Burrow! (1/4)",
            "\u00A7eYou dug out a Griffin Burrow!",
            "\u00A7eYou dug out a Griffin Burrow! \u00A7r\u00A77(x/4)",
    })
    @DisplayName("malformed burrow lines are rejected rather than half-parsed")
    void malformedBurrowLinesRejected(String line) {
        assertEquals(Optional.empty(), DianaPatterns.matchBurrowDig(line));
    }

    // ------------------------------------------------------- treasure digs

    @Test
    @DisplayName("both treasure payout shapes are recognised")
    void treasureDigs() {
        String feather = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "9Griffin Feather" + S + "r" + S + "e!";
        String coins = S + "6" + S + "lWow! " + S + "r" + S + "eYou dug out "
                + S + "r" + S + "62,500 coins" + S + "r" + S + "e!";
        String fragment = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "9Mythos Fragment" + S + "r" + S + "e!";

        assertTrue(DianaPatterns.isTreasureDig(feather));
        assertTrue(DianaPatterns.isTreasureDig(coins));
        assertTrue(DianaPatterns.isTreasureDig(fragment));
    }

    @Test
    @DisplayName("a spawn line is not a treasure dig and vice versa")
    void treasureAndSpawnDoNotOverlap() {
        String spawn = spawnLine("Good Grief", MythologicalCreature.MANTICORE);
        assertFalse(DianaPatterns.isTreasureDig(spawn));

        String feather = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "9Griffin Feather" + S + "r" + S + "e!";
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(feather));
    }

    // -------------------------------------------------- inquisitor share

    @Test
    @DisplayName("the inquisitor waypoint broadcast parses in both party and all-chat form")
    void inquisitorShare() {
        String party = S + "9Party " + S + "8> " + S + "b[MVP" + S + "r" + S + "c+" + S + "b] Steve"
                + S + "f: " + S + "rA MINOS INQUISITOR has spawned near [Howling Cave] at Coords 12 74 -30";
        var partyMatch = DianaPatterns.INQUISITOR_SHARE.matcher(party);
        assertTrue(partyMatch.matches());
        assertEquals(S + "9Party " + S + "8> ", partyMatch.group("party"));
        assertEquals("Howling Cave", partyMatch.group("area"));
        assertEquals("12", partyMatch.group("x"));
        assertEquals("74", partyMatch.group("y"));
        assertEquals("-30", partyMatch.group("z"));

        String allChat = S + "7Steve" + S + "f: "
                + S + "rA MINOS INQUISITOR has spawned near [Graveyard] at Coords -5 70 121";
        var allChatMatch = DianaPatterns.INQUISITOR_SHARE.matcher(allChat);
        assertTrue(allChatMatch.matches());
        assertEquals(null, allChatMatch.group("party"), "no party prefix outside party chat");
        assertEquals("Graveyard", allChatMatch.group("area"));
    }

    @Test
    @DisplayName("a share line wrapped in more chat does not match when anchored")
    void inquisitorShareIsOnlyValidWholeLine() {
        String wrapped = S + "9Party " + S + "8> " + S + "bSteve" + S + "f: "
                + S + "rlol Bob said A MINOS INQUISITOR has spawned near [Graveyard] "
                + "at Coords -5 70 121 but he lied";
        assertFalse(DianaPatterns.INQUISITOR_SHARE.matcher(wrapped).matches(),
                "the trailing text is not part of the broadcast, so the whole line is not one");
    }

    // ------------------------------------------------------- hostile input

    /**
     * The counter groups are {@code \\d+}, which is unbounded, so a corrupted or hostile
     * line can carry more digits than an int holds. Parsing has to survive that inside a
     * chat handler rather than throwing out of it.
     */
    @Test
    @DisplayName("an absurd burrow counter saturates instead of throwing out of the chat handler")
    void absurdBurrowCounterSaturates() {
        String line = S + "eYou dug out a Griffin Burrow! " + S + "r" + S + "7(99999999999999999999/4)";
        assertEquals(Optional.of(new BurrowDig(false, Integer.MAX_VALUE, 4)),
                DianaPatterns.matchBurrowDig(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", "\u00A7", "\u00A7\u00A7\u00A7\u00A7"})
    @DisplayName("empty, blank and code-only lines are safe on every helper")
    void blankAndCodeOnlyLinesAreSafe(String line) {
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(line));
        assertEquals(Optional.empty(), DianaPatterns.matchBurrowDig(line));
        assertFalse(DianaPatterns.isTreasureDig(line));
    }

    /**
     * {@code [\\w\\s]+} admits line terminators, and {@code matches()} would still anchor,
     * so it is worth pinning that neither a second line after a valid spawn nor a break
     * inside the creature name can produce a roll.
     */
    @Test
    @DisplayName("a line break cannot smuggle a spawn line past the anchoring")
    void lineBreaksCannotSmuggleASpawn() {
        String trailing = spawnLine("Oh", MythologicalCreature.MINOS_INQUISITOR) + "\nSteve: hi";
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(trailing));

        String broken = S + "c" + S + "lOh! " + S + "r" + S + "eYou dug out a "
                + S + "r" + S + "cMinos\nInquisitor" + S + "r" + S + "e!";
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(broken),
                "a name split across lines is not a creature this build knows");
    }

    /**
     * A 60,000-character line of nothing but section signs is the shape that makes a
     * badly written alternation backtrack exponentially. All three helpers must stay
     * linear, because they run on the chat thread.
     */
    @Test
    @DisplayName("a pathological line does not make the matchers backtrack")
    void pathologicalLinesDoNotBacktrack() {
        String sectionRun = S + "c" + S + "lOh! " + S + "r" + S + "eYou dug out a " + S.repeat(60_000) + "X";
        String spaceRun = S + "6" + S + "lRARE DROP! " + S + "r" + S + "eYou dug out a "
                + " ".repeat(60_000) + S + "r" + S + "9";

        long startNanos = System.nanoTime();
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(sectionRun));
        assertFalse(DianaPatterns.isTreasureDig(sectionRun));
        assertEquals(Optional.empty(), DianaPatterns.matchSpawn(spaceRun));
        assertFalse(DianaPatterns.isTreasureDig(spaceRun));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMillis < 1_000L,
                "matching four pathological lines took " + elapsedMillis + "ms");
    }
}
