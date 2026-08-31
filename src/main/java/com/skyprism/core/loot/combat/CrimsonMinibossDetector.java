package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The five Crimson Isle minibosses going down.
 *
 * <p><b>Default policy: ALWAYS.</b> Each boss has a two-minute respawn floor, so the theoretical
 * worst case is one roll every two minutes and only for a player camping a single spawn -- well
 * inside Diana's cadence, and in ordinary play far less. Their drops arrive on the ordinary rare
 * banner, so the reels have real symbols.
 *
 * <p>The five names are a closed set, which is what makes accepting them from a
 * {@link BossDownBanner} capture safe. Verified: BLADESOUL, MAGE OUTLAW, BARBARIAN DUKE X, ASHFANG,
 * MAGMA BOSS. Evidence: SkyHanni {@code features/nether/CrimsonMinibossRespawnTimer.kt}, with the
 * name list cross-checked against SkyHanni-REPO {@code constants/CrimsonIsleReputation.json}.
 *
 * <p>Note that this source's registry marker is the bare " DOWN!", which is the widest marker in the
 * family: on the Crimson Isle it will be offered every defeat banner in the game. That is correct
 * and it is why the closed table exists -- a dragon banner reaching this detector is declined, as
 * its own test asserts.
 */
public final class CrimsonMinibossDetector extends BossDownDetector {

    private static final Map<String, String> MINIBOSSES = minibosses();

    public CrimsonMinibossDetector() {
        super(LootSource.CRIMSON_MINIBOSS, MINIBOSSES);
    }

    private static Map<String, String> minibosses() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("BLADESOUL", "Bladesoul");
        map.put("MAGE OUTLAW", "Mage Outlaw");
        map.put("BARBARIAN DUKE X", "Barbarian Duke X");
        map.put("ASHFANG", "Ashfang");
        map.put("MAGMA BOSS", "Magma Boss");
        return map;
    }
}
