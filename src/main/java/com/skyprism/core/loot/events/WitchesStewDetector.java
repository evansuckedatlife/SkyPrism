package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;

/**
 * The Year of the Witch stew, claimed from its own inventory.
 *
 * <h2>Purely GUI driven, and shipped off</h2>
 * <p>This source has no chat line at all: the only verified signals are the inventory title
 * {@code Witches Stew} and the {@code <item> x<n>} shape of its slot names. So the detector reads
 * screen titles and nothing else, {@link #readsChat()} is false, and the bus never puts it on the
 * per-line path -- it costs literally nothing per chat message, which is the whole point of
 * declaring a screen-title source honestly instead of pretending it reads chat.
 *
 * <h2>Default policy: NEVER, because the mechanic is unverified</h2>
 * <p>It was not established that the stew is randomised rather than a fixed menu, and a slot machine
 * on a fixed menu is celebrating a purchase. That is a research gap, not a pacing decision, so the
 * fix is a live capture rather than a different default. Shipping it armed on an unverified mechanic
 * would be the same class of mistake as inventing a regex.
 *
 * <p><b>Unverified:</b> whether the stew's contents are chance based at all.
 */
public final class WitchesStewDetector extends RegistryDetector {

    /** The inventory title, verbatim from SkyHanni's repo constants. */
    private static final String TITLE = "Witches Stew";

    public WitchesStewDetector() {
        super(LootSource.YEAR_OF_THE_WITCH_STEW);
    }

    /** Never: this source has no chat line. */
    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        return Optional.empty();
    }

    /** False, so the bus keeps this detector and its markers off the per-line path entirely. */
    @Override
    public boolean readsChat() {
        return false;
    }

    @Override
    public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        if (title == null) {
            return Optional.empty();
        }
        return TITLE.equals(TextClean.clean(title))
                ? Optional.of(event(TITLE, nowMillis))
                : Optional.empty();
    }
}
