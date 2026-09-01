package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.util.FixedClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The continuity suite: what "seamless" actually means, written down as arithmetic.
 *
 * <p>The complaint that produced {@link ReelScroll} was that the machine stopped and then replayed
 * a spin. Two separate things caused that, and both are checked rather than left to the eye:
 *
 * <ol>
 *   <li>the <b>reels</b> stopped, which is {@link SlotRoll}'s business and is asserted in
 *       {@code SlotRollTest.theReelsNeverStopBeforeTheCelebration}; and</li>
 *   <li>the <b>strip</b> stopped -- or rather teleported, because the scroll was derived by
 *       dividing the wall clock by a cell period and the period changed mid-roll. That is this
 *       class's business.</li>
 * </ol>
 *
 * <p>The property being pinned is not "the numbers look reasonable". It is that the drawn strip
 * position is a continuous, non-decreasing function of time across the whole sequence: it never
 * runs backwards, never restarts, and never advances by more in one millisecond than the fastest
 * configured speed allows. A renderer that satisfies those three cannot show a jump, because a
 * jump is exactly their negation.
 *
 * <p>The shipped rates are the numbers {@code SlotMachineHud} uses; they are copied here rather
 * than imported because that class needs Minecraft on the classpath and this suite deliberately
 * does not.
 */
@DisplayName("the strip scrolls continuously across the whole sequence")
class ReelScrollTest {

    /** {@code SlotMachineHud.STRIP_CELL_MILLIS}: one cell per 150ms in the ordinary spin. */
    private static final long ORDINARY = 150L;

    /** {@code SlotMachineHud.JACKPOT_CELL_MILLIS}: better than twice that in the celebration. */
    private static final long FAST = 65L;

    /** Stands in for {@code SlotMachineHud.STRIP_PITCH}, the cell height in pixels. */
    private static final int PITCH = 30;

    /** The shipped roll timings, so the boundaries under test are the ones players see. */
    private static final SlotRollConfig CFG = SlotRollConfig.defaults();

    /** {@code SlotMachineHud.REEL_STRIP_LEAD_CELLS}: three cells and a third, per column. */
    private static final double LEAD = 3.0 + 1.0 / 3.0;

    private static LootDrop rare(String name) {
        return new LootDrop(name, "d", 1, true);
    }

    private static LootDrop drop(String name) {
        return new LootDrop(name, "a", 1, false);
    }

    // ------------------------------------------------------------------ the pure function

    @Nested
    @DisplayName("cellsTravelled")
    class Travel {

        @Test
        @DisplayName("it starts at zero and runs at the ordinary rate while there is no celebration")
        void ordinaryRate() {
            assertEquals(0.0, ReelScroll.cellsTravelled(0L, 0L, ReelScroll.NEVER, ORDINARY, FAST));
            assertEquals(1.0, ReelScroll.cellsTravelled(150L, 0L, ReelScroll.NEVER, ORDINARY, FAST),
                    1e-9);
            assertEquals(8.0, ReelScroll.cellsTravelled(1200L, 0L, ReelScroll.NEVER, ORDINARY, FAST),
                    1e-9);
        }

        @Test
        @DisplayName("the roll's own start is the origin, so the wall clock cannot shift the drum")
        void originIsTheRoll() {
            // The same elapsed time from three wildly different origins must read the same, which
            // is the property the old now/period division did not have: which symbols a short spin
            // showed used to depend on what the clock happened to read.
            double a = ReelScroll.cellsTravelled(1_000L, 0L, ReelScroll.NEVER, ORDINARY, FAST);
            double b = ReelScroll.cellsTravelled(1_699_999_999_000L, 1_699_999_998_000L,
                    ReelScroll.NEVER, ORDINARY, FAST);
            double c = ReelScroll.cellsTravelled(-500L, -1_500L, ReelScroll.NEVER, ORDINARY, FAST);
            assertEquals(a, b, 1e-9);
            assertEquals(a, c, 1e-9);
        }

        @Test
        @DisplayName("the celebration changes the slope and not the value")
        void slopeChangesNotValue() {
            long start = 0L;
            long actTwo = 1200L;
            double at = ReelScroll.cellsTravelled(actTwo, start, actTwo, ORDINARY, FAST);
            double just = ReelScroll.cellsTravelled(actTwo + 1, start, actTwo, ORDINARY, FAST);
            assertEquals(8.0, at, 1e-9, "1200ms of the ordinary rate is eight cells");
            assertEquals(1.0 / FAST, just - at, 1e-9,
                    "and the next millisecond advances by one fast millisecond, not by a jump");

            // The old behaviour, for contrast: dividing the clock by the period took the content
            // index from 1200/150 = 8 to 1200/65 = 18 on that same boundary.
            assertTrue(Math.floor(just) - Math.floor(at) <= 1.0,
                    "the content index advanced by more than one cell in one millisecond");
        }

        @Test
        @DisplayName("a zero or negative period is read as one rather than as infinite speed")
        void degeneratePeriods() {
            double travelled = ReelScroll.cellsTravelled(10L, 0L, ReelScroll.NEVER, 0L, -5L);
            assertEquals(10.0, travelled, 1e-9);
            assertTrue(Double.isFinite(travelled));
        }

        @Test
        @DisplayName("a celebration claiming to predate its own roll cannot double-count the spin")
        void actTwoBeforeTheRoll() {
            // Not reachable through SlotRoll, but this is a public static taking three longs.
            double travelled = ReelScroll.cellsTravelled(1_000L, 500L, 0L, ORDINARY, FAST);
            assertEquals(500.0 / FAST, travelled, 1e-9);
        }
    }

