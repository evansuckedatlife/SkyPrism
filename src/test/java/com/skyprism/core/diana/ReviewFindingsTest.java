package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.util.FixedClock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the Diana defects a four-lens review turned up, each written so it fails
 * against the code as it stood before the fix.
 *
 * <p>They are grouped by the failure they pin rather than by class, because several of the fixes
 * are two-sided on purpose -- the spawn line is defended both in the regex and in the creature
 * lookup, since neither side of that guess has ever been checked against the live server.</p>
 */
@DisplayName("review findings: the Diana core")
final class ReviewFindingsTest {

    /**
     * spin 1000, stagger 200, window 3000, settle 500, fade 100; jackpot intro 400, spin 600,
     * stagger 100, hold 800. The jackpot durations are never reached by the tests below, which
     * all assert inside act one -- which is the point: a jackpot no longer touches it.
     */
    private static final SlotRollConfig CFG =
            new SlotRollConfig(3, 1000L, 200L, 3000L, 500L, 100L, 400L, 600L, 100L, 800L);

    // =============================================================== spawn lines

    @Nested
    @DisplayName("a spawn line parses whichever side of the colour run the article is on")
    class SpawnArticle {

        /**
         * The shape SkyHanni's pattern was written for: the article outside the coloured run.
         * This one always worked and must keep working.
         */
        @Test
        @DisplayName("article before the colour codes")
        void articleOutsideCodes() {
            String line = "§c§lOh! §r§eYou dug out a "
                    + "§r§2Minotaur§r§e!";
            assertEquals(Optional.of(MythologicalCreature.MINOTAUR), DianaPatterns.matchSpawn(line));
        }

        /**
         * The shape nobody here has ever seen on the wire, and the reason both defences exist.
         *
         * <p>Before the fix this line still <em>matched</em> the regex -- the optional article
         * matched nothing and the creature group swallowed "a Minotaur" -- and then
         * {@code byDisplayName} found no such creature, so {@code matchSpawn} reported "not a
         * spawn line". Nothing logs and nothing throws: the tracker never arms, no roll ever
         * starts from a burrow, and the entire trigger path is dead for all twelve creatures.
         */
        @Test
        @DisplayName("article inside the colour run")
        void articleInsideCodes() {
            String line = "§c§lOh! §r§eYou dug out "
                    + "§r§2a Minotaur§r§e!";
            assertEquals(Optional.of(MythologicalCreature.MINOTAUR), DianaPatterns.matchSpawn(line));
        }

        @Test
        @DisplayName("\"an\" is tolerated on both sides too")
        void anArticle() {
            String outside = "§c§lYikes! §r§eYou dug out an "
                    + "§r§cInquisitor§r§e!";
            // Not a creature name, so this is only asserting the article never lands in the group.
            assertEquals(Optional.empty(), DianaPatterns.matchSpawn(outside));

            String inside = "§c§lYikes! §r§eYou dug out "
                    + "§r§can Minos Inquisitor§r§e!";
            assertEquals(Optional.of(MythologicalCreature.MINOS_INQUISITOR),
                    DianaPatterns.matchSpawn(inside));
        }

        /** The plural line carries no article at all, which is why the group stays optional. */
        @Test
        @DisplayName("the plural line still parses")
        void plural() {
            String line = "§c§lOi! §r§eYou dug out "
                    + "§r§2Siamese Lynxes§r§e!";
            assertEquals(Optional.of(MythologicalCreature.SIAMESE_LYNXES),
                    DianaPatterns.matchSpawn(line));
        }

        @Test
        @DisplayName("byDisplayName strips a leading article, and only a leading article")
        void byDisplayNameStripsArticle() {
            assertEquals(Optional.of(MythologicalCreature.GAIA_CONSTRUCT),
                    MythologicalCreature.byDisplayName("a Gaia Construct"));
            assertEquals(Optional.of(MythologicalCreature.MANTICORE),
                    MythologicalCreature.byDisplayName("An Manticore"));
            // Still exact otherwise: an article is not a wildcard.
            assertEquals(Optional.empty(), MythologicalCreature.byDisplayName("a Griffin"));
            assertEquals(Optional.of(MythologicalCreature.HARPY),
                    MythologicalCreature.byDisplayName("Harpy"));
        }
    }

