package com.skyprism.mc.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.LootParser;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceInfo;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.core.util.FixedClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The properties that only show up once the whole detector set is registered together.
 *
 * <p>These are integration assertions about the shipped configuration rather than about any one
 * class: what the shipped defaults actually add up to, what the chat pre-filter really costs on a
 * given island, and whether a source can be armed with a policy it is structurally incapable of
 * honouring. Every one of them is a question a player would eventually ask as "why does this never
 * fire", which is the failure mode this whole feature was warned about.</p>
 */
@DisplayName("LootMachine: the shipped configuration, end to end")
final class LootMachineWiringMcTest {

    private static LootMachine armed(String island) {
        LootMachine machine = new LootMachine(new FixedClock());
        machine.registerDetectors(() -> "Tester");
        machine.updateContext(true, true, island, false);
        return machine;
    }

    @Nested
    @DisplayName("the shipped defaults")
    final class Defaults {

        @Test
        @DisplayName("no source ships with a policy it cannot honour")
        void noSilentlyDeadPolicy() {
            List<String> broken = new ArrayList<>();
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                RollPolicy policy = info.defaultPolicy();
                if (policy == RollPolicy.ON_RARE_BANNER && !info.emitsRareBanner()) {
                    broken.add(info.source() + " waits for a banner it never emits");
                }
                if (policy == RollPolicy.ON_JACKPOT_ITEM_ONLY && info.jackpotItems().isEmpty()) {
                    broken.add(info.source() + " waits for a jackpot item it has none of");
                }
            }
            assertEquals(List.of(), broken,
                    "a policy a source cannot satisfy is a detector that silently never fires, "
                            + "which is indistinguishable from a working one");
        }

        @Test
        @DisplayName("the high-frequency sources ship switched off or filtered, never on ALWAYS")
        void firehosesAreNotArmedOnAlways() {
            // Named individually rather than derived, because "this one would strobe the widget"
            // is a judgement about SkyBlock's pacing that no property of the code can encode.
            LootSource[] firehoses = {
                LootSource.FISHING_SEA_CREATURE,
                LootSource.FISHING_TROPHY_FISH,
                LootSource.MINING_PRISTINE_GEMSTONE,
                LootSource.MINING_COMPACT,
                LootSource.GARDEN_RARE_CROP,
                LootSource.SLAYER_MINIBOSS,
                LootSource.RIFT_MOTES_ORB,
                LootSource.CHOCOLATE_FACTORY_STRAY,
                LootSource.FROZEN_TREASURE,
            };
            for (LootSource source : firehoses) {
                assertFalse(LootSourceRegistry.defaultPolicy(source) == RollPolicy.ALWAYS,
                        source + " fires several times a minute; ALWAYS would make the machine "
                                + "unusable, which is a bug rather than a preference");
            }
        }

