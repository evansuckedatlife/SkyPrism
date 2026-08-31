package com.skyprism.mc.loot;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.util.Clock;
import com.skyprism.core.util.SystemClock;
import com.skyprism.core.util.TextClean;
import com.skyprism.mc.hud.LootMachine;

import net.minecraft.network.chat.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * The one thing that calls {@link LootEventBus#onScreenTitle}, and the fuse that protects the
 * player's containers from it.
 *
 * <h2>Why this exists at all</h2>
 * <p>Six sources are triggered by a GUI opening rather than by a line of chat -- Croesus, the two
 * reward chests, the Experimentation Table, the Witches Stew and Ubik's Split or Steal. The bus has
 * always had {@link LootEventBus#onScreenTitle} and the detectors have always implemented it, but
 * until this class existed nothing ever called it. So those six only ever fired from their chat
 * halves, and {@code CROESUS_CHEST} -- whose entire distinction from the Catacombs chest is having
 * seen the Croesus run list -- never armed at all.
 *
 * <h2>Where the title comes from, and where it must not come from</h2>
 * <p>From the open-screen packet, read in {@code ClientPacketListenerOpenScreenMixin}. Checked with
 * {@code javap} against both shipped jars on 2026-08-30 and found identical, instruction for
 * instruction, so no Stonecutter conditional is involved:
 *
 * <pre>
 *   ClientPacketListener.handleOpenScreen(ClientboundOpenScreenPacket)   // 31 bytes, one RETURN
 *   ClientboundOpenScreenPacket.getTitle():Component
 * </pre>
 *
 * <p><b>Not from {@code Minecraft.screen}.</b> That field is public on 26.1.2 and not on 26.2, so
 * reading it would force the first version conditional into a codebase that has none.
 * {@code ContainerDetectors}' own javadoc records the same trap; this class is why it can stay
 * recorded rather than discovered.
 *
 * <h2>Fail safe, because of where this runs</h2>
 * <p>This is called from inside vanilla's packet handling. Anything that escapes it does not
 * degrade a colour or drop a frame -- it takes out the player's ability to open a container, which
 * on Hypixel is the ability to play. So the static entry point catches {@link Throwable}, logs the
 * first failure in full, counts the rest silently, and switches the feed off for the session once
 * {@link #FAILURE_BUDGET} is spent. A dead feed is exactly the behaviour the mod shipped with
 * before this class: every one of these sources still works from chat, and Croesus chests are
 * simply captioned as in-run Catacombs chests. That is why giving up is the right failure mode
 * here rather than a retry loop.
 *
 * <h2>What it costs when nothing is listening</h2>
 * <p>{@link #offer(String, long)} bails on {@code bus.registeredCount() == 0} before it flattens
 * anything, so a player who has switched every source off, or who is not on Hypixel, pays one
 * {@code ArrayList.size()} compare per container opened and allocates nothing. Past that the cost
 * is one {@code getString()} and one strip, on an event that happens a handful of times a minute
 * at worst -- which is what makes a GUI-armed detector the cheapest kind in the whole feature.
 *
 * <h2>How it reaches the machine's bus</h2>
 * <p>{@link LootMachine} owns the registered detectors, keeps its {@link LootEventBus} private and
 * has no screen-title route across it. Feeding a second, privately constructed set of detectors
 * instead would not work: these detectors are stateful <em>across both halves</em>.
 * {@code CroesusChestDetector} arms from a screen title and then claims from a chat broadcast, so
 * arming a copy would arm an object the machine never consults, and the Croesus source would still
 * never fire. The feed therefore has to reach the machine's own bus, and does so by reading that
 * one field reflectively, once, on the first container opened.
 *
 * <p>{@link #bind(ScreenTitleFeed)} is checked first and is the seam that retires the reflection:
 * if {@link LootMachine} ever grows a public screen-title route, wire a feed there at startup and
 * {@link #over(LootMachine, Clock)} is never reached. The field name is written down in exactly
 * that one method, and {@code ScreenTitleFeedMcTest} calls it over a machine of its own, so
 * renaming the field breaks a test rather than silently killing six sources in game.
 *
 * <p><b>Threading:</b> client thread only. The mixin injects at {@code TAIL}, after vanilla's
 * {@code ensureRunningOnSameThread} has already bounced the packet off the netty thread, so this
 * runs once per screen and never off-thread.
 */
public final class ScreenTitleFeed {

    /** Failures tolerated before the feed is switched off for the session. */
    static final int FAILURE_BUDGET = 3;

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Loot");

    /** The feed the mixin dispatches to, resolved lazily or handed over by {@link #bind}. */
    private static ScreenTitleFeed live;

    /** Whether resolution has been tried, so a failed resolve is not retried per container. */
    private static boolean resolveAttempted;

    /** Set once the budget is spent, or once resolution proved impossible. */
    private static boolean off;

    private static int failures;

    private final LootEventBus bus;
    private final LootMachine machine;
    private final Clock clock;

    /**
     * @param bus     the bus holding the registered detectors; must be the machine's own
     * @param machine the machine that decides what an event does, via {@link LootMachine#admit}
     * @param clock   the same clock the machine reads, so an event's stamp and the roll agree
     */
    public ScreenTitleFeed(LootEventBus bus, LootMachine machine, Clock clock) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ======================================================================
    //  The static entry point the mixin calls
    // ======================================================================

    /**
     * Feeds one opened screen's title to whatever is listening. Never throws.
     *
     * <p>The whole body is inside the fuse described in the class notes, lazy resolution included,
     * because a failure there is exactly as fatal to the player's containers as a failure inside a
     * detector would be.
     *
     * @param title the packet's title component; null is ignored rather than thrown
     */
    public static void dispatch(Component title) {
        if (off) {
            return;
        }
        try {
            ScreenTitleFeed feed = live;
            if (feed == null) {
                if (resolveAttempted) {
                    return;
                }
                resolveAttempted = true;
                feed = over(LootMachine.get(), SystemClock.INSTANCE);
                live = feed;
            }
            feed.onScreenOpened(title);
        } catch (Throwable failure) {
            record(failure);
        }
    }

    /**
     * Points the static entry point at a feed, skipping reflection entirely.
     *
     * @param feed the feed to dispatch to; null clears the binding and re-arms resolution
     */
    public static void bind(ScreenTitleFeed feed) {
        live = feed;
        resolveAttempted = feed != null;
        if (feed != null) {
            off = false;
            failures = 0;
        }
    }

    /** Whether the fuse has blown and the feed has stopped for this session. */
    public static boolean disabled() {
        return off;
    }

    /** Clears the static state. Tests only; the mod never unbinds. */
    public static void resetForTesting() {
        live = null;
        resolveAttempted = false;
        off = false;
        failures = 0;
    }

    /**
     * Builds a feed over a machine's own {@link LootEventBus}, reflectively.
     *
     * <p>The one place the field name {@code LootMachine.bus} is written down. It is public and
     * takes the machine as a parameter rather than reading the singleton, for one reason: that
     * makes it reachable from a headless test over a machine of the test's own, so the reflection
     * production depends on is <em>covered</em> rather than only exercised in game. Rename the
     * field and {@code ScreenTitleFeedMcTest} fails, which is the whole point.
     *
     * <p>See the class notes for why a fresh detector set is not an option here.
     *
     * @param machine the machine whose bus and admission policy the feed should use
     * @param clock   the clock to stamp events with; pass the machine's own
     * @return a feed wired to that machine
     */
    public static ScreenTitleFeed over(LootMachine machine, Clock clock) {
        Objects.requireNonNull(machine, "machine");
        return new ScreenTitleFeed(machine.bus(), machine, clock);
    }

    /** The failure budget, spelled the way {@code LevelSurfaces} spells its own. */
    private static void record(Throwable failure) {
        failures++;
        if (failures == 1) {
            LOGGER.warn("SkyPrism's screen-title feed failed; the GUI-triggered loot sources will "
                    + "miss this container. Further failures will be counted silently.", failure);
        }
        if (failures >= FAILURE_BUDGET && !off) {
            off = true;
            LOGGER.error("SkyPrism's screen-title feed is disabled after {} failures. The six "
                            + "GUI-triggered sources fall back to their chat halves for this "
                            + "session.", failures);
        }
    }

    // ======================================================================
    //  The instance path, which is what the tests drive
    // ======================================================================

    /**
     * Reads the title off a component and offers it, on this feed's own clock.
     *
     * <p>The gate is tested <em>before</em> {@link Component#getString()}, because flattening is
     * the only allocation on this path and a player with every source switched off should not pay
     * it to open a backpack.
     *
     * @param title the title component, may be null
     * @return whether a detector produced an event
     */
    public boolean onScreenOpened(Component title) {
        if (title == null || bus.registeredCount() == 0) {
            return false;
        }
        return offer(title.getString(), clock.millis());
    }

    /**
     * Offers one title to the bus and hands anything it produces to the machine.
     *
     * <p>Formatting is stripped rather than left alone: the detectors match on readable text, and
     * {@code Component.getString()} still carries whatever legacy section codes Hypixel wrote into
     * the title's own <em>content</em>. Stripped rather than fully {@link TextClean#clean cleaned}
     * because every detector on this path cleans the string itself, and cleaning twice is a second
     * regex pass for an identical answer.
     *
     * <p>An event is handed to {@link LootMachine#admit}, not started directly -- that is the same
     * door {@link LootMachine#onChat} puts its own events through, so a screen-triggered source
     * gets the same policy, the same rapid-fire floor and the same deference to a running Diana
     * roll as a chat-triggered one.
     *
     * @param rawTitle  the title as text, formatting codes and all; null and empty are ignored
     * @param nowMillis the instant to stamp on any event
     * @return whether a detector produced an event
     */
    public boolean offer(String rawTitle, long nowMillis) {
        // First, and before anything is allocated: with nothing registered there is no detector to
        // offer this to, and onScreenTitle would walk an empty list to the same answer.
        if (bus.registeredCount() == 0 || rawTitle == null || rawTitle.isEmpty()) {
            return false;
        }
        String plain = TextClean.stripFormatting(rawTitle);
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        Optional<LootEvent> event = bus.onScreenTitle(plain, nowMillis);
        if (event.isEmpty()) {
            return false;
        }
        machine.admit(event.get(), nowMillis);
        return true;
    }
}
