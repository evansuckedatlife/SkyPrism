package com.skyprism.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings an older config file up to the current schema before Gson ever binds it.
 *
 * <p><b>Why this runs on the JSON tree and not on a bound object.</b> The migrations
 * worth writing are exactly the ones a straight bind gets wrong: a renamed field binds
 * to nothing and the user's choice vanishes, and a field whose unit changed binds
 * perfectly and is off by a factor of fifty. Neither is visible once the JSON has been
 * turned into a {@link SkyPrismConfig}, because by then the evidence -- the old key, the
 * old magnitude -- has already been discarded. Rewriting the tree first means the bind
 * that follows sees a file that looks like it was written by this build.
 *
 * <p><b>The ladder.</b> Steps are registered one version apart and applied in sequence,
 * so a file three releases old walks 1 to 2 to 3 rather than needing a bespoke 1-to-3
 * path. Every step is written to be safe on a file that has already been partly
 * hand-edited: it acts only when the old key is present and the new one is not, so
 * running it twice, or on a file where the user already renamed the key themselves, is
 * a no-op rather than a data loss.
 *
 * <p>A file from a <em>newer</em> build than this one is left completely alone. There is
 * no honest way to downgrade a schema this code has never seen, and guessing would
 * corrupt settings that are still perfectly good in the newer build; the caller is told
 * instead, so it can warn rather than quietly rewrite.
 *
 * <p>All methods are static, side-effect-free on their inputs except for the tree they
 * are explicitly handed, and never throw.
 */
public final class ConfigMigrations {

    /** The oldest schema this build can still read. */
    public static final int OLDEST_SUPPORTED = 1;

    /** One step of the ladder: rewrite the tree from version {@code n} to {@code n + 1}. */
    @FunctionalInterface
    private interface Step {
        /**
         * @param root the whole config object, mutated in place
         * @return a short human-readable note for the load log, or null if it changed nothing
         */
        String apply(JsonObject root);
    }

    /** Keyed by the version being migrated <em>from</em>. */
    private static final Map<Integer, Step> STEPS = buildSteps();

    private ConfigMigrations() {
    }

    /**
     * What a migration did, so the load path can report it and the tests can assert it.
     *
     * @param root        the tree, rewritten in place and ready to bind
     * @param fromVersion the version the file claimed on arrival
     * @param toVersion   the version it claims now
     * @param notes       one line per step that actually changed something, in order
     */
    public record Result(JsonObject root, int fromVersion, int toVersion, List<String> notes) {

        public Result {
            notes = List.copyOf(notes);
        }

        /** True if the file was not already at the current schema. */
        public boolean migrated() {
            return fromVersion != toVersion;
        }

        /** True if the file came from a build newer than this one and was left untouched. */
        public boolean fromFuture() {
            return fromVersion > SkyPrismConfig.CONFIG_VERSION;
        }
    }

    /**
     * Walks a parsed config tree up to {@link SkyPrismConfig#CONFIG_VERSION}.
     *
     * @param root the parsed config object; mutated in place and also returned in the result
     * @return what happened; on a null or already-current tree, a result with no notes
     */
    public static Result migrate(JsonObject root) {
        if (root == null) {
            return new Result(new JsonObject(), SkyPrismConfig.CONFIG_VERSION,
                    SkyPrismConfig.CONFIG_VERSION, List.of());
        }

        int from = readVersion(root);
        if (from > SkyPrismConfig.CONFIG_VERSION) {
            return new Result(root, from, from, List.of(
                    "config is from a newer SkyPrism (v" + from + "); left untouched"));
        }

        var notes = new ArrayList<String>();
        int version = Math.max(from, OLDEST_SUPPORTED);
        while (version < SkyPrismConfig.CONFIG_VERSION) {
            Step step = STEPS.get(version);
            if (step == null) {
                // A gap in the ladder is a mistake in this class, not in the user's file.
                // Stepping over it keeps the load working; the note is the breadcrumb.
                notes.add("no migration registered for v" + version + "; assuming compatible");
            } else {
                String note = step.apply(root);
                if (note != null) {
                    notes.add(note);
                }
            }
            version++;
        }

        root.addProperty("configVersion", SkyPrismConfig.CONFIG_VERSION);
        return new Result(root, from, SkyPrismConfig.CONFIG_VERSION, notes);
    }

    /**
     * The schema version a tree claims.
     *
     * <p>A missing version means the current schema, not the oldest one. Every file
     * SkyPrism has ever written carries the field, so a file without it was hand-written
     * against whatever documentation the author had -- which is this build's field names.
     * Treating it as ancient would run migrations against modern keys for no reason.
     *
     * @param root a parsed config object, possibly null
     * @return the declared version, or {@link SkyPrismConfig#CONFIG_VERSION} if the field
     *         is absent or is not something that reads as a whole number
     */
    public static int readVersion(JsonObject root) {
        if (root == null) {
            return SkyPrismConfig.CONFIG_VERSION;
        }
        JsonElement raw = root.get("configVersion");
        if (raw == null || !raw.isJsonPrimitive()) {
            return SkyPrismConfig.CONFIG_VERSION;
        }
        JsonPrimitive p = raw.getAsJsonPrimitive();
        try {
            if (p.isNumber()) {
                return p.getAsInt();
            }
            if (p.isString()) {
                return Integer.parseInt(p.getAsString().trim());
            }
        } catch (RuntimeException notANumber) {
            return SkyPrismConfig.CONFIG_VERSION;
        }
        return SkyPrismConfig.CONFIG_VERSION;
    }

