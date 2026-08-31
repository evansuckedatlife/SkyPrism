package com.skyprism.core.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LootEvent, GameContext and RollPolicy: the small pieces of the contract")
class LootEventTest {

    @Test
    @DisplayName("a subject survives intact, trimmed")
    void subjectIsKept() {
        LootEvent event = new LootEvent(LootSource.SLAYER_BOSS, "  Voidgloom Seraph IV ", 42L);
        assertEquals("Voidgloom Seraph IV", event.subject());
        assertEquals(LootSource.SLAYER_BOSS, event.source());
        assertEquals(42L, event.atMillis());
    }

    @Test
    @DisplayName("a missing subject falls back to the source's own caption rather than to nothing")
    void blankSubjectFallsBack() {
        assertEquals("Rare Mob Drop", new LootEvent(LootSource.MOB_RARE_DROP, null, 1L).subject());
        assertEquals("Rare Mob Drop", new LootEvent(LootSource.MOB_RARE_DROP, "   ", 1L).subject());
        assertEquals("Rare Mob Drop", LootEvent.of(LootSource.MOB_RARE_DROP, 1L).subject());
    }

    @Test
    @DisplayName("a subject is length-capped, because it comes off a server string and into a fixed widget")
    void subjectIsCapped() {
        String hostile = "x".repeat(500);
        LootEvent event = new LootEvent(LootSource.MOB_RARE_DROP, hostile, 1L);
        assertEquals(LootEvent.MAX_SUBJECT_LENGTH, event.subject().length());
    }

    @Test
    @DisplayName("a null source is rejected up front")
    void nullSourceRejected() {
        assertThrows(NullPointerException.class, () -> new LootEvent(null, "x", 1L));
    }

    @Test
    @DisplayName("an event can answer for its source without anyone holding the registry")
    void eventKnowsItsSource() {
        LootEvent event = LootEvent.of(LootSource.GLACITE_CORPSE, "Vanguard Corpse", 1L);
        assertEquals("Glacite Corpse", event.sourceDisplayName());
        assertEquals(RollPolicy.ALWAYS, event.defaultPolicy());
        assertEquals("Vanguard Corpse", event.subject());
    }

    @Test
    @DisplayName("GameContext strips formatting and treats unknown as shut, not as anywhere")
    void contextNormalises() {
        GameContext ctx = new GameContext(true, true, "§7Crystal Hollows ", " §aJungle Temple",
                "§6Diana", false, false);
        assertEquals("Crystal Hollows", ctx.island());
        assertEquals("Jungle Temple", ctx.area());
        assertEquals("Diana", ctx.mayor());
        assertTrue(ctx.isIsland("crystal hollows"));
        assertTrue(ctx.isArea("Jungle Temple"));
        assertTrue(ctx.isMayor(" diana "));
        assertTrue(ctx.inGame());

        GameContext blank = new GameContext(true, true, null, null, null, false, false);
        assertEquals("", blank.island());
        assertTrue(!blank.isIsland(""), "unknown must not match the empty string and open every gate");
        assertTrue(!blank.isIsland(null));
    }

    @Test
    @DisplayName("RollPolicy answers the only question it exists to answer")
    void policyTable() {
        assertTrue(RollPolicy.ALWAYS.permits(false, false));
        assertTrue(RollPolicy.ON_RARE_BANNER.permits(true, false));
        assertTrue(!RollPolicy.ON_RARE_BANNER.permits(false, true));
        assertTrue(RollPolicy.ON_JACKPOT_ITEM_ONLY.permits(false, true));
        assertTrue(!RollPolicy.ON_JACKPOT_ITEM_ONLY.permits(true, false));
        assertTrue(!RollPolicy.NEVER.permits(true, true));

        assertTrue(RollPolicy.ALWAYS.armed());
        assertTrue(!RollPolicy.NEVER.armed());
    }

    @Test
    @DisplayName("a screen gate is armed by its title rather than shut forever, which would never fire")
    void screenGatesAreOpenInSkyBlock() {
        // A gate that can never open is a detector that silently never fires, which is the exact
        // failure this design exists to avoid. Screen and season gates therefore stay open and let
        // the title or the distinctive line do the filtering.
        assertTrue(SourceGate.screen("Croesus").isOpen(GameContext.onIsland("Dungeon Hub")));
        assertTrue(SourceGate.season("Hoppity's Hunt").isOpen(GameContext.onIsland("Hub")));
        assertTrue(!SourceGate.screen("Croesus").isOpen(GameContext.UNKNOWN));
    }

    @Test
    @DisplayName("a blank gate name is a configuration error, not a gate that matches everything")
    void blankGateNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> SourceGate.island("  "));
        assertThrows(NullPointerException.class, () -> SourceGate.island(null));
    }
}
