package com.skyprism.core.diana;

/**
 * Every tunable duration of one roll, in milliseconds, as a single immutable value.
 *
 * <p>These are gathered into one record rather than left as fields on {@link SlotRoll} so that
 * the config screen can build a candidate, validate it by construction, and hand it over
 * atomically -- a half-applied set of durations would produce a roll whose phases overlap.
 *
 * <p>The durations compose into two consecutive acts. The first always plays:
 * <pre>
 *   start ── spinMillis ──► reel 0 locks ── lockStaggerMillis ──► reel 1 locks ── ... ──►
 *   last reel locks ── settleMillis ──► (act two, or the fade)
 * </pre>
 * The second plays only when a rare drop was captured before the first act finished, and nothing
 * about it reaches back into the first act -- a jackpot no longer buys the ordinary spin any extra
 * time, because a roll that visibly behaves differently from the outset has already given the
 * surprise away:
 * <pre>
 *   settled ── reels break loose AND gold begins ── jackpotIntroMillis ──► gold fully in,
 *   reels still turning ── jackpotSpinMillis ──► reel 0 lands ──
 *   jackpotLockStaggerMillis ──► reel 1 lands ── ... ──► last reel lands ── jackpotHoldMillis ──►
 *   fade begins ── fadeMillis ──► IDLE
 * </pre>
 *
 * @param reelCount               number of columns, 1..5. More than five will not fit any sane
 *                                HUD width, and zero would make the roll meaningless.
 * @param spinMillis              free-spin time before the leftmost reel locks
 * @param lockStaggerMillis       extra delay per reel, so they stop left to right; 0 stops them
 *                                all together and makes {@link RollState#LOCKING} unobservable
 * @param lootWindowMillis        how long after the start drops are still attributed to this kill.
 *                                Deliberately allowed to outlast the locks: Hypixel sometimes
 *                                prints the rare-drop banner a second after the ordinary drops,
 *                                and that late line must still count towards
 *                                {@link SlotRoll#jackpot()}.
 * @param settleMillis            how long the real result is held still before the roll either
 *                                fades or hands over to the jackpot sequence
 * @param fadeMillis              how long the result takes to fade out afterwards
 * @param jackpotIntroMillis      how long the gold takes to wash in. Every reel breaks loose at
 *                                the start of it rather than at the end, so this is the length of
 *                                the colour ramp and not of a pause; 0 makes
 *                                {@link RollState#JACKPOT_INTRO} unobservable and the gold snap on
 * @param jackpotSpinMillis       how much longer the reels turn after the wash is complete,
 *                                before the first one lands
 * @param jackpotLockStaggerMillis extra delay per reel in the second act, so the three of a kind
 *                                is revealed one column at a time; 0 lands them together and
 *                                makes {@link RollState#JACKPOT_LOCK} unobservable
 * @param jackpotHoldMillis       how long the three of a kind is held before the fade
 */
public record SlotRollConfig(int reelCount,
                             long spinMillis,
                             long lockStaggerMillis,
                             long lootWindowMillis,
                             long settleMillis,
                             long fadeMillis,
                             long jackpotIntroMillis,
                             long jackpotSpinMillis,
                             long jackpotLockStaggerMillis,
                             long jackpotHoldMillis) {

    /** Inclusive bounds on {@link #reelCount}. */
    public static final int MIN_REELS = 1;
    public static final int MAX_REELS = 5;

    public SlotRollConfig {
        if (reelCount < MIN_REELS || reelCount > MAX_REELS) {
            throw new IllegalArgumentException(
                    "reelCount must be " + MIN_REELS + ".." + MAX_REELS + " but was " + reelCount);
        }
        requireNonNegative(spinMillis, "spinMillis");
        requireNonNegative(lockStaggerMillis, "lockStaggerMillis");
        requireNonNegative(lootWindowMillis, "lootWindowMillis");
        requireNonNegative(settleMillis, "settleMillis");
        requireNonNegative(fadeMillis, "fadeMillis");
        requireNonNegative(jackpotIntroMillis, "jackpotIntroMillis");
        requireNonNegative(jackpotSpinMillis, "jackpotSpinMillis");
        requireNonNegative(jackpotLockStaggerMillis, "jackpotLockStaggerMillis");
        requireNonNegative(jackpotHoldMillis, "jackpotHoldMillis");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
        }
    }

    /**
     * The shipped defaults: a three-reel machine whose ordinary roll finishes in about four and a
     * half seconds, and whose jackpot celebration adds roughly four and a half more.
     *
     * <p>The ordinary timings are unchanged from before the jackpot rework, because the ordinary
     * roll is now the only thing most kills ever show and it was already tuned: over well before
     * the player has walked to the next burrow, with a three-second loot window that comfortably
     * covers Hypixel's habit of printing a {@code RARE DROP!} banner a beat after the ordinary
     * drop lines.
     *
     * <p>The jackpot act is tuned to earn attention without outstaying it. 600ms of gold is long
     * enough to read as "wait, something else is happening" and short enough to be fully in well
     * before the reels it washed over come to rest; 900ms more of spin plus 280ms between landings
     * gives each column its own moment, so the third one lands on a player who is already watching
     * for it; 2200ms of hold is about as long
     * as a screenshot takes. That is 4.26 seconds of celebration plus the fade -- long enough to be
     * an occasion, short enough that a lucky streak never becomes a queue of animations.
     */
    public static SlotRollConfig defaults() {
        return new SlotRollConfig(3, 1200L, 250L, 3000L, 2500L, 500L, 600L, 900L, 280L, 2200L);
    }

    /**
     * The offset from the roll start at which reel {@code i} locks in the first act.
     *
     * <p>Saturating rather than wrapping. Only negatives are rejected above, so a config screen or
     * a hand-edited file can hand over durations near {@link Long#MAX_VALUE}; multiplying one by the
     * reel index would then wrap to a negative offset, which reads as "this reel locked long ago"
     * and collapses the whole roll to nothing. Clamping instead makes an absurd duration behave like
     * the absurdly long spin it asked for.
     */
    long baseLockOffset(int reelIndex) {
        long staggerTotal = staggerTotal(reelIndex, lockStaggerMillis);
        long sum = spinMillis + staggerTotal;
        return sum < 0L ? Long.MAX_VALUE : sum;   // both operands are >= 0, so a wrap shows as a negative
    }

    /**
     * The offset from the <em>end of the settle phase</em> at which reel {@code i} lands in the
     * jackpot act, i.e. the gold wash plus the re-spin plus this reel's share of the stagger.
     *
     * <p>Saturating for the same reason as {@link #baseLockOffset(int)}, and in the same way: three
     * unbounded non-negative durations are being added here, so every partial sum is checked rather
     * than only the last.
     */
    long jackpotLockOffset(int reelIndex) {
        long spinEnd = jackpotIntroMillis + jackpotSpinMillis;
        if (spinEnd < 0L) {
            return Long.MAX_VALUE;
        }
        long sum = spinEnd + staggerTotal(reelIndex, jackpotLockStaggerMillis);
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /** The offset from the end of the settle phase at which the jackpot reels start moving again. */
    long jackpotSpinStartOffset() {
        return jackpotIntroMillis;
    }

    /** {@code reelIndex * stagger}, clamped, given that both operands are known non-negative. */
    private static long staggerTotal(int reelIndex, long stagger) {
        if (reelIndex == 0) {
            return 0L;
        }
        return stagger <= (Long.MAX_VALUE / reelIndex) ? (long) reelIndex * stagger : Long.MAX_VALUE;
    }
}
