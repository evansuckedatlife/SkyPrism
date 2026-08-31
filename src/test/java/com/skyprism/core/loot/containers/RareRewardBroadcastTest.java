package com.skyprism.core.loot.containers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RareRewardBroadcast: the one line three sources share, and the party it is sent to")
class RareRewardBroadcastTest {

    private static final String OBSIDIAN =
            "§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Recombobulator 3000 "
                    + "§r§ein their Obsidian Chest§r§e!";

    @Test
    @DisplayName("the captured Hypixel line decomposes into player, item and tier")
    void parsesTheCapturedLine() {
        RareRewardBroadcast broadcast = RareRewardBroadcast.parse(OBSIDIAN).orElseThrow();
        assertEquals("Leebys", broadcast.player());
        assertEquals("Recombobulator 3000", broadcast.item());
        assertEquals("Obsidian", broadcast.tier());
        assertEquals("Obsidian Chest", broadcast.chestCaption());
    }

    @Test
    @DisplayName("a rank prefix on the recipient does not leak into the name")
    void rankPrefix() {
        RareRewardBroadcast broadcast = RareRewardBroadcast.parse(
                "§6§lRARE REWARD! §r§b[MVP§c+§b] Leebys §r§efound a §r§6Necron's Handle "
                        + "§r§ein their Bedrock Chest§r§e!").orElseThrow();
        assertEquals("Leebys", broadcast.player());
        assertEquals("Bedrock", broadcast.tier());
    }

    @Test
    @DisplayName("a Kuudra chest reads its tier the same way")
    void kuudraTier() {
        RareRewardBroadcast broadcast = RareRewardBroadcast.parse(
                "§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Kraken Shard "
                        + "§r§ein their Paid Chest§r§e!").orElseThrow();
        assertEquals("Paid", broadcast.tier());
        assertEquals("Kraken Shard", broadcast.item());
    }

    @Test
    @DisplayName("only the named player owns it")
    void ownership() {
        RareRewardBroadcast broadcast = RareRewardBroadcast.parse(OBSIDIAN).orElseThrow();
        assertTrue(broadcast.isOwnedBy("Leebys"));
        assertTrue(broadcast.isOwnedBy("leebys"), "usernames are case-insensitive");
        assertTrue(broadcast.isOwnedBy("[MVP+] Leebys"), "our own name may carry our own rank");
        assertFalse(broadcast.isOwnedBy("Grazma"));
    }

    @Test
    @DisplayName("an unknown local name fails closed rather than claiming a party member's loot")
    void unknownLocalNameFailsClosed() {
        RareRewardBroadcast broadcast = RareRewardBroadcast.parse(OBSIDIAN).orElseThrow();
        assertFalse(broadcast.isOwnedBy(null));
        assertFalse(broadcast.isOwnedBy(""));
        assertFalse(broadcast.isOwnedBy("   "));
    }

    @Test
    @DisplayName("a player quoting the sentence in chat cannot forge it")
    void playerChatCannotForgeIt() {
        assertEquals(Optional.empty(), RareRewardBroadcast.parse(
                "§bGrazma§f: RARE REWARD! Leebys found a Recombobulator 3000 "
                        + "in their Obsidian Chest!"));
        assertEquals(Optional.empty(), RareRewardBroadcast.parse(
                "§2Guild > §bGrazma§f: RARE REWARD! Leebys found a Livid Dagger "
                        + "in their Bedrock Chest!"));
        assertEquals(Optional.empty(), RareRewardBroadcast.parse(
                "§9Party §8> §bGrazma§f: RARE REWARD! Leebys found a Shadow Warp "
                        + "in their Obsidian Chest!"));
    }

    @Test
    @DisplayName("lines from other sources are rejected without a matcher")
    void otherSources() {
        assertEquals(Optional.empty(), RareRewardBroadcast.parse(
                "§6§lRARE DROP! §r§9Dwarf Turtle Shelmet §r§b(+§r§b168% Magic Find§r§b)"));
        assertEquals(Optional.empty(), RareRewardBroadcast.parse("  §r§6§lCHEST LOCKPICKED"));
        assertEquals(Optional.empty(), RareRewardBroadcast.parse(null));
        assertEquals(Optional.empty(), RareRewardBroadcast.parse(""));
    }
}
