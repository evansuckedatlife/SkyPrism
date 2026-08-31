package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceInfo;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.SourceDetector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The test for the failure this feature is most likely to actually ship: two detectors both claiming
 * one line.
 *
 * <p>Twenty-odd sources share a small number of sentence shapes -- one drop banner, one boss-down
 * banner, one container reward block -- so a pattern that is one character too loose does not fail,
 * it succeeds for the wrong source. The symptom is a widget captioned "Fossil Excavation" when the
 * player opened a treasure chest, which nobody reports as a bug because it looks like the feature
 * working slightly oddly.
 *
 * <p>So this drives every container detector with every <em>other</em> source's captured sample
 * lines, from the registry, and fails on any match. It is deliberately written against the registry
 * rather than a hand-kept list: a source added later brings its own samples, and this test starts
 * checking them without anyone remembering to.
 */
@DisplayName("Containers: no detector may claim another source's line")
class ContainerCrossSourceTest {

    private static final Supplier<String> ME = () -> "Leebys";

    /**
     * The three chest sources deliberately share one sentence and split it by tier, so a sample
     * belonging to one of them is a legitimate near-match for another. Their split is pinned
     * exhaustively in {@link ChestDetectorsTest} instead; excluding them here keeps this test about
     * unrelated sources rather than re-asserting that design.
     */
    private static final Set<LootSource> CHEST_FAMILY = EnumSet.of(
            LootSource.DUNGEON_REWARD_CHEST,
            LootSource.KUUDRA_REWARD_CHEST,
            LootSource.CROESUS_CHEST);

    @Test
    @DisplayName("every container detector rejects every other source's captured lines")
    void noCrossSourceMatches() {
        List<String> failures = new ArrayList<>();
        for (SourceDetector detector : ContainerDetectors.all(ME)) {
            LootSource own = detector.source();
            armEverything(detector);
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                if (info.source() == own) {
                    continue;
                }
                if (CHEST_FAMILY.contains(own) && CHEST_FAMILY.contains(info.source())) {
                    continue;
                }
                for (String sample : info.triggerSamples()) {
                    Optional<LootEvent> event = detector.onChat(sample, 1_000L);
                    if (event.isPresent()) {
                        failures.add(own + " claimed a " + info.source() + " line: " + sample);
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail("cross-source false positives:\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    @DisplayName("no detector claims a line a player typed")
    void noPlayerChatMatches() {
        List<String> spoofs = new ArrayList<>();
        for (LootSourceInfo info : LootSourceRegistry.all()) {
            for (String sample : info.triggerSamples()) {
                // What a player quoting a drop actually looks like once the client flattens it:
                // their name and a colon in front, and no formatting of their own.
                spoofs.add("§bGrazma§f: " + stripCodes(sample));
                spoofs.add("§9Party §8> §bGrazma§f: " + stripCodes(sample));
            }
        }
        for (SourceDetector detector : ContainerDetectors.all(ME)) {
            armEverything(detector);
            for (String spoof : spoofs) {
                assertEquals(Optional.empty(), detector.onChat(spoof, 1_000L),
                        detector.source() + " was spoofed by: " + spoof);
            }
        }
    }

    @Test
    @DisplayName("a line no source ever sends is claimed by nobody")
    void ordinaryChatIsFree() {
        LootEventBus bus = new LootEventBus();
        ContainerDetectors.registerAll(bus, ME);
        bus.updateContext(new GameContext(true, true, "Crystal Hollows", "Jungle Temple",
                "Diana", false, false));
        for (String line : new String[]{
                "§bGrazma§f: anyone want to do f7",
                "§eYou are now ready to use Mining Speed Boost!",
                "§6§lRARE DROP! §r§9Judgement Core §r§b(+§r§b168% Magic Find§r§b)",
                "§aYou uncovered a treasure chest!",
                "§6You have successfully picked the lock on this chest!",
                "§cThis chest has already been looted.",
                "  §r§a§lREWARDS",
                "    §r§dGemstone Powder §r§8x537",
                "§e§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                ""}) {
            assertEquals(Optional.empty(), bus.onChat(line, 1_000L), line);
        }
    }

    @Test
    @DisplayName("the bus registers all of them without a duplicate source")
    void registersCleanly() {
        LootEventBus bus = new LootEventBus();
        ContainerDetectors.registerAll(bus, ME);
        assertEquals(ContainerDetectors.all(ME).size(), bus.registeredCount());
    }

    @Test
    @DisplayName("on an island none of them can fire on, the bus is genuinely idle")
    void shutIslandCostsNothing() {
        LootEventBus bus = new LootEventBus();
        ContainerDetectors.registerAll(bus, ME);
        bus.updateContext(GameContext.onIsland("The Barn"));
        // The screen-gated sources are "in SkyBlock" by design and stay open; the island-gated ones
        // must all be shut, so the open set is exactly the four that arm on a GUI title or a claim.
        List<SourceDetector> open = bus.openDetectors();
        for (SourceDetector detector : open) {
            assertTrue(LootSourceRegistry.gate(detector.source())
                            .describe().contains("armed by"),
                    detector.source() + " should be shut on an island it cannot fire on");
        }
    }

    /** Puts a detector into whichever state lets it match the most, so nothing hides behind a gate. */
    private static void armEverything(SourceDetector detector) {
        detector.gateOpen(GameContext.onIsland("Crystal Hollows", "Jungle Temple"));
        detector.onScreenTitle("Croesus", 1L);
        detector.onScreenTitle("Superpairs (Grand)", 1L);
    }

    private static String stripCodes(String line) {
        return com.skyprism.core.util.TextClean.clean(line);
    }
}
