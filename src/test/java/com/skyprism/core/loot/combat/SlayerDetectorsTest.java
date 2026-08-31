package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.core.loot.LootSourceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Slayers: the quest, the two kill lines, and the pair that must roll once")
class SlayerDetectorsTest {

    private static final GameContext HUB = GameContext.onIsland("Hub", "Graveyard");

    @Nested
    @DisplayName("SlayerQuest.parse")
    class QuestParsing {

        @Test
        @DisplayName("reads all six bosses at every tier off the sidebar spelling")
        void allSixAllTiers() {
            String[] numerals = {"I", "II", "III", "IV", "V"};
            for (SlayerBossType type : SlayerBossType.values()) {
                for (int tier = 1; tier <= 5; tier++) {
                    String line = type.displayName() + " " + numerals[tier - 1];
                    SlayerQuest quest = SlayerQuest.parse(line).orElseThrow(() -> new AssertionError(line));
                    assertEquals(type, quest.type(), line);
                    assertEquals(tier, quest.tier(), line);
                    assertEquals(line, quest.caption());
                }
            }
        }

        @Test
        @DisplayName("a boss this build does not know still parses, so the roll is never lost")
        void unknownBossStillParses() {
            SlayerQuest quest = SlayerQuest.parse("Cryogenic Whatsit III").orElseThrow();
            assertEquals(null, quest.type());
            assertEquals(3, quest.tier());
            assertEquals("Cryogenic Whatsit III", quest.caption());
        }

        @Test
        @DisplayName("a missing numeral yields an unknown tier that passes every floor")
        void missingNumeral() {
            SlayerQuest quest = SlayerQuest.parse("Revenant Horror").orElseThrow();
            assertEquals(SlayerBossType.REVENANT_HORROR, quest.type());
            assertEquals(SlayerQuest.UNKNOWN_TIER, quest.tier());
            assertTrue(quest.atLeastTier(5),
                    "an unknown tier must not silence a roll -- see SlayerQuest");
        }

        @Test
        @DisplayName("blank and null yield nothing at all")
        void blankYieldsEmpty() {
            assertTrue(SlayerQuest.parse(null).isEmpty());
            assertTrue(SlayerQuest.parse("   ").isEmpty());
        }
    }

    @Nested
    @DisplayName("SlayerQuestState: the tri-state that keeps an unwired install working")
    class QuestState {

        @Test
        @DisplayName("before anything reports, the gate hint is open -- never silently shut")
        void unreportedIsOpen() {
            SlayerQuestState state = new SlayerQuestState();
            assertFalse(state.reported());
            assertFalse(state.active());
            assertTrue(state.mayBeActive(),
                    "an unreported state must leave the detector armed; a shut gate on a bus that "
                            + "only recomputes on context change would never reopen");
        }

        @Test
        @DisplayName("once reported, the gate hint is exact in both directions")
        void reportedIsExact() {
            SlayerQuestState state = new SlayerQuestState();
            state.questEnded();
            assertTrue(state.reported());
            assertFalse(state.mayBeActive());

            state.questStarted("Voidgloom Seraph IV");
            assertTrue(state.mayBeActive());
            assertTrue(state.active());
            assertEquals("Voidgloom Seraph IV", state.captionOr("fallback"));

            state.questEnded();
            assertFalse(state.mayBeActive());
            assertEquals("fallback", state.captionOr("fallback"));
        }

        @Test
        @DisplayName("the change listener fires on start, on change and on end, never on a repeat")
        void listenerFiresOnRealChanges() {
            SlayerQuestState state = new SlayerQuestState();
            int[] calls = {0};
            state.onChange(() -> calls[0]++);

            state.questStarted("Revenant Horror V");
            state.questStarted("Revenant Horror V");
            assertEquals(1, calls[0], "an unchanged report must not churn the bus");

            state.questStarted("Sven Packmaster III");
            assertEquals(2, calls[0]);

            state.questEnded();
            state.questEnded();
            assertEquals(3, calls[0]);
        }

        @Test
        @DisplayName("an unparseable sidebar line is treated as no quest, not as a broken quest")
        void unparseableIsNoQuest() {
            SlayerQuestState state = new SlayerQuestState();
            state.questStarted("   ");
            assertTrue(state.reported());
            assertFalse(state.active());
        }
    }

    @Nested
    @DisplayName("SlayerBossDetector")
    class BossDetector {

