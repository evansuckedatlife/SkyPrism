package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootSource;

import java.util.Map;

/**
 * Arachne going down in the Spider's Den.
 *
 * <p><b>Default policy: ALWAYS.</b> Arachne has to be summoned with crystals, the fight is communal
 * and infrequent, and unlike the dragons it does announce its drops -- "RARE DROP! Arachne's Keeper
 * Fragment" and the Top of Nest travel scroll both arrive on the ordinary banner -- so the reels
 * have something real to land on.
 *
 * <p>Evidence: SkyHanni {@code features/combat/mobs/ArachneKillTimer.kt} and
 * {@code features/chat/ChatFilter.kt}, which carry both the defeat banner and the two drop lines.
 */
public final class ArachneDetector extends BossDownDetector {

    public ArachneDetector() {
        super(LootSource.ARACHNE, Map.of("ARACHNE", "Arachne"));
    }
}
