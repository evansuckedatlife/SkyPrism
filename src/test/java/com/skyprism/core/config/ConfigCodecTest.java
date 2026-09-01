package com.skyprism.core.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.config.SkyPrismConfig.DianaSettings;
import com.skyprism.core.config.SkyPrismConfig.HudSettings;
import com.skyprism.core.config.SkyPrismConfig.LevelSettings;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.level.GradientRamp;
import com.skyprism.core.level.LevelColorMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Filesystem tests for {@link ConfigCodec}, run against a real temporary directory
 * because the behaviour under test is filesystem behaviour: renaming a broken file
 * aside, replacing it atomically, creating a missing parent.
 *
 * <p>The damaged files here are transcriptions of the ways a config actually breaks --
 * truncated by a crash mid-write, emptied by a full disk, hand-edited into the wrong
 * type -- rather than random bytes.
 */
class ConfigCodecTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("skyprism.json");
    }

    private void write(String json) throws IOException {
        Files.writeString(file(), json, StandardCharsets.UTF_8);
    }

    /** A config with something changed in every group, so a round trip has work to do. */
    private static SkyPrismConfig customised() {
        var c = SkyPrismConfig.defaults();
        c.debugLogging = true;
        // GRADIENT rather than the shipped BRACKETS on purpose: this fixture exists to
        // differ from the defaults in every group, and a field set to the default value is
        // a field the round trip is no longer testing. It moved here when brackets became
        // the shipped mode.
        c.levels.mode = LevelColorMode.GRADIENT;
        c.levels.gradientPreset = LevelSettings.CUSTOM_PRESET;
        c.levels.customStops = new ArrayList<>(List.of(
                new GradientRamp.Stop(0, 0x102030),
                new GradientRamp.Stop(275, 0xA0B0C0),
                new GradientRamp.Stop(600, 0xFFEEDD)));
        c.levels.chromaEnabled = true;
        c.levels.chromaMinLevel = 333;
        c.levels.chromaCyclesPerSecond = 0.75;
        c.levels.applyToNameTags = false;
        c.levels.recolourBrackets = false;
        c.levels.minLevel = 10;
        c.levels.maxLevel = 750;
        c.diana.triggers = new LinkedHashSet<>(List.of(MythologicalCreature.SPHINX));
        c.diana.reelCount = 5;
        c.diana.lootWindowMillis = 4_500;
        c.diana.jackpotItems = new LinkedHashSet<>(List.of("Daedalus Stick"));
        c.diana.suppressDropChatLines = true;
        c.hud.anchor = HudAnchor.BOTTOM_RIGHT;
        c.hud.x = 0.97;
        c.hud.y = 0.9;
        c.hud.scale = 1.5;
        c.sounds.volume = 0.25;
        c.sounds.reelTicks = false;
        return c;
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("everything written comes back identical")
        void savedSettingsSurvive() throws IOException {
            var original = customised();
            ConfigCodec.save(file(), original);

            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, result.status());
            assertEquals(original.sanitized(), result.config());
            assertTrue(result.preservedAs().isEmpty());
        }

        @Test
        @DisplayName("records inside the config survive as records, not as empty objects")
        void gradientStopsSurvive() throws IOException {
            var original = customised();
            ConfigCodec.save(file(), original);
            var loaded = ConfigCodec.loadOrDefaults(file());
            assertEquals(List.of(0, 275, 600),
                    loaded.levels.customStops.stream().map(GradientRamp.Stop::level).toList());
            assertEquals(0xA0B0C0, loaded.levels.customStops.get(1).rgb());
        }

        @Test
        @DisplayName("saving the same config twice produces byte-identical files")
        void outputIsStable() throws IOException {
            ConfigCodec.save(file(), customised());
            String first = Files.readString(file(), StandardCharsets.UTF_8);
            ConfigCodec.save(file(), customised());
            String second = Files.readString(file(), StandardCharsets.UTF_8);
            assertEquals(first, second, "an unchanged config must not churn the file");
        }

        @Test
        @DisplayName("the output is pretty-printed and ends with a newline")
        void outputIsPretty() {
            String json = ConfigCodec.toJson(SkyPrismConfig.defaults());
            assertTrue(json.contains("\n  \"levels\""), "top-level keys are indented");
            assertTrue(json.endsWith(System.lineSeparator()));
            assertFalse(json.contains("\\u003d"), "HTML escaping stays off");
        }

        @Test
        @DisplayName("the atomic write leaves no temporary file behind")
        void noTempFileSurvives() throws IOException {
            ConfigCodec.save(file(), SkyPrismConfig.defaults());
            try (var entries = Files.list(dir)) {
                assertEquals(List.of("skyprism.json"),
                        entries.map(p -> p.getFileName().toString()).sorted().toList());
            }
        }

        @Test
        @DisplayName("saving creates the directories the config lives in")
        void saveCreatesParents() throws IOException {
            Path nested = dir.resolve("config").resolve("skyprism").resolve("settings.json");
            ConfigCodec.save(nested, SkyPrismConfig.defaults());
            assertTrue(Files.isRegularFile(nested));
        }

        @Test
        @DisplayName("save rejects a null path or config, because a failed save must be visible")
        void saveRejectsNulls() {
            assertThrows(NullPointerException.class,
                    () -> ConfigCodec.save(null, SkyPrismConfig.defaults()));
            assertThrows(NullPointerException.class, () -> ConfigCodec.save(file(), null));
        }
    }

    @Nested
    @DisplayName("missing file")
    class Missing {

        @Test
        @DisplayName("defaults are returned and written out so the file exists to be edited")
        void missingFileIsCreated() throws IOException {
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.CREATED, result.status());
            assertEquals(SkyPrismConfig.defaults(), result.config());
            assertTrue(Files.isRegularFile(file()), "the defaults must be on disk afterwards");
            assertEquals(SkyPrismConfig.defaults(), ConfigCodec.loadOrDefaults(file()));
        }

        @Test
        @DisplayName("a missing parent directory is created rather than reported")
        void missingDirectoryIsCreated() {
            Path deep = dir.resolve("a").resolve("b").resolve("skyprism.json");
            var result = ConfigCodec.load(deep);
            assertEquals(ConfigCodec.Status.CREATED, result.status());
            assertTrue(Files.isRegularFile(deep));
        }

        @Test
        @DisplayName("a directory where a file belongs does not throw")
        void directoryInsteadOfFile() {
            var result = assertDoesNotThrow(() -> ConfigCodec.load(dir));
            assertNotNull(result.config());
            assertEquals(SkyPrismConfig.defaults(), result.config());
        }

        @Test
        @DisplayName("a null path yields defaults rather than an exception")
        void nullPath() {
            var result = assertDoesNotThrow(() -> ConfigCodec.load(null));
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertEquals(SkyPrismConfig.defaults(), result.config());
        }
    }

    @Nested
    @DisplayName("corrupt files are recovered, never destroyed")
    class Corrupt {

        @Test
        @DisplayName("an empty file is preserved aside and replaced with defaults")
        void emptyFile() throws IOException {
            write("");
            var result = ConfigCodec.load(file());

            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertTrue(result.recovered());
            assertEquals(SkyPrismConfig.defaults(), result.config());

            Path aside = result.preservedAs().orElseThrow();
            assertEquals("skyprism.json.corrupt", aside.getFileName().toString());
            assertEquals("", Files.readString(aside, StandardCharsets.UTF_8));
            assertEquals(SkyPrismConfig.defaults(), ConfigCodec.loadOrDefaults(file()));
        }

        @Test
        @DisplayName("a file of only whitespace is treated the same way")
        void whitespaceOnlyFile() throws IOException {
            write("   \n\t\n");
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertTrue(result.preservedAs().isPresent());
        }

        @Test
        @DisplayName("JSON truncated mid-write keeps every byte it had, in the preserved copy")
        void truncatedJson() throws IOException {
            String broken = "{\n  \"configVersion\": 2,\n  \"levels\": {\n    \"enabled\": tru";
            write(broken);

            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            Path aside = result.preservedAs().orElseThrow();
            assertEquals(broken, Files.readString(aside, StandardCharsets.UTF_8),
                    "the user's damaged file must be preserved verbatim");
            assertNotEquals(broken, Files.readString(file(), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("a JSON array where an object belongs is refused rather than half-read")
        void topLevelArray() throws IOException {
            write("[1, 2, 3]");
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertTrue(result.preservedAs().isPresent());
        }

        @Test
        @DisplayName("a word where a number belongs is recovered from, with the file kept")
        void wrongTypeString() throws IOException {
            String broken = "{\"configVersion\": 2, \"levels\": {\"chromaMinLevel\": \"quite high\"}}";
            write(broken);

            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertEquals(SkyPrismConfig.defaults(), result.config());
            assertEquals(broken, Files.readString(result.preservedAs().orElseThrow(),
                    StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("an object where a group's number belongs is recovered from")
        void wrongTypeObject() throws IOException {
            write("{\"diana\": {\"reelCount\": {\"value\": 3}}}");
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertTrue(result.preservedAs().isPresent());
        }

        @Test
        @DisplayName("a whole group written as a number is recovered from")
        void wrongTypeGroup() throws IOException {
            write("{\"levels\": 42}");
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertTrue(result.preservedAs().isPresent());
        }

        @Test
        @DisplayName("the note names the file the wreckage went to, so it can be told to the player")
        void recoveryIsExplained() throws IOException {
            write("{oh no");
            var result = ConfigCodec.load(file());
            Path aside = result.preservedAs().orElseThrow();
            assertTrue(result.notes().stream().anyMatch(n -> n.contains(aside.toString())),
                    "notes were " + result.notes());
        }

        @Test
        @DisplayName("a second corruption does not overwrite the first rescue")
        void rescuesAreNumbered() throws IOException {
            write("{first");
            Path firstAside = ConfigCodec.load(file()).preservedAs().orElseThrow();

            write("{second");
            Path secondAside = ConfigCodec.load(file()).preservedAs().orElseThrow();

            assertNotEquals(firstAside, secondAside);
            assertEquals("skyprism.json.corrupt", firstAside.getFileName().toString());
            assertEquals("skyprism.json.corrupt-1", secondAside.getFileName().toString());
            assertEquals("{first", Files.readString(firstAside, StandardCharsets.UTF_8),
                    "the oldest and most complete rescue is the one that must survive");
            assertEquals("{second", Files.readString(secondAside, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("preserveAside declines quietly when there is nothing to preserve")
        void preserveAsideOnNothing() {
            assertTrue(ConfigCodec.preserveAside(null).isEmpty());
            assertTrue(ConfigCodec.preserveAside(dir.resolve("never-existed.json")).isEmpty());
            assertTrue(ConfigCodec.preserveAside(dir).isEmpty(), "a directory is not a config file");
        }
    }

    @Nested
    @DisplayName("tolerated damage: the file is kept and the settings repaired")
    class Tolerated {

        @Test
        @DisplayName("unknown fields are ignored, not fatal")
        void unknownFieldsAreIgnored() throws IOException {
            write("""
                  {
                    "configVersion": %d,
                    "somethingFromTheFuture": {"nested": [1, 2, 3]},
                    "levels": {"chromaMinLevel": 250, "aFieldThatNeverExisted": "hello"},
                    "diana": {"reelCount": 4}
                  }
                  """.formatted(SkyPrismConfig.CONFIG_VERSION));
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, result.status());
            assertEquals(250, result.config().levels.chromaMinLevel);
            assertEquals(4, result.config().diana.reelCount);
            assertTrue(result.preservedAs().isEmpty(), "an unknown field is not damage");
        }

        @Test
        @DisplayName("a group written as null falls back to that group's defaults")
        void nullGroups() throws IOException {
            write("{\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION
                    + ", \"levels\": null, \"diana\": null, \"hud\": null, \"sounds\": null}");
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, result.status());
            assertEquals(SkyPrismConfig.defaults(), result.config());
        }

        @Test
        @DisplayName("an unrecognised enum name is dropped rather than failing the load")
        void unknownEnumNames() throws IOException {
            write("""
                  {
                    "configVersion": %d,
                    "levels": {"mode": "PLAID"},
                    "hud": {"anchor": "SOMEWHERE_ELSE"},
                    "diana": {"triggers": ["MANTICORE", "CHUPACABRA", "MINOTAUR"]}
                  }
                  """.formatted(SkyPrismConfig.CONFIG_VERSION));
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, result.status());
            assertEquals(LevelColorMode.BRACKETS, result.config().levels.mode);
            assertEquals(HudAnchor.TOP_CENTER, result.config().hud.anchor);
            assertEquals(List.of(MythologicalCreature.MINOTAUR, MythologicalCreature.MANTICORE),
                    List.copyOf(result.config().diana.triggers));
        }

        @Test
        @DisplayName("out-of-range numbers are clamped on the way in")
        void outOfRangeNumbersAreClamped() throws IOException {
            write("""
                  {
                    "configVersion": %d,
                    "levels": {"minLevel": 900, "maxLevel": 3, "chromaUpdateHz": 100000},
                    "diana": {"reelCount": 77, "lootWindowMillis": -5},
                    "hud": {"x": 12.0, "scale": 0.0001},
                    "sounds": {"volume": 4.0}
                  }
                  """.formatted(SkyPrismConfig.CONFIG_VERSION));
            var c = ConfigCodec.loadOrDefaults(file());
            assertEquals(3, c.levels.minLevel);
            assertEquals(900, c.levels.maxLevel);
            assertEquals(LevelSettings.MAX_CHROMA_HZ, c.levels.chromaUpdateHz);
            assertEquals(5, c.diana.reelCount);
            assertEquals(DianaSettings.MIN_LOOT_WINDOW_MILLIS, c.diana.lootWindowMillis);
            assertEquals(1.0, c.hud.x);
            assertEquals(HudSettings.MIN_SCALE, c.hud.scale);
            assertEquals(1.0, c.sounds.volume);
        }

        @Test
        @DisplayName("a number spelled as a string is coerced, matching Gson's own leniency")
        void numericStringsAreCoerced() throws IOException {
            write("{\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION
                    + ", \"levels\": {\"chromaMinLevel\": \"480\"}}");
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, result.status(),
                    "a value that reads as a number is not corruption");
            assertEquals(480, result.config().levels.chromaMinLevel);
        }

        @Test
        @DisplayName("an empty JSON object is a valid config: every default, nothing lost")
        void emptyObjectIsValid() throws IOException {
            write("{}");
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, result.status());
            assertEquals(SkyPrismConfig.defaults(), result.config());
            assertTrue(result.preservedAs().isEmpty());
        }
    }

    @Nested
    @DisplayName("fromJson")
    class FromText {

        @Test
        @DisplayName("valid text binds and is sanitized on the way out")
        void validText() {
            var c = ConfigCodec.fromJson("{\"diana\": {\"reelCount\": 900}}").orElseThrow();
            assertEquals(5, c.diana.reelCount);
        }

        @Test
        @DisplayName("nothing usable comes back empty rather than throwing")
        void invalidText() {
            assertTrue(ConfigCodec.fromJson(null).isEmpty());
            assertTrue(ConfigCodec.fromJson("").isEmpty());
            assertTrue(ConfigCodec.fromJson("not json at all {").isEmpty());
            assertTrue(ConfigCodec.fromJson("[]").isEmpty());
            assertTrue(ConfigCodec.fromJson("{\"levels\": {\"minLevel\": \"nope\"}}").isEmpty());
        }

        @Test
        @DisplayName("text produced by toJson binds back to the same settings")
        void textRoundTrip() {
            var original = customised().sanitized();
            assertEquals(original, ConfigCodec.fromJson(ConfigCodec.toJson(original)).orElseThrow());
        }
    }
}