        @Test
        @DisplayName("the shipped default is ALWAYS -- a slayer boss is Diana's own cadence")
        void defaultPolicy() {
            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.SLAYER_BOSS));
        }

        @Test
        @DisplayName("the kill line fires, captioned with the sidebar quest")
        void killLineFires() {
            SlayerQuestState state = new SlayerQuestState();
            state.questStarted("Voidgloom Seraph IV");
            SlayerBossDetector detector = new SlayerBossDetector(state);

            LootEvent event = detector
                    .onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!", 1_000L)
                    .orElseThrow();
            assertEquals(LootSource.SLAYER_BOSS, event.source());
            assertEquals("Voidgloom Seraph IV", event.subject());
            assertEquals(1_000L, event.atMillis());
        }

        @Test
        @DisplayName("without a sidebar the caption falls back to the source name, never a guess")
        void captionFallsBack() {
            LootEvent event = new SlayerBossDetector()
                    .onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!", 1L)
                    .orElseThrow();
            assertEquals("Slayer Boss", event.subject());
        }

        @Test
        @DisplayName("the paired QUEST COMPLETE line does not double-roll the same kill")
        void pairRollsOnce() {
            SlayerBossDetector detector = new SlayerBossDetector();
            assertTrue(detector
                    .onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!", 10_000L)
                    .isPresent());
            assertTrue(detector
                    .onChat("  §r§a§lSLAYER QUEST COMPLETE!", 10_040L)
                    .isEmpty(), "one kill, one roll");
        }

        @Test
        @DisplayName("QUEST COMPLETE alone still fires, so a reworded kill line cannot lose the roll")
        void completeAloneStillFires() {
            assertTrue(new SlayerBossDetector()
                    .onChat("  §r§a§lSLAYER QUEST COMPLETE!", 1L)
                    .isPresent());
        }

        @Test
        @DisplayName("the next boss, past the pairing window, rolls again")
        void nextBossRollsAgain() {
            SlayerBossDetector detector = new SlayerBossDetector();
            assertTrue(detector.onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!", 0L)
                    .isPresent());
            assertTrue(detector.onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!",
                    SlayerBossDetector.PAIR_WINDOW_MILLIS).isPresent());
        }

        @Test
        @DisplayName("a tier floor silences the tiers below it and nothing else")
        void tierFloor() {
            SlayerQuestState state = new SlayerQuestState();
            SlayerBossDetector detector = new SlayerBossDetector(state).minimumTier(3);

            state.questStarted("Revenant Horror II");
            assertTrue(detector.onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!", 0L)
                    .isEmpty());

            state.questStarted("Revenant Horror III");
            assertTrue(detector.onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!", 100_000L)
                    .isPresent());
        }

        @Test
        @DisplayName("the gate shuts once a sidebar reports no quest, and not before")
        void gateFollowsTheSidebar() {
            SlayerQuestState state = new SlayerQuestState();
            SlayerBossDetector detector = new SlayerBossDetector(state);
            assertTrue(detector.gateOpen(HUB), "unreported must stay armed");

            state.questEnded();
            assertFalse(detector.gateOpen(HUB));

            state.questStarted("Sven Packmaster V");
            assertTrue(detector.gateOpen(HUB));
        }

        @Test
        @DisplayName("no gate is open outside SkyBlock, whatever the quest state says")
        void shutOutsideSkyBlock() {
            SlayerQuestState state = new SlayerQuestState();
            state.questStarted("Sven Packmaster V");
            assertFalse(new SlayerBossDetector(state).gateOpen(GameContext.UNKNOWN));
        }

        @Test
        @DisplayName("the neighbouring slayer lines are declined -- STARTED and FAILED are not kills")
        void neighbouringLinesDeclined() {
            SlayerBossDetector detector = new SlayerBossDetector();
            String[] notKills = {
                    "  §r§6§lSLAYER QUEST STARTED!",
                    "  §r§c§lSLAYER QUEST FAILED!",
                    "  §r§cYOU COCOONED YOUR SLAYER BOSS",
                    "  SLAYER MINI-BOSS",
                    "§eYou received kill credit for assisting on a slayer miniboss!",
            };
            for (String line : notKills) {
                assertTrue(detector.onChat(line, 0L).isEmpty(), line);
            }
        }

        @Test
        @DisplayName("a party message quoting the kill line cannot spin somebody else's machine")
        void partyInjectionDeclined() {
            SlayerBossDetector detector = new SlayerBossDetector();
            assertTrue(detector.onChat(
                    "§9Party §8> Steve§f: §rNICE! SLAYER BOSS SLAIN!", 0L)
                    .isEmpty());
            assertTrue(detector.onChat(
                    "§7Steve§f: §rSLAYER QUEST COMPLETE!", 0L)
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("SlayerMinibossDetector")
    class MinibossDetector {

        @Test
        @DisplayName("ships on NEVER: same quest as the boss, opposite cadence")
        void defaultPolicy() {
            assertEquals(RollPolicy.NEVER,
                    LootSourceRegistry.defaultPolicy(LootSource.SLAYER_MINIBOSS));
        }

        @Test
        @DisplayName("both verified shapes fire: the prefix and the assist literal")
        void bothShapesFire() {
            SlayerMinibossDetector detector = new SlayerMinibossDetector();
            assertTrue(detector.onChat("  SLAYER MINI-BOSS", 0L).isPresent());
            assertTrue(detector.onChat(
                    "§eYou received kill credit for assisting on a slayer miniboss!", 0L)
                    .isPresent());
        }

        @Test
        @DisplayName("does not claim the boss kill line, which is a different source entirely")
        void doesNotClaimTheBoss() {
            SlayerMinibossDetector detector = new SlayerMinibossDetector();
            Optional<LootEvent> event =
                    detector.onChat("  §r§6§lNICE! SLAYER BOSS SLAIN!", 0L);
            assertTrue(event.isEmpty());
        }

        @Test
        @DisplayName("the prefix is tested on the cleaned line, so a party quote cannot start it")
        void partyInjectionDeclined() {
            assertTrue(new SlayerMinibossDetector().onChat(
                    "§9Party §8> Steve§f: §rSLAYER MINI-BOSS down", 0L)
                    .isEmpty());
        }
    }
}
