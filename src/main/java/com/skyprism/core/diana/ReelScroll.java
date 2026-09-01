package com.skyprism.core.diana;

/**
 * How far the drums have turned, as one number that never runs backwards.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A renderer needs two things about a spinning column on any given frame: which symbol is in
 * the window, and how far down the window that symbol has slid. Both have to come out of the
 * <em>same</em> quantity or the strip is not a strip -- the content has to advance by exactly one
 * at the instant the sub-cell offset wraps, and any pair of numbers derived independently will
 * miss that instant on some frame and change a sprite halfway through its travel.
 *
 * <p>The obvious way to get that property is to divide the wall clock by a cell period: the
 * quotient is the content index and the remainder is the offset, so they agree by construction.
 * That is what the HUD used to do, and it is correct exactly as long as the period never changes.
 * It does change: the celebration turns the drums at better than twice the ordinary speed. On the
 * frame the period went from 150ms to 65ms the quotient of a wall clock in the trillions jumped by
 * some thousands of cells and the remainder became an unrelated fraction of a different period, so
 * the strip teleported -- new content, new offset, one frame. Under a gold wash that reads as the
 * machine stopping and starting again, which is the whole complaint this class answers.
 *
 * <h2>What it computes instead</h2>
 *
 * <p>Distance rather than position: the number of cells that have gone past since the roll began,
 * integrated over a rate that is piecewise constant. The ordinary rate applies from the roll's
 * start until act two opens and the fast rate from there on, so the answer is
 *
 * <pre>
 *   cells(t) = (min(t, actTwoStart) - rollStart) / ordinaryCellMillis
 *            + max(0, t - actTwoStart)          / jackpotCellMillis
 * </pre>
 *
 * <p>which is continuous at {@code actTwoStart} -- both branches read zero for the second term
 * there -- and strictly increasing everywhere. Its floor is the content index and its fractional
 * part is the offset, so the wrap property above is preserved, and the speed change is now a
 * change of <em>slope</em> rather than a change of value. Nothing snaps.
 *
 * <p>It is also anchored on the roll's own start rather than on the epoch, so every roll begins
 * its drums at cell zero. That is worth having on its own: which symbols a short spin shows used
 * to depend on what the wall clock happened to read, so a strip entry could be missing from one
 * roll and present in the next for no reason a player could see.
 *
 * <h2>Cost</h2>
 *
 * <p>Two long subtractions, two divisions and an add, with no allocation and no boxing. It is
 * called once per frame and then offset per column, on the render thread.
 */
public final class ReelScroll {

    /**
     * The {@code actTwoStart} to pass for a roll that has no celebration coming: a value no clock
     * reading can reach, so the fast branch is unreachable and the ordinary rate holds for the
     * whole roll.
     */
    public static final long NEVER = Long.MAX_VALUE;

    private ReelScroll() {
    }

    /**
     * Cells travelled by {@code now}.
     *
     * @param now                a reading of the roll's clock
     * @param rollStart          the instant the roll began, from {@link SlotRoll#rollStartAt(long)}
     * @param actTwoStart        the instant the celebration begins, from
     *                           {@link SlotRoll#jackpotActStartAt(long)}, or {@link #NEVER}
     * @param ordinaryCellMillis how long one cell takes to pass before act two; values below 1 are
     *                           read as 1, because a zero period is an infinite speed
     * @param jackpotCellMillis  the same for act two, likewise floored at 1
     * @return a non-negative, non-decreasing count of cells; {@code 0.0} at the roll's start
     */
    public static double cellsTravelled(long now, long rollStart, long actTwoStart,
                                        long ordinaryCellMillis, long jackpotCellMillis) {
        long ordinary = Math.max(1L, ordinaryCellMillis);
        long fast = Math.max(1L, jackpotCellMillis);
        // A celebration cannot begin before its own roll did. Clamping here rather than trusting
        // the caller keeps the two terms from overlapping and double-counting the same interval.
        long actTwo = Math.max(actTwoStart, rollStart);
        double cells = elapsed(rollStart, Math.min(now, actTwo)) / ordinary;
        if (now > actTwo) {
            cells += elapsed(actTwo, now) / fast;
        }
        return cells;
    }

    /**
     * {@code to - from} as a non-negative double.
     *
     * <p>Computed in {@code long} for the ordinary case, where it is exact, and re-done in
     * {@code double} only when that subtraction wrapped -- which needs a span of more than
     * {@link Long#MAX_VALUE} milliseconds and so can only come from a hostile config, but a
     * wrapped span here would hand back a negative distance and run the drums backwards.
     */
    private static double elapsed(long from, long to) {
        if (to <= from) {
            return 0.0d;
        }
        long span = to - from;
        return span < 0L ? (double) to - (double) from : (double) span;
    }
}
