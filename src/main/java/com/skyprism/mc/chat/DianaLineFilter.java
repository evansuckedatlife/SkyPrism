package com.skyprism.mc.chat;

import java.util.List;

/**
 * The cheap substring reject that keeps the core's anchored Diana regexes off the chat
 * thread.
 *
 * <h2>Why this is worth doing</h2>
 *
 * <p>Hypixel is a chatty server and {@code ALLOW_GAME} fires for every system message it
 * sends. Running {@code DianaPatterns}' four anchored expressions -- plus
 * {@code LootParser}'s three -- on all of that, after paying for a full component walk to
 * rebuild the legacy string each regex needs, would be a measurable per-line cost for a
 * feature that fires a handful of times per Diana run. Four {@link String#contains(CharSequence)}
 * calls on the already-available plain text are close to free by comparison, and a false
 * positive costs only the work that was going to happen anyway.</p>
 *
 * <h2>Why it is its own class</h2>
 *
 * <p>This list is <b>a contract with {@code com.skyprism.core.diana}, not a private
 * optimisation.</b> A pattern added to the core whose text shares none of these substrings
 * will never be offered a line, the feature will simply not fire, and nothing anywhere will
 * say so -- no exception, no log line, no failing assertion. That is the worst shape a bug
 * can take.</p>
 *
 * <p>So the predicate lives here, in a class with <b>no Minecraft imports</b> and no static
 * state to initialise, rather than as a private helper inside {@link ChatRouter} (whose
 * class-init reaches {@code ConfigManager}, {@code Metrics} and the Diana controller). That
 * makes it loadable and callable from a plain JVM, which is what lets
 * {@code DianaMarkerContractMcTest} enumerate every pattern {@code DianaPatterns} exposes --
 * by reflection, so a newly added one is picked up without anybody remembering to -- feed a
 * known matching line for each through this exact method, and fail loudly if any of them is
 * rejected. Adding a pattern to the core with no marker here now breaks a test instead of
 * quietly deleting a feature.</p>
 *
 * <p>{@link ChatRouter#mightMatterToDiana(String)} delegates here rather than keeping a copy,
 * so there is exactly one list to keep correct.</p>
 */
public final class DianaLineFilter {

    /**
     * The keywords that make a chat line worth reconstructing and parsing.
     *
     * <p>Every pattern the core matches contains at least one of these as plain,
     * uninterrupted text -- uninterrupted matters, because the test runs against the raw
     * section-coded form as well as the plain one, and a marker with a colour code spliced
     * through the middle of it would be found in neither:</p>
     *
     * <ul>
     *   <li>{@code "dug out"} -- {@code DianaPatterns.SPAWN}, {@code TREASURE_DUG}, the
     *       ordinary half of {@code BURROW_DUG}, and both treasure shapes in
     *       {@code LootParser}.</li>
     *   <li>{@code "DROP!"} -- every banner in {@code LootParser.BANNER_DROP}: RARE, VERY
     *       RARE, CRAZY RARE, INSANE and PET. Note the exclamation mark is part of the
     *       marker, which is what keeps it off a player typing "rare drop" in all-chat.</li>
     *   <li>{@code "burrow chain"} -- the chain-finished half of {@code BURROW_DUG}, which is
     *       the one Diana line that never says "dug out".</li>
     *   <li>{@code "has spawned near"} -- {@code DianaPatterns.INQUISITOR_SHARE}.</li>
     * </ul>
     *
     * <p>Case matters and is deliberate: matching case-insensitively would drag in ordinary
     * player chatter for no gain, since Hypixel's own casing is fixed.</p>
     */
    public static final List<String> MARKERS =
            List.of("dug out", "DROP!", "burrow chain", "has spawned near");

    /**
     * The shortest entry in {@link #MARKERS}, so a line too short to hold any of them leaves
     * before the scan.
     *
     * <p>Computed rather than written down. The previous copy of this filter hard-coded it as
     * {@code 5}, which was right only for as long as {@code "DROP!"} stayed the shortest
     * marker; adding a four-character one would have made the guard silently reject every
     * line that contained it and nothing else.</p>
     */
    private static final int SHORTEST_MARKER = shortestMarkerLength();

    private DianaLineFilter() {
    }

    /**
     * Whether this line contains a word that could belong to a Diana pattern.
     *
     * <p>Accepts both the plain text ({@code Component.getString()}) and the raw
     * section-coded form, because callers have one or the other at the point they ask and
     * every marker is code-free in both.
     *
     * <p><b>Over-accepting is fine; under-accepting is a silent feature outage.</b> If a
     * marker ever has to be loosened to catch a line, loosen it -- the only cost is a regex
     * pass on a line that turns out not to match.
     *
     * @param text the line to test, plain or raw; null and anything shorter than the shortest
     *             marker yield false
     * @return true when the line is worth handing to the Diana pipeline
     */
    public static boolean mightMatterToDiana(String text) {
        if (text == null || text.length() < SHORTEST_MARKER) {
            return false;
        }
        // Indexed rather than for-each: MARKERS is a List.of, whose iterator would allocate
        // on a path that runs for every system message the server sends.
        for (int i = 0, n = MARKERS.size(); i < n; i++) {
            if (text.contains(MARKERS.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static int shortestMarkerLength() {
        int shortest = Integer.MAX_VALUE;
        for (String marker : MARKERS) {
            shortest = Math.min(shortest, marker.length());
        }
        return shortest;
    }
}
