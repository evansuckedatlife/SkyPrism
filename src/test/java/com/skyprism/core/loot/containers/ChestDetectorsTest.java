package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Reward chests: three sources, one GUI, one broadcast, and no double roll")
class ChestDetectorsTest {

    private static final Supplier<String> ME = () -> "Leebys";
    private static final Supplier<String> NOBODY = () -> "";

    private static final String OBSIDIAN =
            "§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Recombobulator 3000 "
                    + "§r§ein their Obsidian Chest§r§e!";
    private static final String PAID =
            "§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Kraken Shard "
                    + "§r§ein their Paid Chest§r§e!";
    private static final String SOMEONE_ELSE =
            "§6§lRARE REWARD! §r§bGrazma §r§efound a §r§6Necron's Handle "
                    + "§r§ein their Bedrock Chest§r§e!";

    @Nested
    @DisplayName("Catacombs reward chest")
    class Dungeon {

        @Test
        @DisplayName("claims its own registry sample")
        void registrySample() {
            DungeonRewardChestDetector detector = new DungeonRewardChestDetector(ME);
            for (String sample
                    : LootSourceRegistry.info(LootSource.DUNGEON_REWARD_CHEST).triggerSamples()) {
                LootEvent event = detector.onChat(sample, 1_000L).orElseThrow(
                        () -> new AssertionError("registry sample not matched: " + sample));
                assertEquals(LootSource.DUNGEON_REWARD_CHEST, event.source());
            }
        }

        @Test
        @DisplayName("the caption names the tier the player actually opened")
        void caption() {
            LootEvent event = new DungeonRewardChestDetector(ME)
                    .onChat(OBSIDIAN, 1_000L).orElseThrow();
            assertEquals("Obsidian Chest", event.subject());
        }

        @Test
        @DisplayName("a party member's chest is not our roll")
        void notOurs() {
            assertEquals(Optional.empty(),
                    new DungeonRewardChestDetector(ME).onChat(SOMEONE_ELSE, 1_000L));
        }

        @Test
        @DisplayName("an unknown local name claims nothing at all")
        void unknownPlayer() {
            assertEquals(Optional.empty(),
                    new DungeonRewardChestDetector(NOBODY).onChat(OBSIDIAN, 1_000L));
        }

        @Test
        @DisplayName("a Kuudra chest belongs to the Kuudra source, not this one")
        void kuudraIsNotOurs() {
            assertEquals(Optional.empty(),
                    new DungeonRewardChestDetector(ME).onChat(PAID, 1_000L));
        }

        @Test
        @DisplayName("every chest title Hypixel spells, long form and broken bare form")
        void titles() {
            DungeonRewardChestDetector detector = new DungeonRewardChestDetector(ME);
            long now = 0L;
            for (String title : ContainerPatterns.DUNGEON_CHEST_TITLES) {
                now += 10_000L;
                LootEvent event = detector.onScreenTitle(title, now).orElseThrow(
                        () -> new AssertionError("title not matched: " + title));
                assertEquals(title, event.subject());
            }
        }

        @Test
        @DisplayName("a Kuudra title is left alone")
        void kuudraTitle() {
            DungeonRewardChestDetector detector = new DungeonRewardChestDetector(ME);
            assertEquals(Optional.empty(), detector.onScreenTitle("Paid Chest", 1_000L));
            assertEquals(Optional.empty(), detector.onScreenTitle("Free Chest Chest", 1_000L));
            assertEquals(Optional.empty(), detector.onScreenTitle("Croesus", 1_000L));
        }

        @Test
        @DisplayName("the title and the broadcast for one chest produce one roll, not two")
        void titleThenBroadcastDoesNotDoubleRoll() {
            DungeonRewardChestDetector detector = new DungeonRewardChestDetector(ME);
            assertTrue(detector.onScreenTitle("Obsidian Chest", 1_000L).isPresent());
            assertEquals(Optional.empty(), detector.onChat(OBSIDIAN, 1_500L),
                    "the broadcast a second later is the same chest");
        }

        @Test
        @DisplayName("but a different tier straight afterwards is a different chest")
        void differentTierIsNotADuplicate() {
            DungeonRewardChestDetector detector = new DungeonRewardChestDetector(ME);
            assertTrue(detector.onScreenTitle("Gold Chest", 1_000L).isPresent());
            assertTrue(detector.onScreenTitle("Diamond Chest", 1_500L).isPresent(),
                    "a Croesus backlog opens a chest every few seconds");
        }

        @Test
        @DisplayName("and the same tier after the window is a new chest")
        void sameTierLaterIsANewChest() {
            DungeonRewardChestDetector detector = new DungeonRewardChestDetector(ME);
            assertTrue(detector.onScreenTitle("Gold Chest", 1_000L).isPresent());
            assertTrue(detector.onScreenTitle(
                    "Gold Chest", 1_000L + ChestBroadcastDetector.DEDUPE_MILLIS).isPresent());
        }
    }

    @Nested
    @DisplayName("Kuudra chest")
    class Kuudra {

        @Test
        @DisplayName("claims its own registry sample and captions the tier")
        void registrySample() {
            KuudraRewardChestDetector detector = new KuudraRewardChestDetector(ME);
            for (String sample
                    : LootSourceRegistry.info(LootSource.KUUDRA_REWARD_CHEST).triggerSamples()) {
                assertEquals(LootSource.KUUDRA_REWARD_CHEST,
                        detector.onChat(sample, 1_000L).orElseThrow().source());
            }
            assertEquals("Paid Chest",
                    new KuudraRewardChestDetector(ME).onChat(PAID, 1_000L).orElseThrow().subject());
        }

