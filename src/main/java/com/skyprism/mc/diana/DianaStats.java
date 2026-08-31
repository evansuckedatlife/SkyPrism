package com.skyprism.mc.diana;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.skyprism.core.config.ConfigCodec;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;

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
 * The running tally behind the slot machine: how many of each creature the player has killed, how
 * many rolls and jackpots that produced, and what actually dropped.
 *
 * <h2>Why the counters are keyed by name, not by enum</h2>
 * <p>The obvious shape is {@code Map<MythologicalCreature, Integer>}, and it is the wrong one on
 * disk. Hypixel adds mythological mobs; the day one is added, an older build reading a newer file
 * would either throw on the unknown key or silently drop the row, and a player's kill history is not
 * something to lose over a version skew. Keys are therefore plain strings, unknown ones are carried
 * through untouched, and {@link MythologicalCreature} is only consulted at the API boundary. The same
 * reasoning applies to the drop tally, whose keys are item names the server invents freely.
 *
 * <h2>Corrupt-file discipline</h2>
 * <p>This file follows {@link ConfigCodec}'s rule, for the same reason: a stats file is a record of
 * the player's evening. An unreadable file is moved aside through
 * {@link ConfigCodec#preserveAside(Path)} before anything is written in its place, and if it cannot
 * even be moved aside then nothing is written at all -- a session of lost counters is recoverable,
 * an overwritten history is not. A file that cannot be <em>saved</em> is likewise not fatal: the
 * failure is recorded in {@link #notes()} and the in-memory tally keeps counting.
 *
 * <h2>When it writes</h2>
 * <p>Never on the frame that changed something, and never on the tick either. {@link #recordKill},
 * {@link #recordRoll} and {@link #recordDrop} only set a flag; {@link #maybeSave(long)} is what
 * writes, and the first tick on which it finds that flag set only <em>schedules</em> the write for
 * {@value #AUTOSAVE_INTERVAL_MILLIS} ms later. That deferral is the point: the tick that first
 * dirties the tally is the tick a kill is registered, which is the same tick the slot machine
 * starts spinning, and a synchronous file write is the last thing that should land on it. The controller also forces a
 * {@link #save()} on the two edges where a delay would lose data -- disconnect and client shutdown --
 * because those are exactly when an unsaved minute would vanish.
 *
 * <p>The write is synchronous on the client thread. That is a deliberate choice over a background
 * thread: the file is a few hundred bytes, it happens at most once a minute, and the alternative
 * would mean this module owning a thread, which the performance rules for this mod rule out.
 *
 * <p><b>Threading:</b> client thread only.
 */
public final class DianaStats {

    /** The file name inside the Fabric config directory. */
    public static final String FILE_NAME = "diana_stats.json";

    /** Minimum gap between automatic saves. */
    private static final long AUTOSAVE_INTERVAL_MILLIS = 60_000L;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** The on-disk shape. A nested class so the persisted schema is visible in one place. */
    private static final class Data {
        int version = 1;
        long firstRecordedAt;
        long lastRecordedAt;
        int rolls;
        int jackpots;
        Map<String, Integer> kills = new LinkedHashMap<>();
        Map<String, Integer> drops = new LinkedHashMap<>();
    }

    private final Path file;
    private final List<String> notes = new ArrayList<>();

    private Data data = new Data();
    private boolean dirty;
    private long nextAutosaveAt;

    /**
     * Whether {@link #nextAutosaveAt} has been set since the tally last went dirty.
     *
     * <p>Without it the deadline starts at zero, which every clock reading is already past, so the
     * very first write fired on the tick a counter first changed -- and by construction that is the
     * tick a kill is registered and the slot machine starts spinning. A synchronous
     * {@code createDirectories} + {@code writeString} + atomic rename landing on the first frame of
     * the spin animation is the worst-placed hitch in the mod. Scheduling on the first dirty tick
     * instead puts the write a full interval after the roll is over, and the disconnect and
     * shutdown hooks already guarantee nothing is lost in the meantime.
     */
    private boolean autosaveScheduled;

    private DianaStats(Path file, Data data, List<String> notes) {
        this.file = file;
        this.data = data;
        this.notes.addAll(notes);
    }

    /**
     * Loads the tally, recovering rather than throwing whatever is on disk.
     *
     * @param file where the tally lives; null yields an in-memory-only tally that never writes
     * @return a usable tally in every case
     */
    public static DianaStats load(Path file) {
        List<String> notes = new ArrayList<>();
        if (file == null) {
            notes.add("no stats path available; counters are in memory only for this session");
            return new DianaStats(null, new Data(), notes);
        }
        if (!Files.isRegularFile(file)) {
            return new DianaStats(file, new Data(), notes);
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            notes.add("stats could not be read (" + unreadable + "); counting from zero this session");
            return new DianaStats(file, new Data(), notes);
        }
        Data parsed = null;
        try {
            parsed = GSON.fromJson(text, Data.class);
        } catch (RuntimeException notJson) {
            parsed = null;
        }
        if (parsed == null) {
            Optional<Path> preserved = ConfigCodec.preserveAside(file);
            if (preserved.isPresent()) {
                notes.add("stats file was unreadable; the original is preserved at " + preserved.get());
            } else {
                notes.add("stats file was unreadable and could not be moved aside; "
                        + "it has been left alone and this session counts from zero");
            }
            return new DianaStats(preserved.isPresent() ? file : null, new Data(), notes);
        }
        return new DianaStats(file, sanitize(parsed), notes);
    }

    /** Null maps and negative counters are what a hand-edited file looks like; fix, do not reject. */
    private static Data sanitize(Data raw) {
        if (raw.kills == null) {
            raw.kills = new LinkedHashMap<>();
        }
        if (raw.drops == null) {
            raw.drops = new LinkedHashMap<>();
        }
        raw.kills.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 0);
        raw.drops.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 0);
        raw.rolls = Math.max(0, raw.rolls);
        raw.jackpots = Math.max(0, raw.jackpots);
        return raw;
    }

    /** Diagnostics from the load, and any save failure since. Empty when everything went well. */
    public List<String> notes() {
        return Collections.unmodifiableList(notes);
    }

    /** Where the tally is written, or null when this instance is memory-only. */
    public Path file() {
        return file;
    }

    /**
     * Counts one defeated creature.
     *
     * @param creature the creature, null is ignored
     */
    public void recordKill(MythologicalCreature creature) {
        if (creature == null) {
            return;
        }
        bump(data.kills, creature.name());
        touch();
    }

    /**
     * Counts one completed roll.
     *
     * @param jackpot whether the roll ended having captured a jackpot drop
     */
    public void recordRoll(boolean jackpot) {
        data.rolls++;
        if (jackpot) {
            data.jackpots++;
        }
        touch();
    }

    /**
     * Adds a drop to the tally.
     *
     * <p>Counted by {@link LootDrop#count()}, so a payout of 2,500 coins moves the coin total by
     * 2,500 rather than by one. Item names are already formatting-stripped and whitespace-collapsed
     * by the parser, so they are used as keys verbatim.
     *
     * @param drop the drop, null or blank-named is ignored
     */
    public void recordDrop(LootDrop drop) {
        if (drop == null || drop.itemName() == null || drop.itemName().isEmpty()) {
            return;
        }
        add(data.drops, drop.itemName(), drop.count());
        touch();
    }

    /** How many times this creature has been defeated. */
    public int kills(MythologicalCreature creature) {
        if (creature == null) {
            return 0;
        }
        Integer count = data.kills.get(creature.name());
        return count == null ? 0 : count;
    }

    /** Every kill counted, including creatures this build does not recognise. */
    public int totalKills() {
        int total = 0;
        for (int count : data.kills.values()) {
            total += count;
        }
        return total;
    }

    /** How many rolls have completed. */
    public int rolls() {
        return data.rolls;
    }

    /** How many of those rolls ended on a jackpot. */
    public int jackpots() {
        return data.jackpots;
    }

    /** Kill counts keyed by {@link MythologicalCreature#name()}, unmodifiable. */
    public Map<String, Integer> killsByName() {
        return Collections.unmodifiableMap(data.kills);
    }

    /** Item totals keyed by parsed item name, unmodifiable. */
    public Map<String, Integer> drops() {
        return Collections.unmodifiableMap(data.drops);
    }

    /** When the first event was recorded, in epoch millis, or 0 when nothing has been. */
    public long firstRecordedAt() {
        return data.firstRecordedAt;
    }

    /** Whether there are changes not yet on disk. */
    public boolean dirty() {
        return dirty;
    }

    /** Clears every counter and marks the tally for saving. The file is not deleted. */
    public void reset() {
        data = new Data();
        touch();
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
            // First tick on which this tally is dirty: start the clock rather than writing now.
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
     * Writes now, whether or not the interval has elapsed. A no-op when nothing changed or when this
     * instance has no file.
     *
     * <p>Goes through a temporary file and a rename, which is what stops a crash mid-write from
     * creating the truncated file this class is elsewhere busy recovering from. A failure is recorded
     * in {@link #notes()} and leaves the tally dirty, so the next attempt retries it.
     */
    public void save() {
        if (!dirty || file == null) {
            return;
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomicMove) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            // Re-arm: the next change schedules its own interval from the moment it happens,
            // rather than inheriting a deadline that may already have passed.
            autosaveScheduled = false;
        } catch (IOException | RuntimeException cannotWrite) {
            note("diana stats could not be saved: " + cannotWrite);
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
        return "DianaStats[kills=" + totalKills()
                + ", rolls=" + data.rolls
                + ", jackpots=" + data.jackpots
                + ", items=" + data.drops.size() + "]";
    }

    private void touch() {
        long now = System.currentTimeMillis();
        if (data.firstRecordedAt == 0L) {
            data.firstRecordedAt = now;
        }
        data.lastRecordedAt = now;
        dirty = true;
    }

    private static void bump(Map<String, Integer> map, String key) {
        add(map, key, 1);
    }

    /** Saturating add, because an int tally must not wrap into a negative on a coin payout. */
    private static void add(Map<String, Integer> map, String key, int amount) {
        int existing = map.getOrDefault(key, 0);
        long sum = (long) existing + Math.max(1, amount);
        map.put(key, (int) Math.min(sum, Integer.MAX_VALUE));
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
}
