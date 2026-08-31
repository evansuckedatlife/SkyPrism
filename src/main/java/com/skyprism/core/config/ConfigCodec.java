package com.skyprism.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes {@link SkyPrismConfig} as JSON on disk, without ever losing a file.
 *
 * <p><b>The rule this class is built around: a config file is a player's work.</b> Some
 * of them spent an evening hand-tuning a twelve-stop gradient. So nothing here throws its
 * way out of a load, and nothing here overwrites a file it could not understand until it
 * has put a copy of that file somewhere the player can get it back from. The three
 * failure modes are handled explicitly rather than lumped together:
 *
 * <ul>
 *   <li><b>Missing.</b> First launch, or the player deleted it. Defaults are returned and
 *       written out, so the file exists to be hand-edited immediately afterwards.</li>
 *   <li><b>Unparseable or wrongly typed.</b> Truncated by a crash mid-write, emptied by a
 *       full disk, or hand-edited into invalid JSON. The broken file is renamed aside to a
 *       {@code .corrupt} sibling, defaults are written in its place, and the load reports
 *       where the wreckage went. If the file cannot even be renamed aside, defaults are
 *       returned but <em>nothing is written</em> -- returning to a default palette for one
 *       session is recoverable, silently destroying the only copy is not.</li>
 *   <li><b>From a newer build.</b> Left exactly as it is, bound as best this build can,
 *       and flagged, because downgrading a schema this code has never seen would be
 *       guesswork performed on someone else's settings. This holds even when the newer
 *       shape cannot be bound at all, which is the case a version bump exists for: the
 *       session runs on defaults and the file is not touched, rather than being filed as
 *       corruption and overwritten the first time the player downgrades.</li>
 * </ul>
 *
 * <p>Writes go through a temporary file and an atomic rename, which is what stops this
 * class from creating the truncated files it is elsewhere busy recovering from.
 *
 * <p>Every method here is safe to call from any thread and from several at once: this
 * class serialises its own filesystem access, because an atomic rename protects a file
 * against a crash but not against a second writer aiming at the same destination.
 *
 * <p>Gson is used here and nowhere else in the core; the rest of the module stays free of
 * it so it can be tested without a serialisation library in the way.
 */
public final class ConfigCodec {

    /** Suffix given to a file that could not be read, before defaults replace it. */
    public static final String CORRUPT_SUFFIX = ".corrupt";

    /** How many {@code .corrupt} siblings may pile up before preservation gives up. */
    public static final int MAX_PRESERVED_COPIES = 100;

    /** Extension of the short-lived sibling a save is staged in before being renamed into place. */
    private static final String TEMP_SUFFIX = ".tmp";

    /**
     * Serialises every filesystem operation this class performs.
     *
     * <p>Staging a write and renaming it into place is atomic against a crash, but it is
     * not atomic against a second writer: two threads renaming onto one destination race
     * over the destination itself, and Windows answers the loser with
     * {@code AccessDeniedException}. The config screen's apply button and an auto-save on
     * world change land together often enough that a player really did see that exception
     * for pressing save. Reads are held under the same lock, because on Windows an open
     * read handle is also enough to make a rename over the file fail.
     *
     * <p>One monitor for all paths rather than one per path: a client has a single config
     * file, these operations are a fraction of a millisecond on a file measured in
     * kilobytes, and a map of per-path locks would be more machinery than the contention
     * it removes. The monitor is reentrant, which matters because a load legitimately
     * preserves a file aside and then saves over it while already holding it.
     */
    private static final Object FILE_LOCK = new Object();

    /**
     * Pretty-printed, HTML-escaping off.
     *
     * <p>Both settings are about the file being hand-editable, which is the point of
     * shipping JSON rather than a binary blob: pretty printing makes a diff readable, and
     * disabling HTML escaping stops an apostrophe in an item name turning into
     * {@code '} for no reason a player could ever guess at. Field order follows
     * declaration order in {@link SkyPrismConfig} and collection order follows the
     * collections themselves, so a save that changed nothing produces a byte-identical
     * file and version control stays quiet.
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private ConfigCodec() {
    }

    /** How a load ended, in the caller's terms rather than the filesystem's. */
    public enum Status {
        /** The file was read and bound cleanly at the current schema. */
        LOADED,

