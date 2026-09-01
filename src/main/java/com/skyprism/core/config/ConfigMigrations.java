package com.skyprism.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.skyprism.core.level.BracketTable;
import com.skyprism.core.level.GradientRamp;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.PalettePresets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p><b>Most rungs exist because the shape changed. One exists because a default did.</b>
 * {@link ConfigCodec} serialises every field, so a setting a player has never touched is
 * still written into their file in full, spelled out, and Gson binds it back over the
 * initialiser on the next load. That is what makes an added field free -- and it is also
 * what makes a <em>changed</em> default land on new installs only. When a default changes
 * because existing players asked for it to, the file on disk is the thing standing in the
 * way. The version is the only evidence in that file of which build's opinion the value
 * was: a value equal to the old default in a file written before the change was almost
 * certainly never chosen, and the same value in a file written after it certainly was.
 * {@link #v4ToV5(JsonObject)} is that rung, and it is deliberately the narrowest reading
 * of "never chosen" that still catches an untouched install.
 *
 * <p><b>The ladder.</b> Steps are registered one version apart and applied in sequence,
 * so a file three releases old walks 1 to 2 to 3 rather than needing a bespoke 1-to-3
 * path. Every step is written to be safe on a file that has already been partly
 * hand-edited: it acts only on evidence that the value in front of it is still the one
 * this build wrote -- the old key present and the new one absent, or a value still equal
 * to the default it was shipped with -- so running it twice, or on a file where the user
 * already made the change themselves, is a no-op rather than a data loss.
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
        steps.put(4, ConfigMigrations::v4ToV5);
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

    /**
     * v4 to v5: the level palette default changed, so a file that never chose a palette is
     * moved onto the new one and a file that did choose is left completely alone.
     *
     * <p><b>Why a migration is the only way this reaches anyone.</b> Nothing about the JSON
     * shape moved here. What moved is the shipped default: SkyPrism drew a per-level
     * gradient up to v4 and draws {@link PalettePresets#defaultBrackets()} from v5.
     * {@link ConfigCodec} writes every field, so every config on disk already spells out
     * {@code "mode": "GRADIENT"} and {@code "gradientPreset": "spectrum"}, and Gson binds
     * both over the new initialisers before anything reads them. Flipping the defaults
     * alone would therefore have reached new installs and nobody else -- and the change was
     * asked for by three people who are already running the mod. They would have kept the
     * exact palette they objected to while strangers got the fix.
     *
     * <p><b>What counts as "never chose".</b> Only a palette that is the pre-v5 default in
     * every one of its four parts at once: the mode, the gradient name, the custom stops,
     * and the bracket table, each either absent or still holding the value v4 shipped. One
     * edited stop anywhere is enough to stop this step, even a stop that is inert because
     * the preset is not {@code custom} -- someone who has been in the palette settings
     * moving colours around has an opinion about their palette, and this step has no
     * business guessing which parts of it they meant.
     *
     * <p><b>{@code mode: "BRACKETS"} is deliberately not migrated either</b>, even though
     * the new default is bracket mode. A v4 user in bracket mode chose it, and what they
     * chose it for may well have been the twenty-level {@link PalettePresets#fineBrackets()}
     * table exactly as it stands. They get the new table from the config screen's reset
     * arrow, on purpose, rather than from underneath them.
     *
     * <p><b>The one thing this step writes in the leave-alone case.</b> A v4 file with a
     * chosen palette but no {@code mode} key was in {@link LevelColorMode#GRADIENT}, because
     * that is what absent meant in v4 -- and after the flip, absent means
     * {@link LevelColorMode#BRACKETS}. Left untouched, that file would lose the gradient it
     * was drawing on the strength of a key it never had. So the old default is written in
     * explicitly. Nothing else in the group moves.
     *
     * <p><b>Why the new table is read live rather than frozen the way v2's constants are.</b>
     * {@link #v2ToV3(JsonObject)} pins numbers describing what an old install
     * <em>rendered</em>, which is history and must never move again. This step does the
     * opposite job: it hands a file the palette this build ships. If a later release retunes
     * {@link PalettePresets#defaultBrackets()} it will need its own rung and its own
     * judgement about whose table to touch, and until then a migrated file and a fresh
     * install must agree -- which they only do if both read the same source.
     * {@link PalettePresets#fineBrackets()} on the recognising side is a shipped preset with
     * its shape pinned by its own tests, and it is what v4 seeded {@code brackets} from.
     */
    private static String v4ToV5(JsonObject root) {
        JsonObject levels = childObject(root, "levels");
        if (levels == null) {
            return null;
        }

        boolean saysAnything = levels.has("mode") || levels.has("gradientPreset")
                || levels.has("customStops") || levels.has("brackets");
        if (!saysAnything) {
            // Nothing on disk is overriding the initialisers, so the bind that follows
            // already hands this file the v5 palette. Writing it in would be noise.
            return null;
        }

        boolean untouched = isV4Mode(levels.get("mode"))
                && isV4Preset(levels.get("gradientPreset"))
                && isV4Stops(levels.get("customStops"))
                && isV4Table(levels.get("brackets"));

        if (!untouched) {
            if (!levels.has("mode")) {
                levels.addProperty("mode", LevelColorMode.GRADIENT.name());
                return "v4->v5: the shipped level palette changed; kept yours, and pinned"
                        + " mode GRADIENT so the new default cannot move it";
            }
            return "v4->v5: the shipped level palette changed; kept yours";
        }

        levels.addProperty("mode", LevelColorMode.BRACKETS.name());
        levels.add("brackets", bracketsAsJson(PalettePresets.defaultBrackets().brackets()));
        return "v4->v5: your level palette was still the old shipped default, so it moved"
                + " to the new one (" + PalettePresets.defaultBrackets().brackets().size()
                + " brackets)";
    }

    /** A bracket list written the way Gson writes {@link BracketTable.Bracket}. */
    private static JsonArray bracketsAsJson(List<BracketTable.Bracket> brackets) {
        var array = new JsonArray();
        for (BracketTable.Bracket b : brackets) {
            var entry = new JsonObject();
            entry.addProperty("minLevel", b.minLevel());
            entry.addProperty("rgb", b.rgb());
            array.add(entry);
        }
        return array;
    }

    /** Absent, or v4's default mode however it was spelled. */
    private static boolean isV4Mode(JsonElement element) {
        if (element == null) {
            return true;
        }
        String name = asTrimmedString(element);
        return name != null && name.equalsIgnoreCase(V4_DEFAULT_MODE);
    }

    /**
     * Absent, or v4's default gradient name, normalised the way the sanitiser normalises a
     * preset so a file that says {@code "Spectrum"} is recognised as the untouched default
     * it plainly is.
     */
    private static boolean isV4Preset(JsonElement element) {
        if (element == null) {
            return true;
        }
        String name = asTrimmedString(element);
        return name != null
                && name.toLowerCase(Locale.ROOT).replace(' ', '_').equals(V4_DEFAULT_PRESET);
    }

    /** Absent, or exactly the stop list a v4 install seeded {@code customStops} from. */
    private static boolean isV4Stops(JsonElement element) {
        if (element == null) {
            return true;
        }
        List<GradientRamp.Stop> shipped = PalettePresets.defaultRamp().stops();
        JsonArray array = asArrayOfSize(element, shipped.size());
        if (array == null) {
            return false;
        }
        for (int i = 0; i < shipped.size(); i++) {
            JsonObject entry = asObject(array.get(i));
            if (entry == null
                    || !isNumber(entry.get("level"), shipped.get(i).level())
                    || !isNumber(entry.get("rgb"), shipped.get(i).rgb())) {
                return false;
            }
        }
        return true;
    }

    /** Absent, or exactly the table a v4 install seeded {@code brackets} from. */
    private static boolean isV4Table(JsonElement element) {
        if (element == null) {
            return true;
        }
        List<BracketTable.Bracket> shipped = PalettePresets.fineBrackets().brackets();
        JsonArray array = asArrayOfSize(element, shipped.size());
        if (array == null) {
            return false;
        }
        for (int i = 0; i < shipped.size(); i++) {
            JsonObject entry = asObject(array.get(i));
            if (entry == null
                    || !isNumber(entry.get("minLevel"), shipped.get(i).minLevel())
                    || !isNumber(entry.get("rgb"), shipped.get(i).rgb())) {
                return false;
            }
        }
        return true;
    }

    /** The element as an array of exactly {@code size} entries, or null if it is anything else. */
    private static JsonArray asArrayOfSize(JsonElement element, int size) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        return array.size() == size ? array : null;
    }

    /** The element as an object, or null if it is missing or some other shape. */
    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Whether the element is a whole number equal to {@code expected}. */
    private static boolean isNumber(JsonElement element, int expected) {
        Long value = asLong(element);
        return value != null && value == expected;
    }

    /** A primitive read as trimmed text, however it was spelled; null if it is not text. */
    private static String asTrimmedString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = element.getAsJsonPrimitive();
        return p.isString() ? p.getAsString().trim() : null;
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

    /** The level colour mode every build up to schema v4 shipped as its default. */
    private static final String V4_DEFAULT_MODE = "GRADIENT";

    /**
     * The gradient every build up to schema v4 shipped as its default, frozen as a literal
     * rather than read off {@link PalettePresets#DEFAULT_PRESET_NAME}: this is a fact about
     * what v4 shipped, and it must not follow the constant if a later release renames or
     * repoints the default ramp.
     */
    private static final String V4_DEFAULT_PRESET = "spectrum";

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
