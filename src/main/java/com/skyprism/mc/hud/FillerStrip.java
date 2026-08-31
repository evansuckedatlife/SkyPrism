package com.skyprism.mc.hud;

import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.mc.symbols.DropSymbols;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * <p>{@link LootSourceRegistry} already carries, per source, the drops worth celebrating -- a list
 * transcribed from the wiki and pinned by {@code DropSymbolsMcTest}, which fails if any of them
 * lacks a sprite or if two of one source's drops land on the same picture. So a strip is that list
 * and nothing else, sorted case-insensitively so the drum reads the same in every JVM
 * ({@code Set.copyOf} randomises iteration order per process, which would otherwise arrange the
 * strip differently every session and make any test of it flaky).</p>
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
     * One source's strip: its own celebrated drops, then generic material up to
     * {@link #MIN_LENGTH}.
     *
     * <p>No cap at the top end. A source with twenty-two verified drops shows twenty-two, which
     * costs a refresh twenty-two probes twice a second and costs the drum nothing at all -- three
     * cells are drawn per reel per frame however long the strip is.</p>
     */
    private static String[] resolve(LootSource source) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> strip = new ArrayList<>();

        List<String> own = new ArrayList<>(LootSourceRegistry.info(source).jackpotItems());
        // Sorted, because LootSourceInfo freezes that list with Set.copyOf, whose iteration order
        // is randomised per JVM: without this the drum would be arranged differently in every
        // session and no test could pin it.
        own.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : own) {
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
