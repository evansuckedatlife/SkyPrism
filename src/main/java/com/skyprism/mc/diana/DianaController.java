package com.skyprism.mc.diana;

import com.skyprism.core.config.ConfigCodec;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.DianaGate;
import com.skyprism.core.diana.DianaPatterns;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.LootParser;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.util.Clock;
import com.skyprism.core.util.SystemClock;
import com.skyprism.mc.chat.DianaLineFilter;
import com.skyprism.mc.hud.LootMachine;
import com.skyprism.mc.text.LegacyText;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides when the Diana slot machine spins, and feeds it what the player actually received.
 *
 * <h2>The pipeline</h2>
 * <ol>
 *   <li><b>Gate.</b> {@link HypixelContext} folds server address, SkyBlock sidebar, mayor and island
 *       into a {@link DianaGate}. Every handler below reads {@link DianaGate#isOpen()} on its first
 *       line, so with Diana out of office the whole feature is a boolean test per event.</li>
 *   <li><b>Spawn.</b> {@link DianaPatterns#matchSpawn} names the creature the player just dug out.
 *       That arms {@link CreatureTracker} and starts the loot fallback's clock.</li>
 *   <li><b>Bind.</b> {@link CreatureTracker} attaches to the entity whose nametag names the creature,
 *       from the entity-load event and from a bounded query around the player. Never from a
 *       whole-world iteration.</li>
 *   <li><b>Defeat.</b> The bound entity dying starts the roll. A creature killed out of view never
 *       binds, so the first drop line inside the spawn's lifetime starts it instead.</li>
 *   <li><b>Loot.</b> For {@code diana.lootWindowMillis} after the defeat every line goes through
 *       {@link LootParser} and lands on {@link SlotRoll#offerDrop}.</li>
 *   <li><b>Stats.</b> {@link DianaStats} counts it and writes at most once a minute.</li>
 * </ol>
 *
 * <h2>"Unregister when the gate closes" -- what that means in Fabric</h2>
 * <p>Fabric's {@code Event} has {@code register} and no {@code unregister}; a listener attached at
 * mod-init is attached for the process. The intent behind the rule is still honoured, in the only way
 * the API allows: every handler's first statement is a field read that returns when the gate is shut,
 * nothing downstream of that read allocates, and the falling edge -- observed through
 * {@link DianaGate#consumeChanged()} -- actively tears state down, dropping the bound entity, resetting
 * the roll and flushing stats. The one handler that must keep working while the gate is shut is the
 * throttled poll that would notice Diana being elected, and it returns after comparing two longs.
 *
 * <h2>Two ways in for chat, one path through</h2>
 * <p>This class registers its own chat listener, but {@link #onChatMessage(String, long)} is public so
 * a sibling module that has already reconstructed the line can hand it over instead of paying for a
 * second reconstruction. Both routes converge, and a line seen twice within
 * {@value #DUPLICATE_WINDOW_MILLIS} ms is processed once -- otherwise a drop would be offered to the
 * reels and counted in the stats twice over.
 *
 * <p>Note the deliberate asymmetry: the registered listener checks the gate, the public method does
 * not. The listener's check is what buys the zero-cost property. The public method is the injection
 * point used by {@code /skyprism simulate} and by tests, where insisting on a live Hypixel connection
 * would make the feature untestable.
 *
 * <p><b>Threading:</b> client thread only, matching {@link DianaGate} and {@link SlotRoll}.
 */
public final class DianaController {

    /** Two deliveries of the same line inside this window are the same line. */
    private static final long DUPLICATE_WINDOW_MILLIS = 50L;

    /**
     * How long a spawn stays eligible to start a roll from a drop line alone.
     *
     * <p>Long enough for a genuinely difficult fight -- an Inquisitor at low gear takes minutes -- and
     * short enough that a drop from some unrelated activity half an hour later cannot be credited to
     * a creature the player has long since forgotten.
     */
    private static final long SPAWN_TTL_MILLIS = 300_000L;

    private static final DianaController INSTANCE = new DianaController(SystemClock.INSTANCE);

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Diana");

    private final Clock clock;
    private final DianaGate gate = new DianaGate();
    private final HypixelContext context;
    private final CreatureTracker tracker = new CreatureTracker();
    private final LootParser lootParser = new LootParser();

    /** Built lazily, because it needs the config and the config needs a running Fabric loader. */
    private SlotRoll roll;
    private SlotRollConfig rollConfig;

    /**
     * Bumped every time {@link #ensureRoll()} installs a different {@link SlotRoll}.
     *
     * <p>The HUD caches the roll reference so the render path is a field read rather than a
     * {@code SlotRollConfig} allocation per frame, and that cache used to be refreshed only from
     * a configuration change listener. The listener is not a sufficient signal, because a rebuild
     * is <em>deferred</em> while a roll is on screen: change a Diana timing mid-spin and the
     * listener fires while {@code ensureRoll} is still declining to swap, so the HUD re-caches the
     * instance that is about to be retired and the actual swap happens later with nothing
     * announcing it. From then on the HUD holds a permanently idle roll and the slot machine never
     * draws again for the rest of the session. This counter is the announcement, and reading it is
     * an int compare on the idle path.
     */
    private int rollEpoch;

    /** The area whitelist last pushed into the gate, compared by reference to avoid rebuilding it. */
    private Set<String> appliedAreas;

    private DianaStats stats;

    private Supplier<SkyPrismConfig> configSupplier;
    private SkyPrismConfig fallbackConfig;

    private boolean initialised;

    /** The creature a spawn line named and nothing has yet consumed. */
    private MythologicalCreature pendingCreature;
    private long pendingAt;

    /** End of the window during which drop lines still reach the reels. */
    private long lootDeadline;

    /** True between a roll starting and its loot window closing, so the roll is counted exactly once. */
    private boolean rollPendingTally;

    private String lastLine;
    private long lastLineAt = Long.MIN_VALUE;

    /**
     * Builds a controller on an injected clock.
     *
     * <p>Public so a test, or a replay harness, can drive the whole pipeline from a
     * {@link com.skyprism.core.util.FixedClock} without a game running. Production code uses
     * {@link #get()}.
     *
     * @param clock the time source, never null
     */
    public DianaController(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.context = new HypixelContext(gate);
    }

    /** The controller the mod actually runs on, driven by the system clock. */
    public static DianaController get() {
        return INSTANCE;
    }

    /**
     * Registers every listener the feature needs. Idempotent, so a second entrypoint calling it is
     * harmless.
     *
     * <p>Registration order matters in one place: the chat listener is
     * {@code ClientReceiveMessageEvents.ALLOW_GAME} rather than {@code GAME}, because suppressing a
     * drop line -- which is what {@code diana.suppressDropChatLines} asks for -- is only possible from
     * an allow event, and a line vetoed there never reaches {@code GAME} at all. Doing the parsing in
     * the same callback keeps the line's fate and its contents in one decision.
     */
    public void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        ClientReceiveMessageEvents.ALLOW_GAME.register(this::allowGameMessage);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) ->
                context.onJoin(HypixelContext.currentAddress(mc)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            context.onDisconnect();
            hardStop();
            stats().save();
        });

        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!gate.isOpen()) {
                return;
            }
            tracker.onEntityLoad(entity, clock.millis());
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (!gate.isOpen() || !tracker.bound()) {
                return;
            }
            tracker.onEntityUnload(entity, playerPosition(), clock.millis());
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);

        // A world change tears everything down, exactly as a disconnect does. On Hypixel a proxy
        // warp between islands is a respawn packet rather than a disconnect, so DISCONNECT alone
        // left the tracker's expectation, a half-finished roll and a pending spawn alive across a
        // move to an island where none of them mean anything. Fabric's own respawn handling
        // unloads every entity in the level first, which is why CreatureTracker independently
        // refuses to read an unload as a kill without a death signal; this hook is the other half.
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> hardStop());

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stats().save());
    }

    /** The live roll, for the HUD to render. Created on first use. */
    public SlotRoll roll() {
        ensureRoll();
        return roll;
    }

    /**
     * A counter that changes whenever {@link #roll()} would return a different object.
     *
     * <p>For a caller that caches the reference: compare this against the value held alongside the
     * cache and re-read when they differ. It exists because the swap is deferred past the
     * configuration change that caused it, so a configuration listener is not the signal it looks
     * like. See the field's own documentation.
     *
     * @return the current roll generation; compare for inequality, never for ordering
     */
    public int rollEpoch() {
        return rollEpoch;
    }

    /** The gate, for a status command or a sibling module that wants the same four conditions. */
    public DianaGate gate() {
        return gate;
    }

    /** The persistent tally. Loaded on first use. */
    public DianaStats stats() {
        if (stats == null) {
            stats = DianaStats.load(statsPath());
        }
        return stats;
    }

    /**
     * Points the controller at the mod's shared configuration.
     *
     * <p>Until this is called the controller loads its own copy from the Fabric config directory, so
     * it is functional standalone; once a config module owns the file it should call this so both see
     * the same instance and a settings change takes effect without a restart.
     *
     * @param supplier returns the current config; null restores the built-in loader
     */
    public void setConfigSupplier(Supplier<SkyPrismConfig> supplier) {
        this.configSupplier = supplier;
    }

    /**
     * Forces or clears the "am I on Hypixel" answer.
     *
     * @param value true or false to force, null to go back to reading the server address
     */
    public void setHypixelOverride(Boolean value) {
        context.setHypixelOverride(value);
    }

    /**
     * Runs a roll by hand, ignoring the gate, the triggers and the loot window's usual gatekeeping.
     *
     * <p>This is what {@code /skyprism simulate} calls, and it deliberately does <b>not</b> touch
     * {@link #stats()}: a demonstration that inflated the player's Inquisitor count would corrupt the
     * one number the feature exists to make meaningful.
     *
     * @param creature the creature to show, never null
     * @param drops    the drops to lock the reels onto; null and null elements are ignored, an empty
     *                 list produces the "No Drop" result the machine shows for a barren kill
     */
    public void simulate(MythologicalCreature creature, List<LootDrop> drops) {
        Objects.requireNonNull(creature, "creature");
        ensureRoll();
        roll.start(creature);
        if (drops != null) {
            for (LootDrop drop : drops) {
                if (drop != null) {
                    roll.offerDrop(drop);
                }
            }
        }
        pendingCreature = null;
        rollPendingTally = false;
        lootDeadline = clock.millis() + lootWindowMillis(config());
    }

    /**
     * Feeds one chat line into the pipeline.
     *
     * <p>Deliberately does not consult the gate; see the class javadoc. The registered listener has
     * already done so, and the other callers -- a command, a test -- have no gate to consult.
     *
     * @param rawWithCodes the line in its legacy section-sign form, which is what the core's patterns
     *                     were written against; may be null
     * @param nowMillis    the current time in milliseconds
     */
    public void onChatMessage(String rawWithCodes, long nowMillis) {
        handleLine(rawWithCodes, nowMillis);
    }

    /**
     * The body of {@link #onChatMessage}, reporting whether the line was consumed as loot.
     *
     * @return true when the line announced a drop that reached an active roll, which is the only case
     *         {@code suppressDropChatLines} is entitled to hide
     */
    private boolean handleLine(String raw, long now) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.equals(lastLine) && now - lastLineAt <= DUPLICATE_WINDOW_MILLIS) {
            return false;
        }
        lastLine = raw;
        lastLineAt = now;

        SkyPrismConfig config = config();
        if (!config.diana.enabled) {
            return false;
        }

        Optional<MythologicalCreature> spawned = DianaPatterns.matchSpawn(raw);
        if (spawned.isPresent()) {
            onSpawn(spawned.get(), now);
            return false;
        }

        if (!config.diana.onlyMyBurrows && isSharedInquisitor(raw)) {
            onSpawn(MythologicalCreature.MINOS_INQUISITOR, now);
            return false;
        }

        List<LootDrop> drops = lootParser.parse(raw);
        if (drops.isEmpty()) {
            return false;
        }
        return onDrops(drops, now, config);
    }

    /**
     * A spawn line arrived: remember the creature and start hunting for its nametag.
     *
     * <p>The tracker is armed for every creature, not only the configured triggers, because the bind
     * costs nothing until an entity actually matches and knowing what died is useful to the stats
     * even when the machine stays quiet. The trigger filter is applied where it belongs -- at the
     * moment a roll would start.
     */
    private void onSpawn(MythologicalCreature creature, long now) {
        pendingCreature = creature;
        pendingAt = now;
        tracker.expect(creature, now);
        tracker.scanNearby(Minecraft.getInstance(), now);
    }

    /**
     * Drop lines arrived.
     *
     * <p>With no roll running this is the out-of-view fallback: a creature was announced, we never saw
     * it die, and its loot is landing now. With a roll running the drops simply feed the reels, as
     * long as the loot window is still open -- past the deadline they are the next fight's problem,
     * and letting them in would put a stale symbol on a reel.
     */
    private boolean onDrops(List<LootDrop> drops, long now, SkyPrismConfig config) {
        ensureRoll();
        if (!roll.active()) {
            MythologicalCreature candidate = pendingCreature;
            if (candidate == null || now - pendingAt > SPAWN_TTL_MILLIS) {
                return false;
            }
            if (!isTrigger(candidate, config)) {
                pendingCreature = null;
                return false;
            }
            beginRoll(candidate, now, config);
        }

        if (now > lootDeadline) {
            return false;
        }
        // Bracket the feed so "the roll took this drop" is observed rather than assumed. Returning
        // the toggle unconditionally vetoed the chat line for a drop the machine had merely
        // *recorded*: SlotRoll.offerDrop silently discards a drop once the roll stops running, and
        // reels() only ever renders reelCount symbols however many drops were captured, so a
        // fourth drop inside a three-reel window was hidden from chat and shown nowhere -- the one
        // outcome a feature that hides chat lines must never produce. Suppression is therefore
        // also capped at the number of columns that can actually display the result.
        int before = roll.capturedDropCount();
        for (LootDrop drop : drops) {
            roll.offerDrop(drop);
            stats().recordDrop(drop);
        }
        boolean reachedAReel = before < config.diana.reelCount;
        return config.diana.suppressDropChatLines && reachedAReel
                && roll.capturedDropCount() > before;
    }

    /** Starts a roll and opens its loot window. */
    private void beginRoll(MythologicalCreature creature, long now, SkyPrismConfig config) {
        ensureRoll();
        roll.start(creature);
        lootDeadline = now + lootWindowMillis(config);
        rollPendingTally = true;
        pendingCreature = null;
        tracker.clear();
        stats().recordKill(creature);
    }

    /**
     * The per-tick work, which for a player who is not doing Diana is: one config read, one
     * reference compare, one throttled long comparison, one edge check, one boolean test, return.
     *
     * <p>What sits behind that throttle is itself scoped by what is switched on: the eighty-row
     * mayor walk only runs on a SkyBlock island with the feature enabled, and with both the Diana
     * feature and the level feature's SkyBlock scope switched off the world is not read at all.
     */
    private void onEndTick(Minecraft mc) {
        long now = clock.millis();

        SkyPrismConfig config = config();
        syncAllowedAreas(config);
        // The level feature's server scope reads the SkyBlock half of this gate, so the sidebar
        // poll is still wanted with Diana switched off -- but the eighty-row mayor walk is not.
        // The general machine's gates are almost all "am I on this island", so it is a third
        // caller that wants the sidebar read -- and, unlike the other two, it wants it whether or
        // not Diana is in office. The expensive half, the eighty-row mayor walk, is deliberately
        // left on config.diana.enabled alone: the mayor is Diana's condition and nobody else's.
        //
        // One field read on an ArrayList, and it answers false for a player who has switched every
        // source off, which is what keeps "shut costs nothing" true all the way down to the poll.
        LootMachine machine = LootMachine.get();
        boolean wantsIsland = config.diana.enabled || config.levels.onlyOnSkyBlock
                || machine.wantsWorldFacts();
        context.poll(mc, now, wantsIsland, config.diana.enabled);

        // Hand the general machine the four world facts the poll above just refreshed, so its
        // gates -- which are almost all "am I on this island" -- can be re-evaluated on island
        // change and never per tick.
        //
        // Primitives rather than a GameContext, and unconditional rather than behind an edge
        // check, because LootMachine.updateContext is three boolean compares and one string
        // compare when nothing moved and allocates only when something did. The alternative,
        // gate.consumeChanged(), is the wrong signal here: it reports open/closed transitions of
        // the *Diana* gate and stays silent on the island changes the bus cares most about.
        //
        // Note this must run whatever config.diana.enabled says. The sidebar poll it depends on is
        // already kept alive for the level feature's SkyBlock scope, and a player who has switched
        // the Mythological Ritual off has not switched off dungeons.
        machine.updateContext(gate.onHypixel(), gate.inSkyBlock(), gate.area(), gate.mayorDiana());

        if (gate.consumeChanged()) {
            // Both edges, at INFO. From the outside "Diana is not the mayor" and "SkyPrism cannot
            // read the mayor row" are the same symptom -- nothing ever happens -- with completely
            // different fixes, and this is the line that separates them without the player having
            // to know /skyprism status exists.
            LOGGER.info("SkyPrism Diana gate {}", gate.describe());
            if (!gate.isOpen()) {
                hardStop();
            }
        }
        if (!gate.isOpen()) {
            return;
        }

        MythologicalCreature defeated = tracker.pollDefeat(now);
        if (defeated != null) {
            if (config.diana.enabled && isTrigger(defeated, config)) {
                beginRoll(defeated, now, config);
            } else {
                // Still worth counting: the player killed it, the machine simply stayed quiet.
                stats().recordKill(defeated);
                pendingCreature = null;
            }
        } else {
            tracker.scanNearby(mc, now);
        }

        if (rollPendingTally && now > lootDeadline) {
            rollPendingTally = false;
            ensureRoll();
            stats().recordRoll(roll.jackpot());
        }

        stats().maybeSave(now);
    }

    /**
     * The chat hook.
     *
     * <p>Four bails before anything is parsed, in increasing cost: action-bar lines are not chat; a
     * shut gate means Diana is not even in office; a disabled feature means the player asked for
     * nothing; and a line whose plain text carries none of Hypixel's three announcement markers cannot
     * be a spawn, a treasure or a drop. Only past all four is the component walked back into its
     * legacy form, which is the one genuinely allocating step in the path.
     */
    private boolean allowGameMessage(Component message, boolean overlay) {
        if (overlay || message == null) {
            return true;
        }

        // The two cheap conditions, before anything is allocated at all.
        //
        // Diana's is unchanged: a shut gate or a disabled feature and it does not look at the
        // message, exactly as before. The general machine's is deliberately independent of it --
        // the Mythological Ritual is one source among many now, so the bus keeps working with
        // Diana out of office and with the Diana feature switched off entirely -- and it is two
        // field reads and a long compare, false off Hypixel, false in a lobby, false on an island
        // where nothing is gated open, and false for a player who has switched every source off.
        //
        // Neither of these touches the message. That matters: message.getString() flattens the
        // whole component, and this listener sees every line of chat.
        SkyPrismConfig config = config();
        boolean dianaArmed = gate.isOpen() && config.diana.enabled;
        LootMachine machine = LootMachine.get();
        boolean machineArmed = machine.armed();
        if (!dianaArmed && !machineArmed) {
            return true;
        }

        // One flatten, shared by both readers. message.getString() is what the Diana filter has
        // always been asked with, and it is also the right input for the bus's pre-filter: every
        // chat marker any detector declares is plain text with no formatting codes in it, so the
        // pre-filter gives the same answer here as it would after the legacy conversion. Asking
        // before converting is what keeps the more expensive step off the 99.9% of chat that is
        // neither a drop nor a trigger.
        String plain = message.getString();

        boolean dianaWants = dianaArmed && looksRelevant(plain);
        boolean machineWants = machineArmed && machine.wantsLine(plain);

        if (!dianaWants && !machineWants) {
            return true;
        }

        // The one genuinely allocating step, now paid at most once for both readers rather than
        // once each. Both are written against the legacy section-sign form.
        String legacy = LegacyText.toLegacy(message);
        long now = clock.millis();

        // Diana first, and with its result alone deciding the line's fate. Only Diana suppresses
        // chat lines -- that is what diana.suppressDropChatLines asks for -- and giving the
        // general machine a vote would be a new, unrequested way for the mod to eat chat.
        boolean consumedAsLoot = dianaWants && handleLine(legacy, now);

        if (machineWants) {
            machine.onChat(legacy, now);
        }
        return !consumedAsLoot;
    }

    /**
     * The pre-regex bail the performance rules demand.
     *
     * <p>Delegated rather than reimplemented. This method used to carry its own copy of the
     * marker list, and that copy was missing {@code "burrow chain"} -- so the chain-finished
     * half of {@code DianaPatterns.BURROW_DUG} ("You finished the Griffin burrow chain!"),
     * which is the one Diana line that never says "dug out", was silently dropped by this
     * listener. It was masked only because {@link com.skyprism.mc.chat.ChatRouter} has the
     * full list and feeds {@link #onChatMessage} anyway; the moment anyone retired that
     * listener in favour of this one, as ChatHooks' own advice suggests, it became a
     * user-visible outage. There is now exactly one copy of the list, in
     * {@link DianaLineFilter#MARKERS}, and it is the one both paths test against.</p>
     */
    private static boolean looksRelevant(String plain) {
        return DianaLineFilter.mightMatterToDiana(plain);
    }

    /** The community Inquisitor broadcast, used only when the player has opted out of own-burrows-only. */
    private static boolean isSharedInquisitor(String raw) {
        return DianaPatterns.INQUISITOR_SHARE.matcher(raw).matches();
    }

    /**
     * Whether this creature is one the player asked the machine to celebrate.
     *
     * <p>An empty trigger set means "none" rather than "all": it is reachable only by the player
     * clearing every box, and silently rolling for everything at that point would be the opposite of
     * what they asked for.
     */
    private static boolean isTrigger(MythologicalCreature creature, SkyPrismConfig config) {
        Set<MythologicalCreature> triggers = config.diana.triggers;
        return triggers != null && triggers.contains(creature);
    }

    /**
     * Pushes the configured island whitelist into the gate when it changes.
     *
     * <p>Compared by reference rather than by value: the config manager publishes a whole new
     * settings tree on every change and never mutates the live one in place, so an unchanged
     * configuration is one reference compare and a changed one is a single rebuild. Without this
     * the gate's area condition was documented by three classes and set by none -- the sidebar
     * poll computed the current island every two seconds and pushed it into a whitelist that was
     * permanently empty, which the gate reads as "any area".
     */
    private void syncAllowedAreas(SkyPrismConfig config) {
        Set<String> wanted = config.diana.allowedAreas;
        if (wanted == appliedAreas) {
            return;
        }
        appliedAreas = wanted;
        gate.setAllowedAreas(wanted);
    }

    /**
     * Drops everything the server told us, leaving configuration alone.
     *
     * <p>The general machine is torn down alongside Diana rather than separately, because both are
     * invalidated by exactly the same three events -- a disconnect, a world change and the gate
     * closing -- and a half-open loot window surviving a warp would credit the next island's chat
     * to the last island's chest.</p>
     */
    private void hardStop() {
        LootMachine.get().hardStop();
        tracker.clear();
        pendingCreature = null;
        rollPendingTally = false;
        lootDeadline = 0L;
        lastLine = null;
        lastLineAt = Long.MIN_VALUE;
        if (roll != null) {
            roll.reset();
        }
        if (stats != null) {
            stats.save();
        }
    }

    /**
     * Creates the roll, or rebuilds it when the timing settings changed.
     *
     * <p>A rebuild while a roll is on screen would blank it mid-spin, so a changed config waits for
     * the machine to fall idle. {@link SlotRollConfig} is a record, so the comparison is a value
     * comparison and an unchanged config costs nothing.
     *
     * <p>Because the rebuild is deferred, it can happen at a moment nothing else is watching --
     * typically inside {@link #beginRoll}, long after the settings change that asked for it. Any
     * swap therefore bumps {@link #rollEpoch()}, which is the only signal a caller caching the
     * reference can safely follow.
     */
    private void ensureRoll() {
        SlotRollConfig wanted = config().diana.toRollConfig();
        if (roll == null) {
            install(wanted);
            return;
        }
        if (!wanted.equals(rollConfig) && !roll.active()) {
            install(wanted);
        }
    }

    /** Installs a roll and announces it, so nothing can hold a reference to the retired one. */
    private void install(SlotRollConfig wanted) {
        rollConfig = wanted;
        roll = new SlotRoll(wanted, clock);
        rollEpoch++;
    }

    /** The configured loot window, already clamped by the config's own bounds. */
    private static long lootWindowMillis(SkyPrismConfig config) {
        long window = config.diana.lootWindowMillis;
        if (window < SkyPrismConfig.DianaSettings.MIN_LOOT_WINDOW_MILLIS) {
            return SkyPrismConfig.DianaSettings.MIN_LOOT_WINDOW_MILLIS;
        }
        if (window > SkyPrismConfig.DianaSettings.MAX_LOOT_WINDOW_MILLIS) {
            return SkyPrismConfig.DianaSettings.MAX_LOOT_WINDOW_MILLIS;
        }
        return window;
    }

    /** The current configuration, from the shared supplier when one has been installed. */
    private SkyPrismConfig config() {
        if (configSupplier != null) {
            SkyPrismConfig supplied = configSupplier.get();
            if (supplied != null) {
                return supplied;
            }
        }
        if (fallbackConfig == null) {
            Path file = configDir() == null ? null : configDir().resolve("skyprism.json");
            fallbackConfig = file == null
                    ? SkyPrismConfig.defaults()
                    : ConfigCodec.loadOrDefaults(file);
        }
        return fallbackConfig;
    }

    /** Where the stats file lives, or null when the loader is not available (a bare-JVM test). */
    private static Path statsPath() {
        Path dir = configDir();
        return dir == null ? null : dir.resolve(DianaStats.FILE_NAME);
    }

    /** The Fabric config directory, or null outside a running loader. */
    private static Path configDir() {
        try {
            return FabricLoader.getInstance().getConfigDir();
        } catch (RuntimeException | LinkageError notLoaded) {
            return null;
        }
    }

    /** The local player's position, or null when there is no world. */
    private static Vec3 playerPosition() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.player == null ? null : mc.player.position();
    }
}
