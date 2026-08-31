package com.skyprism.core.loot.combat;

import java.util.Locale;
import java.util.Optional;

/**
 * One slayer quest as the sidebar describes it: a boss and a tier, e.g. "Voidgloom Seraph IV".
 *
 * <p>This is the only place the six bosses and the five tiers appear, and it exists for two
 * reasons. It gives {@link SlayerBossDetector} a caption -- a widget reading "Voidgloom Seraph IV"
 * where it could have read "Slayer Boss" has thrown away the part the player cares about. And it
 * gives the per-slayer <em>minimum tier floor</em> something to compare against, which is the right
 * knob for the one population a per-boss roll could annoy: a T1 Revenant farmer clearing a boss
 * every thirty seconds. Downgrading the whole source's policy to fix that population would gut it
 * for everyone else, because the entire point of a slayer run is the moment of truth on a boss that
 * usually drops nothing.
 *
 * @param type the boss, or null when the sidebar named one this build does not know
 * @param tier 1 to 5, or {@link #UNKNOWN_TIER} when the numeral was missing or unrecognised
 * @param displayName the sidebar string as given, e.g. "Voidgloom Seraph IV"
 */
public record SlayerQuest(SlayerBossType type, int tier, String displayName) {

    /** No tier could be read. Compares below every real floor, so a floor never silences a roll. */
    public static final int UNKNOWN_TIER = 0;

    /** The five Roman numerals Hypixel uses for slayer tiers, indexed by tier. Index 0 is unused. */
    private static final String[] NUMERALS = {"", "I", "II", "III", "IV", "V"};

    public SlayerQuest {
        displayName = displayName == null ? "" : displayName.trim();
    }

    /**
     * Reads a sidebar quest line, e.g. "Voidgloom Seraph IV".
     *
     * <p>Deliberately forgiving in one direction only: a name this build does not know, or a missing
     * numeral, yields a quest with a null type or an unknown tier rather than empty. Being unable to
     * name the boss must never mean being unable to notice it died -- that would turn a cosmetic gap
     * into a silently missing feature, which is the trade this whole design refuses to make.
     *
     * @param sidebarLine the quest name as the sidebar shows it; null or blank yields empty
     */
    public static Optional<SlayerQuest> parse(String sidebarLine) {
        if (sidebarLine == null) {
            return Optional.empty();
        }
        String trimmed = sidebarLine.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace > 0) {
            int tier = tierOf(trimmed.substring(lastSpace + 1));
            if (tier != UNKNOWN_TIER) {
                String name = trimmed.substring(0, lastSpace);
                return Optional.of(new SlayerQuest(
                        SlayerBossType.byDisplayName(name).orElse(null), tier, trimmed));
            }
        }
        return Optional.of(new SlayerQuest(
                SlayerBossType.byDisplayName(trimmed).orElse(null), UNKNOWN_TIER, trimmed));
    }

    /** Whether this quest is at or above {@code floor}; an unknown tier always passes. */
    public boolean atLeastTier(int floor) {
        return tier == UNKNOWN_TIER || tier >= floor;
    }

    /** The caption, e.g. "Voidgloom Seraph IV". Never blank for a quest that parsed. */
    public String caption() {
        return displayName;
    }

    private static int tierOf(String numeral) {
        String key = numeral.toUpperCase(Locale.ROOT);
        for (int tier = 1; tier < NUMERALS.length; tier++) {
            if (NUMERALS[tier].equals(key)) {
                return tier;
            }
        }
        return UNKNOWN_TIER;
    }
}