        /** No file was there; defaults were returned and written out. */
        CREATED,

        /** An older file was walked up to the current schema and rewritten. */
        MIGRATED,

        /** The file was unusable; defaults were returned. See {@link LoadResult#preservedAs()}. */
        RECOVERED,

        /** The file came from a newer SkyPrism. It was read as far as possible and left alone. */
        FROM_NEWER_VERSION
    }

    /**
     * The outcome of a load, in enough detail for the mod to tell the player what happened.
     *
     * <p>The status and the notes exist so a recovery is announceable. A player whose
     * palette silently reset learns that the mod is broken; a player who gets "your config
     * was unreadable and has been saved as skyprism.json.corrupt" learns that their file
     * is broken, and still has it.
     *
     * @param config      always non-null and always {@link SkyPrismConfig#sanitized()}
     * @param status      how the load ended
     * @param preservedAs where a broken file was moved to, empty in every other case
     * @param notes       human-readable lines about migrations and repairs, possibly empty
     */
    public record LoadResult(SkyPrismConfig config, Status status, Optional<Path> preservedAs,
                             List<String> notes) {

        public LoadResult {
            notes = List.copyOf(notes);
        }

        /** True when the returned config is not what the file asked for. */
        public boolean recovered() {
            return status == Status.RECOVERED;
        }
    }

    /**
     * Loads a config, repairing or replacing whatever it finds.
     *
     * <p>Never throws, for any input, including a null path, a directory where a file was
     * expected, and a file the process cannot read.
     *
     * @param file where the config lives
     * @return the outcome; {@link LoadResult#config()} is always usable
     */
    public static LoadResult load(Path file) {
        var notes = new ArrayList<String>();
        if (file == null) {
            notes.add("no config path was given; using defaults for this session");
            return new LoadResult(SkyPrismConfig.defaults(), Status.RECOVERED, Optional.empty(), notes);
        }

        synchronized (FILE_LOCK) {
            return loadLocked(file, notes);
        }
    }

    /** The body of {@link #load(Path)}, with this class's file lock already held. */
    private static LoadResult loadLocked(Path file, ArrayList<String> notes) {
        if (!Files.isRegularFile(file)) {
            var fresh = SkyPrismConfig.defaults();
            try {
                save(file, fresh);
                notes.add("no config found; wrote defaults to " + file);
            } catch (IOException cannotWrite) {
                notes.add("no config found and defaults could not be written: " + cannotWrite);
            }
            return new LoadResult(fresh, Status.CREATED, Optional.empty(), notes);
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            // Could be a transient Windows lock rather than damage, so the file is left
            // strictly alone: not moved aside, not overwritten. Next launch may well work.
            notes.add("config could not be read (" + unreadable + "); using defaults, file untouched");
            return new LoadResult(SkyPrismConfig.defaults(), Status.RECOVERED, Optional.empty(), notes);
        }

        JsonObject root = parseObject(text);
        if (root == null) {
            return recoverFrom(file, "the file is not valid JSON", notes);
        }

        ConfigMigrations.Result migration = ConfigMigrations.migrate(root);
        notes.addAll(migration.notes());

        SkyPrismConfig bound;
        try {
            bound = GSON.fromJson(migration.root(), SkyPrismConfig.class);
        } catch (RuntimeException wrongShape) {
            // Gson throws here for a field whose JSON type cannot be coerced at all -- a
            // word where a number belongs, an array where an object belongs.
            if (migration.fromFuture()) {
                // ...but from a NEWER build that is not damage, it is the schema change the
                // version was bumped for. Treating it as corruption would rename a perfectly
                // good file aside and write defaults over it the first time the player
                // downgrades, which is exactly the loss the version field exists to prevent.
                notes.add("config declares v" + migration.fromVersion() + " but this build reads v"
                        + SkyPrismConfig.CONFIG_VERSION + " and cannot read its shape ("
                        + wrongShape.getMessage() + "); defaults are in use for this session and "
                        + "the file was left untouched");
                return new LoadResult(SkyPrismConfig.defaults(), Status.FROM_NEWER_VERSION,
                        Optional.empty(), notes);
            }
            // At this build's own version there is no partial answer to salvage, so the file
            // goes aside intact.
            return recoverFrom(file, "a setting had the wrong type (" + wrongShape.getMessage() + ")",
                    notes);
        }
        if (bound == null) {
            return recoverFrom(file, "the file contained no settings", notes);
        }

        SkyPrismConfig clean = bound.sanitized();

        if (migration.fromFuture()) {
            notes.add("config declares v" + migration.fromVersion() + " but this build reads v"
                    + SkyPrismConfig.CONFIG_VERSION + "; the file was not rewritten");
            return new LoadResult(clean, Status.FROM_NEWER_VERSION, Optional.empty(), notes);
        }

        if (migration.migrated()) {
            try {
                save(file, clean);
                notes.add("migrated v" + migration.fromVersion() + " -> v"
                        + SkyPrismConfig.CONFIG_VERSION + " and saved");
            } catch (IOException cannotWrite) {
                notes.add("migrated in memory but could not save: " + cannotWrite);
            }
            return new LoadResult(clean, Status.MIGRATED, Optional.empty(), notes);
        }

        return new LoadResult(clean, Status.LOADED, Optional.empty(), notes);
    }

