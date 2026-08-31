package com.skyprism.core.diana;

/**
 * The nine phases of one slot-machine roll, declared in the order they always occur.
 *
 * <p>The HUD renderer switches on this instead of inspecting timestamps, so the animation
 * and the logic can never disagree about "what part are we in". Every phase boundary is a
 * pure function of {@code clock.millis()} and the roll's start time, which is what makes the
 * whole feature reproducible under a fixed clock in tests.
 *
 * <h2>Two acts</h2>
 * <p>An ordinary roll is the first four values and then {@link #FADING}. A roll that captured a
 * rare drop plays that first act <em>completely unchanged</em> -- the reels lock on the real loot,
 * at the ordinary instants, with no gold anywhere -- and only once it has fully settled does the
 * second act begin: the four {@code JACKPOT_*} phases, a casino three-of-a-kind on the winning
 * item. The jackpot is deliberately invisible until the normal result has been read, and then
 * arrives as one event rather than two -- the reels are unlocked and the gold is ramping from the
 * same instant, so {@link #JACKPOT_INTRO} and {@link #JACKPOT_SPIN} are two stretches of one
 * continuous spin rather than a pause followed by a spin.
 *
 * <h2>Why the ordinals are the timeline</h2>
 * <p>{@link #FADING} sits last, after {@link #JACKPOT_HOLD}, rather than beside {@link #SETTLED}
 * where the first act would put it. That is what makes {@code ordinal()} a monotonically
 * non-decreasing function of time on <em>both</em> paths, so "the phase never runs backwards" is a
 * property anything can assert with a single comparison. It is also why a rare drop that arrives
 * after the fade has already begun cannot start the second act: doing so would step the roll from
 * {@code FADING} back to {@code JACKPOT_INTRO}, which on screen is a half-faded panel snapping
 * back to full opacity.
 */
public enum RollState {

    /** Nothing to draw. {@link SlotRoll#active()} is false here; the HUD hook returns immediately. */
    IDLE,

    /** All reels scrolling, no symbol decided yet. Drops may already be arriving and being captured. */
    SPINNING,

    /**
     * At least one reel has locked but not all of them.
     *
     * <p>Only observable when the configuration actually staggers the locks: with
     * {@code reelCount == 1}, or {@code lockStaggerMillis == 0}, every reel locks on the same
     * millisecond and the roll steps straight from {@link #SPINNING} to {@link #SETTLED}.
     */
    LOCKING,

    /** Every reel locked, the real result held still so the player can read it. */
    SETTLED,

    /**
     * The gold washes in, and the reels break loose underneath it.
     *
     * <p>Both at once, which is the point. Every column unlocks on the first instant of this
     * phase and is already turning towards {@link SlotRoll#jackpotSymbol()}, while the colour
     * ramps over the top of them on {@link SlotRoll#jackpotIntroProgress()} running 0 to 1. The
     * beat that tells the player something else is about to happen is therefore a machine that
     * has started moving again <em>and</em> started turning gold, rather than a still picture
     * that changes colour and only afterwards decides to spin.
     */
    JACKPOT_INTRO,

    /**
     * The reels keep turning with the wash complete, before the first of them lands.
     *
     * <p>Nothing starts here; the columns have been moving since {@link #JACKPOT_INTRO} and this
     * phase is simply the rest of that spin, once the gold has finished arriving. No reel is
     * locked, but {@link SlotRoll#reels()} still reports {@link SlotRoll#jackpotSymbol()} on
     * each one -- as it does throughout act two -- because the destination is known in advance
     * and the renderer wants it to blur the right item past.
     */
    JACKPOT_SPIN,

    /**
     * The reels come to rest one at a time, left to right, every one of them on the same item.
     *
     * <p>Staggered by {@code jackpotLockStaggerMillis}, which is the whole point -- a slot machine
     * that reveals three of a kind simultaneously reveals nothing.
     */
    JACKPOT_LOCK,

    /** Three of a kind on screen, held still for {@code jackpotHoldMillis} while it lands. */
    JACKPOT_HOLD,

    /** Result still on screen but on its way out; the renderer fades alpha across this phase. */
    FADING
}
