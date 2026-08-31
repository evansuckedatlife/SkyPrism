package com.skyprism.core.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.loot.combat.CombatDetectors;
import com.skyprism.core.loot.containers.ContainerDetectors;
import com.skyprism.core.loot.events.EventDetectors;
import com.skyprism.core.loot.gathering.GatheringDetectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every detector's own captured lines, walked past every other detector that could be listening at
 * the same moment.
 *
 * <h2>The risk this exists for</h2>
 * <p>One detector was easy to keep honest: it had its own tests and nothing else in the process
 * cared. Fifty-one is a different problem. The failure mode is not a detector that stops working --
 * that shows up in its own suite -- it is a detector that starts working on somebody <em>else's</em>
 * line, so the machine spins with the wrong caption, or spins twice, or a specific source is
 * silently swallowed forever by a catch-all registered too early. None of that fails an existing
 * test, and none of it is visible in any single detector's file.
 *
 * <h2>Why the gates are part of the test and not an afterthought</h2>
 * <p>Feeding every sample to every detector with no context at all is easy and it answers the wrong
 * question. Run that way, this sweep reports twenty-one collisions -- and almost all of them are
 * pairs that can never be listening at the same instant, because one is armed on the Farming
 * Islands and the other only inside Kuudra. Counting those as defects would bury the handful that
 * are real under noise nobody can act on.
 *
 * <p>So the sweep is done the way the bus does it: pick a context, ask every detector whether its
 * gate is open there, and consider only the ones that answer yes. That reduces a wall of theoretical
 * overlaps to twelve genuine ones, which is a number a person can read and judge. Of those twelve,
 * seven are the design working as intended (a specific source claiming its line ahead of the
 * catch-all) and three distinct shapes are recorded below as accepted.
 *
 * <h2>What a failure here means</h2>
 * <p>{@link Sweep#theFirstOpenDetectorToClaimALineIsTheOneThatOwnsIt()} is the assertion that
 * matters. The bus is first-match-wins, so "does anything else also match" is only half the
 * question; the half that decides behaviour is "does the right one match <em>first</em>". A new
 * detector that matches an existing source's line is harmless if it registers after it and fatal if
 * it registers before, and this test can tell those two apart.
 */
@DisplayName("Cross-source: one line, one owner")
final class CrossSourceCollisionTest {

    /**
     * The two sources two packages both wrote a detector for, and which {@code LootMachine}
     * resolves by preferring these implementations. Pinned so a third claimant, or the quiet
     * disappearance of one of these, is a test failure rather than a surprise at startup --
     * {@code LootEventBus.register} throws on a duplicate source, which would take the whole loot
     * feature down the first time the client launched.
     */
    private static final List<String> PREFERRED_ON_COLLISION = List.of(
            "com.skyprism.core.loot.events.GenericRareDropDetector",
            "com.skyprism.core.loot.combat.PetDropDetector");

    /**
     * Overlaps that are real, reachable, and deliberately tolerated -- {@code owner<-thief}, where
     * the thief matches first and therefore wins the line.
     *
     * <p>Every entry is the same root cause: {@code TrapperDetector} is armed by nothing but the
     * island and then claims <em>any</em> rare-drop banner on it, because its ON_RARE_BANNER policy
     * can only ever be satisfied by a banner (see that class's own notes). On the Farming Islands it
     * therefore sits in front of the two summon-window sources and the universal catch-all.
     *
     * <p>The cost is a caption, not a lost or doubled roll: the machine still spins, and it spins
     * exactly once, because the bus stops at the first match. Narrowing it needs the hunt-assignment
     * line, which carries no marker this source declares and so never reaches the detector --
     * recorded in {@code TrapperDetector} as the tightening available once somebody can capture it.
     */
    private static final Set<String> ACCEPTED_STEALS = Set.of(
            "PRIMAL_FEAR<-TREVOR_TRAPPER",
            "REINDRAKE<-TREVOR_TRAPPER",
            "MOB_RARE_DROP<-TREVOR_TRAPPER");

    /** Realistic places to stand, chosen to open every island- and area-gated source at least once. */
    private static List<GameContext> contexts() {
        List<GameContext> out = new ArrayList<>();
        String[] islands = {"Hub", "Private Island", "The Farming Islands", "Garden", "Spider's Den",
                "The End", "Crimson Isle", "Kuudra", "Dwarven Mines", "Crystal Hollows", "Mineshaft",
                "Galatea", "The Rift", "Dungeon Hub", "Catacombs", "Jerry's Workshop"};
        for (String island : islands) {
            out.add(new GameContext(true, true, island, "", "Diana", false, false));
        }
        out.add(new GameContext(true, true, "Dwarven Mines", "The Mist", "Diana", false, false));
        out.add(new GameContext(true, true, "Hub", "Carnival", "Diana", false, false));
        out.add(new GameContext(true, true, "The End", "Dragon's Nest", "Diana", false, false));
        out.add(new GameContext(true, true, "Catacombs", "(M7)", "Diana", true, false));
        out.add(new GameContext(true, true, "The Rift", "", "Diana", false, true));
        return out;
    }

    /** Every detector the client would actually register, in the order it would register them. */
    private static List<SourceDetector> live() {
        List<SourceDetector> candidates = candidates();
        Map<LootSource, SourceDetector> chosen = new LinkedHashMap<>();
        for (SourceDetector d : candidates) {
            if (!chosen.containsKey(d.source())
                    || PREFERRED_ON_COLLISION.contains(d.getClass().getName())) {
                chosen.put(d.source(), d);
            }
        }
        List<SourceDetector> live = new ArrayList<>();
        for (SourceDetector d : candidates) {
            if (chosen.get(d.source()) == d) {
                live.add(d);
            }
        }
        return live;
    }

    /** Everything the four packages offer, duplicates and all, in the client's assembly order. */
    private static List<SourceDetector> candidates() {
        List<SourceDetector> candidates = new ArrayList<>();
        candidates.addAll(CombatDetectors.create().detectors());
        candidates.addAll(ContainerDetectors.all(() -> "Leebys"));
        candidates.addAll(GatheringDetectors.all());
        candidates.addAll(EventDetectors.inOrder());
        return candidates;
    }

    @Nested
    @DisplayName("the registered set")
    final class Registration {

        @Test
        @DisplayName("exactly two sources are claimed twice, and the client knows which to prefer")
        void onlyTheTwoKnownSourcesAreContested() {
            Map<LootSource, List<String>> bySource = new LinkedHashMap<>();
            for (SourceDetector d : candidates()) {
                bySource.computeIfAbsent(d.source(), k -> new ArrayList<>())
                        .add(d.getClass().getName());
            }
            Set<String> contested = new TreeSet<>();
            bySource.forEach((source, owners) -> {
                if (owners.size() > 1) {
                    contested.add(source.name());
                }
            });
            assertEquals(Set.of("MOB_RARE_DROP", "PET_DROP"), contested,
                    "a source claimed by two detectors makes LootEventBus.register throw at startup");
        }

        @Test
        @DisplayName("the deduplicated set really does register on a real bus without throwing")
        void theChosenSetRegistersCleanly() {
            LootEventBus bus = new LootEventBus();
            List<SourceDetector> live = live();
            for (SourceDetector d : live) {
                bus.register(d);
            }
            assertEquals(live.size(), bus.registeredCount());
        }
    }

    @Nested
    @DisplayName("one line, one owner")
    final class Sweep {

        @Test
        @DisplayName("the first open detector to claim a line is the one that owns it")
        void theFirstOpenDetectorToClaimALineIsTheOneThatOwnsIt() {
            Set<String> steals = new TreeSet<>();
            for (GameContext ctx : contexts()) {
                List<SourceDetector> open = openIn(ctx);
                for (SourceDetector owner : open) {
                    for (String sample : owner.triggerSamples()) {
                        LootSource first = null;
                        boolean ownerMatches = false;
                        for (SourceDetector d : open) {
                            Optional<LootEvent> hit = d.onChat(sample, 1_000L);
                            if (hit.isEmpty()) {
                                continue;
                            }
                            if (first == null) {
                                first = hit.get().source();
                            }
                            if (d.source() == owner.source()) {
                                ownerMatches = true;
                            }
                        }
                        // A sample that produces nothing is not a collision: several detectors take
                        // a line that arms them and emit only on a later one (a summon window, a
                        // hunt completion, a quest start, a cooldown suppressor). Those are covered
                        // by their own suites, which can hold the state this sweep deliberately
                        // does not carry between samples.
                        if (first != null && ownerMatches && first != owner.source()) {
                            steals.add(owner.source().name() + "<-" + first.name());
                        }
                    }
                }
            }
            assertEquals(ACCEPTED_STEALS, steals,
                    "a source's own line is being claimed by a detector registered ahead of it");
        }

        @Test
        @DisplayName("the gates keep the overlaps down to the dozen that are actually reachable")
        void gatesActuallySeparateTheIslands() {
            // The point of the gates is that most overlaps are unreachable. If this number climbs,
            // a gate has been widened and the sweep above is testing less than it appears to.
            Set<String> multi = new LinkedHashSet<>();
            for (GameContext ctx : contexts()) {
                List<SourceDetector> open = openIn(ctx);
                for (SourceDetector owner : open) {
                    for (String sample : owner.triggerSamples()) {
                        int hits = 0;
                        for (SourceDetector d : open) {
                            if (d.onChat(sample, 1_000L).isPresent()) {
                                hits++;
                            }
                        }
                        if (hits > 1) {
                            multi.add(owner.source().name() + "|" + sample);
                        }
                    }
                }
            }
            assertTrue(multi.size() <= 12,
                    "reachable multi-match samples climbed to " + multi.size()
                            + "; a gate was probably widened. " + multi);
        }

        private List<SourceDetector> openIn(GameContext ctx) {
            List<SourceDetector> open = new ArrayList<>();
            for (SourceDetector d : live()) {
                if (d.readsChat() && d.gateOpen(ctx)) {
                    open.add(d);
                }
            }
            return open;
        }
    }

    @Nested
    @DisplayName("Diana, which no bus detector may speak for")
    final class DianaIsNotOnTheBus {

        @Test
        @DisplayName("no registered detector claims DIANA_MYTHOLOGICAL")
        void dianaIsNotRegistered() {
            for (SourceDetector d : live()) {
                assertTrue(d.source() != LootSource.DIANA_MYTHOLOGICAL,
                        "DianaController owns that source end to end; " + d.getClass().getName()
                                + " must not be in the client's registration list");
            }
        }

        @Test
        @DisplayName("the burrow treasure payout is claimed by nobody, on any island Diana reaches")
        void theBurrowTreasureLineIsLeftAlone() {
            String treasure = "§6§lRARE DROP! §r§eYou dug out a "
                    + "§r§9Griffin Feather§r§e!";
            for (GameContext ctx : contexts()) {
                for (SourceDetector d : live()) {
                    if (!d.readsChat() || !d.gateOpen(ctx)) {
                        continue;
                    }
                    assertTrue(d.onChat(treasure, 1_000L).isEmpty(),
                            d.getClass().getSimpleName() + " claimed a Diana burrow payout on "
                                    + ctx.island() + "; Diana already spins for that line");
                }
            }
        }
    }
}
