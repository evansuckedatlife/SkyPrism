package com.skyprism.core.config;

import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SkyBlock-wide slot machine's configuration: how sources are filed, how a source resolves to
 * an answer, and -- the part that matters most on the day this ships -- what happens to the config
 * file of somebody who is already running the Diana-only build.
 */
@DisplayName("Loot source configuration")
class LootConfigTest {

    /** A v3 file with real choices in it, of the shape the shipped build writes today. */
    private static final String V3_FILE = """
            {
              "configVersion": 3,
              "debugLogging": false,
              "levels": {
                "enabled": true,
                "mode": "BRACKETS",
                "gradientPreset": "custom",
                "chromaEnabled": true,
                "chromaMinLevel": 400,
                "chromaSaturation": 0.75,
                "chromaLightness": 0.5,
                "applyToNameTags": true,
                "minLevel": 10,
                "maxLevel": 900
              },
              "diana": {
                "enabled": %s,
                "triggers": ["MINOS_INQUISITOR", "KING_MINOS", "MANTICORE", "SPHINX"],
                "onlyMyBurrows": false,
                "lootWindowMillis": 4500,
                "reelCount": 4,
                "spinMillis": 1500,
                "jackpotHoldMillis": 3000,
                "jackpotItems": ["Daedalus Stick", "Crown of Greed", "My Own Addition"],
                "suppressDropChatLines": %s,
                "allowedAreas": ["Hub", "Crimson Isle"]
              },
              "hud": { "x": 0.8, "y": 0.2, "scale": 1.5 },
              "sounds": { "volume": 0.35 }
            }
            """;

    private static String v3File(boolean dianaEnabled, boolean suppressing) {
        return V3_FILE.formatted(dianaEnabled, suppressing);
    }

    // ============================================================ filing

    @Nested
    @DisplayName("every source is filed in exactly one drawer")
    class Filing {

        @Test
        @DisplayName("nothing has been left in MISC")
        void nothingUnfiled() {
            List<LootSource> unfiled = LootSourceCategory.sources(LootSourceCategory.MISC);
            assertTrue(unfiled.isEmpty(),
                    "these sources have no category, so the settings screen has nowhere to put "
                            + "them; add them to LootSourceCategory: " + unfiled);
        }

        @Test
        @DisplayName("the drawers together hold every source, once each")
        void everySourceExactlyOnce() {
            var seen = EnumSet.noneOf(LootSource.class);
            int total = 0;
            for (LootSourceCategory category : LootSourceCategory.values()) {
                for (LootSource source : LootSourceCategory.sources(category)) {
                    assertTrue(seen.add(source), source + " appears in more than one category");
                    total++;
                }
            }
            assertEquals(LootSource.values().length, total);
            assertEquals(LootSource.values().length, seen.size());
        }

        @Test
        @DisplayName("Diana is a drawer of one, and it is not offered to the general controls")
        void dianaIsAlone() {
            assertEquals(List.of(LootSource.DIANA_MYTHOLOGICAL),
                    LootSourceCategory.sources(LootSourceCategory.DIANA));
            assertFalse(LootSourceCategory.DIANA.configurable());
            assertEquals(LootSourceCategory.DIANA,
                    LootSourceCategory.of(LootSource.DIANA_MYTHOLOGICAL));
        }

        @Test
        @DisplayName("sources keep their declaration order inside a drawer")
        void declarationOrderIsKept() {
            for (LootSourceCategory category : LootSourceCategory.values()) {
                List<LootSource> sources = LootSourceCategory.sources(category);
                for (int i = 1; i < sources.size(); i++) {
                    assertTrue(sources.get(i - 1).ordinal() < sources.get(i).ordinal(),
                            category + " is out of order at " + sources.get(i));
                }
            }
        }

