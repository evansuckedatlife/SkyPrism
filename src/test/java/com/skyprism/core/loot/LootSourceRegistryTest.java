package com.skyprism.core.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("LootSourceRegistry: the seeded source table and its invariants")
class LootSourceRegistryTest {

    @Nested
    @DisplayName("completeness")
    class Completeness {

        @Test
        @DisplayName("every LootSource constant has exactly one entry")
        void everySourceIsDescribed() {
            List<LootSource> missing = new ArrayList<>();
            for (LootSource source : LootSource.values()) {
                try {
                    assertNotNull(LootSourceRegistry.info(source));
                } catch (IllegalStateException e) {
                    missing.add(source);
                }
            }
            assertTrue(missing.isEmpty(), "sources with no registry entry: " + missing);
            assertEquals(LootSource.values().length, LootSourceRegistry.all().size());
        }

        @Test
        @DisplayName("display names are present and unique, so the config screen cannot show two rows alike")
        void displayNamesUnique() {
            Set<String> seen = new HashSet<>();
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                assertFalse(info.displayName().isBlank(), info.source() + " has a blank display name");
                assertTrue(seen.add(info.displayName()),
                        "duplicate display name " + info.displayName() + " on " + info.source());
            }
        }

        @Test
        @DisplayName("ids round-trip, because config files are edited by hand")
        void idsRoundTrip() {
            for (LootSource source : LootSource.values()) {
                assertEquals(source, LootSource.byId(source.id()).orElseThrow());
                assertEquals(source, LootSource.byId(source.name()).orElseThrow());
                assertEquals(source, LootSource.byId(" " + source.id().replace('_', '-') + " ")
                        .orElseThrow());
            }
            assertTrue(LootSource.byId(null).isEmpty());
            assertTrue(LootSource.byId("not_a_source").isEmpty());
        }

