package com.skyprism.core.diana;

/**
 * One "You dug out a Griffin Burrow! (2/4)" event.
 *
 * <p>Hypixel chains burrows: digging one points you at the next, and the dig that closes
 * the chain says "You finished the Griffin burrow chain!" instead. Both forms carry the
 * same {@code (current/max)} counter, so the wording is the only thing that separates them.
 *
 * <p><b>Why {@code chainFinished} is stored rather than derived.</b> It is tempting to
 * compute it as {@code current == max} and drop the field. That is wrong in both
 * directions on the live server: a chain interrupted by a warp, a death or a server hop
 * leaves an ordinary dig sitting at {@code 4/4}, and the finished line is the one that
 * actually tells a HUD to celebrate and reset. Keeping the server's own answer means a
 * future change to chain length costs nothing here.
 *
 * @param chainFinished true when this dig closed the chain rather than continuing it
 * @param current       1-based index of this burrow within the chain
 * @param max           chain length Hypixel announced, normally 4
 */
public record BurrowDig(boolean chainFinished, int current, int max) {

    public BurrowDig {
        if (current < 0) {
            throw new IllegalArgumentException("current must be >= 0 but was " + current);
        }
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0 but was " + max);
        }
    }
}
