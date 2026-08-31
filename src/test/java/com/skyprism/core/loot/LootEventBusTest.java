package com.skyprism.core.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LootEventBus: routing, gating and the cost of a line nobody wants")
class LootEventBusTest {

    /** A detector with hand-written markers, so the routing tests do not depend on registry data. */
    private static class Stub implements SourceDetector {

        private final LootSource source;
        private final String trigger;
        private final List<String> markers;
        private boolean open = true;
        private final AtomicInteger calls = new AtomicInteger();

        Stub(LootSource source, String trigger, String... markers) {
            this.source = source;
            this.trigger = trigger;
            this.markers = List.of(markers);
        }

        @Override
        public LootSource source() {
            return source;
        }

        @Override
        public boolean gateOpen(GameContext ctx) {
            return open;
        }

        @Override
        public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
            calls.incrementAndGet();
            return rawLine.contains(trigger)
                    ? Optional.of(new LootEvent(source, "subject of " + source, nowMillis))
                    : Optional.empty();
        }

        @Override
        public List<String> chatMarkers() {
            return markers;
        }
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("an open detector receives a line carrying its marker and produces its event")
        void openDetectorFires() {
            LootEventBus bus = new LootEventBus();
            bus.register(new Stub(LootSource.GLACITE_CORPSE, "CORPSE LOOT!", "CORPSE LOOT!"));
            bus.updateContext(GameContext.onIsland("Mineshaft"));

            LootEvent event = bus.onChat("  §r§b§lVANGUARD CORPSE LOOT!", 7L).orElseThrow();
            assertEquals(LootSource.GLACITE_CORPSE, event.source());
            assertEquals(7L, event.atMillis());
        }

        @Test
        @DisplayName("a shut gate is never consulted, not even for its own trigger")
        void shutGateIsNotConsulted() {
            LootEventBus bus = new LootEventBus();
            Stub stub = new Stub(LootSource.GLACITE_CORPSE, "CORPSE LOOT!", "CORPSE LOOT!");
            stub.open = false;
            bus.register(stub);
            bus.updateContext(GameContext.onIsland("Mineshaft"));

            assertTrue(bus.onChat("  §r§b§lVANGUARD CORPSE LOOT!", 1L).isEmpty());
            assertEquals(0, stub.calls.get());
        }

        @Test
        @DisplayName("first registered wins, so one line cannot spin the machine twice")
        void firstMatchWins() {
            LootEventBus bus = new LootEventBus();
            bus.register(new Stub(LootSource.SLAYER_BOSS, "DROP!", "DROP!"));
            bus.register(new Stub(LootSource.MOB_RARE_DROP, "DROP!", "DROP!"));
            bus.updateContext(GameContext.onIsland("Hub"));

            LootEvent event = bus.onChat("§6§lRARE DROP! §r§9Judgement Core", 1L).orElseThrow();
            assertEquals(LootSource.SLAYER_BOSS, event.source(),
                    "register the specific before the general; the order is the policy");
        }

        @Test
        @DisplayName("a line matching nothing produces nothing, and detectors past the filter still say no")
        void noMatchIsQuiet() {
            LootEventBus bus = new LootEventBus();
            Stub stub = new Stub(LootSource.PET_DROP, "PET DROP!", "DROP!");
            bus.register(stub);
            bus.updateContext(GameContext.onIsland("Hub"));

            assertTrue(bus.onChat("§6§lRARE DROP! §r§9Judgement Core", 1L).isEmpty());
            assertEquals(1, stub.calls.get(), "the marker matched, so the detector had to decide");
        }

        @Test
        @DisplayName("null and empty lines are ignored rather than thrown, because chat callbacks are noisy")
        void nullLinesAreIgnored() {
            LootEventBus bus = new LootEventBus();
            Stub stub = new Stub(LootSource.PET_DROP, "PET DROP!", "DROP!");
            bus.register(stub);
            bus.updateContext(GameContext.onIsland("Hub"));

            assertTrue(bus.onChat(null, 1L).isEmpty());
            assertTrue(bus.onChat("", 1L).isEmpty());
            assertEquals(0, stub.calls.get());
        }

