package com.skyprism.mc.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.BurrowDig;
import com.skyprism.core.diana.DianaPatterns;
import com.skyprism.core.diana.JackpotRule;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.LootParser;
import com.skyprism.core.diana.MythologicalCreature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /skyprism simulate} must produce lines the real parsers can read.
 *
 * <p>{@link SimulatedLoot} is a developer toy, which is exactly why it needs a test: the way
 * it fails is that a hand-written format string drifts out of step with
 * {@link DianaPatterns} or {@link LootParser}, and the only symptom is that the demo
 * silently shows nothing. Nobody files that as a bug against the parser, because the parser
 * is fine.
 *
 * <p>Every case here is a round trip -- generate a line, parse it back, compare to what was
 * generated -- so the assertion holds whatever the tables say, and the drop loop runs a few
 * thousand unseeded rolls because the generator deliberately has no seed and only volume
 * reaches the rarer branches. Grown from the ad-hoc {@code cmdtest/CmdProbe} main().</p>
 */
@DisplayName("SimulatedLoot round-trips through the real parsers")
final class SimulatedLootMcTest {

    /** Enough rolls to hit the jackpot branch of every creature many times over. */
    private static final int ROLLS = 4_000;

    @ParameterizedTest
    @EnumSource(MythologicalCreature.class)
    @DisplayName("every creature's spawn line matches back to that same creature")
    void spawnLineMatchesItsCreature(MythologicalCreature creature) {
        String line = SimulatedLoot.spawnLine(creature);
        Optional<MythologicalCreature> matched = DianaPatterns.matchSpawn(line);
        assertEquals(Optional.of(creature), matched,
                () -> "spawn line did not match: " + readable(line));
    }

    @Test
    @DisplayName("every generated drop line parses back to the drop it was generated from")
    void dropLinesRoundTrip() {
        LootParser parser = new LootParser();
        MythologicalCreature[] creatures = MythologicalCreature.values();
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (int i = 0; i < ROLLS; i++) {
            MythologicalCreature creature = creatures[i % creatures.length];
            for (LootDrop drop : SimulatedLoot.rollFor(creature)) {
                String line = SimulatedLoot.dropLine(drop);
                List<LootDrop> back = parser.parse(line);
                checked++;
                boolean ok = back.size() == 1
                        && back.get(0).itemName().equals(drop.itemName())
                        && back.get(0).count() == drop.count();
                if (!ok && failures.size() < 8) {
                    failures.add(drop + " -> " + readable(line) + " => " + back);
                }
            }
        }

        assertTrue(checked > ROLLS, "the roller produced suspiciously few drops: " + checked);
        assertTrue(failures.isEmpty(), () -> "drop lines that did not round-trip:\n  "
                + String.join("\n  ", failures));
    }

    /**
     * The simulator exists to demonstrate the jackpot flourish, so a jackpot has to be
     * reachable -- but not on every kill, or it demonstrates nothing about the ordinary case.
     */
    @Test
    @DisplayName("jackpot drops are reachable but are not the common case")
    void jackpotsAreReachableAndRare() {
        JackpotRule jackpot = JackpotRule.defaults();
        MythologicalCreature[] creatures = MythologicalCreature.values();
        int total = 0;
        int jackpots = 0;

        for (int i = 0; i < ROLLS; i++) {
            for (LootDrop drop : SimulatedLoot.rollFor(creatures[i % creatures.length])) {
                total++;
                if (jackpot.isJackpot(drop)) {
                    jackpots++;
                }
            }
        }

        int seen = jackpots;
        int drops = total;
        assertTrue(seen > 0, "no simulated roll ever produced a JackpotRule-recognised drop");
        assertTrue(seen < drops / 2,
                () -> "jackpots on " + seen + " of " + drops + " drops is not a rare event");
    }

    /** A simulated line must not be mistaken for something else on its way through the router. */
    @Test
    @DisplayName("generated lines survive the Diana pre-filter")
    void generatedLinesPassThePreFilter() {
        for (MythologicalCreature creature : MythologicalCreature.values()) {
            String spawn = SimulatedLoot.spawnLine(creature);
            assertTrue(com.skyprism.mc.chat.ChatRouter.mightMatterToDiana(spawn),
                    () -> "pre-filter would have dropped " + readable(spawn));
        }
    }

    /** Sanity: the simulator makes drop lines, not burrow lines. */
    @Test
    @DisplayName("a drop line is not mistaken for a burrow dig")
    void dropIsNotABurrow() {
        LootDrop drop = SimulatedLoot.rollFor(MythologicalCreature.MINOS_INQUISITOR).get(0);
        Optional<BurrowDig> dig = DianaPatterns.matchBurrowDig(SimulatedLoot.dropLine(drop));
        assertFalse(dig.isPresent(), () -> "drop line matched a burrow pattern: " + dig);
    }

    /** Section signs are invisible in an assertion message; show them as ampersands. */
    private static String readable(String raw) {
        return raw.replace('§', '&');
    }
}
