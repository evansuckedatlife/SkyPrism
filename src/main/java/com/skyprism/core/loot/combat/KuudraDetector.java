package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootSource;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kuudra going down, captioned with the tier the run was.
 *
 * <p><b>Default policy: ALWAYS.</b> A Kuudra run is two to five minutes of committed group play and
 * it costs a key to enter. That is the definition of an event worth a roll, and there is no
 * frequency risk at all: even a fast T5 team clears twelve an hour.
 *
 * <p><b>Where the loot is, and why this source does not chase it.</b> Kuudra announces nothing per
 * drop -- it pays out afterwards through the Free Chest and Paid Chest GUIs, which
 * {@link LootSource#KUUDRA_REWARD_CHEST} owns. This source rolls on the banner and captions the
 * tier; the chest source owns the contents. Both firing would mean two rolls per run, which is the
 * same double-fire trap {@link LootSource#DUNGEON_BOSS} and
 * {@link LootSource#DUNGEON_RUN_COMPLETE} are separated to avoid.
 *
 * <h2>The tier is not in the line</h2>
 * <p>The banner says only "KUUDRA DOWN!". The tier comes from the sidebar --
 * "{@code \u00A77\u2312 \u00A7cKuudra's Hollow \u00A78(T5)}" -- which reaches this detector through
 * {@link ContextAwareDetector#context()}'s area, already stripped of formatting. When the sidebar
 * has not been read the caption falls back to the bare source name, which is honest; it never
 * guesses a tier.
 *
 * <p>Evidence: SkyHanni {@code features/nether/kuudra/KuudraApi.kt} for both the banner and the
 * sidebar tier line, and {@code KuudraTier.kt} for the five tier names in order.
 */
public final class KuudraDetector extends BossDownDetector {

    /** The five tiers, indexed by the digit the sidebar prints. Index 0 is unused. */
    private static final String[] TIER_NAMES = {
            "", "Basic", "Hot", "Burning", "Fiery", "Infernal"};

    /**
     * The tier suffix on the sidebar area, e.g. {@code (T5)}.
     *
     * <p>Matched with {@code find} rather than {@code matches} deliberately, and safely: this runs
     * against {@link com.skyprism.core.loot.GameContext#area()}, which is a sidebar string the
     * server controls and no player can type into, not against a chat line. It also runs at most
     * once per Kuudra clear.
     */
    private static final Pattern SIDEBAR_TIER = Pattern.compile("\\(T(?<tier>[1-5])\\)");

    public KuudraDetector() {
        super(LootSource.KUUDRA_COMPLETE, Map.of("KUUDRA", "Kuudra"));
    }

    @Override
    protected String caption(String tableCaption) {
        String tier = tierName();
        return tier.isEmpty() ? tableCaption : tier + " " + tableCaption;
    }

    /** The tier read off the sidebar area, e.g. "Infernal"; empty when it is not known. */
    public String tierName() {
        String area = context().area();
        if (area.isEmpty()) {
            return "";
        }
        Matcher matcher = SIDEBAR_TIER.matcher(area);
        if (!matcher.find()) {
            return "";
        }
        return TIER_NAMES[matcher.group("tier").charAt(0) - '0'];
    }
}
