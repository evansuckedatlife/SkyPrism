package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.util.FixedClock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for {@link SlotRoll}, driven entirely by {@link FixedClock}.
 *
 * <p>The test config uses round numbers so every boundary can be asserted on the exact
 * millisecond rather than within a tolerance. Act one: spin 1000, stagger 200, so with three reels
 * the locks fall at 1000 / 1200 / 1400, the settle ends at 1900 and, with no jackpot, the fade ends
 * at 2000. Act two, when it is earned, picks up at 1900: 400 of gold wash, 600 of re-spin, then
 * landings 100 apart at 2900 / 3000 / 3100, an 800 hold to 3900 and the same 100 fade to 4000.
 */
class SlotRollTest {

    /**
     * spin 1000, stagger 200, window 3000, settle 500, fade 100;
     * jackpot intro 400, spin 600, stagger 100, hold 800.
     */
    private static final SlotRollConfig CFG =
            new SlotRollConfig(3, 1000L, 200L, 3000L, 500L, 100L, 400L, 600L, 100L, 800L);

    private static final long LOCK_0 = 1000L;
    private static final long LOCK_1 = 1200L;
    private static final long LOCK_2 = 1400L;
    private static final long SETTLE_END = 1900L;
    private static final long FADE_END = 2000L;

    /** Act two, all measured from {@link #SETTLE_END}, which is where it begins. */
    private static final long J_INTRO_START = SETTLE_END;          // 1900
    private static final long J_SPIN_START = 2300L;
    private static final long J_LOCK_0 = 2900L;
    private static final long J_LOCK_1 = 3000L;
    private static final long J_LOCK_2 = 3100L;
    private static final long J_HOLD_END = 3900L;
    private static final long J_FADE_END = 4000L;

    private static LootDrop drop(String name) {
        return new LootDrop(name, "a", 1, false);
    }

    private static LootDrop drop(String name, int count) {
        return new LootDrop(name, "a", count, false);
    }

    private static LootDrop rare(String name) {
        return new LootDrop(name, "d", 1, true);
    }

    private static LootDrop rare(String name, int count) {
        return new LootDrop(name, "d", count, true);
    }

    private static List<LootDrop> symbols(SlotRoll roll) {
        return roll.reels().stream().map(Reel::symbol).toList();
    }

    private static List<Boolean> locks(SlotRoll roll) {
        return roll.reels().stream().map(Reel::locked).toList();
    }

    // ------------------------------------------------------------------ lifecycle

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a fresh machine is idle and reports nothing")
        void freshMachineIsIdle() {
            var roll = new SlotRoll(CFG, new FixedClock());
            assertEquals(RollState.IDLE, roll.state());
            assertFalse(roll.active());
            assertEquals(List.of(), roll.reels());
            assertEquals(List.of(), roll.capturedDrops());
            assertFalse(roll.jackpot());
            assertFalse(roll.inJackpotSequence());
            assertNull(roll.jackpotSymbol());
            assertEquals(0.0d, roll.jackpotIntroProgress());
            assertTrue(roll.creature().isEmpty());
        }

