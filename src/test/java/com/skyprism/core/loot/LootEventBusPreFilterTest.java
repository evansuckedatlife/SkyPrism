package com.skyprism.core.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test the whole pre-filter exists to be held to.
 *
 * <p>A pre-filter is a silent thing: everything it rejects is lost with nothing logged and no
 * exception, so the failure it produces -- a detector that works in its own unit test and never
 * fires in game -- is invisible from the inside. The mod already learned this once: the shipped chat
 * path has a hardcoded keyword list with exactly that hazard, and a test pinning it. This is the
 * same test for the general bus, and it is stronger in one way that matters: the filter here is
 * derived from the registered detectors rather than written next to them, so the test drives the
 * REAL bus with every registered detector's OWN captured Hypixel lines and fails if any is swallowed.
 *
 * <p>The consequence worth stating: adding a source cannot break this quietly. A new entry brings
 * its markers and its samples together, and if the two disagree the failure lands here, at build
 * time, naming the source.
 */
@DisplayName("LootEventBus pre-filter: it must not swallow a line a registered detector would match")
class LootEventBusPreFilterTest {

    /**
     * A detector that takes its markers and samples from the registry, claims every gate is open,
     * and simply records the lines it was offered.
     *
     * <p>Forcing the gate open is the point: this test is about the filter, not the gates, and a
     * shut gate would hide a bad marker rather than expose it.
     */
    private static class Spy extends RegistryDetector {

        private final List<String> seen = new ArrayList<>();

        Spy(LootSource source) {
            super(source);
        }

        @Override
        public boolean gateOpen(GameContext ctx) {
            return true;
        }

        @Override
        public boolean readsChat() {
            return true;
        }

        @Override
        public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
            seen.add(rawLine);
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("every registered detector's own trigger samples survive the real bus")
    void everySampleReachesItsDetector() {
        LootEventBus bus = new LootEventBus();
        List<Spy> spies = new ArrayList<>();
        for (LootSourceInfo info : LootSourceRegistry.all()) {
            if (!info.chatDriven()) {
                continue;
            }
            Spy spy = new Spy(info.source());
            spies.add(spy);
            bus.register(spy);
        }
        bus.updateContext(new GameContext(true, true, "Hub", "", "Diana", false, false));

        List<String> swallowed = new ArrayList<>();
        for (Spy spy : spies) {
            for (String sample : spy.triggerSamples()) {
                spy.seen.clear();
                bus.onChat(sample, 1_000L);
                if (!spy.seen.contains(sample)) {
                    swallowed.add(spy.source() + " :: " + sample);
                }
            }
        }
        assertTrue(swallowed.isEmpty(),
                "the pre-filter rejected lines their own detectors were registered to match:\n"
                        + String.join("\n", swallowed));
    }

    @Test
    @DisplayName("a sample still reaches its detector when only that one source is registered")
    void samplesSurviveAloneAsWellAsInACrowd() {
        // Registering everything at once builds a large marker union, which could mask a source
        // whose own markers are wrong but whose sample happens to contain a neighbour's marker.
        List<String> swallowed = new ArrayList<>();
        for (LootSourceInfo info : LootSourceRegistry.all()) {
            if (!info.chatDriven()) {
                continue;
            }
            LootEventBus bus = new LootEventBus();
            Spy spy = new Spy(info.source());
            bus.register(spy);
            bus.updateContext(new GameContext(true, true, "Hub", "", "Diana", false, false));
            for (String sample : info.triggerSamples()) {
                spy.seen.clear();
                bus.onChat(sample, 1_000L);
                if (!spy.seen.contains(sample)) {
                    swallowed.add(info.source() + " :: " + sample);
                }
            }
        }
        assertTrue(swallowed.isEmpty(),
                "these sources rely on another source's marker to get their own lines through:\n"
                        + String.join("\n", swallowed));
    }

    @Test
    @DisplayName("the filter is the union of what detectors declared, never a list of its own")
    void theFilterIsDerivedNotWritten() {
        LootEventBus bus = new LootEventBus();
        bus.register(new Spy(LootSource.PET_DROP));
        bus.updateContext(GameContext.onIsland("Hub"));
        assertEquals(List.of("PET DROP!"), bus.activeMarkers());

        bus.register(new Spy(LootSource.GLACITE_CORPSE));
        assertEquals(List.of("PET DROP!", "CORPSE LOOT!"), bus.activeMarkers());

        bus.unregister(LootSource.PET_DROP);
        assertEquals(List.of("CORPSE LOOT!"), bus.activeMarkers());
    }