    private static Map<Integer, Step> buildSteps() {
        var steps = new LinkedHashMap<Integer, Step>();
        steps.put(1, ConfigMigrations::v1ToV2);
        steps.put(2, ConfigMigrations::v2ToV3);
        steps.put(3, ConfigMigrations::v3ToV4);
        return Map.copyOf(steps);
    }

    /**
     * v1 to v2, the two changes a plain bind could not have survived.
     *
     * <p><b>{@code levels.chroma} became {@code levels.chromaEnabled}.</b> The old name
     * read as a colour setting rather than a toggle and sat confusingly next to
     * {@code chromaMinLevel}. Bound as-is, the old key is simply ignored and a player who
     * had turned the shimmer on finds it off.
     *
     * <p><b>{@code diana.lootWindowTicks} became {@code diana.lootWindowMillis}.</b> v1
     * counted the loot window in Minecraft ticks, which is a unit the config screen could
     * not label sensibly and which stops meaning anything under a laggy server. This is
     * the dangerous kind of change: bound as-is the number would be accepted without
     * complaint and the window would be fifty times too short, so the value is multiplied
     * on the way across rather than merely moved.
     */
    private static String v1ToV2(JsonObject root) {
        var changes = new ArrayList<String>();

        JsonObject levels = childObject(root, "levels");
        if (levels != null && levels.has("chroma") && !levels.has("chromaEnabled")) {
            levels.add("chromaEnabled", levels.get("chroma"));
            levels.remove("chroma");
            changes.add("levels.chroma -> levels.chromaEnabled");
        }

        JsonObject diana = childObject(root, "diana");
        if (diana != null && diana.has("lootWindowTicks") && !diana.has("lootWindowMillis")) {
            Long ticks = asLong(diana.get("lootWindowTicks"));
            diana.remove("lootWindowTicks");
            if (ticks != null) {
                long millis = saturatingTimes(ticks, MILLIS_PER_TICK);
                diana.addProperty("lootWindowMillis", millis);
                changes.add("diana.lootWindowTicks " + ticks + " -> lootWindowMillis " + millis);
            } else {
                changes.add("dropped unreadable diana.lootWindowTicks");
            }
        }

        return changes.isEmpty() ? null : "v1->v2: " + String.join(", ", changes);
    }

    /**
     * v2 to v3: the two shimmer knobs the adapter used to hard-code become real settings.
     *
     * <p>This is the migration a plain bind gets wrong quietly rather than loudly. Up to
     * v2 the shimmer was always built at saturation {@value #V2_CHROMA_SATURATION} and
     * lightness {@value #V2_CHROMA_LIGHTNESS}, because there was nowhere in the file to
     * say otherwise. Bound as-is, an old file inherits whatever this build's defaults
     * happen to be -- which is harmless today only because the defaults were chosen to
     * match. The first release that retunes them would silently repaint the shimmer of
     * every player who upgraded, with nothing in their config to explain why. Writing the
     * old constants in makes what a v2 install was rendering explicit, so a later default
     * change reaches new installs and leaves existing ones alone.
     *
     * <p>The constants are frozen copies rather than references to
     * {@link SkyPrismConfig.LevelSettings#DEFAULT_CHROMA_SATURATION}: this step describes
     * what v2 <em>did</em>, which is history and must not move when the default does.
     *
     * <p>A file with no {@code levels} object at all is left alone. It was running on
     * defaults across the board, so there is no chosen appearance to preserve, and
     * inventing a group to hold two numbers would only make the file harder to read.
     * Each key is written only when absent, so a player who already added one keeps it.
     */
    private static String v2ToV3(JsonObject root) {
        JsonObject levels = childObject(root, "levels");
        if (levels == null) {
            return null;
        }

        var changes = new ArrayList<String>();
        if (!levels.has("chromaSaturation")) {
            levels.addProperty("chromaSaturation", V2_CHROMA_SATURATION);
            changes.add("levels.chromaSaturation pinned at " + V2_CHROMA_SATURATION);
        }
        if (!levels.has("chromaLightness")) {
            levels.addProperty("chromaLightness", V2_CHROMA_LIGHTNESS);
            changes.add("levels.chromaLightness pinned at " + V2_CHROMA_LIGHTNESS);
        }
        return changes.isEmpty() ? null : "v2->v3: " + String.join(", ", changes);
    }