        @Test
        @DisplayName("lookups tolerate whatever a hand-edited file spells")
        void lookupIsForgiving() {
            assertEquals(Optional.of(LootSourceCategory.GATHERING),
                    LootSourceCategory.byId("gathering"));
            assertEquals(Optional.of(LootSourceCategory.GATHERING),
                    LootSourceCategory.byId("  Gathering  "));
            assertEquals(Optional.empty(), LootSourceCategory.byId("fishing"));
            assertEquals(Optional.empty(), LootSourceCategory.byId(null));
            assertEquals(LootSourceCategory.MISC, LootSourceCategory.of(null));
            assertEquals(List.of(), LootSourceCategory.sources(null));
        }
    }

    // ============================================================ shipped state

    @Nested
    @DisplayName("a fresh install")
    class Defaults {

        @Test
        @DisplayName("stores no opinions at all")
        void storesNothing() {
            var config = SkyPrismConfig.defaults().sanitized();
            assertTrue(config.loot.enabled);
            assertFalse(config.loot.suppressDropChatLines);
            assertTrue(config.loot.disabledCategories.isEmpty());
            assertTrue(config.loot.sources.isEmpty(),
                    "an untouched install must not write sixty entries that restate the code");
        }

        @Test
        @DisplayName("every source reads through to the shipped default policy")
        void policiesComeFromTheRegistry() {
            var config = SkyPrismConfig.defaults().sanitized();
            for (LootSource source : LootSource.values()) {
                if (source == LootSource.DIANA_MYTHOLOGICAL) {
                    continue;
                }
                assertEquals(LootSourceRegistry.defaultPolicy(source), config.lootPolicy(source),
                        source + " does not read through to its shipped policy");
            }
        }

        @Test
        @DisplayName("every source reads through to the shipped jackpot list")
        void jackpotListsComeFromTheRegistry() {
            var config = SkyPrismConfig.defaults().sanitized();
            for (LootSource source : LootSource.values()) {
                if (source == LootSource.DIANA_MYTHOLOGICAL) {
                    continue;
                }
                assertEquals(LootSourceRegistry.info(source).jackpotItems(),
                        config.effectiveSource(source).jackpotItems(), source.toString());
            }
        }

        @Test
        @DisplayName("something is armed, and it is not everything")
        void someButNotAll() {
            var config = SkyPrismConfig.defaults().sanitized();
            int armed = config.loot.armedSourceCount();
            int total = LootSource.values().length - 1;
            assertTrue(armed > 0, "shipping with nothing armed would be a feature that never fires");
            assertTrue(armed < total,
                    "shipping with everything armed would strobe the widget during any grind");
        }
    }

    // ============================================================ resolution

    @Nested
    @DisplayName("resolving one source")
    class Resolution {

        @Test
        @DisplayName("the master, the category and the source must all agree")
        void allThreeMustAgree() {
            var config = SkyPrismConfig.defaults();
            LootSource source = LootSource.SLAYER_BOSS;
            assertTrue(config.lootEnabled(source));

            config.loot.enabled = false;
            assertFalse(config.lootEnabled(source), "the master alone can silence a source");

            config.loot.enabled = true;
            config.loot.setCategoryEnabled(LootSourceCategory.COMBAT, false);
            assertFalse(config.lootEnabled(source), "the category alone can silence a source");

            config.loot.setCategoryEnabled(LootSourceCategory.COMBAT, true);
            config.loot.settingsFor(source).enabled = false;
            assertFalse(config.lootEnabled(source), "the source alone can silence itself");

            config.loot.setCategoryEnabled(LootSourceCategory.COMBAT, false);
            config.loot.setCategoryEnabled(LootSourceCategory.COMBAT, true);
            assertFalse(config.lootEnabled(source),
                    "switching the category back on must not resurrect a source disabled by name");
        }

