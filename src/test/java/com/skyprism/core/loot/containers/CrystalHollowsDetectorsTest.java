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

@DisplayName("The Crystal Nucleus and the Mines of Divan")
class CrystalHollowsDetectorsTest {

    private static final String CRYSTAL_ONE = "§f    §r§5§l✦ CRYSTAL FOUND §r§7(1§r§7/5§r§7)";
    private static final String CRYSTAL_FIVE = "§f    §r§5§l✦ CRYSTAL FOUND §r§7(5§r§7/5§r§7)";
    private static final String NUCLEUS_DONE = "§7Pick it up near the §r§5Nucleus Vault§r§7!";
    private static final String SCAVENGED =
            "§aYou found §r§cScavenged Diamond Axe §r§awith your §r§cMetal Detector§r§a!";
    private static final String GEMSTONE =
            "§aYou found §r§a☘ Flawed Jade Gemstone §r§8x2 §r§awith your "
                    + "§r§cMetal Detector§r§a!";

    @Nested
    @DisplayName("Crystal Nucleus run")
    class Nucleus {

        @Test
        @DisplayName("the completion rolls; the five progress lines do not")
        void onlyTheCompletionRolls() {
            CrystalNucleusDetector detector = new CrystalNucleusDetector();
            assertEquals(Optional.empty(), detector.onChat(CRYSTAL_ONE, 1L));
            assertEquals(Optional.empty(), detector.onChat(CRYSTAL_FIVE, 2L));
            LootEvent event = detector.onChat(NUCLEUS_DONE, 3L).orElseThrow();
            assertEquals(LootSource.CRYSTAL_NUCLEUS_RUN, event.source());
        }

        @Test
        @DisplayName("the progress lines are read, so the caption can carry the count")
        void countedCaption() {
            CrystalNucleusDetector detector = new CrystalNucleusDetector();
            detector.onChat(CRYSTAL_FIVE, 1L);
            assertEquals(5, detector.crystalsFound());
            assertEquals("Crystal Nucleus (5/5)",
                    detector.onChat(NUCLEUS_DONE, 2L).orElseThrow().subject());
        }

        @Test
        @DisplayName("the count resets, so the next run does not inherit the last one's caption")
        void countResets() {
            CrystalNucleusDetector detector = new CrystalNucleusDetector();
            detector.onChat(CRYSTAL_FIVE, 1L);
            detector.onChat(NUCLEUS_DONE, 2L);
            assertEquals(0, detector.crystalsFound());
            assertEquals("Crystal Nucleus Run",
                    detector.onChat(NUCLEUS_DONE, 3L).orElseThrow().subject());
        }

        @Test
        @DisplayName("both registry samples are understood -- one as a trigger, one as progress")
        void samples() {
            CrystalNucleusDetector detector = new CrystalNucleusDetector();
            int matched = 0;
            for (String sample
                    : LootSourceRegistry.info(LootSource.CRYSTAL_NUCLEUS_RUN).triggerSamples()) {
                if (detector.onChat(sample, 1L).isPresent()) {
                    matched++;
                }
            }
            assertEquals(1, matched, "exactly the completion line rolls");
            // The registry lists the completion first and the progress line second, so a count of
            // one here proves the progress pattern's private-use glyph really is the one Hypixel
            // sends -- a wrong codepoint would leave this silently at zero.
            assertEquals(1, detector.crystalsFound(),
                    "the CRYSTAL FOUND sample must be understood, not merely ignored");
        }

        @Test
        @DisplayName("neither chest header is a Nucleus run")
        void notTheChests() {
            CrystalNucleusDetector detector = new CrystalNucleusDetector();
            assertEquals(Optional.empty(), detector.onChat("  §r§6§lCHEST LOCKPICKED", 1L));
            assertEquals(Optional.empty(), detector.onChat("  §r§5§lLOOT CHEST COLLECTED", 1L));
            assertEquals(Optional.empty(), detector.onChat(
                    "§bGrazma§f: Pick it up near the Nucleus Vault!", 1L));
        }

        @Test
        @DisplayName("shut outside the Crystal Hollows")
        void gate() {
            CrystalNucleusDetector detector = new CrystalNucleusDetector();
            assertTrue(detector.gateOpen(GameContext.onIsland("Crystal Hollows")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Dwarven Mines")));
        }
    }

    @Nested
    @DisplayName("Metal Detector")
    class MetalDetector {

        @Test
        @DisplayName("the found item is the caption, verbatim")
        void toolCaption() {
            LootEvent event = new MetalDetectorScavengeDetector()
                    .onChat(SCAVENGED, 1L).orElseThrow();
            assertEquals(LootSource.METAL_DETECTOR_SCAVENGE, event.source());
            assertEquals("Scavenged Diamond Axe", event.subject());
        }

        @Test
        @DisplayName("the count and the tier glyph are stripped out of the caption")
        void gemstoneCaption() {
            assertEquals("Flawed Jade Gemstone", new MetalDetectorScavengeDetector()
                    .onChat(GEMSTONE, 1L).orElseThrow().subject());
        }

        @Test
        @DisplayName("the caption is exactly what a jackpot list is written in")
        void subjectMatchesTheJackpotList() {
            LootEvent event = new MetalDetectorScavengeDetector()
                    .onChat(SCAVENGED, 1L).orElseThrow();
            assertTrue(LootSourceRegistry.info(LootSource.METAL_DETECTOR_SCAVENGE)
                            .jackpotItems().contains(event.subject()),
                    "a policy layer answers ON_JACKPOT_ITEM_ONLY straight off the subject");
        }

        @Test
        @DisplayName("the registry sample matches")
        void sample() {
            MetalDetectorScavengeDetector detector = new MetalDetectorScavengeDetector();
            for (String sample : LootSourceRegistry
                    .info(LootSource.METAL_DETECTOR_SCAVENGE).triggerSamples()) {
                assertTrue(detector.onChat(sample, 1L).isPresent(), sample);
            }
        }

        @Test
        @DisplayName("a player mentioning a metal detector is not a dig")
        void notForgeable() {
            MetalDetectorScavengeDetector detector = new MetalDetectorScavengeDetector();
            assertEquals(Optional.empty(),
                    detector.onChat("§bGrazma§f: You found a Metal Detector!", 1L));
            assertEquals(Optional.empty(), detector.onChat(
                    "§aYou found §r§cScavenged Diamond Axe §r§awith your §r§cPickaxe§r§a!", 1L));
        }
    }
}
