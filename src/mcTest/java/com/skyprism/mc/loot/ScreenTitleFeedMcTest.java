package com.skyprism.mc.loot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.SourceDetector;
import com.skyprism.core.util.FixedClock;
import com.skyprism.mc.hud.LootMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * The feed that finally calls {@code LootEventBus.onScreenTitle}.
 *
 * <p>What can honestly be checked without a server is the <em>route</em>: that a title reaches the
 * machine's own registered detectors, that formatting is stripped on the way, that the right
 * detector claims it, and that a player with nothing registered pays nothing. What cannot be
 * checked here is whether the titles the detectors expect are the titles Hypixel actually sends --
 * those are transcribed from SkyHanni and Skyblocker and are unverified against a live server. The
 * fixtures below are therefore written as "the string the detector says it wants", and a test
 * passing means the plumbing works, not that the six sources fire in game.
 *
 * <p>The fixture deliberately builds its feed with {@link ScreenTitleFeed#over}, the same
 * reflective route production uses, over a {@link LootMachine} of its own. That is what keeps the
 * one reflective dependency in the mod covered: rename {@code LootMachine.bus} and every test in
 * here fails immediately instead of six sources going quiet in game.
 */
@DisplayName("ScreenTitleFeed: the GUI half of the loot bus")
final class ScreenTitleFeedMcTest {

    private static final SlotRollConfig CFG = SlotRollConfig.defaults();

    /** Well clear of zero, so a detector's "not before this instant" guards are not at their init. */
    private static final long START = 1_000_000L;

    private record Fixture(FixedClock clock, SlotRoll roll, LootMachine machine,
                           ScreenTitleFeed feed) {
    }

    /** A machine with the shipped detector set registered, and a feed over its own bus. */
    private static Fixture armed() {
        FixedClock clock = new FixedClock(START);
        SlotRoll roll = new SlotRoll(CFG, clock);
        LootMachine machine = new LootMachine(clock);
        machine.wire(() -> roll, CFG::lootWindowMillis);
        machine.registerDetectors(() -> "Tester");
        ScreenTitleFeed feed = assertDoesNotThrow(() -> ScreenTitleFeed.over(machine, clock),
                "the feed reaches the machine's own bus by reflection; if this throws, the field "
                        + "moved and the six GUI-triggered sources are dead in game");
        return new Fixture(clock, roll, machine, feed);
    }

    @AfterEach
    void clearStaticBinding() {
        ScreenTitleFeed.resetForTesting();
    }

    // ==================================================================
    //  Costing nothing when nothing is listening
    // ==================================================================

    @Nested
    @DisplayName("the bail-out")
    final class BailOut {

        @Test
        @DisplayName("an empty bus is never asked, whatever the title says")
        void nothingRegisteredMeansNothingHappens() {
            FixedClock clock = new FixedClock(START);
            SlotRoll roll = new SlotRoll(CFG, clock);
            LootMachine machine = new LootMachine(clock);
            machine.wire(() -> roll, CFG::lootWindowMillis);
            // No registerDetectors: this is the player who switched every source off, and the
            // player who is not on Hypixel at all.
            ScreenTitleFeed feed = new ScreenTitleFeed(new LootEventBus(), machine, clock);

            assertFalse(feed.offer("Split or Steal", START),
                    "a title a detector would have claimed still produces nothing when no "
                            + "detector is registered");
            assertEquals(0, machine.admittedCount());
            assertNull(machine.pending());
        }

        @Test
        @DisplayName("a null or empty title is ignored rather than thrown at")
        void emptyTitles() {
            Fixture f = armed();
            assertFalse(f.feed.offer(null, START));
            assertFalse(f.feed.offer("", START));
            assertFalse(f.feed.onScreenOpened(null));
            // A title that is nothing but formatting strips to empty and must not reach the bus.
            assertFalse(f.feed.offer("§a§l", START));
            assertEquals(0, f.machine.admittedCount());
        }

        @Test
        @DisplayName("an inventory nobody cares about does nothing")
        void unclaimedTitle() {
            Fixture f = armed();
            assertFalse(f.feed.offer("Large Backpack", START));
            assertEquals(0, f.machine.admittedCount());
            assertNull(f.machine.pending());
        }
    }

    // ==================================================================
    //  A title reaching the right detector
    // ==================================================================

    @Nested
    @DisplayName("a representative title reaches the right detector")
    final class Routing {

        @Test
        @DisplayName("Split or Steal spins the machine, because its shipped policy is ALWAYS")
        void splitOrSteal() {
            Fixture f = armed();
            assertTrue(f.feed.offer("Split or Steal", START));
            assertEquals(1, f.machine.admittedCount());
            assertNotNull(f.machine.lastAdmitted());
            assertEquals(LootSource.RIFT_UBIK_SPLIT_OR_STEAL, f.machine.lastAdmitted().source());
            assertTrue(f.roll.activeAt(START), "an ALWAYS source starts the reels immediately");
        }

        @Test
        @DisplayName("the Croesus run list arms nothing on its own, then claims the chest")
        void croesusArmsThenClaims() {
            Fixture f = armed();

            // Opening the run list is a menu, not a payout.
            assertFalse(f.feed.offer("(1/2) Croesus", START),
                    "looking at a menu is not receiving anything");
            assertEquals(0, f.machine.admittedCount());
            assertNull(f.machine.pending());

            // The chest inside it is. CROESUS_CHEST ships ON_RARE_BANNER, so the event arms and
            // waits for loot rather than rolling -- which is exactly the admission path a
            // chat-triggered source would get, and the point of routing through admit().
            f.clock.advance(2_000L);
            long now = f.clock.millis();
            assertTrue(f.feed.offer("Wood Chest", now));
            assertEquals(1, f.machine.deferredCount());
            assertNotNull(f.machine.pending());
            assertEquals(LootSource.CROESUS_CHEST, f.machine.pending().source(),
                    "registered before DungeonRewardChestDetector, so an armed Croesus takes the "
                            + "chest; without the run list it would have been an in-run chest");
        }

        @Test
        @DisplayName("without the run list the same chest is not a Croesus chest")
        void chestWithoutCroesus() {
            Fixture f = armed();
            assertTrue(f.feed.offer("Wood Chest", START));
            assertNotNull(f.machine.pending());
            assertEquals(LootSource.DUNGEON_REWARD_CHEST, f.machine.pending().source(),
                    "this is the behaviour the mod already had, and it must not change for a "
                            + "player who never visits Croesus");
        }
    }

    // ==================================================================
    //  Getting the text out of the title
    // ==================================================================

    @Nested
    @DisplayName("title extraction and stripping")
    final class Extraction {

        @Test
        @DisplayName("legacy colour codes inside the title text are stripped")
        void legacyCodes() {
            Fixture f = armed();
            assertTrue(f.feed.offer("§d§lSplit or Steal", START));
            assertEquals(LootSource.RIFT_UBIK_SPLIT_OR_STEAL, f.machine.lastAdmitted().source());
        }

        @Test
        @DisplayName("the section-x RGB form is stripped whole, hex digits and all")
        void rgbCodes() {
            Fixture f = armed();
            // The fourteen-character form some proxies emit. Strip it as a unit or the six hex
            // digits leak into the title as text and no exact-match detector can ever hit.
            String rgb = "§x§f§f§5§5§0§0Split or Steal";
            assertTrue(f.feed.offer(rgb, START));
            assertEquals(LootSource.RIFT_UBIK_SPLIT_OR_STEAL, f.machine.lastAdmitted().source());
        }

        @Test
        @DisplayName("a styled Component is read through getString(), not through its style")
        void componentPath() {
            Fixture f = armed();
            // Style-driven colour leaves no codes in the text at all; the detector sees the
            // readable words either way, which is why getString() is the right flatten here.
            Component title = Component.literal("Split or Steal")
                    .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
            assertTrue(f.feed.onScreenOpened(title));
            assertEquals(LootSource.RIFT_UBIK_SPLIT_OR_STEAL, f.machine.lastAdmitted().source());
        }

        @Test
        @DisplayName("a component whose own text carries section codes is stripped too")
        void componentWithLegacyContent() {
            Fixture f = armed();
            assertTrue(f.feed.onScreenOpened(Component.literal("§dSplit or Steal")));
            assertEquals(LootSource.RIFT_UBIK_SPLIT_OR_STEAL, f.machine.lastAdmitted().source());
        }
    }

    // ==================================================================
    //  The fuse
    // ==================================================================

    @Nested
    @DisplayName("failing safe")
    final class FailSafe {

        @Test
        @DisplayName("the static entry point swallows everything, because vanilla is on the stack")
        void dispatchNeverThrows() {
            ScreenTitleFeed.resetForTesting();
            // Resolves the real singleton, whose bus has nothing registered in a headless run, so
            // this exercises the reflective bind and the bail-out and mutates nothing.
            assertDoesNotThrow(() -> ScreenTitleFeed.dispatch(Component.literal("Croesus")));
            assertDoesNotThrow(() -> ScreenTitleFeed.dispatch(null));
            assertFalse(ScreenTitleFeed.disabled(),
                    "nothing failed, so the feed must still be armed for the session");
        }

        @Test
        @DisplayName("a detector that throws is retired rather than allowed to escape")
        void budgetRetiresABrokenDetector() {
            FixedClock clock = new FixedClock(START);
            LootMachine machine = new LootMachine(clock);
            LootEventBus bus = new LootEventBus();
            bus.register(new ExplodingDetector());
            ScreenTitleFeed.bind(new ScreenTitleFeed(bus, machine, clock));

            Component title = Component.literal("Croesus");
            for (int i = 0; i < ScreenTitleFeed.FAILURE_BUDGET; i++) {
                assertDoesNotThrow(() -> ScreenTitleFeed.dispatch(title),
                        "vanilla's handleOpenScreen is on the stack: an escape here is the "
                                + "player losing the ability to open containers");
            }
            assertTrue(ScreenTitleFeed.disabled(),
                    "a detector that keeps throwing inside packet handling has to be given up "
                            + "on; the six sources fall back to their chat halves, which is "
                            + "where they were before this feed existed");

            // And once retired it stays retired for the session rather than retrying per chest.
            assertDoesNotThrow(() -> ScreenTitleFeed.dispatch(title));
        }
    }

    /** The bug this whole fuse exists for: a detector that throws on a real container opening. */
    private static final class ExplodingDetector implements SourceDetector {

        @Override
        public LootSource source() {
            return LootSource.CROESUS_CHEST;
        }

        @Override
        public boolean gateOpen(GameContext ctx) {
            return false;
        }

        @Override
        public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
            return Optional.empty();
        }

        @Override
        public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
            throw new IllegalStateException("a detector blew up on a screen title");
        }
    }
}