        @Test
        @DisplayName("an explicit policy wins over the shipped one")
        void explicitPolicyWins() {
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(LootSource.FISHING_SEA_CREATURE).policy = RollPolicy.NEVER;
            assertEquals(RollPolicy.NEVER, config.lootPolicy(LootSource.FISHING_SEA_CREATURE));
            assertEquals(RollPolicy.NEVER,
                    config.sanitized().lootPolicy(LootSource.FISHING_SEA_CREATURE));
        }

        @Test
        @DisplayName("an explicit policy is never quietly rewritten into 'no opinion'")
        void explicitPolicySurvivesSanitising() {
            LootSource source = LootSource.SLAYER_BOSS;
            var config = SkyPrismConfig.defaults();
            // Deliberately the same value the registry already ships, which is the case a
            // "collapse redundant entries" optimisation would eat.
            config.loot.settingsFor(source).policy = LootSourceRegistry.defaultPolicy(source);

            var clean = config.sanitized();
            assertNotNull(clean.loot.sources.get(source),
                    "a choice the player made must survive even when it agrees with the default, "
                            + "or a later release retuning that default moves their setting");
            assertEquals(LootSourceRegistry.defaultPolicy(source),
                    clean.loot.sources.get(source).policy);
        }

        @Test
        @DisplayName("a custom jackpot list replaces the shipped one, and an empty one is a real answer")
        void jackpotOverride() {
            var config = SkyPrismConfig.defaults();
            LootSource source = LootSource.POWDER_CHEST;
            assertFalse(config.effectiveSource(source).jackpotItems().isEmpty());

            var entry = config.loot.settingsFor(source);
            entry.applyJackpotText("Jungle Heart, §6Prehistoric Egg ");
            assertEquals(Set.of("Jungle Heart", "Prehistoric Egg"),
                    config.effectiveSource(source).jackpotItems());
            assertTrue(config.isLootJackpot(source, "§djungle heart"));
            assertFalse(config.isLootJackpot(source, "Gemstone Powder"));

            entry.applyJackpotText(SourceSettings.JACKPOT_NONE);
            assertTrue(config.effectiveSource(source).jackpotItems().isEmpty(),
                    "the lone hyphen means 'nothing from here', not 'I have no opinion'");

            entry.applyJackpotText("   ");
            assertFalse(config.effectiveSource(source).jackpotItems().isEmpty(),
                    "blanking the field means 'forget I said anything'");
        }

        @Test
        @DisplayName("the jackpot field round-trips through its text form")
        void jackpotTextRoundTrips() {
            for (String text : List.of("", "-", "Alpha", "Alpha, Beta", "Alpha, Beta, Gamma")) {
                var entry = new SourceSettings();
                entry.applyJackpotText(text);
                assertEquals(text, entry.jackpotText(), "round trip failed for [" + text + "]");
            }
        }
    }

    // ============================================================ the never-fires trap

    @Nested
    @DisplayName("policies that could never fire are demoted, not stored")
    class ImpossiblePolicies {

        @Test
        @DisplayName("ON_RARE_BANNER on a source Hypixel prints no banner for")
        void rareBannerWithoutABanner() {
            LootSource noBanner = firstWithoutBanner();
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(noBanner).policy = RollPolicy.ON_RARE_BANNER;

            var clean = config.sanitized();
            assertNull(clean.loot.sources.get(noBanner),
                    "a policy that can never be satisfied is a detector that silently never "
                            + "fires, which looks exactly like one that works");
            assertEquals(LootSourceRegistry.defaultPolicy(noBanner), clean.lootPolicy(noBanner));
        }

        @Test
        @DisplayName("ON_JACKPOT_ITEM_ONLY with the list explicitly emptied")
        void jackpotOnlyWithNoItems() {
            LootSource source = LootSource.POWDER_CHEST;
            var config = SkyPrismConfig.defaults();
            var entry = config.loot.settingsFor(source);
            entry.policy = RollPolicy.ON_JACKPOT_ITEM_ONLY;
            entry.applyJackpotText(SourceSettings.JACKPOT_NONE);

            var clean = config.sanitized().loot.sources.get(source);
            assertNotNull(clean, "the emptied list is still an opinion and is kept");
            assertTrue(clean.overrideJackpotItems);
            assertTrue(clean.jackpotItems.isEmpty());
            assertNull(clean.policy, "but the policy that needed that list is dropped");
        }

