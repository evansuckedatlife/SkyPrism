package com.skyprism.core.loot.containers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RewardBlock: the container grammar six sources share")
class RewardBlockTest {

    private static String rule(String colour) {
        return colour + "§l" + "▬".repeat(64);
    }

    @Test
    @DisplayName("every wrapper colour the research found opens and closes a block")
    void edges() {
        assertTrue(RewardBlock.isEdge(rule("§e")), "powder chest");
        assertTrue(RewardBlock.isEdge(rule("§d")), "loot chest");
        assertTrue(RewardBlock.isEdge(rule("§a")), "corpse loot and fossil excavation");
        assertTrue(RewardBlock.isEdge(rule("§3")), "Nucleus run completed");
        assertTrue(RewardBlock.isEdge(rule("§5")), "crystal found");
        assertTrue(RewardBlock.isEdge(rule("§2")), "Galatea tree gift");
    }

    @Test
    @DisplayName("a rule of the wrong length is not an edge")
    void wrongLength() {
        assertFalse(RewardBlock.isEdge("§e§l" + "▬".repeat(63)));
        assertFalse(RewardBlock.isEdge("§e§l" + "▬".repeat(65)));
        assertFalse(RewardBlock.isEdge("§e§l"));
    }

    @Test
    @DisplayName("ordinary chat is rejected before a matcher is allocated")
    void notEdges() {
        assertFalse(RewardBlock.isEdge(null));
        assertFalse(RewardBlock.isEdge(""));
        assertFalse(RewardBlock.isEdge("  §r§6§lCHEST LOCKPICKED"));
        assertFalse(RewardBlock.isEdge("§bGrazma§f: " + "▬".repeat(64)));
    }

    @Test
    @DisplayName("a reward line yields its item, with the count folded away")
    void items() {
        assertEquals(Optional.of("Gemstone Powder"),
                RewardBlock.itemOn("    §r§dGemstone Powder §r§8x537"));
        assertEquals(Optional.of("Treasurite"), RewardBlock.itemOn("    §r§5Treasurite"));
        assertEquals(Optional.of("Rough Amethyst Gemstone"),
                RewardBlock.itemOn("    §r§f❈ Rough Amethyst Gemstone §r§8x24"));
        assertEquals(Optional.of("Red Goblin Egg"),
                RewardBlock.itemOn("    §r§9§r§cRed Goblin Egg"));
        assertEquals(Optional.of("Tusk Fossil"), RewardBlock.itemOn("    §r§6Tusk Fossil"));
        assertEquals(Optional.of("Fine Onyx Gemstone"),
                RewardBlock.itemOn("    §r§9☠ Fine Onyx Gemstone §r§8x2"));
    }

    @Test
    @DisplayName("counts are read, including the ones Hypixel writes with separators")
    void counts() {
        assertEquals(537, RewardBlock.countOn("    §r§dGemstone Powder §r§8x537"));
        assertEquals(1204, RewardBlock.countOn("    §r§2Mithril Powder §r§8x1,204"));
        assertEquals(1, RewardBlock.countOn("    §r§5Treasurite"));
        assertEquals(1, RewardBlock.countOn("not a reward line"));
    }

    @Test
    @DisplayName("the headers and sub-headers of a block are not reward lines")
    void headersAreNotItems() {
        assertEquals(Optional.empty(), RewardBlock.itemOn("  §r§6§lCHEST LOCKPICKED"));
        assertEquals(Optional.empty(), RewardBlock.itemOn("  §r§a§lREWARDS"));
        assertEquals(Optional.empty(), RewardBlock.itemOn("  §r§5§lLOOT CHEST COLLECTED"));
        assertEquals(Optional.empty(), RewardBlock.itemOn(rule("§e")));
        assertEquals(Optional.empty(), RewardBlock.itemOn(null));
        assertEquals(Optional.empty(),
                RewardBlock.itemOn("§6§lRARE DROP! §r§9Judgement Core"));
    }
}