        @Test
        @DisplayName("with nothing registered a line costs one length check")
        void emptyBusIsFree() {
            LootEventBus bus = new LootEventBus();
            assertTrue(bus.onChat("§6§lRARE DROP! §r§9Judgement Core", 1L).isEmpty());
            assertEquals(0, bus.openDetectorCount());
            assertEquals(0, bus.registeredCount());
        }
    }

    @Nested
    @DisplayName("registration and context")
    class Lifecycle {

        @Test
        @DisplayName("two detectors for one source are rejected, since the second could only double-roll")
        void duplicateSourceRejected() {
            LootEventBus bus = new LootEventBus();
            bus.register(new Stub(LootSource.PET_DROP, "x", "x"));
            assertThrows(IllegalArgumentException.class,
                    () -> bus.register(new Stub(LootSource.PET_DROP, "y", "y")));
        }

        @Test
        @DisplayName("a null detector is rejected up front")
        void nullDetectorRejected() {
            LootEventBus bus = new LootEventBus();
            assertThrows(NullPointerException.class, () -> bus.register(null));
        }

        @Test
        @DisplayName("gates are re-evaluated on context change and not on every line")
        void gatesFollowTheContext() {
            LootEventBus bus = new LootEventBus();
            Stub corpse = new Stub(LootSource.GLACITE_CORPSE, "CORPSE LOOT!", "CORPSE LOOT!") {
            };
            bus.register(corpse);
            bus.updateContext(GameContext.onIsland("Mineshaft"));
            assertEquals(1, bus.openDetectorCount());

            corpse.open = false;
            // The gate has changed its mind, but nothing has told the bus, and a chat line must not
            // be what tells it: that is the per-line cost the whole design removes.
            assertEquals(1, bus.openDetectorCount());

            bus.updateContext(GameContext.onIsland("Hub"));
            assertEquals(0, bus.openDetectorCount());
        }

        @Test
        @DisplayName("an unchanged context does not rebuild anything")
        void repeatedContextIsANoOp() {
            LootEventBus bus = new LootEventBus();
            bus.register(new Stub(LootSource.PET_DROP, "PET DROP!", "PET DROP!"));
            GameContext ctx = GameContext.onIsland("Hub");
            bus.updateContext(ctx);
            List<SourceDetector> before = bus.openDetectors();
            bus.updateContext(new GameContext(true, true, "Hub", "", "", false, false));
            assertEquals(before, bus.openDetectors());
            assertSame(ctx, bus.context());
        }

        @Test
        @DisplayName("a null context is treated as knowing nothing, not as knowing everything")
        void nullContextIsShut() {
            LootEventBus bus = new LootEventBus();
            bus.register(new Stub(LootSource.PET_DROP, "PET DROP!", "PET DROP!") {
                @Override
                public boolean gateOpen(GameContext ctx) {
                    return ctx.inGame();
                }
            });
            bus.updateContext(GameContext.onIsland("Hub"));
            assertEquals(1, bus.openDetectorCount());
            bus.updateContext(null);
            assertEquals(0, bus.openDetectorCount());
            assertEquals(GameContext.UNKNOWN, bus.context());
        }

        @Test
        @DisplayName("clear drops everything, e.g. on disconnect")
        void clearResets() {
            LootEventBus bus = new LootEventBus();
            bus.register(new Stub(LootSource.PET_DROP, "PET DROP!", "PET DROP!"));
            bus.updateContext(GameContext.onIsland("Hub"));
            bus.clear();
            assertEquals(0, bus.registeredCount());
            assertEquals(0, bus.openDetectorCount());
            assertEquals(GameContext.UNKNOWN, bus.context());
        }

        @Test
        @DisplayName("a detector that reads no chat never joins the per-line path")
        void nonChatDetectorsAreNotOnTheHotPath() {
            LootEventBus bus = new LootEventBus();
            bus.register(new SourceDetector() {
                @Override
                public LootSource source() {
                    return LootSource.DIANA_MYTHOLOGICAL;
                }

                @Override
                public boolean gateOpen(GameContext ctx) {
                    return true;
                }

                @Override
                public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
                    throw new AssertionError("must never be offered a line");
                }

                @Override
                public boolean readsChat() {
                    return false;
                }
            });
            bus.updateContext(GameContext.onIsland("Hub"));
            assertEquals(0, bus.openDetectorCount());
            assertFalse(bus.unfiltered(), "an entity-driven detector must not unfilter the bus");
            assertTrue(bus.onChat("anything at all", 1L).isEmpty());
        }
    }

    @Nested
    @DisplayName("screen titles")
    class ScreenTitles {

        @Test
        @DisplayName("titles reach every registered detector, gate or no gate")
        void titlesBypassTheGate() {
            LootEventBus bus = new LootEventBus();
            Stub stub = new Stub(LootSource.CROESUS_CHEST, "never", "never") {
                @Override
                public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
                    return title.startsWith("Croesus")
                            ? Optional.of(new LootEvent(LootSource.CROESUS_CHEST, title, nowMillis))
                            : Optional.empty();
                }
            };
            stub.open = false;
            bus.register(stub);
            bus.updateContext(GameContext.onIsland("Hub"));

            // The title IS the gate for a container source, and a stricter one than any island test.
            LootEvent event = bus.onScreenTitle("Croesus", 5L).orElseThrow();
            assertEquals(LootSource.CROESUS_CHEST, event.source());
            assertEquals("Croesus", event.subject());
            assertTrue(bus.onScreenTitle("Your Backpack", 5L).isEmpty());
            assertTrue(bus.onScreenTitle(null, 5L).isEmpty());
        }
    }
}
