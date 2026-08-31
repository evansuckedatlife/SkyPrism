package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Foraging: Galatea tree gifts, the bonus sub-gift, and phantoms")
class ForagingTreeTest {

    private static final long NOW = 7L;

    @Nested
    @DisplayName("the two headers, which must never claim each other")
    class Headers {

        private final TreeGiftDetector gift = TreeGiftDetector.gift();
        private final TreeGiftDetector bonus = TreeGiftDetector.bonus();

        @Test
        @DisplayName("the gift header fires the gift source")
        void giftHeader() {
            LootEvent event = gift.onChat(GatheringSamples.TREE_GIFT_HEADER, NOW).orElseThrow();
            assertEquals(LootSource.FORAGING_TREE_GIFT, event.source());
            assertEquals("Tree Gift", event.subject());
        }

        @Test
        @DisplayName("the bonus header fires the bonus source")
        void bonusHeader() {
            LootEvent event = bonus.onChat(GatheringSamples.TREE_BONUS_HEADER, NOW).orElseThrow();
            assertEquals(LootSource.FORAGING_TREE_BONUS_GIFT, event.source());
        }

        @Test
        @DisplayName("neither claims the other's header, in either direction")
        void mutuallyExclusive() {
            assertTrue(gift.onChat(GatheringSamples.TREE_BONUS_HEADER, NOW).isEmpty());
            assertTrue(bonus.onChat(GatheringSamples.TREE_GIFT_HEADER, NOW).isEmpty());
        }

        @Test
        @DisplayName("neither fires on the block's body, which would double-roll one gift")
        void bodyDoesNotFire() {
            for (String line : new String[]{GatheringSamples.TREE_CONTRIBUTION,
                    GatheringSamples.TREE_BONUS_COMMON, GatheringSamples.TREE_BONUS_RARE,
                    GatheringSamples.TREE_BONUS_BOOK, GatheringSamples.TREE_PHANTOM}) {
                assertTrue(gift.onChat(line, NOW).isEmpty(), "gift claimed: " + line);
                assertTrue(bonus.onChat(line, NOW).isEmpty(), "bonus claimed: " + line);
            }
        }

        @Test
        @DisplayName("cannot be spoofed by a player typing the header")
        void cannotBeSpoofed() {
            assertTrue(gift.onChat("§bBob§f: TREE GIFT", NOW).isEmpty());
            assertTrue(bonus.onChat("§bBob§f: BONUS GIFT", NOW).isEmpty());
        }
    }

    @Nested
    @DisplayName("the reward lines, parsed for the odds Hypixel prints on them")
    class Rewards {

        @Test
        @DisplayName("a common bonus carries its 20% and is not a jackpot")
        void commonBonus() {
            TreeGiftLines.BonusReward reward =
                    TreeGiftLines.bonusReward(GatheringSamples.TREE_BONUS_COMMON).orElseThrow();
            assertEquals("Stretching Sticks", reward.item());
            assertEquals(20.0d, reward.percentage());
            assertFalse(TreeGiftLines.isJackpotOdds(reward.percentage()));
        }

        @Test
        @DisplayName("a rare bonus carries its 0.05% and is a jackpot, with no item list involved")
        void rareBonus() {
            TreeGiftLines.BonusReward reward =
                    TreeGiftLines.bonusReward(GatheringSamples.TREE_BONUS_RARE).orElseThrow();
            assertEquals("Tree the Fish", reward.item());
            assertEquals(0.05d, reward.percentage());
            assertTrue(TreeGiftLines.isJackpotOdds(reward.percentage()));
        }

        @Test
        @DisplayName("an enchanted book keeps the enchantment, which is inside the brackets")
        void enchantedBook() {
            TreeGiftLines.BonusReward reward =
                    TreeGiftLines.bonusReward(GatheringSamples.TREE_BONUS_BOOK).orElseThrow();
            assertEquals("Enchanted Book (First Impression I)", reward.item());
            assertTrue(TreeGiftLines.isJackpotOdds(reward.percentage()));
        }

        @Test
        @DisplayName("the phantom line is not a reward line -- the reference mod's own negative test")
        void phantomIsNotAReward() {
            assertTrue(TreeGiftLines.bonusReward(GatheringSamples.TREE_PHANTOM).isEmpty());
        }

        @Test
        @DisplayName("the contribution line yields the tree type and nothing else does")
        void treeType() {
            assertEquals("Fig",
                    TreeGiftLines.treeType(GatheringSamples.TREE_CONTRIBUTION).orElseThrow());
            assertTrue(TreeGiftLines.treeType(GatheringSamples.TREE_GIFT_HEADER).isEmpty());
        }

        @Test
        @DisplayName("the jackpot threshold is a strict cut at one percent")
        void threshold() {
            assertTrue(TreeGiftLines.isJackpotOdds(0.99d));
            assertFalse(TreeGiftLines.isJackpotOdds(1.0d));
            assertFalse(TreeGiftLines.isJackpotOdds(0.0d));
        }
    }

    @Nested
    @DisplayName("phantoms falling out of a tree")
    class Phantoms {

        private final TreePhantomDetector detector = new TreePhantomDetector();

        @Test
        @DisplayName("fires captioned with the phantom")
        void named() {
            LootEvent event = detector.onChat(GatheringSamples.TREE_PHANTOM, NOW).orElseThrow();
            assertEquals(LootSource.FORAGING_TREE_PHANTOM, event.source());
            assertEquals("Phanpyre", event.subject());
            assertEquals("Dreadwing", detector.onChat(GatheringSamples.TREE_PHANTOM_DREADWING, NOW)
                    .orElseThrow().subject());
        }

        @Test
        @DisplayName("every phantom the repo knows is matched by the one pattern")
        void everyKnownPhantom() {
            for (String name : TreePhantomDetector.KNOWN) {
                String line = "§r§7A §r§d" + name + " §r§7fell from the Tree!";
                assertEquals(name, detector.onChat(line, NOW).orElseThrow().subject(),
                        "not matched: " + name);
            }
        }

        @Test
        @DisplayName("a name that is not a creature name is refused rather than captioned")
        void refusesJunkNames() {
            String line = "§r§7A §r§d" + "x".repeat(40) + " §r§7fell from the Tree!";
            assertTrue(detector.onChat(line, NOW).isEmpty());
        }

        @Test
        @DisplayName("cannot be spoofed, and claims no other source's line")
        void noCrossClaims() {
            assertTrue(detector.onChat("§bBob§f: A Dreadwing fell from the Tree!", NOW).isEmpty());
            for (String line : GatheringSamples.ALL_LINES) {
                if (line.indexOf("fell from the Tree!") >= 0) {
                    continue;
                }
                assertFalse(detector.onChat(line, NOW).isPresent(), "claimed: " + line);
            }
        }
    }
}
