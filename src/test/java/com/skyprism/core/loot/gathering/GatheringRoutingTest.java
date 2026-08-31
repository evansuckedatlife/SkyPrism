package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.SourceDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test that matters most in this package: every captured line, driven through a real bus with a
 * real context, must land on exactly one source and on the right one.
 *
 * <p>Each detector's own test proves it matches what it should. This one proves the set of them
 * does not fight: that a VERY RARE CROP is not claimed by the RARE CROP source, that a pest drop is
 * not claimed by the trapper, that a treasure catch is not a trophy fish, and that a Diana treasure
 * dig -- which wears the same banner and happens on the same island as a trapper hunt -- is claimed
 * by nobody here at all.
 */
@DisplayName("Gathering routing: one line, one source, through the real bus")
class GatheringRoutingTest {

    private static final long NOW = 99L;

    /** A line, where the player was standing, and which source should end up owning it. */
    private record Case(String line, String island, LootSource expected) {
    }

    private static final List<Case> CASES = List.of(
            // fishing
            new Case(GatheringSamples.TROPHY_GOLD, "Crimson Isle",
                    LootSource.FISHING_TROPHY_FISH_RARE),
            new Case(GatheringSamples.TROPHY_BRONZE, "Crimson Isle",
                    LootSource.FISHING_TROPHY_FISH),
            new Case(GatheringSamples.GOLDEN_FISH_SPAWN, "Crimson Isle",
                    LootSource.FISHING_GOLDEN_FISH),
            new Case(GatheringSamples.CATCH_PET, "Crimson Isle", LootSource.FISHING_TREASURE),
            new Case(GatheringSamples.CATCH_COINS, "Hub", LootSource.FISHING_TREASURE),
            new Case(GatheringSamples.SEA_RARE_JAWBUS, "Crimson Isle",
                    LootSource.FISHING_RARE_SEA_CREATURE),
            new Case(GatheringSamples.SEA_ORDINARY_SQUID, "Hub", LootSource.FISHING_SEA_CREATURE),

            // foraging
            new Case(GatheringSamples.TREE_GIFT_HEADER, "Galatea", LootSource.FORAGING_TREE_GIFT),
            new Case(GatheringSamples.TREE_BONUS_HEADER, "Galatea",
                    LootSource.FORAGING_TREE_BONUS_GIFT),
            new Case(GatheringSamples.TREE_PHANTOM, "Galatea", LootSource.FORAGING_TREE_PHANTOM),

            // garden
            new Case(GatheringSamples.RARE_CROP, "Garden", LootSource.GARDEN_RARE_CROP),
            new Case(GatheringSamples.VERY_RARE_CROP, "Garden", LootSource.GARDEN_VERY_RARE_CROP),
            new Case(GatheringSamples.PEST_DROP, "Garden", LootSource.GARDEN_PEST_DROP),
            new Case(GatheringSamples.PEST_VINYL, "Garden", LootSource.GARDEN_PEST_DROP),
            new Case(GatheringSamples.CROP_FEVER_START, "Garden", LootSource.GARDEN_CROP_FEVER),
            new Case(GatheringSamples.VISITOR_LEGENDARY, "Garden", LootSource.GARDEN_VISITOR_RARE),

            // mining
            new Case(GatheringSamples.PRISTINE, "Crystal Hollows",
                    LootSource.MINING_PRISTINE_GEMSTONE),
            new Case(GatheringSamples.COMPACT, "Dwarven Mines", LootSource.MINING_COMPACT),
            new Case(GatheringSamples.GOBLIN_GOLDEN, "Dwarven Mines", LootSource.MINING_GOBLIN_RAID),

            // trapper
            new Case(GatheringSamples.TRAPPER_DROP, "The Farming Islands",
                    LootSource.TREVOR_TRAPPER),

            // nothing at all
            new Case(GatheringSamples.PLAYER_CHAT, "Garden", null),
            new Case(GatheringSamples.PLAYER_CHAT, "The Farming Islands", null),
            new Case(GatheringSamples.CROP_FEVER_END, "Garden", null),
            new Case(GatheringSamples.VISITOR_UNCOMMON, "Garden", null),
            new Case(GatheringSamples.TREE_CONTRIBUTION, "Galatea", null),
            new Case(GatheringSamples.TRAPPER_FAILED, "The Farming Islands", null),

            // a Diana dig on the island where the trapper is armed: the shipped path keeps it
            new Case(GatheringSamples.DIANA_TREASURE, "The Farming Islands", null),
            new Case(GatheringSamples.DIANA_TREASURE, "Garden", null),

            // gates: the right line on the wrong island is nobody's
            new Case(GatheringSamples.TROPHY_GOLD, "Garden", null),
            new Case(GatheringSamples.PEST_DROP, "Galatea", null),
            new Case(GatheringSamples.PRISTINE, "Garden", null),
            new Case(GatheringSamples.GOBLIN_GOLDEN, "Garden", null));

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("every captured line lands on exactly the source it belongs to")
        void everyCaseRoutes() {
            for (Case testCase : CASES) {
                LootEventBus bus = freshBus(testCase.island());
                Optional<LootEvent> event = bus.onChat(testCase.line(), NOW);
                if (testCase.expected() == null) {
                    assertTrue(event.isEmpty(),
                            "expected nobody to claim " + describe(testCase) + " but "
                                    + event.map(e -> e.source().name()).orElse("") + " did");
                } else {
                    assertTrue(event.isPresent(), "nobody claimed " + describe(testCase));
                    assertEquals(testCase.expected(), event.get().source(),
                            "wrong source for " + describe(testCase));
                }
            }
        }

