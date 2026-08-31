package com.skyprism.core.loot.events;

import com.skyprism.core.diana.DianaPatterns;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;

/**
 * A Mythological <b>treasure</b> burrow paying out.
 *
 * <h2>The gap this fills, and the path it must not touch</h2>
 * <p>A Griffin burrow is one of two things. Dig a <em>mob</em> burrow and a mythological creature
 * climbs out; the shipped Diana feature binds it and spins the machine when it is defeated, which is
 * an entity event with no chat line. Dig a <em>treasure</em> burrow and there is no creature at all
 * -- the reward arrives straight away as {@code RARE DROP! You dug out a Griffin Feather} or
 * {@code Wow! You dug out 2,500 coins}. Those burrows never spin the machine today, because there is
 * nothing to defeat. That is the whole and only reason this detector exists.
 *
 * <p>It therefore matches <b>only</b> the treasure payout, and it does so by delegating to
 * {@link DianaPatterns#isTreasureDig(String)} rather than by copying the pattern. That is a
 * deliberate choice with one purpose: there is then exactly one definition of what a Diana treasure
 * line is, it is the live-verified one, and this class cannot drift away from it or change what it
 * means. Nothing in {@code com.skyprism.core.diana} is modified, read-only reuse being the strongest
 * available guarantee that the shipped path is untouched.
 *
 * <h2>Why it is not registered by default</h2>
 * <p>{@link com.skyprism.core.diana.DianaLootSource} already speaks for
 * {@link LootSource#DIANA_MYTHOLOGICAL}, and the bus rejects two detectors for one source -- rightly,
 * since two would double-roll. The two are alternatives, not companions: {@code DianaLootSource} is
 * the entity-driven creature kill, this is the chat-driven treasure payout, and a wiring that wants
 * both behaviours must run the creature path through the shipped controller (where it already is)
 * and register only this one on the general bus. {@link EventDetectors#registerAll} therefore leaves
 * it out and {@link EventDetectors#registerBurrowTreasure} adds it explicitly, so the choice is
 * always made on purpose and never by accident.
 *
 * <h2>Default policy: ALWAYS, inherited unchanged</h2>
 * <p>A treasure burrow is the same event shape and the same cadence as the creature burrow the
 * player already enjoys at {@code ALWAYS}, so anything else here would be inconsistent.
 *
 * <h2>The caption is the burrow, not the item</h2>
 * <p>{@code LootEvent.subject} is what <em>produced</em> the payout, and what produced it is a
 * treasure burrow. The item belongs on the reels, which the shared drop parser already fills; naming
 * the item here would put the same string in two places and let them disagree.
 */
public final class BurrowTreasureDetector extends RegistryDetector {

    /** The caption: the burrow, not what came out of it. */
    private static final String SUBJECT = "Treasure Burrow";

    public BurrowTreasureDetector() {
        super(LootSource.DIANA_MYTHOLOGICAL);
    }

    /**
     * Matches a treasure payout and nothing else.
     *
     * <p>In particular it must never match the creature spawn line, which also contains "You dug
     * out": that line means a mob is now alive and the shipped Diana path owns the roll for it, so
     * matching it here would start a roll for a creature that has not been fought yet -- a visible
     * regression on the one path that must not regress. The shipped treasure pattern is anchored on
     * the {@code RARE DROP!}/{@code Wow!} banner while the spawn line is anchored on one of seven
     * exclamations, so the two cannot collide; the point is tested rather than assumed.
     */
    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (!DianaPatterns.isTreasureDig(rawLine)) {
            return Optional.empty();
        }
        return Optional.of(event(SUBJECT, nowMillis));
    }
}