    /**
     * v3 to v4: the slot machine stops being a Diana feature, so the Diana switches that used to
     * govern the whole feature have to be carried across to the ones that now do.
     *
     * <p><b>This is the migration that exists to protect a decision, not a value.</b> Up to v3
     * SkyPrism's slot machine had exactly one trigger, so {@code diana.enabled} was the mod's
     * answer to "do you want the slot machine at all" and {@code diana.suppressDropChatLines} was
     * its answer to "should the reels replace the chat lines". In v4 both fields govern the Diana
     * path alone, and the thirty-odd new sources answer to {@code loot.enabled} and
     * {@code loot.suppressDropChatLines} instead. Bound straight across, a player who had switched
     * the machine off in v3 would find it back -- spinning on slayer bosses, dungeon runs and tree
     * gifts they never asked it to watch. That is the sharpest edge of this whole release, because
     * the player is running the build right now and would experience it as the mod overriding them.
     *
     * <p>So a v3 file that said "off" is carried across as "off everywhere", and a v3 file that
     * said "hide the drop lines" keeps hiding them on the new sources too. Nothing else moves: the
     * per-source table starts empty, which means every new source sits at its shipped default, and
     * the Diana group is left exactly as it was because the Diana path still reads it.
     *
     * <p>Each key is written only when the new one is absent, so a file the player has already
     * hand-edited, or one that has been through this step before, is untouched. A v3 file with no
     * {@code diana} group at all was running on Diana's defaults, which means the machine was on
     * and the drop lines were visible -- both of which are also the v4 defaults -- so there is
     * nothing to preserve and the step reports no change.
     */
    private static String v3ToV4(JsonObject root) {
        JsonObject diana = childObject(root, "diana");
        if (diana == null) {
            return null;
        }

        var changes = new ArrayList<String>();

        Boolean wasEnabled = asBoolean(diana.get("enabled"));
        if (Boolean.FALSE.equals(wasEnabled) && !hasKey(root, "loot", "enabled")) {
            loot(root).addProperty("enabled", false);
            changes.add("the slot machine was switched off, so loot.enabled starts off too");
        }

        Boolean wasSuppressing = asBoolean(diana.get("suppressDropChatLines"));
        if (Boolean.TRUE.equals(wasSuppressing) && !hasKey(root, "loot", "suppressDropChatLines")) {
            loot(root).addProperty("suppressDropChatLines", true);
            changes.add("drop lines were hidden, so loot.suppressDropChatLines starts on");
        }

        return changes.isEmpty() ? null : "v3->v4: " + String.join(", ", changes);
    }

    /** The {@code loot} group, created empty if the file has none yet. */
    private static JsonObject loot(JsonObject root) {
        JsonObject existing = childObject(root, "loot");
        if (existing != null) {
            return existing;
        }
        var fresh = new JsonObject();
        root.add("loot", fresh);
        return fresh;
    }

    /** Whether {@code root.<group>.<key>} is present, whatever its value or shape. */
    private static boolean hasKey(JsonObject root, String group, String key) {
        JsonObject child = childObject(root, group);
        return child != null && child.has(key);
    }

    /**
     * A primitive read as a boolean, however it was spelled; null if it cannot be.
     *
     * <p>Strings are accepted because a hand-edited file quoting {@code "false"} is common enough
     * to be worth honouring, and because reading that as "not false" would silently re-arm the
     * machine on a player who had switched it off -- the exact loss this step exists to prevent.
     */
    private static Boolean asBoolean(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = element.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isString()) {
            String text = p.getAsString().trim();
            if (text.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (text.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** The shimmer saturation every build up to schema v2 hard-coded. */
    private static final double V2_CHROMA_SATURATION = 0.90;

    /** The shimmer lightness every build up to schema v2 hard-coded. */
    private static final double V2_CHROMA_LIGHTNESS = 0.62;

    /** Minecraft runs at twenty ticks a second. */
    private static final long MILLIS_PER_TICK = 50L;

    /**
     * A unit conversion that clamps at the ends of {@code long} instead of wrapping past
     * them.
     *
     * <p>Wrapping here was worse than it looks. A hand-edited file asking for an absurdly
     * large tick count multiplied past {@link Long#MAX_VALUE}, came out negative, and the
     * sanitiser's clamp then snapped that negative to the <em>shortest</em> legal loot
     * window -- turning "far too long" into "as short as possible", which is the opposite
     * of what the file asked for and leaves no trace of how it happened. Saturating means
     * an absurd number stays absurd in the direction it was written, and the clamp that
     * follows lands on the right end of the range.
     */
    private static long saturatingTimes(long value, long factor) {
        try {
            return Math.multiplyExact(value, factor);
        } catch (ArithmeticException overflow) {
            return value > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** The named child if it is an object; null if it is missing, null, or some other shape. */
    private static JsonObject childObject(JsonObject root, String name) {
        JsonElement child = root.get(name);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
    }

    /** A primitive read as a whole number, however it was spelled; null if it cannot be. */
    private static Long asLong(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = element.getAsJsonPrimitive();
        try {
            if (p.isNumber()) {
                return p.getAsLong();
            }
            if (p.isString()) {
                return Long.parseLong(p.getAsString().trim());
            }
        } catch (RuntimeException notANumber) {
            return null;
        }
        return null;
    }
}
