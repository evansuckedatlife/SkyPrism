package com.skyprism.mc.symbols;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.skyprism.core.config.ConfigCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What Hypixel's own art for a Diana drop looks like, remembered across restarts.
 *
 * <h2>Why anything has to be remembered at all</h2>
 * <p>SkyBlock now ships a server resource pack, and the way an item in it gets custom art is the
 * vanilla 26.x item-model system: the server sets the {@code minecraft:item_model} component on the
 * stack to a namespaced id such as
 * {@code hypixel_skyblock:item/community_center/mayor/diana/daedalus_blade}, and the pack supplies
 * the matching model. The reels used to draw a <em>synthesised</em> vanilla stack -- a Daedalus
 * Stick became {@code minecraft:stick} -- and a synthesised stack carries no such component, so it
 * could never match the art the player sees in their own inventory.
 *
 * <p>The obvious fix -- look the model id up from the drop's name -- is not available. The pack's
 * paths are semantic ({@code community_center/mayor/diana/...}), there is no display-name index
 * anywhere in it, and nothing in the protocol announces the mapping. The id can only be
 * <em>learned</em>, by reading it off a genuine stack the server sent, which means it can only be
 * learned the first time a drop actually lands.
 *
 * <p>That is what this file is for. Learning once per drop per install would be a poor deal --
 * a Chimera is a once-a-week event -- so the pair {base item id, item model id} is written to disk
 * the moment it is seen. From the next launch onward the reel draws Hypixel's real Chimera the
 * instant the name appears, from a cold start, with no capture needed and no window in which the
 * wrong picture is shown.
 *
 * <h2>Why the SkyBlock id is stored too</h2>
 * <p>Rows are keyed by the normalised display name, because a name is all a chat line gives us.
 * Names are also the least stable thing Hypixel owns: they get re-worded, re-coloured and
 * re-capitalised between updates. So when the captured stack carries an {@code ExtraAttributes.id}
 * -- SkyBlock's own internal id, which does not churn -- it is stored alongside, and a later
 * capture bearing the same id supersedes the row it used to live under rather than piling up a
 * second one. That is what keeps a re-named drop from occupying two slots and serving stale art
 * under the old name.
 *
 * <h2>Bounds and eviction</h2>
 * <p>Keys come from chat, so an unbounded map is a leak a hostile server can drive. The file holds
 * at most {@value #MAX_ENTRIES} rows; when a new row would exceed that, the rows with the oldest
 * {@code seenAt} are dropped until it fits. Least-recently-seen is the right axis here because the
 * value of a row is entirely "will this drop appear again", and the drop a player has not seen in
 * months is the one they will miss least -- and it is re-learned free the next time it lands.
 *
 * <h2>Schema</h2>
 * <p>The file carries a {@code version}. A file written by a <em>newer</em> build is left strictly
 * alone -- not parsed, not migrated, and never overwritten -- because the alternative is a player
 * who launches an old jar once and loses everything the new one learned. This session simply runs
 * without a memory and says so in {@link #notes()}.
 *
 * <h2>Corrupt-file discipline</h2>
 * <p>{@link ConfigCodec}'s rule, for {@link com.skyprism.mc.diana.DianaStats}'s reason: an
 * unreadable file is moved aside with {@link ConfigCodec#preserveAside(Path)} before anything is
 * written in its place, and if it cannot even be moved aside then nothing is ever written -- a
 * session without remembered art is a cosmetic loss, an overwritten memory is not. Nothing in this
 * class throws; a failure becomes a note and the reels fall back to the synthesised vanilla item.
 *
 * <h2>When it writes</h2>
 * <p>Never on the tick that learned something. {@link #remember} only sets a flag;
 * {@link #maybeSave(long)} is what writes, and the first call that finds the flag set merely
 * schedules the write for {@value #AUTOSAVE_INTERVAL_MILLIS} ms later. The tick that learns is by
 * construction a tick inside a live roll -- the slot machine is on screen and spinning -- which is
 * the last place to put a synchronous file write.
 *
 * <p><b>Threading:</b> client thread only.
 */
public final class IconMemory {

    /** The file name inside the Fabric config directory. */
    public static final String FILE_NAME = "drop_item_models.json";

    /** The schema this build writes and is willing to read. */
    public static final int SCHEMA_VERSION = 1;

    /** How many drop names may be remembered at once. See the class javadoc on eviction. */
    public static final int MAX_ENTRIES = 512;

    /** Minimum gap between automatic saves. */
    static final long AUTOSAVE_INTERVAL_MILLIS = 10_000L;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * One remembered drop.
     *
     * @param itemId     the base vanilla item the server sent, e.g. {@code minecraft:stick}
     * @param modelId    the {@code minecraft:item_model} component it carried, e.g.
     *                   {@code hypixel_skyblock:item/community_center/mayor/diana/daedalus_stick}
     * @param skyblockId SkyBlock's own {@code ExtraAttributes.id}, or null when the stack had none
     * @param seenAt     epoch millis of the most recent capture, which is the eviction key
     */
    public record Learned(String itemId, String modelId, String skyblockId, long seenAt) {
    }

    /** The on-disk shape of one row. A field per column, so the schema is visible in one place. */
    private static final class Row {
        String name;
        String sbId;
        String item;
        String model;
        long seenAt;
    }

    /** The on-disk shape of the whole file. */
    private static final class Data {
        int version = SCHEMA_VERSION;
        List<Row> entries = new ArrayList<>();
    }

    private final Path file;
    private final List<String> notes = new ArrayList<>();

    /** Name key to row. Insertion-ordered so a hand-read file stays diffable between saves. */
    private final Map<String, Learned> byName = new LinkedHashMap<>();

    private boolean dirty;
    private boolean autosaveScheduled;
    private long nextAutosaveAt;

    private IconMemory(Path file, Map<String, Learned> rows, List<String> notes) {
        this.file = file;
        this.byName.putAll(rows);
        this.notes.addAll(notes);
    }

    /**
     * Reads the memory, recovering rather than throwing whatever is on disk.
     *
     * @param file where the memory lives; null yields an in-memory-only store that never writes,
     *             which is what a bare-JVM test and a mod running without a Fabric loader get
     * @return a usable store in every case
     */
    public static IconMemory load(Path file) {
        List<String> notes = new ArrayList<>();
        if (file == null) {
            notes.add("no config path available; learned item models are in memory only "
                    + "for this session");
            return new IconMemory(null, Map.of(), notes);
        }
        if (!Files.isRegularFile(file)) {
            return new IconMemory(file, Map.of(), notes);
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            // Unreadable-right-now is not corruption: a locked or briefly unavailable file must
            // not be moved aside, or one transient failure would shuffle the memory into a
            // numbered rescue copy and start again from nothing.
            notes.add("learned item models could not be read (" + unreadable
                    + "); relearning them this session");
            return new IconMemory(null, Map.of(), notes);
        }

        Data parsed;
        try {
            parsed = GSON.fromJson(text, Data.class);
        } catch (RuntimeException notJson) {
            parsed = null;
        }
        if (parsed == null || parsed.entries == null) {
            return recover(file, notes);
        }
        if (parsed.version > SCHEMA_VERSION) {
            // A newer build wrote this. Read nothing, write nothing, touch nothing.
            notes.add("learned item models are schema v" + parsed.version + ", newer than this "
                    + "build understands (v" + SCHEMA_VERSION + "); the file is left untouched "
                    + "and models are relearned in memory this session");
            return new IconMemory(null, Map.of(), notes);
        }

        Map<String, Learned> rows = new LinkedHashMap<>();
        for (Row row : parsed.entries) {
            if (row == null || blank(row.name) || blank(row.item) || blank(row.model)) {
                continue;
            }
            rows.put(row.name.strip().toLowerCase(Locale.ROOT),
                    new Learned(row.item.strip(), row.model.strip(),
                            blank(row.sbId) ? null : row.sbId.strip(), Math.max(0L, row.seenAt)));
        }
        return new IconMemory(file, rows, notes);
    }

    /** A file that parsed to nothing usable: preserve it, then start empty. */
    private static IconMemory recover(Path file, List<String> notes) {
        Optional<Path> preserved = ConfigCodec.preserveAside(file);
        if (preserved.isPresent()) {
            notes.add("learned item models were unreadable; the original is preserved at "
                    + preserved.get());
            return new IconMemory(file, Map.of(), notes);
        }
        notes.add("learned item models were unreadable and could not be moved aside; "
                + "the file is left alone and models are relearned in memory this session");
        return new IconMemory(null, Map.of(), notes);
    }

    /** Diagnostics from the load, and any save failure since. Empty when everything went well. */
    public List<String> notes() {
        return Collections.unmodifiableList(notes);
    }

    /** Where the memory is written, or null when this instance is memory-only. */
    public Path file() {
        return file;
    }

    /** How many drop names are remembered. */
    public int size() {
        return byName.size();
    }

    /** Whether there are changes not yet on disk. */
    public boolean dirty() {
        return dirty;
    }

    /**
     * What is remembered for one already-normalised drop name.
     *
     * @param nameKey the key {@code DropSymbols.matchKey} produces; may be null
     * @return the remembered pair, or null when this name has never been captured
     */
    public Learned get(String nameKey) {
        return nameKey == null ? null : byName.get(nameKey);
    }

    /** Every remembered row, keyed by normalised drop name, unmodifiable. */
    public Map<String, Learned> all() {
        return Collections.unmodifiableMap(byName);
    }

    /**
     * Records what a real stack turned out to look like.
     *
     * <p>A row whose stored ids already match is refreshed rather than rewritten, so a drop that
     * lands ten times in an evening does not mark the file dirty ten times.
     *
     * @param nameKey    normalised drop name, the key everything else looks up by
     * @param skyblockId SkyBlock's own item id, or null when the stack carried none
     * @param itemId     the base item id, e.g. {@code minecraft:stick}
     * @param modelId    the {@code item_model} component id
     * @param now        epoch millis, stored as the eviction key
     * @return true when this changed what is remembered
     */
    public boolean remember(String nameKey, String skyblockId, String itemId, String modelId,
            long now) {
        if (blank(nameKey) || blank(itemId) || blank(modelId)) {
            return false;
        }
        Learned fresh = new Learned(itemId, modelId, blank(skyblockId) ? null : skyblockId,
                Math.max(0L, now));
        Learned existing = byName.get(nameKey);
        boolean changed = existing == null
                || !existing.itemId().equals(fresh.itemId())
                || !existing.modelId().equals(fresh.modelId());

        // A capture that carries an id supersedes any other row wearing that id: it means the
        // display name moved, and leaving the old row behind would keep serving stale art under
        // the old name while quietly consuming one of the bounded slots.
        if (fresh.skyblockId() != null) {
            changed |= byName.entrySet().removeIf(entry ->
                    !entry.getKey().equals(nameKey)
                            && fresh.skyblockId().equals(entry.getValue().skyblockId())
                            && !entry.getValue().modelId().equals(fresh.modelId()));
        }

        byName.put(nameKey, fresh);
        evictDownToBound(nameKey);
        if (changed) {
            dirty = true;
        }
        return changed;
    }

    /**
     * Drops the least-recently-seen rows until the map is back inside {@link #MAX_ENTRIES}.
     *
     * @param keep the row just written, which is never the one evicted however old its clock reads
     */
    private void evictDownToBound(String keep) {
        while (byName.size() > MAX_ENTRIES) {
            String oldestKey = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Learned> entry : byName.entrySet()) {
                if (entry.getKey().equals(keep)) {
                    continue;
                }
                if (entry.getValue().seenAt() <= oldestAt) {
                    oldestAt = entry.getValue().seenAt();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            byName.remove(oldestKey);
            dirty = true;
        }
    }

    /** Forgets everything and marks the memory for saving. The file is not deleted. */
    public void clear() {
        if (byName.isEmpty()) {
            return;
        }
        byName.clear();
        dirty = true;
    }

    /**
     * Writes if something changed and the autosave interval has elapsed.
     *
     * <p>Safe to call every tick: with nothing dirty it is one boolean read.
     *
     * @param now the current time in milliseconds
     */
    public void maybeSave(long now) {
        if (!dirty) {
            return;
        }
        if (!autosaveScheduled) {
            autosaveScheduled = true;
            nextAutosaveAt = now + AUTOSAVE_INTERVAL_MILLIS;
            return;
        }
        if (now < nextAutosaveAt) {
            return;
        }
        nextAutosaveAt = now + AUTOSAVE_INTERVAL_MILLIS;
        save();
    }

    /**
     * Writes now. A no-op when nothing changed or when this instance has no file.
     *
     * <p>Through a temporary file and a rename, which is what stops a crash mid-write from leaving
     * behind the truncated file {@link #load(Path)} is elsewhere busy recovering from. A failure is
     * recorded in {@link #notes()} and leaves the memory dirty, so the next attempt retries it.
     */
    public void save() {
        if (!dirty || file == null) {
            return;
        }
        Data data = new Data();
        for (Map.Entry<String, Learned> entry : byName.entrySet()) {
            Row row = new Row();
            row.name = entry.getKey();
            row.sbId = entry.getValue().skyblockId();
            row.item = entry.getValue().itemId();
            row.model = entry.getValue().modelId();
            row.seenAt = entry.getValue().seenAt();
            data.entries.add(row);
        }

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomicMove) {
                // Some network and virtual filesystems refuse ATOMIC_MOVE; a plain replace is
                // still far better than writing the destination in place.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            autosaveScheduled = false;
        } catch (IOException | RuntimeException cannotWrite) {
            note("learned item models could not be saved: " + cannotWrite);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException ignored) {
                // The stray temp file is cosmetic; the next save overwrites it.
            }
        }
    }

    /** A short, human-readable line for a status command. */
    @Override
    public String toString() {
        return "IconMemory[remembered=" + byName.size()
                + ", dirty=" + dirty
                + ", file=" + (file == null ? "<memory only>" : file) + "]";
    }

    /** Keeps the note list from growing without bound when a disk stays unwritable. */
    private void note(String message) {
        String line = message.toLowerCase(Locale.ROOT);
        for (String existing : notes) {
            if (existing.toLowerCase(Locale.ROOT).equals(line)) {
                return;
            }
        }
        if (notes.size() < 16) {
            notes.add(message);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
