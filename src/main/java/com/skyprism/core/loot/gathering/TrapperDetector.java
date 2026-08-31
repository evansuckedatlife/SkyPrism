package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;

/**
 * A hunt for Trevor the Trapper on the Farming Islands.
 *
 * <h2>Why the trigger is the drop and not the kill</h2>
 * <p>This source ships on ON_RARE_BANNER, and that decides the shape of the whole detector. A hunt
 * completes every one to three minutes and the reward is usually mundane, so ALWAYS on the kill
 * would be too frequent; what makes a hunt worth celebrating is the rarity tier of what it dropped,
 * and Hypixel encodes exactly that in the drop banner.
 *
 * <p>Which means the trigger has to <b>be</b> the banner. Firing on the completion line instead
 * would produce an event carrying no rarity flag at all, and ON_RARE_BANNER would then refuse every
 * one of them: a detector that fires and never spins, which is the same silent failure as one that
 * never fires. So the completion line is read and remembered, and the banner is what emits.
 *
 * <h2>What the completion line is for</h2>
 * <p>{@link #lastHuntCompletedAtMillis()} records when Hypixel last said "Return to the Trapper
 * soon", which is the only trapper line reachable through this source's markers. It deliberately
 * does <em>not</em> gate the emit. The ordering between an animal's drop banner and its completion
 * line is not verified in either reference mod, so requiring a recent completion could silently
 * drop every trapper jackpot if the banner turns out to come first. Recording it costs a field and
 * leaves the tightening available to whoever can confirm the order in play.
 *
 * <h2>Registration order matters for this one</h2>
 * <p>A trapper drop is also a rare mob drop, and the bus stops at the first source that claims a
 * line. On the Farming Islands this detector should therefore be registered <em>before</em> the
 * general MOB_RARE_DROP detector, so the drop is captioned as the hunt that produced it rather than
 * as an anonymous mob. The cost of that choice is stated plainly: any other rare drop earned on the
 * Farming Islands while this source is armed will also be captioned as the trapper's.
 *
 * <p>Lines verbatim from SkyHanni TrevorFeatures.kt (misc.trevor.mob.died.colorless) and
 * TrevorTracker.kt. The assignment line, which carries the animal's rarity tier and would make a
 * far better caption -- "[NPC] Trevor: You can find your ELUSIVE animal near the Desert Mountain."
 * -- contains none of this source's chat markers, so the filtered bus never offers it. Narrowing
 * the source to Endangered and Elusive assignments, which the research suggests would make ALWAYS
 * defensible, needs that line and therefore needs a marker change in the registry first.
 */
public final class TrapperDetector extends RegistryDetector {

    /** Captured, colourless: the line Hypixel prints when a hunted animal dies. */
    private static final String HUNT_COMPLETE =
            "Return to the Trapper soon to get a new animal to hunt!";

    /** Captured, colourless: the hunt failing. Recorded for symmetry, never a trigger. */
    static final String HUNT_FAILED = "You ran out of time and the animal disappeared!";

    /** The caption. The animal's own name is never printed in chat; its rarity tier is, elsewhere. */
    private static final String SUBJECT = "Trevor's Animal";

    private long lastHuntCompletedAtMillis = Long.MIN_VALUE;

    public TrapperDetector() {
        super(LootSource.TREVOR_TRAPPER);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        if (rawLine.indexOf("Return to the Trapper soon") >= 0
                && HUNT_COMPLETE.equals(TextClean.clean(rawLine))) {
            lastHuntCompletedAtMillis = nowMillis;
            return Optional.empty();
        }
        return BannerLines.isRareDropBanner(rawLine)
                ? Optional.of(event(SUBJECT, nowMillis))
                : Optional.empty();
    }

    /**
     * When the last hunt completed, or {@link Long#MIN_VALUE} if none has this session.
     *
     * <p>Diagnostics, and the hook for tightening the emit once the ordering is verified; see the
     * class notes for why it is not a condition today.
     */
    public long lastHuntCompletedAtMillis() {
        return lastHuntCompletedAtMillis;
    }
}
