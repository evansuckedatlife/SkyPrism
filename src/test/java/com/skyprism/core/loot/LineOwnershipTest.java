package com.skyprism.core.loot;

import com.skyprism.core.diana.LootParser;
import com.skyprism.core.loot.combat.CombatChatGuards;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ownership rule: whose drop a chat line is about.
 *
 * <p>This guard decides whether the player ever sees their own loot, so the cases here are written
 * as the two failures rather than as the happy path. Refusing a line the player owned is the bug
 * this class was built to fix -- the machine silently does not spin, which reads as a feature that
 * does not work. Accepting a line they did not own is the failure that gets the mod turned off.
 *
 * <p>Every test that installs a name uninstalls it again in {@link #forgetTheName()}: the source is
 * static, because the callers are, and a leaked name would quietly change the answer for every
 * other test in the suite.
 */
@DisplayName("LineOwnership")
class LineOwnershipTest {

    private static final char S = '§';

    /** The client's own username in these tests. */
    private static final String ME = "Notch";

    /** Somebody else, whose loot must never spin this machine. */
    private static final String THEM = "Leebys";

    @AfterEach
    void forgetTheName() {
        LineOwnership.useLocalPlayerName(null);
    }

    /** "&sect;aName &sect;r&sect;ehas obtained &sect;r&sect;9Judgement Core&sect;r&sect;e!" */
    private static String hasObtained(String who) {
        return S + "a" + who + " " + S + "r" + S + "ehas obtained "
                + S + "r" + S + "9Judgement Core" + S + "r" + S + "e!";
    }

    /** The RARE REWARD chest broadcast, which Hypixel sends to the whole party. */
    private static String foundAInTheir(String who) {
        return S + "6" + S + "lRARE REWARD! " + S + "r" + S + "b" + who + " "
                + S + "r" + S + "efound a " + S + "r" + S + "6Recombobulator 3000 "
                + S + "r" + S + "ein their Obsidian Chest" + S + "r" + S + "e!";
    }

    @Nested
    @DisplayName("the player's own loot")
    class OwnLoot {

        @Test
        @DisplayName("their own name in a third-person line is accepted -- the bug being fixed")
        void ownNameIsAccepted() {
            LineOwnership.useLocalPlayerName(() -> ME);
            assertFalse(LineOwnership.announcesAnotherPlayer(hasObtained(ME)));
            assertFalse(LineOwnership.announcesAnotherPlayer(foundAInTheir(ME)));
        }

        @Test
        @DisplayName("their own name wearing a rank prefix is still theirs")
        void ownNameWithRankPrefixIsAccepted() {
            // The prefix is on the line, as Hypixel writes it, coloured a piece at a time.
            String prefixed = S + "b[MVP" + S + "c+" + S + "b] " + ME + " "
                    + S + "r" + S + "ehas obtained " + S + "r" + S + "9Sorrow" + S + "r" + S + "e!";
            assertFalse(LineOwnership.announcesAnotherPlayer(prefixed, ME));

            // And on the supplied name, in case the client ever hands one over decorated.
            assertFalse(LineOwnership.announcesAnotherPlayer(hasObtained(ME), "[MVP+] " + ME));
        }

        @Test
        @DisplayName("a second-person line is theirs by construction, name or no name")
        void secondPersonIsAlwaysAccepted() {
            String own = S + "6" + S + "lEXCAVATOR! " + S + "r" + S + "fYou found a "
                    + S + "r" + S + "9Suspicious Scrap" + S + "r" + S + "f!";
            assertFalse(LineOwnership.announcesAnotherPlayer(own, ME));
            // No name installed: still theirs. "You" needs nothing to compare against, which is
            // why the unknown-name asymmetry does not reach this shape.
            assertFalse(LineOwnership.announcesAnotherPlayer(own, null));
            assertFalse(LineOwnership.announcesAnotherPlayer(own));
        }

        @Test
        @DisplayName("case does not decide ownership")
        void comparisonIsCaseInsensitive() {
            assertFalse(LineOwnership.announcesAnotherPlayer(hasObtained("notch"), "NOTCH"));
        }
    }

    @Nested
    @DisplayName("somebody else's loot")
    class ForeignLoot {

        @Test
        @DisplayName("another name in the same sentence is refused")
        void otherNameIsRefused() {
            LineOwnership.useLocalPlayerName(() -> ME);
            assertTrue(LineOwnership.announcesAnotherPlayer(hasObtained(THEM)));
            assertTrue(LineOwnership.announcesAnotherPlayer(foundAInTheir(THEM)));
        }

        @Test
        @DisplayName("another player's rank prefix does not make the line yours")
        void otherNameWithRankPrefixIsRefused() {
            String theirs = S + "6" + S + "lRARE REWARD! " + S + "r" + S + "b[MVP" + S + "c+"
                    + S + "b] " + THEM + " " + S + "r" + S + "efound a "
                    + S + "r" + S + "6Necron's Handle " + S + "r" + S + "ein their Bedrock Chest"
                    + S + "r" + S + "e!";
            assertTrue(LineOwnership.announcesAnotherPlayer(theirs, ME));
        }

        @Test
        @DisplayName("a name that is a prefix of another player's does not claim their drop")
        void substringNamesDoNotMatch() {
            // The whole point of comparing tokens rather than searching for the name in the line.
            assertTrue(LineOwnership.announcesAnotherPlayer(hasObtained("Notch"), "Not"));
            assertTrue(LineOwnership.announcesAnotherPlayer(hasObtained("Not"), "Notch"));
            assertTrue(LineOwnership.announcesAnotherPlayer(hasObtained("Notchy"), "Notch"));
            assertTrue(LineOwnership.announcesAnotherPlayer(hasObtained("xNotch"), "Notch"));
        }

        @Test
        @DisplayName("the pronoun shapes are refused however good the name is")
        void pronounShapesAreAlwaysRefused() {
            LineOwnership.useLocalPlayerName(() -> ME);
            // No actor to compare against, and the English already says whose it is.
            assertTrue(LineOwnership.announcesAnotherPlayer(
                    S + "c" + S + "lBONUS LOOT! " + S + "r" + S + "eThey also received "
                            + S + "r" + S + "817x " + S + "r" + S + "5Wise Dragon Fragment "
                            + S + "r" + S + "efrom their sacrifice!"));
            // Second person, but a share of somebody else's kill.
            assertTrue(LineOwnership.announcesAnotherPlayer(
                    "LOOT SHARE You received 2 Mossybit Shards for assisting FallenYeti!"));
        }
    }

    @Nested
    @DisplayName("forged lines")
    class Forgery {

        @Test
        @DisplayName("a party broadcast is refused even when it names the local player")
        void partyBroadcastIsRefused() {
            LineOwnership.useLocalPlayerName(() -> ME);
            String forged = S + "9Party " + S + "8> " + S + "bGrazma" + S + "f: "
                    + S + "rRARE REWARD! " + ME + " found a Livid Dagger in their Bedrock Chest!";
            assertTrue(LineOwnership.announcesAnotherPlayer(forged),
                    "a party member must not be able to type the local player's own name into a "
                            + "banner and have it believed");
            assertTrue(CombatChatGuards.rejects(forged));
        }

        @Test
        @DisplayName("a player typing a banner in ordinary chat is refused")
        void typedBannerIsRefused() {
            LineOwnership.useLocalPlayerName(() -> ME);
            assertTrue(LineOwnership.announcesAnotherPlayer(
                    S + "b[MVP" + S + "r" + S + "6+" + S + "r" + S + "b] Grazma" + S + "f: "
                            + S + "r" + ME + " has obtained a Chimera"));
            // And the shape the existing corpus test guards: no tell at all, so ownership has
            // nothing to say and the anchoring in the parser is what refuses it.
            String noTell = S + "9Party " + S + "8> Steve" + S + "f: " + S + "rRARE DROP! "
                    + S + "r" + S + "9Judgement Core";
            assertTrue(CombatChatGuards.rejects(noTell));
            assertEquals(List.of(), new LootParser().parse(noTell));
        }
    }

    @Nested
    @DisplayName("before the client knows who it is")
    class UnknownName {

        @Test
        @DisplayName("every named third-person shape is refused, deliberately")
        void unknownNameRefusesThirdPerson() {
            // The asymmetry is the point: refusing costs one spin in a window that closes on its
            // own; accepting puts a party member's Chimera on this player's screen.
            for (String line : List.of(hasObtained(ME), hasObtained(THEM),
                    foundAInTheir(ME), foundAInTheir(THEM))) {
                assertTrue(LineOwnership.announcesAnotherPlayer(line, null), line);
                assertTrue(LineOwnership.announcesAnotherPlayer(line, ""), line);
                assertTrue(LineOwnership.announcesAnotherPlayer(line, "   "), line);
            }
        }

        @Test
        @DisplayName("no source installed is the same as an unknown name")
        void noSourceInstalled() {
            assertNull(LineOwnership.localPlayerName());
            assertTrue(LineOwnership.announcesAnotherPlayer(hasObtained(ME)));
        }

        @Test
        @DisplayName("a source that throws reads as unknown rather than taking the line down")
        void throwingSourceIsUnknown() {
            LineOwnership.useLocalPlayerName(() -> {
                throw new IllegalStateException("no session yet");
            });
            assertNull(LineOwnership.localPlayerName());
            assertTrue(LineOwnership.announcesAnotherPlayer(hasObtained(ME)));
        }
    }

    @Nested
    @DisplayName("cost")
    class Cost {

        @Test
        @DisplayName("an ordinary chat line never reads the name source")
        void commonPathDoesNotTouchTheSupplier() {
            // An AssertionError is neither a RuntimeException nor a LinkageError, so
            // localPlayerName() will not swallow it: if the common path reads the supplier, this
            // test fails loudly instead of silently costing a lookup per chat line.
            LineOwnership.useLocalPlayerName(() -> {
                throw new AssertionError("the supplier was read on the common path");
            });
            assertFalse(LineOwnership.announcesAnotherPlayer(
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Judgement Core"));
            assertFalse(LineOwnership.announcesAnotherPlayer(S + "9Party " + S + "8> Steve"
                    + S + "f: " + S + "rgg"));
            assertFalse(LineOwnership.announcesAnotherPlayer("A Squid appeared."));
            assertFalse(LineOwnership.announcesAnotherPlayer(null));

            // ... and a line that does carry a tell reaches it, which is what makes the above
            // an assertion about the fast path rather than about the supplier never being used.
            assertThrows(AssertionError.class,
                    () -> LineOwnership.announcesAnotherPlayer(hasObtained(THEM)));
        }
    }

    @Nested
    @DisplayName("one rule, two front doors")
    class OneRule {

        @Test
        @DisplayName("LootParser and CombatChatGuards give the same answer on every shape")
        void bothCallersDelegate() {
            LineOwnership.useLocalPlayerName(() -> ME);
            List<String> corpus = List.of(
                    hasObtained(ME),
                    hasObtained(THEM),
                    foundAInTheir(ME),
                    foundAInTheir(THEM),
                    S + "6" + S + "lEXCAVATOR! " + S + "r" + S + "fYou found a "
                            + S + "r" + S + "9Suspicious Scrap" + S + "r" + S + "f!",
                    "LOOT SHARE You received 2 Mossybit Shards for assisting FallenYeti!",
                    S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Judgement Core");
            for (String line : corpus) {
                assertEquals(LineOwnership.announcesAnotherPlayer(line),
                        LootParser.isThirdPartyLine(line), line);
                assertEquals(LineOwnership.announcesAnotherPlayer(line),
                        CombatChatGuards.announcesAnotherPlayer(line), line);
            }
        }

        @Test
        @DisplayName("the explicit-name overloads agree with the installed source")
        void explicitOverloadsAgree() {
            LineOwnership.useLocalPlayerName(() -> ME);
            String mine = hasObtained(ME);
            assertFalse(LootParser.isThirdPartyLine(mine, ME));
            assertFalse(CombatChatGuards.announcesAnotherPlayer(mine, ME));
            assertTrue(LootParser.isThirdPartyLine(mine, THEM));
            assertTrue(CombatChatGuards.announcesAnotherPlayer(mine, THEM));
        }

        @Test
        @DisplayName("every needle the rule can act on is also one the fast path looks for")
        void noNeedleEscapesTheFastPath() {
            // hasAnyTell is the gate in front of the whole rule. A needle the rule would act on
            // but the gate does not look for is not a slow line -- it is a line called the local
            // player's without anybody checking, i.e. somebody else's drop on this screen. That is
            // the fail-OPEN direction, so it is asserted rather than trusted to a reviewer noticing
            // one array had grown and its sibling had not.
            List<String> actionable = List.of(
                    "has obtained", "found a ", "in their ",
                    "They also received", "from their sacrifice", "for assisting ");
            for (String needle : actionable) {
                String line = "Somebody " + needle + " something";
                assertTrue(LineOwnership.announcesAnotherPlayer(line, ME),
                        "the fast path does not look for \"" + needle + "\", so a line carrying it "
                                + "is silently treated as the local player's");
            }
        }

        @Test
        @DisplayName("a null line belongs to nobody and is not third party")
        void nullLine() {
            assertFalse(LineOwnership.announcesAnotherPlayer(null));
            assertFalse(LineOwnership.announcesAnotherPlayer(null, ME));
            assertFalse(LootParser.isThirdPartyLine(null));
        }
    }

    @Nested
    @DisplayName("the parser end to end")
    class ThroughTheParser {

        @Test
        @DisplayName("a banner line naming the local player reaches the reels")
        void ownBannerParses() {
            String own = S + "6" + S + "lRARE DROP! " + S + "r" + S + "b" + ME + " "
                    + S + "r" + S + "ehas obtained " + S + "r" + S + "9Judgement Core"
                    + S + "r" + S + "e!";
            LineOwnership.useLocalPlayerName(() -> ME);
            assertTrue(LootParser.matchBanner(own).isPresent(),
                    "the ownership check is what used to stop this line");

            // The same line naming somebody else, and the same line before the client has a name,
            // are both still refused before any pattern runs.
            LineOwnership.useLocalPlayerName(() -> THEM);
            assertTrue(LootParser.matchBanner(own).isEmpty());
            LineOwnership.useLocalPlayerName(null);
            assertTrue(LootParser.matchBanner(own).isEmpty());
        }

        @Test
        @DisplayName("the rank-prefix strip is the one the chest broadcasts use")
        void bareNameIsShared() {
            assertEquals("Notch", LineOwnership.bareName(S + "b[MVP" + S + "c+" + S + "b] Notch"));
            assertEquals("Notch", LineOwnership.bareName("Notch"));
            assertEquals("", LineOwnership.bareName(null));
        }
    }
}
