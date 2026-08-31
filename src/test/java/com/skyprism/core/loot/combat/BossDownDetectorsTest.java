package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The boss-down banner: one pattern, four sources, and a closed table each")
class BossDownDetectorsTest {

    /** Every DOWN! line in this file, so each detector can be shown to decline the others. */
    private static final List<String> ALL_DOWN_LINES = List.of(
            "§f                              §r§6§lARACHNE DOWN!",
            "§f                      §r§6§lPROTECTOR DRAGON DOWN!",
            "§f                      §r§6§lSUPERIOR DRAGON DOWN!",
            "§f                    §r§6§lENDSTONE PROTECTOR DOWN!",
            "§f                      §r§6§lASHFANG DOWN!",
            "§f                      §r§6§lBARBARIAN DUKE X DOWN!",
            "§c§l                    §r§6§lKUUDRA DOWN!");

    @Nested
    @DisplayName("the shared pattern")
    class SharedPattern {

        @Test
        @DisplayName("reads the name out of every captured padding variant")
        void everyPaddingVariant() {
            assertEquals("ARACHNE", BossDownBanner.subjectOf(
                    "§f                              §r§6§lARACHNE DOWN!"));
            assertEquals("PROTECTOR DRAGON", BossDownBanner.subjectOf(
                    "§r§f                           §r§6§lPROTECTOR DRAGON DOWN!§r"));
            assertEquals("KUUDRA", BossDownBanner.subjectOf(
                    "§c§l                    §r§6§lKUUDRA DOWN!"));
            assertEquals("BARBARIAN DUKE X", BossDownBanner.subjectOf(
                    "§f §r§6§lBARBARIAN DUKE X DOWN!"));
        }

        @Test
        @DisplayName("a player cannot inject one through party, guild or all chat")
        void injectionIsAnchoredOut() {
            String[] injections = {
                    "§9Party §8> Steve§f: §rARACHNE DOWN!",
                    "§9Party §8> §cSTEVE§f: §rARACHNE DOWN!",
                    "§2G §a> §bBOB§f: §rARACHNE DOWN!",
                    "§7Steve§f: §rKUUDRA DOWN!",
                    "From STEVE: ARACHNE DOWN!",
                    "Co-op > STEVE: ASHFANG DOWN!",
            };
            for (String line : injections) {
                assertNull(BossDownBanner.subjectOf(line), line);
            }
        }

        @Test
        @DisplayName("a line that is not a defeat banner costs one indexOf and no matcher")
        void nonBannerRejected() {
            assertNull(BossDownBanner.subjectOf("§6§lRARE DROP! §r§9Judgement Core"));
            assertNull(BossDownBanner.subjectOf(""));
            assertNull(BossDownBanner.subjectOf(null));
        }
    }

    @Nested
    @DisplayName("Ender Dragon")
    class Dragons {

        @Test
        @DisplayName("all seven types are captioned, and nothing else is")
        void allSevenTypes() {
            EnderDragonDetector detector = new EnderDragonDetector();
            String[][] cases = {
                    {"PROTECTOR", "Protector Dragon"},
                    {"OLD", "Old Dragon"},
                    {"UNSTABLE", "Unstable Dragon"},
                    {"YOUNG", "Young Dragon"},
                    {"STRONG", "Strong Dragon"},
                    {"WISE", "Wise Dragon"},
                    {"SUPERIOR", "Superior Dragon"},
            };
            for (String[] each : cases) {
                LootEvent event = detector
                        .onChat("§f                      §r§6§l" + each[0] + " DRAGON DOWN!", 5L)
                        .orElseThrow();
                assertEquals(LootSource.ENDER_DRAGON, event.source());
                assertEquals(each[1], event.subject());
            }
            assertEquals(7, detector.acceptedNames().size());
        }

        @Test
        @DisplayName("an unverified dragon name is declined rather than captioned")
        void unknownDragonDeclined() {
            assertTrue(new EnderDragonDetector()
                    .onChat("§f   §r§6§lANCIENT DRAGON DOWN!", 0L)
                    .isEmpty());
        }

        @Test
        @DisplayName("gated to the Dragon's Nest, not merely to The End")
        void gate() {
            EnderDragonDetector detector = new EnderDragonDetector();
            assertTrue(detector.gateOpen(GameContext.onIsland("The End", "Dragon's Nest")));
            assertFalse(detector.gateOpen(GameContext.onIsland("The End", "Zealot Bruiser Hideout")));
            assertFalse(detector.gateOpen(GameContext.onIsland("Crimson Isle", "Dragon's Nest")));
        }
    }

    @Nested
    @DisplayName("Endstone Protector, Arachne, Crimson minibosses, Kuudra")
    class TheRest {

