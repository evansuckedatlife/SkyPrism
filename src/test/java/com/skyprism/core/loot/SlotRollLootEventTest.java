package com.skyprism.core.loot;

import com.skyprism.core.diana.DianaLootSource;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.RollState;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.util.FixedClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generalisation, tested from the outside: the machine is now started by a {@link LootEvent}
 * from any source, and Diana is one of them rather than the only one.
 *
 * <p>Diana's own behaviour is pinned by its existing suite, which was not touched. What is checked
 * here is the seam: that a non-Diana event drives the identical timeline, that the creature stays
 * Diana's business, and that starting through the creature entry point still produces a Diana event.
 */
@DisplayName("SlotRoll driven by any LootSource")
class SlotRollLootEventTest {

    private static final SlotRollConfig CFG = SlotRollConfig.defaults();

    private static LootEvent corpse(long at) {
        return new LootEvent(LootSource.GLACITE_CORPSE, "Vanguard Corpse", at);
    }

    @Test
    @DisplayName("a non-Diana event runs the same two-act timeline, with no creature anywhere in it")
    void anySourceCanDriveTheMachine() {
        FixedClock clock = new FixedClock(1_000L);
        SlotRoll roll = new SlotRoll(CFG, clock);

        roll.startEvent(corpse(1_000L));
        assertTrue(roll.active());
        assertEquals(RollState.SPINNING, roll.state());
        assertEquals(LootSource.GLACITE_CORPSE, roll.sourceAt(clock.millis()));
        assertEquals("Vanguard Corpse", roll.subjectAt(clock.millis()));
        assertTrue(roll.creature().isEmpty(), "the creature is Diana's, not the machine's");
        assertNull(roll.creatureAt(clock.millis()));

        roll.offerDrop(new LootDrop("Ascension Rope", "9", 1, true));
        clock.advance(CFG.spinMillis() + (long) (CFG.reelCount() - 1) * CFG.lockStaggerMillis()
                + CFG.settleMillis() + 1L);
        assertTrue(roll.inJackpotSequence(),
                "a rare drop earns the celebration whatever produced it");
        assertEquals("Ascension Rope", roll.jackpotSymbol().itemName());
    }

    @Test
    @DisplayName("the roll's clock sets the origin, not the event's timestamp")
    void staleEventsDoNotStartHalfFinishedRolls() {
        FixedClock clock = new FixedClock(1_000_000L);
        SlotRoll roll = new SlotRoll(CFG, clock);

        // A detector stamped this a very long time ago. If that number set the origin, the roll
        // would already be over -- or worse, would lock its reels in the past.
        roll.startEvent(new LootEvent(LootSource.SLAYER_BOSS, "Voidgloom Seraph IV", 1L));
        assertEquals(RollState.SPINNING, roll.state());
        assertTrue(roll.active());
        assertEquals(1L, roll.eventAt(clock.millis()).atMillis(),
                "the event keeps its own timestamp for anyone who wants to know how late it was");
    }

    @Test
    @DisplayName("Diana's entry point still produces a Diana event, captioned with the creature")
    void dianaIsTheFirstImplementation() {
        FixedClock clock = new FixedClock(500L);
        SlotRoll roll = new SlotRoll(CFG, clock);

        roll.start(MythologicalCreature.MINOS_INQUISITOR);
        LootEvent event = roll.event().orElseThrow();
        assertEquals(LootSource.DIANA_MYTHOLOGICAL, event.source());
        assertEquals("Minos Inquisitor", event.subject());
        assertEquals(MythologicalCreature.MINOS_INQUISITOR, roll.creature().orElseThrow());
        assertEquals("Minos Inquisitor", roll.subjectAt(clock.millis()));
    }

    @Test
    @DisplayName("the same event a detector would build is the one the creature path builds")
    void dianaLootSourceAgreesWithTheRoll() {
        FixedClock clock = new FixedClock(500L);
        SlotRoll roll = new SlotRoll(CFG, clock);
        roll.start(MythologicalCreature.KING_MINOS);

        LootEvent fromDetector = DianaLootSource.defeat(MythologicalCreature.KING_MINOS, 500L);
        assertEquals(fromDetector, roll.event().orElseThrow());
    }

    @Test
    @DisplayName("Diana's detector reads no chat, because its trigger is a creature dying")
    void dianaDetectorDoesNotClaimChat() {
        DianaLootSource diana = DianaLootSource.get();
        assertEquals(LootSource.DIANA_MYTHOLOGICAL, diana.source());
        assertFalse(diana.readsChat());
        assertTrue(diana.onChat("§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!", 1L)
                .isEmpty(), "the dig line is loot, not the trigger");
        assertTrue(diana.gateOpen(
                new GameContext(true, true, "Hub", "Graveyard", "Diana", false, false)));
        assertFalse(diana.gateOpen(
                new GameContext(true, true, "Hub", "Graveyard", "Derpy", false, false)));
    }

    @Test
    @DisplayName("a source restarts over another source's roll, exactly as two Diana kills already do")
    void restartAcrossSources() {
        FixedClock clock = new FixedClock(0L);
        SlotRoll roll = new SlotRoll(CFG, clock);

        roll.start(MythologicalCreature.MINOS_INQUISITOR);
        roll.offerDrop(new LootDrop("Chimera", "5", 1, true));
        long firstRoll = roll.rollId();

        clock.advance(50L);
        roll.startEvent(corpse(clock.millis()));

        assertEquals(firstRoll + 1L, roll.rollId(), "a restart must be visible on the edge counter");
        assertEquals(LootSource.GLACITE_CORPSE, roll.sourceAt(clock.millis()));
        assertTrue(roll.creature().isEmpty(), "the previous source's subject must not linger");
        assertTrue(roll.capturedDrops().isEmpty(), "nor its loot");
        assertFalse(roll.jackpot());
    }

    @Test
    @DisplayName("idle reports no event at all")
    void idleHasNoEvent() {
        FixedClock clock = new FixedClock(0L);
        SlotRoll roll = new SlotRoll(CFG, clock);
        assertTrue(roll.event().isEmpty());
        assertNull(roll.eventAt(0L));
        assertNull(roll.sourceAt(0L));
        assertNull(roll.subjectAt(0L));

        roll.startEvent(corpse(0L));
        assertNotNull(roll.eventAt(0L));
        roll.reset();
        assertNull(roll.eventAt(0L));
        assertNull(roll.sourceAt(0L));
    }

    @Test
    @DisplayName("a null event is rejected as firmly as a null creature")
    void nullEventRejected() {
        SlotRoll roll = new SlotRoll(CFG, new FixedClock());
        assertThrows(NullPointerException.class, () -> roll.startEvent(null));
    }
}
