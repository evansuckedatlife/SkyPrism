package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Fishing: trophy fish, the Golden Fish, and treasure catches")
class FishingDetectorsTest {

    private static final long NOW = 42L;

    @Nested
    @DisplayName("trophy fish, split at the detector rather than at the policy")
    class Trophy {

        private final TrophyFishDetector rare = TrophyFishDetector.rare();
        private final TrophyFishDetector ordinary = TrophyFishDetector.ordinary();

        @Test
        @DisplayName("Gold fires the rare source, captioned with the fish and its tier")
        void gold() {
            LootEvent event = rare.onChat(GatheringSamples.TROPHY_GOLD, NOW).orElseThrow();
            assertEquals(LootSource.FISHING_TROPHY_FISH_RARE, event.source());
            assertEquals("Lavahorse (Gold)", event.subject());
        }

        @Test
        @DisplayName("Diamond fires the rare source")
        void diamond() {
            LootEvent event = rare.onChat(GatheringSamples.TROPHY_DIAMOND_BY_ANALOGY, NOW)
                    .orElseThrow();
            assertEquals("Vanille (Diamond)", event.subject());
        }

        @Test
        @DisplayName("Bronze and Silver do not, which is what makes ALWAYS safe on the rare source")
        void rareIgnoresTheCommonTiers() {
            assertTrue(rare.onChat(GatheringSamples.TROPHY_BRONZE, NOW).isEmpty());
            assertTrue(rare.onChat(GatheringSamples.TROPHY_SILVER, NOW).isEmpty());
        }

        @Test
        @DisplayName("the ordinary detector takes exactly the other two tiers")
        void ordinaryTakesTheRest() {
            assertEquals("Soul Fish (Bronze)",
                    ordinary.onChat(GatheringSamples.TROPHY_BRONZE, NOW).orElseThrow().subject());
            assertEquals("Golden Fish (Silver)",
                    ordinary.onChat(GatheringSamples.TROPHY_SILVER, NOW).orElseThrow().subject());
            assertTrue(ordinary.onChat(GatheringSamples.TROPHY_GOLD, NOW).isEmpty());
            assertTrue(ordinary.onChat(GatheringSamples.TROPHY_DIAMOND_BY_ANALOGY, NOW).isEmpty());
        }

        @Test
        @DisplayName("matches with the rarity glyph replaced, because the glyph is not anchored on")
        void iconAgnostic() {
            assertTrue(rare.onChat(GatheringSamples.TROPHY_GOLD_ICON_REPLACED, NOW).isPresent());
        }

        @Test
        @DisplayName("an undiscovered fish prints obfuscated, and the code is stripped not shown")
        void obfuscatedName() {
            String line = "§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§9§kLavahorse §r§6§lGOLD§r§f!";
            assertEquals("Lavahorse (Gold)", rare.onChat(line, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("a player quoting the banner cannot spin anybody's machine")
        void cannotBeSpoofed() {
            String spoof = "§bBob§f: §6§lTROPHY FISH! §r§fYou caught a §r§9Lavahorse §r§6§lGOLD§r§f!";
            assertTrue(rare.onChat(spoof, NOW).isEmpty());
            assertTrue(ordinary.onChat(spoof, NOW).isEmpty());
        }

        @Test
        @DisplayName("a treasure catch is not a trophy fish, though both say 'You caught'")
        void notATreasureCatch() {
            assertTrue(rare.onChat(GatheringSamples.CATCH_PET, NOW).isEmpty());
            assertTrue(ordinary.onChat(GatheringSamples.CATCH_PET, NOW).isEmpty());
        }
    }

    @Nested
    @DisplayName("the Golden Fish, which must fire once and not four times")
    class GoldenFish {

        private final GoldenFishDetector detector = new GoldenFishDetector();

        @Test
        @DisplayName("fires on the spawn")
        void spawn() {
            LootEvent event = detector.onChat(GatheringSamples.GOLDEN_FISH_SPAWN, NOW).orElseThrow();
            assertEquals(LootSource.FISHING_GOLDEN_FISH, event.source());
            assertEquals("Golden Fish", event.subject());
        }

        @Test
        @DisplayName("stays silent for the other three lines of the same fish's story")
        void oneFishOneRoll() {
            assertTrue(detector.onChat(GatheringSamples.GOLDEN_FISH_WEAK, NOW).isEmpty());
            assertTrue(detector.onChat(GatheringSamples.GOLDEN_FISH_DESPAWN, NOW).isEmpty());
            assertTrue(detector.onChat(
                    "§9The §r§6Golden Fish §r§9escapes your hook but looks weakened.", NOW).isEmpty());
        }

        @Test
        @DisplayName("does not claim the Golden Fish trophy catch, which is a different source")
        void notTheTrophyLine() {
            assertTrue(detector.onChat(GatheringSamples.TROPHY_SILVER, NOW).isEmpty());
        }

        @Test
        @DisplayName("cannot be spoofed")
        void cannotBeSpoofed() {
            assertTrue(detector.onChat(
                    "§bBob§f: You spot a Golden Fish surface from beneath the lava!", NOW).isEmpty());
        }
    }

    @Nested
    @DisplayName("treasure catches, whose caption is what the jackpot list has to match")
    class TreasureCatch {

        private final TreasureCatchDetector detector = new TreasureCatchDetector();

        @Test
        @DisplayName("coins are captioned as coins")
        void coins() {
            LootEvent event = detector.onChat(GatheringSamples.CATCH_COINS, NOW).orElseThrow();
            assertEquals(LootSource.FISHING_TREASURE, event.source());
            assertEquals("36,064 Coins", event.subject());
        }

        @Test
        @DisplayName("a fished pet is captioned with the pet, which is what the jackpot list holds")
        void pet() {
            assertEquals("Squid",
                    detector.onChat(GatheringSamples.CATCH_PET, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("both shard forms name the shard")
        void shards() {
            assertEquals("Water Snake Shard",
                    detector.onChat(GatheringSamples.CATCH_SHARD_STACKED, NOW).orElseThrow().subject());
            assertEquals("Water Snake Shard",
                    detector.onChat(GatheringSamples.CATCH_SHARD_SINGLE, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("the bait form says 'found' and ends in a full stop, and still parses")
        void bait() {
            assertEquals("Fish Bait",
                    detector.onChat(GatheringSamples.CATCH_BAIT, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("matches with the treasure glyph replaced")
        void iconAgnostic() {
            assertTrue(detector.onChat(GatheringSamples.CATCH_COINS_ICON_REPLACED, NOW).isPresent());
        }

        @Test
        @DisplayName("a player quoting a catch cannot match, because a name has letters in it")
        void cannotBeSpoofed() {
            assertTrue(detector.onChat("§bBob§f: GOOD CATCH! You caught 36,064 Coins!", NOW).isEmpty());
        }

        @Test
        @DisplayName("does not claim a trophy fish or any other source's line")
        void noCrossClaims() {
            for (String line : GatheringSamples.ALL_LINES) {
                if (line.indexOf("CATCH!") >= 0) {
                    continue;
                }
                assertFalse(detector.onChat(line, NOW).isPresent(), "claimed: " + line);
            }
        }
    }
}