        @Test
        @DisplayName("ON_JACKPOT_ITEM_ONLY is fine when a list is actually there")
        void jackpotOnlyWithItems() {
            LootSource source = LootSource.LOOT_CHEST;
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(source).policy = RollPolicy.ON_JACKPOT_ITEM_ONLY;
            assertEquals(RollPolicy.ON_JACKPOT_ITEM_ONLY,
                    config.sanitized().lootPolicy(source));
        }

        /** Any source the research found no server rarity flag for; several exist. */
        private static LootSource firstWithoutBanner() {
            for (LootSource source : LootSource.values()) {
                if (source != LootSource.DIANA_MYTHOLOGICAL
                        && !LootSourceRegistry.info(source).emitsRareBanner()) {
                    return source;
                }
            }
            throw new AssertionError("no banner-less source to test the demotion against");
        }
    }

    // ============================================================ Diana

    @Nested
    @DisplayName("Diana answers from its own group and nowhere else")
    class Diana {

        @Test
        @DisplayName("its enablement is diana.enabled, untouched by the general master")
        void masterDoesNotReachDiana() {
            var config = SkyPrismConfig.defaults();
            config.loot.enabled = false;
            assertTrue(config.lootEnabled(LootSource.DIANA_MYTHOLOGICAL),
                    "DianaController reads diana.enabled directly, so this method must agree "
                            + "with it or the two paths disagree about whether Diana is on");

            config.diana.enabled = false;
            assertFalse(config.lootEnabled(LootSource.DIANA_MYTHOLOGICAL));
        }

        @Test
        @DisplayName("its jackpot list is diana.jackpotItems")
        void jackpotComesFromDiana() {
            var config = SkyPrismConfig.defaults();
            config.diana.jackpotItems = new LinkedHashSet<>(List.of("Griffin Feather"));
            assertEquals(Set.of("Griffin Feather"),
                    config.effectiveSource(LootSource.DIANA_MYTHOLOGICAL).jackpotItems());
            assertTrue(config.isLootJackpot(LootSource.DIANA_MYTHOLOGICAL, "§9Griffin Feather"));
        }

        @Test
        @DisplayName("an entry for it in the loot map is dropped rather than becoming a second opinion")
        void noDianaEntryInTheMap() {
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(LootSource.DIANA_MYTHOLOGICAL).enabled = false;
            var clean = config.sanitized();
            assertNull(clean.loot.sources.get(LootSource.DIANA_MYTHOLOGICAL));
            assertTrue(clean.lootEnabled(LootSource.DIANA_MYTHOLOGICAL));
        }

        @Test
        @DisplayName("the Diana category cannot be switched off from the general controls")
        void dianaCategoryIsInert() {
            var config = SkyPrismConfig.defaults();
            config.loot.setCategoryEnabled(LootSourceCategory.DIANA, false);
            assertTrue(config.loot.categoryEnabled(LootSourceCategory.DIANA));
            config.loot.disabledCategories.add(LootSourceCategory.DIANA);
            assertTrue(config.sanitized().loot.disabledCategories.isEmpty(),
                    "a hand-edited file must not be able to disable Diana from here either");
        }
    }

    // ============================================================ the file

    @Nested
    @DisplayName("the file stays sparse and stable")
    class OnDisk {

        @Test
        @DisplayName("an entry the player never actually changed is dropped")
        void blankEntriesArePruned() {
            var config = SkyPrismConfig.defaults();
            // What the settings screen does the moment it builds a control for a source.
            for (LootSource source : LootSourceCategory.sources(LootSourceCategory.GATHERING)) {
                config.loot.settingsFor(source);
            }
            assertFalse(config.loot.sources.isEmpty());
            assertTrue(config.sanitized().loot.sources.isEmpty(),
                    "opening the screen must not add sixty entries to the file");
        }

