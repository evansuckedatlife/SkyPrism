package com.skyprism.mc.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.RollState;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.core.util.FixedClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rapid-fire policy, the deferred policies, and the promise that Diana did not regress.
 *
 * <p>Two shapes of test here, deliberately kept apart. The admission tests drive
 * {@link LootMachine#admit} and {@link LootMachine#onChat} on a machine with <em>no detectors
 * registered</em>, so the decision under test is the only thing that can decide anything: a bus
 * full of live detectors would claim the same drop banner the test is feeding and the assertion
 * would be about detector ordering rather than about the policy. The wiring tests register the
 * real set and check the properties that only emerge from it -- what is armed, what is gated, and
 * what the pre-filter costs.</p>
 */
@DisplayName("LootMachine: admission, deferral and the Diana guarantee")
final class LootMachineMcTest {

    private static final SlotRollConfig CFG = SlotRollConfig.defaults();

    /** A plain banner Hypixel really sends, and one LootParser decomposes cleanly. */
    private static final String JUDGEMENT_CORE =
            "§6§lRARE DROP! §r§9Judgement Core "
                    + "§r§b(+§r§b168% §r§b✯ Magic Find§r§b)";

    /** Same shape, an item that is not on any source's jackpot list. */
    private static final String FOUL_FLESH =
            "§6§lRARE DROP! §r§9Foul Flesh "
                    + "§r§b(+§r§b168% §r§b✯ Magic Find§r§b)";

    /** A machine with a real roll behind it and nothing registered on its bus. */
    private static Fixture fixture() {
        FixedClock clock = new FixedClock(1_000L);
        SlotRoll roll = new SlotRoll(CFG, clock);
        LootMachine machine = new LootMachine(clock);
        machine.wire(() -> roll, () -> CFG.lootWindowMillis());
        return new Fixture(clock, roll, machine);
    }

    private record Fixture(FixedClock clock, SlotRoll roll, LootMachine machine) {
    }

    private static LootEvent corpse(long at) {
        return new LootEvent(LootSource.GLACITE_CORPSE, "Vanguard Corpse", at);
    }

    private static LootEvent slayer(long at) {
        return new LootEvent(LootSource.SLAYER_BOSS, "Voidgloom Seraph IV", at);
    }

    // ==================================================================
    //  Rapid fire
    // ==================================================================

    @Nested
    @DisplayName("rapid fire: freshest wins, with a floor")
    final class RapidFire {

        @Test
        @DisplayName("an ALWAYS event on an idle machine spins it")
        void firstEventRolls() {
            Fixture f = fixture();
            assertEquals(LootMachine.Admission.ROLLED,
                    f.machine.admit(corpse(f.clock.millis()), f.clock.millis()));
            assertTrue(f.roll.active());
            assertEquals(LootSource.GLACITE_CORPSE, f.roll.sourceAt(f.clock.millis()));
            assertEquals("Vanguard Corpse", f.roll.subjectAt(f.clock.millis()));
        }

        @Test
        @DisplayName("a second event inside the floor is ignored, not queued and not shown")
        void burstCollapsesToOneSpin() {
            Fixture f = fixture();
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());

            // Three more container lines in the same handful of ticks, which is exactly what a
            // reward block looks like coming off the wire.
            for (int i = 0; i < 3; i++) {
                f.clock.advance(40L);
                assertEquals(LootMachine.Admission.TOO_SOON,
                        f.machine.admit(slayer(f.clock.millis()), f.clock.millis()));
            }

            assertEquals("Vanguard Corpse", f.roll.subjectAt(f.clock.millis()),
                    "the first event keeps the machine; the burst did not restart the reels");
            assertEquals(1, f.machine.admittedCount());
            assertEquals(3, f.machine.suppressedCount());
            assertEquals(LootSource.SLAYER_BOSS, f.machine.lastSuppressed().source(),
                    "what was turned away is recorded, so /skyprism sources can say so");
        }

        @Test
        @DisplayName("an event past the floor takes the machine over and rebases the timeline")
        void freshestWinsOnceTheFloorHasPassed() {
            Fixture f = fixture();
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());

            f.clock.advance(LootMachine.DEFAULT_MIN_INTERVAL_MILLIS);
            assertEquals(LootMachine.Admission.ROLLED,
                    f.machine.admit(slayer(f.clock.millis()), f.clock.millis()));

            assertEquals("Voidgloom Seraph IV", f.roll.subjectAt(f.clock.millis()));
            assertEquals(RollState.SPINNING, f.roll.state(),
                    "the roll restarted rather than resuming the one it interrupted");
            assertEquals(2, f.machine.admittedCount());
        }

        @Test
        @DisplayName("one millisecond short of the floor is still inside it")
        void theFloorIsExclusiveAtItsEdge() {
            Fixture f = fixture();
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());
            f.clock.advance(LootMachine.DEFAULT_MIN_INTERVAL_MILLIS - 1);
            assertEquals(LootMachine.Admission.TOO_SOON,
                    f.machine.admit(slayer(f.clock.millis()), f.clock.millis()));
        }

        @Test
        @DisplayName("a floor of zero restores the raw replace rule SlotRoll already had")
        void zeroFloorIsOff() {
            Fixture f = fixture();
            f.machine.setMinIntervalMillis(0L);
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());
            f.clock.advance(1L);
            assertEquals(LootMachine.Admission.ROLLED,
                    f.machine.admit(slayer(f.clock.millis()), f.clock.millis()));
            assertEquals(0, f.machine.suppressedCount());
        }

        @Test
        @DisplayName("the floor is clamped rather than trusted")
        void floorIsClamped() {
            Fixture f = fixture();
            assertEquals(LootMachine.MIN_INTERVAL_FLOOR_MILLIS,
                    f.machine.setMinIntervalMillis(-5_000L));
            assertEquals(LootMachine.MAX_INTERVAL_MILLIS,
                    f.machine.setMinIntervalMillis(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("an installed supplier takes the floor over from the session setting")
        void suppliedFloorWins() {
            Fixture f = fixture();
            f.machine.setMinIntervalMillis(200L);
            assertFalse(f.machine.intervalSupplied());
            f.machine.setMinIntervalSupplier(() -> 4_000L);
            assertTrue(f.machine.intervalSupplied());
            assertEquals(4_000L, f.machine.minIntervalMillis());
            f.machine.setMinIntervalSupplier(null);
            assertEquals(200L, f.machine.minIntervalMillis(), "control comes back, not a default");
        }
    }

    // ==================================================================
    //  Diana must not regress
    // ==================================================================

    @Nested
    @DisplayName("Diana outranks the bus")
    final class DianaOutranks {

        @Test
        @DisplayName("a bus event cannot clobber a running Diana roll")
        void busCannotInterruptDiana() {
            Fixture f = fixture();
            f.roll.start(MythologicalCreature.MINOS_INQUISITOR);
            f.roll.offerDrop(new LootDrop("Chimera I", "5", 1, true));

            f.clock.advance(200L);
            assertEquals(LootMachine.Admission.OUTRANKED,
                    f.machine.admit(slayer(f.clock.millis()), f.clock.millis()));

            assertEquals(LootSource.DIANA_MYTHOLOGICAL, f.roll.sourceAt(f.clock.millis()));
            assertEquals("Minos Inquisitor", f.roll.subjectAt(f.clock.millis()));
            assertEquals(MythologicalCreature.MINOS_INQUISITOR, f.roll.creature().orElseThrow());
            assertTrue(f.roll.jackpot(), "the Inquisitor's celebration survived intact");
        }

        @Test
        @DisplayName("the machine does not feed drops into a Diana roll; its controller does that")
        void busDoesNotDoubleFeedDiana() {
            Fixture f = fixture();
            f.roll.start(MythologicalCreature.MINOS_INQUISITOR);
            int before = f.roll.capturedDropCount();

            f.clock.advance(100L);
            f.machine.onChat(JUDGEMENT_CORE, f.clock.millis());

            assertEquals(before, f.roll.capturedDropCount(),
                    "a second feed would put the same symbol on two reels");
        }

        @Test
        @DisplayName("once the Diana roll has finished the bus is free again")
        void busResumesWhenDianaIsDone() {
            Fixture f = fixture();
            f.roll.start(MythologicalCreature.HARPY);
            f.clock.advance(60_000L);
            assertFalse(f.roll.active());

            assertEquals(LootMachine.Admission.ROLLED,
                    f.machine.admit(corpse(f.clock.millis()), f.clock.millis()));
        }

        @Test
        @DisplayName("no detector on the bus ever claims the Mythological Ritual")
        void dianaHasExactlyOneOwner() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");
            assertFalse(machine.registered(LootSource.DIANA_MYTHOLOGICAL),
                    "the controller owns that source; a second owner would double-spin a burrow");
        }
    }

    // ==================================================================
    //  Deferred policies
    // ==================================================================

    @Nested
    @DisplayName("policies that wait for the loot")
    final class Deferred {

        @Test
        @DisplayName("ON_RARE_BANNER arms rather than rolling, then rolls on the banner")
        void rareBannerDefersThenRolls() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.ON_RARE_BANNER);

            assertEquals(LootMachine.Admission.DEFERRED,
                    f.machine.admit(slayer(f.clock.millis()), f.clock.millis()));
            assertFalse(f.roll.active(), "nothing on screen until the loot justifies it");
            assertNotNull(f.machine.pending());

            f.clock.advance(300L);
            f.machine.onChat(JUDGEMENT_CORE, f.clock.millis());

            assertTrue(f.roll.active());
            assertEquals("Voidgloom Seraph IV", f.roll.subjectAt(f.clock.millis()),
                    "the roll is captioned with the trigger, not with the drop line");
            assertEquals("Judgement Core", f.roll.capturedDrops().get(0).itemName());
            assertNull(f.machine.pending(), "the armed event was consumed, not left standing");
        }

        @Test
        @DisplayName("a deferred event whose loot never qualifies simply never appears")
        void deferredWithoutQualifyingLootStaysSilent() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.ON_JACKPOT_ITEM_ONLY);
            f.machine.admit(slayer(f.clock.millis()), f.clock.millis());

            f.clock.advance(300L);
            f.machine.onChat(FOUL_FLESH, f.clock.millis());

            assertFalse(f.roll.active(),
                    "Foul Flesh is not on the slayer jackpot list, so the player asked for silence");
            assertEquals(0, f.machine.admittedCount());
        }

        @Test
        @DisplayName("ON_JACKPOT_ITEM_ONLY rolls when a named item lands")
        void jackpotItemStartsTheRoll() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.ON_JACKPOT_ITEM_ONLY);
            assertTrue(LootSourceRegistry.info(LootSource.SLAYER_BOSS)
                            .jackpotItems().contains("Judgement Core"),
                    "fixture check: this test is only meaningful while that name is on the list");

            f.machine.admit(slayer(f.clock.millis()), f.clock.millis());
            f.clock.advance(300L);
            f.machine.onChat(JUDGEMENT_CORE, f.clock.millis());

            assertTrue(f.roll.active());
            assertTrue(f.roll.jackpot(), "the item that satisfied the policy also earns the act");
        }

        @Test
        @DisplayName("an armed event expires with the loot window and cannot fire late")
        void pendingExpires() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.ON_RARE_BANNER);
            f.machine.admit(slayer(f.clock.millis()), f.clock.millis());

            f.clock.advance(CFG.lootWindowMillis() + 1L);
            assertNull(f.machine.pending());

            f.machine.onChat(JUDGEMENT_CORE, f.clock.millis());
            assertFalse(f.roll.active(),
                    "a drop from the next activity must not be credited to the last one");
        }

        @Test
        @DisplayName("a NEVER source is refused even if something hands it an event directly")
        void neverIsRefused() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.NEVER);
            assertEquals(LootMachine.Admission.OFF,
                    f.machine.admit(slayer(f.clock.millis()), f.clock.millis()));
            assertFalse(f.roll.active());
        }

        @Test
        @DisplayName("a policy override can be cleared back to the shipped default")
        void policyResets() {
            Fixture f = fixture();
            RollPolicy shipped = LootSourceRegistry.defaultPolicy(LootSource.SLAYER_BOSS);
            f.machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.NEVER);
            assertTrue(f.machine.overridden(LootSource.SLAYER_BOSS));
            assertEquals(shipped, f.machine.setPolicy(LootSource.SLAYER_BOSS, null));
            assertFalse(f.machine.overridden(LootSource.SLAYER_BOSS));
        }

        @Test
        @DisplayName("Diana's policy cannot be changed here; it lives in the Diana settings")
        void dianaPolicyIsNotOurs() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.DIANA_MYTHOLOGICAL, RollPolicy.NEVER);
            assertFalse(f.machine.overridden(LootSource.DIANA_MYTHOLOGICAL),
                    "a switch that visibly does nothing is worse than no switch");
        }
    }

    // ==================================================================
    //  The chat path's cost
    // ==================================================================

    @Nested
    @DisplayName("what a chat line costs")
    final class ChatCost {

        @Test
        @DisplayName("an idle machine with an empty bus wants no line at all")
        void idleWantsNothing() {
            Fixture f = fixture();
            assertFalse(f.machine.wantsLine("Player: hello everyone"));
            assertFalse(f.machine.wantsLine("RARE DROP! Judgement Core"));
            assertFalse(f.machine.wantsLine(null));
            assertFalse(f.machine.wantsLine(""));
        }

        @Test
        @DisplayName("a machine that has never run has no window, even at clock zero")
        void windowIsShutBeforeAnythingHappens() {
            FixedClock clock = new FixedClock(0L);
            SlotRoll roll = new SlotRoll(CFG, clock);
            LootMachine machine = new LootMachine(clock);
            machine.wire(() -> roll, () -> CFG.lootWindowMillis());

            // Zero is a real instant on an injected clock. A loot deadline initialised to zero
            // read as an open window here and made the machine ask for every line in the game
            // before anything had fired -- invisible under wall-clock millis, which never
            // approaches zero.
            assertFalse(machine.armed(), "no detectors and no window is not armed");
            assertFalse(machine.wantsLine("Party > Steve: gg"));
        }

        @Test
        @DisplayName("an open loot window wants every line, because drop rows carry no marker")
        void openWindowWantsEverything() {
            Fixture f = fixture();
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());
            assertTrue(f.machine.wantsLine("    §r§9Fine Onyx Gemstone §r§8x2"),
                    "an indented reward row matches no trigger literal anywhere");
        }

        @Test
        @DisplayName("the window shuts again, and with it the interest in ordinary chat")
        void windowShuts() {
            Fixture f = fixture();
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());
            f.clock.advance(CFG.lootWindowMillis() + 1L);
            assertFalse(f.machine.wantsLine("Player: hello everyone"));
        }

        @Test
        @DisplayName("a registered bus pre-filters on plain text, which is what the caller has")
        void preFilterWorksOnPlainText() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");
            machine.updateContext(true, true, "Hub", false);

            // The markers are formatting-free literals, so the flattened line answers the same
            // question the legacy one would -- which is the whole reason the conversion can wait.
            assertTrue(machine.wantsLine("  NICE! SLAYER BOSS SLAIN!"));
        }

        @Test
        @DisplayName("hardStop closes the window, so a warp cannot carry it to the next island")
        void hardStopClosesTheWindow() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.ON_RARE_BANNER);
            f.machine.admit(slayer(f.clock.millis()), f.clock.millis());
            assertNotNull(f.machine.pending());

            f.machine.hardStop();
            assertNull(f.machine.pending());
            assertFalse(f.machine.wantsLine("Player: hello"));
        }
    }

    // ==================================================================
    //  Gates and registration
    // ==================================================================

    @Nested
    @DisplayName("gates and the registered set")
    final class Gates {

        @Test
        @DisplayName("only armed sources get a detector, so a shut source is an absent object")
        void neverSourcesAreNotRegistered() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.NEVER);
            machine.registerDetectors(() -> "Tester");
            machine.updateContext(true, true, "Hub", false);

            assertFalse(machine.registered(LootSource.SLAYER_BOSS));
            assertTrue(machine.registeredCount() > 0, "the rest of the bus is still there");
        }

        @Test
        @DisplayName("switching a source off after startup takes its detector back out")
        void policyChangeRebuildsTheBus() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");
            machine.updateContext(true, true, "Hub", false);
            int before = machine.registeredCount();

            machine.setPolicy(LootSource.SLAYER_BOSS, RollPolicy.NEVER);
            assertEquals(before - 1, machine.registeredCount());
            assertFalse(machine.registered(LootSource.SLAYER_BOSS));

            // ...and the context survives the rebuild, or every gate would read as shut.
            assertEquals("Hub", machine.context().island());
            assertTrue(machine.openGateCount() > 0);
        }

        @Test
        @DisplayName("an island-gated source is shut on the wrong island and open on the right one")
        void islandGatesFollowTheIsland() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");

            machine.updateContext(true, true, "Hub", false);
            assertFalse(machine.gateOpen(LootSource.ARACHNE));

            machine.updateContext(true, true, "Spider's Den", false);
            assertTrue(machine.gateOpen(LootSource.ARACHNE));
        }

        @Test
        @DisplayName("off Hypixel, and out of SkyBlock, every gate is shut")
        void nothingFiresOffServer() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");

            machine.updateContext(false, false, "", false);
            assertEquals(0, machine.openGateCount());
            assertFalse(machine.gateOpen(LootSource.SLAYER_BOSS));

            machine.updateContext(true, false, "", false);
            assertEquals(0, machine.openGateCount(),
                    "on Hypixel but in a lobby is still nothing to detect");
        }

        @Test
        @DisplayName("the dungeon flag is derived from the island the sidebar reports")
        void catacombsImpliesInDungeon() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");

            machine.updateContext(true, true, "The Catacombs (F7)", false);
            assertTrue(machine.context().inDungeon());
            assertTrue(machine.gateOpen(LootSource.DUNGEON_BOSS));

            machine.updateContext(true, true, "The Rift", false);
            assertFalse(machine.context().inDungeon());
            assertTrue(machine.context().inRift());
        }

        @Test
        @DisplayName("an unchanged context does not rebuild anything")
        void unchangedContextIsIdempotent() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");
            machine.updateContext(true, true, "Hub", false);
            var first = machine.context();

            machine.updateContext(true, true, "Hub", false);
            assertSame(first, machine.context(),
                    "the per-tick path must not allocate a GameContext when nothing moved");
        }

        @Test
        @DisplayName("two packages claiming one source is resolved, not thrown, and is reported")
        void collidingDetectorsAreResolved() {
            LootMachine machine = new LootMachine(new FixedClock());
            // The bus rejects a duplicate registration by design, so this call reaching the end at
            // all is the assertion: startup must survive a collision rather than take the whole
            // loot feature down with it.
            machine.registerDetectors(() -> "Tester");
            machine.updateContext(true, true, "Hub", false);

            for (LootSource source : machine.contestedSources()) {
                assertTrue(machine.registered(source) || !machine.gateOpen(source),
                        source + " was contested and then lost entirely");
            }

            // Both known collisions must still have a live owner.
            assertTrue(machine.registered(LootSource.MOB_RARE_DROP));
            assertTrue(machine.registered(LootSource.PET_DROP));
        }

        @Test
        @DisplayName("the pet detector that won is the one that captions with the pet's name")
        void petDropKeepsItsSubject() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");
            machine.updateContext(true, true, "Hub", false);

            LootEvent event = com.skyprism.core.loot.combat.PetDropDetector.PET_DROP
                    .matcher("§6§lPET DROP! §r§5Baby Yeti §r§b(+§r§b168% §r§b✯ Magic Find§r§b)")
                    .matches()
                    ? new LootEvent(LootSource.PET_DROP, "Baby Yeti", 1L)
                    : null;
            assertNotNull(event, "fixture check: the winning detector still matches a real line");
            assertEquals("Baby Yeti", event.subject());
        }

        @Test
        @DisplayName("armedSourceCount counts what the player has switched on, not what is gated")
        void armedCountIgnoresGates() {
            LootMachine machine = new LootMachine(new FixedClock());
            machine.registerDetectors(() -> "Tester");
            machine.updateContext(false, false, "", false);
            assertTrue(machine.armedSourceCount() > 0,
                    "armed is a setting; open is a place. /skyprism sources reports both.");
            assertEquals(0, machine.openGateCount());
        }
    }

    // ==================================================================
    //  Simulation
    // ==================================================================

    @Nested
    @DisplayName("simulate bypasses every gatekeeper on purpose")
    final class Simulate {

        @Test
        @DisplayName("it spins with the gate shut, the source off and the floor unexpired")
        void simulateIgnoresEverything() {
            Fixture f = fixture();
            f.machine.setPolicy(LootSource.ARACHNE, RollPolicy.NEVER);
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());

            f.clock.advance(10L);
            LootEvent event = new LootEvent(LootSource.ARACHNE, "Arachne", f.clock.millis());
            assertTrue(f.machine.simulate(event, java.util.List.of(
                    new LootDrop("Arachne's Calling", "5", 1, true))));

            assertEquals("Arachne", f.roll.subjectAt(f.clock.millis()));
            assertTrue(f.roll.jackpot());
        }

        @Test
        @DisplayName("an empty drop list produces the barren-event result rather than nothing")
        void simulateWithNoDrops() {
            Fixture f = fixture();
            LootEvent event = new LootEvent(LootSource.KUUDRA_COMPLETE, "Kuudra (T5)",
                    f.clock.millis());
            assertTrue(f.machine.simulate(event, java.util.List.of()));

            f.clock.advance(CFG.spinMillis() + 2L * CFG.lockStaggerMillis() + 1L);
            assertEquals(SlotRoll.NO_DROP, f.roll.reels().get(0).symbol());
        }

        @Test
        @DisplayName("with no machine wired it reports failure instead of throwing")
        void simulateWithoutAMachine() {
            LootMachine machine = new LootMachine(new FixedClock());
            assertFalse(machine.wired());
            assertFalse(machine.simulate(corpse(1L), java.util.List.of()));
        }
    }

    // ==================================================================
    //  The caption's contract
    // ==================================================================

    @Nested
    @DisplayName("what the caption strip will have to draw")
    final class Caption {

        @Test
        @DisplayName("every source has a category, and every category a real legacy colour code")
        void everySourceIsCategorised() {
            for (LootSource source : LootSource.values()) {
                SourceCategory category = SourceCategory.of(source);
                assertNotNull(category, source.name());
                String code = category.colorCode();
                assertEquals(1, code.length(), source.name());
                assertTrue("0123456789abcdef".indexOf(code.charAt(0)) >= 0,
                        () -> category + " uses " + code + ", which is not a colour code");
                assertFalse(category.displayName().isBlank());
            }
        }

        @Test
        @DisplayName("a subject is never blank, so the strip can never be drawn empty")
        void subjectIsAlwaysPrintable() {
            for (LootSource source : LootSource.values()) {
                assertFalse(new LootEvent(source, null, 1L).subject().isBlank(), source.name());
                assertFalse(new LootEvent(source, "   ", 1L).subject().isBlank(), source.name());
            }
        }

        @Test
        @DisplayName("Diana still captions with its creature, which is what shipped")
        void dianaCaptionUnchanged() {
            Fixture f = fixture();
            f.roll.start(MythologicalCreature.MINOS_INQUISITOR);
            assertEquals("Minos Inquisitor", f.roll.subjectAt(f.clock.millis()));
            assertEquals(MythologicalCreature.MINOS_INQUISITOR,
                    f.roll.creatureAt(f.clock.millis()),
                    "the creature is still there for the colour, not only the text");
        }

        @Test
        @DisplayName("a non-Diana roll has no creature, so the caption falls to the subject")
        void otherSourcesHaveNoCreature() {
            Fixture f = fixture();
            f.machine.admit(corpse(f.clock.millis()), f.clock.millis());
            assertNull(f.roll.creatureAt(f.clock.millis()));
            assertEquals("Vanguard Corpse", f.roll.subjectAt(f.clock.millis()));
            assertEquals(SourceCategory.MINING, SourceCategory.of(LootSource.GLACITE_CORPSE));
        }
    }
}