        @Test
        @DisplayName("start spins immediately and remembers the creature")
        void startBeginsSpinning() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);

            assertEquals(RollState.SPINNING, roll.state());
            assertTrue(roll.active());
            assertEquals(MythologicalCreature.MINOS_INQUISITOR, roll.creature().orElseThrow());
            assertEquals(3, roll.reels().size());
            for (Reel r : roll.reels()) {
                assertFalse(r.locked(), "reel " + r.index() + " must still be spinning");
                assertNull(r.symbol(), "a spinning reel has no symbol yet");
            }
        }

        @Test
        @DisplayName("with no jackpot every phase boundary falls on the exact millisecond")
        void phaseBoundariesAreExact() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MANTICORE);

            clock.set(LOCK_0 - 1);
            assertEquals(RollState.SPINNING, roll.state());
            clock.set(LOCK_0);
            assertEquals(RollState.LOCKING, roll.state());
            clock.set(LOCK_2 - 1);
            assertEquals(RollState.LOCKING, roll.state());
            clock.set(LOCK_2);
            assertEquals(RollState.SETTLED, roll.state());
            clock.set(SETTLE_END - 1);
            assertEquals(RollState.SETTLED, roll.state());
            clock.set(SETTLE_END);
            assertEquals(RollState.FADING, roll.state(), "no jackpot means SETTLED hands straight to FADING");
            clock.set(FADE_END - 1);
            assertEquals(RollState.FADING, roll.state());
            clock.set(FADE_END);
            assertEquals(RollState.IDLE, roll.state());
            assertFalse(roll.active());
        }

        @Test
        @DisplayName("reaching idle clears the roll rather than leaving it readable")
        void idleClearsEverything() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.KING_MINOS);
            roll.offerDrop(drop("Daedalus Stick"));

            clock.set(FADE_END);
            assertEquals(RollState.IDLE, roll.state());
            assertEquals(List.of(), roll.reels());
            assertEquals(List.of(), roll.capturedDrops());
            assertFalse(roll.jackpot());
            assertTrue(roll.creature().isEmpty());
        }

        @Test
        @DisplayName("reels lock left to right, one stagger apart, and never unlock again")
        void reelsLockLeftToRight() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.SPHINX);
            roll.offerDrop(drop("Dwarf Turtle Shelmet"));

            clock.set(LOCK_0);
            assertEquals(List.of(true, false, false), locks(roll));
            clock.set(LOCK_1);
            assertEquals(List.of(true, true, false), locks(roll));
            clock.set(LOCK_2);
            assertEquals(List.of(true, true, true), locks(roll));
            clock.set(SETTLE_END);
            assertEquals(List.of(true, true, true), locks(roll), "a locked reel stays locked while fading");
        }

        @Test
        @DisplayName("reset returns to idle from any phase, act two included")
        void resetReturnsToIdle() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(rare("Crown of Greed"));
            clock.set(LOCK_1);
            assertEquals(RollState.LOCKING, roll.state());

            roll.reset();
            assertEquals(RollState.IDLE, roll.state());
            assertFalse(roll.jackpot());
            assertEquals(List.of(), roll.capturedDrops());

            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(rare("Chimera"));
            clock.set(LOCK_1 + J_LOCK_1);
            assertEquals(RollState.JACKPOT_LOCK, roll.state());
            roll.reset();
            assertEquals(RollState.IDLE, roll.state());
            assertNull(roll.jackpotSymbol());
        }
    }

    // ------------------------------------------------------------------ symbols

    @Nested
    @DisplayName("symbol policy")
    class Symbols {

        @Test
        @DisplayName("drops fill the reels most-interesting-first")
        void dropsFillReels() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var a = drop("Chimera");
            var b = drop("Judgement Core");
            var c = drop("Ancient Claw");
            roll.offerDrop(a);
            roll.offerDrop(b);
            roll.offerDrop(c);

            clock.set(LOCK_2);
            assertEquals(List.of(a, b, c), symbols(roll), "equal rank falls back to arrival order");
        }

        @Test
        @DisplayName("a single drop fills every reel, giving a three-of-a-kind")
        void singleDropFillsEveryReel() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MANTICORE);
            var only = drop("Mythological Bone Fragment", 4);
            roll.offerDrop(only);

            clock.set(LOCK_2);
            assertEquals(List.of(only, only, only), symbols(roll));
        }

        @Test
        @DisplayName("two drops on three reels cycle back to the top of the ranking")
        void twoDropsCycle() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.KING_MINOS);
            var a = drop("Crown of Greed");
            var b = drop("Ancient Claw");
            roll.offerDrop(a);
            roll.offerDrop(b);

            clock.set(LOCK_2);
            assertEquals(List.of(a, b, a), symbols(roll));
        }

        @Test
        @DisplayName("no drops at all lands every reel on NO_DROP instead of hanging in LOCKING")
        void noDropsLandOnPlaceholder() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.HARPY);

            clock.set(LOCK_2);
            assertEquals(RollState.SETTLED, roll.state(), "must not sit in LOCKING waiting for loot");
            assertEquals(List.of(SlotRoll.NO_DROP, SlotRoll.NO_DROP, SlotRoll.NO_DROP), symbols(roll));
            for (Reel r : roll.reels()) {
                assertTrue(r.locked());
                assertNotNull(r.symbol());
            }
        }

        @Test
        @DisplayName("more drops than reels: the rarest win the columns, the rest stay in capturedDrops")
        void moreDropsThanReels() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var common1 = drop("Ancient Claw");
            var common2 = drop("Griffin Feather");
            var big = drop("Mythological Bone Fragment", 9);
            var jackpot = rare("Chimera");
            var common3 = drop("Dwarf Turtle Shelmet");
            roll.offerDrop(common1);
            roll.offerDrop(common2);
            roll.offerDrop(big);
            roll.offerDrop(jackpot);
            roll.offerDrop(common3);

            clock.set(LOCK_2);
            assertEquals(List.of(jackpot, big, common1), symbols(roll),
                    "rare first, then the biggest stack, then arrival order");
            assertEquals(List.of(common1, common2, big, jackpot, common3), roll.capturedDrops(),
                    "capturedDrops keeps everything, in arrival order");
        }

        @Test
        @DisplayName("a rare drop outranks an earlier common one however late it arrives")
        void rarityOutranksArrival() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.SPHINX);
            var common = drop("Ancient Claw");
            roll.offerDrop(common);
            clock.set(500L);
            var jackpot = rare("Crown of Greed");
            roll.offerDrop(jackpot);

            clock.set(LOCK_2);
            assertEquals(jackpot, symbols(roll).get(0));
        }

        @Test
        @DisplayName("a reel only ever sees the drops that had arrived by its own lock instant")
        void reelSeesOnlyDropsThatHadArrived() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var early = drop("Ancient Claw");
            roll.offerDrop(early);

            clock.set(LOCK_0 + 50);
            var late = drop("Griffin Feather");
            roll.offerDrop(late);

            clock.set(LOCK_2);
            var shown = symbols(roll);
            assertEquals(early, shown.get(0), "reel 0 locked before the second drop existed");
            assertEquals(late, shown.get(1), "reel 1 locked after it and takes the unused drop");
        }

        @Test
        @DisplayName("a spinning reel has a phase in [0,1); a locked one is pinned to 0")
        void spinPhaseRange() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.CRETAN_BULL);
            roll.offerDrop(rare("Ancient Claw"));   // rare, so act two's re-spin is covered too

            for (long t = 0; t < J_FADE_END; t += 7) {
                clock.set(t);
                for (Reel r : roll.reels()) {
                    assertTrue(r.spinPhase() >= 0.0d && r.spinPhase() < 1.0d,
                            "phase out of range at t=" + t + " reel " + r.index() + ": " + r.spinPhase());
                    if (r.locked()) {
                        assertEquals(0.0d, r.spinPhase(), "a locked reel draws aligned");
                    }
                }
            }
        }

        @Test
        @DisplayName("the phase actually scrolls, and the columns do not scroll in lockstep")
        void spinPhaseActuallyMoves() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.CRETAN_BULL);

            // The range check above is also enforced by Reel's own constructor, so on its own it
            // would pass against a phase hard-coded to 0. These two assertions would not.
            var distinct = new java.util.HashSet<Double>();
            for (long t = 0; t < 600; t += 13) {
                clock.set(t);
                distinct.add(roll.reels().get(0).spinPhase());
            }
            assertTrue(distinct.size() > 20, "a strip that never moves is not scrolling: " + distinct.size());

            clock.set(17L);
            var reels = roll.reels();
            assertNotEquals(reels.get(0).spinPhase(), reels.get(1).spinPhase(),
                    "columns scrolling in lockstep read as one wide strip, not three reels");
            assertNotEquals(reels.get(1).spinPhase(), reels.get(2).spinPhase());
        }
    }

    // ------------------------------------------------------------------ act one is untouched

    /**
     * The behaviour the whole rework exists to produce: a kill that dropped something rare must be
     * indistinguishable from one that did not, right up until the ordinary result has been read.
     * Everything here would have failed against the previous design, where a jackpot bought extra
     * spin time and raised a flag the HUD painted gold from the first frame.
     */
    @Nested
    @DisplayName("a jackpot is invisible until act one has finished")
    class ActOneIsUntouched {

        @Test
        @DisplayName("the act-one boundaries are identical with and without a jackpot")
        void actOneTimingIsIdentical() {
            var plainClock = new FixedClock();
            var plain = new SlotRoll(CFG, plainClock);
            plain.start(MythologicalCreature.MINOS_INQUISITOR);
            plain.offerDrop(drop("Ancient Claw"));

            var luckyClock = new FixedClock();
            var lucky = new SlotRoll(CFG, luckyClock);
            lucky.start(MythologicalCreature.MINOS_INQUISITOR);
            lucky.offerDrop(rare("Chimera"));

            for (long t = 0; t < SETTLE_END; t++) {
                plainClock.set(t);
                luckyClock.set(t);
                assertEquals(plain.state(), lucky.state(), "act one diverged at t=" + t);
                assertEquals(locks(plain), locks(lucky), "a reel locked at a different time at t=" + t);
                assertFalse(lucky.inJackpotSequence(), "act two must not have started at t=" + t);
                assertEquals(0.0d, lucky.jackpotIntroProgress(), "no gold in act one, at t=" + t);
            }
        }

        @Test
        @DisplayName("the reels show the real drops through act one, not the jackpot symbol")
        void actOneShowsTheRealDrops() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var jackpot = rare("Chimera");
            var common = drop("Ancient Claw");
            roll.offerDrop(jackpot);
            roll.offerDrop(common);

            clock.set(LOCK_2);
            assertEquals(RollState.SETTLED, roll.state());
            assertEquals(List.of(jackpot, common, jackpot), symbols(roll),
                    "the ordinary symbol policy, unchanged: rare, then the other drop, then cycled");
            assertFalse(roll.inJackpotSequence());
        }

        @Test
        @DisplayName("the jackpot flag latches in act one even though nothing on screen shows it")
        void flagLatchesWithoutShowing() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            clock.set(50L);
            roll.offerDrop(rare("Chimera"));

            assertTrue(roll.jackpot(), "the banner is recorded the moment it is parsed");
            assertEquals(RollState.SPINNING, roll.state(), "and buys the spin no extra time at all");
            assertFalse(roll.inJackpotSequence());
        }
    }

    // ------------------------------------------------------------------ act two

    @Nested
    @DisplayName("the jackpot sequence")
    class JackpotSequence {

        /** A roll that captured one rare drop at t=0, i.e. the ordinary lucky-kill shape. */
        private SlotRoll lucky(FixedClock clock, LootDrop... drops) {
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            for (LootDrop d : drops) {
                roll.offerDrop(d);
            }
            return roll;
        }

        @Test
        @DisplayName("every act-two boundary falls on the exact millisecond")
        void actTwoBoundariesAreExact() {
            var clock = new FixedClock();
            var roll = lucky(clock, rare("Chimera"));

            clock.set(J_INTRO_START - 1);
            assertEquals(RollState.SETTLED, roll.state());
            clock.set(J_INTRO_START);
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            clock.set(J_SPIN_START - 1);
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            clock.set(J_SPIN_START);
            assertEquals(RollState.JACKPOT_SPIN, roll.state());
            clock.set(J_LOCK_0 - 1);
            assertEquals(RollState.JACKPOT_SPIN, roll.state());
            clock.set(J_LOCK_0);
            assertEquals(RollState.JACKPOT_LOCK, roll.state());
            clock.set(J_LOCK_2 - 1);
            assertEquals(RollState.JACKPOT_LOCK, roll.state());
            clock.set(J_LOCK_2);
            assertEquals(RollState.JACKPOT_HOLD, roll.state());
            clock.set(J_HOLD_END - 1);
            assertEquals(RollState.JACKPOT_HOLD, roll.state());
            clock.set(J_HOLD_END);
            assertEquals(RollState.FADING, roll.state());
            clock.set(J_FADE_END - 1);
            assertEquals(RollState.FADING, roll.state());
            clock.set(J_FADE_END);
            assertEquals(RollState.IDLE, roll.state());
            assertFalse(roll.active());
        }

        @Test
        @DisplayName("inJackpotSequence covers exactly the four JACKPOT_ phases")
        void inSequenceCoversFourPhases() {
            var clock = new FixedClock();
            var roll = lucky(clock, rare("Chimera"));
            for (long t = 0; t <= J_FADE_END; t++) {
                clock.set(t);
                boolean expected = t >= J_INTRO_START && t < J_HOLD_END;
                assertEquals(expected, roll.inJackpotSequence(), "at t=" + t + " state " + roll.state());
            }
        }

        @Test
        @DisplayName("the reels are already turning while the gold is still washing in")
        void introSpinsUnderneathTheGoldWash() {
            var clock = new FixedClock();
            var jackpot = rare("Chimera");
            var common = drop("Ancient Claw");
            var roll = lucky(clock, jackpot, common);

            // The frame before act two, act one's real result is still standing and locked.
            clock.set(J_INTRO_START - 1);
            assertEquals(RollState.SETTLED, roll.state());
            assertEquals(List.of(true, true, true), locks(roll));
            assertEquals(List.of(jackpot, common, jackpot), symbols(roll));

            // The instant act two opens, every column breaks loose - the wash and the spin-up run
            // together, so the machine is moving as it turns gold rather than changing colour first.
            clock.set(J_INTRO_START);
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            assertEquals(List.of(false, false, false), locks(roll),
                    "the reels break loose with the gold, not after it");
            assertEquals(List.of(jackpot, jackpot, jackpot), symbols(roll),
                    "the destination is committed from the first frame of act two");
            assertTrue(roll.jackpotIntroProgress() < 1.0d, "the gold is still arriving");

            // Still spinning, and still gold-ramping, right up to the end of the intro.
            clock.set(J_SPIN_START - 1);
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            assertEquals(List.of(false, false, false), locks(roll));
            assertEquals(List.of(jackpot, jackpot, jackpot), symbols(roll));

            // Crossing into JACKPOT_SPIN changes nothing about the reels; only the wash has finished.
            clock.set(J_SPIN_START);
            assertEquals(List.of(false, false, false), locks(roll));
            assertEquals(1.0d, roll.jackpotIntroProgress(), 1.0e-9d, "wash complete, reels still turning");
        }

        @Test
        @DisplayName("the reels spin up together and land one at a time, all on the same item")
        void threeOfAKindLandsStaggered() {
            var clock = new FixedClock();
            var jackpot = rare("Chimera");
            var roll = lucky(clock, jackpot, drop("Ancient Claw"));

            clock.set(J_SPIN_START);
            assertEquals(List.of(false, false, false), locks(roll), "every reel spins up again");
            assertEquals(List.of(jackpot, jackpot, jackpot), symbols(roll),
                    "the destination is committed before they get there");

            clock.set(J_LOCK_0);
            assertEquals(List.of(true, false, false), locks(roll));
            clock.set(J_LOCK_1);
            assertEquals(List.of(true, true, false), locks(roll));
            clock.set(J_LOCK_2);
            assertEquals(List.of(true, true, true), locks(roll));
            assertEquals(List.of(jackpot, jackpot, jackpot), symbols(roll), "three of a kind");

            clock.set(J_HOLD_END - 1);
            assertEquals(List.of(jackpot, jackpot, jackpot), symbols(roll), "held through the celebration");
            clock.set(J_FADE_END - 1);
            assertEquals(List.of(jackpot, jackpot, jackpot), symbols(roll), "and through the fade");
        }

        @Test
        @DisplayName("a reel spinning in act two is unlocked but still reports where it is going")
        void spinningJackpotReelsCarryTheirDestination() {
            var clock = new FixedClock();
            var jackpot = rare("Chimera");
            var roll = lucky(clock, jackpot);

            clock.set(J_LOCK_0 + 20);
            var reels = roll.reels();
            assertTrue(reels.get(0).locked());
            assertFalse(reels.get(2).locked(), "the last column is still travelling");
            assertEquals(jackpot, reels.get(2).symbol(),
                    "a renderer must be able to blur the right item past");
            assertEquals(0.0d, reels.get(0).spinPhase(), "a landed reel draws aligned");

            // and the strip the last column is drawing genuinely moves, rather than being a still item
            var travelling = new java.util.HashSet<Double>();
            for (long t = J_LOCK_0 + 20; t < J_LOCK_2; t += 3) {
                clock.set(t);
                travelling.add(roll.reels().get(2).spinPhase());
            }
            assertTrue(travelling.size() > 20, "the last column is not scrolling: " + travelling.size());
        }

        @Test
        @DisplayName("no JACKPOT_ phase ever reports a null symbol on any reel")
        void neverANullSymbolInActTwo() {
            var clock = new FixedClock();
            var roll = lucky(clock, rare("Chimera"), drop("Ancient Claw"));
            int checked = 0;
            for (long t = 0; t <= J_FADE_END; t++) {
                clock.set(t);
                if (!roll.inJackpotSequence()) {
                    continue;
                }
                checked++;
                for (Reel r : roll.reels()) {
                    assertNotNull(r.symbol(),
                            "null symbol at t=" + t + " state " + roll.state() + " reel " + r.index());
                }
            }
            assertEquals(J_HOLD_END - J_INTRO_START, checked, "the whole sequence was actually walked");
        }

        @Test
        @DisplayName("the gold wash ramps 0 to 1 across the intro and stays pinned at 1 after it")
        void introProgressRampsThenPins() {
            var clock = new FixedClock();
            var roll = lucky(clock, rare("Chimera"));

            clock.set(SETTLE_END - 1);
            assertEquals(0.0d, roll.jackpotIntroProgress(), "no gold while the real result is settled");
            clock.set(J_INTRO_START);
            assertEquals(0.0d, roll.jackpotIntroProgress());
            clock.set(J_INTRO_START + 100);
            assertEquals(0.25d, roll.jackpotIntroProgress(), 1e-9);
            clock.set(J_INTRO_START + 200);
            assertEquals(0.5d, roll.jackpotIntroProgress(), 1e-9);
            clock.set(J_SPIN_START - 1);
            assertTrue(roll.jackpotIntroProgress() < 1.0d);
            clock.set(J_SPIN_START);
            assertEquals(1.0d, roll.jackpotIntroProgress());
            clock.set(J_LOCK_2);
            assertEquals(1.0d, roll.jackpotIntroProgress(), "the gold does not drain back out");
            clock.set(J_FADE_END - 1);
            assertEquals(1.0d, roll.jackpotIntroProgress(), "still gold while it fades");
            clock.set(J_FADE_END);
            assertEquals(0.0d, roll.jackpotIntroProgress(), "and nothing at all once idle");
        }

        @Test
        @DisplayName("the progress never runs backwards and never leaves [0,1]")
        void introProgressIsMonotonic() {
            var clock = new FixedClock();
            var roll = lucky(clock, rare("Chimera"));
            double previous = 0.0d;
            for (long t = 0; t < J_FADE_END; t++) {
                clock.set(t);
                double p = roll.jackpotIntroProgress();
                assertTrue(p >= 0.0d && p <= 1.0d, "out of range at t=" + t + ": " + p);
                assertTrue(p >= previous, "went backwards at t=" + t + ": " + previous + " -> " + p);
                previous = p;
            }
        }

        @Test
        @DisplayName("a roll with no jackpot never enters the sequence and reports no symbol")
        void noJackpotNoSequence() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.HARPY);
            roll.offerDrop(drop("Ancient Claw", 32));

            for (long t = 0; t <= FADE_END; t++) {
                clock.set(t);
                assertFalse(roll.inJackpotSequence(), "at t=" + t);
                assertNull(roll.jackpotSymbol(), "at t=" + t);
                assertEquals(0.0d, roll.jackpotIntroProgress(), "at t=" + t);
            }
            clock.set(FADE_END);
            assertEquals(RollState.IDLE, roll.state(), "an ordinary roll still ends at " + FADE_END);
        }

        @Test
        @DisplayName("an ordinary drop never raises the jackpot flag")
        void ordinaryDropIsNoJackpot() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.HARPY);
            roll.offerDrop(drop("Ancient Claw", 32));
            assertFalse(roll.jackpot());
            assertNull(roll.jackpotSymbol());
        }

        @Test
        @DisplayName("a jackpot with no other drops at all still gets its whole celebration")
        void jackpotAloneStillCelebrates() {
            var clock = new FixedClock();
            var only = rare("Chimera");
            var roll = lucky(clock, only);

            clock.set(LOCK_2);
            assertEquals(List.of(only, only, only), symbols(roll), "act one already cycles it across");
            clock.set(J_LOCK_2);
            assertEquals(RollState.JACKPOT_HOLD, roll.state());
            assertEquals(List.of(only, only, only), symbols(roll));
            assertEquals(only, roll.jackpotSymbol());
            clock.set(J_FADE_END);
            assertEquals(RollState.IDLE, roll.state());
        }

        @Test
        @DisplayName("the winning symbol is readable throughout act one, before anything shows it")
        void symbolIsKnownEarly() {
            var clock = new FixedClock();
            var jackpot = rare("Chimera");
            var roll = lucky(clock, jackpot);

            clock.set(10L);
            assertEquals(RollState.SPINNING, roll.state());
            assertEquals(jackpot, roll.jackpotSymbol(), "a HUD can preload the sprite this early");
            clock.set(J_LOCK_2);
            assertEquals(jackpot, roll.jackpotSymbol());
            clock.set(J_FADE_END);
            assertNull(roll.jackpotSymbol(), "and it is gone with the roll");
        }
    }

    // ------------------------------------------------------------------ which jackpot wins

    @Nested
    @DisplayName("choosing the symbol when more than one drop is rare")
    class WhichJackpotWins {

        @Test
        @DisplayName("the reels converge on the better rare, not the one announced first")
        void bestRareWinsNotFirst() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var firstButSmall = rare("Crown of Greed", 1);
            roll.offerDrop(firstButSmall);
            clock.set(300L);
            var secondButBigger = rare("Chimera", 3);
            roll.offerDrop(secondButBigger);

            clock.set(J_LOCK_2);
            assertEquals(secondButBigger, roll.jackpotSymbol(),
                    "the bigger prize is the one worth three columns");
            assertEquals(List.of(secondButBigger, secondButBigger, secondButBigger), symbols(roll));
        }

        @Test
        @DisplayName("two equally interesting rares fall back to arrival order")
        void equalRaresBreakTieByArrival() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var first = rare("Crown of Greed");
            roll.offerDrop(first);
            clock.set(300L);
            roll.offerDrop(rare("Chimera"));

            clock.set(J_LOCK_2);
            assertEquals(first, roll.jackpotSymbol());
        }

        @Test
        @DisplayName("a big ordinary drop never becomes the jackpot symbol")
        void rarityBeatsSize() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var small = rare("Chimera", 1);
            roll.offerDrop(small);
            roll.offerDrop(drop("Mythological Bone Fragment", 64));

            clock.set(J_LOCK_2);
            assertEquals(small, roll.jackpotSymbol(), "the banner is what makes it a jackpot, not the stack");
        }

        @Test
        @DisplayName("a rare arriving mid-celebration cannot rewrite the reels landing in front of the player")
        void symbolIsFrozenWhenActTwoBegins() {
            // A 3800ms loot window is still open while act two is playing, which is the only way
            // this can happen at all -- and exactly why the symbol is frozen at the sequence start.
            var wide = new SlotRollConfig(3, 1000L, 200L, 3800L, 500L, 100L, 400L, 600L, 100L, 800L);
            var clock = new FixedClock();
            var roll = new SlotRoll(wide, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var early = rare("Crown of Greed", 1);
            roll.offerDrop(early);

            clock.set(J_SPIN_START + 50);
            assertEquals(RollState.JACKPOT_SPIN, roll.state());
            roll.offerDrop(rare("Chimera", 64));   // would outrank it, but arrives mid-spin

            assertEquals(early, roll.jackpotSymbol(), "the columns were already committed");
            clock.set(J_LOCK_2);
            assertEquals(List.of(early, early, early), symbols(roll));
        }
    }

    // ------------------------------------------------------------------ how late is too late

    @Nested
    @DisplayName("how late a banner can arrive and still earn the celebration")
    class Lateness {

        @Test
        @DisplayName("a banner arriving after every reel locked still fires the whole sequence")
        void bannerDuringSettleStillFires() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MANTICORE);
            var common = drop("Ancient Claw");
            roll.offerDrop(common);

            clock.set(LOCK_2 + 100);
            assertEquals(RollState.SETTLED, roll.state());
            var late = rare("Chimera");
            roll.offerDrop(late);

            assertTrue(roll.jackpot(), "the banner must not be swallowed just because it came last");
            assertEquals(List.of(common, common, common), symbols(roll),
                    "a locked act-one reel is never rewritten under the player");

            clock.set(SETTLE_END);
            assertEquals(RollState.JACKPOT_INTRO, roll.state(), "the loot window may outlast the locks");
            clock.set(J_LOCK_2);
            assertEquals(List.of(late, late, late), symbols(roll));
            clock.set(J_FADE_END);
            assertEquals(RollState.IDLE, roll.state());
        }

        @Test
        @DisplayName("one millisecond before the settle ends still earns it; exactly on the boundary does not")
        void theArmingBoundaryIsExact() {
            var justInTime = new FixedClock();
            var early = new SlotRoll(CFG, justInTime);
            early.start(MythologicalCreature.KING_MINOS);
            early.offerDrop(drop("Crown of Greed"));
            justInTime.set(SETTLE_END - 1);
            early.offerDrop(rare("Chimera"));
            justInTime.set(SETTLE_END);
            assertEquals(RollState.JACKPOT_INTRO, early.state(), "one millisecond earlier still buys act two");

            var onTheDot = new FixedClock();
            var boundary = new SlotRoll(CFG, onTheDot);
            boundary.start(MythologicalCreature.KING_MINOS);
            boundary.offerDrop(drop("Crown of Greed"));
            onTheDot.set(SETTLE_END);
            boundary.offerDrop(rare("Chimera"));
            assertEquals(RollState.FADING, boundary.state(), "exactly on the boundary, the fade wins");
            assertTrue(boundary.jackpot(), "and the banner is still reported");
            assertNull(boundary.jackpotSymbol(), "there is simply no sequence for it to headline");
            onTheDot.set(FADE_END);
            assertEquals(RollState.IDLE, boundary.state(), "the roll ends when it was always going to");
        }

        @Test
        @DisplayName("a banner arriving during the fade is reported but does not rewind the roll")
        void bannerDuringFadeDoesNotRewind() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MANTICORE);
            roll.offerDrop(drop("Ancient Claw"));

            clock.set(SETTLE_END + 20);
            assertEquals(RollState.FADING, roll.state());

            var late = rare("Chimera");
            roll.offerDrop(late);
            assertEquals(RollState.FADING, roll.state(),
                    "a half-faded panel must not snap back to full opacity and re-spin");
            assertFalse(roll.inJackpotSequence());
            assertTrue(roll.jackpot(), "it is still surfaced, it just arrived after the machine committed");
            assertTrue(roll.capturedDrops().contains(late));

            clock.set(FADE_END);
            assertEquals(RollState.IDLE, roll.state(), "and the roll still ends when it was going to");
        }

        @Test
        @DisplayName("a banner arriving once the roll has gone idle cannot revive it")
        void bannerAfterIdle() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MANTICORE);
            clock.set(FADE_END);
            roll.offerDrop(rare("Chimera"));
            assertEquals(RollState.IDLE, roll.state());
            assertFalse(roll.jackpot());
            assertNull(roll.jackpotSymbol());
        }

        @Test
        @DisplayName("the first rare decides whether there is a sequence, even when a better one is later")
        void firstRareArmsEvenIfLaterOneIsBetter() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            clock.set(SETTLE_END - 1);
            var armer = rare("Crown of Greed", 1);
            roll.offerDrop(armer);
            var better = rare("Chimera", 9);
            roll.offerDrop(better);   // same millisecond, so still before the cutoff

            clock.set(J_LOCK_2);
            assertEquals(RollState.JACKPOT_HOLD, roll.state());
            assertEquals(better, roll.jackpotSymbol(), "armed by the first, headlined by the best");
        }
    }

    // ------------------------------------------------------------------ offer guards

    @Nested
    @DisplayName("offerDrop guards")
    class OfferGuards {

        @Test
        @DisplayName("a drop offered while idle is ignored")
        void ignoredWhileIdle() {
            var roll = new SlotRoll(CFG, new FixedClock());
            roll.offerDrop(rare("Chimera"));
            assertEquals(RollState.IDLE, roll.state());
            assertFalse(roll.jackpot());
            assertEquals(List.of(), roll.capturedDrops());
        }

        @Test
        @DisplayName("the loot window is inclusive on its last millisecond and shut one after")
        void ignoredPastLootWindow() {
            // an 800ms window, deliberately shorter than the 1000ms spin, so the window closes
            // while the roll is still very much alive
            var clock = new FixedClock();
            var roll = new SlotRoll(
                    new SlotRollConfig(3, 1000, 200, 800, 500, 100, 400, 600, 100, 800), clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var inside = drop("Ancient Claw");
            roll.offerDrop(inside);

            clock.set(800L);
            var lastMoment = drop("Griffin Feather");
            roll.offerDrop(lastMoment);
            assertEquals(List.of(inside, lastMoment), roll.capturedDrops(),
                    "800ms is still inside an 800ms window");

            clock.set(801L);
            roll.offerDrop(rare("Crown of Greed"));
            assertEquals(List.of(inside, lastMoment), roll.capturedDrops(),
                    "a drop one millisecond late belongs to no roll");
            assertFalse(roll.jackpot(), "a rejected drop cannot raise the flag either");
            clock.set(SETTLE_END);
            assertEquals(RollState.FADING, roll.state(), "and cannot buy a celebration either");
        }

        @Test
        @DisplayName("a null drop is ignored rather than thrown, because a parse miss is ordinary")
        void nullDropIsIgnored() {
            var roll = new SlotRoll(CFG, new FixedClock());
            roll.start(MythologicalCreature.SPHINX);
            roll.offerDrop(null);
            assertEquals(List.of(), roll.capturedDrops());
        }

        @Test
        @DisplayName("a drop arriving after the roll went idle cannot revive it")
        void dropAfterIdleCannotRevive() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MANTICORE);
            clock.set(FADE_END);
            roll.offerDrop(rare("Chimera"));
            assertEquals(RollState.IDLE, roll.state());
            assertEquals(List.of(), roll.capturedDrops());
        }
    }

    // ------------------------------------------------------------------ restart

    @Nested
    @DisplayName("restart policy")
    class Restart {

        @Test
        @DisplayName("a second kill mid-animation restarts the roll from scratch")
        void secondKillRestarts() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(drop("Chimera"));
            clock.set(LOCK_1);
            assertEquals(RollState.LOCKING, roll.state());

            roll.start(MythologicalCreature.KING_MINOS);
            assertEquals(RollState.SPINNING, roll.state(), "the fresh kill wins");
            assertEquals(MythologicalCreature.KING_MINOS, roll.creature().orElseThrow());
            assertFalse(roll.jackpot(), "the previous kill's drops must not carry over");
            assertEquals(List.of(), roll.capturedDrops());

            // the timeline is rebased on the restart instant
            clock.set(LOCK_1 + LOCK_0 - 1);
            assertEquals(RollState.SPINNING, roll.state());
            clock.set(LOCK_1 + LOCK_0);
            assertEquals(RollState.LOCKING, roll.state());
        }

        @Test
        @DisplayName("a second kill during the celebration abandons it and starts a plain roll")
        void secondKillAbandonsActTwo() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(rare("Chimera"));

            clock.set(J_LOCK_1);
            assertEquals(RollState.JACKPOT_LOCK, roll.state());
            long celebratingRoll = roll.rollId();

            roll.start(MythologicalCreature.KING_MINOS);
            assertEquals(RollState.SPINNING, roll.state(), "the freshest kill wins, mid-flourish included");
            assertFalse(roll.jackpot(), "and it inherits none of the previous kill's luck");
            assertNull(roll.jackpotSymbol());
            assertFalse(roll.inJackpotSequence());
            assertEquals(0.0d, roll.jackpotIntroProgress(), "the gold goes with it");
            assertNotEquals(celebratingRoll, roll.rollId(),
                    "the rollId edge is how a HUD knows to drop its per-roll flourish state");

            // and the new roll is an ordinary one, on its own rebased timeline
            clock.set(J_LOCK_1 + SETTLE_END);
            assertEquals(RollState.FADING, roll.state());
        }

        @Test
        @DisplayName("after a restart the new kill's drops are the ones on the reels")
        void restartTakesTheNewDrops() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(drop("Ancient Claw"));

            clock.set(300L);
            roll.start(MythologicalCreature.MANTICORE);
            var fresh = drop("Griffin Feather");
            roll.offerDrop(fresh);

            clock.set(300L + LOCK_2);
            assertEquals(List.of(fresh, fresh, fresh), symbols(roll));
        }

        @Test
        @DisplayName("restarting from idle behaves exactly like a first start")
        void restartFromIdle() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.HARPY);
            clock.set(FADE_END);
            assertEquals(RollState.IDLE, roll.state());

            roll.start(MythologicalCreature.SPHINX);
            assertEquals(RollState.SPINNING, roll.state());
            assertEquals(MythologicalCreature.SPHINX, roll.creature().orElseThrow());
        }
    }

    // ------------------------------------------------------------------ determinism

    @Nested
    @DisplayName("determinism")
    class Determinism {

        /** Runs one fixed script and returns a snapshot of every millisecond of the roll. */
        private List<String> script(int pollEveryMillis) {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            var log = new ArrayList<String>();
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            for (long t = 0; t <= J_FADE_END + 600; t++) {
                clock.set(t);
                if (t == 400L) {
                    roll.offerDrop(drop("Ancient Claw"));
                }
                if (t == 900L) {
                    roll.offerDrop(drop("Mythological Bone Fragment", 5));
                }
                if (t == 1300L) {
                    roll.offerDrop(rare("Chimera"));
                }
                if (pollEveryMillis > 0 && t % pollEveryMillis == 0) {
                    log.add(t + " " + roll.state() + " " + roll.jackpot()
                            + " " + roll.jackpotIntroProgress() + " " + symbols(roll));
                }
            }
            clock.set(J_FADE_END + 600);
            log.add("final " + roll.state() + " " + roll.reels());
            return log;
        }

        @Test
        @DisplayName("the same script produces byte-identical output twice")
        void repeatable() {
            assertEquals(script(1), script(1));
        }

        @Test
        @DisplayName("the script actually walks the whole two-act timeline")
        void scriptCoversBothActs() {
            var seen = new java.util.HashSet<String>();
            for (String line : script(1)) {
                int first = line.indexOf(' ');
                int second = line.indexOf(' ', first + 1);
                if (first > 0 && second > first) {
                    seen.add(line.substring(first + 1, second));
                }
            }
            for (RollState s : RollState.values()) {
                assertTrue(seen.contains(s.name()), "the determinism script never reached " + s);
            }
        }

        @Test
        @DisplayName("polling every millisecond and polling once at the end agree, act two included")
        void pollIndependent() {
            var dense = new FixedClock();
            var sparse = new FixedClock();
            var denseRoll = new SlotRoll(CFG, dense);
            var sparseRoll = new SlotRoll(CFG, sparse);
            denseRoll.start(MythologicalCreature.MINOS_INQUISITOR);
            sparseRoll.start(MythologicalCreature.MINOS_INQUISITOR);

            for (long t = 0; t <= J_LOCK_1; t++) {
                dense.set(t);
                if (t == 400L) {
                    denseRoll.offerDrop(drop("Ancient Claw"));
                }
                if (t == 1100L) {
                    denseRoll.offerDrop(rare("Chimera"));
                }
                denseRoll.reels(); // the HUD polling every frame must not influence anything
            }

            sparse.set(400L);
            sparseRoll.offerDrop(drop("Ancient Claw"));
            sparse.set(1100L);
            sparseRoll.offerDrop(rare("Chimera"));
            sparse.set(J_LOCK_1);

            assertEquals(RollState.JACKPOT_LOCK, sparseRoll.state(), "the comparison is inside act two");
            assertEquals(sparseRoll.reels(), denseRoll.reels());
            assertEquals(sparseRoll.state(), denseRoll.state());
            assertEquals(sparseRoll.jackpot(), denseRoll.jackpot());
            assertEquals(sparseRoll.jackpotSymbol(), denseRoll.jackpotSymbol());
            assertEquals(sparseRoll.jackpotIntroProgress(), denseRoll.jackpotIntroProgress());
        }

        @Test
        @DisplayName("a clock that does not start at zero changes nothing")
        void clockOriginIsIrrelevant() {
            var clock = new FixedClock(-1_234_567L);
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.KING_MINOS);
            var d = rare("Crown of Greed");
            roll.offerDrop(d);

            clock.advance(LOCK_2 - 1);
            assertEquals(RollState.LOCKING, roll.state());
            clock.advance(1);
            assertEquals(RollState.SETTLED, roll.state());
            assertEquals(List.of(d, d, d), symbols(roll));
            clock.advance(SETTLE_END - LOCK_2);
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            clock.advance(J_FADE_END - SETTLE_END);
            assertEquals(RollState.IDLE, roll.state());
        }
    }

    // ------------------------------------------------------------------ config

    @Nested
    @DisplayName("configuration")
    class Config {

        @Test
        @DisplayName("the shipped defaults are the documented three-reel machine")
        void defaultsAreSane() {
            var d = SlotRollConfig.defaults();
            assertEquals(3, d.reelCount());
            assertEquals(1200L, d.spinMillis());
            assertEquals(250L, d.lockStaggerMillis());
            assertEquals(3000L, d.lootWindowMillis());
            assertEquals(2500L, d.settleMillis());
            assertEquals(500L, d.fadeMillis());
            assertTrue(d.jackpotIntroMillis() > 0, "a gold wash of zero is not a wash");
            assertTrue(d.jackpotSpinMillis() > 0, "the reels have to visibly spin up again");
            assertTrue(d.jackpotLockStaggerMillis() > 0,
                    "three of a kind revealed simultaneously reveals nothing");
            assertTrue(d.jackpotHoldMillis() > 0, "the celebration has to land before it fades");
            assertTrue(d.lootWindowMillis() >= d.spinMillis() + 2 * d.lockStaggerMillis(),
                    "the loot window must outlast the locks so a late banner still counts");
        }

        @Test
        @DisplayName("the default celebration runs about four to five seconds end to end")
        void defaultSequenceLength() {
            var d = SlotRollConfig.defaults();
            long sequence = d.jackpotIntroMillis() + d.jackpotSpinMillis()
                    + (d.reelCount() - 1) * d.jackpotLockStaggerMillis()
                    + d.jackpotHoldMillis() + d.fadeMillis();
            assertTrue(sequence >= 4000L && sequence <= 5000L,
                    "a celebration that earns attention without overstaying, but this is " + sequence + "ms");
        }

        @Test
        @DisplayName("reelCount outside 1..5 and negative durations are rejected")
        void validation() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(0, 1, 1, 1, 1, 1, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(6, 1, 1, 1, 1, 1, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, -1, 1, 1, 1, 1, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, -1, 1, 1, 1, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, 1, -1, 1, 1, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, 1, 1, -1, 1, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, 1, 1, 1, -1, 1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, 1, 1, 1, 1, -1, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, 1, 1, 1, 1, 1, -1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, 1, 1, 1, 1, 1, 1, -1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SlotRollConfig(3, 1, 1, 1, 1, 1, 1, 1, 1, -1));
        }

        @Test
        @DisplayName("a one-reel machine steps straight from SPINNING to SETTLED, and skips JACKPOT_LOCK")
        void singleReelSkipsLocking() {
            var clock = new FixedClock();
            var roll = new SlotRoll(
                    new SlotRollConfig(1, 500, 200, 3000, 300, 100, 100, 200, 50, 400), clock);
            roll.start(MythologicalCreature.SPHINX);
            var d = rare("Ancient Claw");
            roll.offerDrop(d);

            clock.set(499L);
            assertEquals(RollState.SPINNING, roll.state());
            clock.set(500L);
            assertEquals(RollState.SETTLED, roll.state(), "there is no partial-lock phase with one reel");
            assertEquals(List.of(d), symbols(roll));

            clock.set(800L);        // settle ends at 500 + 300
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            clock.set(900L);        // + intro 100
            assertEquals(RollState.JACKPOT_SPIN, roll.state());
            clock.set(1100L);       // + spin 200: the only reel lands, so there is nothing to stagger
            assertEquals(RollState.JACKPOT_HOLD, roll.state(), "one column cannot land one at a time");
            assertEquals(List.of(d), symbols(roll));
            clock.set(1600L);       // + hold 400 + fade 100
            assertEquals(RollState.IDLE, roll.state());
        }

        @Test
        @DisplayName("zero stagger locks every reel together, in both acts")
        void zeroStaggerSkipsLocking() {
            var clock = new FixedClock();
            var roll = new SlotRoll(
                    new SlotRollConfig(5, 500, 0, 3000, 300, 100, 100, 200, 0, 400), clock);
            roll.start(MythologicalCreature.MANTICORE);
            roll.offerDrop(rare("Ancient Claw"));

            clock.set(499L);
            assertEquals(RollState.SPINNING, roll.state());
            clock.set(500L);
            assertEquals(RollState.SETTLED, roll.state());
            assertEquals(5, roll.reels().size());
            assertTrue(roll.reels().stream().allMatch(Reel::locked));

            clock.set(1100L);   // settle end 800, + intro 100 + spin 200: all five land at once
            assertEquals(RollState.JACKPOT_HOLD, roll.state());
            assertTrue(roll.reels().stream().allMatch(Reel::locked));
        }

        @Test
        @DisplayName("a zero-length jackpot sequence collapses to the ordinary fade")
        void zeroLengthSequenceIsUnobservable() {
            var clock = new FixedClock();
            var roll = new SlotRoll(
                    new SlotRollConfig(3, 500, 100, 3000, 300, 100, 0, 0, 0, 0), clock);
            roll.start(MythologicalCreature.HARPY);
            roll.offerDrop(rare("Chimera"));

            long settleEnd = 500 + 2 * 100 + 300;   // 1000
            clock.set(settleEnd - 1);
            assertEquals(RollState.SETTLED, roll.state());
            clock.set(settleEnd);
            assertEquals(RollState.FADING, roll.state(),
                    "a celebration with no duration is a celebration nobody sees");
            assertFalse(roll.inJackpotSequence());
            clock.set(settleEnd + 100);
            assertEquals(RollState.IDLE, roll.state());
        }

        @Test
        @DisplayName("all-zero durations settle and go idle on the start instant without hanging")
        void degenerateAllZeroConfig() {
            var clock = new FixedClock();
            var roll = new SlotRoll(new SlotRollConfig(3, 0, 0, 0, 0, 0, 0, 0, 0, 0), clock);
            roll.start(MythologicalCreature.HARPY);
            assertEquals(RollState.IDLE, roll.state(), "nothing to show means nothing is shown");
            assertFalse(roll.active());
        }

        @Test
        @DisplayName("null constructor arguments are rejected up front")
        void constructorRejectsNulls() {
            assertThrows(NullPointerException.class, () -> new SlotRoll(null, new FixedClock()));
            assertThrows(NullPointerException.class, () -> new SlotRoll(CFG, null));
            var roll = new SlotRoll(CFG, new FixedClock());
            assertThrows(NullPointerException.class, () -> roll.start(null));
        }
    }

    // ------------------------------------------------------------------ Reel record

    @Nested
    @DisplayName("Reel invariants")
    class ReelRecord {

        @Test
        @DisplayName("a locked reel without a symbol, a negative index and an out-of-range phase are rejected")
        void invariants() {
            assertThrows(IllegalArgumentException.class, () -> new Reel(-1, false, null, 0.0d));
            assertThrows(IllegalArgumentException.class, () -> new Reel(0, true, null, 0.0d));
            assertThrows(IllegalArgumentException.class, () -> new Reel(0, false, null, 1.0d));
            assertThrows(IllegalArgumentException.class, () -> new Reel(0, false, null, -0.1d));
            assertThrows(IllegalArgumentException.class, () -> new Reel(0, false, null, Double.NaN));
        }

        @Test
        @DisplayName("an unlocked reel may carry a symbol, which is what act two needs")
        void unlockedReelMayCarryASymbol() {
            var r = new Reel(2, false, drop("Chimera"), 0.5d);
            assertFalse(r.locked());
            assertNotNull(r.symbol());
        }

        @Test
        @DisplayName("reels come back in left-to-right index order")
        void indexOrder() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.GAIA_CONSTRUCT);
            var reels = roll.reels();
            for (int i = 0; i < reels.size(); i++) {
                assertEquals(i, reels.get(i).index());
            }
        }

        @Test
        @DisplayName("NO_DROP is a stable, shared placeholder rather than a fresh object each time")
        void placeholderIsShared() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOTAUR);
            clock.set(LOCK_2);
            assertSame(SlotRoll.NO_DROP, roll.reels().get(0).symbol());
            assertFalse(SlotRoll.NO_DROP.rare(), "the placeholder must never trigger a jackpot flourish");
        }

        @Test
        @DisplayName("the reel list is a snapshot the caller cannot mutate")
        void reelsAreImmutable() {
            var roll = new SlotRoll(CFG, new FixedClock());
            roll.start(MythologicalCreature.MINOTAUR);
            var reels = roll.reels();
            assertThrows(UnsupportedOperationException.class, () -> reels.add(new Reel(9, false, null, 0.0d)));
            assertThrows(UnsupportedOperationException.class,
                    () -> roll.capturedDrops().add(drop("nope")));
        }
    }


    // ------------------------------------------------------------------ hostile inputs

    /**
     * The adversarial pass: inputs a config file, a long-running client or an unlucky chat order
     * can genuinely produce, and which a timeline built out of plain {@code +} gets wrong.
     */
    @Nested
    @DisplayName("hostile inputs")
    class Hostile {

        @Test
        @DisplayName("durations near Long.MAX_VALUE spin forever instead of wrapping into instant IDLE")
        void hugeDurationsDoNotWrap() {
            var clock = new FixedClock();
            var roll = new SlotRoll(
                    new SlotRollConfig(3, Long.MAX_VALUE, 1L, 1000L, 100L, 100L, 0L, 0L, 0L, 0L), clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);

            // Adding the stagger on top of a MAX_VALUE spin wrapped the last lock negative, so the
            // sweep read the roll as long finished and killed it on the millisecond it started.
            assertEquals(RollState.SPINNING, roll.state());
            assertTrue(roll.active());
            clock.set(Long.MAX_VALUE - 1);
            assertEquals(RollState.SPINNING, roll.state(), "an absurd spin is absurdly long, not absent");
        }

        @Test
        @DisplayName("absurd jackpot durations saturate into an endless celebration, not an absent one")
        void hugeJackpotDurationsDoNotWrap() {
            var clock = new FixedClock();
            var roll = new SlotRoll(
                    new SlotRollConfig(2, 1000L, 0L, 1000L, 10L, 10L,
                            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), clock);
            roll.start(MythologicalCreature.SPHINX);
            roll.offerDrop(rare("Chimera"));

            assertTrue(roll.jackpot());
            clock.set(1010L);   // spin 1000 + settle 10
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            clock.set(Long.MAX_VALUE - 1);
            assertEquals(RollState.JACKPOT_INTRO, roll.state(),
                    "an absurd gold wash is absurdly long, not skipped");
            assertEquals(1.0d, roll.jackpotIntroProgress(), 1e-9, "and it is fully washed in by then");
        }

        @Test
        @DisplayName("a five-reel jackpot stagger near Long.MAX_VALUE saturates rather than wrapping")
        void hugeJackpotStaggerDoesNotWrap() {
            var clock = new FixedClock();
            var roll = new SlotRoll(
                    new SlotRollConfig(5, 100L, 0L, 1000L, 10L, 10L, 0L, 0L, Long.MAX_VALUE, 10L), clock);
            roll.start(MythologicalCreature.SPHINX);
            roll.offerDrop(rare("Chimera"));

            clock.set(110L);
            assertEquals(RollState.JACKPOT_LOCK, roll.state(),
                    "reel 0 lands at once, the rest are absurdly far away");
            var reels = roll.reels();
            assertTrue(reels.get(0).locked());
            assertFalse(reels.get(4).locked());
            for (Reel r : reels) {
                assertNotNull(r.symbol(), "even a reel that will never land knows what it is chasing");
            }
        }

        @Test
        @DisplayName("a clock reading near Long.MAX_VALUE does not wrap the timeline")
        void clockNearMaxDoesNotWrap() {
            var clock = new FixedClock(Long.MAX_VALUE - 100L);
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.SPHINX);

            assertEquals(RollState.SPINNING, roll.state());
            var d = drop("Ancient Claw");
            roll.offerDrop(d);
            assertEquals(List.of(d), roll.capturedDrops(), "the loot window must not wrap shut either");
        }

        @Test
        @DisplayName("the phase sequence never runs backwards, on either path")
        void phasesNeverRegress() {
            assertMonotonic(1950L, "a banner too late to arm anything");
            assertMonotonic(1150L, "a banner that earns the whole celebration");
            assertMonotonic(-1L, "no banner at all");
        }

        private void assertMonotonic(long rareAt, String why) {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.KING_MINOS);
            RollState worst = RollState.SPINNING;
            for (long t = 0; t <= 6000; t++) {
                clock.set(t);
                if (t == 100L) {
                    roll.offerDrop(drop("Ancient Claw"));
                }
                if (t == 1150L) {
                    roll.offerDrop(drop("Griffin Feather", 3));
                }
                if (t == rareAt) {
                    roll.offerDrop(rare("Chimera"));
                }
                var s = roll.state();
                if (s == RollState.IDLE) {
                    return;
                }
                assertTrue(s.ordinal() >= worst.ordinal(),
                        why + ": regressed from " + worst + " to " + s + " at t=" + t);
                worst = s;
            }
            throw new AssertionError(why + ": the roll never ended");
        }

        @Test
        @DisplayName("two value-identical drop lines fill the reels rather than leaving a hole")
        void duplicateDropsAreHandled() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOTAUR);
            var first = drop("Ancient Claw");
            var second = drop("Ancient Claw");   // equal by value: two separate lines of the same item
            roll.offerDrop(first);
            roll.offerDrop(second);

            clock.set(LOCK_2);
            assertEquals(List.of(first, first, first), symbols(roll));
            assertEquals(2, roll.capturedDrops().size(), "both lines are still captured for the caption");
        }

        @Test
        @DisplayName("an ordinary drop arriving during the fade cannot rewrite a locked reel")
        void ordinaryDropDuringFadeChangesNothing() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.HARPY);
            var early = drop("Ancient Claw");
            roll.offerDrop(early);

            clock.set(SETTLE_END + 10);
            roll.offerDrop(drop("Griffin Feather", 64));   // outranks it, but arrives far too late
            assertEquals(List.of(early, early, early), symbols(roll));
            assertEquals(RollState.FADING, roll.state());
        }

        @Test
        @DisplayName("across five reels an act-one lock is left-to-right, permanent, and never rewritten")
        void locksAreLeftToRightPermanentAndStable() {
            var clock = new FixedClock();
            var cfg = new SlotRollConfig(5, 1000, 200, 5000, 500, 100, 400, 600, 100, 800);
            var roll = new SlotRoll(cfg, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var settled = new LootDrop[5];
            long settleEnd = 1000 + 4 * 200 + 500;   // 2300, where act one hands over

            // Strictly act one. At settleEnd act two opens and every reel deliberately breaks loose
            // again to spin up under the gold wash, so "a lock is permanent" is an invariant of one
            // act, not of the whole roll - jackpotLocksAreLeftToRightAndPermanent covers the other side.
            for (long t = 0; t < settleEnd; t++) {
                clock.set(t);
                if (t == 100L) {
                    roll.offerDrop(drop("Ancient Claw"));
                }
                if (t == 1300L) {
                    roll.offerDrop(rare("Chimera"));       // lands between two locks
                }
                if (t == 1900L) {
                    roll.offerDrop(drop("Griffin Feather", 40));
                }
                var reels = roll.reels();
                boolean seenSpinning = false;
                for (Reel r : reels) {
                    if (!r.locked()) {
                        seenSpinning = true;
                        assertNull(settled[r.index()], "reel " + r.index() + " unlocked again at t=" + t);
                        continue;
                    }
                    assertFalse(seenSpinning,
                            "reel " + r.index() + " locked while a reel to its left spun, at t=" + t);
                    if (settled[r.index()] == null) {
                        settled[r.index()] = r.symbol();
                    } else {
                        assertEquals(settled[r.index()], r.symbol(),
                                "reel " + r.index() + " changed symbol under the player at t=" + t);
                    }
                }
            }
            for (int i = 0; i < 5; i++) {
                assertNotNull(settled[i], "reel " + i + " never locked");
            }

            // And the handover itself: one millisecond earlier every reel was locked on act one's
            // result; on the instant act two opens they are all turning again, with the gold only
            // beginning to arrive. That overlap is the point - the machine moves as it goes gold.
            clock.set(settleEnd - 1);
            assertEquals(RollState.SETTLED, roll.state());
            assertTrue(roll.reels().stream().allMatch(Reel::locked), "act one ends fully locked");
            clock.set(settleEnd);
            assertEquals(RollState.JACKPOT_INTRO, roll.state());
            assertTrue(roll.reels().stream().noneMatch(Reel::locked),
                    "act two opens with every reel already spinning");
            assertTrue(roll.jackpotIntroProgress() < 1.0d, "and with the gold still washing in");
        }

        @Test
        @DisplayName("an act-two lock is also left-to-right and permanent within its own act")
        void jackpotLocksAreLeftToRightAndPermanent() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            var jackpot = rare("Chimera");
            roll.offerDrop(jackpot);

            boolean[] landed = new boolean[3];
            for (long t = J_SPIN_START; t < J_FADE_END; t++) {
                clock.set(t);
                var reels = roll.reels();
                boolean seenSpinning = false;
                for (Reel r : reels) {
                    assertEquals(jackpot, r.symbol(), "every column chases the same item, at t=" + t);
                    if (!r.locked()) {
                        seenSpinning = true;
                        assertFalse(landed[r.index()], "reel " + r.index() + " unlanded at t=" + t);
                        continue;
                    }
                    assertFalse(seenSpinning,
                            "reel " + r.index() + " landed while a reel to its left spun, at t=" + t);
                    landed[r.index()] = true;
                }
            }
            for (int i = 0; i < 3; i++) {
                assertTrue(landed[i], "reel " + i + " never landed");
            }
        }

        @Test
        @DisplayName("Integer.MAX_VALUE and 1 rank against each other without subtraction overflow")
        void extremeStackCountsRank() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.SPHINX);
            var small = new LootDrop("Ancient Claw", "a", 1, false);
            var huge = new LootDrop("Griffin Feather", "a", Integer.MAX_VALUE, false);
            roll.offerDrop(small);
            roll.offerDrop(huge);

            clock.set(LOCK_2);
            assertEquals(huge, symbols(roll).get(0), "the bigger stack is the more interesting one");
            assertEquals(small, symbols(roll).get(1));
        }

        @Test
        @DisplayName("two rares with Integer.MAX_VALUE and 1 stacks pick a winner without overflow")
        void extremeStackCountsRankAmongRares() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.SPHINX);
            roll.offerDrop(new LootDrop("Crown of Greed", "d", 1, true));
            var huge = new LootDrop("Chimera", "d", Integer.MAX_VALUE, true);
            roll.offerDrop(huge);

            clock.set(J_LOCK_2);
            assertEquals(huge, roll.jackpotSymbol());
        }

        @Test
        @DisplayName("surrogate pairs and a 100k-character item name pass through untouched")
        void hostileItemNames() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOTAUR);
            var emoji = new LootDrop("🐍🐍", "d", 1, true);
            var enormous = new LootDrop("x".repeat(100_000), "a", 2, false);
            roll.offerDrop(emoji);
            roll.offerDrop(enormous);

            clock.set(LOCK_2);
            assertEquals(emoji, symbols(roll).get(0), "rarity still wins whatever the name looks like");
            assertEquals(enormous, symbols(roll).get(1));
            clock.set(J_LOCK_2);
            assertEquals(emoji, roll.jackpotSymbol());
        }

        @Test
        @DisplayName("reels() always has exactly reelCount entries while active, and none when not")
        void reelCountIsStableAcrossTheWholeTimeline() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.GAIA_CONSTRUCT);
            roll.offerDrop(rare("Chimera"));
            for (long t = 0; t <= J_FADE_END + 100; t++) {
                clock.set(t);
                assertEquals(roll.active() ? 3 : 0, roll.reels().size(), "at t=" + t);
            }
        }

        @Test
        @DisplayName("one instance reused across twenty kills never leaks state between them")
        void repeatedReuseLeaksNothing() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            for (int kill = 0; kill < 20; kill++) {
                long base = kill * 10_000L;
                clock.set(base);
                roll.start(kill % 2 == 0 ? MythologicalCreature.MINOS_INQUISITOR : MythologicalCreature.HARPY);
                assertFalse(roll.jackpot(), "kill " + kill + " must not inherit the previous jackpot");
                assertNull(roll.jackpotSymbol(), "kill " + kill + " starts with no celebration pending");
                assertEquals(List.of(), roll.capturedDrops(), "kill " + kill + " starts with no loot");

                var d = drop("Ancient Claw " + kill);
                roll.offerDrop(d);
                if (kill % 3 == 0) {
                    roll.offerDrop(rare("Chimera " + kill));
                }
                clock.set(base + LOCK_2);
                assertEquals(kill % 3 == 0, roll.jackpot(), "kill " + kill);
                assertTrue(roll.capturedDrops().contains(d));

                clock.set(base + SETTLE_END);
                assertEquals(kill % 3 == 0 ? RollState.JACKPOT_INTRO : RollState.FADING, roll.state(),
                        "kill " + kill + " took the wrong branch at the settle boundary");
            }
            clock.set(20 * 10_000L + J_FADE_END + 1000);
            assertEquals(RollState.IDLE, roll.state());
        }
    }
}