        @Test
        @DisplayName("entries are written in enum order however they were added")
        void stableOrder() {
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(LootSource.WINTER_GIFT).enabled = false;
            config.loot.settingsFor(LootSource.SLAYER_BOSS).enabled = false;
            config.loot.settingsFor(LootSource.GARDEN_RARE_CROP).enabled = false;

            assertEquals(List.of(LootSource.SLAYER_BOSS, LootSource.WINTER_GIFT,
                            LootSource.GARDEN_RARE_CROP),
                    List.copyOf(config.sanitized().loot.sources.keySet()));
        }

        @Test
        @DisplayName("it survives a JSON round trip with its choices intact")
        void jsonRoundTrip() {
            var config = SkyPrismConfig.defaults();
            config.loot.enabled = true;
            config.loot.suppressDropChatLines = true;
            config.loot.setCategoryEnabled(LootSourceCategory.GATHERING, false);
            config.loot.settingsFor(LootSource.SLAYER_BOSS).policy = RollPolicy.NEVER;
            var powder = config.loot.settingsFor(LootSource.POWDER_CHEST);
            powder.applyJackpotText("Pickonimbus 2000, Jungle Heart");

            String json = ConfigCodec.toJson(config.sanitized());
            SkyPrismConfig back = ConfigCodec.fromJson(json).orElseThrow();

            assertEquals(config.sanitized().loot, back.loot);
            assertTrue(back.loot.suppressDropChatLines);
            assertFalse(back.loot.categoryEnabled(LootSourceCategory.GATHERING));
            assertEquals(RollPolicy.NEVER, back.lootPolicy(LootSource.SLAYER_BOSS));
            assertEquals(Set.of("Pickonimbus 2000", "Jungle Heart"),
                    back.effectiveSource(LootSource.POWDER_CHEST).jackpotItems());
        }

        @Test
        @DisplayName("a policy or category name this build does not know is dropped, not fatal")
        void unknownNamesAreDropped() {
            String json = """
                    {
                      "configVersion": %d,
                      "loot": {
                        "disabledCategories": ["GATHERING", "PLUMBING"],
                        "sources": {
                          "SLAYER_BOSS": { "policy": "ON_TUESDAYS" },
                          "TIME_TRAVEL": { "enabled": false }
                        }
                      }
                    }
                    """.formatted(SkyPrismConfig.CONFIG_VERSION);

            SkyPrismConfig back = ConfigCodec.fromJson(json).orElseThrow();
            assertEquals(Set.of(LootSourceCategory.GATHERING), back.loot.disabledCategories);
            assertEquals(LootSourceRegistry.defaultPolicy(LootSource.SLAYER_BOSS),
                    back.lootPolicy(LootSource.SLAYER_BOSS),
                    "an unreadable opinion is not an opinion");
        }

        @Test
        @DisplayName("copy() is deep, so the screen's cancel button really cancels")
        void copyIsDeep() {
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(LootSource.SLAYER_BOSS).enabled = false;

            SkyPrismConfig snapshot = config.copy();
            config.loot.settingsFor(LootSource.SLAYER_BOSS).enabled = true;
            config.loot.setCategoryEnabled(LootSourceCategory.EVENTS, false);

            assertFalse(snapshot.loot.sources.get(LootSource.SLAYER_BOSS).enabled);
            assertTrue(snapshot.loot.categoryEnabled(LootSourceCategory.EVENTS));
        }

        @Test
        @DisplayName("nulls anywhere in the group are repaired rather than thrown")
        void nullsAreRepaired() {
            var config = SkyPrismConfig.defaults();
            config.loot.disabledCategories = null;
            config.loot.sources = null;
            var clean = config.sanitized();
            assertNotNull(clean.loot.disabledCategories);
            assertNotNull(clean.loot.sources);

            config.loot = null;
            assertNotNull(config.sanitized().loot);
            assertNotNull(config.copy().loot);
        }

