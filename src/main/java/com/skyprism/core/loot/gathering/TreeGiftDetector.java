package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * A Galatea tree gift, and the bonus sub-gift inside it.
 *
 * <p>The user named tree gifts explicitly, and they turn out to be two events wearing one block, so
 * they are two sources with opposite defaults.
 *
 * <h2>Tree Gift: ON_JACKPOT_ITEM_ONLY</h2>
 * <p>A gift arrives for every tree the player contributed at least ten percent to, which during a
 * foraging session is one every thirty to ninety seconds -- the foraging equivalent of spinning on
 * every fish. Its base contents are guaranteed filler: Forest Essence, logs, Foraging XP, Forest
 * Whispers, HOTF XP. Arming it would drown the widget, so the celebration is left to the bonus.
 *
 * <h2>Tree Bonus Gift: ALWAYS</h2>
 * <p>The BONUS GIFT sub-header <b>only prints when a bonus actually rolled</b>, which means Hypixel
 * has already done the filtering: the printed odds on the reward lines run from 20% down to 0.02%,
 * and a forager sees the sub-block a few times an hour. That is squarely in the band Diana already
 * ships in.
 *
 * <h2>Why both fire on a header line</h2>
 * <p>The rest of the block -- the separator, the contribution line naming the tree, the "+N rewards
 * gained!" summary and the bonus reward lines with their odds -- carries no registry chat marker,
 * so the bus does not offer those lines to these detectors unless something else has switched the
 * filter off entirely. A block reader built on lines it may never be shown is a feature that works
 * in its unit test and silently never fires, which is the exact failure this design exists to
 * avoid. So each detector fires on the one line it is guaranteed to see, and the parsers for the
 * rest live in {@link TreeGiftLines}, tested and ready for whatever gains access to the body.
 *
 * <p>The visible cost is the caption. The tree type ("Fig", "Mangrove", "Helix") is printed on the
 * contribution line, which arrives <em>after</em> the header, so even an unfiltered bus could not
 * put it in the caption of an event that has already fired. The caption is therefore the source's
 * own display name, which is honest, rather than the type of the previous tree, which would be a
 * plausible-looking lie.
 */
public final class TreeGiftDetector extends RegistryDetector {

    private final Predicate<String> isHeader;

    private TreeGiftDetector(LootSource source, Predicate<String> isHeader) {
        super(source);
        this.isHeader = isHeader;
    }

    /** The ordinary gift block. Ships on ON_JACKPOT_ITEM_ONLY; see the class notes. */
    public static TreeGiftDetector gift() {
        return new TreeGiftDetector(LootSource.FORAGING_TREE_GIFT, TreeGiftLines::isGiftHeader);
    }

    /** The bonus sub-block, which only prints when a bonus rolled. Ships armed. */
    public static TreeGiftDetector bonus() {
        return new TreeGiftDetector(LootSource.FORAGING_TREE_BONUS_GIFT,
                TreeGiftLines::isBonusGiftHeader);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        // Both headers end in "GIFT"; the two patterns are mutually exclusive on the word before
        // it, so a BONUS GIFT line can never be claimed by the gift detector or the reverse.
        return isHeader.test(rawLine) ? Optional.of(event(nowMillis)) : Optional.empty();
    }
}
