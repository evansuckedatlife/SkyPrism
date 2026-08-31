package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The seven Ender Dragon types going down in the Dragon's Nest.
 *
 * <p><b>Default policy: ALWAYS.</b> A dragon lands every five to fifteen minutes on a busy End
 * lobby, far less often if the player is placing their own eyes, and the fight is communal and
 * anticipated -- squarely inside the thirty-second-to-ten-minute band Diana already ships in and
 * that the player already enjoys.
 *
 * <p><b>Why it is not ON_RARE_BANNER, which matters more than the choice of ALWAYS.</b> Dragon loot
 * spawns as floating armour stands and is <em>never announced in chat</em> -- SkyHanni reconstructs
 * it by scanning armour-stand names and back-computing from the weight formula. So a policy keyed on
 * a rare banner would be a detector that silently never fires, indistinguishable from a working
 * feature, which is the single failure mode this whole design exists to avoid. The registry enforces
 * that as an invariant: this source is not declared as emitting a rare banner, so the policy is not
 * even expressible.
 *
 * <p><b>The honest consequence:</b> a roll fired here has nothing to lock its reels onto unless
 * something reads the world in the few seconds after the banner. Until that exists this is a
 * caption-only roll -- "Superior Dragon", reels landing on No Drop -- which is the truthful
 * behaviour rather than a pretend one.
 *
 * <p>Names verified: PROTECTOR, OLD, UNSTABLE, YOUNG, STRONG, WISE, SUPERIOR. Evidence: SkyHanni
 * {@code features/combat/end/DragonFightAPI.kt} and {@code DragonType.kt}.
 */
public final class EnderDragonDetector extends BossDownDetector {

    private static final Map<String, String> DRAGONS = dragons();

    public EnderDragonDetector() {
        super(LootSource.ENDER_DRAGON, DRAGONS);
    }

    private static Map<String, String> dragons() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("PROTECTOR DRAGON", "Protector Dragon");
        map.put("OLD DRAGON", "Old Dragon");
        map.put("UNSTABLE DRAGON", "Unstable Dragon");
        map.put("YOUNG DRAGON", "Young Dragon");
        map.put("STRONG DRAGON", "Strong Dragon");
        map.put("WISE DRAGON", "Wise Dragon");
        map.put("SUPERIOR DRAGON", "Superior Dragon");
        return map;
    }
}