        @Test
        @DisplayName("resetting a category forgets every opinion in it")
        void resetCategory() {
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(LootSource.POWDER_CHEST).policy = RollPolicy.ALWAYS;
            config.loot.settingsFor(LootSource.LOOT_CHEST).enabled = false;
            config.loot.settingsFor(LootSource.SLAYER_BOSS).policy = RollPolicy.NEVER;
            config.loot.setCategoryEnabled(LootSourceCategory.CONTAINERS, false);

            config.loot.resetCategory(LootSourceCategory.CONTAINERS);

            assertTrue(config.loot.categoryEnabled(LootSourceCategory.CONTAINERS));
            assertNull(config.loot.sources.get(LootSource.POWDER_CHEST));
            assertNull(config.loot.sources.get(LootSource.LOOT_CHEST));
            assertNotNull(config.loot.sources.get(LootSource.SLAYER_BOSS),
                    "a reset must stay inside its own drawer");
        }
    }

    // ============================================================ the upgrade

    @Nested
    @DisplayName("upgrading the file of somebody already running the Diana build")
    class Upgrade {

        @Test
        @DisplayName("every Diana choice they made is still there afterwards")
        void nothingIsLost() {
            SkyPrismConfig after = ConfigCodec.fromJson(v3File(true, false)).orElseThrow();

            assertEquals(SkyPrismConfig.CONFIG_VERSION, after.configVersion);

            assertTrue(after.diana.enabled);
            assertEquals(EnumSet.of(MythologicalCreature.SPHINX,
                            MythologicalCreature.MINOS_INQUISITOR,
                            MythologicalCreature.KING_MINOS,
                            MythologicalCreature.MANTICORE),
                    EnumSet.copyOf(after.diana.triggers));
            assertFalse(after.diana.onlyMyBurrows);
            assertEquals(4_500L, after.diana.lootWindowMillis);
            assertEquals(4, after.diana.reelCount);
            assertEquals(1_500L, after.diana.spinMillis);
            assertEquals(3_000L, after.diana.jackpotHoldMillis);
            assertTrue(after.diana.jackpotItems.contains("My Own Addition"),
                    "their hand-added jackpot item is their work and must survive the upgrade");
            assertEquals(Set.of("Hub", "Crimson Isle"), after.diana.allowedAreas);

            assertTrue(after.levels.chromaEnabled);
            assertEquals(400, after.levels.chromaMinLevel);
            assertEquals(0.75, after.levels.chromaSaturation, 1e-9);
            assertTrue(after.levels.applyToNameTags);
            assertEquals(10, after.levels.minLevel);
            assertEquals(900, after.levels.maxLevel);

            assertEquals(0.8, after.hud.x, 1e-9);
            assertEquals(1.5, after.hud.scale, 1e-9);
            assertEquals(0.35, after.sounds.volume, 1e-9);
        }

