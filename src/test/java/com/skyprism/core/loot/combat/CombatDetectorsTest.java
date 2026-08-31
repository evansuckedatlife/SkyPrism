package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.SourceDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The combat set on the real bus: order, gates, and one claimant per line")
class CombatDetectorsTest {

    /** The context each source needs before its gate opens. */
    private static final Map<LootSource, GameContext> CONTEXTS = contexts();

    private static Map<LootSource, GameContext> contexts() {
        GameContext hub = GameContext.onIsland("Hub", "Graveyard");
        GameContext catacombs =
                new GameContext(true, true, "Catacombs", "The Catacombs (F7)", "", true, false);
        Map<LootSource, GameContext> map = new EnumMap<>(LootSource.class);
        map.put(LootSource.SLAYER_BOSS, hub);
        map.put(LootSource.SLAYER_MINIBOSS, hub);
        map.put(LootSource.MOB_RARE_DROP, hub);
        map.put(LootSource.PET_DROP, hub);
        map.put(LootSource.DUNGEON_BOSS, catacombs);
        map.put(LootSource.DUNGEON_RUN_COMPLETE, catacombs);
        map.put(LootSource.KUUDRA_COMPLETE,
                GameContext.onIsland("Kuudra", "Kuudra's Hollow (T5)"));
        map.put(LootSource.ENDER_DRAGON, GameContext.onIsland("The End", "Dragon's Nest"));
        map.put(LootSource.ENDSTONE_PROTECTOR, GameContext.onIsland("The End"));
        map.put(LootSource.CRIMSON_MINIBOSS, GameContext.onIsland("Crimson Isle"));
        map.put(LootSource.ARACHNE, GameContext.onIsland("Spider's Den"));
        return map;
    }

    @Nested
    @DisplayName("registration order, which is behaviour")
    class Order {

        @Test
        @DisplayName("the catch-all banner detector is last, and pets sit immediately before it")
        void catchAllIsLast() {
            List<SourceDetector> detectors = CombatDetectors.create().detectors();
            int size = detectors.size();
            assertInstanceOf(MobRareDropDetector.class, detectors.get(size - 1),
                    "MOB_RARE_DROP matches a banner every other combat source can also emit, so it "
                            + "must be offered a line last");
            assertInstanceOf(PetDropDetector.class, detectors.get(size - 2));
        }

        @Test
        @DisplayName("the summary header comes before the defeat line, as Hypixel prints them")
        void headerBeforeDefeat() {
            List<SourceDetector> detectors = CombatDetectors.create().detectors();
            int header = indexOf(detectors, LootSource.DUNGEON_RUN_COMPLETE);
            int defeat = indexOf(detectors, LootSource.DUNGEON_BOSS);
            assertTrue(header < defeat,
                    "the header carries the floor the defeat line's caption wants");
        }

        @Test
        @DisplayName("every combat source has exactly one detector and no duplicates")
        void oneDetectorPerSource() {
            List<SourceDetector> detectors = CombatDetectors.create().detectors();
            assertEquals(CONTEXTS.size(), detectors.size());
            for (LootSource source : CONTEXTS.keySet()) {
                assertEquals(1, detectors.stream().filter(d -> d.source() == source).count(),
                        "expected exactly one detector for " + source);
            }
        }

        @Test
        @DisplayName("the whole set registers on a real bus without a duplicate-source rejection")
        void registersCleanly() {
            LootEventBus bus = new LootEventBus();
            CombatDetectors.create().registerAll(bus);
            assertEquals(CONTEXTS.size(), bus.registeredCount());
        }

        private static int indexOf(List<SourceDetector> detectors, LootSource source) {
            for (int i = 0; i < detectors.size(); i++) {
                if (detectors.get(i).source() == source) {
                    return i;
                }
            }
            throw new AssertionError("no detector for " + source);
        }
    }

    @Nested
    @DisplayName("shared state reaches the detectors that need it")
    class SharedState {

        @Test
        @DisplayName("the slayer quest state gates both slayer detectors at once")
        void slayerStateIsShared() {
            CombatDetectors.Wiring wiring = CombatDetectors.create();
            LootEventBus bus = new LootEventBus();
            wiring.registerAll(bus);
            bus.updateContext(CONTEXTS.get(LootSource.SLAYER_BOSS));
            int armed = bus.openDetectorCount();

            wiring.slayerQuest().questEnded();
            // The bus caches its open set, so the gate only reshuts on the next recompute; that is
            // exactly what SlayerQuestState.onChange exists to drive.
            bus.unregister(LootSource.SLAYER_BOSS);
            bus.unregister(LootSource.SLAYER_MINIBOSS);
            assertEquals(armed - 2, bus.openDetectorCount());
        }

        @Test
        @DisplayName("the dungeon run state is the same object the header detector writes")
        void dungeonStateIsShared() {
            CombatDetectors.Wiring wiring = CombatDetectors.create();
            LootEventBus bus = new LootEventBus();
            wiring.registerAll(bus);
            bus.updateContext(CONTEXTS.get(LootSource.DUNGEON_BOSS));

            bus.onChat("                Master Mode The Catacombs - Floor VII", 0L);
            assertTrue(wiring.dungeonRun().masterMode());
            assertEquals("Master Mode Floor VII", wiring.dungeonRun().describe());

            LootEvent event = bus.onChat("     ☠ Defeated Necron in 5m 43s", 1L).orElseThrow();
            assertEquals(LootSource.DUNGEON_BOSS, event.source());
            assertEquals("Necron (Master Mode Floor VII)", event.subject());
        }

