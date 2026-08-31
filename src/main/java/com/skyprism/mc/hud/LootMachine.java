package com.skyprism.mc.hud;

import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.LootParser;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LineOwnership;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceInfo;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.core.loot.SourceDetector;
import com.skyprism.core.loot.combat.CombatDetectors;
import com.skyprism.core.loot.containers.ContainerDetectors;
import com.skyprism.core.loot.events.EventDetectors;
import com.skyprism.core.loot.gathering.GatheringDetectors;
import com.skyprism.core.util.Clock;
import com.skyprism.core.util.SystemClock;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Turns {@link LootEvent}s from every corner of SkyBlock into spins of the one slot machine.
 *
 * <p>The widget beside this class draws a {@link SlotRoll}. Diana has always started that roll from
 * a creature dying. This class is the other way in: it owns a {@link LootEventBus}, registers the
 * detectors the player has armed, and decides -- per source, and against a floor that stops a burst
 * from thrashing the machine -- which of the events coming out of that bus are worth showing.</p>
 *
 * <h2>Diana does not come through here, and that is the point</h2>
 *
 * <p>{@code DianaController} still detects its own kills, still starts its own rolls, and still
 * feeds its own loot window, exactly as it did before this class existed. It is the one path
 * verified against the live server, and the cheapest way to guarantee it did not regress was to not
 * touch it: Diana became the first <em>implementation</em> of the general abstraction rather than
 * the first <em>caller</em> of it. Two consequences are load-bearing:</p>
 *
 * <ul>
 *   <li>{@code DianaLootSource} and {@code BurrowTreasureDetector} are deliberately <b>not</b>
 *       registered on this bus. Both would claim {@link LootSource#DIANA_MYTHOLOGICAL}, and the
 *       controller already owns it. Registering either would give one source two owners and a
 *       burrow two spins.</li>
 *   <li>A running Diana roll <b>outranks</b> anything the bus produces -- see {@link #admit}.
 *       Hypixel's universal drop banner really does fire on Diana's own treasure lines, and while
 *       the core detectors already refuse those (their guard list contains "You dug out"), a rule
 *       stated once here is what makes the guarantee hold for a detector written next year by
 *       somebody who has not read that guard.</li>
 * </ul>
 *
 * <h2>The rapid-fire policy: freshest wins, with a floor</h2>
 *
 * <p>{@link SlotRoll} already has a start-while-active rule, and it is <em>replace</em>: a start
 * over a running roll rebases the timeline, drops the previous captures and shows the new subject.
 * That was the right rule for Diana -- if two creatures die four seconds apart you want to see the
 * second one -- and it is still the right rule with sixty sources, because a queue would make the
 * machine lag reality. A player who opens six chests and then watches six spins in a row is being
 * shown history, and the widget's whole value is that it reacts to the moment they are living
 * through.</p>
 *
 * <p>So this class extends that rule rather than inventing a second one. The extension is a single
 * floor:</p>
 *
 * <blockquote><b>An event is admitted when the machine has been idle, or when at least
 * {@link #minIntervalMillis()} has passed since the last admitted roll started. An event arriving
 * inside that floor is ignored -- not queued, not merged.</b></blockquote>
 *
 * <p>Ignored rather than queued for the reason above; ignored rather than allowed through because
 * three container lines inside one tick would otherwise restart the reels three times and the
 * player would see a stutter rather than a spin. The floor is deliberately shorter than a complete
 * roll ({@value #DEFAULT_MIN_INTERVAL_MILLIS} ms against roughly four seconds of animation), so a
 * genuinely separate event a couple of seconds later still interrupts and still feels immediate;
 * what it stops is only sub-second thrash. Frequency at the scale of "every fish" is not the
 * floor's job -- that is what {@link RollPolicy} is for, and every source ships with a default
 * chosen for its own cadence.</p>
 *
 * <p>Diana is exempt, structurally rather than by special case: its rolls never pass through
 * {@link #admit}. Its cadence is bounded by the game -- a creature must spawn, be bound and die --
 * so it cannot thrash, and holding its behaviour identical was the harder requirement.</p>
 *
 * <h2>Deferred policies</h2>
 *
 * <p>{@link RollPolicy#ALWAYS} can be answered the moment the trigger line arrives.
 * {@link RollPolicy#ON_RARE_BANNER} and {@link RollPolicy#ON_JACKPOT_ITEM_ONLY} cannot: they are
 * questions about the <em>loot</em>, which arrives after the trigger and sometimes on the very same
 * line. Those two therefore <em>arm</em> a pending event and start the roll only when a drop that
 * satisfies the policy turns up inside the loot window. If none does, nothing was ever shown, which
 * is exactly what the player asked for by choosing the policy.</p>
 *
 * <h2>What this costs when nothing is happening</h2>
 *
 * <ul>
 *   <li><b>Per tick:</b> {@link #updateContext} compares three booleans and one string. A
 *       {@link GameContext} is allocated and the bus recomputed only when the player actually
 *       changes island.</li>
 *   <li><b>Per chat line:</b> {@link #wantsLine(String)} is one long compare plus, at most, the
 *       bus's own marker scan -- a handful of {@code indexOf} calls on a string the caller already
 *       had. Nothing is converted to legacy form, no {@code Pattern} is touched and nothing is
 *       allocated unless a marker actually hits.</li>
 *   <li><b>Per frame:</b> nothing. This class is not on the render path; it hands the widget the
 *       same {@link SlotRoll} the widget already caches.</li>
 * </ul>
 *
 * <p><b>Threading:</b> client thread only, like everything it talks to.</p>
 */
public final class LootMachine {

    /**
     * The shipped floor between two admitted rolls.
     *
     * <p>Shorter than one complete roll on the default timings (1200 ms spin + 500 ms stagger +
     * 2500 ms settle), so a second event a couple of seconds later still takes the machine over and
     * still reads as a reaction. Long enough that a container reward block, which Hypixel prints as
     * a dozen lines across two or three ticks, produces one spin.</p>
     */
    public static final long DEFAULT_MIN_INTERVAL_MILLIS = 1_500L;

    /** Nothing below this is a floor at all; a caller asking for less is asking for it off. */
    public static final long MIN_INTERVAL_FLOOR_MILLIS = 0L;

    /** Half a minute between spins is already eccentric; past it the feature is simply off. */
    public static final long MAX_INTERVAL_MILLIS = 30_000L;

    /** How long a deferred event stays armed when no loot-window length is available. */
    private static final long FALLBACK_LOOT_WINDOW_MILLIS = 3_000L;

    private static final LootMachine INSTANCE = new LootMachine(SystemClock.INSTANCE);

    private final Clock clock;
    private final LootEventBus bus = new LootEventBus();
    private final LootParser parser = new LootParser();

    /** Session overrides; a source absent from this map runs on its registry default. */
    private final Map<LootSource, RollPolicy> policies = new EnumMap<>(LootSource.class);

    /** Where the machine to spin comes from; null until the mod wires it up. */
    private Supplier<SlotRoll> rollSupplier;

    /** The configured loot window, shared with Diana so both halves agree on how long loot lands. */
    private LongSupplier lootWindowSupplier;

    /** An installed source for the floor, so a future config section can own it without a rewrite. */
    private LongSupplier minIntervalSupplier;

    /** The username supplier the detector set was built with, kept so a rebuild can reuse it. */
    private Supplier<String> localPlayerName = () -> null;

    private long minIntervalMillis = DEFAULT_MIN_INTERVAL_MILLIS;

    private boolean detectorsRegistered;

    // --- the cheap per-tick context comparison -------------------------------------------

    private boolean lastOnHypixel;
    private boolean lastInSkyBlock;
    private boolean lastMayorDiana;
    private String lastIsland = "";
    private boolean contextEverPushed;

    // --- admission and window state ------------------------------------------------------

    private long lastAdmittedAt = Long.MIN_VALUE;
    private LootEvent pending;
    private long pendingUntil;

    /**
     * When the running roll stops accepting drops.
     *
     * <p>{@link Long#MIN_VALUE} rather than zero for "never opened". Zero is a real instant on an
     * injected clock -- a {@code FixedClock} starts there -- so a zero deadline read as an open
     * window on the first tick of a harness and made the machine ask for every chat line before
     * anything had happened. Wall-clock millis would have hidden it forever, which is exactly the
     * kind of bug that survives to production.</p>
     */
    private long lootDeadline = Long.MIN_VALUE;

    /** Counters, for {@code /skyprism sources} to answer "why have I never seen this fire". */
    private int admittedCount;
    private int suppressedCount;
    private int deferredCount;
    private LootEvent lastAdmitted;
    private LootEvent lastSuppressed;

    /**
     * Builds a machine on an injected clock.
     *
     * <p>Public so a harness can drive the whole path from a {@code FixedClock}; the mod uses
     * {@link #get()}.
     *
     * @param clock the time source, never null
     */
    public LootMachine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The machine the mod actually runs on. */
    public static LootMachine get() {
        return INSTANCE;
    }

    /**
     * This machine's event bus.
     *
     * <p>Exposed for one reason: {@code ScreenTitleFeed} needs the same bus the chat path uses, and
     * without an accessor it read this field reflectively. Reflection worked and was pinned by a
     * test, but it made a private field part of the contract in everything but name — a rename would
     * have compiled cleanly and failed at runtime, in a packet handler, where a throw costs the
     * player the ability to open containers. A method the compiler checks is strictly better.
     *
     * <p>A screen opening cannot go through {@link #onChat} because it is not chat, and it cannot use
     * a detector set of its own because some sources arm from a title and then claim from chat — the
     * two halves must see one bus or the arming is lost.
     *
     * @return the bus this machine dispatches through; never null
     */
    public LootEventBus bus() {
        return bus;
    }

    // ======================================================================
    //  Wiring
    // ======================================================================

    /**
     * Points the machine at the {@link SlotRoll} the HUD draws, and at the configured loot window.
     *
     * <p>Both are suppliers rather than values because the controller rebuilds its roll whenever
     * the timing settings change, and a cached reference would go stale the first time the player
     * touched the settings screen.</p>
     *
     * @param rolls        supplies the live roll; null leaves the machine inert
     * @param lootWindowMs supplies the loot window in milliseconds; null falls back to three
     *                     seconds, which is the shipped Diana default
     */
    public void wire(Supplier<SlotRoll> rolls, LongSupplier lootWindowMs) {
        this.rollSupplier = rolls;
        this.lootWindowSupplier = lootWindowMs;
    }

    /**
     * When two packages claim the same source, the class that wins.
     *
     * <p>The detector packages were written in parallel against one shared enum, and two constants
     * ended up with two implementations each. {@link LootEventBus#register} rejects the second,
     * loudly and correctly -- one source with two owners means one of them is unreachable and
     * nobody would know which. But throwing at startup would take the entire bus down over a
     * disagreement about two detectors, so the collision is resolved here instead, deliberately and
     * on the record:</p>
     *
     * <ul>
     *   <li><b>{@code MOB_RARE_DROP}</b> goes to the events package's {@code
     *       GenericRareDropDetector}. Its {@code RareDropBanner} decomposes <em>both</em> shapes
     *       Hypixel uses -- the plain banner and the bracketed "went to sacks" form that slayers and
     *       several dungeon drops use -- and handles the two spaces that follow {@code VERY RARE
     *       DROP!} and {@code CRAZY RARE DROP!}. Those are exactly the two tiers a slot machine
     *       exists to celebrate. The combat package's version matches the family with a looser
     *       pattern and does not take the line apart.</li>
     *   <li><b>{@code PET_DROP}</b> goes the other way, to the combat package's
     *       {@code PetDropDetector}, and for one reason: it captures the pet's name and puts it in
     *       the event's subject, so the caption strip reads "Baby Yeti". The events version is a
     *       better parser but captions every pet "Pet Drop", and naming what you got is the entire
     *       job of that strip.</li>
     * </ul>
     *
     * <p>Held as class names rather than {@code Class} objects so this list can name a class in a
     * package that has not been loaded, and so reading it does not load one.</p>
     */
    private static final List<String> PREFERRED_ON_COLLISION = List.of(
            "com.skyprism.core.loot.events.GenericRareDropDetector",
            "com.skyprism.core.loot.combat.PetDropDetector");

    /** Sources two packages both claimed, for {@code /skyprism sources} to admit to. */
    private final List<LootSource> contested = new ArrayList<>(2);

    /**
     * Registers every detector whose source is armed.
     *
     * <p>Idempotent. A source whose policy is {@link RollPolicy#NEVER} is not registered at all,
     * which is the strongest form of the performance rule the mod is built on: a shut source is not
     * a branch that returns early, it is an object that does not exist, contributes no chat marker
     * to the bus's pre-filter and cannot be reached from any code path.</p>
     *
     * <p>Deliberately absent, and neither is an oversight: {@code DianaLootSource} and
     * {@code BurrowTreasureDetector}, both of which claim {@link LootSource#DIANA_MYTHOLOGICAL},
     * which {@code DianaController} already owns end to end.</p>
     *
     * <p>Where two packages claim one source, {@link #PREFERRED_ON_COLLISION} decides and the
     * loser is dropped rather than allowed to throw. Registration order is otherwise preserved
     * exactly as each package declared it, because several of those orders are load-bearing --
     * Croesus before the Catacombs chest, the VERY tier before the plain one, the catch-all last.
     *
     * @param names supplies the client's own username for the chest broadcasts, which Hypixel sends
     *              to the whole party; may return null while connecting, which shuts the ownership
     *              check rather than opening it
     */
    public void registerDetectors(Supplier<String> names) {
        if (names != null) {
            this.localPlayerName = names;
            // The chest detectors take the supplier by constructor argument; the banner parsers
            // cannot, because their ownership check is static and is reached from a dozen
            // detectors that hold no reference to anything. So the same supplier is installed
            // once, here, on the one class that answers "whose drop is this" for all of them.
            //
            // Installed on registration rather than on world join deliberately: the supplier is
            // lazy, so it is safe to hand over before there is a session, and it must be in place
            // before the first chat line rather than after the first join. LineOwnership reads
            // null as "unknown" and refuses the third-person shapes until the name arrives.
            LineOwnership.useLocalPlayerName(names);
        }
        if (detectorsRegistered) {
            return;
        }
        detectorsRegistered = true;

        List<SourceDetector> candidates = new ArrayList<>(64);
        candidates.addAll(CombatDetectors.create().detectors());
        candidates.addAll(ContainerDetectors.all(localPlayerName));
        candidates.addAll(GatheringDetectors.all());
        candidates.addAll(EventDetectors.inOrder());

        // Pass one: pick exactly one detector per armed source.
        Map<LootSource, SourceDetector> chosen = new EnumMap<>(LootSource.class);
        contested.clear();
        for (SourceDetector detector : candidates) {
            LootSource source = detector.source();
            if (source == LootSource.DIANA_MYTHOLOGICAL || !policyFor(source).armed()) {
                continue;
            }
            SourceDetector incumbent = chosen.get(source);
            if (incumbent == null) {
                chosen.put(source, detector);
                continue;
            }
            if (!contested.contains(source)) {
                contested.add(source);
            }
            if (PREFERRED_ON_COLLISION.contains(detector.getClass().getName())) {
                chosen.put(source, detector);
            }
        }

        // Pass two: register in the candidate order, so each package's ordering survives.
        for (SourceDetector detector : candidates) {
            if (chosen.get(detector.source()) == detector) {
                bus.register(detector);
            }
        }
    }

    /**
     * Sources that more than one detector package claimed.
     *
     * <p>Empty is the healthy answer. A non-empty one is not a failure -- one detector was chosen
     * and the feature works -- but it is worth surfacing, because the implementation that lost is
     * dead code that will keep being maintained by whoever wrote it unless somebody notices.</p>
     */
    public List<LootSource> contestedSources() {
        return List.copyOf(contested);
    }

    /**
     * Rebuilds the registered set after a policy change.
     *
     * <p>Cheap enough to be unconditional -- it happens when a player types a command, never on a
     * tick -- and it is the only way to honour "a shut source costs nothing": flipping a policy to
     * {@link RollPolicy#NEVER} has to take the detector <em>out</em>, not merely make it decline.
     */
    private void rebuildDetectors() {
        bus.clear();
        detectorsRegistered = false;
        contextEverPushed = false;
        registerDetectors(localPlayerName);
        // clear() reset the bus to an unknown context, so push the one we already know back in.
        bus.updateContext(buildContext(lastOnHypixel, lastInSkyBlock, lastIsland, lastMayorDiana));
        contextEverPushed = true;
    }

    // ======================================================================
    //  Context
    // ======================================================================

    /**
     * Offers the four world facts the mod already reads, rebuilding the bus's gates only on change.
     *
     * <p>Primitives rather than a {@link GameContext} on purpose: this is called from the client
     * tick, and building a record with three normalised strings twenty times a second to discover
     * that nothing moved is exactly the kind of cost the mod's performance rules forbid. Three
     * boolean compares and one string compare answer it instead, and the string compare is a
     * reference hit in the common case because the gate stores a new island name only when the
     * island actually changes.</p>
     *
     * @param onHypixel  whether the client is on Hypixel
     * @param inSkyBlock whether the SkyBlock sidebar is up
     * @param island     the sidebar's island name; null is read as unknown
     * @param mayorDiana whether Diana currently holds office
     */
    public void updateContext(boolean onHypixel, boolean inSkyBlock, String island,
                              boolean mayorDiana) {
        String name = island == null ? "" : island;
        if (contextEverPushed
                && onHypixel == lastOnHypixel
                && inSkyBlock == lastInSkyBlock
                && mayorDiana == lastMayorDiana
                && name.equals(lastIsland)) {
            return;
        }
        lastOnHypixel = onHypixel;
        lastInSkyBlock = inSkyBlock;
        lastMayorDiana = mayorDiana;
        lastIsland = name;
        contextEverPushed = true;
        bus.updateContext(buildContext(onHypixel, inSkyBlock, name, mayorDiana));
    }

    /**
     * Folds the four facts into the record the core gates read.
     *
     * <p>Two of the seven fields are derived rather than read, because SkyPrism has no separate
     * source for them and the island name settles both: Hypixel's sidebar says
     * {@code The Catacombs (F7)} inside a dungeon and {@code The Rift} inside the Rift.</p>
     *
     * <p>The {@code area} field is left empty, and that is a known and deliberate gap rather than an
     * oversight. Hypixel publishes the finer graph area -- "The Mist", "Dragon's Nest", "Crystal
     * Nucleus" -- in the TAB list, which SkyPrism does not read today. The sources gated on one stay
     * shut, {@code /skyprism sources} prints the gate that is holding them shut verbatim so the
     * player can see exactly why, and nothing pretends otherwise. Filling this in is one argument
     * here once a TAB area reader exists.</p>
     */
    private static GameContext buildContext(boolean onHypixel, boolean inSkyBlock, String island,
                                            boolean mayorDiana) {
        String lower = island.toLowerCase(Locale.ROOT);
        boolean dungeon = lower.startsWith("the catacombs") || lower.startsWith("catacombs");
        boolean rift = lower.equals("the rift") || lower.equals("rift");
        return new GameContext(onHypixel, inSkyBlock, island, "",
                mayorDiana ? "Diana" : "", dungeon, rift);
    }

    /** The context the bus is currently gating against. */
    public GameContext context() {
        return bus.context();
    }

    // ======================================================================
    //  Chat
    // ======================================================================

    /**
     * Whether this line is worth reconstructing into its legacy, section-sign form.
     *
     * <p>The caller has a {@code Component} and the plain string it flattens to; turning that back
     * into the legacy form the core's patterns are written against is the one genuinely allocating
     * step in the chat path, so it is worth a question first. Every chat marker any detector
     * declares is plain text with no formatting codes in it, so the bus's own pre-filter gives the
     * same answer against the plain string as it would against the legacy one -- which is what lets
     * this run before the conversion rather than after it.</p>
     *
     * <p>The window test comes first and is unconditional: a drop line inside an open loot window
     * carries no trigger marker at all -- the item rows inside a corpse-loot block are the clearest
     * case -- so while a roll is being fed, every line has to be looked at. That window is a few
     * seconds long and only ever opens because something already fired.</p>
     *
     * @param plainText the line's flattened text; may be null
     * @return whether {@link #onChat} should be called with the legacy form of this line
     */
    public boolean wantsLine(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return false;
        }
        if (windowOpen(clock.millis())) {
            return true;
        }
        return bus.openDetectorCount() > 0 && bus.passesPreFilter(plainText);
    }

    /**
     * Whether anything here could want <em>any</em> line right now.
     *
     * <p>Asked before the caller flattens the chat component, because flattening is itself an
     * allocation and the whole point of the chat path's design is that a player standing in a hub
     * lobby with every gate shut pays nothing at all for this feature. Two field reads and a long
     * compare, and false is the answer off Hypixel, outside SkyBlock, on an island where no
     * source's gate is open, and for a player who has switched every source off.</p>
     *
     * <p>{@link #wantsLine(String)} is the finer question and needs the flattened text; this is the
     * one that decides whether that text is worth producing.</p>
     */
    public boolean armed() {
        return bus.openDetectorCount() > 0 || windowOpen(clock.millis());
    }

    /**
     * Feeds one chat line through the bus and, if a roll is live, through the loot parser.
     *
     * <p>Order is load-bearing. The bus is offered the line <em>first</em>, because for most of
     * SkyBlock the trigger line and the drop line are the same line: Hypixel's rare-drop banner both
     * announces that something happened and names what it was. Starting the roll before parsing the
     * drops means that single line lands on the reels of the roll it just started, rather than being
     * parsed against a machine that is still idle and thrown away.</p>
     *
     * @param rawWithCodes the line in legacy section-sign form; may be null
     * @param nowMillis    the current time
     */
    public void onChat(String rawWithCodes, long nowMillis) {
        if (rawWithCodes == null || rawWithCodes.isEmpty()) {
            return;
        }
        sweep(nowMillis);

        if (bus.openDetectorCount() > 0) {
            Optional<LootEvent> event = bus.onChat(rawWithCodes, nowMillis);
            if (event.isPresent()) {
                admit(event.get(), nowMillis);
            }
        }

        if (windowOpen(nowMillis)) {
            feed(rawWithCodes, nowMillis);
        }
    }

    /**
     * Decides what an event does to the machine.
     *
     * @param event the event a detector produced
     * @param now   the current time
     * @return the state the event ended in, which is what the command layer reports back
     */
    public Admission admit(LootEvent event, long now) {
        Objects.requireNonNull(event, "event");
        SlotRoll roll = roll();
        if (roll == null) {
            return Admission.NO_MACHINE;
        }

        // Diana outranks the bus. See the class javadoc: the controller owns that source end to
        // end, and a bus event must never be able to clobber a roll it started.
        if (roll.activeAt(now) && roll.sourceAt(now) == LootSource.DIANA_MYTHOLOGICAL) {
            suppressedCount++;
            lastSuppressed = event;
            return Admission.OUTRANKED;
        }

        RollPolicy policy = policyFor(event.source());
        if (!policy.armed()) {
            return Admission.OFF;
        }
        if (policy == RollPolicy.ALWAYS) {
            return start(event, now) ? Admission.ROLLED : Admission.TOO_SOON;
        }

        // ON_RARE_BANNER and ON_JACKPOT_ITEM_ONLY are questions about loot that has not arrived
        // yet. Arm, and let feed() answer them. Arming is free, so it is not subject to the floor;
        // only the roll it may eventually start is.
        pending = event;
        pendingUntil = now + lootWindowMillis();
        deferredCount++;
        return Admission.DEFERRED;
    }

    /**
     * Starts a roll, unless the floor says the machine is still busy with the last one.
     *
     * @return whether the roll was started
     */
    private boolean start(LootEvent event, long now) {
        long floor = minIntervalMillis();
        if (lastAdmittedAt != Long.MIN_VALUE && floor > 0L && now - lastAdmittedAt < floor) {
            suppressedCount++;
            lastSuppressed = event;
            return false;
        }
        SlotRoll roll = roll();
        if (roll == null) {
            return false;
        }
        roll.startEvent(event);
        lastAdmittedAt = now;
        lastAdmitted = event;
        admittedCount++;
        lootDeadline = now + lootWindowMillis();
        pending = null;
        pendingUntil = 0L;
        return true;
    }

    /**
     * Offers a line's drops to the live roll, and lets them settle a deferred policy.
     *
     * <p>Deliberately silent while a Diana roll is on screen: the controller feeds that one itself,
     * and offering the same drop twice would put the same symbol on two reels.</p>
     */
    private void feed(String rawWithCodes, long now) {
        SlotRoll roll = roll();
        if (roll == null) {
            return;
        }

        List<LootDrop> drops = parser.parse(rawWithCodes);
        if (drops.isEmpty()) {
            return;
        }

        LootEvent armed = pending;
        if (armed != null && now <= pendingUntil) {
            RollPolicy policy = policyFor(armed.source());
            boolean rare = false;
            boolean jackpot = false;
            for (int i = 0; i < drops.size(); i++) {
                LootDrop drop = drops.get(i);
                rare |= drop.rare();
                jackpot |= isJackpotItem(armed.source(), drop);
            }
            if (!policy.permits(rare, jackpot)) {
                return;
            }
            if (!start(armed, now)) {
                return;
            }
        }

        if (!roll.activeAt(now) || now > lootDeadline) {
            return;
        }
        LootSource running = roll.sourceAt(now);
        if (running == null || running == LootSource.DIANA_MYTHOLOGICAL) {
            return;
        }
        for (int i = 0; i < drops.size(); i++) {
            roll.offerDrop(promote(drops.get(i), running));
        }
    }

    /**
     * Marks a drop rare when the source's own jackpot list says it deserves the celebration.
     *
     * <p>{@link SlotRoll} earns its three-of-a-kind act from {@link LootDrop#rare()}, and the
     * registry already carries the per-source jackpot list that
     * {@link RollPolicy#ON_JACKPOT_ITEM_ONLY} is decided from. Those two answers should agree: a
     * source whose policy would have rolled for an item ought to celebrate that item when it lands.
     * This is what makes them agree.</p>
     *
     * <p><b>How much work it does today, stated honestly.</b> The only drop parser wired in here is
     * {@link LootParser}, and it marks every banner drop rare and coins not-rare -- so right now
     * this method changes nothing, because no jackpot item ever arrives already un-flagged. It
     * earns its place the moment a bannerless line reaches the feed: a Glacial Talisman out of a
     * Frozen Treasure and a Pickonimbus out of a powder chest are announced on lines Hypixel never
     * flags at all, and those are exactly the drops a slot machine exists for. Writing the rule now
     * means the day that parser lands, the celebration is already correct rather than a bug filed
     * against a feature that half works.</p>
     *
     * <p>Never reached for Diana, whose drops are fed by its own controller.</p>
     */
    private static LootDrop promote(LootDrop drop, LootSource source) {
        if (drop.rare() || !isJackpotItem(source, drop)) {
            return drop;
        }
        // asRare(), not a hand-rebuilt record: rebuilding by hand drops any component the record
        // gains later, and it had already dropped one. Magic Find is carried on LootDrop now, and
        // the drops this method promotes are exactly the ones whose figure the jackpot reveal
        // exists to show, so rebuilding four of the five fields would have blanked the stat on
        // precisely the screen that reports it.
        return drop.asRare();
    }

    private static boolean isJackpotItem(LootSource source, LootDrop drop) {
        if (drop == null) {
            return false;
        }
        String name = drop.itemName();
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (String candidate : LootSourceRegistry.info(source).jackpotItems()) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void sweep(long now) {
        if (pending != null && now > pendingUntil) {
            pending = null;
            pendingUntil = 0L;
        }
    }

    private boolean windowOpen(long now) {
        return now <= lootDeadline || (pending != null && now <= pendingUntil);
    }

    // ======================================================================
    //  Simulation
    // ======================================================================

    /**
     * Runs a roll by hand for {@code /skyprism simulate}, ignoring gates, policies and the floor.
     *
     * <p>Every one of those is bypassed on purpose. The command exists so a player can see a source
     * they would otherwise have to run a dungeon to see, and a demonstration that silently declined
     * because the player is not on Crimson Isle, or because they spun something else two seconds
     * ago, would be indistinguishable from the feature being broken.</p>
     *
     * @param event the event to show; its subject becomes the caption
     * @param drops the drops to lock the reels onto; null and null elements are ignored, and an
     *              empty list produces the "No Drop" result a barren event shows
     * @return whether a machine was available to spin
     */
    public boolean simulate(LootEvent event, List<LootDrop> drops) {
        Objects.requireNonNull(event, "event");
        SlotRoll roll = roll();
        if (roll == null) {
            return false;
        }
        roll.startEvent(event);
        if (drops != null) {
            for (LootDrop drop : drops) {
                if (drop != null) {
                    roll.offerDrop(promote(drop, event.source()));
                }
            }
        }
        long now = clock.millis();
        lastAdmittedAt = now;
        lastAdmitted = event;
        lootDeadline = now + lootWindowMillis();
        pending = null;
        pendingUntil = 0L;
        return true;
    }

    // ======================================================================
    //  Policy and settings
    // ======================================================================

    /** The policy in force for a source: the session override if one is set, else the default. */
    public RollPolicy policyFor(LootSource source) {
        RollPolicy override = policies.get(source);
        return override != null ? override : LootSourceRegistry.defaultPolicy(source);
    }

    /** Whether a source is running on something other than its shipped default. */
    public boolean overridden(LootSource source) {
        return policies.containsKey(source);
    }

    /**
     * Sets a source's policy for the rest of the session and re-registers the detector set.
     *
     * @param source the source to change
     * @param policy the new policy; null restores the registry default
     * @return the policy in force afterwards
     */
    public RollPolicy setPolicy(LootSource source, RollPolicy policy) {
        Objects.requireNonNull(source, "source");
        if (source == LootSource.DIANA_MYTHOLOGICAL) {
            // Diana's arming lives in its own settings and its own controller; accepting it here
            // would give the player a switch that visibly does nothing.
            return LootSourceRegistry.defaultPolicy(source);
        }
        RollPolicy before = policyFor(source);
        if (policy == null) {
            policies.remove(source);
        } else {
            policies.put(source, policy);
        }
        RollPolicy after = policyFor(source);
        if (detectorsRegistered && before != after) {
            rebuildDetectors();
        }
        return after;
    }

    /**
     * The floor between two admitted rolls, in milliseconds.
     *
     * <p>Read from the installed supplier when there is one, so a configuration section that owns
     * this setting can take it over without anything here changing; otherwise from the value set by
     * {@link #setMinIntervalMillis}. Clamped either way, because a negative floor is meaningless and
     * a five-minute one is a feature switched off by accident.</p>
     */
    public long minIntervalMillis() {
        LongSupplier supplier = minIntervalSupplier;
        long raw = supplier == null ? minIntervalMillis : supplier.getAsLong();
        if (raw < MIN_INTERVAL_FLOOR_MILLIS) {
            return MIN_INTERVAL_FLOOR_MILLIS;
        }
        return Math.min(raw, MAX_INTERVAL_MILLIS);
    }

    /**
     * Sets the floor for this session.
     *
     * @param millis the new floor; clamped to the supported range
     * @return the value actually in force afterwards
     */
    public long setMinIntervalMillis(long millis) {
        minIntervalMillis = Math.max(MIN_INTERVAL_FLOOR_MILLIS,
                Math.min(millis, MAX_INTERVAL_MILLIS));
        return minIntervalMillis();
    }

    /** Whether the floor is coming from an installed supplier rather than this session's setting. */
    public boolean intervalSupplied() {
        return minIntervalSupplier != null;
    }

    /**
     * Installs a persistent source for the floor.
     *
     * <p>The hook a configuration section should use rather than this class growing a file of its
     * own. Until one exists the floor is a session setting, which is honest: {@code /skyprism loot
     * interval} says so.
     *
     * @param supplier supplies the floor in milliseconds; null hands control back to
     *                 {@link #setMinIntervalMillis}
     */
    public void setMinIntervalSupplier(LongSupplier supplier) {
        this.minIntervalSupplier = supplier;
    }

    private long lootWindowMillis() {
        LongSupplier supplier = lootWindowSupplier;
        if (supplier == null) {
            return FALLBACK_LOOT_WINDOW_MILLIS;
        }
        long value = supplier.getAsLong();
        return value > 0L ? value : FALLBACK_LOOT_WINDOW_MILLIS;
    }

    private SlotRoll roll() {
        Supplier<SlotRoll> supplier = rollSupplier;
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError broken) {
            return null;
        }
    }

    // ======================================================================
    //  Diagnostics, for /skyprism status and /skyprism sources
    // ======================================================================

    /** Whether the mod has pointed this machine at a roll yet. */
    public boolean wired() {
        return rollSupplier != null;
    }

    /**
     * Whether anything registered here still needs the island the player is standing on.
     *
     * <p>Read once per client tick by the poll that refreshes those facts, so it is an
     * {@code ArrayList} size compare and nothing more. It answers false when every source has been
     * switched off, which is what lets the sidebar read stop entirely for a player who wants none
     * of this -- the same bargain {@code HypixelContext.poll} already offers Diana and the level
     * feature.</p>
     */
    public boolean wantsWorldFacts() {
        return bus.registeredCount() > 0;
    }

    /** How many sources are armed, whatever their gate currently says. */
    public int armedSourceCount() {
        int armed = 0;
        for (LootSourceInfo info : LootSourceRegistry.all()) {
            if (info.source() != LootSource.DIANA_MYTHOLOGICAL && policyFor(info.source()).armed()) {
                armed++;
            }
        }
        return armed;
    }

    /** How many armed chat detectors the current context lets through. */
    public int openGateCount() {
        return bus.openDetectorCount();
    }

    /** How many chat detectors are registered at all. */
    public int registeredCount() {
        return bus.registeredCount();
    }

    /** Whether the bus is currently paying a full look at every chat line. */
    public boolean unfiltered() {
        return bus.unfiltered();
    }

    /**
     * How many open detectors declared no chat markers and so are offered every line.
     *
     * <p>The per-line cost a player can actually act on. Each one is a detector whose full cost
     * is paid on every line of chat in the game, so a non-zero answer is worth colouring in
     * {@code /skyprism sources} even though it is not an error.
     */
    public int unmarkedDetectorCount() {
        return bus.unmarkedDetectorCount();
    }

    /** The literals the bus is scanning for, or empty when it is unfiltered. */
    public List<String> activeMarkers() {
        return bus.activeMarkers();
    }

    /** Whether this source's gate is open right now. */
    public boolean gateOpen(LootSource source) {
        return LootSourceRegistry.gateOpen(source, bus.context());
    }

    /** Whether a detector for this source is registered on the bus at all. */
    public boolean registered(LootSource source) {
        for (SourceDetector detector : bus.openDetectors()) {
            if (detector.source() == source) {
                return true;
            }
        }
        return false;
    }

    /** The sources whose gates are open, in registration order. */
    public List<LootSource> openSources() {
        List<LootSource> out = new ArrayList<>(8);
        for (SourceDetector detector : bus.openDetectors()) {
            out.add(detector.source());
        }
        return List.copyOf(out);
    }

    /** Rolls started from the bus this session. */
    public int admittedCount() {
        return admittedCount;
    }

    /** Events the floor or a Diana roll turned away this session. */
    public int suppressedCount() {
        return suppressedCount;
    }

    /** Events armed by a deferred policy this session, whether or not their loot ever qualified. */
    public int deferredCount() {
        return deferredCount;
    }

    /** The last event that spun the machine, or null. */
    public LootEvent lastAdmitted() {
        return lastAdmitted;
    }

    /** The last event that was turned away, or null. */
    public LootEvent lastSuppressed() {
        return lastSuppressed;
    }

    /** The event currently armed and waiting on qualifying loot, or null. */
    public LootEvent pending() {
        return pending != null && clock.millis() <= pendingUntil ? pending : null;
    }

    /** Drops everything session-scoped; called on disconnect alongside the Diana teardown. */
    public void hardStop() {
        pending = null;
        pendingUntil = 0L;
        lootDeadline = Long.MIN_VALUE;
        lastAdmittedAt = Long.MIN_VALUE;
    }

    /** What {@link #admit} decided; the command layer turns these into one sentence. */
    public enum Admission {
        /** The roll started. */
        ROLLED,
        /** Armed, waiting on loot that satisfies a deferred policy. */
        DEFERRED,
        /** Inside the floor since the last roll, so ignored. */
        TOO_SOON,
        /** A Diana roll was on screen and owns the machine. */
        OUTRANKED,
        /** The source's policy is NEVER. */
        OFF,
        /** No machine is wired up. */
        NO_MACHINE
    }
}
