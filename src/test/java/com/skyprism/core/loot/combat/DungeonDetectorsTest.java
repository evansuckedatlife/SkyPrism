package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Catacombs: the defeat line, Master Mode, and the pair that must roll once")
class DungeonDetectorsTest {

    private static GameContext inDungeon(String area) {
        return new GameContext(true, true, "Catacombs", area, "", true, false);
    }

    @Nested
    @DisplayName("DungeonBossDetector")
    class BossDetector {

        @Test
        @DisplayName("all eight bosses, including the two an article-truncating regex would break")
        void allEightBosses() {
            DungeonBossDetector detector = new DungeonBossDetector();
            for (DungeonBoss boss : DungeonBoss.values()) {
                String line = "                    ☠ Defeated " + boss.displayName()
                        + " in 5m 43s";
                LootEvent event = detector.onChat(line, 0L).orElseThrow(() -> new AssertionError(line));
                assertEquals(LootSource.DUNGEON_BOSS, event.source());
                assertEquals(boss.displayName(), event.subject(), line);
            }
        }

        @Test
        @DisplayName("The Professor and The Watcher keep their article, which \\w+ would eat")
        void articlesSurvive() {
            DungeonBossDetector detector = new DungeonBossDetector();
            assertEquals("The Professor", detector
                    .onChat("     ☠ Defeated The Professor in 12m 3s", 0L)
                    .orElseThrow().subject());
            assertEquals("The Watcher", detector
                    .onChat("     ☠ Defeated The Watcher in 1m 2s", 0L)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("a new record still reads as a defeat")
        void newRecord() {
            assertEquals("Necron", new DungeonBossDetector()
                    .onChat("     ☠ Defeated Necron in 4m 12s (NEW RECORD!)", 0L)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("the floor and the mode reach the caption from the summary header")
        void captionFromHeader() {
            DungeonRunState run = new DungeonRunState();
            DungeonBossDetector boss = new DungeonBossDetector(run);
            DungeonRunCompleteDetector header = new DungeonRunCompleteDetector(run);

            // Hypixel prints the header first; that ordering is what makes this work.
            header.onChat("                Master Mode The Catacombs - Floor VII", 0L);
            assertEquals("Necron (Master Mode Floor VII)", boss
                    .onChat("     ☠ Defeated Necron in 5m 43s", 1L)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("with no header, the sidebar floor carries the caption instead")
        void captionFromSidebar() {
            DungeonBossDetector boss = new DungeonBossDetector();
            boss.gateOpen(inDungeon("The Catacombs (M7)"));
            assertEquals("Necron (Master Mode F7)", boss
                    .onChat("     ☠ Defeated Necron in 5m 43s", 0L)
                    .orElseThrow().subject());

            boss.gateOpen(inDungeon("The Catacombs (F5)"));
            assertEquals("Livid (F5)", boss
                    .onChat("     ☠ Defeated Livid in 5m 43s", 0L)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("with neither, the caption is the bare boss name rather than a guess")
        void captionDegradesHonestly() {
            DungeonBossDetector boss = new DungeonBossDetector();
            boss.gateOpen(inDungeon(""));
            assertEquals("Necron", boss
                    .onChat("     ☠ Defeated Necron in 5m 43s", 0L)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("a name outside the closed table is declined, never captioned")
        void unknownBossDeclined() {
            DungeonBossDetector detector = new DungeonBossDetector();
            assertTrue(detector.onChat("     ☠ Defeated Steve in 5m 43s", 0L).isEmpty());
            assertTrue(detector.onChat("     ☠ Defeated Maxor in 5m 43s", 0L).isEmpty());
        }

        @Test
        @DisplayName("a party message quoting the defeat line is declined")
        void partyInjectionDeclined() {
            assertTrue(new DungeonBossDetector().onChat(
                    "§9Party §8> Steve§f: §r☠ Defeated Necron in 5m 43s", 0L)
                    .isEmpty());
        }

        @Test
        @DisplayName("gated to a dungeon run, so it is free on every other island")
        void gate() {
            DungeonBossDetector detector = new DungeonBossDetector();
            assertTrue(detector.gateOpen(inDungeon("F7")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Dungeon Hub")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Hub")));
            assertFalse(detector.gateOpen(GameContext.UNKNOWN));
        }

        @Test
        @DisplayName("does not claim a drop banner, a slayer line or a DOWN! banner")
        void declinesForeignLines() {
            DungeonBossDetector detector = new DungeonBossDetector();
            String[] foreign = {
                    "§6§lRARE DROP! §r§9Hunk of Blue Ice §r§b(+123% ✯ Magic Find)",
                    "  §r§6§lNICE! SLAYER BOSS SLAIN!",
                    "§f   §r§6§lARACHNE DOWN!",
                    "                Master Mode The Catacombs - Floor VII",
            };
            for (String line : foreign) {
                assertTrue(detector.onChat(line, 0L).isEmpty(), line);
            }
        }
    }

    @Nested
    @DisplayName("DungeonRunCompleteDetector")
    class RunComplete {

        @Test
        @DisplayName("ships on NEVER, because it is the same run as the boss a few lines later")
        void defaultPolicy() {
            assertEquals(RollPolicy.NEVER,
                    LootSourceRegistry.defaultPolicy(LootSource.DUNGEON_RUN_COMPLETE));
            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.DUNGEON_BOSS));
        }

        @Test
        @DisplayName("reads both modes and every floor, plus the Entrance")
        void bothModesEveryFloor() {
            DungeonRunCompleteDetector detector = new DungeonRunCompleteDetector();
            String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII"};
            for (String numeral : numerals) {
                assertEquals("Floor " + numeral, detector
                        .onChat("                The Catacombs - Floor " + numeral, 0L)
                        .orElseThrow().subject());
                assertEquals("Master Mode Floor " + numeral, detector
                        .onChat("          Master Mode The Catacombs - Floor " + numeral, 0L)
                        .orElseThrow().subject());
            }
            assertEquals("Entrance", detector
                    .onChat("                The Catacombs - Entrance", 0L)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("records the run into the shared state even though it ships disarmed")
        void recordsTheRunRegardless() {
            DungeonRunState run = new DungeonRunState();
            assertFalse(run.known());
            new DungeonRunCompleteDetector(run)
                    .onChat("          Master Mode The Catacombs - Floor VII", 0L);
            assertTrue(run.known());
            assertTrue(run.masterMode());
            assertEquals("Master Mode Floor VII", run.describe());

            run.clear();
            assertFalse(run.known());
            assertEquals("", run.describe());
        }

        @Test
        @DisplayName("does not claim the defeat line its sibling owns")
        void doesNotClaimTheDefeat() {
            assertTrue(new DungeonRunCompleteDetector()
                    .onChat("     ☠ Defeated Necron in 5m 43s", 0L)
                    .isEmpty());
        }

        @Test
        @DisplayName("a party message quoting the header is declined")
        void partyInjectionDeclined() {
            assertTrue(new DungeonRunCompleteDetector().onChat(
                    "§9Party §8> Steve§f: §rMaster Mode The Catacombs - Floor VII", 0L)
                    .isEmpty());
        }
    }
}