        @Test
        @DisplayName("each fires on its own banner")
        void eachFiresOnItsOwn() {
            assertEquals("Endstone Protector", new EndstoneProtectorDetector()
                    .onChat("§f                    §r§6§lENDSTONE PROTECTOR DOWN!", 0L)
                    .orElseThrow().subject());
            assertEquals("Arachne", new ArachneDetector()
                    .onChat("§f                              §r§6§lARACHNE DOWN!", 0L)
                    .orElseThrow().subject());
            assertEquals("Ashfang", new CrimsonMinibossDetector()
                    .onChat("§f                      §r§6§lASHFANG DOWN!", 0L)
                    .orElseThrow().subject());
            assertEquals("Kuudra", new KuudraDetector()
                    .onChat("§c§l                    §r§6§lKUUDRA DOWN!", 0L)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("all five Crimson minibosses, and only those five")
        void allFiveMinibosses() {
            CrimsonMinibossDetector detector = new CrimsonMinibossDetector();
            String[] names = {"BLADESOUL", "MAGE OUTLAW", "BARBARIAN DUKE X", "ASHFANG",
                    "MAGMA BOSS"};
            for (String name : names) {
                assertTrue(detector.onChat("§f  §r§6§l" + name + " DOWN!", 0L).isPresent(), name);
            }
            assertEquals(5, detector.acceptedNames().size());
            assertTrue(detector.onChat("§f  §r§6§lMAGMA CUBE BOSS DOWN!", 0L).isEmpty());
        }

        @Test
        @DisplayName("Kuudra captions the tier from the sidebar, and never invents one")
        void kuudraTier() {
            KuudraDetector detector = new KuudraDetector();
            String line = "§c§l                    §r§6§lKUUDRA DOWN!";

            assertTrue(detector.gateOpen(GameContext.onIsland("Kuudra")));
            assertEquals("", detector.tierName());
            assertEquals("Kuudra", detector.onChat(line, 0L).orElseThrow().subject());

            detector.gateOpen(GameContext.onIsland("Kuudra", "§7⏣ §cKuudra's Hollow §8(T5)"));
            assertEquals("Infernal", detector.tierName());
            assertEquals("Infernal Kuudra", detector.onChat(line, 0L).orElseThrow().subject());

            detector.gateOpen(GameContext.onIsland("Kuudra", "Kuudra's Hollow (T1)"));
            assertEquals("Basic", detector.tierName());
        }

        @Test
        @DisplayName("island gates keep the family apart even before the name table does")
        void islandGates() {
            assertTrue(new ArachneDetector().gateOpen(GameContext.onIsland("Spider's Den")));
            assertFalse(new ArachneDetector().gateOpen(GameContext.onIsland("The End")));
            assertTrue(new CrimsonMinibossDetector()
                    .gateOpen(GameContext.onIsland("Crimson Isle")));
            assertFalse(new CrimsonMinibossDetector().gateOpen(GameContext.onIsland("Kuudra")));
            assertTrue(new KuudraDetector().gateOpen(GameContext.onIsland("Kuudra")));
            assertFalse(new KuudraDetector().gateOpen(GameContext.onIsland("Crimson Isle")));
        }
    }

    @Nested
    @DisplayName("cross-source: no two detectors in the family claim one line")
    class CrossSource {

        @Test
        @DisplayName("each detector claims exactly the lines in its own table")
        void exactlyOneClaimant() {
            record Case(BossDownDetector detector, int expected) {
            }
            List<Case> cases = List.of(
                    new Case(new ArachneDetector(), 1),
                    new Case(new EnderDragonDetector(), 2),
                    new Case(new EndstoneProtectorDetector(), 1),
                    new Case(new CrimsonMinibossDetector(), 2),
                    new Case(new KuudraDetector(), 1));

            for (Case each : cases) {
                int claimed = 0;
                for (String line : ALL_DOWN_LINES) {
                    if (each.detector().onChat(line, 0L).isPresent()) {
                        claimed++;
                    }
                }
                assertEquals(each.expected(), claimed,
                        each.detector().source() + " claimed the wrong number of DOWN! lines");
            }
        }

        @Test
        @DisplayName("no DOWN! detector claims a drop banner, a slayer line or a dungeon defeat")
        void declinesOtherSourcesEntirely() {
            List<String> foreign = List.of(
                    "§6§lRARE DROP! §r§9Judgement Core §r§b(+§r§b168% §r§b✯ Magic Find§r§b)",
                    "§6§lPET DROP! §r§6Rat",
                    "  §r§6§lNICE! SLAYER BOSS SLAIN!",
                    "                    ☠ Defeated Necron in 5m 43s",
                    "                Master Mode The Catacombs - Floor VII",
                    "A Vanquisher is spawning nearby!",
                    "§c§lBEWARE - Bladesoul Is Spawning.");
            List<BossDownDetector> detectors = List.of(
                    new ArachneDetector(), new EnderDragonDetector(),
                    new EndstoneProtectorDetector(), new CrimsonMinibossDetector(),
                    new KuudraDetector());
            for (BossDownDetector detector : detectors) {
                for (String line : foreign) {
                    assertTrue(detector.onChat(line, 0L).isEmpty(),
                            detector.source() + " must not claim: " + line);
                }
            }
        }
    }
}
