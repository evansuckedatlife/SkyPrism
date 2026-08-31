package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The block-header sources: powder chest, structure loot chest, fossil excavation")
class BlockSourceDetectorsTest {

    private static final String LOCKPICKED = "  §r§6§lCHEST LOCKPICKED";
    private static final String COLLECTED = "  §r§5§lLOOT CHEST COLLECTED";
    private static final String EXCAVATED = "  §r§6§lEXCAVATION COMPLETE";
    private static final String EMPTY_DIG = "§cYou didn't find anything. Maybe next time!";

    @Nested
    @DisplayName("Crystal Hollows treasure chest")
    class Powder {

        @Test
        @DisplayName("the header fires the roll, and every registry sample matches")
        void header() {
            PowderChestDetector detector = new PowderChestDetector();
            LootEvent event = detector.onChat(LOCKPICKED, 5L).orElseThrow();
            assertEquals(LootSource.POWDER_CHEST, event.source());
            assertEquals("Treasure Chest", event.subject());
            for (String sample : LootSourceRegistry.info(LootSource.POWDER_CHEST).triggerSamples()) {
                assertTrue(detector.onChat(sample, 5L).isPresent(), sample);
            }
        }

        @Test
        @DisplayName("the structure loot chest header is a different source")
        void notTheOtherChest() {
            assertEquals(Optional.empty(), new PowderChestDetector().onChat(COLLECTED, 5L));
        }

        @Test
        @DisplayName("a player cannot type the header, because they cannot type a section sign")
        void notForgeable() {
            assertEquals(Optional.empty(),
                    new PowderChestDetector().onChat("§bGrazma§f: CHEST LOCKPICKED", 5L));
            assertEquals(Optional.empty(),
                    new PowderChestDetector().onChat("CHEST LOCKPICKED", 5L));
        }

        @Test
        @DisplayName("shut everywhere but the Crystal Hollows")
        void gate() {
            PowderChestDetector detector = new PowderChestDetector();
            assertTrue(detector.gateOpen(GameContext.onIsland("Crystal Hollows")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Dwarven Mines")));
            assertFalse(detector.gateOpen(GameContext.UNKNOWN));
        }
    }

    @Nested
    @DisplayName("structure loot chest")
    class Structure {

        @Test
        @DisplayName("the area names the structure, for free")
        void captionsTheStructure() {
            StructureLootChestDetector detector = new StructureLootChestDetector();
            detector.gateOpen(GameContext.onIsland("Crystal Hollows", "Jungle Temple"));
            LootEvent event = detector.onChat(COLLECTED, 5L).orElseThrow();
            assertEquals(LootSource.LOOT_CHEST, event.source());
            assertEquals("Jungle Temple", event.subject());
        }

        @Test
        @DisplayName("an unknown area falls back to the source name rather than guessing")
        void unknownArea() {
            StructureLootChestDetector detector = new StructureLootChestDetector();
            detector.gateOpen(GameContext.onIsland("Mineshaft"));
            LootEvent event = detector.onChat(COLLECTED, 5L).orElseThrow();
            assertEquals(LootSourceRegistry.displayName(LootSource.LOOT_CHEST), event.subject());
        }

        @Test
        @DisplayName("every registry sample matches, and the lockpicked header does not")
        void samples() {
            StructureLootChestDetector detector = new StructureLootChestDetector();
            detector.gateOpen(GameContext.onIsland("Crystal Hollows"));
            for (String sample : LootSourceRegistry.info(LootSource.LOOT_CHEST).triggerSamples()) {
                assertTrue(detector.onChat(sample, 5L).isPresent(), sample);
            }
            assertEquals(Optional.empty(), detector.onChat(LOCKPICKED, 5L));
        }

        @Test
        @DisplayName("open on both islands the chest exists on, shut elsewhere")
        void gate() {
            StructureLootChestDetector detector = new StructureLootChestDetector();
            assertTrue(detector.gateOpen(GameContext.onIsland("Crystal Hollows")));
            assertTrue(detector.gateOpen(GameContext.onIsland("Mineshaft")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Hub")));
        }

        @Test
        @DisplayName("a shut gate clears the cached area, so a stale structure cannot be captioned")
        void staleArea() {
            StructureLootChestDetector detector = new StructureLootChestDetector();
            detector.gateOpen(GameContext.onIsland("Crystal Hollows", "Jungle Temple"));
            detector.gateOpen(GameContext.onIsland("Hub", "Bazaar"));
            detector.gateOpen(GameContext.onIsland("Mineshaft"));
            assertEquals(LootSourceRegistry.displayName(LootSource.LOOT_CHEST),
                    detector.onChat(COLLECTED, 5L).orElseThrow().subject());
        }
    }

    @Nested
    @DisplayName("fossil excavation")
    class Fossil {

        @Test
        @DisplayName("a completed excavation rolls")
        void complete() {
            LootEvent event = new FossilExcavationDetector().onChat(EXCAVATED, 5L).orElseThrow();
            assertEquals(LootSource.FOSSIL_EXCAVATION, event.source());
        }

        @Test
        @DisplayName("an excavation that found nothing rolls too -- the near miss is the point")
        void emptyStillRolls() {
            assertTrue(new FossilExcavationDetector().onChat(EMPTY_DIG, 5L).isPresent());
        }

        @Test
        @DisplayName("both registry samples match")
        void samples() {
            for (String sample
                    : LootSourceRegistry.info(LootSource.FOSSIL_EXCAVATION).triggerSamples()) {
                assertTrue(new FossilExcavationDetector().onChat(sample, 5L).isPresent(), sample);
            }
        }

        @Test
        @DisplayName("one excavation cannot roll twice")
        void noDoubleRoll() {
            FossilExcavationDetector detector = new FossilExcavationDetector();
            assertTrue(detector.onChat(EXCAVATED, 5L).isPresent());
            assertEquals(Optional.empty(), detector.onChat(EMPTY_DIG, 500L));
            assertTrue(detector.onChat(EXCAVATED, 5L + FossilExcavationDetector.DEDUPE_MILLIS)
                    .isPresent(), "the next excavation is a minute away, not three seconds");
        }

        @Test
        @DisplayName("the other block headers are not excavations")
        void notTheOtherBlocks() {
            FossilExcavationDetector detector = new FossilExcavationDetector();
            assertEquals(Optional.empty(), detector.onChat(LOCKPICKED, 5L));
            assertEquals(Optional.empty(), detector.onChat(COLLECTED, 5L));
            assertEquals(Optional.empty(),
                    detector.onChat("  §r§b§l§r§9§lLAPIS §r§b§lCORPSE LOOT!", 5L));
        }

        @Test
        @DisplayName("gated on Dwarven Mines, because the Glacite Tunnels report as that island")
        void gate() {
            FossilExcavationDetector detector = new FossilExcavationDetector();
            assertTrue(detector.gateOpen(GameContext.onIsland("Dwarven Mines")));
            assertTrue(detector.gateOpen(GameContext.onIsland("Mineshaft")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Glacite Tunnels")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Crystal Hollows")));
        }
    }
}
