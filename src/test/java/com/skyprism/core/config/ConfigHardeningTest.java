package com.skyprism.core.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyprism.core.config.SkyPrismConfig.DianaSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The adversarial half of the config suite: inputs a player's machine really can produce
 * but which the happy-path tests never reach.
 *
 * <p>Each test here was written against an implementation that failed it. They are kept
 * separate from {@link ConfigCodecTest} and {@link SkyPrismConfigTest} only so that what
 * they cost to keep -- a real thread race, a directory used as a write target -- is
 * obvious to whoever reads them next.
 */
class ConfigHardeningTest {

    /** Written as an escape so the file's own encoding can never change what is tested. */
    private static final char S = '§';

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("skyprism.json");
    }

    @Nested
    @DisplayName("a failed write must not leave debris")
    class FailedWrites {

        /**
         * A write that cannot complete used to leave its temporary sibling behind for ever.
         * The realistic trigger is a config path that is occupied by a directory -- a
         * mistyped path in a launcher profile, or a mod that made a folder where this one
         * wants a file -- and the debris then sits next to the player's config directory
         * with no explanation and no owner.
         */
        @Test
        @DisplayName("a save that cannot be renamed into place cleans up after itself")
        void failedSaveLeavesNoTemporaryFile() throws IOException {
            Path occupied = dir.resolve("occupied");
            Files.createDirectory(occupied);
            Files.writeString(occupied.resolve("something"), "x", StandardCharsets.UTF_8);

            assertThrows(IOException.class, () -> ConfigCodec.save(occupied, SkyPrismConfig.defaults()),
                    "writing onto a directory has to be reported");
            assertNoTemporaryFiles();
        }

        @Test
        @DisplayName("a load that tries and fails to create a config cleans up too")
        void failedCreateLeavesNoTemporaryFile() throws IOException {
            Path occupied = dir.resolve("occupied");
            Files.createDirectory(occupied);
            Files.writeString(occupied.resolve("something"), "x", StandardCharsets.UTF_8);

            var result = assertDoesNotThrow(() -> ConfigCodec.load(occupied));
            assertEquals(SkyPrismConfig.defaults(), result.config());
            assertNoTemporaryFiles();
        }

        private void assertNoTemporaryFiles() throws IOException {
            try (var entries = Files.list(dir)) {
                var leftovers = entries.map(p -> p.getFileName().toString())
                        .filter(n -> n.contains(".tmp"))
                        .sorted()
                        .toList();
                assertEquals(List.of(), leftovers, "a failed write must not leave a temporary file");
            }
        }
    }

    @Nested
    @DisplayName("numbers JSON cannot spell")
    class NonFiniteNumbers {

        /**
         * Gson refuses to write NaN or an infinity, and it does so with an
         * {@link IllegalArgumentException} rather than the {@link IOException} the save
         * signature promises. A HUD screen that divided by a zero window width hands over
         * exactly such a value, and the result was that the save button threw an
         * undeclared exception and the player's other forty settings never reached disk.
         */
        @Test
        @DisplayName("toJson renders a config carrying NaN instead of refusing it")
        void toJsonSurvivesNonFiniteNumbers() {
            var broken = SkyPrismConfig.defaults();
            broken.hud.x = Double.NaN;
            broken.hud.scale = Double.POSITIVE_INFINITY;
            broken.sounds.volume = Double.NEGATIVE_INFINITY;

            String json = assertDoesNotThrow(() -> ConfigCodec.toJson(broken));
            assertFalse(json.contains("NaN"), "the file must stay parseable by anything but Gson");
            assertFalse(json.contains("Infinity"));

            var reread = ConfigCodec.fromJson(json).orElseThrow();
            assertEquals(0.5, reread.hud.x, "the repaired value is what lands on disk");
            assertEquals(SkyPrismConfig.HudSettings.MAX_SCALE, reread.hud.scale);
            assertEquals(0.0, reread.sounds.volume);
        }

        @Test
        @DisplayName("saving such a config writes a file that loads back cleanly")
        void saveSurvivesNonFiniteNumbers() throws IOException {
            var broken = SkyPrismConfig.defaults();
            broken.hud.y = Double.NaN;
            broken.levels.chromaCyclesPerSecond = Double.NaN;
            broken.diana.reelCount = 4;

            assertDoesNotThrow(() -> ConfigCodec.save(file(), broken));
            var back = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, back.status());
            assertEquals(4, back.config().diana.reelCount,
                    "one impossible number must not cost the settings around it");
            assertEquals(broken.sanitized(), back.config());
        }
    }

    @Nested
    @DisplayName("the v1 tick conversion at the far end of its range")
    class TickOverflow {

        /**
         * Multiplying by fifty overflowed silently, and because the wrapped value came out
         * negative the clamp then snapped it to the <em>shortest</em> legal window. A file
         * asking for an absurdly long loot window got the shortest one instead, which is
         * the opposite of what it said and is invisible in the resulting config.
         */
        @Test
        @DisplayName("a tick count too large to convert saturates upwards, never downwards")
        void hugeTickCountSaturates() {
            var result = ConfigMigrations.migrate(parse(
                    "{\"configVersion\": 1, \"diana\": {\"lootWindowTicks\": 200000000000000000}}"));

            long millis = result.root().getAsJsonObject("diana").get("lootWindowMillis").getAsLong();
            assertTrue(millis > 0, "the converted window must not wrap into a negative, was " + millis);
        }

        @Test
        @DisplayName("through the codec, an absurd window becomes the longest, not the shortest")
        void hugeTickCountClampsToTheLongestWindow() throws IOException {
            Files.writeString(file(),
                    "{\"configVersion\": 1, \"diana\": {\"lootWindowTicks\": 200000000000000000}}",
                    StandardCharsets.UTF_8);

            var c = ConfigCodec.loadOrDefaults(file());
            assertEquals(DianaSettings.MAX_LOOT_WINDOW_MILLIS, c.diana.lootWindowMillis);
        }

        @Test
        @DisplayName("a negative tick count still ends up at the shortest legal window")
        void negativeTickCountClampsDown() throws IOException {
            Files.writeString(file(),
                    "{\"configVersion\": 1, \"diana\": {\"lootWindowTicks\": -9000000000000000000}}",
                    StandardCharsets.UTF_8);

            var c = ConfigCodec.loadOrDefaults(file());
            assertEquals(DianaSettings.MIN_LOOT_WINDOW_MILLIS, c.diana.lootWindowMillis);
        }

        private static JsonObject parse(String json) {
            return JsonParser.parseString(json).getAsJsonObject();
        }
    }

    @Nested
    @DisplayName("a file from a newer build")
    class NewerBuild {

        /**
         * The stated policy is that a file from a newer SkyPrism is never rewritten. That
         * held only while the newer file happened to still bind, which is precisely the
         * case where the version bump did not matter. A newer build that changed a field's
         * shape -- the only reason to bump at all -- failed the bind, fell into the corrupt
         * path, and had the player's settings renamed aside and replaced with defaults.
         */
        @Test
        @DisplayName("is left alone even when this build cannot bind its shape")
        void unbindableFutureFileIsNotDestroyed() throws IOException {
            String json = "{\"configVersion\": " + (SkyPrismConfig.CONFIG_VERSION + 1)
                    + ", \"hud\": {\"anchor\": {\"preset\": \"TOP_LEFT\", \"offset\": [4, 4]}},"
                    + " \"diana\": {\"reelCount\": 2}}";
            Files.writeString(file(), json, StandardCharsets.UTF_8);

            var result = ConfigCodec.load(file());

            assertEquals(ConfigCodec.Status.FROM_NEWER_VERSION, result.status());
            assertTrue(result.preservedAs().isEmpty(), "nothing was destroyed, so nothing was rescued");
            assertEquals(json, Files.readString(file(), StandardCharsets.UTF_8),
                    "a schema this build has never seen must survive the downgrade");
            assertEquals(SkyPrismConfig.defaults(), result.config(),
                    "and the session runs on defaults rather than half-read settings");
            assertFalse(result.notes().isEmpty(), "the player has to be told why nothing applied");

            try (var entries = Files.list(dir)) {
                assertEquals(List.of("skyprism.json"),
                        entries.map(p -> p.getFileName().toString()).sorted().toList());
            }
        }

        @Test
        @DisplayName("a same-version file with the same damage is still recovered from")
        void currentVersionWithTheSameDamageIsStillTreatedAsCorrupt() throws IOException {
            String json = "{\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION
                    + ", \"hud\": {\"anchor\": {\"preset\": \"TOP_LEFT\"}}}";
            Files.writeString(file(), json, StandardCharsets.UTF_8);

            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.RECOVERED, result.status(),
                    "the future exemption must not become a blanket excuse");
            assertEquals(json, Files.readString(result.preservedAs().orElseThrow(),
                    StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("two saves at once")
    class Concurrency {

        /**
         * The config screen's apply button and an auto-save on world change can land in the
         * same millisecond. Every save used to stage its JSON under one fixed temporary
         * name, so two writers shared a single file: on Windows the second hit the first's
         * open handle and threw {@code AccessDeniedException}, and when the first won the
         * race to rename it the second threw {@code NoSuchFileException} instead. Either
         * way an exception reached a player who had only pressed save.
         */
        @Test
        @DisplayName("two saves at once never collide over one temporary name")
        void concurrentSavesAllSucceed() throws Exception {
            ConfigCodec.save(file(), SkyPrismConfig.defaults());

            int writers = 6;
            int rounds = 40;
            var start = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();

            var threads = new java.util.ArrayList<Thread>();
            for (int w = 0; w < writers; w++) {
                int id = w;
                threads.add(Thread.ofPlatform().unstarted(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < rounds; i++) {
                            var c = SkyPrismConfig.defaults();
                            c.levels.chromaMinLevel = 100 + id;
                            ConfigCodec.save(file(), c);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
            }

            threads.forEach(Thread::start);
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }

            assertEquals(null, failure.get(), "a save must not fail because another was in flight");
            assertEquals(ConfigCodec.Status.LOADED, ConfigCodec.load(file()).status(),
                    "and the file left behind is one of the configs written, intact");
            try (var entries = Files.list(dir)) {
                assertEquals(List.of("skyprism.json"),
                        entries.map(p -> p.getFileName().toString()).sorted().toList());
            }
        }

        /**
         * A load racing a save was the other half of the same problem. Windows will not
         * rename over a file another handle has open, so the reader's own open handle was
         * enough to make the writer's save throw -- and the reader could in principle be
         * handed a file mid-replacement.
         */
        @Test
        @DisplayName("a load racing a save neither fails nor sees a half-written file")
        void readersNeverSeeAPartialFile() throws Exception {
            ConfigCodec.save(file(), SkyPrismConfig.defaults());

            int rounds = 300;
            var start = new CountDownLatch(1);
            var recoveredReads = new AtomicInteger();
            var failure = new AtomicReference<Throwable>();

            var writer = Thread.ofPlatform().unstarted(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        var c = SkyPrismConfig.defaults();
                        c.levels.chromaMinLevel = 100 + (i % 50);
                        ConfigCodec.save(file(), c);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            var reader = Thread.ofPlatform().unstarted(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        if (ConfigCodec.load(file()).recovered()) {
                            recoveredReads.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            writer.start();
            reader.start();
            start.countDown();
            writer.join();
            reader.join();

            assertEquals(null, failure.get());
            assertEquals(0, recoveredReads.get(), "a reader must never see a half-written file");
            try (var entries = Files.list(dir)) {
                assertEquals(List.of("skyprism.json"),
                        entries.map(p -> p.getFileName().toString()).sorted().toList());
            }
        }

        @Test
        @DisplayName("sanitizing one shared instance from many threads is safe and side-effect free")
        void concurrentSanitizeIsPure() throws Exception {
            var shared = SkyPrismConfig.defaults();
            shared.levels.minLevel = 900;
            shared.levels.maxLevel = 100;
            shared.diana.reelCount = 99;
            var expected = shared.sanitized();
            var before = shared.copy();

            var start = new CountDownLatch(1);
            var mismatch = new AtomicInteger();
            var threads = new java.util.ArrayList<Thread>();
            for (int t = 0; t < 8; t++) {
                threads.add(Thread.ofPlatform().unstarted(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 200; i++) {
                            if (!expected.equals(shared.sanitized())) {
                                mismatch.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            threads.forEach(Thread::start);
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }

            assertEquals(0, mismatch.get());
            assertEquals(before, shared, "sanitizing must not touch the instance it was called on");
        }
    }

    @Nested
    @DisplayName("jackpot matching")
    class Jackpot {

        /**
         * {@code isJackpot} promises to ignore colour codes, but only ever cleaned the drop
         * name it was handed, not the entries it compared against. A name pasted straight
         * out of chat into the config screen therefore never matched anything -- and the
         * screen holds the live, not-yet-sanitized instance, which is exactly where a
         * pasted name lives until the player presses save.
         */
        @Test
        @DisplayName("ignores formatting on the configured entry as well as on the drop")
        void formattingOnAConfiguredEntryIsIgnored() {
            var d = new DianaSettings();
            d.jackpotItems = new LinkedHashSet<>(List.of(
                    S + "6" + S + "lMinos  Relic" + S + "r", "  Crown of Greed  "));

            assertTrue(d.isJackpot("Minos Relic"), "a pasted, still-coloured entry must still match");
            assertTrue(d.isJackpot(S + "6minos relic"));
            assertTrue(d.isJackpot("crown of greed"));
            assertFalse(d.isJackpot("Griffin Feather"));
        }

        @Test
        @DisplayName("a blank or formatting-only entry never matches anything")
        void blankEntriesNeverMatch() {
            var d = new DianaSettings();
            d.jackpotItems = new LinkedHashSet<>(List.of(S + "6" + S + "r", "   ", ""));

            assertFalse(d.isJackpot(""));
            assertFalse(d.isJackpot("   "));
            assertFalse(d.isJackpot(S + "6"));
            assertFalse(d.isJackpot("Anything"));
        }

        @Test
        @DisplayName("an entry carrying an emoji survives cleaning as one code point")
        void supplementaryCodePointsSurvive() {
            var d = new DianaSettings();
            d.jackpotItems = new LinkedHashSet<>(List.of(S + "6💀 Skull"));
            var clean = configWith(d).sanitized().diana;

            assertEquals(List.of("💀 Skull"), List.copyOf(clean.jackpotItems));
            assertTrue(clean.isJackpot("💀 Skull"));
            assertEquals(1, clean.jackpotItems.iterator().next().codePointAt(0) == 0x1F480 ? 1 : 0,
                    "the surrogate pair must not have been split");
        }

        private static SkyPrismConfig configWith(DianaSettings diana) {
            var c = SkyPrismConfig.defaults();
            c.diana = diana;
            return c;
        }
    }

    @Nested
    @DisplayName("odds and ends the happy path never reaches")
    class Misc {

        @Test
        @DisplayName("a file saved by an editor that adds a byte-order mark still loads")
        void byteOrderMarkIsTolerated() throws IOException {
            Files.writeString(file(), "﻿{\"configVersion\": " + SkyPrismConfig.CONFIG_VERSION
                    + ", \"diana\": {\"reelCount\": 4}}", StandardCharsets.UTF_8);
            var result = ConfigCodec.load(file());
            assertEquals(ConfigCodec.Status.LOADED, result.status(),
                    "Notepad's BOM is not corruption");
            assertEquals(4, result.config().diana.reelCount);
        }

        @Test
        @DisplayName("a pathologically nested file is recovered from rather than crashing the load")
        void deeplyNestedJsonDoesNotEscapeAsAnError() throws IOException {
            int depth = 60_000;
            Files.writeString(file(),
                    "{\"x\":" + "[".repeat(depth) + "]".repeat(depth) + "}", StandardCharsets.UTF_8);

            var result = assertDoesNotThrow(() -> ConfigCodec.load(file()));
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertTrue(result.preservedAs().isPresent());
        }

        @Test
        @DisplayName("a file of invalid UTF-8 bytes is left in place, not moved aside")
        void undecodableBytesLeaveTheFileAlone() throws IOException {
            byte[] notUtf8 = {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1};
            Files.write(file(), notUtf8);

            var result = assertDoesNotThrow(() -> ConfigCodec.load(file()));
            assertEquals(ConfigCodec.Status.RECOVERED, result.status());
            assertTrue(result.preservedAs().isEmpty(),
                    "an unreadable file may just be locked; it must not be moved");
            org.junit.jupiter.api.Assertions.assertArrayEquals(notUtf8, Files.readAllBytes(file()));
        }

        @Test
        @DisplayName("a level table far past the cap is trimmed to something the ramp accepts")
        void oversizedTablesStayConstructible() {
            var c = SkyPrismConfig.defaults();
            var stops = new java.util.ArrayList<com.skyprism.core.level.GradientRamp.Stop>();
            for (int i = 0; i < 5_000; i++) {
                stops.add(new com.skyprism.core.level.GradientRamp.Stop(i, 0x010203));
            }
            c.levels.customStops = stops;
            c.levels.gradientPreset = SkyPrismConfig.LevelSettings.CUSTOM_PRESET;

            var clean = c.sanitized().levels;
            assertEquals(SkyPrismConfig.LevelSettings.MAX_TABLE_ENTRIES, clean.customStops.size());
            assertDoesNotThrow(clean::resolveRamp);
            assertNotEquals(0, clean.resolveRamp().colorAt(0));
        }

        @Test
        @DisplayName("preserving aside stops rather than wrapping once the names run out")
        void preservationGivesUpRatherThanOverwriting() throws IOException {
            Path victim = dir.resolve("full.json");
            Files.writeString(victim, "original", StandardCharsets.UTF_8);
            for (int i = 0; i < ConfigCodec.MAX_PRESERVED_COPIES; i++) {
                Files.writeString(dir.resolve("full.json" + ConfigCodec.CORRUPT_SUFFIX
                        + (i == 0 ? "" : "-" + i)), "taken", StandardCharsets.UTF_8);
            }

            assertTrue(ConfigCodec.preserveAside(victim).isEmpty());
            assertEquals("original", Files.readString(victim, StandardCharsets.UTF_8),
                    "when there is nowhere to put it, the original stays where it is");
            assertEquals("taken", Files.readString(dir.resolve("full.json"
                    + ConfigCodec.CORRUPT_SUFFIX), StandardCharsets.UTF_8));
        }
    }
}