        @Test
        @DisplayName("and the event is stamped with the clock it was handed")
        void stampsTheClock() {
            LootEventBus bus = freshBus("Garden");
            assertEquals(NOW, bus.onChat(GatheringSamples.RARE_CROP, NOW).orElseThrow().atMillis());
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("registers cleanly, which pins that no two detectors claim one source")
        void registersCleanly() {
            LootEventBus bus = new LootEventBus();
            GatheringDetectors.registerAll(bus);
            assertEquals(GatheringDetectors.all().size(), bus.registeredCount());
        }

        @Test
        @DisplayName("every detector's source is one the registry knows and describes")
        void everySourceIsRegistered() {
            for (SourceDetector detector : GatheringDetectors.all()) {
                assertFalse(LootSourceRegistry.displayName(detector.source()).isBlank(),
                        "no display name for " + detector.source());
            }
        }

        @Test
        @DisplayName("each detector is a fresh instance, because two of them hold session state")
        void freshInstances() {
            List<SourceDetector> first = GatheringDetectors.all();
            List<SourceDetector> second = GatheringDetectors.all();
            for (int i = 0; i < first.size(); i++) {
                assertFalse(first.get(i) == second.get(i),
                        "shared instance for " + first.get(i).source());
            }
        }

        @Test
        @DisplayName("on an island with nothing to gather, nothing at all is open")
        void shutOnAnIrrelevantIsland() {
            LootEventBus bus = new LootEventBus();
            // Only the four anywhere-gated sources -- the two sea creature detectors and the
            // treasure catch -- may be open in a random hub, and the sea creature pair is what
            // switches the pre-filter off.
            List<LootSource> open = new ArrayList<>();
            GatheringDetectors.registerAll(bus);
            bus.updateContext(GameContext.onIsland("Private Island"));
            for (SourceDetector detector : bus.openDetectors()) {
                open.add(detector.source());
            }
            assertEquals(List.of(LootSource.FISHING_TREASURE,
                            LootSource.FISHING_RARE_SEA_CREATURE,
                            LootSource.FISHING_SEA_CREATURE),
                    open);
        }

        @Test
        @DisplayName("and outside SkyBlock entirely, nothing is open at all")
        void shutOutsideSkyBlock() {
            LootEventBus bus = new LootEventBus();
            GatheringDetectors.registerAll(bus);
            bus.updateContext(GameContext.UNKNOWN);
            assertEquals(0, bus.openDetectorCount());
            assertTrue(bus.onChat(GatheringSamples.RARE_CROP, NOW).isEmpty());
        }
    }

    private static LootEventBus freshBus(String island) {
        LootEventBus bus = new LootEventBus();
        GatheringDetectors.registerAll(bus);
        bus.updateContext(GameContext.onIsland(island));
        return bus;
    }

    private static String describe(Case testCase) {
        return "[" + testCase.island() + "] " + testCase.line();
    }
}