    // ================================================================ drop lines

    @Nested
    @DisplayName("an item name split across components still parses whole")
    class SplitItemNames {

        private final LootParser parser = new LootParser();

        /**
         * {@code LegacyText.toLegacy} injects a reset in front of every run after the first, even
         * between two runs with identical styles, so a component boundary Hypixel places inside an
         * item name reaches the parser as an interleaved <code>&#167;r&#167;9</code>.
         *
         * <p>Before the fix the treasure pattern could not cross it, failed outright, and
         * {@code parse} then hit the treasure guard and returned nothing -- a real drop shown on
         * the reel as "No Drop".
         */
        @Test
        @DisplayName("treasure item split mid-name")
        void treasureSplit() {
            String line = "§6§lRARE DROP! §r§eYou dug out a "
                    + "§r§9Griffin §r§9Feather§r§e!";
            List<LootDrop> drops = parser.parse(line);
            assertEquals(1, drops.size());
            assertEquals("Griffin Feather", drops.get(0).itemName());
        }

        /**
         * The banner shape fails worse: its optional trailing group swallows the remainder, so the
         * match still succeeded and locked the reel onto a truncated name that no jackpot entry
         * can ever match and that {@code DianaStats} then records forever.
         */
        @Test
        @DisplayName("mob drop split mid-name, with a magic-find tail")
        void bannerSplit() {
            String line = "§6§lRARE DROP! §r§9Dwarf Turtle "
                    + "§r§9Shelmet §r§b(+§r§b168% "
                    + "§r§b* Magic Find§r§b)";
            List<LootDrop> drops = parser.parse(line);
            assertEquals(1, drops.size());
            assertEquals("Dwarf Turtle Shelmet", drops.get(0).itemName());
        }

        /**
         * The tail must still be excluded. It is a different colour from the item, which is
         * exactly what the backreference keys on -- so a name that stops where the aqua bracket
         * starts keeps stopping there.
         */
        @Test
        @DisplayName("the magic-find tail is never swallowed into the name")
        void tailStaysOut() {
            String line = "§6§lRARE DROP! §r§9Griffin Feather "
                    + "§r§b(+§r§b168% §r§b* Magic Find§r§b)";
            List<LootDrop> drops = parser.parse(line);
            assertEquals(1, drops.size());
            assertEquals("Griffin Feather", drops.get(0).itemName());
        }

        @Test
        @DisplayName("an uncoloured drop is unaffected")
        void uncoloured() {
            String line = "§6§lPET DROP! §rGriffin";
            List<LootDrop> drops = parser.parse(line);
            assertEquals(1, drops.size());
            assertEquals("Griffin", drops.get(0).itemName());
        }
    }

    // ============================================================== the gate

    @Nested
    @DisplayName("the gate publishes its own conditions")
    class GateVisibility {

        @Test
        @DisplayName("onHypixel and inSkyBlock are readable without opening the gate")
        void accessors() {
            DianaGate gate = new DianaGate();
            assertFalse(gate.onHypixel());
            assertFalse(gate.inSkyBlock());

            gate.setOnHypixel(true);
            assertTrue(gate.onHypixel());
            assertFalse(gate.isOpen());

            gate.setInSkyBlock(true);
            assertTrue(gate.inSkyBlock());
            assertFalse(gate.isOpen());

            gate.setMayorDiana(true);
            assertTrue(gate.isOpen());
        }

