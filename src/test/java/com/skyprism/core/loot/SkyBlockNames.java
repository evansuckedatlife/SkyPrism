package com.skyprism.core.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.skyprism.core.util.TextClean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every SkyBlock item name the mod says out loud, and the snapshot of real ones it is checked
 * against.
 *
 * <h2>The bug this exists to make impossible</h2>
 *
 * <p>The slot machine names drops in two places -- the per-source jackpot lists in
 * {@link LootSourceRegistry} and the sprite rows in {@code drop_symbols.json} -- and until this
 * class went in, nothing anywhere checked that those names were names of things. They were
 * assembled by reading wikis and inferring, and roughly a hundred of them were wrong: "Chimera" is
 * an enchantment and not an item, "Enchanted Ancient Claws" is a plural nobody types, there is no
 * Ashfang armour set at all, no Soul Esperance, no Reindrake Fragment, no golden goblin egg. Every
 * one survived several review passes, because a fake name compiles, ships, and shows up on a
 * spinning reel where only a SkyBlock player notices it. The fix is not more careful reading. It is
 * a build failure.</p>
 *
 * <h2>Where the truth comes from</h2>
 *
 * <p>{@code src/test/resources/skyblock/skyblock_item_names.tsv} is a snapshot generated from the
 * NotEnoughUpdates item repository -- the community's canonical SkyBlock item database, 8,755
 * items, each carrying the display name Hypixel actually prints. {@link #main} regenerates it; the
 * file's own header records the repository path and the date it was read. It holds only the names
 * the mod uses, not all 7,600, because a snapshot nobody can read is a snapshot nobody checks.</p>
 *
 * <p>Four families of name are expanded when the snapshot is built, because NEU stores them under
 * a form that is not what chat prints:</p>
 * <ul>
 *   <li>{@code PET} -- NEU writes a pet as {@code [Lvl {LVL}] Griffin}; the drop is "Griffin".</li>
 *   <li>{@code ENCHANT} -- every enchanted book displays as "Enchanted Book"; the enchantment's own
 *       name is in its lore ("Chimera III"). Both the tiered form and the bare stem are recorded,
 *       because {@code DropSymbols} retries a lookup with a trailing roman numeral removed but
 *       cannot add one.</li>
 *   <li>{@code TROPHY} -- a trophy fish is four items, "Golden Fish BRONZE" through DIAMOND; the
 *       species name is what the mod lists.</li>
 *   <li>{@code ITEM} -- everything else, plus the roman-numeral stem of anything tiered (the runes
 *       are "Bite Rune I" and friends).</li>
 * </ul>
 *
 * <h2>Why it reads the source tree rather than the classpath</h2>
 *
 * <p>Both files this checks are edited by hand and both are also copied into a build output
 * directory. Reading the copies would let the test pass against yesterday's data while today's
 * file carries a fake name -- which is the same failure mode as not testing at all, only quieter.
 * So it finds the repository root and reads the files a person actually edits.</p>
 */
final class SkyBlockNames {

    /** The snapshot, relative to the repository root. */
    static final String SNAPSHOT = "src/test/resources/skyblock/skyblock_item_names.tsv";

    /** The sprite table, relative to the repository root. */
    static final String DROP_SYMBOLS = "src/main/resources/assets/skyprism/drop_symbols.json";

    /**
     * The filler strip's generic top-up, which lives in {@code mc/} and so cannot be imported here.
     *
     * <p>Read out of the source file as text rather than duplicated as a constant. A duplicate
     * would be a second copy of the very thing this class exists to stop drifting: the list would
     * be checked and the real one would not. Ten strings scrolling on every under-researched
     * source is the widest reach any name in the mod has -- the first of them read "Enchanted
     * Gold", the internal id, instead of "Enchanted Gold Ingot" -- so it is worth the odd reach.</p>
     */
    static final String FILLER_STRIP = "src/main/java/com/skyprism/mc/hud/FillerStrip.java";

    private static final Pattern GENERIC_BLOCK = Pattern.compile(
            "GENERIC\\s*=\\s*List\\.of\\((?<body>[^;]*?)\\);", Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /** {@code DropSymbols.TRAILING_ROMAN}, mirrored so the test retries exactly as the mod does. */
    private static final Pattern TRAILING_ROMAN =
            Pattern.compile("^(?<rest>.+?)\\s+(?:x{0,3})(?:ix|iv|v?i{0,3})$");

    /** A leading run of decoration NEU puts on some names, e.g. the diamond on every rune. */
    private static final Pattern LEADING_DECORATION = Pattern.compile("^[^\\p{L}\\p{N}]+");

    private SkyBlockNames() {
    }

    // ==================================================================================
    //  One name the mod uses, and where it says it
    // ==================================================================================

    /**
     * A spelling the mod ships, with every place it appears.
     *
     * @param spelling the name exactly as written in the source
     * @param sites    the jackpot lists, {@code drop_symbols.json} or {@code FillerStrip.GENERIC}
     *                 it appears in; never empty, and what a failure message quotes so a
     *                 maintainer knows which file to open
     */
    record Use(String spelling, Set<String> sites) {
        Use {
            sites = Set.copyOf(sites);
        }

        @Override
        public String toString() {
            return "\"" + spelling + "\" (" + String.join(", ", new java.util.TreeSet<>(sites)) + ")";
        }
    }

    /** One snapshot row: what kind of thing the name is, and the NEU id that proves it. */
    record Known(String kind, String name, String internalName) {
    }

    // ==================================================================================
    //  Locating the repository
    // ==================================================================================

    /**
     * The repository root, found by walking up from the working directory and, failing that, from
     * this class's own location.
     *
     * <p>Gradle runs the unit tests with the working directory set to the version node
     * ({@code versions/26.2}), so the first walk finds it two levels up; a bare {@code javac} run
     * starts at the root already. The second walk covers an IDE that sets neither.</p>
     *
     * @throws IllegalStateException when neither walk finds it, rather than silently checking
     *         nothing
     */
    static Path repoRoot() {
        Path fromCwd = walkUp(Path.of("").toAbsolutePath());
        if (fromCwd != null) {
            return fromCwd;
        }
        try {
            Path source = Path.of(SkyBlockNames.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path fromClasses = walkUp(source.toAbsolutePath());
            if (fromClasses != null) {
                return fromClasses;
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // Fall through to the failure below; a missing code source is not more informative
            // than the message it carries.
        }
        throw new IllegalStateException(
                "cannot find the SkyPrism source tree from " + Path.of("").toAbsolutePath()
                        + ". This test reads the files a person edits, not the copies a build "
                        + "makes, so that it cannot pass against stale data.");
    }

    private static Path walkUp(Path start) {
        for (Path at = start; at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve(DROP_SYMBOLS))) {
                return at;
            }
        }
        return null;
    }

    // ==================================================================================
    //  Every name the mod uses
    // ==================================================================================

    /**
     * Every distinct name the mod can put on screen, keyed by its normalised form.
     *
     * <p>The three sources are the whole of it: a reel strip is its source's jackpot list plus the
     * generic top-up, and every one of those has to resolve through a {@code drop_symbols.json}
     * row, so covering the three covers every string a player can read off the widget.</p>
     */
    static Map<String, Use> namesInUse(Path root) {
        Map<String, Set<String>> sites = new TreeMap<>();
        Map<String, String> spellings = new LinkedHashMap<>();

        for (LootSource source : LootSource.values()) {
            for (String name : LootSourceRegistry.info(source).jackpotItems()) {
                record(sites, spellings, name, source.name());
            }
        }
        for (String name : dropSymbolNames(root)) {
            record(sites, spellings, name, "drop_symbols.json");
        }
        for (String name : fillerStripGeneric(root)) {
            record(sites, spellings, name, "FillerStrip.GENERIC");
        }

        Map<String, Use> uses = new TreeMap<>();
        sites.forEach((key, where) -> uses.put(key, new Use(spellings.get(key), where)));
        return uses;
    }

    private static void record(Map<String, Set<String>> sites, Map<String, String> spellings,
            String name, String site) {
        String key = key(name);
        if (key.isEmpty()) {
            return;
        }
        sites.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(site);
        spellings.putIfAbsent(key, name.strip());
    }

    /** Every {@code "names"} entry in the sprite table, in file order. */
    static List<String> dropSymbolNames(Path root) {
        JsonObject table = readJson(root.resolve(DROP_SYMBOLS)).getAsJsonObject();
        List<String> names = new ArrayList<>();
        for (JsonElement entry : table.getAsJsonArray("entries")) {
            JsonArray row = entry.getAsJsonObject().getAsJsonArray("names");
            for (JsonElement name : row) {
                names.add(name.getAsString());
            }
        }
        return List.copyOf(names);
    }

    /** {@code FillerStrip.GENERIC}, read out of the source file; see {@link #FILLER_STRIP}. */
    static List<String> fillerStripGeneric(Path root) {
        String source = readText(root.resolve(FILLER_STRIP));
        Matcher block = GENERIC_BLOCK.matcher(source);
        if (!block.find()) {
            throw new IllegalStateException(
                    "FillerStrip.GENERIC is no longer a `GENERIC = List.of(...)` initialiser, so "
                            + "the names it scrolls on every under-researched source are no longer "
                            + "checked. Update the pattern in SkyBlockNames rather than leaving "
                            + "them unchecked.");
        }
        List<String> names = new ArrayList<>();
        Matcher literal = STRING_LITERAL.matcher(block.group("body"));
        while (literal.find()) {
            names.add(literal.group(1));
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("FillerStrip.GENERIC parsed to no names at all");
        }
        return List.copyOf(names);
    }

    // ==================================================================================
    //  The snapshot
    // ==================================================================================

    /** The snapshot, keyed by normalised name. */
    static Map<String, Known> snapshot(Path root) {
        Map<String, Known> known = new TreeMap<>();
        for (String line : readText(root.resolve(SNAPSHOT)).split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                throw new IllegalStateException("malformed snapshot row: " + line);
            }
            known.put(key(parts[1]), new Known(parts[0], parts[1], parts[2]));
        }
        return known;
    }

    /**
     * Whether {@code key} names something real, trying the same retries the render path does.
     *
     * <p>Three rules, and no more, because every extra rule is a hole a fake name can hide in:</p>
     * <ol>
     *   <li>the name itself;</li>
     *   <li>the name with a trailing roman numeral removed -- {@code DropSymbols} does exactly
     *       this, so one row answers every tier of an enchantment;</li>
     *   <li>the name with a plural ending removed. The sprite table deliberately carries plural
     *       spellings ("16 Ancient Claws") beside the singular, and a plural of a real item is not
     *       an invented name.</li>
     * </ol>
     */
    static Known resolve(Map<String, Known> known, String key) {
        Known hit = known.get(key);
        if (hit != null) {
            return hit;
        }
        Matcher roman = TRAILING_ROMAN.matcher(key);
        if (roman.matches()) {
            hit = known.get(roman.group("rest"));
            if (hit != null) {
                return hit;
            }
        }
        for (String ending : new String[] {"es", "s"}) {
            if (key.endsWith(ending)) {
                hit = known.get(key.substring(0, key.length() - ending.length()));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * The comparison form: {@code DropSymbols.normalise} exactly -- formatting codes stripped,
     * whitespace collapsed, lower case -- plus NEU's decorative prefixes removed, because NEU
     * stores "&#9670; Bite Rune I" and Hypixel prints the diamond as part of the colour run rather
     * than the name.
     */
    static String key(String name) {
        String cleaned = TextClean.clean(name);
        if (cleaned == null) {
            return "";
        }
        return LEADING_DECORATION.matcher(cleaned).replaceFirst("").strip()
                .toLowerCase(Locale.ROOT);
    }

    // ==================================================================================
    //  Regenerating the snapshot
    // ==================================================================================

    /**
     * Rewrites the snapshot from a NotEnoughUpdates repository checkout.
     *
     * <p>Run it by hand after adding a name, never automatically: a generator wired into the build
     * would re-bless whatever the build happened to contain, which is the opposite of the point.
     * It refuses to write a row for a name it cannot find, so a fake name comes back as a missing
     * row and then as a failing test.</p>
     *
     * <pre>{@code
     * java -cp <test classes>:<gson> com.skyprism.core.loot.SkyBlockNames \
     *     "<...>/config/notenoughupdates/repo"
     * }</pre>
     *
     * @param args the NEU repository directory; the one that contains {@code items/}
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: SkyBlockNames <notenoughupdates-repo-dir>");
            System.exit(2);
        }
        Path neu = Path.of(args[0]);
        Path root = repoRoot();
        Map<String, Known> everything = readNeu(neu);
        Map<String, Use> used = namesInUse(root);

        Map<String, Known> keep = new TreeMap<>();
        List<String> unresolved = new ArrayList<>();
        used.forEach((key, use) -> {
            Known hit = resolve(everything, key);
            if (hit == null) {
                unresolved.add(use.toString());
            } else {
                keep.put(key(hit.name()), hit);
            }
        });

        StringBuilder out = new StringBuilder();
        out.append("# Real SkyBlock item names, for SkyBlockNameSnapshotTest.\n")
                .append("#\n")
                .append("# Generated by com.skyprism.core.loot.SkyBlockNames#main from the\n")
                .append("# NotEnoughUpdates item repository -- the community's canonical SkyBlock\n")
                .append("# item database -- read at\n")
                .append("#     ").append(neu.toAbsolutePath().normalize()).append('\n')
                .append("# on ").append(java.time.LocalDate.now()).append(", from ")
                .append(everything.size()).append(" distinct names across its items/ directory.\n")
                .append("#\n")
                .append("# Only the names the mod actually uses are kept. Regenerate after adding\n")
                .append("# one; a name this file does not carry fails the build unless it is on\n")
                .append("# the test's allowlist with a reason.\n")
                .append("#\n")
                .append("# <kind>\\t<display name>\\t<NEU internal name>\n")
                .append("# ITEM     an ordinary item\n")
                .append("# PET      NEU stores it as \"[Lvl {LVL}] X\"; the drop is X\n")
                .append("# ENCHANT  an enchanted book; the name is in its lore, not its title\n")
                .append("# TROPHY   a trophy fish species, which ships as four tiered items\n");
        keep.values().forEach(k -> out.append(k.kind()).append('\t')
                .append(k.name()).append('\t').append(k.internalName()).append('\n'));
        Path target = root.resolve(SNAPSHOT);
        Files.createDirectories(target.getParent());
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);

        System.out.println("wrote " + keep.size() + " rows to " + target);
        if (!unresolved.isEmpty()) {
            System.out.println(unresolved.size() + " names resolve to nothing in NEU and were NOT "
                    + "written; each must be corrected or allowlisted:");
            unresolved.forEach(name -> System.out.println("  " + name));
        }
    }

    /** Every name NEU knows, with the four naming families expanded; see the class javadoc. */
    private static Map<String, Known> readNeu(Path neu) throws IOException {
        Path items = neu.resolve("items");
        if (!Files.isDirectory(items)) {
            throw new IllegalArgumentException("no items/ directory under " + neu);
        }
        Map<String, Known> known = new TreeMap<>();
        try (Stream<Path> files = Files.list(items)) {
            files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .forEach(file -> readNeuItem(known, file));
        }
        return known;
    }

    private static void readNeuItem(Map<String, Known> known, Path file) {
        JsonElement parsed;
        try {
            parsed = readJson(file);
        } catch (RuntimeException malformed) {
            return;
        }
        if (!parsed.isJsonObject()) {
            return;
        }
        JsonObject item = parsed.getAsJsonObject();
        if (!item.has("displayname") || !item.has("internalname")) {
            return;
        }
        String cleaned = TextClean.clean(item.get("displayname").getAsString());
        String internal = item.get("internalname").getAsString();
        if (cleaned == null || cleaned.isBlank()) {
            return;
        }

        // Before the decoration strip, not after: a pet's display name opens with a bracket, which
        // the decoration pattern would eat along with the level marker it introduces.
        Matcher pet = Pattern.compile("^\\[Lvl \\{LVL}]\\s*(?<name>.+)$").matcher(cleaned);
        if (pet.matches()) {
            put(known, "PET", pet.group("name"), internal.split(";")[0]);
            return;
        }

        String display = LEADING_DECORATION.matcher(cleaned).replaceFirst("").strip();
        if ("Enchanted Book".equals(display)) {
            String enchantment = enchantmentInLore(item);
            if (enchantment != null) {
                put(known, "ENCHANT", enchantment, internal);
                put(known, "ENCHANT", stripRoman(enchantment), internal);
                return;
            }
            // The plain, un-enchanted book: a real item and a real drop line.
            put(known, "ITEM", display, internal);
            return;
        }
        Matcher trophy = Pattern.compile("^(?<name>.+?)\\s+(BRONZE|SILVER|GOLD|DIAMOND)$")
                .matcher(display);
        if (trophy.matches() && internal.matches(".*_(BRONZE|SILVER|GOLD|DIAMOND)$")) {
            put(known, "TROPHY", trophy.group("name"),
                    internal.replaceFirst("_(BRONZE|SILVER|GOLD|DIAMOND)$", ""));
            put(known, "TROPHY", display, internal);
            return;
        }
        put(known, "ITEM", display, internal);
        put(known, "ITEM", stripRoman(display), internal);
    }

    private static String enchantmentInLore(JsonObject item) {
        if (!item.has("lore") || !item.get("lore").isJsonArray()) {
            return null;
        }
        Pattern named = Pattern.compile("^[\\p{L}'\\- ]+ (?:I|II|III|IV|V|VI|VII|VIII|IX|X)$");
        for (JsonElement line : item.getAsJsonArray("lore")) {
            String text = TextClean.clean(line.getAsString());
            if (text != null && named.matcher(text).matches()) {
                return text;
            }
        }
        return null;
    }

    private static String stripRoman(String name) {
        Matcher roman = TRAILING_ROMAN.matcher(name.toLowerCase(Locale.ROOT));
        return roman.matches() ? name.substring(0, roman.end("rest")) : name;
    }

    private static void put(Map<String, Known> known, String kind, String name, String internal) {
        String key = key(name);
        if (!key.isEmpty()) {
            known.putIfAbsent(key, new Known(kind, name.strip(), internal));
        }
    }

    // ==================================================================================
    //  Small IO helpers
    // ==================================================================================

    private static JsonElement readJson(Path file) {
        return JsonParser.parseString(readText(file));
    }

    private static String readText(Path file) {
        try {
            return Files.readString(Objects.requireNonNull(file), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException("cannot read " + file, failed);
        }
    }
}