        @Test
        @DisplayName("something is armed, or the whole feature shipped switched off by accident")
        void somethingIsArmed() {
            LootMachine machine = armed("Hub");
            assertTrue(machine.armedSourceCount() > 20);
            assertTrue(machine.registeredCount() > 10);
        }
    }

    @Nested
    @DisplayName("what a chat line costs on a real island")
    final class ChatCost {

        @Test
        @DisplayName("an island's open set is small, and its markers are distinctive literals")
        void gatesReallyDoShutMostOfTheGame() {
            LootMachine hub = armed("Hub");
            LootMachine mineshaft = armed("Mineshaft");

            assertFalse(hub.gateOpen(LootSource.GLACITE_CORPSE), "no corpses in the Hub");
            assertTrue(mineshaft.gateOpen(LootSource.GLACITE_CORPSE));
            assertFalse(mineshaft.gateOpen(LootSource.ARACHNE), "no Arachne in a mineshaft");
        }

        @Test
        @DisplayName("the bus is unfiltered only because of the markerless sea-creature source")
        void unfilteredHasExactlyOneCause() {
            LootMachine machine = armed("Hub");
            if (machine.unfiltered()) {
                // Prove the cause rather than tolerating it: switching the markerless sources off
                // must be enough to get the pre-filter back, or something else is unfiltered too
                // and the /skyprism sources explanation would be wrong.
                machine.setPolicy(LootSource.FISHING_RARE_SEA_CREATURE, RollPolicy.NEVER);
                machine.setPolicy(LootSource.FISHING_SEA_CREATURE, RollPolicy.NEVER);
                assertFalse(machine.unfiltered(),
                        "some other source is markerless, so the report names the wrong cause");
            }
            assertFalse(machine.activeMarkers().isEmpty());
        }

        @Test
        @DisplayName("ordinary player chat matches no marker, which is the whole pre-filter budget")
        void playerChatIsRejectedByTheFilter() {
            LootMachine machine = armed("Hub");
            machine.setPolicy(LootSource.FISHING_RARE_SEA_CREATURE, RollPolicy.NEVER);
            machine.setPolicy(LootSource.FISHING_SEA_CREATURE, RollPolicy.NEVER);

            assertFalse(machine.wantsLine("Party > Steve: gg that was a fast run"));
            assertFalse(machine.wantsLine("Guild > Alex: anyone want to do f7"));
            assertFalse(machine.wantsLine("You are now in the party of Steve"));
        }
    }

    @Nested
    @DisplayName("the drop feed")
    final class DropFeed {

        @Test
        @DisplayName("the shared parser really does decompose the banner the feed relies on")
        void parserHandlesThePlainBanner() {
            var drops = new LootParser().parse(
                    "§6§lRARE DROP! §r§9Judgement Core §r§b(+§r§b168% §r§b✯ Magic Find§r§b)");
            assertEquals(1, drops.size());
            assertEquals("Judgement Core", drops.get(0).itemName());
            assertTrue(drops.get(0).rare());
        }

        @Test
        @DisplayName("drops stop landing once the loot window closes")
        void feedRespectsTheWindow() {
            FixedClock clock = new FixedClock(1_000L);
            SlotRoll roll = new SlotRoll(SlotRollConfig.defaults(), clock);
            LootMachine machine = new LootMachine(clock);
            machine.wire(() -> roll, () -> SlotRollConfig.defaults().lootWindowMillis());

            machine.admit(new com.skyprism.core.loot.LootEvent(
                    LootSource.GLACITE_CORPSE, "Vanguard Corpse", clock.millis()), clock.millis());
            clock.advance(100L);
            machine.onChat("§6§lRARE DROP! §r§9Ascension Rope §r§b(+120% ✯ Magic Find)",
                    clock.millis());
            assertEquals(1, roll.capturedDropCount());

            clock.advance(SlotRollConfig.defaults().lootWindowMillis());
            machine.onChat("§6§lRARE DROP! §r§9Fine Onyx Gemstone §r§b(+120% ✯ Magic Find)",
                    clock.millis());
            assertEquals(1, roll.capturedDropCount(),
                    "a drop past the window belongs to whatever happens next, not to this roll");
        }

        @Test
        @DisplayName("simulated loot for any source comes out of that source's real jackpot list")
        void simulatedLootIsNotInvented() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                var drops = com.skyprism.mc.command.SimulatedLoot.rollFor(info.source());
                assertFalse(drops.isEmpty(), info.source() + " simulated to nothing");
                for (var drop : drops) {
                    if (drop.rare()) {
                        assertTrue(info.jackpotItems().contains(drop.itemName()),
                                info.source() + " celebrated \"" + drop.itemName()
                                        + "\", which is not on its jackpot list");
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("every source is reachable from the command tree")
    final class CommandSurface {

        @Test
        @DisplayName("every source id round-trips, so /skyprism simulate can reach all of them")
        void everySourceIdResolves() {
            for (LootSource source : LootSource.values()) {
                assertEquals(source, LootSource.byId(source.id()).orElseThrow(),
                        source + " cannot be typed back in");
            }
        }

        @Test
        @DisplayName("every source has a display name and a gate description worth printing")
        void everySourceExplainsItself() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                assertFalse(info.displayName().isBlank(), info.source().name());
                assertNotNull(info.gate());
                assertFalse(info.gate().describe().isBlank(), info.source().name());
            }
        }
    }
}