    @Test
    @DisplayName("a markerless detector disables the filter rather than being quietly starved")
    void markerlessDetectorsSeeEverything() {
        LootEventBus bus = new LootEventBus();
        Spy sea = new Spy(LootSource.FISHING_RARE_SEA_CREATURE);
        bus.register(sea);
        bus.updateContext(GameContext.onIsland("Hub"));

        assertTrue(bus.unfiltered(), "a detector with no markers must not be filtered against none");
        bus.onChat("something entirely unrelated", 1L);
        assertEquals(List.of("something entirely unrelated"), sea.seen);
    }

    /**
     * <b>This test was rewritten, and the behaviour it pins was deliberately reversed.</b>
     *
     * <p>It used to assert the opposite: that one markerless detector unfiltered the bus for every
     * other open detector too, on the reasoning that "erring towards offering a line is correct;
     * erring towards dropping it is not". That reasoning is sound in isolation and it was measured
     * to be very expensive in practice. Exactly one shipped detector is markerless -- the
     * sea-creature reader, whose ninety announcements share no literal -- and it is open on every
     * island, because fishing is possible everywhere. So the filter was bypassed across the whole of
     * SkyBlock, and every line of guild chat ran the regexes of all twenty-odd open detectors:
     * 2,566 ns per line against 184 ns filtered, on the Farming Islands.
     *
     * <p>The safety margin that bought is not gone, it has moved. A detector that declares markers
     * promises that every line it can match contains one of them; that promise is what
     * {@link #preFilterNeverSwallowsARealTrigger()} checks, against every registered detector's own
     * captured lines. Skipping such a detector on a line the filter rejected is therefore provably
     * a no-op, not a gamble. What is given up is only the accident that used to cover a detector
     * whose markers were wrong in a way its own samples did not reveal.
     */
    @Test
    @DisplayName("a markerless detector is offered everything without unfiltering its neighbours")
    void oneMarkerlessDetectorDoesNotUnfilterTheBus() {
        LootEventBus bus = new LootEventBus();
        Spy banner = new Spy(LootSource.MOB_RARE_DROP);
        Spy sea = new Spy(LootSource.FISHING_RARE_SEA_CREATURE);
        bus.register(banner);
        bus.register(sea);
        bus.updateContext(GameContext.onIsland("Hub"));

        assertFalse(bus.unfiltered(), "one markerless detector must not switch filtering off");
        assertEquals(1, bus.unmarkedDetectorCount());

        // A sea-creature announcement: nothing in the marker union, so only the markerless
        // detector is asked -- which is the entire saving.
        bus.onChat("A Squid appeared.", 1L);
        assertEquals(1, sea.seen.size(), "the markerless detector must still see every line");
        assertEquals(0, banner.seen.size(),
                "a detector that declared markers has nothing to do on a line without them");

        // A line carrying the banner detector's own marker still reaches both, in order.
        bus.onChat("§6§lRARE DROP! §r§5Minos Relic", 2L);
        assertEquals(1, banner.seen.size(), "a marker hit must still reach the detector that owns it");
        assertEquals(2, sea.seen.size(), "the markerless detector sees that line too");
    }

    @Test
    @DisplayName("with nothing open the filter rejects everything, which is the whole point")
    void shutGatesCostNothing() {
        LootEventBus bus = new LootEventBus();
        Spy corpse = new Spy(LootSource.GLACITE_CORPSE) {
            @Override
            public boolean gateOpen(GameContext ctx) {
                return false;
            }
        };
        bus.register(corpse);
        bus.updateContext(GameContext.onIsland("Mineshaft"));

        assertEquals(0, bus.openDetectorCount());
        assertFalse(bus.unfiltered());
        assertTrue(bus.activeMarkers().isEmpty());
        assertTrue(bus.onChat("  §r§b§l§r§9§lLAPIS §r§b§lCORPSE LOOT!", 1L).isEmpty());
        assertTrue(corpse.seen.isEmpty(), "a shut gate must not even be offered its own trigger");
    }

    @Test
    @DisplayName("ordinary chat does not reach a detector whose markers are absent")
    void ordinaryChatIsRejected() {
        LootEventBus bus = new LootEventBus();
        Spy corpse = new Spy(LootSource.GLACITE_CORPSE);
        bus.register(corpse);
        bus.updateContext(GameContext.onIsland("Mineshaft"));

        bus.onChat("§bParty §8> §aPlayer§f: are we going again", 1L);
        bus.onChat("§eYou are now in a party with 3 players.", 1L);
        bus.onChat("§7[123] §bPlayer §7joined the lobby.", 1L);
        assertTrue(corpse.seen.isEmpty());
    }
}