        @Test
        @DisplayName("every entry explains its default, because a default nobody can justify is a guess")
        void everyEntryCarriesANote() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                assertFalse(info.note().isBlank(), info.source() + " ships with no rationale");
            }
        }
    }

    @Nested
    @DisplayName("policy invariants")
    class Policies {

        @Test
        @DisplayName("ON_RARE_BANNER is never set on a source that emits no banner")
        void noBannerPolicyWithoutABanner() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                if (info.defaultPolicy() == RollPolicy.ON_RARE_BANNER) {
                    assertTrue(info.emitsRareBanner(),
                            info.source() + " waits for a banner it never receives");
                }
            }
        }

        @Test
        @DisplayName("ON_JACKPOT_ITEM_ONLY is never set with an empty jackpot list")
        void noJackpotPolicyWithoutItems() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                if (info.defaultPolicy() == RollPolicy.ON_JACKPOT_ITEM_ONLY) {
                    assertFalse(info.jackpotItems().isEmpty(),
                            info.source() + " waits for an item that is not on any list");
                }
            }
        }

        @Test
        @DisplayName("the two invariants are enforced at construction, not merely observed here")
        void constructionRejectsUnsatisfiablePolicies() {
            assertThrows(IllegalArgumentException.class, () ->
                    LootSourceInfo.builder(LootSource.ENDER_DRAGON, "x")
                            .policy(RollPolicy.ON_RARE_BANNER)
                            .markers("y")
                            .samples("xy")
                            .build());
            assertThrows(IllegalArgumentException.class, () ->
                    LootSourceInfo.builder(LootSource.ENDER_DRAGON, "x")
                            .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                            .markers("y")
                            .samples("xy")
                            .build());
        }

        @Test
        @DisplayName("the high-frequency sources are not armed, and the event-shaped ones are")
        void thePacingDecisionsSurvive() {
            // These are the calls that make the feature usable rather than a strobe; a future edit
            // that flips one should have to come through this test and argue for it.
            assertEquals(RollPolicy.NEVER,
                    LootSourceRegistry.defaultPolicy(LootSource.FISHING_SEA_CREATURE));
            assertEquals(RollPolicy.NEVER,
                    LootSourceRegistry.defaultPolicy(LootSource.FISHING_TROPHY_FISH));
            assertEquals(RollPolicy.NEVER,
                    LootSourceRegistry.defaultPolicy(LootSource.MINING_PRISTINE_GEMSTONE));
            assertEquals(RollPolicy.NEVER,
                    LootSourceRegistry.defaultPolicy(LootSource.MINING_COMPACT));
            assertEquals(RollPolicy.NEVER,
                    LootSourceRegistry.defaultPolicy(LootSource.SLAYER_MINIBOSS));

            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.SLAYER_BOSS));
            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.DUNGEON_BOSS));
            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.KUUDRA_COMPLETE));
            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.GLACITE_CORPSE));
            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.FISHING_RARE_SEA_CREATURE));
            assertEquals(RollPolicy.ALWAYS,
                    LootSourceRegistry.defaultPolicy(LootSource.FORAGING_TREE_BONUS_GIFT));
        }

        @Test
        @DisplayName("exactly one of the duplicated dungeon pair is armed, so a run rolls once")
        void dungeonPairDoesNotDoubleFire() {
            boolean bossArmed = LootSourceRegistry.info(LootSource.DUNGEON_BOSS).armedByDefault();
            boolean summaryArmed =
                    LootSourceRegistry.info(LootSource.DUNGEON_RUN_COMPLETE).armedByDefault();
            assertTrue(bossArmed ^ summaryArmed,
                    "DUNGEON_BOSS and DUNGEON_RUN_COMPLETE are the same run seen twice");
        }

        @Test
        @DisplayName("Diana still ships on ALWAYS, gated on the mayor")
        void dianaUnchanged() {
            LootSourceInfo diana = LootSourceRegistry.info(LootSource.DIANA_MYTHOLOGICAL);
            assertEquals(RollPolicy.ALWAYS, diana.defaultPolicy());
            assertTrue(diana.triggers().contains(TriggerKind.ENTITY),
                    "Diana rolls when a creature dies, which is not a chat line");
            assertTrue(diana.gate().isOpen(
                    new GameContext(true, true, "Hub", "Graveyard", "Diana", false, false)));
            assertFalse(diana.gate().isOpen(
                    new GameContext(true, true, "Hub", "Graveyard", "Foxy", false, false)));
            assertFalse(diana.gate().isOpen(
                    new GameContext(true, false, "Hub", "Graveyard", "Diana", false, false)));
        }
    }

    @Nested
    @DisplayName("the pre-filter contract, checked at the data level")
    class MarkerContract {

        @Test
        @DisplayName("every declared marker appears verbatim in at least one of its own samples")
        void markersAreExercised() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                for (String marker : info.chatMarkers()) {
                    boolean seen = info.triggerSamples().stream().anyMatch(s -> s.contains(marker));
                    assertTrue(seen, info.source() + " declares marker \"" + marker
                            + "\" that none of its samples contains -- either the marker is wrong "
                            + "or the sample proving it is missing");
                }
            }
        }

        @Test
        @DisplayName("every sample contains one of its own markers, or the source is markerless on purpose")
        void samplesSurviveTheirOwnFilter() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                if (info.chatMarkers().isEmpty()) {
                    continue; // opted out of filtering; the bus offers it every line
                }
                for (String sample : info.triggerSamples()) {
                    boolean covered = info.chatMarkers().stream().anyMatch(sample::contains);
                    assertTrue(covered, info.source()
                            + " has a sample its own pre-filter would swallow: " + sample);
                }
            }
        }

        @Test
        @DisplayName("a chat-driven source has samples, so nothing ships unproven")
        void chatSourcesCarryEvidence() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                if (info.chatDriven()) {
                    assertFalse(info.triggerSamples().isEmpty(),
                            info.source() + " claims a chat trigger with no captured line to show");
                }
            }
        }

        @Test
        @DisplayName("markerless sources are the two that genuinely share no literal, and are named")
        void markerlessIsDeliberate() {
            Set<LootSource> markerless = EnumSet.noneOf(LootSource.class);
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                if (info.chatDriven() && info.chatMarkers().isEmpty()) {
                    markerless.add(info.source());
                }
            }
            // Opting out is legal and safe, but it forces every chat line through that detector, so
            // the set must stay small and deliberate rather than growing by accident.
            assertEquals(EnumSet.of(LootSource.FISHING_RARE_SEA_CREATURE,
                            LootSource.FISHING_SEA_CREATURE), markerless,
                    "a new markerless source makes the pre-filter a no-op whenever it is open");
        }
    }

    @Nested
    @DisplayName("gates")
    class Gates {

        @Test
        @DisplayName("no gate is open outside SkyBlock")
        void nothingFiresInALobby() {
            GameContext lobby = new GameContext(true, false, "Hub", "", "Diana", false, false);
            GameContext offline = GameContext.UNKNOWN;
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                assertFalse(info.gate().isOpen(lobby), info.source() + " fires outside SkyBlock");
                assertFalse(info.gate().isOpen(offline), info.source() + " fires off Hypixel");
                assertFalse(info.gate().isOpen(null), info.source() + " fires on a null context");
            }
        }

        @Test
        @DisplayName("island gates are shut on other islands and survive formatting codes")
        void islandGatesDiscriminate() {
            SourceGate hollows = LootSourceRegistry.gate(LootSource.POWDER_CHEST);
            assertTrue(hollows.isOpen(GameContext.onIsland("Crystal Hollows")));
            assertTrue(hollows.isOpen(GameContext.onIsland("§7Crystal Hollows ")),
                    "a colour code off the sidebar must not shut a gate");
            assertFalse(hollows.isOpen(GameContext.onIsland("Dwarven Mines")));
            assertFalse(hollows.isOpen(GameContext.onIsland("")),
                    "an unknown island is shut, not open");
        }

        @Test
        @DisplayName("area gates need both halves")
        void areaGatesNeedIslandAndArea() {
            SourceGate mist = LootSourceRegistry.gate(LootSource.GHOST_MIST);
            assertTrue(mist.isOpen(GameContext.onIsland("Dwarven Mines", "The Mist")));
            assertFalse(mist.isOpen(GameContext.onIsland("Dwarven Mines", "Royal Mines")));
            assertFalse(mist.isOpen(GameContext.onIsland("Crystal Hollows", "The Mist")));
        }

        @Test
        @DisplayName("every gate describes itself, because the config screen has to say why a row is grey")
        void gatesAreExplained() {
            for (LootSourceInfo info : LootSourceRegistry.all()) {
                String description = info.gate().describe();
                if (description == null || description.isBlank()) {
                    fail(info.source() + " has an unexplained gate");
                }
            }
        }
    }
}
