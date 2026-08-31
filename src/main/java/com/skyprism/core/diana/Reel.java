package com.skyprism.core.diana;

/**
 * One column of the slot machine at one instant, as a value the renderer can draw without
 * asking any further questions.
 *
 * <p>Reels are produced fresh on every {@link SlotRoll#reels()} call rather than stored and
 * mutated, so a renderer can hold one for a frame without it changing underneath, and a test
 * can compare two snapshots taken at different clock times.
 *
 * <p><b>Draw off {@code locked}, not off {@code symbol}.</b> The two are not the same question.
 * {@code locked} says whether the column has come to rest, which is what decides between drawing a
 * scrolling strip and drawing a still item. {@code symbol} says what it is showing or heading for,
 * and during the jackpot act it is known before the reel gets there -- the whole point of that act
 * is that the destination was never in doubt -- so an unlocked reel can perfectly well carry one.
 * A renderer that tests {@code symbol == null} to decide "is it still spinning" draws the jackpot
 * re-spin as three motionless items.
 *
 * @param index     column position, 0 for the leftmost reel
 * @param locked    true once this reel has stopped on its symbol. A locked reel never unlocks
 *                  within the first act of a roll; the jackpot act deliberately spins the settled
 *                  reels up again, which is a new act rather than a reel changing its mind.
 * @param symbol    the drop this reel is resting on, or is on its way to. Non-null on every locked
 *                  reel, and also on an unlocked one during {@link RollState#JACKPOT_SPIN} and
 *                  {@link RollState#JACKPOT_LOCK}, where all three columns are already committed to
 *                  {@link SlotRoll#jackpotSymbol()}. Null only during the first act's spin, where
 *                  the symbol genuinely is not decided yet and the renderer has nothing to blur
 *                  past but the generic strip.
 * @param spinPhase 0..1 (never 1) scroll position within the current symbol cell, for the
 *                  renderer's vertical offset. Meaningless once {@code locked} is true, where
 *                  it is pinned to 0 so a locked reel draws perfectly aligned.
 */
public record Reel(int index, boolean locked, LootDrop symbol, double spinPhase) {

    public Reel {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0 but was " + index);
        }
        if (!(spinPhase >= 0.0d) || spinPhase >= 1.0d) {
            throw new IllegalArgumentException("spinPhase must be in [0,1) but was " + spinPhase);
        }
        if (locked && symbol == null) {
            throw new IllegalArgumentException("a locked reel must have a symbol");
        }
    }
}