    // ------------------------------------------------------------------ the invariant itself

    @Nested
    @DisplayName("driven by a real roll, millisecond by millisecond")
    class AgainstARoll {

        /**
         * Walks every millisecond of a roll and asserts the properties that together mean "no
         * jump": monotone, bounded step, no dead frame, and the wrap rule that content advances by
         * exactly one cell at the instant the pixel offset wraps. Returns how many milliseconds
         * were actually walked, so a caller can prove the walk was not empty.
         */
        private long walk(SlotRoll roll, FixedClock clock, long until) {
            double previous = -1.0;
            int previousOffset = -1;
            long previousCell = Long.MIN_VALUE;
            long steps = 0;
            for (long t = 0; t <= until; t++) {
                clock.set(t);
                if (!roll.activeAt(t)) {
                    break;
                }
                double travelled = ReelScroll.cellsTravelled(t, roll.rollStartAt(t),
                        roll.jackpotActStartAt(t), ORDINARY, FAST);
                long cell = (long) Math.floor(travelled);
                int offset = (int) ((travelled - cell) * PITCH);

                if (previous >= 0.0) {
                    assertTrue(travelled >= previous,
                            "the strip ran backwards at t=" + t + ": " + previous + " -> " + travelled);
                    assertTrue(travelled - previous <= 1.0 / FAST + 1e-9,
                            "the strip teleported at t=" + t + ": " + previous + " -> " + travelled);
                    assertTrue(travelled > previous, "the strip stalled at t=" + t);
                    // The wrap property: content advances by exactly one cell at the instant the
                    // offset wraps, never by more, and never while the offset is still climbing.
                    long advanced = cell - previousCell;
                    assertTrue(advanced == 0L || advanced == 1L,
                            "content jumped " + advanced + " cells at t=" + t);
                    if (advanced == 0L) {
                        assertTrue(offset >= previousOffset,
                                "the offset slid back inside one cell at t=" + t);
                    } else {
                        assertTrue(offset < previousOffset,
                                "the content advanced without the offset wrapping at t=" + t);
                    }
                }
                previous = travelled;
                previousCell = cell;
                previousOffset = offset;
                steps++;
            }
            return steps;
        }

        @Test
        @DisplayName("a jackpot roll never jumps, never stalls and never runs backwards")
        void theWholeJackpotSequenceIsSeamless() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(rare("Chimera I"));
            roll.offerDrop(drop("Ancient Claw"));

            // Asserted before the walk, because the walk runs the roll off the end of its fade and
            // the sweep clears it; asked afterwards this would answer IDLE and prove nothing.
            clock.set(CFG.spinMillis());
            assertEquals(RollState.JACKPOT_INTRO, roll.state(),
                    "the walk below has to actually cross the speed change to mean anything");

            long walked = walk(roll, clock, 20_000L);
            assertTrue(walked > 5_000L, "the walk covered only " + walked + "ms of the sequence");
        }

        @Test
        @DisplayName("a late banner does not jump either, even though the reels do stop")
        void aLateBannerIsSeamlessToo() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(drop("Ancient Claw"));

            // Late enough that every column has landed: the reels are genuinely stopped here, and
            // the strip is not being drawn for them, but the accumulator behind it must still be
            // continuous or it snaps on the frame they break loose again.
            long banner = CFG.spinMillis() + 2 * CFG.lockStaggerMillis() + 100L;
            clock.set(banner);
            roll.offerDrop(rare("Chimera I"));
            assertEquals(RollState.JACKPOT_INTRO, roll.state(),
                    "the celebration opens on the banner, so that is the instant the rate changes");

            walk(roll, clock, 20_000L);
        }

        @Test
        @DisplayName("an ordinary roll scrolls at one unchanging rate from start to fade")
        void anOrdinaryRollHasNoBoundaryAtAll() {
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MANTICORE);
            roll.offerDrop(drop("Ancient Claw"));

            assertEquals(SlotRoll.NO_ACT_TWO, roll.jackpotActStartAt(0L),
                    "an ordinary roll must report no boundary rather than one in the past");
            walk(roll, clock, 20_000L);
        }

        @Test
        @DisplayName("the three columns keep their separation through the speed change")
        void theColumnsKeepTheirSeparation() {
            // The per-column lead is in cells rather than in milliseconds precisely so that this
            // holds. In milliseconds a 50ms lead is a third of a cell at 150ms and nearly four
            // fifths of one at 65ms, so the drums' relationship to each other changed on the
            // boundary frame -- three columns rearranging themselves under a gold wash.
            var clock = new FixedClock();
            var roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(rare("Chimera I"));

            double previousGap = -1.0;
            boolean crossed = false;
            for (long t = 0; t <= 5_000L; t += 7) {
                clock.set(t);
                if (!roll.activeAt(t)) {
                    break;
                }
                double base = ReelScroll.cellsTravelled(t, roll.rollStartAt(t),
                        roll.jackpotActStartAt(t), ORDINARY, FAST);
                double gap = (base + 2 * LEAD) - (base + LEAD);
                if (previousGap >= 0.0) {
                    assertEquals(previousGap, gap, 1e-9, "columns drifted apart at t=" + t);
                }
                previousGap = gap;
                crossed |= roll.inJackpotSequenceAt(t);
            }
            assertEquals(LEAD, previousGap, 1e-9);
            assertTrue(crossed, "the walk never reached the celebration, so it proved nothing");
        }
    }
}