    /**
     * The load for callers that only want the settings.
     *
     * @param file where the config lives
     * @return the sanitized config, never null
     */
    public static SkyPrismConfig loadOrDefaults(Path file) {
        return load(file).config();
    }

    /**
     * Writes a config, atomically.
     *
     * <p>The JSON goes to a temporary sibling first and is then renamed over the target, so
     * a crash or a power cut during the write leaves either the old file or the new one
     * and never a half-written one. The sibling has to be in the same directory for the
     * rename to be atomic; a temp directory on another volume would silently degrade to a
     * copy, which is the very thing being avoided.
     *
     * <p><b>The temporary name is unique per write, not fixed.</b> A config screen's apply
     * button and an auto-save on world change land in the same millisecond often enough to
     * matter, and with one shared name the second writer either fails outright on the
     * first writer's open handle or renames a file the first writer has already moved.
     * Both surfaced as an exception thrown at a player who did nothing wrong. A unique name
     * also makes the write safe between two game instances sharing a config directory.
     *
     * <p>A temporary file that never made it into place is always removed, so a write that
     * cannot finish leaves the directory exactly as it found it rather than an orphaned
     * {@code .tmp} nobody will ever recognise.
     *
     * <p>Unlike {@link #load(Path)} this does throw, because a caller that cannot save must
     * be able to tell the player so.
     *
     * @param file   destination, whose parent directories are created if needed
     * @param config the settings to write; nulls inside it are tolerated
     * @throws IOException          if the file could not be written or renamed into place
     * @throws NullPointerException if {@code file} or {@code config} is null
     */
    public static void save(Path file, SkyPrismConfig config) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(config, "config");