        @Test
        @DisplayName("the Diana path behaves exactly as it did before the upgrade")
        void dianaDoesNotRegress() {
            SkyPrismConfig before = ConfigCodec.fromJson(v3File(true, true)).orElseThrow();
            // The same file, minus the version marker that triggers the ladder: what a build
            // with no loot feature at all would have bound.
            SkyPrismConfig baseline = ConfigCodec.fromJson(
                    v3File(true, true).replace("\"configVersion\": 3",
                            "\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION)).orElseThrow();

            assertEquals(baseline.diana, before.diana,
                    "the migration must not touch the Diana group at all");
            assertEquals(baseline.diana.toRollConfig(), before.diana.toRollConfig());
            assertTrue(before.lootEnabled(LootSource.DIANA_MYTHOLOGICAL));
        }

        @Test
        @DisplayName("somebody who switched the machine off does not get it back on new content")
        void offStaysOff() {
            SkyPrismConfig after = ConfigCodec.fromJson(v3File(false, false)).orElseThrow();

            assertFalse(after.diana.enabled);
            assertFalse(after.loot.enabled,
                    "diana.enabled was the whole feature's off switch in v3; leaving loot.enabled "
                            + "on would hand them back the thing they turned off, on thirty "
                            + "sources they have never seen it on");
            assertEquals(0, after.loot.armedSourceCount());
            for (LootSource source : LootSource.values()) {
                assertFalse(after.lootEnabled(source), source + " is armed after an opt-out");
            }
        }

        @Test
        @DisplayName("somebody who hid the drop lines keeps them hidden on the new sources")
        void hidingIsCarriedAcross() {
            SkyPrismConfig after = ConfigCodec.fromJson(v3File(true, true)).orElseThrow();
            assertTrue(after.diana.suppressDropChatLines);
            assertTrue(after.loot.suppressDropChatLines);
        }

        @Test
        @DisplayName("somebody who left it on gets the shipped defaults, not everything at once")
        void onMeansShippedDefaults() {
            SkyPrismConfig after = ConfigCodec.fromJson(v3File(true, false)).orElseThrow();
            assertTrue(after.loot.enabled);
            assertFalse(after.loot.suppressDropChatLines);
            assertTrue(after.loot.sources.isEmpty(),
                    "an upgrade writes no per-source entries, so a later release can still retune "
                            + "the defaults for people who never expressed an opinion");
            assertEquals(SkyPrismConfig.defaults().sanitized().loot.armedSourceCount(),
                    after.loot.armedSourceCount());
        }

        @Test
        @DisplayName("a v3 file with nothing to carry across migrates silently")
        void quietWhenThereIsNothingToDo() {
            var result = ConfigMigrations.migrate(com.google.gson.JsonParser
                    .parseString("{\"configVersion\": 3, \"hud\": {\"x\": 0.5}}")
                    .getAsJsonObject());
            assertTrue(result.migrated());
            assertTrue(result.notes().isEmpty(), result.notes().toString());
        }

        @Test
        @DisplayName("the note says what it did, so the change is auditable")
        void theChangeIsExplained() {
            var result = ConfigMigrations.migrate(com.google.gson.JsonParser
                    .parseString(v3File(false, true)).getAsJsonObject());
            String note = result.notes().stream()
                    .filter(n -> n.startsWith("v3->v4:"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no v3->v4 note in " + result.notes()));
            assertTrue(note.contains("loot.enabled"), note);
            assertTrue(note.contains("loot.suppressDropChatLines"), note);
        }

        @Test
        @DisplayName("running the ladder twice changes nothing the second time")
        void idempotent() {
            var once = ConfigMigrations.migrate(com.google.gson.JsonParser
                    .parseString(v3File(false, true)).getAsJsonObject());
            String afterOnce = once.root().toString();
            var twice = ConfigMigrations.migrate(once.root());
            assertFalse(twice.migrated());
            assertTrue(twice.notes().isEmpty());
            assertEquals(afterOnce, twice.root().toString());
        }

        @Test
        @DisplayName("a choice the player already made in the new shape is not overwritten")
        void newKeyWins() {
            String json = v3File(false, true)
                    .replace("\"hud\":", "\"loot\": { \"enabled\": true }, \"hud\":");
            SkyPrismConfig after = ConfigCodec.fromJson(json).orElseThrow();
            assertTrue(after.loot.enabled,
                    "a value the player already moved across must not be clobbered");
        }

        @Test
        @DisplayName("a v1 file still climbs the whole ladder, rung by rung")
        void theWholeLadder() {
            String v1 = """
                    {
                      "configVersion": 1,
                      "levels": { "chroma": true, "chromaMinLevel": 300 },
                      "diana": { "enabled": false, "lootWindowTicks": 60 }
                    }
                    """;
            SkyPrismConfig after = ConfigCodec.fromJson(v1).orElseThrow();
            assertTrue(after.levels.chromaEnabled, "v1->v2");
            assertEquals(3_000L, after.diana.lootWindowMillis, "v1->v2 unit change");
            assertEquals(0.90, after.levels.chromaSaturation, 1e-9, "v2->v3");
            assertFalse(after.loot.enabled, "v3->v4");
        }

        @Test
        @DisplayName("a config written by this build reads back identically")
        void currentFilesAreUntouched() {
            var config = SkyPrismConfig.defaults();
            config.loot.settingsFor(LootSource.KUUDRA_COMPLETE).policy = RollPolicy.NEVER;
            config.diana.enabled = false;

            SkyPrismConfig back =
                    ConfigCodec.fromJson(ConfigCodec.toJson(config.sanitized())).orElseThrow();
            assertTrue(back.loot.enabled,
                    "the v3->v4 rung must not run again on a file already at v4 and re-derive "
                            + "loot.enabled from a Diana switch the player has since changed");
            assertEquals(config.sanitized(), back);
        }
    }

    // ============================================================ housekeeping

    @Nested
    @DisplayName("small guarantees the screen leans on")
    class Housekeeping {

        @Test
        @DisplayName("settingsFor returns the same stored entry every time")
        void settingsForIsStable() {
            var settings = new SkyPrismConfig.LootSettings();
            SourceSettings first = settings.settingsFor(LootSource.ARACHNE);
            assertSame(first, settings.settingsFor(LootSource.ARACHNE));
            assertTrue(settings.peek(LootSource.ARACHNE).isPresent());
            assertTrue(settings.peek(null).isEmpty());
        }

        @Test
        @DisplayName("peek does not make the config grow just by being read")
        void peekDoesNotStore() {
            var settings = new SkyPrismConfig.LootSettings();
            settings.peek(LootSource.ARACHNE);
            assertTrue(settings.sources.isEmpty());
        }

        @Test
        @DisplayName("describe names the source and says where the policy came from")
        void describeReadsWell() {
            var entry = new SourceSettings();
            assertTrue(entry.describe(LootSource.SLAYER_BOSS).contains("slayer_boss"));
            assertTrue(entry.describe(LootSource.SLAYER_BOSS).contains("shipped"));
            entry.policy = RollPolicy.NEVER;
            assertTrue(entry.describe(LootSource.SLAYER_BOSS).contains("chosen"));
        }

        @Test
        @DisplayName("ids are legal translation-key segments, so no key can be malformed")
        void idsAreKeySafe() {
            // The screen builds skyprism.common.loot_source.<id> and
            // skyprism.config.loot.category.<id> by concatenation, so an id with a capital or a
            // space would produce a key that silently renders as raw text in game.
            for (LootSource source : LootSource.values()) {
                assertTrue(source.id().matches("[a-z0-9_]+"), source + " -> " + source.id());
            }
            for (LootSourceCategory category : LootSourceCategory.values()) {
                assertTrue(category.id().matches("[a-z0-9_]+"),
                        category + " -> " + category.id());
            }
            for (RollPolicy policy : RollPolicy.values()) {
                assertTrue(policy.name().toLowerCase(java.util.Locale.ROOT).matches("[a-z0-9_]+"),
                        policy.toString());
            }
        }

        @Test
        @DisplayName("a jackpot list is capped so one pathological file cannot blow up the screen")
        void jackpotIsCapped() {
            var entry = new SourceSettings();
            var many = new StringBuilder("Item0");
            for (int i = 1; i < SourceSettings.MAX_JACKPOT_ITEMS + 50; i++) {
                many.append(", Item").append(i);
            }
            entry.applyJackpotText(many.toString());
            assertEquals(SourceSettings.MAX_JACKPOT_ITEMS, entry.jackpotItems.size());
        }
    }
}
