package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Experimentation Table: the claim line is the trigger, the GUI only names the game")
class ExperimentsRewardsDetectorTest {

    private static final String CLAIM = "§eYou claimed the §r§dMetaphysical §r§erewards!";

    @Test
    @DisplayName("the claim rolls on its own, with no screen title ever seen")
    void claimAloneIsEnough() {
        LootEvent event = new ExperimentsRewardsDetector().onChat(CLAIM, 1L).orElseThrow();
        assertEquals(LootSource.EXPERIMENTS_REWARDS, event.source());
        assertEquals("Metaphysical", event.subject());
    }

    @Test
    @DisplayName("a recently seen minigame improves the caption")
    void titleImprovesTheCaption() {
        ExperimentsRewardsDetector detector = new ExperimentsRewardsDetector();
        assertEquals(Optional.empty(), detector.onScreenTitle("Ultrasequencer (Metaphysical)", 1L),
                "opening a menu is not a payout");
        assertEquals("Ultrasequencer (Metaphysical)",
                detector.onChat(CLAIM, 2L).orElseThrow().subject());
    }

    @Test
    @DisplayName("every Experimentation title the research quotes is recognised")
    void titles() {
        ExperimentsRewardsDetector detector = new ExperimentsRewardsDetector();
        for (String title : new String[]{
                "Superpairs (Beginner)",
                "Chronomatron ➜ Stakes",
                "Ultrasequencer Rewards",
                "Experimentation Table"}) {
            assertEquals(Optional.empty(), detector.onScreenTitle(title, 1L), title);
        }
        assertEquals("Ultrasequencer (Metaphysical)",
                detector.onChat(CLAIM, 2L).orElseThrow().subject(),
                "the table itself carries no game name, so the last minigame stands");
    }

    @Test
    @DisplayName("a stale game name is dropped rather than captioning the wrong minigame")
    void staleGameName() {
        ExperimentsRewardsDetector detector = new ExperimentsRewardsDetector();
        detector.onScreenTitle("Superpairs (Grand)", 1L);
        assertEquals("Metaphysical", detector.onChat(
                CLAIM, 1L + ExperimentsRewardsDetector.GAME_MEMORY_MILLIS).orElseThrow().subject());
    }

    @Test
    @DisplayName("the registry sample matches")
    void sample() {
        ExperimentsRewardsDetector detector = new ExperimentsRewardsDetector();
        for (String sample
                : LootSourceRegistry.info(LootSource.EXPERIMENTS_REWARDS).triggerSamples()) {
            assertTrue(detector.onChat(sample, 1L).isPresent(), sample);
        }
    }

    @Test
    @DisplayName("other 'rewards' lines and player chat do not claim it")
    void negatives() {
        ExperimentsRewardsDetector detector = new ExperimentsRewardsDetector();
        assertEquals(Optional.empty(),
                detector.onChat("§bGrazma§f: You claimed the Metaphysical rewards!", 1L));
        assertEquals(Optional.empty(), detector.onChat("§eYou claimed the rewards!", 1L));
        assertEquals(Optional.empty(),
                detector.onChat("  §r§a§lREWARDS", 1L));
        assertEquals(Optional.empty(), detector.onChat(
                "§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Recombobulator 3000 "
                        + "§r§ein their Obsidian Chest§r§e!", 1L));
    }

    @Test
    @DisplayName("no island gate: the table can sit on a private island")
    void gate() {
        ExperimentsRewardsDetector detector = new ExperimentsRewardsDetector();
        assertTrue(detector.gateOpen(GameContext.onIsland("Private Island")));
        assertTrue(detector.gateOpen(GameContext.onIsland("Hub")));
        assertFalse(detector.gateOpen(GameContext.UNKNOWN),
                "but never outside SkyBlock");
    }
}
