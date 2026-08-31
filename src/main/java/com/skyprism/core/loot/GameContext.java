package com.skyprism.core.loot;

import java.util.Locale;
import java.util.Objects;

/**
 * The coarse, slow-moving facts a {@link SourceDetector} gates on.
 *
 * <p>Every field here changes on the order of minutes: you warp to an island, you walk into an area,
 * a mayor is elected, you enter a dungeon. Nothing in it is per-tick and nothing in it is per-frame.
 * That is the whole point -- {@link LootEventBus#updateContext(GameContext)} is called when one of
 * these actually changes (and at worst on a multi-second poll), the bus recomputes which detectors
 * are open exactly once, and from then until the next change a chat line costs a field read and at
 * most a handful of {@code indexOf} calls. A gate that had to be asked per line, or per tick, would
 * defeat the design however cheap each individual answer was.
 *
 * <h2>Normalisation</h2>
 * <p>The three string fields are normalised on construction: null becomes {@code ""}, formatting
 * codes are stripped, and surrounding whitespace is trimmed. Comparisons through {@link
 * #isIsland(String)} and friends are case-insensitive. This matters because the strings come off a
 * sidebar and a tab list, where Hypixel is inconsistent about colour codes and leading spaces, and a
 * gate that fails on {@code "\u00a77Crystal Hollows"} versus {@code "Crystal Hollows"} is a feature
 * that silently never runs on half the accounts.
 *
 * <p>{@code ""} means "unknown", not "nowhere". A gate that requires a specific island is shut while
 * the island is unknown, which is the safe direction: the machine stays quiet until we know where we
 * are, rather than firing on a stale guess.
 *
 * @param onHypixel whether the client is connected to Hypixel at all
 * @param inSkyBlock whether the player is in SkyBlock rather than another Hypixel game
 * @param island the SkyBlock island, e.g. {@code "Crystal Hollows"}, {@code "The Rift"}; may be empty
 * @param area the finer graph area within the island, e.g. {@code "The Mist"}, {@code "Jungle Temple"}
 * @param mayor the current SkyBlock mayor, e.g. {@code "Diana"}; may be empty
 * @param inDungeon whether the player is inside a Catacombs run
 * @param inRift whether the player is inside The Rift
 */
public record GameContext(boolean onHypixel, boolean inSkyBlock, String island, String area,
                          String mayor, boolean inDungeon, boolean inRift) {

    /** Nothing known: every gate that requires anything is shut. */
    public static final GameContext UNKNOWN = new GameContext(false, false, "", "", "", false, false);

    public GameContext {
        island = normalise(island);
        area = normalise(area);
        mayor = normalise(mayor);
    }

    /** A context that is on Hypixel and in SkyBlock on {@code island}, with nothing else known. */
    public static GameContext onIsland(String island) {
        return new GameContext(true, true, island, "", "", false, false);
    }

    /** As {@link #onIsland(String)}, additionally inside {@code area}. */
    public static GameContext onIsland(String island, String area) {
        return new GameContext(true, true, island, area, "", false, false);
    }

    /** True when the player is on Hypixel <em>and</em> in SkyBlock, which every gate requires. */
    public boolean inGame() {
        return onHypixel && inSkyBlock;
    }

    /** Case-insensitive island test; false when the island is unknown. */
    public boolean isIsland(String candidate) {
        return matches(island, candidate);
    }

    /** Case-insensitive area test; false when the area is unknown. */
    public boolean isArea(String candidate) {
        return matches(area, candidate);
    }

    /** Case-insensitive mayor test; false when the mayor is unknown. */
    public boolean isMayor(String candidate) {
        return matches(mayor, candidate);
    }

    private static boolean matches(String actual, String candidate) {
        return !actual.isEmpty() && candidate != null && actual.equalsIgnoreCase(candidate.trim());
    }

    /**
     * Strips legacy formatting codes and collapses surrounding whitespace.
     *
     * <p>Deliberately hand-rolled rather than a regex: this runs on context updates, which are rare,
     * but it is also the kind of helper that gets called from a poll someone later moves onto a
     * tick, and a compiled-once regex plus a {@code Matcher} allocation is a worse default to leave
     * lying around than a single pass over a short string.
     */
    private static String normalise(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7') {
                i++; // skip the code character too; a trailing lone section sign just ends the loop
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /** Lower-cased island, for use as a map key. */
    public String islandKey() {
        return island.toLowerCase(Locale.ROOT);
    }

    /** Whether {@code other} would change any gate's answer. Cheap identity fast path first. */
    public boolean differsFrom(GameContext other) {
        return !Objects.equals(this, other);
    }
}
