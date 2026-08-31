package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Mining procs and Trevor the Trapper")
class MiningAndTrapperTest {

    private static final long NOW = 11L;

    @Nested
    @DisplayName("Pristine gemstones -- enumerated, shipped off")
    class Pristine {

        private final PristineGemstoneDetector detector = new PristineGemstoneDetector();

        @Test
        @DisplayName("parses the gem out of the line, glyph and all")
        void parses() {
            LootEvent event = detector.onChat(GatheringSamples.PRISTINE, NOW).orElseThrow();
            assertEquals(LootSource.MINING_PRISTINE_GEMSTONE, event.source());
            assertEquals("Flawed Jade Gemstone", event.subject());
        }

        @Test
        @DisplayName("works for a different gem, whose glyph is a different symbol")
        void differentGlyph() {
            assertEquals("Flawed Amethyst Gemstone", detector.onChat(
                    "§d§lPRISTINE! §r§fYou found §r§a❈ Flawed Amethyst Gemstone §r§8x16§r§f!", NOW)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("cannot be spoofed")
        void cannotBeSpoofed() {
            assertTrue(detector.onChat("§bBob§f: PRISTINE! You found a Flawed Jade Gemstone", NOW)
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("Compact procs -- enumerated, shipped off")
    class Compact {

        private final CompactProcDetector detector = new CompactProcDetector();

        @Test
        @DisplayName("parses the material rather than pinning it to Hard Stone")
        void parses() {
            assertEquals("Enchanted Hard Stone",
                    detector.onChat(GatheringSamples.COMPACT, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("cannot be spoofed")
        void cannotBeSpoofed() {
            assertTrue(detector.onChat("§bBob§f: COMPACT! You found an Enchanted Hard Stone!", NOW)
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("Goblins -- the spawn, with the drops arriving on the shared banner")
    class Goblins {

        private final GoblinRaidDetector detector = new GoblinRaidDetector();

        @Test
        @DisplayName("both goblins fire, each captioned with itself")
        void both() {
            LootEvent golden = detector.onChat(GatheringSamples.GOBLIN_GOLDEN, NOW).orElseThrow();
            assertEquals(LootSource.MINING_GOBLIN_RAID, golden.source());
            assertEquals("Golden Goblin", golden.subject());
            assertEquals("Diamond Goblin",
                    detector.onChat(GatheringSamples.GOBLIN_DIAMOND, NOW).orElseThrow().subject());
        }

        @Test
        @DisplayName("cannot be spoofed, which matters for a lobby-wide announcement")
        void cannotBeSpoofed() {
            assertTrue(detector.onChat("§bBob§f: A Golden Goblin has spawned!", NOW).isEmpty());
            assertTrue(detector.onChat("§9A Goblin has spawned!", NOW).isEmpty());
        }
    }

    @Nested
    @DisplayName("the rare-drop banner test the trapper rides on")
    class Banner {

        @Test
        @DisplayName("recognises every verified banner word")
        void banners() {
            assertTrue(BannerLines.isRareDropBanner(GatheringSamples.MOB_RARE_DROP));
            assertTrue(BannerLines.isRareDropBanner(GatheringSamples.TRAPPER_DROP));
            assertTrue(BannerLines.isRareDropBanner("§5§lVERY RARE DROP! §r§5Revenant Catalyst"));
            assertTrue(BannerLines.isRareDropBanner("§d§lCRAZY RARE DROP! §r§fPocket Espresso"));
            assertTrue(BannerLines.isRareDropBanner("§6§lPET DROP! §r§6Rat "));
        }

        @Test
        @DisplayName("is not fooled by the Garden's RARE CROP, which differs by one letter")
        void notACrop() {
            assertFalse(BannerLines.isRareDropBanner(GatheringSamples.RARE_CROP));
            assertFalse(BannerLines.isRareDropBanner("§6§lRARE CROP! §aCane Knot §e(§e+139.5)"));
        }

        @Test
        @DisplayName("is anchored, so a player quoting a banner is not a banner")
        void anchored() {
            assertFalse(BannerLines.isRareDropBanner(GatheringSamples.PLAYER_CHAT));
            assertFalse(BannerLines.isRareDropBanner(
                    "§bBob§f: §6§lRARE DROP! §r§9Hunter Ring §r§b(+123%)"));
        }
    }

    @Nested
    @DisplayName("the trapper, whose trigger is the drop and not the kill")
    class Trapper {

        private final TrapperDetector detector = new TrapperDetector();

        @Test
        @DisplayName("the completion line is remembered, not fired on")
        void completionIsNotATrigger() {
            assertTrue(detector.onChat(GatheringSamples.TRAPPER_COMPLETE, NOW).isEmpty());
            assertEquals(NOW, detector.lastHuntCompletedAtMillis());
        }

        @Test
        @DisplayName("the drop banner is what fires, because the policy needs a rarity flag")
        void dropFires() {
            LootEvent event = detector.onChat(GatheringSamples.TRAPPER_DROP, NOW).orElseThrow();
            assertEquals(LootSource.TREVOR_TRAPPER, event.source());
            assertEquals("Trevor's Animal", event.subject());
        }

        @Test
        @DisplayName("fires even when the completion line has not been seen, by design")
        void doesNotRequireACompletion() {
            TrapperDetector fresh = new TrapperDetector();
            assertTrue(fresh.onChat(GatheringSamples.TRAPPER_DROP, NOW).isPresent());
        }

        @Test
        @DisplayName("does not fire on the hunt failing, or on a player quoting a banner")
        void quietOtherwise() {
            assertTrue(detector.onChat(GatheringSamples.TRAPPER_FAILED, NOW).isEmpty());
            assertTrue(detector.onChat(GatheringSamples.PLAYER_CHAT, NOW).isEmpty());
            assertTrue(detector.onChat(GatheringSamples.RARE_CROP, NOW).isEmpty());
        }
    }
}
