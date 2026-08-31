package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Garden: rare crops, pest drops, Crop Fever and visitors")
class GardenDetectorsTest {

    private static final long NOW = 5L;

    @Nested
    @DisplayName("rare crops, where one banner is a prefix of the other")
    class Crops {

        private final RareCropDetector rare = RareCropDetector.rare();
        private final RareCropDetector veryRare = RareCropDetector.veryRare();

        @Test
        @DisplayName("a rare crop is captioned with the crop, not with the fortune bracket")
        void rareCrop() {
            LootEvent event = rare.onChat(GatheringSamples.RARE_CROP, NOW).orElseThrow();
            assertEquals(LootSource.GARDEN_RARE_CROP, event.source());
            assertEquals("Cropie", event.subject());
        }

        @Test
        @DisplayName("'(automatically donated)' is not part of the crop name either")
        void donated() {
            assertEquals("Seasoning",
                    rare.onChat(GatheringSamples.RARE_CROP_DONATED, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("the VERY tier goes to its own source and never to the ordinary one")
        void veryRareCrop() {
            LootEvent event = veryRare.onChat(GatheringSamples.VERY_RARE_CROP, NOW).orElseThrow();
            assertEquals(LootSource.GARDEN_VERY_RARE_CROP, event.source());
            assertEquals("Burrowing Spores", event.subject());
            assertTrue(rare.onChat(GatheringSamples.VERY_RARE_CROP, NOW).isEmpty(),
                    "the ordinary detector claimed a VERY RARE CROP line");
        }

        @Test
        @DisplayName("and the ordinary tier never goes to the VERY source")
        void veryRareIgnoresOrdinary() {
            assertTrue(veryRare.onChat(GatheringSamples.RARE_CROP, NOW).isEmpty());
        }

        @Test
        @DisplayName("a coloured pest-dropped crop parses too, because matching is on clean text")
        void colouredForm() {
            assertEquals("Cane Knot",
                    rare.onChat("§6§lRARE CROP! §aCane Knot §e(§e+139.5)", NOW)
                            .orElseThrow().subject());
        }

        @Test
        @DisplayName("an unknown crop name is refused rather than captioned with a sentence tail")
        void unknownCrop() {
            assertTrue(rare.onChat("RARE CROP! Nonexistent Vegetable (+97)", NOW).isEmpty());
        }

        @Test
        @DisplayName("RARE DROP is not RARE CROP, which is one letter and two sources apart")
        void dropIsNotCrop() {
            assertTrue(rare.onChat(GatheringSamples.PEST_DROP, NOW).isEmpty());
            assertTrue(rare.onChat(GatheringSamples.MOB_RARE_DROP, NOW).isEmpty());
            assertTrue(rare.onChat(GatheringSamples.CROP_FEVER_DROP, NOW).isEmpty());
        }

        @Test
        @DisplayName("cannot be spoofed")
        void cannotBeSpoofed() {
            assertTrue(rare.onChat(GatheringSamples.PLAYER_CHAT, NOW).isEmpty());
        }

        @Test
        @DisplayName("a name only matches on a word boundary, so 'Cropiexyz' is not a Cropie")
        void wordBoundary() {
            assertEquals("Cropie", RareCrops.startingWith("Cropie").orElseThrow());
            assertEquals("Cropie", RareCrops.startingWith("Cropie (+97)").orElseThrow());
            assertTrue(RareCrops.startingWith("Cropiexyz").isEmpty());
            assertTrue(RareCrops.startingWith("").isEmpty());
            assertTrue(RareCrops.startingWith(null).isEmpty());
        }

        @Test
        @DisplayName("the longest name wins, so a shorter one cannot shadow it")
        void longestWins() {
            assertEquals("Salted Sunflower Seeds",
                    RareCrops.startingWith("Salted Sunflower Seeds (+115)").orElseThrow());
        }
    }

    @Nested
    @DisplayName("pest drops, told apart by the bracket at the end")
    class Pests {

        private final PestDropDetector detector = new PestDropDetector();

        @Test
        @DisplayName("a stacked rare drop is captioned with the item and not its count")
        void rareDrop() {
            LootEvent event = detector.onChat(GatheringSamples.PEST_DROP, NOW).orElseThrow();
            assertEquals(LootSource.GARDEN_PEST_DROP, event.source());
            assertEquals("Mutant Nether Wart", event.subject());
        }

        @Test
        @DisplayName("a pest pet drop is the same shape and the same source")
        void petDrop() {
            assertEquals("Slug",
                    detector.onChat(GatheringSamples.PEST_PET_DROP, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("the Cocoaleech vinyl, whose bracket holds a word rather than a number")
        void vinyl() {
            assertEquals("Not Just a Pest Vinyl",
                    detector.onChat(GatheringSamples.PEST_VINYL, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("an ordinary magic-find rare drop is NOT a pest drop")
        void notAMobDrop() {
            assertTrue(detector.onChat(GatheringSamples.MOB_RARE_DROP, NOW).isEmpty());
            assertTrue(detector.onChat(GatheringSamples.TRAPPER_DROP, NOW).isEmpty());
        }

        @Test
        @DisplayName("and a RARE CROP line is not either -- the reference mod's own negative test")
        void notARareCrop() {
            assertTrue(detector.onChat("§6§lRARE CROP! §aCane Knot §e(§e+139.5)", NOW).isEmpty());
        }

        @Test
        @DisplayName("nor is a Diana treasure dig, which shares the banner word")
        void notADianaDig() {
            assertTrue(detector.onChat(GatheringSamples.DIANA_TREASURE, NOW).isEmpty());
        }
    }

    @Nested
    @DisplayName("Crop Fever: one roll on the start, none on the stream inside it")
    class CropFever {

        private final CropFeverDetector detector = new CropFeverDetector();

        @Test
        @DisplayName("fires on the fever starting")
        void start() {
            LootEvent event = detector.onChat(GatheringSamples.CROP_FEVER_START, NOW).orElseThrow();
            assertEquals(LootSource.GARDEN_CROP_FEVER, event.source());
        }

        @Test
        @DisplayName("does not fire on the fever ending")
        void end() {
            assertTrue(detector.onChat(GatheringSamples.CROP_FEVER_END, NOW).isEmpty());
        }

        @Test
        @DisplayName("does not fire on the drops inside the window, which would strobe")
        void dropsDoNotFire() {
            assertTrue(detector.onChat(GatheringSamples.CROP_FEVER_DROP, NOW).isEmpty());
        }

        @Test
        @DisplayName("but does parse those drops for whoever collects the loot")
        void dropsAreStillParsed() {
            CropFeverDetector.FeverDrop drop =
                    CropFeverDetector.feverDrop(GatheringSamples.CROP_FEVER_DROP).orElseThrow();
            assertEquals("RARE DROP", drop.rarity());
            assertEquals(48, drop.amount());
            assertEquals("Enchanted Melon Slice", drop.crop());
        }

        @Test
        @DisplayName("including the tier this window is the only place in the game to use")
        void prayToRngesus() {
            CropFeverDetector.FeverDrop drop = CropFeverDetector.feverDrop(
                    "PRAY TO RNGESUS DROP! You dropped 96x Enchanted Melon Slice!").orElseThrow();
            assertEquals("PRAY TO RNGESUS DROP", drop.rarity());
        }

        @Test
        @DisplayName("a count too large for an int is refused, not saturated onto a reel")
        void absurdCount() {
            assertTrue(CropFeverDetector.feverDrop(
                    "RARE DROP! You dropped 99999999999x Enchanted Melon Slice!").isEmpty());
        }
    }

    @Nested
    @DisplayName("visitors, where the rarity filter lives in the detector")
    class Visitors {

        private final GardenVisitorDetector detector = new GardenVisitorDetector();

        @Test
        @DisplayName("a legendary visitor fires, captioned with name and rarity")
        void legendary() {
            LootEvent event = detector.onChat(GatheringSamples.VISITOR_LEGENDARY, NOW).orElseThrow();
            assertEquals(LootSource.GARDEN_VISITOR_RARE, event.source());
            assertEquals("Sirius (Legendary)", event.subject());
        }

        @Test
        @DisplayName("so does a special one")
        void special() {
            assertEquals("Spaceman (Special)",
                    detector.onChat(GatheringSamples.VISITOR_SPECIAL, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("an uncommon one does not, which is what keeps ALWAYS liveable in the Garden")
        void uncommon() {
            assertTrue(detector.onChat(GatheringSamples.VISITOR_UNCOMMON, NOW).isEmpty());
        }

        @Test
        @DisplayName("claims no other source's line")
        void noCrossClaims() {
            for (String line : GatheringSamples.ALL_LINES) {
                if (line.indexOf("OFFER ACCEPTED") >= 0) {
                    continue;
                }
                assertFalse(detector.onChat(line, NOW).isPresent(), "claimed: " + line);
            }
        }
    }
}