        /**
         * "Closed" on its own is the same symptom whether Diana is simply not in office or the
         * mod cannot read the mayor row at all, and those have completely different fixes.
         */
        @Test
        @DisplayName("describe names every failing condition")
        void describe() {
            DianaGate gate = new DianaGate();
            String all = gate.describe();
            assertTrue(all.contains("not on Hypixel"), all);
            assertTrue(all.contains("not in SkyBlock"), all);
            assertTrue(all.contains("mayor is not Diana"), all);

            gate.setOnHypixel(true);
            gate.setInSkyBlock(true);
            String onlyMayor = gate.describe();
            assertFalse(onlyMayor.contains("not on Hypixel"), onlyMayor);
            assertTrue(onlyMayor.contains("mayor is not Diana"), onlyMayor);

            gate.setMayorDiana(true);
            assertEquals("open", gate.describe());
        }

        @Test
        @DisplayName("a whitelist that excludes the current area is named too")
        void describeArea() {
            DianaGate gate = new DianaGate();
            gate.setOnHypixel(true);
            gate.setInSkyBlock(true);
            gate.setMayorDiana(true);
            gate.setAllowedAreas(java.util.Set.of("Hub"));
            gate.setArea("Crimson Isle");
            assertFalse(gate.isOpen());
            assertTrue(gate.describe().contains("crimson isle"), gate.describe());
        }
    }

    // =========================================================== the roll engine

    @Nested
    @DisplayName("the roll answers one instant consistently")
    class OneInstant {

        /**
         * The no-argument queries each read the clock for themselves, so a reel's lock deadline
         * falling between two of them produces a frame that reports SPINNING and a locked reel at
         * once -- the cross-effect desync the HUD's "one clock read for the whole frame" comment
         * claims the design rules out. The {@code ...At} overloads are what make the claim true.
         */
        @Test
        @DisplayName("stateAt and reelsAt cannot disagree the way state and reels can")
        void noDesync() {
            FixedClock clock = new FixedClock(0L);
            SlotRoll roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);

            // One millisecond before the first reel locks.
            clock.set(999L);
            assertEquals(RollState.SPINNING, roll.state());
            // A slow frame: the clock crosses the deadline between two queries.
            clock.set(1000L);
            assertTrue(roll.reels().get(0).locked());

            // Asked about a single instant, the two agree by construction.
            clock.set(999L);
            long now = roll.nowMillis();
            assertEquals(999L, now);
            assertEquals(RollState.SPINNING, roll.stateAt(now));
            assertFalse(roll.reelsAt(now).get(0).locked());
            assertFalse(roll.jackpotAt(now));
            assertEquals(MythologicalCreature.MINOS_INQUISITOR, roll.creatureAt(now));
        }

        @Test
        @DisplayName("creatureAt is null rather than empty when idle, and matches creature()")
        void creatureAt() {
            FixedClock clock = new FixedClock(0L);
            SlotRoll roll = new SlotRoll(CFG, clock);
            org.junit.jupiter.api.Assertions.assertNull(roll.creatureAt(0L));
            assertEquals(Optional.empty(), roll.creature());

            roll.start(MythologicalCreature.KING_MINOS);
            assertEquals(MythologicalCreature.KING_MINOS, roll.creatureAt(clock.millis()));
            assertEquals(Optional.of(MythologicalCreature.KING_MINOS), roll.creature());
        }

