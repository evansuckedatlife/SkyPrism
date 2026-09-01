package com.skyprism.mc.hud;

import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.mc.symbols.DropSymbols;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The symbols a reel scrolls while it is still spinning, for one {@link LootSource}.
 *
 * <h2>Why this is per source and used to be one array</h2>
 *
 * <p>The machine shipped Diana-only, so a single hand-written strip of Diana drop names was
 * exactly right: a player glancing at a spinning reel saw Griffin Feathers and Ancient Claws and
 * knew without being told what kind of machine they were looking at. Going SkyBlock-wide made that
 * one list wrong nearly everywhere. A Minos Champion roll scrolled a Control Switch -- a Crystal
 * Hollows mining item that has never been Diana loot, and was on the list only because nobody had
 * revisited it -- a fishing roll scrolled Daedalus Sticks, and a slayer roll scrolled Griffin
 * Feathers. The strip's whole job is to say what this machine pays, and it was saying "Diana" on
 * all sixty-four of them.</p>
 *
 * <h2>Where the names come from, and why none of them are invented</h2>
 *
 * <p>{@link LootSourceRegistry#dropPool} carries, per source, everything that source actually pays
 * -- transcribed from the wiki, every name pinned to a real SkyBlock item by
 * {@code SkyBlockNameSnapshotTest} and to a sprite of its own by {@code DropSymbolsMcTest}. So a
 * strip is that pool and nothing else, in the pool's own case-insensitive order so the drum reads
 * the same in every JVM.</p>
 *
 * <p>It used to be the narrower {@code jackpotItems()} list, which is the drops worth a
 * three-of-a-kind flourish -- and that list is written to <em>exclude</em> the commonest drops,
 * because celebrating a Griffin Feather that falls off two thirds of all burrow treasure would
 * empty the celebration of meaning. Correct for the flourish, wrong for the drum: it deleted from
 * Diana's reel the two items a Diana player would name first. The two questions now have two
 * lists.</p>
 *
 * <h2>Why a strip is a window and not the whole pool</h2>
 *
 * <p>A roll shows twenty-one consecutive slots of the drum and no more -- the arithmetic is on
 * {@link #WINDOW} -- so past that length, adding a symbol lowers the odds of every other symbol
 * appearing. That is the reported bug, stated as a number: the Chimera book has always been on
 * Diana's strip and a player who has killed hundreds of Inquisitors had never seen it, because it
 * was one unremarkable book among sixteen distinctive sprites for one 150 ms cell in one column.
 * Filling the pool out to all thirty of the ritual's drops without capping the strip would have
 * dropped it to seven rolls in ten. So the pool is complete, the strip is at most twenty-one of it,
 * and {@link #SIGNATURES} pins the handful that must be in every window.</p>
 *
 * <p>Sources whose loot table nobody could verify carry an empty jackpot list, and a source with
 * two names would scroll the same two pictures round and round -- which reads as a stalled reel
 * rather than as motion. Those are topped up from {@link #GENERIC}, and only as far as
 * {@link #MIN_LENGTH}: enchanted materials, which every content area in SkyBlock actually pays and
 * which therefore claim nothing about the source. That is the same rule, and the same reasoning,
 * {@code SimulatedLoot}'s filler already follows -- inventing a plausible-sounding item name for a
 * content area nobody researched is how a cosmetic starts teaching people things that are not
 * true.</p>
 *
 * <h2>The performance contract</h2>
 *
 * <p>One instance per source, all of them built at class-init and held in an array indexed by
 * {@link LootSource#ordinal()}, so {@link #of} is a bounds check and a load. The names array is
 * built once and never rewritten; {@link #icons} allocates at most one array per source for the
 * whole session and refreshes its contents in place. Nothing on the spinning path allocates.</p>
 */
final class FillerStrip {

    /**
     * How many symbols a strip needs before it stops looking like a stalled reel.
     *
     * <p>Three windows show three rows each and the per-column term in
     * {@code SlotMachineHud.drawSpinningReel} offsets each drum by three, so the nine cells on
     * screen at any instant span roughly nine consecutive indices of the strip. Ten is the
     * smallest number that keeps one symbol out of two windows at once, and it is exactly what
     * the single global strip was.</p>
     */
    static final int MIN_LENGTH = 10;

    /**
     * How many symbols a strip may hold before a roll stops being able to show all of them.
     *
     * <p>This is arithmetic, not taste. {@code SlotMachineHud} advances a reel one strip index
     * every {@code STRIP_CELL_MILLIS} (150), offsets each column by {@code REEL_STRIP_OFFSET_MILLIS}
     * (50) and by three indices, and the reels lock at {@code spinMillis} (1200) plus
     * {@code lockStaggerMillis} (250) each. Working the three columns out:</p>
     *
     * <pre>
     *   reel 0 shows strip slots  -1 .. 9    (11)
     *   reel 1 shows strip slots   2 .. 14   (13)
     *   reel 2 shows strip slots   5 .. 19   (15)
     *   union                     -1 .. 19   (21 consecutive indices)
     * </pre>
     *
     * <p>So a roll shows exactly twenty-one consecutive slots of the drum. A strip of twenty-one or
     * fewer therefore puts <em>every</em> symbol on screen at least once in every roll; a strip of
     * thirty shows twenty-one of them and leaves which nine to the wall clock. That is the whole of
     * the reported bug: a player who has killed hundreds of Minos Inquisitors had never once seen
     * the Chimera book scroll past. Filling Diana's pool out to the thirty things the ritual
     * actually pays would have made that worse, not better -- so the pool grew and the strip is a
     * window onto it, capped here, with {@link #SIGNATURES} pinned inside every window.</p>
     */
    static final int WINDOW = 21;

    /**
     * How stale a resolved sprite may be before it is looked up again.
     *
     * <p>Long enough that the cost is nothing -- a dozen map probes twice a second, and only while
     * a reel is spinning -- and short enough that a symbol learned off a drop the player just
     * picked up is wearing its real art by the next spin rather than the next session.</p>
     */
    private static final long REFRESH_MILLIS = 500L;

    /**
     * The top-up for a source with too few verified drops of its own.
     *
     * <p>Deliberately generic rather than plausible-per-source, and deliberately drawn from the
     * enchanted materials every skill in SkyBlock produces: they carry no claim about where the
     * roll came from, so a padded strip says "nobody has written this source's table down" instead
     * of saying something false about it. Each has a row of its own in {@code drop_symbols.json}
     * on a distinct vanilla item, and each is glinted -- which is what keeps them apart from the
     * plain accessories some sources drop onto the same items. A Hunter Ring is a bare gold ingot
     * precisely so that Enchanted Gold Ingot can be the glinted one.</p>
     *
     * <p>Every name here is spelled the way Hypixel spells it, and pinned to that by
     * {@code SkyBlockNameSnapshotTest}: the first entry read "Enchanted Gold" until that test went
     * in, which is the internal id ({@code ENCHANTED_GOLD}) rather than the display name, so the
     * one string that scrolls on more reels than any other in the mod was a string the server
     * never prints.</p>
     */
    private static final List<String> GENERIC = List.of(
            "Enchanted Gold Ingot",
            "Enchanted Diamond",
            "Enchanted Iron Ingot",
            "Enchanted Redstone Block",
            "Enchanted Lapis Lazuli Block",
            "Enchanted Emerald Block",
            "Enchanted Coal Block",
            "Enchanted Obsidian",
            "Enchanted Quartz Block",
            "Enchanted Glowstone");

    /**
     * The drops that have to be on a strip whatever else is, because they are what make the machine
     * legible as that machine.
     *
     * <p>Only sources whose pool is wider than {@link #WINDOW} need an entry, because a shorter
     * pool is shown whole. The names are the ones a player would use to describe the content out
     * loud: Diana is griffin feathers, ancient claws and the Inquisitor's book; a slayer is one
     * signature drop per boss, so that a Voidgloom roll cannot scroll six colours of dye and
     * nothing else. Anything here that its source does not actually pay is ignored rather than
     * inserted -- this pins what is shown, it cannot invent loot -- and {@code
     * SlotMachineFillerMcTest} fails if a name here has gone stale.</p>
     */
    private static final Map<LootSource, List<String>> SIGNATURES = signatures();

    /** One strip per source, indexed by ordinal; built once, never replaced. */
    private static final FillerStrip[] BY_SOURCE = buildAll();

    /**
     * The strip for a roll carrying no source at all.
     *
     * <p>Reachable: {@code SlotRoll.sourceAt} answers null the instant a roll stops running, and a
     * frame can be drawn on either side of that. Generic rather than Diana's, because a strip that
     * names one source's loot under another source's caption is the exact mistake this class
     * exists to undo.</p>
     */
    private static final FillerStrip UNKNOWN =
            new FillerStrip(null, GENERIC.toArray(new String[0]));

    /** Every name any strip can show, distinct, for {@code /skyprism status}. */
    private static final List<String> ALL_NAMES = buildAllNames();

    /** The source this strip belongs to, or null for {@link #UNKNOWN}. */
    private final LootSource source;

    /**
     * The strip, in drum order.
     *
     * <p>Handed out raw to the drawing path so a frame can index it without a bounds-checked
     * accessor or a {@code List} iterator. It is never written after construction; callers outside
     * this class must treat it as immutable, which {@link #names()} says out loud.</p>
     */
    private final String[] names;

    /**
     * {@link #names}'s sprites, resolved lazily and refreshed in place.
     *
     * <p>Not built at construction because an {@code ItemStack} cannot exist before the item
     * registry has loaded, and this class initialises with {@code SlotMachineHud}. Not frozen once
     * built because {@link DropSymbols} learns items off real drops, so a name that resolved to
     * the generic fallback on the first spin of a session would keep drawing the fallback for the
     * rest of it, and the player would have to restart the game to see art the mod had already
     * learned.</p>
     *
     * <p>Render-thread only, and idempotent -- two threads racing here would write the same stacks
     * into the same slots -- so it deliberately carries no synchronisation, exactly as the single
     * global array it replaces did not.</p>
     */
    private ItemStack[] icons;

    /** When {@link #icons} was last resolved, on the roll's own clock. */
    private long iconsAt;

    /**
     * The type size every name on this strip fits at, or 0 before it has been measured.
     *
     * <p>Per strip rather than per machine because the strips are different lengths of word: the
     * scale is the minimum fit over one fixed array, so it is a constant for one source and would
     * be a value that changed under the player mid-scroll if it were shared. Filled in by
     * {@code SlotMachineHud}, which owns the budget and the two bounds.</p>
     */
    private float labelScale;

    private FillerStrip(LootSource source, String[] names) {
        this.source = source;
        this.names = names;
    }

    /**
     * The strip for {@code source}.
     *
     * @param source the source rolling, or null when the roll carries none
     * @return the one shared strip for it; never null, never empty
     */
    static FillerStrip of(LootSource source) {
        return source == null ? UNKNOWN : BY_SOURCE[source.ordinal()];
    }

    /** The strip a roll with no source shows. */
    static FillerStrip unknown() {
        return UNKNOWN;
    }

    /**
     * The names, in drum order.
     *
     * @return the live array, which the caller must not modify
     */
    String[] names() {
        return names;
    }

    /** The names as a list, for tests and for {@code /skyprism status}. */
    List<String> nameList() {
        return List.of(names);
    }

    /** The source this strip belongs to, or null for the no-source strip. */
    LootSource source() {
        return source;
    }

    /**
     * Every name any strip can put on screen, distinct, in source order.
     *
     * <p>What {@code /skyprism status} counts. The union rather than one source's strip, because
     * the question that line answers -- how many of the sprites the machine can draw are resolving
     * through Hypixel's pack rather than through the fallback -- is about the whole machine, and
     * because a player runs the command while nothing at all is rolling.</p>
     */
    static List<String> allNames() {
        return ALL_NAMES;
    }

    /** Every strip, in {@link LootSource} declaration order, with the no-source strip last. */
    static List<FillerStrip> all() {
        List<FillerStrip> strips = new ArrayList<>(BY_SOURCE.length + 1);
        Collections.addAll(strips, BY_SOURCE);
        strips.add(UNKNOWN);
        return List.copyOf(strips);
    }

    /** The generic top-up, exposed so a test can pin every one of its names to a sprite. */
    static List<String> genericTopUp() {
        return GENERIC;
    }

    /**
     * The pinned names, exposed so a test can catch one that its source has stopped paying.
     *
     * <p>A stale pin fails silently -- {@link #window} skips it rather than inventing loot, and the
     * window quietly loses a slot to nothing -- which is precisely the kind of rot that needs a
     * machine watching it.</p>
     */
    static Map<LootSource, List<String>> signaturesForTest() {
        return SIGNATURES;
    }

    /**
     * This strip's sprites, one per name, in the same order.
     *
     * <p>The array is allocated at most once for the session and rewritten in place after that, so
     * a refresh costs the probes and nothing else. {@code now} is the roll's own clock, the same
     * instant every other value in the frame is derived from, so a source that is not on screen
     * never ages at all.</p>
     *
     * @param now the roll's clock reading for this frame
     * @return the sprites; the live array, which the caller must not modify
     */
    ItemStack[] icons(long now) {
        ItemStack[] cached = icons;
        if (cached != null && now - iconsAt < REFRESH_MILLIS) {
            return cached;
        }
        ItemStack[] resolved = cached == null ? new ItemStack[names.length] : cached;
        for (int i = 0; i < names.length; i++) {
            resolved[i] = DropSymbols.iconForName(names[i]);
        }
        iconsAt = now;
        icons = resolved;
        return resolved;
    }

    /** The measured type size, or 0 when it has not been worked out yet. */
    float labelScale() {
        return labelScale;
    }

    /** Records the measured type size; see the field for why it is a per-strip constant. */
    void labelScale(float value) {
        labelScale = value;
    }

    @Override
    public String toString() {
        return (source == null ? "<no source>" : source.id()) + " strip " + List.of(names);
    }

    private static FillerStrip[] buildAll() {
        LootSource[] sources = LootSource.values();
        FillerStrip[] strips = new FillerStrip[sources.length];
        for (LootSource source : sources) {
            strips[source.ordinal()] = new FillerStrip(source, resolve(source));
        }
        return strips;
    }

    /**
     * One source's strip: a window onto its whole drop pool, topped up to {@link #MIN_LENGTH} with
     * generic material when the pool is thin.
     *
     * <p>The pool is {@link LootSourceRegistry#dropPool}, which is everything the source pays and
     * not only the drops worth a flourish -- so a Diana reel scrolls Griffin Feathers and Ancient
     * Claws again, and a slayer reel scrolls something from each of the six bosses. It arrives
     * already sorted case-insensitively, which is what keeps the drum arranged the same in every
     * JVM.</p>
     *
     * <p>A pool of {@link #WINDOW} or fewer is the strip. A wider pool is sampled, because a
     * twenty-one-slot roll cannot show more than twenty-one symbols and lengthening the drum past
     * that only lowers the odds of any particular symbol appearing -- which is precisely how the
     * Chimera book became invisible. The sample takes {@link #SIGNATURES} first and then walks the
     * remainder at an even stride, so it spans the whole alphabet rather than stopping at C, and it
     * is a pure function of the pool, so it is the same window in every session and a test can pin
     * it.</p>
     */
    private static String[] resolve(LootSource source) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> strip = new ArrayList<>();

        for (String name : window(source, LootSourceRegistry.dropPool(source))) {
            add(keys, strip, name);
        }
        for (String name : GENERIC) {
            if (strip.size() >= MIN_LENGTH) {
                break;
            }
            add(keys, strip, name);
        }
        return strip.toArray(new String[0]);
    }

    /**
     * At most {@link #WINDOW} of {@code pool}, in the pool's own order, signatures included.
     *
     * @param source the source, for its {@link #SIGNATURES} row
     * @param pool   its whole drop pool, already sorted
     * @return the pool itself when it fits, otherwise the sample described on {@link #resolve}
     */
    private static List<String> window(LootSource source, List<String> pool) {
        if (pool.size() <= WINDOW) {
            return pool;
        }
        Set<String> chosen = new LinkedHashSet<>();
        Map<String, String> spellings = new LinkedHashMap<>();
        for (String name : pool) {
            spellings.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        for (String name : SIGNATURES.getOrDefault(source, List.of())) {
            // The POOL's spelling, not the signature list's, so a difference of case here cannot
            // cost the window a slot to a name the strip then deduplicates away. Null -- the source
            // no longer pays it -- is skipped rather than inserted: a signature list is allowed to
            // pin what is shown and is not allowed to put loot on a machine.
            String spelling = spellings.get(name.toLowerCase(Locale.ROOT));
            if (spelling != null && chosen.size() < WINDOW) {
                chosen.add(spelling);
            }
        }
        List<String> rest = new ArrayList<>(pool.size());
        for (String name : pool) {
            if (!chosen.contains(name)) {
                rest.add(name);
            }
        }
        int need = WINDOW - chosen.size();
        for (int i = 0; i < need; i++) {
            // Even stride over what is left, so the window is spread across the pool instead of
            // being its first twenty-one names -- an alphabetical prefix would mean a slayer strip
            // that stops somewhere around "Etherwarp Merger" for ever.
            chosen.add(rest.get((int) ((long) i * rest.size() / need)));
        }
        List<String> ordered = new ArrayList<>(chosen);
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        return ordered;
    }

    private static Map<LootSource, List<String>> signatures() {
        Map<LootSource, List<String>> map = new EnumMap<>(LootSource.class);

        // Diana's is deliberately almost the whole of the strip as it stood before the pool grew:
        // one signature drop per mythological creature, plus the burrow treasure. Widening the pool
        // to everything the ritual pays must not cost the drum a single sprite a Diana player
        // already recognises, so the twenty-nine-name pool is sampled around these rather than
        // across them, and what rotates through the two slots left over is the attribute shards and
        // the coin line. The Chimera book is first because it is the one that was reported missing.
        map.put(LootSource.DIANA_MYTHOLOGICAL, List.of(
                "Chimera I", "Griffin Feather", "Ancient Claw", "Minos Relic", "Daedalus Stick",
                "Crown of Greed", "Mythological Dye", "Braided Griffin Feather", "Myth the Fish",
                "Crochet Tiger Plushie", "Shimmering Wool", "Manti-core", "Washed-up Souvenir",
                "Cretan Urn", "Hilt of Revelations", "Brain Food", "Antique Remedies",
                "Dwarf Turtle Shelmet", "Fateful Stinger"));

        // One LootSource carries all six slayers, so the risk here is the opposite one: a
        // seventy-six-name pool sampled evenly could easily say nothing about a whole boss. Two per
        // fight, chosen as what a player would name if asked what that slayer drops, plus the
        // Enchanted Book that every one of the six tables pays and that a real Chimera, Smite VI or
        // Critical VI arrives in chat as.
        map.put(LootSource.SLAYER_BOSS, List.of(
                "Enchanted Book",
                "Judgement Core", "Etherwarp Merger",
                "Warden Heart", "Beheaded Horror",
                "Tarantula Talisman", "Fly Swatter",
                "Overflux Capacitor", "Red Claw Egg",
                "Archfiend Dice", "Subzero Inverter",
                "Handy Blood Chalice", "Sangria Dye"));

        return Map.copyOf(map);
    }

    private static void add(Set<String> keys, List<String> strip, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        String trimmed = name.trim();
        if (keys.add(trimmed.toLowerCase(Locale.ROOT))) {
            strip.add(trimmed);
        }
    }

    private static List<String> buildAllNames() {
        Set<String> keys = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (FillerStrip strip : BY_SOURCE) {
            for (String name : strip.names) {
                add(keys, names, name);
            }
        }
        for (String name : UNKNOWN.names) {
            add(keys, names, name);
        }
        return List.copyOf(names);
    }
}
