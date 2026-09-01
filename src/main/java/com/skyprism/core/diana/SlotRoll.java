package com.skyprism.core.diana;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.util.Clock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The slot machine's brain: it turns "a Mythological creature died, and then these drop lines
 * appeared" into a frame-by-frame animation state, with no rendering and no wall clock anywhere.
 *
 * <h2>Why it is shaped like this</h2>
 * <p>Everything observable is derived, on demand, from {@code clock.millis()} and a handful of
 * recorded timestamps. Nothing is advanced by a tick method and nothing is decided at the moment a
 * caller happens to look. That is deliberate: the HUD polls at the frame rate, chat callbacks poke
 * it at arbitrary moments, and a test pokes it twice a second. All three must see the same roll.
 * Concretely the guarantee is <b>poll independence</b>: a reel's locked symbol depends only on which
 * drops had arrived by that reel's lock instant, never on whether anyone called {@link #reels()} in
 * between.
 *
 * <h2>Timeline: two acts, and one continuous spin</h2>
 * <p><b>An ordinary roll always plays the same way.</b> Reel {@code i} locks at
 * {@code start + spinMillis + i * lockStaggerMillis} on the drops the kill actually produced. Once
 * the last reel locks the result holds for {@code settleMillis} and then fades. Nothing about
 * ordinary loot changes those instants.
 *
 * <p><b>A roll that captured a jackpot never comes to a stop before its celebration.</b> Act two
 * begins at {@link #jackpotActStartAt(long)}, which is the first reel's ordinary lock instant --
 * so the reels reach the moment they would have stopped and keep going instead. From there
 * {@link RollState#JACKPOT_INTRO} washes gold in ({@link #jackpotIntroProgress()} running 0 to 1)
 * over columns that have never stopped turning; the wash completes and they keep turning
 * ({@link RollState#JACKPOT_SPIN}); then they land one at a time, left to right, every one of them
 * on {@link #jackpotSymbol()} ({@link RollState#JACKPOT_LOCK}); then the three of a kind holds
 * ({@link RollState#JACKPOT_HOLD}) before the fade. The whole roll is therefore one movement --
 * spin, gold, spin, land -- with exactly one stop in it, at the end, where a slot machine's stop
 * belongs.
 *
 * <p><b>Why act two used to start later, and why that was the bug.</b> It began at the end of the
 * settle, which meant a lucky roll locked its three reels, held them dead still for the whole
 * settle, and only then broke them loose again: a stop and a restart, plainly visible, in the
 * middle of the one animation that is supposed to build. The reading act one bought -- the real
 * loot on the reels before the flourish converged them -- was not worth a three-second stall, so
 * it is gone: on a celebrating roll the reels no longer land in act one at all, and the real loot
 * stays available through {@link #capturedDrops()} for the caption.
 *
 * <p><b>A banner that arrives late still cuts the stall short rather than waiting it out.</b>
 * Hypixel prints the rare-drop line a beat after the ordinary ones, and if it lands after some
 * columns have already locked, act two opens on the spot -- at the banner rather than at the end
 * of the settle. Those columns unlock and rejoin the spin, which is the shortest stop the timing
 * allows. With no jackpot the roll steps from {@link RollState#SETTLED} straight to
 * {@link RollState#FADING} as it always did.
 *
 * <h2>Symbol policy (act one)</h2>
 * <p>Captured drops are ranked <i>most interesting first</i>: jackpot drops before ordinary ones,
 * then larger stacks, then arrival order as a stable tie-break. When a reel locks it takes the
 * highest-ranked drop that no already-locked reel is showing.
 * <ul>
 *   <li><b>More drops than reels</b> -- the tail of the ranking never reaches a reel. The rarest
 *       loot always wins a column; {@link #capturedDrops()} still lists everything so the HUD can
 *       caption the overflow.</li>
 *   <li><b>Fewer drops than reels</b> -- once every distinct drop is on a reel, the remaining reels
 *       cycle back through the ranking ({@code ranked[i % ranked.size()]}). A single drop therefore
 *       fills all three columns, which reads as the three-of-a-kind a slot machine is supposed to
 *       produce, and it is fully deterministic.</li>
 *   <li><b>No drops at all</b> when a reel locks -- it lands on {@link #NO_DROP}. A creature can
 *       genuinely die without dropping anything, and the machine must never sit in
 *       {@link RollState#LOCKING} waiting for loot that is not coming.</li>
 * </ul>
 *
 * <h2>Jackpot policy (act two)</h2>
 * <p>A drop counts as a jackpot when {@link LootDrop#rare()} is set, i.e. the server printed a
 * rare-drop banner for it. Deciding that is the parser's job, so this class needs no rarity table of
 * its own. Any captured jackpot latches {@link #jackpot()} true for the rest of the roll. Whether it
 * also earns the celebration is a separate question with one rule:
 * <ul>
 *   <li><b>Captured strictly before the settle ends -- the sequence fires.</b> That deliberately
 *       includes a banner arriving after every reel has locked, while the result is being held:
 *       {@code lootWindowMillis} is allowed to outlast the locks precisely because Hypixel prints
 *       the banner a beat late. Those columns then unlock and rejoin the spin, which is a stop of
 *       whatever length the banner was late by rather than the full settle.</li>
 *   <li><b>Captured once the fade has begun -- it does not.</b> Act two would have to start from
 *       {@link RollState#FADING}, stepping the roll back to {@link RollState#JACKPOT_INTRO}: on
 *       screen, a half-faded panel snapping back to full opacity and re-spinning. The drop is still
 *       reported through {@link #jackpot()} and {@link #capturedDrops()}; it simply arrived after
 *       the machine had committed to ending.</li>
 * </ul>
 *
 * <p><b>Two jackpots in one kill</b> converge the reels on the better one, not the first one:
 * {@link #jackpotSymbol()} is the highest-ranked rare capture under the same ordering act one uses,
 * so a stack of two beats a stack of one and arrival order breaks the remaining tie. The first rare
 * decides <em>whether</em> there is a celebration, because that is a timing question and timing must
 * latch early; the best rare decides <em>what</em> it celebrates, because a player who got a Chimera
 * second does not want three Crowns of Greed on the screen. The symbol is frozen at the instant act
 * two begins -- a rare arriving mid-celebration, which a long enough {@code lootWindowMillis} allows,
 * cannot rewrite reels the player is already watching land.
 *
 * <h2>Restart policy</h2>
 * <p>{@link #start(MythologicalCreature)} while a roll is running <b>restarts</b> from scratch: new
 * creature, cleared drops, cleared jackpot, clock rebased. That holds in the middle of a jackpot
 * celebration too. Queueing would show the second Inquisitor's loot seconds after the player looted
 * it, and ignoring would silently discard the rarer of two kills; a celebration for loot already in
 * the inventory is worth less than the reels for loot just dropped. The freshest kill is the one
 * worth celebrating, so it wins, and act two is abandoned wherever it had got to.
 *
 * <p>That restart is invisible to anyone watching {@link #active()}, which is true on both sides of
 * it, so {@link #rollId()} marks the edge. Anything holding per-roll state -- a sound that must fire
 * once, the instant a flourish began -- has to reset on it, and the HUD's jackpot sting and reveal
 * burst are exactly that.
 *
 * <h2>Reading one instant</h2>
 * <p>Every query comes in two forms. The no-argument form reads {@code clock.millis()} for itself,
 * which is what a command or a test wants. The {@code ...At(long)} form takes the caller's already
 * read instant, which is what a renderer wants: asking eight questions in a frame through the
 * no-argument forms means eight clock reads that are not consistent with each other, and a reel's
 * lock deadline falling between two of them produces a frame reporting {@link RollState#SPINNING}
 * from one call and a locked reel from the next. {@link #nowMillis()} is how a caller gets an
 * instant off <em>this roll's</em> clock rather than off the wall clock, which matters wherever the
 * two differ -- that is, in every test.
 *
 * <p><b>Threading:</b> not thread safe. It is expected to live on the client thread, touched by the
 * chat callback and the HUD render callback, which are the same thread in a Minecraft client.
 */
public final class SlotRoll {

    /** What a reel shows when the creature died and nothing at all had dropped by its lock instant. */
    public static final LootDrop NO_DROP = new LootDrop("No Drop", "8", 1, false);

    /** Milliseconds for one symbol to scroll past, driving {@link Reel#spinPhase()}. */
    private static final long SYMBOL_PERIOD_MILLIS = 120L;

    /** Per-reel phase offset so the columns do not scroll in lockstep. */
    private static final long REEL_PHASE_OFFSET_MILLIS = 40L;

    /** Sentinel for "no jackpot captured in this roll". */
    private static final long NO_JACKPOT = Long.MIN_VALUE;

    /**
     * What {@link #jackpotActStartAt(long)} answers when this roll has no celebration coming: an
     * instant no clock reading can reach, so {@code now >= actTwoStart} is false for every frame.
     * Deliberately the same value as {@link ReelScroll#NEVER}, which is where it is normally fed.
     */
    public static final long NO_ACT_TWO = Long.MAX_VALUE;

    private final SlotRollConfig config;
    private final Clock clock;

    private final List<Capture> captures = new ArrayList<>();

    /**
     * What started the running roll: which activity, and what to caption it.
     *
     * <p>This is the generalisation. The machine used to know only about Diana and so held a
     * {@link MythologicalCreature}; it now holds a {@link LootEvent}, which every chance-based
     * activity in the game can produce, and Diana is simply the first one -- {@link
     * #start(MythologicalCreature)} builds a {@link LootSource#DIANA_MYTHOLOGICAL} event captioned
     * with the creature's name and hands it to the same code path as everything else.
     *
     * <p>Nothing downstream of here changed and nothing downstream of here needed to. The reels, the
     * loot window, the ranking and the two-act timeline were already written against {@code
     * LootDrop}, which has never known where a drop came from.
     */
    private LootEvent event;

    private MythologicalCreature creature;
    private boolean running;
    private long startMillis;
    private long jackpotMillis = NO_JACKPOT;

    /**
     * Counts the rolls this instance has started, so a presentation layer can tell a
     * <em>restart</em> from a continuation.
     *
     * <p>{@link #start(MythologicalCreature)} deliberately restarts over a running roll, which means
     * "the machine is active" is true on both sides of the transition and an observer watching only
     * {@link #active()} sees nothing happen. Anything holding per-roll state -- a sound that must
     * fire once, a flourish timestamp -- has to reset on that edge or it inherits the previous
     * roll's flags, so this counter is the edge.
     */
    private long rollId;

    /**
     * {@link #captures} in symbol-policy order, rebuilt only when the captures actually change.
     *
     * <p>{@link #reelsAt(long)} runs at the frame rate and asks for this ranking once per locked
     * reel. Deriving it with a stream and a freshly composed {@link Comparator} chain each time --
     * which is what this used to do -- allocated three lambdas, two comparator wrappers and a list
     * per reel per frame, and boxed a {@code Boolean} and an {@code Integer} on every comparison,
     * all to recompute an answer that cannot change once a reel has locked. A drop is captured a
     * handful of times per roll; a reel is drawn a few thousand times. The sort belongs on the
     * former.
     */
    private final List<Capture> ranked = new ArrayList<>();

    /** Whether {@link #ranked} still reflects {@link #captures}. */
    private boolean rankStale;

    /**
     * The symbol policy's ordering, allocated once: jackpots first, then larger stacks, then
     * arrival order. Hand-written rather than composed from {@code Comparator.comparing} so no
     * comparison boxes a primitive.
     */
    private static final Comparator<Capture> RANK = (a, b) -> {
        boolean ra = a.drop().rare();
        if (ra != b.drop().rare()) {
            return ra ? -1 : 1;
        }
        int byCount = Integer.compare(b.drop().count(), a.drop().count());
        return byCount != 0 ? byCount : Integer.compare(a.arrival(), b.arrival());
    };

    /** A drop plus the instant it was captured, which is what makes locking poll independent. */
    private record Capture(LootDrop drop, long atMillis, int arrival) {
    }

    public SlotRoll(SlotRollConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---------------------------------------------------------------- commands

    /**
     * Begins a roll for {@code event} at the current clock instant, replacing any roll already in
     * progress -- including one in the middle of its jackpot celebration (see the restart policy
     * above).
     *
     * <p>This is the general entry point: any source that can say "a chance-based thing just
     * resolved, and here is what to call it" can drive the machine through it. Diana reaches it via
     * {@link #start(MythologicalCreature)}, which is now a thin wrapper rather than a separate path.
     *
     * <p><b>Why this is not simply another {@code start} overload.</b> It cannot be. Java resolves
     * {@code start(null)} by picking the most specific applicable method, and {@link LootEvent} and
     * {@link MythologicalCreature} are unrelated types, so a {@code start(LootEvent)} overload makes
     * every existing {@code start(null)} call ambiguous -- including the one in the frozen Diana
     * test that pins null rejection. Editing that test to disambiguate would mean editing the proof
     * that Diana did not change, which is exactly backwards. A distinct name costs one identifier
     * and keeps the shipped path byte-for-byte the same; the overload would have cost a change to
     * the evidence.
     *
     * <p>The event's own {@code atMillis} is deliberately <em>not</em> used as the roll's start
     * instant. The whole timeline is derived from this roll's injected {@link Clock}, and letting a
     * detector's timestamp -- which may come from a different clock, or from a line the server
     * delayed -- set the origin would let a stale event start a roll that is already half over, or
     * one whose reels lock in the past. The event's timestamp stays available on {@link #event()}
     * for anything that wants to know how late the trigger was.
     *
     * @throws NullPointerException if {@code event} is null
     */
    public void startEvent(LootEvent event) {
        Objects.requireNonNull(event, "event");
        this.event = event;
        this.creature = null;
        this.captures.clear();
        this.ranked.clear();
        this.rankStale = false;
        this.jackpotMillis = NO_JACKPOT;
        this.startMillis = clock.millis();
        this.running = true;
        this.rollId++;
    }

    /**
     * Begins a roll for a defeated Mythological creature -- Diana's way in.
     *
     * <p>Behaviourally identical to what it has always been, and deliberately so: this is the one
     * path verified on the live server. It now expresses itself as a {@link LootEvent} on {@link
     * LootSource#DIANA_MYTHOLOGICAL} captioned with the creature's display name, so the creature is
     * one kind of subject rather than the only kind, and additionally keeps the creature itself for
     * {@link #creature()}, which Diana's own presentation still reads for the name colour.
     *
     * @throws NullPointerException if {@code creature} is null
     */
    public void start(MythologicalCreature creature) {
        Objects.requireNonNull(creature, "creature");
        startEvent(new LootEvent(LootSource.DIANA_MYTHOLOGICAL, creature.displayName(), clock.millis()));
        this.creature = creature;
    }

    /**
     * Offers a parsed drop line to the running roll.
     *
     * <p>Ignored when no roll is running and ignored once the loot window has closed, so loot from
     * an unrelated mob cannot bleed into the next kill's reels. A captured jackpot drop latches
     * {@link #jackpot()} on the spot; whether it also earns the celebration depends on how late it
     * is, which is the jackpot policy on the class.
     *
     * @param drop the parsed drop; null is ignored rather than thrown, because this is called
     *             straight from a chat callback where a parse miss is ordinary
     */
    public void offerDrop(LootDrop drop) {
        long now = clock.millis();
        sweepAt(now);
        if (drop == null || !running) {
            return;
        }
        // Compared as start + window rather than now - start so a clock far from zero cannot wrap
        // the subtraction and reopen a window that has long since shut.
        if (now > addClamped(startMillis, config.lootWindowMillis())) {
            return;
        }
        captures.add(new Capture(drop, now, captures.size()));
        rankStale = true;
        if (drop.rare() && jackpotMillis == NO_JACKPOT) {
            jackpotMillis = now;
        }
    }

    /** Returns the machine to {@link RollState#IDLE} at once, e.g. on world change or disconnect. */
    public void reset() {
        running = false;
        event = null;
        creature = null;
        captures.clear();
        ranked.clear();
        rankStale = false;
        jackpotMillis = NO_JACKPOT;
        startMillis = 0L;
    }

    // ---------------------------------------------------------------- queries

    /**
     * The roll's own notion of "now".
     *
     * <p>Every {@code ...At(long)} overload below is a pure function of this number, so a caller
     * that wants a self-consistent view of one frame reads this once and passes it to all of them.
     * It is exposed rather than left implicit because the alternative -- a renderer reading
     * {@code System.currentTimeMillis()} for itself -- silently disagrees with the roll whenever
     * the injected {@link Clock} is not the system clock, which is exactly what every test uses.
     *
     * @return the injected clock's current reading in milliseconds
     */
    public long nowMillis() {
        return clock.millis();
    }

    /**
     * How many rolls this instance has started.
     *
     * <p>Zero before the first {@link #start(MythologicalCreature)}, and incremented by every one
     * after -- including a restart over a roll that was still running, which is the transition an
     * observer watching {@link #active()} alone cannot see. A caller holding per-roll presentation
     * state should reset it whenever this number changes.
     *
     * @return a monotonically increasing roll counter
     */
    public long rollId() {
        return rollId;
    }

    /** The phase the roll is in at the current clock instant. */
    public RollState state() {
        return stateAt(clock.millis());
    }

    /**
     * The phase the roll is in at {@code now}.
     *
     * @param now a reading of this roll's clock, normally from {@link #nowMillis()}
     * @return the phase at that instant
     */
    public RollState stateAt(long now) {
        sweepAt(now);
        if (!running) {
            return RollState.IDLE;
        }
        // Act two is asked about first, and the act-one ladder below is reached only while act two
        // has not opened yet. Ordering it the other way round -- act one's ladder first, act two as
        // its tail -- is what made the stall structural: LOCKING and SETTLED were returned for
        // instants that now belong to the celebration, and reelsAt would have disagreed with this
        // method about them. See ReviewFindingsTest.noDesync for the assertion that catches exactly
        // that split.
        if (celebrating() && now >= actTwoStartMillis()) {
            if (now < jackpotSpinStartMillis()) {
                return RollState.JACKPOT_INTRO;
            }
            if (now < jackpotLockMillis(0)) {
                return RollState.JACKPOT_SPIN;
            }
            if (now < jackpotLockMillis(config.reelCount() - 1)) {
                return RollState.JACKPOT_LOCK;
            }
            if (now < jackpotHoldEndMillis()) {
                return RollState.JACKPOT_HOLD;
            }
            return RollState.FADING;
        }
        if (now < lockMillis(0)) {
            return RollState.SPINNING;
        }
        if (now < lockMillis(config.reelCount() - 1)) {
            return RollState.LOCKING;
        }
        if (now < settleEndMillis()) {
            return RollState.SETTLED;
        }
        // The sweep above already turned a finished roll idle, so the only phase left is the fade.
        // Only an ordinary roll gets here: a celebrating one has an act-two start no later than the
        // settle's end, so every instant past the settle went through the branch above.
        return RollState.FADING;
    }

    /** The HUD's early-out: false exactly when {@link #state()} is {@link RollState#IDLE}. */
    public boolean active() {
        return state() != RollState.IDLE;
    }

    /**
     * {@link #active()} at a caller-supplied instant.
     *
     * @param now a reading of this roll's clock
     * @return whether a roll is running at that instant
     */
    public boolean activeAt(long now) {
        return stateAt(now) != RollState.IDLE;
    }

    /**
     * A fresh snapshot of every column, left to right.
     *
     * @return {@code reelCount} reels while a roll is running, or an empty list when idle
     */
    public List<Reel> reels() {
        return reelsAt(clock.millis());
    }

    /**
     * Every column as it stands at {@code now}.
     *
     * <p>In act one a reel reports the drop it locked on, or null while it is still spinning. From
     * {@link RollState#JACKPOT_INTRO} onwards -- that is, from the first instant of act two -- every
     * reel reports {@link #jackpotSymbol()} whether it has landed yet or not, and every reel is
     * already unlocked and turning. See {@link Reel} for why {@code locked}, not {@code symbol}, is
     * the flag a renderer should branch on.
     *
     * <p>On a celebrating roll act one's locks are unreachable: act two opens at the instant the
     * first column would have stopped, so the branch below that locks a reel on the real loot is
     * only ever taken by an ordinary roll, or by a roll whose banner arrived after some columns had
     * already landed. Nothing in here stops a turning column and starts it again -- the gold
     * arrives over reels that are still moving, and the only landing in a lucky roll is act two's.
     *
     * @param now a reading of this roll's clock
     * @return {@code reelCount} reels while a roll is running, or an empty list when idle
     */
    public List<Reel> reelsAt(long now) {
        sweepAt(now);
        if (!running) {
            return List.of();
        }
        int count = config.reelCount();
        List<Reel> out = new ArrayList<>(count);
        // Act two's motion starts the instant act two does, not when the gold has finished arriving:
        // the reels are already turning underneath the wash. Gating this on jackpotSpinStartMillis()
        // instead held every column still for the whole of JACKPOT_INTRO, which read as the machine
        // pausing to change colour and only then deciding to spin. The gate is actTwoStartMillis()
        // rather than settleEndMillis() for the same reason one act further out: measured from the
        // settle, every celebrating roll stopped dead for the whole of it before this branch could
        // ever be taken.
        if (celebrating() && now >= actTwoStartMillis()) {
            LootDrop symbol = frozenJackpotSymbol();
            for (int i = 0; i < count; i++) {
                boolean locked = now >= jackpotLockMillis(i);
                out.add(new Reel(i, locked, symbol, locked ? 0.0d : spinPhase(i, now)));
            }
            return List.copyOf(out);
        }
        List<LootDrop> shown = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long lock = lockMillis(i);
            if (now < lock) {
                out.add(new Reel(i, false, null, spinPhase(i, now)));
                continue;
            }
            // A reel that locked earlier could only have seen the drops that had arrived by then.
            LootDrop symbol = chooseAt(i, lock, shown);
            shown.add(symbol);
            out.add(new Reel(i, true, symbol, 0.0d));
        }
        return List.copyOf(out);
    }

    /** True once a drop carrying the server's rare-drop banner has been captured in this roll. */
    public boolean jackpot() {
        return jackpotAt(clock.millis());
    }

    /**
     * {@link #jackpot()} at a caller-supplied instant.
     *
     * <p>Deliberately <em>not</em> the same question as {@link #inJackpotSequence()}: this is "was a
     * rare drop announced", which is true from the instant the banner is captured and stays true
     * through the whole roll, including the entirety of act one, where nothing on screen may show
     * it. A renderer that paints gold off this flag paints it from the first frame, which is exactly
     * the behaviour the two-act timeline exists to remove.
     *
     * @param now a reading of this roll's clock
     * @return whether a jackpot has been captured in the roll running at that instant
     */
    public boolean jackpotAt(long now) {
        sweepAt(now);
        return running && jackpotMillis != NO_JACKPOT;
    }

    /** Whether the roll is in one of the four {@code JACKPOT_*} phases at the current instant. */
    public boolean inJackpotSequence() {
        return inJackpotSequenceAt(clock.millis());
    }

    /**
     * {@link #inJackpotSequence()} at a caller-supplied instant.
     *
     * @param now a reading of this roll's clock
     * @return whether act two is playing at that instant
     */
    public boolean inJackpotSequenceAt(long now) {
        RollState state = stateAt(now);
        return state == RollState.JACKPOT_INTRO
                || state == RollState.JACKPOT_SPIN
                || state == RollState.JACKPOT_LOCK
                || state == RollState.JACKPOT_HOLD;
    }

    /** How far the gold wash has come, at the current instant. */
    public double jackpotIntroProgress() {
        return jackpotIntroProgressAt(clock.millis());
    }

    /**
     * How far the gold wash has come at {@code now}, as a fraction the renderer can interpolate on.
     *
     * <p>0 for the whole of act one and for a roll that never earned a celebration; ramping 0 to 1
     * across {@link RollState#JACKPOT_INTRO}; and pinned at 1 for every phase after it, so the gold
     * that washed in stays in for the spin, the landings, the hold and the fade rather than draining
     * back out the moment the reels move. A zero-length intro reads 1 from the instant act two
     * begins.
     *
     * @param now a reading of this roll's clock
     * @return a value in [0,1]
     */
    public double jackpotIntroProgressAt(long now) {
        sweepAt(now);
        if (!running || !celebrating()) {
            return 0.0d;
        }
        long begin = actTwoStartMillis();
        if (now <= begin) {
            return 0.0d;
        }
        long duration = config.jackpotIntroMillis();
        if (duration <= 0L) {
            return 1.0d;
        }
        long elapsed = now - begin;
        // A negative elapsed here can only come from that subtraction overflowing across the full
        // width of the type, which means "now" is astronomically past the start: fully washed in.
        if (elapsed < 0L || elapsed >= duration) {
            return 1.0d;
        }
        return (double) elapsed / duration;
    }

    /**
     * The instant the roll running at {@code now} began, or {@code now} itself when idle.
     *
     * <p>Published for one reason: a renderer that integrates a scroll rate over the roll needs an
     * origin, and the only honest origin is the roll's own start. Deriving one from the wall clock
     * instead -- dividing {@code System.currentTimeMillis()} by a cell period, which is what the
     * HUD used to do -- makes which symbols a short spin shows a function of what the clock happened
     * to read, so an entry can be missing from one roll and present in the next for no visible
     * reason. Answering {@code now} rather than zero when idle means a caller that integrates from
     * it reads a travelled distance of zero rather than of several decades.
     *
     * @param now a reading of this roll's clock
     * @return the roll's start instant, or {@code now} when nothing is running
     */
    public long rollStartAt(long now) {
        sweepAt(now);
        return running ? startMillis : now;
    }

    /**
     * The instant act two opens for the roll running at {@code now}, or {@link #NO_ACT_TWO} when
     * this roll has no celebration coming.
     *
     * <p>This is the boundary the whole seamless sequence turns on, and it is published rather than
     * left private because presentation needs it: the scroll accelerates here, and an effect that
     * has to accelerate <em>at</em> a boundary cannot be driven by having noticed the boundary a
     * frame later. A renderer feeds this straight to
     * {@link ReelScroll#cellsTravelled(long, long, long, long, long)}, whose {@link ReelScroll#NEVER}
     * is deliberately the same value as {@link #NO_ACT_TWO} so the no-celebration case needs no
     * branch at the call site.
     *
     * @param now a reading of this roll's clock
     * @return the act-two start instant, or {@link #NO_ACT_TWO}
     */
    public long jackpotActStartAt(long now) {
        sweepAt(now);
        return running && celebrating() ? actTwoStartMillis() : NO_ACT_TWO;
    }

    /** The single drop all reels converge on in act two, at the current instant. */
    public LootDrop jackpotSymbol() {
        return jackpotSymbolAt(clock.millis());
    }

    /**
     * The single drop all reels converge on in act two, at {@code now}.
     *
     * <p>Null exactly when there is no celebration to come: no roll running, no jackpot captured, or
     * one captured too late to earn the sequence. Non-null for the entire roll when there is,
     * including throughout act one -- a caller can therefore ask what is coming before it arrives,
     * which is what lets a HUD preload an item sprite rather than resolve it on the frame the gold
     * appears.
     *
     * @param now a reading of this roll's clock
     * @return the winning drop, or null when this roll has no jackpot sequence
     */
    public LootDrop jackpotSymbolAt(long now) {
        sweepAt(now);
        if (!running || !celebrating()) {
            return null;
        }
        return frozenJackpotSymbol();
    }

    /** The event that started the running roll, or empty when idle. */
    public Optional<LootEvent> event() {
        return Optional.ofNullable(eventAt(clock.millis()));
    }

    /**
     * The event that started the roll running at {@code now}.
     *
     * <p>Returns the value rather than an {@link Optional} because this is on the render path and
     * {@code Optional.of} allocates, exactly as {@link #creatureAt(long)} does.
     *
     * @param now a reading of this roll's clock
     * @return the event, or null when the machine is idle at that instant
     */
    public LootEvent eventAt(long now) {
        sweepAt(now);
        return running ? event : null;
    }

    /**
     * Which activity started the roll running at {@code now}, or null when idle.
     *
     * <p>What a widget branches on to caption itself and what a config check reads to decide whether
     * this source is allowed to be on screen at all.
     */
    public LootSource sourceAt(long now) {
        LootEvent current = eventAt(now);
        return current == null ? null : current.source();
    }

    /**
     * What to call the thing that paid out, at {@code now}: "Minos Inquisitor", "Vanguard Corpse",
     * "Superior Dragon". Null when idle.
     */
    public String subjectAt(long now) {
        LootEvent current = eventAt(now);
        return current == null ? null : current.subject();
    }

    /**
     * The creature whose death started the running roll, or empty when idle.
     *
     * <p>Empty for every non-Diana source, which is the point: the creature is Diana's business, not
     * the machine's. A caller that wants a caption for any source wants {@link #subjectAt(long)}.
     */
    public Optional<MythologicalCreature> creature() {
        return Optional.ofNullable(creatureAt(clock.millis()));
    }

    /**
     * The creature whose death started the roll running at {@code now}.
     *
     * <p>Returns the value rather than an {@link Optional} because this is on the render path and
     * {@code Optional.of} allocates; the boxed form stays for the command surface.
     *
     * @param now a reading of this roll's clock
     * @return the creature, or null when the machine is idle at that instant
     */
    public MythologicalCreature creatureAt(long now) {
        sweepAt(now);
        return running ? creature : null;
    }

    /**
     * Every drop captured in this roll, in arrival order -- including drops that arrived after the
     * last reel locked and so could not reach a column. Empty when idle.
     */
    public List<LootDrop> capturedDrops() {
        sweep();
        if (!running) {
            return List.of();
        }
        return captures.stream().map(Capture::drop).toList();
    }

    /**
     * How many drops {@link #capturedDrops()} would list, without building the list.
     *
     * <p>The suppression rule reads this twice around a single feed to observe whether the roll
     * actually took a line's drop, so it must not allocate.
     *
     * @return the captured-drop count, or 0 when idle
     */
    public int capturedDropCount() {
        sweep();
        return running ? captures.size() : 0;
    }

    // ---------------------------------------------------------------- derivation

    /** Drops the roll to IDLE once the fade has finished, so nothing lingers between kills. */
    private void sweep() {
        sweepAt(clock.millis());
    }

    /** {@link #sweep()} against an already-read instant, so one frame reads the clock once. */
    private void sweepAt(long now) {
        if (running && now >= fadeEndMillis()) {
            reset();
        }
    }

    /**
     * Whether this roll has earned act two.
     *
     * <p>The one rule: a jackpot captured strictly before the settle ends fires the sequence, and
     * one captured on or after that instant does not. See the jackpot policy on the class for why
     * the boundary is where it is. This is a pure function of {@code jackpotMillis} and the config,
     * so it cannot change under a caller mid-frame and needs no instant of its own.
     */
    private boolean celebrating() {
        return jackpotMillis != NO_JACKPOT && jackpotMillis < settleEndMillis();
    }

    /** When reel {@code i} locks in act one. Loot never moves this; see the class timeline. */
    private long lockMillis(int reelIndex) {
        return addClamped(startMillis, config.baseLockOffset(reelIndex));
    }

    /**
     * When an ordinary roll's settle ends and it starts fading.
     *
     * <p>Still the arming cutoff for {@link #celebrating()}, and no longer the origin of act two.
     * Those were one instant playing two roles, and the second role is what produced the stall: a
     * celebration measured from here could not begin until act one had locked every column and held
     * them for the whole settle. {@link #actTwoStartMillis()} is the origin now; this stays exactly
     * where it was, because "a rare captured strictly before the settle ends earns the sequence" is
     * a rule about lateness that must not become self-referential.
     */
    private long settleEndMillis() {
        return addClamped(lockMillis(config.reelCount() - 1), config.settleMillis());
    }

    /**
     * When act two opens: the first column's ordinary lock instant, or the banner if it was late.
     *
     * <p>The first lock is the earliest instant the celebration can start without cutting the
     * ordinary spin short -- the reels have to have been turning for a beat before anything can be
     * said to interrupt them -- and it is the latest instant it can start without a visible stop,
     * because it is exactly where the columns would otherwise have begun to land. Taking the later
     * of it and the banner is what handles Hypixel printing the rare-drop line after the ordinary
     * ones: a banner during the spin gets the full seamless sequence, and one arriving after some
     * columns have already locked opens act two on the spot rather than waiting out the settle,
     * which is the shortest stop the timing allows.
     *
     * <p>Only meaningful while {@link #celebrating()}, which is what bounds it: that guarantees a
     * capture strictly before {@link #settleEndMillis()}, so the answer always lies in
     * {@code [lockMillis(0), settleEndMillis()]} and the act-one ladder in {@link #stateAt(long)}
     * can never be reached past it.
     */
    private long actTwoStartMillis() {
        long earliest = lockMillis(0);
        return jackpotMillis > earliest ? jackpotMillis : earliest;
    }

    /** When act two's gold has finished washing in; the reels have been turning throughout. */
    private long jackpotSpinStartMillis() {
        return addClamped(actTwoStartMillis(), config.jackpotSpinStartOffset());
    }

    /** When reel {@code i} lands on the jackpot symbol, staggered left to right. */
    private long jackpotLockMillis(int reelIndex) {
        return addClamped(actTwoStartMillis(), config.jackpotLockOffset(reelIndex));
    }

    /** When the three of a kind stops being held and the fade begins. */
    private long jackpotHoldEndMillis() {
        return addClamped(jackpotLockMillis(config.reelCount() - 1), config.jackpotHoldMillis());
    }

    /**
     * When the roll goes idle: the fade measured from the end of whichever act finished last.
     *
     * <p>A celebrating roll takes the later of its own end and the ordinary one. Act two now starts
     * early enough that a celebration configured shorter than the settle it replaced would finish
     * before an ordinary roll of the same length would have, and a lucky kill must never be
     * <em>briefer</em> than an unlucky one -- that would yank the result off the screen as a reward
     * for the rare drop.
     */
    private long fadeEndMillis() {
        long lastVisible = settleEndMillis();
        if (celebrating()) {
            long celebrationEnd = jackpotHoldEndMillis();
            if (celebrationEnd > lastVisible) {
                lastVisible = celebrationEnd;
            }
        }
        return addClamped(lastVisible, config.fadeMillis());
    }

    /**
     * The drop every act-two reel converges on: the highest-ranked rare capture that had arrived
     * before act two began.
     *
     * <p>{@link #ranked} already sorts rares to the front, so the first visible rare in it is the
     * best one -- biggest stack, earliest arrival to break a tie. The visibility cutoff is act two's
     * own start instant rather than {@code now}, which is what freezes the answer: a
     * {@code lootWindowMillis} long enough to still be open during the celebration would otherwise
     * let a late banner rewrite reels the player is watching land.
     *
     * <p>The cutoff is inclusive, and has to be. Act two starts no earlier than the banner that
     * armed it, and on the late-banner path it starts <em>on</em> that banner, so a strict
     * comparison would exclude the very drop the celebration exists for and converge three columns
     * on the fallback.
     *
     * <p>Only called while {@link #celebrating()}, which guarantees a rare captured no later than
     * the cutoff, so the fallback is unreachable; it returns {@link #NO_DROP} rather than null
     * because {@link Reel} forbids a locked reel without a symbol and a crash in a render loop is
     * worse than a wrong picture.
     */
    private LootDrop frozenJackpotSymbol() {
        ensureRanked();
        long cutoff = actTwoStartMillis();
        for (int i = 0; i < ranked.size(); i++) {
            Capture candidate = ranked.get(i);
            if (candidate.drop().rare() && candidate.atMillis() <= cutoff) {
                return candidate.drop();
            }
        }
        return NO_DROP;
    }

    /**
     * {@code a + b}, clamped to the {@code long} range instead of wrapping.
     *
     * <p>Every instant on the timeline is built by adding a configured duration to a clock reading,
     * and both are attacker-shaped: {@link SlotRollConfig} only rejects negative durations, and a
     * {@link Clock} promises nothing about its origin. A wrapped sum turns a very distant deadline
     * into a very past one, which {@link #sweep()} then reads as "this roll finished long ago" and
     * kills the roll on the millisecond it started. Saturating keeps the ordering that every phase
     * comparison depends on.
     */
    private static long addClamped(long a, long b) {
        long sum = a + b;
        // Overflow is exactly the case where both operands differ in sign from the result.
        if (((a ^ sum) & (b ^ sum)) < 0L) {
            return b < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }

    /**
     * Refreshes {@link #ranked} if a drop has been captured since it was last built.
     *
     * <p>Called from the render path, so the common case has to be a boolean test. It is: captures
     * change a handful of times per roll and the reels are read thousands of times, and the
     * ordering is a pure function of the captures, so the sort runs on the rare side of that ratio.
     */
    private void ensureRanked() {
        if (!rankStale) {
            return;
        }
        rankStale = false;
        ranked.clear();
        ranked.addAll(captures);
        ranked.sort(RANK);
    }

    /**
     * Applies the symbol policy documented on the class, for the reel that locked at {@code at}.
     *
     * <p>Two passes over the (tiny) ranking rather than one filtered copy of it: the first finds the
     * best drop no earlier reel is already showing and counts how many were visible at all, and the
     * second -- reached only when every visible drop is already on a reel -- picks the cycled one.
     * Neither allocates, which is the whole point, since this runs once per locked reel per frame.
     */
    private LootDrop chooseAt(int reelIndex, long at, List<LootDrop> alreadyShown) {
        ensureRanked();
        int visible = 0;
        LootDrop pick = null;
        for (int i = 0; i < ranked.size(); i++) {
            Capture candidate = ranked.get(i);
            if (candidate.atMillis() > at) {
                continue;
            }
            visible++;
            if (pick == null && !alreadyShown.contains(candidate.drop())) {
                pick = candidate.drop();
            }
        }
        if (visible == 0) {
            return NO_DROP;
        }
        if (pick != null) {
            return pick;
        }
        int target = reelIndex % visible;
        int seen = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Capture candidate = ranked.get(i);
            if (candidate.atMillis() > at) {
                continue;
            }
            if (seen++ == target) {
                return candidate.drop();
            }
        }
        return NO_DROP;
    }

    private double spinPhase(int reelIndex, long now) {
        long elapsed = now - startMillis + (long) reelIndex * REEL_PHASE_OFFSET_MILLIS;
        return (double) Math.floorMod(elapsed, SYMBOL_PERIOD_MILLIS) / SYMBOL_PERIOD_MILLIS;
    }
}
