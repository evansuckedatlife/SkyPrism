package com.skyprism.mc.symbols;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.util.TextClean;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The item sprite to draw on a slot machine reel for a given Diana drop.
 *
 * <h2>Why sprites instead of the drop's name</h2>
 * The reels used to draw text. Text is unambiguous but it is not a slot machine: nobody
 * reads three words spinning past, they read three <em>pictures</em>, and the whole reason
 * the widget is shaped like a fruit machine is that a picture lands. So the reel draws an
 * item and the name goes underneath in small type, where it does the job text is good at --
 * telling apart the two small brown things.
 *
 * <h2>Four tiers, best first</h2>
 * Hypixel SkyBlock now pushes an official server resource pack, and every drop in it has real
 * art. That art is addressed by the vanilla {@code minecraft:item_model} data component, which
 * the server sets on the stack it sends the player; the pack then supplies
 * {@code hypixel_skyblock:item/community_center/mayor/diana/...} for it. Nothing about that is
 * derivable from a chat line -- the pack has no display-name index and the paths are semantic --
 * so a stack synthesised from a name can never carry it, and a Daedalus Stick drawn as
 * {@code minecraft:stick} will always look wrong next to the same item in the player's own
 * inventory. That mismatch is what these tiers exist to close:
 *
 * <ol>
 *   <li><b>{@link SymbolSource#REAL}</b> -- a copy of the genuine stack the server sent, taken by
 *       {@link IconCapture} when the drop landed in the inventory. Identical to what the player
 *       sees on their hotbar, because it <em>is</em> what they see.</li>
 *   <li><b>{@link SymbolSource#LEARNED}</b> -- the base item plus the {@code item_model} id
 *       remembered from a previous capture, through {@link IconMemory}. This is the tier that
 *       makes the feature worth having: a Chimera seen once renders correctly forever after, from
 *       a cold start, with no capture needed.</li>
 *   <li><b>{@link SymbolSource#FALLBACK}</b> -- the hand-chosen vanilla lookalike from
 *       {@code drop_symbols.json}, which is what every reel drew before any of this existed and
 *       what a vanilla server or a pack-less session still gets.</li>
 *   <li><b>{@link SymbolSource#NONE}</b> -- {@link ItemStack#EMPTY}, which the HUD already treats
 *       as "draw the name instead". Reached only before Minecraft has bound item components.</li>
 * </ol>
 *
 * <h2>The fallback table</h2>
 * The vanilla lookalikes live in {@code assets/skyprism/drop_symbols.json}, not in this file, so a
 * renamed or newly added Hypixel drop can be corrected in a resource pack or a hotfix without a
 * recompile. Each row names a vanilla item id, the display names it answers to, and a {@code why}
 * explaining the choice.
 *
 * <p>Most Diana drops are easy, because SkyBlock itself reuses a vanilla texture: Griffin
 * Feather really is a feather, Ancient Claw really is flint, Crown of Greed really is an
 * enchanted golden helmet, Chimera really is an enchanted book. The judgement calls are the
 * handful with custom art, and there the rule was "what does a player half-recognise at
 * 16x16" rather than "what is technically the same object" -- a decorated pot for the Cretan
 * Urn, pointed dripstone for the Fateful Stinger, brain coral for Brain Food. The JSON
 * argues each one.
 *
 * <p>SkyBlock draws every {@code Enchanted <x>} with an enchantment glint, so rows can ask
 * for one. That is not decoration: Ancient Claw and Enchanted Ancient Claw are both flint,
 * and Hilt of Revelations and Daedalus Stick are both a stick, so the shimmer is the only
 * thing keeping those pairs apart on a spinning reel.
 *
 * <h2>Matching</h2>
 * Names arrive from {@code LootParser} already stripped, but this is a render path fed by
 * chat, so matching is defensive: formatting codes removed, whitespace collapsed, case
 * folded, and a count on either end ignored, so {@code "16x Ancient Claw"} and
 * {@code "Enchanted Gold x16"} both find their row. Failing that it retries without a
 * trailing roman numeral and without a parenthetical, which is what picks up an
 * {@code "Enchanted Book (Chimera VI)"} the day Hypixel adds a sixth level.
 *
 * <p>The captured and learned tiers are keyed by {@link #matchKey}, the same normalisation with
 * a count stripped, because a stack that arrived in the inventory is titled the same way the chat
 * line named it.
 *
 * <h2>When the sprite exists</h2>
 * An {@link ItemStack} cannot be constructed before Minecraft has bound the item's data
 * components, which on 26.x happens when a world or a server supplies them and <em>not</em>
 * at the title screen. Building the stacks eagerly therefore threw
 * {@code NullPointerException: Components not bound yet} out of this class's initialiser,
 * which is the worst possible place for it: a failed {@code <clinit>} poisons the class for
 * the rest of the process, so every later call threw {@link NoClassDefFoundError} and the
 * slot machine silently never drew again.
 *
 * <p>So the table resolves ids to {@link Item}s -- a pure registry lookup, legal from the
 * moment the game has bootstrapped -- and each row builds its stack on first use, once the
 * components behind it are bound. Until they are, {@link #iconFor} answers
 * {@link ItemStack#EMPTY}. The learned tier obeys exactly the same rule, and the captured tier
 * cannot be affected by it, because a stack that came off the wire already exists. Nothing in
 * this class can throw at any point in that sequence.
 *
 * <h2>Cost</h2>
 * The resource is read once, on first use; each row's {@link ItemStack} is built once, on
 * the first frame that asks for it with components bound, and held forever. A lookup after
 * that is one hash probe on the raw string, two probes on the capture and memory maps -- both
 * of which are empty and therefore free for anyone not on Hypixel -- and one field read, so a
 * reel spinning at 240fps allocates nothing. Nothing here runs at all until a roll is on screen,
 * because {@code SlotMachineHud} early-outs before it asks for an icon. The one thing this class
 * will not do on a render frame is touch the disk: {@link IconMemory} is read from the tick on
 * which the Diana gate first opens, which is minutes before any reel spins.
 *
 * <p><b>The returned stacks are shared and must not be mutated.</b> They exist to be handed
 * to {@code GuiGraphics.item}, which does not touch them. Copying per frame would put an
 * allocation on the render path for no benefit.
 *
 * <p>Nothing here throws. A missing, unparseable or half-wrong resource costs one warning in
 * the log and falls back to a chest, because a slot machine that draws the wrong picture is
 * a cosmetic disappointment and one that throws inside a HUD render is a crash.
 */
public final class DropSymbols {

    private DropSymbols() {
    }

    /** Which of the four tiers answered for a name. See the class javadoc. */
    public enum SymbolSource {
        /** A live captured stack: byte-for-byte what the server sent the player. */
        REAL,
        /** A base item carrying an {@code item_model} id remembered from an earlier capture. */
        LEARNED,
        /** The synthesised vanilla lookalike from {@code drop_symbols.json}. */
        FALLBACK,
        /** Nothing drawable: {@link ItemStack#EMPTY}, and the HUD draws the name instead. */
        NONE
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Symbols");

    /** Where the bundled table lives; also the path a resource pack overrides. */
    static final String RESOURCE = "/assets/skyprism/drop_symbols.json";

    /**
     * Last-resort icon, referenced through {@link Items} rather than the registry so it
     * cannot itself fail to resolve. A chest reads as "some loot we could not name", which
     * is exactly what an unmapped drop is.
     */
    private static final Item HARD_FALLBACK = Items.CHEST;

    /** "16x Ancient Claw" -- Hypixel's banner form. */
    private static final Pattern LEADING_COUNT =
            Pattern.compile("^\\d{1,9}\\s*x\\s+(?<rest>.+)$");

    /** "Enchanted Gold x16" -- the form some capture tools write. */
    private static final Pattern TRAILING_COUNT =
            Pattern.compile("^(?<rest>.+?)\\s*x\\s*\\d{1,9}$");

    /** A trailing enchantment level, so an unlisted "Chimera VI" still finds Chimera. */
    private static final Pattern TRAILING_ROMAN =
            Pattern.compile("^(?<rest>.+?)\\s+(?:x{0,3})(?:ix|iv|v?i{0,3})$");

    /** "Enchanted Book (Chimera I)" -- both halves are worth trying. */
    private static final Pattern PARENTHETICAL =
            Pattern.compile("^(?<outer>[^(]*?)\\s*\\((?<inner>[^)]+)\\)\\s*$");

    /**
     * Raw-name to resolution, so a reel redrawing the same drop every frame never re-normalises.
     * Capped because the keys come from chat and an unbounded map fed by a hostile server is
     * a leak; past the cap lookups still work, they just pay for normalisation again.
     */
    private static final int RESOLVED_CACHE_LIMIT = 512;

    /**
     * How many live captured stacks are held. Diana's whole drop table is a few dozen entries, so
     * this is never approached in play; it is here because a server that invents drop names could
     * otherwise make the client hold one stack per name forever. Past the cap an already-captured
     * name still refreshes, a new one is simply not taken -- and the learned tier, which is the
     * one that survives a restart, is bounded separately by {@link IconMemory#MAX_ENTRIES}.
     */
    private static final int CAPTURED_LIMIT = 256;

    private static final Map<String, Resolved> RESOLVED = new ConcurrentHashMap<>();

    /**
     * Live captured stacks, keyed by {@link #matchKey}. Our own copies, never references into the
     * player's inventory, which the server mutates underneath us.
     *
     * <p>Concurrent because the writer is the client tick and the reader is the HUD render, and on
     * a modern client those are not reliably the same thread. Every value is fully built before it
     * is published, so a reader never sees a half-made stack.
     */
    private static final Map<String, ItemStack> CAPTURED = new ConcurrentHashMap<>();

    /** Learned models, mirroring {@link #memory} with the stacks built lazily. */
    private static final Map<String, LearnedIcon> LEARNED = new ConcurrentHashMap<>();

    /**
     * The on-disk memory, or null before {@link #ensureMemoryLoaded()} has run.
     *
     * <p>Volatile because it is installed from the client tick and read from the render path.
     */
    private static volatile IconMemory memory;

    /** Set once the load has been attempted, so a failure is not retried on every tick. */
    private static volatile boolean memoryLoadAttempted;

    /**
     * One row of the fallback table: which item to draw, whether it shimmers, and the shared
     * stack once one could be built.
     *
     * <p>The stack is deliberately not built in the constructor. See the class javadoc: an
     * {@link ItemStack} cannot exist before the item's data components are bound, and that
     * has not happened yet at the title screen. {@link #stack()} is therefore the only way
     * to get one, it answers {@link ItemStack#EMPTY} until the components arrive, and it
     * builds exactly one stack per row for the life of the process.
     */
    private static final class Icon {

        private final Item item;
        private final boolean glint;

        /**
         * The shared stack, or null before one could be made.
         *
         * <p>Volatile because the write happens on whichever thread first drew a reel and a
         * later read may be on another; the value is immutable-by-contract once written, so
         * a racing pair of builders would only waste one allocation, never publish a
         * half-built stack.
         */
        private volatile ItemStack stack;

        Icon(Item item, boolean glint) {
            this.item = item;
            this.glint = glint;
        }

        /**
         * @return the shared stack for this row, or {@link ItemStack#EMPTY} while the item's
         *         data components are still unbound
         */
        ItemStack stack() {
            ItemStack built = stack;
            if (built != null) {
                return built;
            }
            // Exactly the holder ItemStack's own constructor would dereference, so this is
            // the question "would that constructor throw", not an approximation of it.
            if (!item.builtInRegistryHolder().areComponentsBound()) {
                return ItemStack.EMPTY;
            }
            ItemStack fresh = new ItemStack(item);
            if (glint) {
                fresh.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
            }
            stack = fresh;
            return fresh;
        }
    }

    /**
     * A remembered {@code item_model}, applied to the base item the server actually sent.
     *
     * <p>Setting {@link DataComponents#ITEM_MODEL} is the whole trick: the vanilla renderer
     * consults that component before the item's own model, so the stack draws Hypixel's art even
     * though it is, underneath, an ordinary stick. The component is a plain
     * {@link Identifier} on both 26.1.2 and 26.2, which is why none of this needs a version
     * conditional.
     *
     * <p>Lazily built for the same reason {@link Icon} is, and never eagerly: this class is
     * populated from a file read, which can happen long before a world exists.
     */
    private static final class LearnedIcon {

        private final Item item;
        private final Identifier model;

        /** The row this was built from, kept verbatim so a re-sync is a string compare. */
        private final String itemId;
        private final String modelId;

        private volatile ItemStack stack;

        LearnedIcon(Item item, Identifier model, String itemId, String modelId) {
            this.item = item;
            this.model = model;
            this.itemId = itemId;
            this.modelId = modelId;
        }

        /** The built stack without building one, for identity checks that must not have effects. */
        ItemStack peek() {
            return stack;
        }

        /**
         * @return the shared stack, or {@link ItemStack#EMPTY} while components are unbound
         */
        ItemStack stack() {
            ItemStack built = stack;
            if (built != null) {
                return built;
            }
            try {
                if (!item.builtInRegistryHolder().areComponentsBound()) {
                    return ItemStack.EMPTY;
                }
                ItemStack fresh = new ItemStack(item);
                fresh.set(DataComponents.ITEM_MODEL, model);
                stack = fresh;
                return fresh;
            } catch (Exception | LinkageError notReady) {
                // A HUD is not a place to throw, and this path runs on a render frame.
                return ItemStack.EMPTY;
            }
        }
    }

    /**
     * What one raw chat name resolves to, cached so the render path never re-normalises.
     *
     * @param key  the normalised, count-stripped key the captured and learned tiers use
     * @param icon the fallback-tier row, never null -- the table's own fallback when nothing matched
     */
    private record Resolved(String key, Icon icon) {
    }

    /**
     * Holds the parsed table. A class-init holder gives lazy-once loading with no lock on the
     * hot path and no chance of the warning being logged twice.
     *
     * <p>Everything it builds is registry lookups and strings. Nothing in {@link #load()} can
     * construct an {@link ItemStack}, which is what keeps this initialiser incapable of
     * throwing however early it runs.
     */
    private static final class Table {
        static final Table INSTANCE = load();

        final Map<String, Icon> byName;
        final Icon fallback;

        Table(Map<String, Icon> byName, Icon fallback) {
            this.byName = byName;
            this.fallback = fallback;
        }
    }

    // ======================================================================
    //  Public API
    // ======================================================================

    /**
     * The icon for one drop.
     *
     * @param drop the drop, may be null
     * @return a shared, never-null stack; the fallback when the drop has no row, and
     *         {@link ItemStack#EMPTY} only while item components are unbound
     */
    public static ItemStack iconFor(LootDrop drop) {
        return drop == null ? Table.INSTANCE.fallback.stack() : iconForName(drop.itemName());
    }

    /**
     * The icon for a display name, from the best tier that can answer.
     *
     * @param itemName a Hypixel display name, may be null, may still carry formatting codes
     *                 or a count on either end
     * @return a shared, never-null stack; the fallback when there is no row, and
     *         {@link ItemStack#EMPTY} only while item components are unbound
     */
    public static ItemStack iconForName(String itemName) {
        Table table = Table.INSTANCE;
        if (itemName == null || itemName.isEmpty()) {
            return table.fallback.stack();
        }
        // The cache holds rows, never stacks. Caching a stack would freeze whatever answer
        // the first frame got, and the first frame is exactly the one that may have run
        // before the item components were bound and taken ItemStack.EMPTY for the answer --
        // or before the drop had ever been captured.
        Resolved resolved = resolve(table, itemName);
        if (!resolved.key().isEmpty()) {
            ItemStack real = CAPTURED.get(resolved.key());
            if (real != null && !real.isEmpty()) {
                return real;
            }
            LearnedIcon learned = LEARNED.get(resolved.key());
            if (learned != null) {
                ItemStack built = learned.stack();
                if (!built.isEmpty()) {
                    return built;
                }
            }
        }
        return resolved.icon().stack();
    }

    /**
     * Whether anything better than the table's own fallback answers for this name -- that is,
     * whether the reel will draw a picture chosen for this drop rather than the generic chest.
     *
     * <p>True for a name with a row in {@code drop_symbols.json}, and also for one that has been
     * captured or learned even if no row was ever written for it, which is how a drop Hypixel
     * added after this build shipped still reports as mapped.
     *
     * @param itemName a Hypixel display name, may be null
     * @return true when some tier claims this name
     */
    public static boolean hasMapping(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        String key = matchKey(itemName);
        if (!key.isEmpty() && (CAPTURED.containsKey(key) || LEARNED.containsKey(key))) {
            return true;
        }
        return lookup(Table.INSTANCE, itemName) != null;
    }

    /**
     * Which tier {@link #iconForName(String)} would answer this name from.
     *
     * <p>Determined by asking for the icon and identifying which tier produced the object that
     * came back, rather than by a second copy of the resolution order -- so the two can never
     * disagree, which is the whole point of having this method at all.
     *
     * <p>Not on the render path; it exists for {@code /skyprism status}, for the self-test, and
     * for the tests that assert the ordering.
     *
     * @param itemName a Hypixel display name, may be null
     * @return {@link SymbolSource#REAL} for a live captured stack, {@link SymbolSource#LEARNED}
     *         for a base item plus a remembered model, {@link SymbolSource#FALLBACK} for the
     *         synthesised vanilla item, {@link SymbolSource#NONE} when nothing is drawable
     */
    public static SymbolSource sourceFor(String itemName) {
        ItemStack chosen = iconForName(itemName);
        if (chosen == null || chosen.isEmpty()) {
            return SymbolSource.NONE;
        }
        if (itemName == null || itemName.isEmpty()) {
            return SymbolSource.FALLBACK;
        }
        String key = resolve(Table.INSTANCE, itemName).key();
        if (key.isEmpty()) {
            return SymbolSource.FALLBACK;
        }
        if (chosen == CAPTURED.get(key)) {
            return SymbolSource.REAL;
        }
        LearnedIcon learned = LEARNED.get(key);
        if (learned != null && chosen == learned.peek()) {
            return SymbolSource.LEARNED;
        }
        return SymbolSource.FALLBACK;
    }

    /**
     * Every drop name this module can say anything about, in the normalised form
     * {@link #matchKey} produces.
     *
     * <p>The union of three sets, and it has to be the union rather than just the bundled table:
     * a drop Hypixel added after this build shipped has no row in {@code drop_symbols.json}, but
     * once it has been captured or learned the mod does know how to draw it, and a status line
     * counting only table rows would report it as missing.
     *
     * <p>Builds a fresh set on every call and is not for the render path; it exists for
     * {@code /skyprism status} and for the self-test.
     *
     * @return an unmodifiable snapshot, in a stable order: table rows first, then what has been
     *         learned, then what has been captured this session
     */
    public static Set<String> knownNames() {
        Set<String> names = new LinkedHashSet<>(Table.INSTANCE.byName.keySet());
        names.addAll(LEARNED.keySet());
        names.addAll(CAPTURED.keySet());
        return Collections.unmodifiableSet(names);
    }

    /** How many drop names have a live captured stack this session. */
    public static int capturedCount() {
        return CAPTURED.size();
    }

    /** How many drop names have a remembered {@code item_model}, learned this session or earlier. */
    public static int learnedCount() {
        return LEARNED.size();
    }

    // ======================================================================
    //  Learning
    // ======================================================================

    /**
     * Takes a genuine stack the server sent as the truth about what a drop looks like.
     *
     * <p>Two things happen, and they are independent. The stack itself is copied and kept, which
     * makes this name {@link SymbolSource#REAL} for the rest of the session. Separately, if it
     * carries an {@code item_model} pointing somewhere other than its own item, that id and the
     * base item are written to {@link IconMemory}, which is what makes the name
     * {@link SymbolSource#LEARNED} on every launch after this one. A stack whose model is just its
     * own id -- which is the vanilla default every item has, so it is what a plain server or an
     * un-dressed SkyBlock item looks like -- still gets the first half; there is simply nothing
     * about it worth remembering.
     *
     * <p>The copy is not optional. Holding the inventory's own {@link ItemStack} would mean
     * rendering an object the server rewrites in place, so the reel would change picture, count
     * or nothing at all depending on what the player did with the item afterwards. The copy is
     * also forced to a count of one, so the reel never draws a stack size over the sprite.
     *
     * <p>Never throws, and is a no-op for a null or empty stack.
     *
     * @param dropName the name the chat pipeline parsed, which is the key the reel will look up
     * @param live     the stack as it arrived; copied, never retained
     */
    public static void learnFrom(String dropName, ItemStack live) {
        try {
            if (dropName == null || live == null || live.isEmpty()) {
                return;
            }
            String key = matchKey(dropName);
            if (key.isEmpty()) {
                return;
            }

            ItemStack keep = live.copyWithCount(1);
            if (CAPTURED.size() < CAPTURED_LIMIT || CAPTURED.containsKey(key)) {
                CAPTURED.put(key, keep);
            }

            Identifier model = live.get(DataComponents.ITEM_MODEL);
            Identifier baseId = BuiltInRegistries.ITEM.getKey(live.getItem());
            if (model == null || baseId == null || model.equals(baseId)) {
                // Every vanilla item carries an item_model of its own id by default, so a stack
                // whose model *is* its id is telling us nothing: it is an ordinary stick, and the
                // synthesised fallback already draws exactly that. Writing it down would fill the
                // bounded memory with rows that change nothing and hide the ones that do.
                return;
            }

            IconMemory store = memory;
            if (store == null) {
                // The disk read has not happened yet, so remember it only for this session.
                // Deliberately not a reason to read the file here: this runs on the tick a drop
                // lands, which is the tick the slot machine starts spinning.
                installLearned(key, baseId.toString(), model.toString());
                return;
            }
            if (store.remember(key, SkyBlockIds.of(live), baseId.toString(), model.toString(),
                    System.currentTimeMillis())) {
                LOGGER.debug("learned item model for '{}': {} as {}", key, baseId, model);
            }
            // Refresh from the store rather than from the arguments, so a row the store chose to
            // supersede or evict is reflected here instead of drifting out of step with the file.
            syncLearnedFrom(store);
        } catch (Exception | LinkageError never) {
            LOGGER.debug("SkyPrism could not learn an item model for '{}'", dropName, never);
        }
    }

    /**
     * Reads {@link IconMemory} from the config directory, at most once per process.
     *
     * <p>Called from {@link IconCapture} on the first tick the Diana gate is open, which is a
     * deliberate choice of moment: it is minutes before any creature dies, so the file read lands
     * nowhere near the frame a reel starts spinning. Calling it from the render path would put a
     * synchronous file read on exactly that frame, which is the hitch this whole module is
     * organised to avoid.
     */
    static void ensureMemoryLoaded() {
        if (memoryLoadAttempted) {
            return;
        }
        memoryLoadAttempted = true;
        try {
            Path dir = configDir();
            installMemory(IconMemory.load(dir == null ? null : dir.resolve(IconMemory.FILE_NAME)));
        } catch (Exception | LinkageError unreadable) {
            LOGGER.warn("SkyPrism could not read its learned item models; Diana reels will draw "
                    + "the built-in vanilla lookalikes.", unreadable);
        }
    }

    /**
     * Installs a memory and rebuilds the learned tier from it.
     *
     * <p>Package-private so a test can point the module at a temporary file. Rows naming an item
     * or a model this Minecraft version cannot resolve are dropped from the in-memory tier but
     * left in the file, because they may well be valid on the version the player launches next.
     *
     * @param store the memory to adopt; null clears the learned tier
     */
    static void installMemory(IconMemory store) {
        memory = store;
        memoryLoadAttempted = true;
        LEARNED.clear();
        if (store == null) {
            return;
        }
        syncLearnedFrom(store);
        for (String note : store.notes()) {
            LOGGER.info("SkyPrism item model memory: {}", note);
        }
    }

    /** The memory in use, or null before one has been installed. */
    static IconMemory memory() {
        return memory;
    }

    /** Writes the memory if it is due. One boolean read when nothing has been learned. */
    static void maybeSaveMemory(long now) {
        IconMemory store = memory;
        if (store != null) {
            store.maybeSave(now);
        }
    }

    /** Writes the memory now, for the disconnect and shutdown edges. */
    static void saveMemory() {
        IconMemory store = memory;
        if (store != null) {
            store.save();
        }
    }

    /** Whether a live stack has already been captured for this key, so a scan can stop looking. */
    static boolean hasCaptured(String key) {
        return key != null && !key.isEmpty() && CAPTURED.containsKey(key);
    }

    /** Rebuilds the learned tier to match the store, adding what resolves and dropping what does not. */
    private static void syncLearnedFrom(IconMemory store) {
        for (Map.Entry<String, IconMemory.Learned> entry : store.all().entrySet()) {
            installLearned(entry.getKey(), entry.getValue().itemId(), entry.getValue().modelId());
        }
        LEARNED.keySet().retainAll(store.all().keySet());
    }

    /**
     * Turns one remembered row into a drawable tier entry.
     *
     * <p>Resolving stops at the {@link Item} and the {@link Identifier}; the stack is built on
     * first draw, for the reason the class javadoc gives.
     */
    private static void installLearned(String key, String itemId, String modelId) {
        LearnedIcon existing = LEARNED.get(key);
        if (existing != null && existing.modelId.equals(modelId) && existing.itemId.equals(itemId)) {
            return;
        }
        Identifier itemKey = Identifier.tryParse(itemId);
        Identifier modelKey = Identifier.tryParse(modelId);
        if (itemKey == null || modelKey == null) {
            LOGGER.debug("remembered item model for '{}' is unparseable ({} / {})",
                    key, itemId, modelId);
            LEARNED.remove(key);
            return;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemKey);
        if (item.isEmpty()) {
            LOGGER.debug("remembered base item '{}' for '{}' does not exist on this Minecraft "
                    + "version; that drop falls back to its vanilla lookalike", itemKey, key);
            LEARNED.remove(key);
            return;
        }
        LEARNED.put(key, new LearnedIcon(item.get(), modelKey, itemId, modelId));
    }

    /** The Fabric config directory, or null outside a running loader (a bare-JVM test). */
    private static Path configDir() {
        try {
            return FabricLoader.getInstance().getConfigDir();
        } catch (RuntimeException | LinkageError notLoaded) {
            return null;
        }
    }

    // ======================================================================
    //  Matching
    // ======================================================================

    /** The cached resolution for a raw chat name, computed once per distinct raw string. */
    private static Resolved resolve(Table table, String itemName) {
        Resolved cached = RESOLVED.get(itemName);
        if (cached != null) {
            return cached;
        }
        Icon found = lookup(table, itemName);
        Resolved fresh = new Resolved(matchKey(itemName), found == null ? table.fallback : found);
        if (RESOLVED.size() < RESOLVED_CACHE_LIMIT) {
            RESOLVED.put(itemName, fresh);
        }
        return fresh;
    }

    /** @return the mapped row, or null when nothing matched. */
    private static Icon lookup(Table table, String itemName) {
        String key = normalise(itemName);
        if (key.isEmpty()) {
            return null;
        }

        Icon hit = table.byName.get(key);
        if (hit != null) {
            return hit;
        }

        String stripped = stripCount(key);
        if (!stripped.equals(key)) {
            hit = table.byName.get(stripped);
            if (hit != null) {
                return hit;
            }
            key = stripped;
        }

        Matcher paren = PARENTHETICAL.matcher(key);
        if (paren.matches()) {
            hit = table.byName.get(paren.group("inner"));
            if (hit == null) {
                hit = table.byName.get(paren.group("outer"));
            }
            if (hit == null) {
                hit = withoutRomanNumeral(table, paren.group("inner"));
            }
            if (hit != null) {
                return hit;
            }
        }

        return withoutRomanNumeral(table, key);
    }

    /** Retries a name with a trailing enchantment level removed, e.g. "chimera vi". */
    private static Icon withoutRomanNumeral(Table table, String key) {
        Matcher roman = TRAILING_ROMAN.matcher(key);
        return roman.matches() ? table.byName.get(roman.group("rest")) : null;
    }

    /**
     * The form both this table and its JSON keys are written in: no formatting codes, no
     * repeated whitespace, no surrounding whitespace, lower case.
     */
    static String normalise(String itemName) {
        String cleaned = TextClean.clean(itemName);
        return cleaned == null ? "" : cleaned.toLowerCase(Locale.ROOT);
    }

    /**
     * The key the captured and learned tiers are stored under: {@link #normalise} with a count on
     * either end removed.
     *
     * <p>Idempotent, so a key can safely be fed back through it -- which the capture scan does,
     * because it works in keys but hands {@link #learnFrom} a name.
     *
     * @param itemName any display name, may be null
     * @return the key, or the empty string when the name normalises to nothing
     */
    static String matchKey(String itemName) {
        String key = normalise(itemName);
        return key.isEmpty() ? key : stripCount(key);
    }

    /** Removes a leading or trailing count from an already-normalised name. */
    private static String stripCount(String key) {
        Matcher leading = LEADING_COUNT.matcher(key);
        if (leading.matches()) {
            return leading.group("rest").strip();
        }
        Matcher trailing = TRAILING_COUNT.matcher(key);
        if (trailing.matches()) {
            return trailing.group("rest").strip();
        }
        return key;
    }

    // ======================================================================
    //  Loading
    // ======================================================================

    private static Table load() {
        Icon fallback = new Icon(HARD_FALLBACK, false);
        Map<String, Icon> byName = new HashMap<>(96);

        try (InputStream in = DropSymbols.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("SkyPrism drop symbol table {} is missing from the jar; every "
                        + "slot machine reel will draw the fallback item.", RESOURCE);
                return new Table(Map.of(), fallback);
            }

            JsonElement root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("SkyPrism drop symbol table {} is not a JSON object; falling back.",
                        RESOURCE);
                return new Table(Map.of(), fallback);
            }
            JsonObject object = root.getAsJsonObject();

            Icon configured = resolve(string(object, "fallback"), false);
            if (configured != null) {
                fallback = configured;
            }

            int skipped = readEntries(object, byName);
            if (skipped > 0) {
                LOGGER.warn("SkyPrism drop symbol table {} had {} unusable row(s); those drops "
                        + "will draw the fallback item.", RESOURCE, skipped);
            }
            LOGGER.debug("loaded {} drop symbol name(s) from {}", byName.size(), RESOURCE);
        } catch (Exception | LinkageError failure) {
            // A HUD is not a place to throw. Whatever went wrong -- truncated resource,
            // malformed JSON, a registry that is not ready -- the reels still draw.
            LOGGER.warn("SkyPrism could not read the drop symbol table {}; slot machine reels "
                    + "will draw the fallback item.", RESOURCE, failure);
            return new Table(Map.copyOf(byName), fallback);
        } finally {
            // The first time anything asks for a reel sprite, start watching for the real ones.
            // A mod that wires IconCapture.init() at startup gets the memory read on the tick the
            // Diana gate opens, which is where it belongs; this is the safety net that keeps the
            // feature working if nobody does, at the cost of arming one roll late.
            IconCapture.arm();
        }

        return new Table(Map.copyOf(byName), fallback);
    }

    /**
     * @return how many rows had to be skipped
     */
    private static int readEntries(JsonObject object, Map<String, Icon> byName) {
        JsonElement entries = object.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            return 1;
        }

        int skipped = 0;
        for (JsonElement element : entries.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                skipped++;
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            JsonElement names = row.get("names");
            Icon icon = resolve(string(row, "item"), bool(row, "glint"));
            if (icon == null || names == null || !names.isJsonArray()) {
                skipped++;
                continue;
            }

            JsonArray array = names.getAsJsonArray();
            boolean any = false;
            for (JsonElement name : array) {
                if (name == null || !name.isJsonPrimitive()) {
                    continue;
                }
                String key = normalise(name.getAsString());
                if (!key.isEmpty()) {
                    byName.putIfAbsent(key, icon);
                    any = true;
                }
            }
            if (!any) {
                skipped++;
            }
        }
        return skipped;
    }

    /**
     * Turns an item id into a table row.
     *
     * <p>The id is resolved through {@link BuiltInRegistries#ITEM} rather than matched
     * against a hard-coded list, so this is the same question the renderer will ask and
     * an id that is wrong for this Minecraft version is caught here instead of becoming a
     * missing-texture cube on screen.
     *
     * <p>Resolving stops at the {@link Item}. Going one step further and building the stack
     * here is what used to make this method, and therefore the whole class initialiser,
     * capable of throwing before a world existed.
     *
     * @return null when the id is absent, malformed, or names no item on this version
     */
    private static Icon resolve(String id, boolean glint) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Identifier key = Identifier.tryParse(id.strip());
        if (key == null) {
            LOGGER.warn("SkyPrism drop symbol table: '{}' is not a valid item id.", id);
            return null;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(key);
        if (item.isEmpty()) {
            LOGGER.warn("SkyPrism drop symbol table: no item '{}' on this Minecraft version.", key);
            return null;
        }
        return new Icon(item.get(), glint);
    }

    private static String string(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean bool(JsonObject object, String member) {
        JsonElement element = object.get(member);
        try {
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return false;
        }
    }

    // ======================================================================
    //  Test seams
    // ======================================================================

    /**
     * Drops everything learned and captured this session, for a test that needs a clean module.
     *
     * <p>Does not touch the file: a test that wants a clean file writes one.
     */
    /**
     * Whether the learned tier has already built a stack for this key.
     *
     * <p>This is the assertion behind the rule that cost the most to learn: an {@link ItemStack}
     * built before Minecraft has bound its item's data components throws, and throwing out of a
     * class initialiser here once poisoned the whole module and left the slot machine drawing
     * nothing at all. A test that asserts this is still false after a memory has been installed
     * is the thing that will catch anyone quietly moving construction back into the load.
     *
     * @param key a {@link #matchKey} key
     * @return true only once something has actually drawn this symbol
     */
    static boolean learnedStackBuilt(String key) {
        LearnedIcon learned = key == null ? null : LEARNED.get(key);
        return learned != null && learned.peek() != null;
    }

    /** Whether the learned tier claims this key. */
    static boolean hasLearned(String key) {
        return key != null && LEARNED.containsKey(key);
    }

    static void forgetEverythingForTesting() {
        CAPTURED.clear();
        LEARNED.clear();
        RESOLVED.clear();
        memory = null;
        memoryLoadAttempted = false;
    }
}
