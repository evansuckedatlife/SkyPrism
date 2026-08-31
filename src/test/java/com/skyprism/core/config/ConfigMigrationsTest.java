package com.skyprism.core.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the version ladder.
 *
 * <p>These matter more than their size suggests. The v1-to-v2 unit change is the kind of
 * bug that does not announce itself: bound without migration the loot window would be
 * fifty times too short and the slot machine would simply appear to be broken, with a
 * perfectly valid-looking config file to prove it was not.
 */
class ConfigMigrationsTest {

    @TempDir
    Path dir;

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Nested
    @DisplayName("reading the declared version")
    class Version {

        @Test
        @DisplayName("a number is read as written")
        void numericVersion() {
            assertEquals(1, ConfigMigrations.readVersion(parse("{\"configVersion\": 1}")));
            assertEquals(7, ConfigMigrations.readVersion(parse("{\"configVersion\": 7}")));
        }

        @Test
        @DisplayName("a version spelled as a string still counts")
        void stringVersion() {
            assertEquals(1, ConfigMigrations.readVersion(parse("{\"configVersion\": \" 1 \"}")));
        }

        @Test
        @DisplayName("a missing version means current, because every file we write has one")
        void missingVersion() {
            assertEquals(SkyPrismConfig.CONFIG_VERSION, ConfigMigrations.readVersion(parse("{}")));
            assertEquals(SkyPrismConfig.CONFIG_VERSION, ConfigMigrations.readVersion(null));
        }

        @Test
        @DisplayName("an unreadable version falls back instead of throwing")
        void unreadableVersion() {
            assertEquals(SkyPrismConfig.CONFIG_VERSION,
                    ConfigMigrations.readVersion(parse("{\"configVersion\": \"old\"}")));
            assertEquals(SkyPrismConfig.CONFIG_VERSION,
                    ConfigMigrations.readVersion(parse("{\"configVersion\": {\"major\": 1}}")));
            assertEquals(SkyPrismConfig.CONFIG_VERSION,
                    ConfigMigrations.readVersion(parse("{\"configVersion\": null}")));
        }
    }

    @Nested
    @DisplayName("v1 to v2")
    class V1ToV2 {

