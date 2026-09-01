package com.skyprism.core.diana;

/**
 * The nine phases of one slot-machine roll, declared in the order they always occur.
 *
 * <p>The HUD renderer switches on this instead of inspecting timestamps, so the animation
 * and the logic can never disagree about "what part are we in". Every phase boundary is a
 * pure function of {@code clock.millis()} and the roll's start time, which is what makes the
 * whole feature reproducible under a fixed clock in tests.
 *
 * <h2>Two acts, and no stop between them</h2>
 * <p>An ordinary roll is the first four values and then {@link #FADING}: it spins, it locks left to
 * right on the real loot, it holds, it fades.
 *
 * <p>A roll that captured a rare drop takes a different route through the same enum, and the
 * difference is that it never stops. It spins, and at the instant the first column would have
 * locked it goes to {@link #JACKPOT_INTRO} instead -- the gold arrives over reels that are still
 * turning -- then {@link #JACKPOT_SPIN}, then the columns land one at a time on the winning item in
 * {@link #JACKPOT_LOCK}, then {@link #JACKPOT_HOLD} and the fade. {@link #LOCKING} and
 * {@link #SETTLED} are skipped outright, which is the point: reached, they were a dead stop and a
 * restart in the middle of the one animation that is meant to build.
 *
 * <p>The one exception is a banner Hypixel printed late. A rare captured after some columns have
 * already locked opens act two on the spot, so such a roll does pass through {@link #LOCKING}, and
 * through {@link #SETTLED} if every column had landed, before unlocking them again. That is the
 * shortest stop the timing allows once the server has told us too late.
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
     * The gold washes in over reels that have not stopped turning.
     *
     * <p>On the ordinary lucky roll no column has locked when this phase opens -- it opens at the
     * instant the first one would have -- so there is nothing to unlock and nothing to restart, and
     * the colour simply arrives on a machine already in motion, ramping on
     * {@link SlotRoll#jackpotIntroProgress()} running 0 to 1 while every column turns towards
     * {@link SlotRoll#jackpotSymbol()}. The beat that tells the player something else is about to
     * happen is a spin that should have ended and did not.
     *
     * <p>After a late banner some columns had landed, and those unlock here. That is the one shape
     * in which this phase has anything to break loose.
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