        @Test
        @DisplayName("capturedDropCount agrees with capturedDrops without building the list")
        void capturedDropCount() {
            FixedClock clock = new FixedClock(0L);
            SlotRoll roll = new SlotRoll(CFG, clock);
            assertEquals(0, roll.capturedDropCount());

            roll.start(MythologicalCreature.MANTICORE);
            assertEquals(0, roll.capturedDropCount());
            roll.offerDrop(new LootDrop("Griffin Feather", "9", 1, false));
            roll.offerDrop(new LootDrop("Coins", "6", 25000, false));
            assertEquals(2, roll.capturedDropCount());
            assertEquals(roll.capturedDrops().size(), roll.capturedDropCount());

            clock.set(10_000L);
            assertEquals(0, roll.capturedDropCount());
        }
    }

    @Nested
    @DisplayName("a restart is observable")
    class RestartEdge {

        /**
         * {@code start} over a running roll restarts it, so anything holding per-roll state -- a
         * sound that must fire once, a flourish timestamp -- has to be able to see that edge.
         * Watching {@code active()} alone cannot: it is true on both sides.
         */
        @Test
        @DisplayName("rollId moves on every start, including one over a running roll")
        void rollIdMoves() {
            FixedClock clock = new FixedClock(0L);
            SlotRoll roll = new SlotRoll(CFG, clock);
            assertEquals(0L, roll.rollId());

            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            long first = roll.rollId();
            assertNotEquals(0L, first);

            // Still spinning: active() reports true before and after, but the roll is a new one.
            clock.set(500L);
            assertTrue(roll.active());
            roll.start(MythologicalCreature.KING_MINOS);
            assertTrue(roll.active());
            assertNotEquals(first, roll.rollId());
            assertEquals(Optional.of(MythologicalCreature.KING_MINOS), roll.creature());
        }
    }

    // ======================================================= ranking, memoised

    @Nested
    @DisplayName("the symbol ranking survives being memoised")
    class Ranking {

        /**
         * The ranking is now built once per capture rather than once per locked reel per frame.
         * The observable ordering must not have moved: jackpots first, then larger stacks, then
         * arrival order.
         */
        @Test
        @DisplayName("rare beats big, big beats early, and a later drop re-sorts correctly")
        void ordering() {
            FixedClock clock = new FixedClock(0L);
            SlotRoll roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);

            roll.offerDrop(new LootDrop("Coins", "6", 25_000, false));
            roll.offerDrop(new LootDrop("Ancient Claw", "a", 3, false));

            clock.set(1400L); // every reel locked
            List<LootDrop> before = roll.reels().stream().map(Reel::symbol).toList();
            assertEquals("Coins", before.get(0).itemName());
            assertEquals("Ancient Claw", before.get(1).itemName());

            // A rare drop arriving later must overtake the 25k stack in the memoised ranking. Where
            // that is now observable is the celebration rather than a column: a banner captured
            // during the spin opens act two at the first lock instant, so by 1800 every reel is
            // chasing the prize and none of them ever landed on the ordinary loot.
            clock.set(0L);
            SlotRoll second = new SlotRoll(CFG, clock);
            second.start(MythologicalCreature.MINOS_INQUISITOR);
            second.offerDrop(new LootDrop("Coins", "6", 25_000, false));
            clock.set(500L);
            second.offerDrop(new LootDrop("Daedalus Stick", "5", 1, true));
            clock.set(1800L);
            assertEquals("Daedalus Stick", second.jackpotSymbolAt(1800L).itemName(),
                    "rare beats a 25,000 stack, memo or no memo");
            List<LootDrop> after = second.reelsAt(1800L).stream().map(Reel::symbol).toList();
            assertEquals("Daedalus Stick", after.get(0).itemName());
            assertEquals("Daedalus Stick", after.get(1).itemName());
        }

        /** A reel that locked before a drop arrived must never show it, memo or no memo. */
        @Test
        @DisplayName("poll independence is unchanged")
        void pollIndependence() {
            FixedClock clock = new FixedClock(0L);
            SlotRoll roll = new SlotRoll(CFG, clock);
            roll.start(MythologicalCreature.MINOS_INQUISITOR);
            roll.offerDrop(new LootDrop("Griffin Feather", "9", 1, false));

            clock.set(1300L); // reels 0 and 1 locked; reel 2 has not
            // Deliberately an ordinary drop. A rare one would open the celebration on the spot and
            // unlock all three columns, which is a different (and separately tested) property; what
            // is under test here is that a reel which locked at 1000 cannot see a line from 1300.
            roll.offerDrop(new LootDrop("Crown of Greed", "5", 1, false));

            // Reel 2 locks at 1400 on its own schedule and is the only one that can have seen the
            // line; 1800 is well inside the settle, where all three are readable.
            clock.set(1800L);
            List<Reel> reels = roll.reelsAt(1800L);
            assertEquals("Griffin Feather", reels.get(0).symbol().itemName());
            assertEquals("Griffin Feather", reels.get(1).symbol().itemName());
            assertEquals("Crown of Greed", reels.get(2).symbol().itemName());
        }
    }
}
