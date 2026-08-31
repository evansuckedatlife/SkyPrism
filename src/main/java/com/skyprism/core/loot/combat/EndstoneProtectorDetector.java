package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootSource;

import java.util.Map;

/**
 * The Endstone Protector going down in The End.
 *
 * <p><b>Default policy: ALWAYS.</b> Rarer than the dragons and it takes a hundred community zealot
 * kills to summon, so the moment it lands is unambiguously earned and cannot recur at a rate anyone
 * could find annoying.
 *
 * <p>Subject to exactly the same loot caveat as {@link EnderDragonDetector}: the Golem's drops are
 * armour stands, not chat, so this is a caption-only roll until something reads the world. Stated
 * rather than papered over, for the reason that class documents.
 *
 * <p>Evidence: SkyHanni {@code features/combat/end/DragonFeatures.kt}, which handles the Protector
 * on the same code path as the dragons and pairs the banner with a
 * "Zealots Contributed: n/100" line.
 */
public final class EndstoneProtectorDetector extends BossDownDetector {

    public EndstoneProtectorDetector() {
        super(LootSource.ENDSTONE_PROTECTOR, Map.of("ENDSTONE PROTECTOR", "Endstone Protector"));
    }
}