        @Test
        @DisplayName("the wiring hands out the very state objects the detectors hold")
        void wiringExposesTheState() {
            CombatDetectors.Wiring wiring = CombatDetectors.create();
            assertSame(wiring.slayerQuest(), wiring.slayerQuest());
            assertSame(wiring.dungeonRun(), wiring.dungeonRun());
        }
    }

    @Nested
    @DisplayName("end to end: every registry sample reaches its own source and nobody else's")
    class EndToEnd {

        @Test
        @DisplayName("each source's captured lines survive the pre-filter and produce its event")
        void ownSamplesProduceOwnEvent() {
            long now = 0L;
            for (Map.Entry<LootSource, GameContext> entry : CONTEXTS.entrySet()) {
                LootSource source = entry.getKey();
                LootEventBus bus = new LootEventBus();
                CombatDetectors.create().registerAll(bus);
                bus.updateContext(entry.getValue());

                for (String sample : LootSourceRegistry.info(source).triggerSamples()) {
                    // Step well past SlayerBossDetector's pairing window so the two slayer lines,
                    // which are one kill in game, are each seen as a fresh event here.
                    now += 60_000L;
                    assertTrue(bus.passesPreFilter(sample),
                            source + " declared markers that swallow its own line: " + sample);
                    LootEvent event = bus.onChat(sample, now)
                            .orElseThrow(() -> new AssertionError(
                                    source + " produced nothing for: " + sample));
                    assertEquals(source, event.source(),
                            "wrong claimant for " + sample);
                    assertEquals(now, event.atMillis());
                }
            }
        }

        @Test
        @DisplayName("no combat detector claims a line from outside combat")
        void foreignSourcesAreDeclined() {
            // Real captured lines belonging to other agents' areas. Every one of them must fall
            // through the entire combat set, whatever context is set: cross-source false positives
            // are the likeliest bug in this feature and this is the test that would catch one.
            List<String> foreign = List.of(
                    "§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!",
                    "§6§lWow! §r§eYou dug out §r§62,500 coins§r§e!",
                    "  §r§6§lCHEST LOCKPICKED",
                    "  §r§5§lLOOT CHEST COLLECTED",
                    "  §r§6§lEXCAVATION COMPLETE",
                    "  §r§b§l§r§9§lLAPIS §r§b§lCORPSE LOOT!",
                    "§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Recombobulator 3000 "
                            + "§r§ein their Obsidian Chest§r§e!",
                    "§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§9Lavahorse §r§6§lGOLD§r§f!",
                    "§d§lPRISTINE! §r§fYou found §r§a☘ Flawed Jade Gemstone §r§8x20§r§f!",
                    "§5§lWOW! §r§aYou found a §r§bGlacite Mineshaft §r§aportal!",
                    "§6§lEXCAVATOR! §r§fYou found a §r§9Suspicious Scrap§r§f!",
                    "FROZEN TREASURE! You found Glacial Talisman!",
                    "§9§lRARE! §r§9Scavenger IV §r§egift with §r§aSteve§r§f§r§e!",
                    "§D§LHOPPITY'S HUNT §7You found §6Solomon §7(§6§LLEGENDARY§7)!",
                    "§6§lRARE CROP! §r§9Cropie",
                    "§6§lVERY RARE CROP! §r§9Burrowing Spores",
                    "RARE DROP! You dropped 48x Enchanted Melon Slice!",
                    "§6§lRARE DROP! §r§aNot Just a Pest Vinyl §r§6(Cocoaleech)",
                    "§5§lORB! §r§dPicked up §r§5+120 Motes§r§d!",
                    "A Squid appeared.",
                    "You have angered a legendary creature... Lord Jawbus has arrived.",
                    "§aYou found §r§cScavenged Diamond Axe §r§awith your §r§cMetal Detector§r§a!",
                    "§9Party §8> Steve§f: §rRARE DROP! §r§9Judgement Core",
                    "§9Party §8> Steve§f: §rNICE! SLAYER BOSS SLAIN!",
                    "§7Steve§f: §rARACHNE DOWN!");

            for (GameContext ctx : CONTEXTS.values()) {
                LootEventBus bus = new LootEventBus();
                CombatDetectors.create().registerAll(bus);
                bus.updateContext(ctx);
                for (String line : foreign) {
                    assertTrue(bus.onChat(line, 1L).isEmpty(),
                            "a combat detector claimed a foreign line in " + ctx.island()
                                    + ": " + line);
                }
            }
        }

        @Test
        @DisplayName("nothing at all is open outside SkyBlock, so a lobby costs one length check")
        void nothingOpenOutsideSkyBlock() {
            LootEventBus bus = new LootEventBus();
            CombatDetectors.create().registerAll(bus);
            bus.updateContext(GameContext.UNKNOWN);
            assertEquals(0, bus.openDetectorCount());
            assertFalse(bus.unfiltered(),
                    "no open detector means no unfiltered detector either");
            assertTrue(bus.onChat("§6§lRARE DROP! §r§9Judgement Core", 0L).isEmpty());
        }

        @Test
        @DisplayName("on an island with nothing armed, only the server-wide banners stay open")
        void quietIslandKeepsOnlyTheBanners() {
            LootEventBus bus = new LootEventBus();
            CombatDetectors.create().registerAll(bus);
            bus.updateContext(GameContext.onIsland("Garden"));
            // Slayers stay armed because nothing has reported quest state yet; that is the
            // deliberate tri-state in SlayerQuestState, not an oversight.
            for (SourceDetector detector : bus.openDetectors()) {
                assertTrue(List.of(LootSource.MOB_RARE_DROP, LootSource.PET_DROP,
                                LootSource.SLAYER_BOSS, LootSource.SLAYER_MINIBOSS)
                                .contains(detector.source()),
                        detector.source() + " should be shut in the Garden");
            }
        }
    }
}