        @Test
        @DisplayName("the renamed shimmer toggle keeps its value instead of being silently dropped")
        void chromaIsRenamed() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"levels\": {\"chroma\": true, \"chromaMinLevel\": 250}}"));

            assertTrue(result.migrated());
            JsonObject levels = result.root().getAsJsonObject("levels");
            assertFalse(levels.has("chroma"), "the old key is gone");
            assertTrue(levels.get("chromaEnabled").getAsBoolean(), "the value moved across");
            assertEquals(250, levels.get("chromaMinLevel").getAsInt(), "neighbours are untouched");
            assertEquals(SkyPrismConfig.CONFIG_VERSION,
                    result.root().get("configVersion").getAsInt());
        }

        @Test
        @DisplayName("the loot window is converted from ticks, not merely copied")
        void lootWindowUnitChanges() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"diana\": {\"lootWindowTicks\": 60}}"));

            JsonObject diana = result.root().getAsJsonObject("diana");
            assertFalse(diana.has("lootWindowTicks"));
            assertEquals(3_000L, diana.get("lootWindowMillis").getAsLong(),
                    "60 ticks is three seconds, not sixty milliseconds");
        }

        @Test
        @DisplayName("the note says what it did, so the change is auditable")
        void migrationIsExplained() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"levels\": {\"chroma\": false},"
                            + " \"diana\": {\"lootWindowTicks\": 40}}"));
            // One note per rung of the ladder that actually changed something; this file
            // climbs every rung, so the v1->v2 note is the first of them.
            String note = result.notes().get(0);
            assertTrue(note.startsWith("v1->v2:"), note);
            assertTrue(note.contains("chromaEnabled"), note);
            assertTrue(note.contains("lootWindowMillis"), note);
        }

        @Test
        @DisplayName("a v1 file that never used the old keys migrates silently")
        void nothingToDo() {
            var result = ConfigMigrations.migrate(parse("{\"configVersion\": 1, \"hud\": {\"x\": 0.5}}"));
            assertTrue(result.migrated(), "the version still moves");
            assertTrue(result.notes().isEmpty(), "but nothing is reported that did not happen");
            assertEquals(SkyPrismConfig.CONFIG_VERSION, result.root().get("configVersion").getAsInt());
        }

        @Test
        @DisplayName("a half-migrated file is left alone rather than clobbered")
        void newKeyWins() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"levels\": {\"chroma\": true, \"chromaEnabled\": false}}"));
            JsonObject levels = result.root().getAsJsonObject("levels");
            assertFalse(levels.get("chromaEnabled").getAsBoolean(),
                    "a value the user already moved across must not be overwritten");
        }

        @Test
        @DisplayName("an unreadable old value is dropped rather than migrated into nonsense")
        void unreadableOldValue() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"diana\": {\"lootWindowTicks\": \"lots\"}}"));
            JsonObject diana = result.root().getAsJsonObject("diana");
            assertFalse(diana.has("lootWindowTicks"));
            assertFalse(diana.has("lootWindowMillis"), "no invented value; the default applies");
        }

        @Test
        @DisplayName("running the ladder twice is a no-op the second time")
        void migrationIsIdempotent() {
            var once = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"levels\": {\"chroma\": true},"
                            + " \"diana\": {\"lootWindowTicks\": 60}}"));
            String afterOnce = once.root().toString();

            var twice = ConfigMigrations.migrate(once.root());
            assertFalse(twice.migrated());
            assertTrue(twice.notes().isEmpty());
            assertEquals(afterOnce, twice.root().toString());
        }
    }

    @Nested
    @DisplayName("the edges of the ladder")
    class Edges {

        @Test
        @DisplayName("a current file is passed through untouched")
        void currentVersionIsUntouched() {
            String json = "{\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION
                    + ", \"levels\": {\"chroma\": true}}";
            var result = ConfigMigrations.migrate(parse(json));
            assertFalse(result.migrated());
            assertTrue(result.notes().isEmpty());
            assertTrue(result.root().getAsJsonObject("levels").has("chroma"),
                    "a key that only means something in v1 is left where it is at v2");
        }

        @Test
        @DisplayName("a file from a newer build is flagged and left exactly as it is")
        void newerVersionIsLeftAlone() {
            int future = SkyPrismConfig.CONFIG_VERSION + 5;
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": " + future + ", \"levels\": {\"chroma\": true}}"));

            assertTrue(result.fromFuture());
            assertFalse(result.migrated(), "a schema this build has never seen is not downgraded");
            assertEquals(future, result.root().get("configVersion").getAsInt());
            assertTrue(result.root().getAsJsonObject("levels").has("chroma"));
            assertFalse(result.notes().isEmpty(), "but the caller is told");
        }

        @Test
        @DisplayName("a version below the oldest supported still walks the whole ladder")
        void absurdlyOldVersion() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": -40, \"levels\": {\"chroma\": true}}"));
            assertEquals(SkyPrismConfig.CONFIG_VERSION, result.toVersion());
            assertTrue(result.root().getAsJsonObject("levels").has("chromaEnabled"));
        }

        @Test
        @DisplayName("migrate never throws, whatever shape it is handed")
        void neverThrows() {
            assertDoesNotThrow(() -> ConfigMigrations.migrate(null));
            assertDoesNotThrow(() -> ConfigMigrations.migrate(new JsonObject()));
            assertDoesNotThrow(() -> ConfigMigrations.migrate(parse("{\"levels\": 5, \"diana\": []}")));
            assertDoesNotThrow(() -> ConfigMigrations.migrate(
                    parse("{\"configVersion\": 1, \"levels\": \"not an object\"}")));
        }

        @Test
        @DisplayName("a group of the wrong shape is stepped over, not migrated into")
        void wrongShapedGroup() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"levels\": [1, 2], \"diana\": 9}"));
            assertTrue(result.notes().isEmpty());
            assertEquals(SkyPrismConfig.CONFIG_VERSION, result.root().get("configVersion").getAsInt());
        }
    }

    @Nested
    @DisplayName("through the codec, end to end")
    class ThroughTheCodec {

        private Path file() {
            return dir.resolve("skyprism.json");
        }

        @Test
        @DisplayName("a v1 file on disk loads as MIGRATED and is rewritten at the current version")
        void v1FileIsUpgradedOnDisk() throws IOException {
            Files.writeString(file(), """
                    {
                      "configVersion": 1,
                      "levels": {"chroma": true, "chromaMinLevel": 300},
                      "diana": {"lootWindowTicks": 60, "reelCount": 4}
                    }
                    """, StandardCharsets.UTF_8);

            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.MIGRATED, result.status());
            assertTrue(result.config().levels.chromaEnabled);
            assertEquals(300, result.config().levels.chromaMinLevel);
            assertEquals(3_000L, result.config().diana.lootWindowMillis);
            assertEquals(4, result.config().diana.reelCount);
            assertTrue(result.preservedAs().isEmpty(), "an old file is not a broken file");

            String rewritten = Files.readString(file(), StandardCharsets.UTF_8);
            assertTrue(rewritten.contains("\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION));
            assertTrue(rewritten.contains("\"chromaEnabled\""));
            assertFalse(rewritten.contains("\"lootWindowTicks\""));

            var second = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, second.status(),
                    "the upgrade is durable; the ladder does not run again");
            assertEquals(result.config(), second.config());
        }

        @Test
        @DisplayName("a tick count so large it becomes nonsense is migrated, then clamped")
        void migrationAndClampCompose() throws IOException {
            Files.writeString(file(),
                    "{\"configVersion\": 1, \"diana\": {\"lootWindowTicks\": 100000}}",
                    StandardCharsets.UTF_8);
            var c = ConfigCodec.loadOrDefaults(file());
            assertEquals(SkyPrismConfig.DianaSettings.MAX_LOOT_WINDOW_MILLIS, c.diana.lootWindowMillis);
        }

        @Test
        @DisplayName("a file from a newer build is read as best it can be and not rewritten")
        void newerFileIsNotRewritten() throws IOException {
            String json = "{\"configVersion\": " + (SkyPrismConfig.CONFIG_VERSION + 1)
                    + ", \"diana\": {\"reelCount\": 2}, \"aFutureGroup\": {\"x\": 1}}";
            Files.writeString(file(), json, StandardCharsets.UTF_8);

            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.FROM_NEWER_VERSION, result.status());
            assertEquals(2, result.config().diana.reelCount, "what this build understands still applies");
            assertEquals(json, Files.readString(file(), StandardCharsets.UTF_8),
                    "settings this build cannot see must not be written away");
        }
    }

    @Nested
    @DisplayName("v2 to v3")
    class V2ToV3 {

        @Test
        @DisplayName("the formerly hard-coded shimmer colour is written into the file")
        void shimmerColourIsPinned() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 2, \"levels\": {\"chromaEnabled\": true}}"));

            assertTrue(result.migrated());
            JsonObject levels = result.root().getAsJsonObject("levels");
            assertEquals(0.90, levels.get("chromaSaturation").getAsDouble());
            assertEquals(0.62, levels.get("chromaLightness").getAsDouble());
            assertTrue(levels.get("chromaEnabled").getAsBoolean(), "neighbours are untouched");
        }

        @Test
        @DisplayName("the pinned values are what v2 rendered with, whatever the defaults become")
        void pinnedValuesAreHistoryNotDefaults() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 2, \"levels\": {\"chromaEnabled\": true}}"));
            JsonObject levels = result.root().getAsJsonObject("levels");

            // Stated as literals on purpose: this assertion must fail if someone retunes
            // the shipped default and "helpfully" points the migration at it, because that
            // would repaint every upgrading player's shimmer.
            assertEquals(0.90, levels.get("chromaSaturation").getAsDouble());
            assertEquals(0.62, levels.get("chromaLightness").getAsDouble());
        }

        @Test
        @DisplayName("a value the player already wrote is not overwritten")
        void handEditedValuesWin() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 2, \"levels\": {\"chromaSaturation\": 0.2}}"));
            JsonObject levels = result.root().getAsJsonObject("levels");
            assertEquals(0.2, levels.get("chromaSaturation").getAsDouble(),
                    "the player's own number survives");
            assertEquals(0.62, levels.get("chromaLightness").getAsDouble(),
                    "the one they did not write is still filled in");
        }

        @Test
        @DisplayName("a file with no levels group is left alone, since it chose no appearance")
        void noLevelsGroupIsNoOp() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 2, \"hud\": {\"x\": 0.5}}"));
            assertTrue(result.migrated(), "the version still moves");
            assertTrue(result.notes().isEmpty(), "but nothing is reported that did not happen");
            assertFalse(result.root().has("levels"), "no group is invented to hold two numbers");
        }

        @Test
        @DisplayName("a v1 file climbs both rungs: the rename and then the pin")
        void ladderRunsEndToEnd() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"levels\": {\"chroma\": true},"
                            + " \"diana\": {\"lootWindowTicks\": 60}}"));

            assertEquals(2, result.notes().size(), "notes were " + result.notes());
            assertTrue(result.notes().get(0).startsWith("v1->v2:"));
            assertTrue(result.notes().get(1).startsWith("v2->v3:"));

            JsonObject levels = result.root().getAsJsonObject("levels");
            assertTrue(levels.get("chromaEnabled").getAsBoolean());
            assertEquals(0.90, levels.get("chromaSaturation").getAsDouble());
            assertEquals(3_000L, result.root().getAsJsonObject("diana")
                    .get("lootWindowMillis").getAsLong());
        }

        @Test
        @DisplayName("running the v2 step twice changes nothing the second time")
        void isIdempotent() {
            var once = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 2, \"levels\": {\"chromaEnabled\": true}}"));
            String afterOnce = once.root().toString();

            var twice = ConfigMigrations.migrate(once.root());
            assertFalse(twice.migrated());
            assertTrue(twice.notes().isEmpty());
            assertEquals(afterOnce, twice.root().toString());
        }

        @Test
        @DisplayName("a v2 file on disk is upgraded, saved, and read back as current")
        void v2FileIsUpgradedOnDisk() throws IOException {
            Path file = dir.resolve("v2.json");
            Files.writeString(file,
                    "{\"configVersion\": 2, \"levels\": {\"chromaEnabled\": true,"
                            + " \"chromaMinLevel\": 300}}",
                    StandardCharsets.UTF_8);

            var result = ConfigCodec.load(file);
            assertEquals(ConfigCodec.Status.MIGRATED, result.status());
            assertEquals(0.90, result.config().levels.chromaSaturation);
            assertEquals(0.62, result.config().levels.chromaLightness);
            assertEquals(300, result.config().levels.chromaMinLevel);

            String rewritten = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(rewritten.contains("\"chromaSaturation\""), rewritten);
            assertTrue(rewritten.contains("\"chromaLightness\""), rewritten);

            var second = ConfigCodec.load(file);
            assertEquals(ConfigCodec.Status.LOADED, second.status(), "the upgrade is durable");
            assertEquals(result.config(), second.config());
        }

        @Test
        @DisplayName("a current file that simply lacks the fields gets the shipped defaults")
        void currentFileWithoutTheFieldsUsesDefaults() throws IOException {
            Path file = dir.resolve("current.json");
            Files.writeString(file, "{\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION
                    + ", \"levels\": {\"chromaEnabled\": true}}", StandardCharsets.UTF_8);

            var result = ConfigCodec.load(file);
            assertEquals(ConfigCodec.Status.LOADED, result.status(),
                    "a file at this version is not migrated, so nothing is pinned into it");
            assertEquals(SkyPrismConfig.LevelSettings.DEFAULT_CHROMA_SATURATION,
                    result.config().levels.chromaSaturation);
            assertEquals(SkyPrismConfig.LevelSettings.DEFAULT_CHROMA_LIGHTNESS,
                    result.config().levels.chromaLightness);
        }

        @Test
        @DisplayName("a nonsense value in the file is clamped on the way in, not on the way through")
        void outOfRangeValuesAreClampedByTheSanitiser() throws IOException {
            Path file = dir.resolve("silly.json");
            Files.writeString(file, "{\"configVersion\": 2, \"levels\":"
                    + " {\"chromaSaturation\": 7.5, \"chromaLightness\": -3.0}}",
                    StandardCharsets.UTF_8);

            var c = ConfigCodec.loadOrDefaults(file);
            assertEquals(SkyPrismConfig.LevelSettings.MAX_CHROMA_SATURATION, c.levels.chromaSaturation);
            assertEquals(SkyPrismConfig.LevelSettings.MIN_CHROMA_LIGHTNESS, c.levels.chromaLightness);
        }
    }

}
