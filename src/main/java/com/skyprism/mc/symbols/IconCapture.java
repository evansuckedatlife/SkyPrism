package com.skyprism.mc.symbols;

import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.mc.diana.DianaController;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Watches the player's own inventory for the drops the Diana pipeline just announced, and hands
 * the genuine stacks to {@link DropSymbols}.
 *
 * <h2>Why the stack has to come from the player</h2>
 * <p>Hypixel's server resource pack addresses its art through the vanilla
 * {@code minecraft:item_model} component, which the server sets on the item it sends. There is no
 * table anywhere that turns "Daedalus Stick" into
 * {@code hypixel_skyblock:item/community_center/mayor/diana/daedalus_stick} -- the pack's paths are
 * semantic and it carries no display-name index. The only place the answer exists is on the stack
 * itself, so the only way to draw a reel that matches what the player sees is to look at what the
 * player got. See {@link IconMemory} for what happens to that answer afterwards.
 *
 * <h2>What it costs when nothing is happening</h2>
 * <p>This is a tick listener, and a tick listener that scanned an inventory unconditionally would
 * be exactly the kind of thing this mod refuses to ship. It does not. In order:
 *
 * <ol>
 *   <li>Diana gate shut -- one boolean field read, return. That is every tick of every session for
 *       every player who is not doing Diana with Diana in office.</li>
 *   <li>Gate open, no roll and no container window -- a cached reference, one {@code long}
 *       comparison inside {@link SlotRoll#active()}, and two reference compares. No allocation.</li>
 *   <li>Roll live but every announced drop already captured or already known -- one int compare,
 *       return. This is the steady state once a player has run Diana for an evening, because the
 *       memory persists: nothing is ever scanned again for a drop already learned.</li>
 *   <li>Roll live with something still unknown -- the scan below, which is bounded by the
 *       inventory size and skips every slot whose stack has not changed.</li>
 * </ol>
 *
 * <h2>How arrival is detected</h2>
 * <p>Not by polling display names, which would allocate a string per slot per tick. The scan holds
 * the previous {@link ItemStack} <em>reference</em> for each slot and looks only at slots where
 * that reference changed -- and a slot update from the server always installs a new object, so a
 * changed reference is precisely "something arrived here". The snapshot is discarded whenever the
 * set of wanted drops changes, so the tick a drop is announced re-examines every slot once; that
 * pass is what also picks up a drop the player already had one of, which is just as good a source
 * of the art and costs nothing extra.
 *
 * <p>Matching prefers SkyBlock's own item id from {@code ExtraAttributes} over the display name,
 * because the id is the half Hypixel does not re-word. See {@link SkyBlockIds}.
 *
 * <h2>What is kept</h2>
 * <p>A copy, never a reference. The inventory's stacks are rewritten in place by the server, so a
 * held reference would mean the reel drew whatever the slot became. {@link DropSymbols#learnFrom}
 * takes the copy and does the remembering.
 *
 * <p>Nothing here throws. A failure disarms the listener for the rest of the session rather than
 * repeating itself twenty times a second, and the reels fall back to their vanilla lookalikes.
 *
 * <p><b>Threading:</b> client thread only.
 */
public final class IconCapture {

    private IconCapture() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Symbols");

    /**
     * How long after a roll goes idle a container window still counts as its loot window.
     *
     * <p>Diana's drops normally land while the reels are still spinning, but Hypixel also shows
     * some rewards in a chest GUI the player opens a moment later, and the stack in that GUI is
     * every bit as genuine. Long enough to cover opening it, short enough that a chest opened for
     * some unrelated reason ten minutes later is not credited to a fight nobody remembers.
     */
    private static final long WINDOW_GRACE_MILLIS = 60_000L;

    /** Nothing is scanned for a drop with no chance of being an item. */
    private static final int MAX_WANTED = 16;

    private static boolean armed;
    private static boolean disabled;

    /** The roll being watched, cached so the tick path is not an allocation. */
    private static SlotRoll roll;
    private static int rollEpoch = Integer.MIN_VALUE;

    /** The drops still worth looking for, as {@link DropSymbols#matchKey} keys; nulled as taken. */
    private static String[] wanted = new String[0];
    private static int remaining;

    /** What the wanted set was built from, so it is rebuilt exactly when it is stale. */
    private static long wantedRollId = Long.MIN_VALUE;
    private static int wantedDropCount = -1;

    /** Previous stack references, per slot. Null means "look at everything next pass". */
    private static ItemStack[] inventorySeen;
    private static ItemStack[] menuSeen;
    private static int menuSeenId = Integer.MIN_VALUE;

    /** When a roll was last running, for the container-window grace period. */
    private static long lastActiveAt = Long.MIN_VALUE;

    /**
     * Starts watching. Idempotent, so a second entrypoint calling it is harmless, and safe to call
     * at any point in startup -- it registers listeners and touches nothing else.
     *
     * <p>Also called from {@link DropSymbols} the first time the symbol table is read, so the
     * feature works whether or not anyone wired it explicitly; a mod that never draws a reel never
     * arms it at all.
     */
    public static void init() {
        arm();
    }

    /** The body of {@link #init()}. Never throws, however early it runs. */
    static synchronized void arm() {
        if (armed) {
            return;
        }
        armed = true;
        try {
            ClientTickEvents.END_CLIENT_TICK.register(IconCapture::onEndTick);
            // Both edges where a deferred write would lose what this session learned. The autosave
            // interval is ten seconds, and a player who kills an Inquisitor and immediately
            // disconnects would otherwise have to learn it again.
            ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
                DropSymbols.saveMemory();
                forgetScanState();
            });
            ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> DropSymbols.saveMemory());
        } catch (Exception | LinkageError noLoader) {
            // A bare-JVM test has no Fabric event bus. That is not a failure worth a warning:
            // everything else in this module works without one.
            disabled = true;
            LOGGER.debug("SkyPrism item capture is not available in this environment", noLoader);
        }
    }

    /**
     * The per-tick work. See the class javadoc for what each early-out costs.
     *
     * @param mc the client, never null from Fabric
     */
    private static void onEndTick(Minecraft mc) {
        if (disabled) {
            return;
        }
        try {
            DianaController controller = DianaController.get();
            if (!controller.gate().isOpen()) {
                return;
            }
            // The gate opening is the cheapest moment in the whole feature to read a file: the
            // player has just arrived on the Hub with Diana in office, minutes before anything
            // dies. Doing it on the frame a reel first spins is the hitch this avoids.
            DropSymbols.ensureMemoryLoaded();

            long now = System.currentTimeMillis();
            SlotRoll live = currentRoll(controller);
            boolean active = live.active();
            if (active) {
                lastActiveAt = now;
            } else if (!lootWindowOpen(mc) || now - lastActiveAt > WINDOW_GRACE_MILLIS) {
                DropSymbols.maybeSaveMemory(now);
                return;
            }

            refreshWanted(live, active);
            if (remaining > 0) {
                scan(mc);
            }
            DropSymbols.maybeSaveMemory(now);
        } catch (Exception | LinkageError broken) {
            // Twenty of these a second would bury the log, and a mismatched reel sprite is not
            // worth one line of it beyond the first.
            disabled = true;
            LOGGER.warn("SkyPrism stopped capturing Diana item art after an error; reels will "
                    + "draw their built-in vanilla lookalikes for the rest of this session.",
                    broken);
        }
    }

    /**
     * The controller's roll, re-fetched only when it has actually been replaced.
     *
     * <p>{@code DianaController.roll()} builds a {@code SlotRollConfig} to decide whether a swap is
     * due, so calling it every tick would put an allocation on a path that is meant to have none.
     * {@code rollEpoch()} is an int field read and changes exactly when the answer would differ.
     */
    private static SlotRoll currentRoll(DianaController controller) {
        int epoch = controller.rollEpoch();
        if (roll == null || epoch != rollEpoch) {
            roll = controller.roll();
            rollEpoch = controller.rollEpoch();
            // A replacement roll counts its own fights from zero, so a rollId held from the
            // retired one would read as "same fight" and keep a stale wanted list alive.
            wantedRollId = Long.MIN_VALUE;
            forgetScanState();
        }
        return roll;
    }

    /**
     * Whether the player has a container open -- a chest, a reward GUI, anything that is not their
     * own inventory screen.
     *
     * <p>Read from the menu rather than from the open {@code Screen}, deliberately: the client's
     * {@code screen} field is public on 26.1.2 and not on 26.2, and this mod compiles both versions
     * from byte-identical source. The menu is public on both and is the more accurate question
     * anyway, since it is the menu, not the screen, that holds the stacks.
     */
    private static boolean lootWindowOpen(Minecraft mc) {
        LocalPlayer player = mc == null ? null : mc.player;
        return player != null && player.containerMenu != null
                && player.containerMenu != player.inventoryMenu;
    }

    /**
     * Rebuilds the list of drops still worth looking for, but only when the roll has moved on.
     *
     * <p>{@code rollId} changing means a new fight, {@code capturedDropCount} changing means a new
     * drop line. Both are field reads. Only when one of them moves is the drop list -- which
     * allocates -- asked for at all.
     *
     * <p>The list is deliberately <em>not</em> rebuilt once the roll has stopped running, because
     * {@link SlotRoll#capturedDrops()} answers empty when idle and rebuilding then would throw away
     * exactly the names the grace period exists to keep looking for. A drop announced in chat and
     * then handed over in a chest GUI ten seconds later is a real Hypixel flow, and forgetting what
     * the fight dropped the moment the reels stop would make it uncapturable. The list is cleared
     * when {@code rollId} changes, which is the next fight, and never lives longer than that.
     *
     * @param live   the roll being watched
     * @param active whether it is still running
     */
    static void refreshWanted(SlotRoll live, boolean active) {
        long id = live.rollId();
        if (id != wantedRollId) {
            wantedRollId = id;
            wantedDropCount = -1;
            wanted = new String[0];
            remaining = 0;
            forgetScanState();
        }
        if (!active) {
            return;
        }
        int count = live.capturedDropCount();
        if (count == wantedDropCount) {
            return;
        }
        wantedDropCount = count;

        List<LootDrop> drops = live.capturedDrops();
        String[] keys = new String[Math.min(drops.size(), MAX_WANTED)];
        int n = 0;
        for (LootDrop drop : drops) {
            if (n == keys.length) {
                break;
            }
            if (drop == null) {
                continue;
            }
            String key = DropSymbols.matchKey(drop.itemName());
            // Already captured this session is the common case on a second Inquisitor, and there
            // is nothing a second look could improve.
            if (key.isEmpty() || DropSymbols.hasCaptured(key) || contains(keys, n, key)) {
                continue;
            }
            keys[n++] = key;
        }
        wanted = keys;
        remaining = n;
        // A new drop means new slots to look at, including ones whose reference has not changed
        // since the last pass -- the player may already have been holding one.
        forgetScanState();
    }

    private static boolean contains(String[] keys, int size, String key) {
        for (int i = 0; i < size; i++) {
            if (key.equals(keys[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks at every slot whose stack has changed since the last pass, in the player's inventory
     * and in whatever container they have open.
     */
    private static void scan(Minecraft mc) {
        LocalPlayer player = mc == null ? null : mc.player;
        if (player == null) {
            forgetScanState();
            return;
        }

        Inventory inventory = player.getInventory();
        int size = inventory.getContainerSize();
        if (inventorySeen == null || inventorySeen.length != size) {
            inventorySeen = new ItemStack[size];
        }
        for (int i = 0; i < size && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack == inventorySeen[i]) {
                continue;
            }
            inventorySeen[i] = stack;
            consider(stack);
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            menuSeen = null;
            menuSeenId = Integer.MIN_VALUE;
            return;
        }
        if (menuSeenId != menu.containerId) {
            menuSeenId = menu.containerId;
            menuSeen = null;
        }
        List<Slot> slots = menu.slots;
        if (menuSeen == null || menuSeen.length != slots.size()) {
            menuSeen = new ItemStack[slots.size()];
        }
        for (int i = 0; i < menuSeen.length && remaining > 0; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack == menuSeen[i]) {
                continue;
            }
            menuSeen[i] = stack;
            consider(stack);
        }
    }

    /**
     * Decides whether one stack is one of the drops being looked for, and takes it if so.
     *
     * <p>The id is tried first. A stack whose {@code ExtraAttributes.id} says
     * {@code DAEDALUS_STICK} is a Daedalus Stick whatever Hypixel has decided to call it this
     * week, and that is a stronger statement than any display name can make.
     */
    private static void consider(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String byId = SkyBlockIds.matchKeyOf(stack);
        if (byId != null && take(byId, stack)) {
            return;
        }
        String byName = DropSymbols.matchKey(stack.getHoverName().getString());
        if (!byName.isEmpty()) {
            take(byName, stack);
        }
    }

    /** @return true when the key was one we wanted and the stack has now been learned from */
    private static boolean take(String key, ItemStack stack) {
        for (int i = 0; i < wanted.length; i++) {
            if (!key.equals(wanted[i])) {
                continue;
            }
            DropSymbols.learnFrom(wanted[i], stack);
            wanted[i] = null;
            remaining--;
            return true;
        }
        return false;
    }

    /** Forces the next scan to look at every slot again. */
    private static void forgetScanState() {
        inventorySeen = null;
        menuSeen = null;
        menuSeenId = Integer.MIN_VALUE;
    }

    // ======================================================================
    //  Test seams
    // ======================================================================

    /** How many drops the scan is currently still looking for. */
    static int wantedCountForTesting() {
        return remaining;
    }

    /** Puts the capture state back the way a fresh process would find it. */
    static void resetForTesting() {
        roll = null;
        rollEpoch = Integer.MIN_VALUE;
        wanted = new String[0];
        remaining = 0;
        wantedRollId = Long.MIN_VALUE;
        wantedDropCount = -1;
        lastActiveAt = Long.MIN_VALUE;
        forgetScanState();
    }

    /**
     * Drives the match-and-take step directly, without a client.
     *
     * <p>The scan around it is Minecraft's inventory, which cannot be built headlessly; this is the
     * part with the logic in it, and it is the part a test can and should pin down.
     *
     * @param dropNames the drop names the pipeline parsed
     * @param stack     a stack as it would have arrived
     * @return true when the stack matched one of those names
     */
    static boolean offerForTesting(List<String> dropNames, ItemStack stack) {
        String[] keys = new String[dropNames.size()];
        int n = 0;
        for (String name : dropNames) {
            String key = DropSymbols.matchKey(name);
            if (!key.isEmpty()) {
                keys[n++] = key;
            }
        }
        wanted = keys;
        remaining = n;
        int before = remaining;
        consider(stack);
        return remaining < before;
    }
}
