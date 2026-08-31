package com.skyprism.mc.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.diana.SlotRollConfig;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.util.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one interaction the SkyBlock-wide rewrite could break that nothing else would catch.
 *
 * <h2>The collision this pins</h2>
 * <p>Diana burrows spawn on the Hub and on the Farming Islands. The Farming Islands is also the only
 * place {@code TrapperDetector} is armed, and that detector claims <em>any</em> rare-drop banner
 * there -- a deliberate trade recorded in its own notes, because its ON_RARE_BANNER policy can only
 * ever be satisfied by a banner. {@code BannerLines} guards the burrow <em>treasure</em> sentence
 * ("You dug out"), so a dug payout is safe. It cannot guard the other half: a Minos Inquisitor's
 * drop reaches chat as a bare "§6§lRARE DROP! §r§5Minos Relic", which is textually indistinguishable
 * from any other rare drop on that island.
 *
 * <p>So the separation cannot come from the text, and it does not. It comes from
 * {@link LootMachine#admit(LootEvent, long)}, which refuses every bus event while a Diana roll is on
 * screen. That single branch is the whole reason killing a Diana creature on the Farming Islands
 * still shows one Diana roll rather than a Diana roll and a "Trevor's Animal" roll fighting over the
 * widget -- and a branch that load-bearing, guarding the one path verified on the live server,
 * should not rest on a comment.
 *
 * <p>These tests drive the real {@link LootMachine} against a real {@link SlotRoll} on a hand-cranked
 * clock. Nothing here is a mock of the thing under test.
 */
@DisplayName("Diana outranks the bus")
final class DianaOutranksTheBusMcTest {

    /** A Diana creature drop, verbatim in shape from the frozen Diana parser suite. */
    private static final String MINOS_RELIC = "§6§lRARE DROP! §r§5Minos Relic";

    /** The burrow payout, which carries the sentence BannerLines does guard. */
    private static final String BURROW_TREASURE =
            "§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!";

    /** A clock the test moves by hand, so roll timings are exact rather than raced. */
    private static final class Hand implements Clock {
        private long now = 10_000L;

        @Override
        public long millis() {
            return now;
        }

        void advance(long by) {
            now += by;
        }
    }

    private Hand clock;
    private SlotRoll roll;
    private LootMachine machine;

    @BeforeEach
    void setUp() {
        clock = new Hand();
        roll = new SlotRoll(SlotRollConfig.defaults(), clock);
        machine = new LootMachine(clock);
        machine.wire(() -> roll, () -> 3_000L);
        machine.updateContext(true, true, "The Farming Islands", true);
    }

    private LootEvent trapper(long at) {
        return new LootEvent(LootSource.TREVOR_TRAPPER, "Trevor's Animal", at);
    }

    @Nested
    @DisplayName("while a Diana roll is on screen")
    final class WhileDianaIsRolling {

        @Test
        @DisplayName("a trapper event is refused outright, not merely delayed")
        void trapperEventIsOutranked() {
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            assertTrue(roll.activeAt(clock.millis()), "the Diana roll should be running");

            LootMachine.Admission verdict = machine.admit(trapper(clock.millis()), clock.millis());

            assertEquals(LootMachine.Admission.OUTRANKED, verdict,
                    "a bus event must not be able to touch a roll Diana started");
            assertEquals(LootSource.DIANA_MYTHOLOGICAL, roll.sourceAt(clock.millis()),
                    "the roll on screen is still Diana's");
        }

        @Test
        @DisplayName("the refusal holds for every millisecond the Diana roll is alive")
        void theRefusalHoldsForTheWholeRoll() {
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            for (int step = 0; step < 40; step++) {
                if (!roll.activeAt(clock.millis())) {
                    break;
                }
                assertEquals(LootMachine.Admission.OUTRANKED,
                        machine.admit(trapper(clock.millis()), clock.millis()),
                        "a bus event slipped past at +" + (clock.millis() - 10_000L) + "ms");
                assertEquals(LootSource.DIANA_MYTHOLOGICAL, roll.sourceAt(clock.millis()));
                clock.advance(100L);
            }
        }

        @Test
        @DisplayName("the real Farming-Islands chat path cannot start a second roll either")
        void theChatPathIsAlsoRefused() {
            machine.registerDetectors(() -> "Leebys");
            roll.start(MythologicalCreature.MINOS_INQUISITOR);

            // The end-to-end shape: the drop line Hypixel prints a tick after the kill, offered to
            // the general machine exactly as DianaController offers it.
            machine.onChat(MINOS_RELIC, clock.millis());

            assertEquals(LootSource.DIANA_MYTHOLOGICAL, roll.sourceAt(clock.millis()),
                    "the Minos Relic line restarted the widget as a trapper roll");
        }
    }

    @Nested
    @DisplayName("the burrow payout, which has no roll to hide behind")
    final class BurrowTreasure {

        @Test
        @DisplayName("no bus detector claims it even with the machine idle on the Farming Islands")
        void nobodyClaimsTheBurrowLine() {
            machine.registerDetectors(() -> "Leebys");
            assertTrue(machine.armed(), "the bus should have detectors open on the Farming Islands");

            machine.onChat(BURROW_TREASURE, clock.millis());

            assertTrue(!roll.activeAt(clock.millis())
                            || roll.sourceAt(clock.millis()) == LootSource.DIANA_MYTHOLOGICAL,
                    "a burrow payout spun a non-Diana roll; Diana already owns that line");
        }
    }

    @Nested
    @DisplayName("what the guard must not do")
    final class TheGuardIsNotTooBroad {

        @Test
        @DisplayName("once the Diana roll has ended, the bus works again")
        void theBusRecoversAfterTheRoll() {
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            // Walk past the end of the animation rather than assuming its length.
            for (int step = 0; step < 200 && roll.activeAt(clock.millis()); step++) {
                clock.advance(100L);
            }
            assertTrue(!roll.activeAt(clock.millis()), "the Diana roll should have finished by now");

            LootMachine.Admission verdict = machine.admit(trapper(clock.millis()), clock.millis());

            assertNotEquals(LootMachine.Admission.OUTRANKED, verdict,
                    "the guard is meant to protect a live Diana roll, not to shut the bus forever");
        }
    }
}
