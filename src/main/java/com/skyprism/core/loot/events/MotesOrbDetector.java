package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Motes orb picked up in The Rift.
 *
 * <h2>Default policy: NEVER, and the constant exists mainly as a warning</h2>
 * <p>{@code ORB!} looks like a rare banner. It is not one. It is the Rift's routine currency
 * pickup -- the direct equivalent of walking over a pile of coins -- and orbs drop from very nearly
 * everything there, so an armed detector would spin the reels continuously for the length of a Rift
 * session. Enumerating it explicitly, disabled, is what stops somebody adding it later on the
 * strength of the exclamation mark.
 *
 * <p>The gate is the Rift itself, which is a mode flag rather than a string comparison, so this
 * detector is shut and costs nothing everywhere else in the game.
 */
public final class MotesOrbDetector extends RegistryDetector {

    /**
     * The orb pickup.
     *
     * <p>Verbatim from SkyHanni's repo constants: {@code \u00A75\u00A7lORB! \u00A7r\u00A7dPicked up
     * \u00A7r\u00A75\+.* Motes\u00A7r\u00A7d.*}
     */
    private static final Pattern ORB = Pattern.compile(
            "\u00A75\u00A7lORB! \u00A7r\u00A7dPicked up \u00A7r\u00A75\\+(?<amount>[\\d,.]+) Motes\u00A7r\u00A7d.*");

    public MotesOrbDetector() {
        super(LootSource.RIFT_MOTES_ORB);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || !ORB.matcher(rawLine).matches()) {
            return Optional.empty();
        }
        return Optional.of(event("Motes Orb", nowMillis));
    }
}