        String json = toJson(config);
        synchronized (FILE_LOCK) {
            writeLocked(file, json);
        }
    }

    /** The body of {@link #save(Path, SkyPrismConfig)}, with this class's file lock held. */
    private static void writeLocked(Path file, String json) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = parent == null
                ? file.resolveSibling(file.getFileName() + TEMP_SUFFIX)
                : Files.createTempFile(parent, file.getFileName() + ".", TEMP_SUFFIX);
        try {
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // Some network and virtual filesystems refuse ATOMIC_MOVE. A plain replace is
                // still far better than writing the destination in place.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cannotClean) {
                // Attached rather than thrown: the caller needs to see why the save failed,
                // not why the tidy-up afterwards also failed.
                failed.addSuppressed(cannotClean);
            }
            throw failed;
        }
    }

    /**
     * The text {@link #save(Path, SkyPrismConfig)} would write for these settings.
     *
     * <p>A config holding a value JSON cannot spell -- a NaN or an infinity, which a screen
     * produces the moment something divides by a zero-width window -- is rendered from its
     * {@link SkyPrismConfig#sanitized()} form instead of refusing to render at all. Gson
     * signals that case with an {@link IllegalArgumentException}, and letting it out would
     * mean one impossible number costs the player every other setting in the file, thrown
     * as an exception the {@code save} signature never promised.
     *
     * @param config the settings to render
     * @return pretty-printed JSON, with a trailing newline so the file ends the way every
     *         other text file on the machine does
     */
    public static String toJson(SkyPrismConfig config) {
        String body;
        try {
            body = GSON.toJson(config);
        } catch (IllegalArgumentException nonFiniteNumber) {
            body = GSON.toJson(config.sanitized());
        }
        return body + System.lineSeparator();
    }

    /**
     * Binds JSON text without touching the filesystem, for tests and for a paste-a-config
     * command.
     *
     * @param json config JSON, migrated on the way through exactly as a file would be
     * @return the sanitized config, or empty if the text is not a usable config object
     */
    public static Optional<SkyPrismConfig> fromJson(String json) {
        JsonObject root = json == null ? null : parseObject(json);
        if (root == null) {
            return Optional.empty();
        }
        try {
            SkyPrismConfig bound = GSON.fromJson(ConfigMigrations.migrate(root).root(),
                    SkyPrismConfig.class);
            return bound == null ? Optional.empty() : Optional.of(bound.sanitized());
        } catch (RuntimeException wrongShape) {
            return Optional.empty();
        }
    }

    /**
     * Moves a file aside without ever overwriting an earlier rescue.
     *
     * <p>The numbering matters more than it looks: a config that is being corrupted every
     * launch by some other tool would, under a fixed name, have its first and most
     * complete copy overwritten by the fourth and emptiest. Names are chosen in order and
     * the search stops rather than wrapping, so the oldest copies are the ones that
     * survive.
     *
     * @param file the file to preserve
     * @return where it went, or empty if it could not be moved -- in which case the caller
     *         must leave the original alone
     */
    public static Optional<Path> preserveAside(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        synchronized (FILE_LOCK) {
            return preserveAsideLocked(file);
        }
    }

    /** The body of {@link #preserveAside(Path)}, with this class's file lock held. */
    private static Optional<Path> preserveAsideLocked(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String base = file.getFileName().toString();
        for (int i = 0; i < MAX_PRESERVED_COPIES; i++) {
            Path target = file.resolveSibling(base + CORRUPT_SUFFIX + (i == 0 ? "" : "-" + i));
            if (Files.exists(target)) {
                continue;
            }
            try {
                Files.move(file, target);
                return Optional.of(target);
            } catch (IOException | RuntimeException cannotMove) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Null rather than an exception for anything that is not a JSON object. */
    private static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** Preserve the wreckage, put defaults in its place, and say so. */
    private static LoadResult recoverFrom(Path file, String reason, List<String> notes) {
        var fresh = SkyPrismConfig.defaults();
        Optional<Path> preserved = preserveAside(file);
        if (preserved.isEmpty()) {
            notes.add("config is unusable (" + reason
                    + ") but could not be moved aside; it has been left in place and defaults "
                    + "are in use for this session");
            return new LoadResult(fresh, Status.RECOVERED, Optional.empty(), notes);
        }
        notes.add("config is unusable (" + reason + "); the original is preserved at "
                + preserved.get());
        try {
            save(file, fresh);
        } catch (IOException cannotWrite) {
            notes.add("defaults could not be written: " + cannotWrite);
        }
        return new LoadResult(fresh, Status.RECOVERED, preserved, notes);
    }
}
