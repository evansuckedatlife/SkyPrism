package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;

/**
 * A Crystal Hollows treasure chest, uncovered while mining and then lockpicked.
 *
 * <h2>It fires on the header, and that is the design rather than a shortcut</h2>
 * <p>Hypixel prints the payout as a block: a rule, the {@code CHEST LOCKPICKED} header, a {@code
 * REWARDS} sub-header, one indented line per drop, the rule again. This detector fires on the header
 * and never reads the item lines. Two reasons, and the second is the better one.
 *
 * <p>The bus only offers a detector lines containing one of its declared markers, and the markers
 * come from the registry -- here, {@code "CHEST LOCKPICKED"}. A block's item lines contain no such
 * literal, so they are never routed here, and widening the markers to catch them would widen the
 * filter for every line of chat the client receives. {@link RewardBlock} exists for whoever owns the
 * unfiltered loot path.
 *
 * <p>The better reason: a slot machine should start spinning when the chest opens, not after the
 * loot is on screen. Firing on the header means the reels are already moving while the payout is
 * still unknown and land on the drops as they arrive, which is exactly what the shipped Diana path
 * does. Waiting for the block to close would turn the animation into a re-reveal of something the
 * player has already read.
 *
 * <h2>Shipped policy, and the one thing that has to be got right about it</h2>
 * <p>{@link com.skyprism.core.loot.RollPolicy#ON_JACKPOT_ITEM_ONLY}. A powder grinder opens thirty
 * to a hundred of these an hour, sometimes several a minute, so {@code ALWAYS} is unusable -- and
 * there is <b>no rare banner anywhere in the block</b>, so {@code ON_RARE_BANNER} would be a source
 * that silently never rolls. The block does give clean, exact item names, which is what makes the
 * registry's jackpot list both easy to write and correct.
 *
 * <p><b>Consequence for whoever wires the policy:</b> because this detector fires before any item is
 * known, {@code sawJackpotItem} cannot be answered at trigger time. The jackpot test for this source
 * has to be applied to the drops as they land inside the block, not to the event. That is a property
 * of the source, not a defect in the detector -- but a policy layer that evaluates {@code
 * ON_JACKPOT_ITEM_ONLY} against the event alone will conclude "no jackpot" every single time and
 * this source will never roll.
 */
public final class PowderChestDetector extends RegistryDetector {

    private static final String MARKER = "CHEST LOCKPICKED";

    public PowderChestDetector() {
        super(LootSource.POWDER_CHEST);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf(MARKER) < 0) {
            return Optional.empty();
        }
        if (!ContainerPatterns.CHEST_LOCKPICKED.matcher(rawLine).matches()) {
            return Optional.empty();
        }
        return Optional.of(event("Treasure Chest", nowMillis));
    }
}