        @Test
        @DisplayName("all four spellings of the doubled title are accepted")
        void doubledTitle() {
            KuudraRewardChestDetector detector = new KuudraRewardChestDetector(ME);
            long now = 0L;
            for (String title : ContainerPatterns.KUUDRA_CHEST_TITLES) {
                now += 10_000L;
                assertTrue(detector.onScreenTitle(title, now).isPresent(), title);
            }
        }

        @Test
        @DisplayName("a Catacombs chest is not ours")
        void dungeonIsNotOurs() {
            KuudraRewardChestDetector detector = new KuudraRewardChestDetector(ME);
            assertEquals(Optional.empty(), detector.onChat(OBSIDIAN, 1_000L));
            assertEquals(Optional.empty(), detector.onScreenTitle("Bedrock Chest", 1_000L));
        }
    }

    @Nested
    @DisplayName("Croesus")
    class Croesus {

        @Test
        @DisplayName("claims nothing until the run list has been opened")
        void shutUntilArmed() {
            CroesusChestDetector detector = new CroesusChestDetector(ME);
            assertFalse(detector.isArmed(1_000L));
            assertEquals(Optional.empty(), detector.onChat(OBSIDIAN, 1_000L));
            assertEquals(Optional.empty(), detector.onScreenTitle("Obsidian Chest", 1_000L));
        }

        @Test
        @DisplayName("the run list arms it without itself being a payout")
        void runListIsNotAPayout() {
            CroesusChestDetector detector = new CroesusChestDetector(ME);
            assertEquals(Optional.empty(), detector.onScreenTitle("Croesus", 1_000L));
            assertTrue(detector.isArmed(1_000L));
            assertEquals(Optional.empty(), detector.onScreenTitle("(1/2) Croesus", 2_000L));
            assertTrue(detector.isArmed(2_000L));
        }

        @Test
        @DisplayName("once armed it claims the chest, and its registry sample")
        void claimsWhileArmed() {
            CroesusChestDetector detector = new CroesusChestDetector(ME);
            detector.onScreenTitle("Croesus", 1_000L);
            LootEvent event = detector.onChat(OBSIDIAN, 2_000L).orElseThrow();
            assertEquals(LootSource.CROESUS_CHEST, event.source());
            assertEquals("Obsidian Chest", event.subject());

            CroesusChestDetector second = new CroesusChestDetector(ME);
            second.onScreenTitle("Croesus", 1_000L);
            for (String sample
                    : LootSourceRegistry.info(LootSource.CROESUS_CHEST).triggerSamples()) {
                assertTrue(second.onChat(sample, 2_000L).isPresent(), sample);
            }
        }

        @Test
        @DisplayName("the window lapses, and opening a chest does not renew it")
        void windowLapses() {
            CroesusChestDetector detector = new CroesusChestDetector(ME);
            detector.onScreenTitle("Croesus", 1_000L);
            long later = 1_000L + CroesusChestDetector.ARMED_MILLIS;
            assertFalse(detector.isArmed(later));
            assertEquals(Optional.empty(), detector.onChat(OBSIDIAN, later));
        }

        @Test
        @DisplayName("a Kuudra chest in the Croesus list is left to the Kuudra source")
        void kuudraStaysKuudra() {
            CroesusChestDetector detector = new CroesusChestDetector(ME);
            detector.onScreenTitle("Croesus", 1_000L);
            assertEquals(Optional.empty(), detector.onChat(PAID, 2_000L));
            assertEquals(Optional.empty(), detector.onScreenTitle("Paid Chest", 2_000L));
        }
    }

    @Nested
    @DisplayName("registration order on the real bus")
    class Ordering {

        private LootEventBus busWithContainers() {
            LootEventBus bus = new LootEventBus();
            ContainerDetectors.registerAll(bus, ME);
            bus.updateContext(new GameContext(true, true, "Dungeon Hub", "", "", false, false));
            return bus;
        }

        @Test
        @DisplayName("an in-run chest goes to the Catacombs source when Croesus is cold")
        void catacombsByDefault() {
            LootEvent event = busWithContainers().onChat(OBSIDIAN, 1_000L).orElseThrow();
            assertEquals(LootSource.DUNGEON_REWARD_CHEST, event.source());
        }

        @Test
        @DisplayName("the same line goes to Croesus once its run list has been open")
        void croesusWins() {
            LootEventBus bus = busWithContainers();
            bus.onScreenTitle("Croesus", 1_000L);
            LootEvent event = bus.onChat(OBSIDIAN, 2_000L).orElseThrow();
            assertEquals(LootSource.CROESUS_CHEST, event.source());
        }

        @Test
        @DisplayName("a Kuudra chest is never claimed by either of the other two")
        void kuudraKeepsItsOwn() {
            LootEventBus bus = busWithContainers();
            bus.onScreenTitle("Croesus", 1_000L);
            LootEvent event = bus.onChat(PAID, 2_000L).orElseThrow();
            assertEquals(LootSource.KUUDRA_REWARD_CHEST, event.source());
        }

        @Test
        @DisplayName("one chest, one event, even with both triggers on the bus")
        void oneChestOneEvent() {
            LootEventBus bus = busWithContainers();
            assertTrue(bus.onScreenTitle("Obsidian Chest", 1_000L).isPresent());
            assertEquals(Optional.empty(), bus.onChat(OBSIDIAN, 1_400L));
        }
    }
}
