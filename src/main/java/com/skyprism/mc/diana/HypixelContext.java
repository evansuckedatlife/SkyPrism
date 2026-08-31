package com.skyprism.mc.diana;

import com.skyprism.core.diana.DianaGate;
import com.skyprism.core.util.TextClean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Answers the four questions {@link DianaGate} asks -- are we on Hypixel, are we in SkyBlock, is
 * Diana the mayor, and where are we -- from the running client, without ever costing a frame.
 *
 * <h2>The polling budget, and why it is shaped this way</h2>
 * <p>None of these four facts can change quickly. A server address changes on a reconnect, an island
 * changes on a warp, and a mayor changes once every five real days. So this class deliberately does
 * <em>not</em> run per tick:
 *
 * <ul>
 *   <li>{@link #onJoin} and {@link #onDisconnect} handle the server address on the connection edge,
 *       at zero recurring cost.</li>
 *   <li>{@link #poll} reads the sidebar at most once every {@value #SIDEBAR_INTERVAL_MILLIS} ms,
 *       and not at all when neither feature wants the answer.
 *       That is the SkyBlock check and the area check, both of which come off the same objective, so
 *       they share one pass.</li>
 *   <li>The mayor is re-read at most once every {@value #MAYOR_INTERVAL_MILLIS} ms, because it is the
 *       expensive one: it walks the TAB list, and TAB on Hypixel is eighty entries. Five days of
 *       stability does not deserve more than that.</li>
 * </ul>
 *
 * <p>Between polls {@code poll} returns after a single {@code long} comparison, which is what lets
 * the caller hang it on the end-of-tick event without a second thought.
 *
 * <h2>Where each fact is read from</h2>
 * <p><b>Hypixel</b> is the current server address ending in one of Hypixel's two domains. A dev or
 * test setup has no such address, so {@link #setHypixelOverride(Boolean)} forces the answer; the
 * system property {@code skyprism.forceHypixel} seeds the same override at class-load so the check
 * can be defeated from a launch argument without a config round-trip.
 *
 * <p><b>SkyBlock</b> and <b>area</b> come from the sidebar scoreboard. Hypixel puts almost nothing in
 * the score entry name -- the visible text is the team prefix concatenated with the team suffix -- so
 * the recipe is objective, then entries, then each entry's team. The sidebar title carries
 * "SKYBLOCK" (and "SKIBLOCK" during the April Fools re-skin, which is a real live variant rather
 * than a joke this code can ignore). The area line is the one beginning with the island glyph.
 *
 * <p><b>The mayor</b> comes from the TAB list. There is no packet or scoreboard field for it: Hypixel
 * renders an "Election" column whose entries are ordinary player-list rows with a custom display
 * name, so the only way to read it is to look at those names. Diana is accepted as mayor only from a
 * line that actually says "Mayor"; a "Minister Diana" line is explicitly <em>not</em> accepted,
 * because a minister's perks do not include the Mythological Ritual and rolling the slot machine on
 * a burrow that cannot exist would be a bug the player could not explain.
 *
 * <p><b>Threading:</b> client thread only, like the gate it feeds.
 */
final class HypixelContext {

    /** How often the sidebar is re-read. Fast enough that a warp is noticed before the player is. */
    private static final long SIDEBAR_INTERVAL_MILLIS = 2_000L;

    /** How often the TAB list is re-read for the mayor. Mayors last five real days. */
    private static final long MAYOR_INTERVAL_MILLIS = 15_000L;

    /** Hypixel's two public domains, lower-cased and dot-prefixed so a look-alike cannot match. */
    private static final String[] HYPIXEL_SUFFIXES = {"hypixel.net", "hypixel.io"};

    /** The glyphs Hypixel uses to introduce the current island on the sidebar. */
    private static final char AREA_GLYPH = '⏣';
    private static final char DEEP_CAVERNS_GLYPH = 'ф';

    /** Launch-argument escape hatch, read once so a typo cannot cost anything per poll. */
    private static final Boolean PROPERTY_OVERRIDE = readPropertyOverride();

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Diana");

    /**
     * "Mayor" immediately followed by "Diana", allowing a colon and any spacing between them.
     *
     * <p>Adjacency rather than mere ordering. The previous rule accepted any row where "diana"
     * appeared <em>anywhere after</em> "mayor", which a combined office row such as "Mayor
     * Foraging Fortune | Minister Diana" satisfies -- and a minister does not grant the
     * Mythological Ritual, so that would arm the whole feature on an island where no Griffin
     * burrow can spawn and start crediting unrelated "RARE DROP!" lines to Diana. The loop also
     * walks all eighty real player rows, so a player calling themselves "MayorDiana" did it too.
     */
    private static final Pattern MAYOR_DIANA = Pattern.compile("(?i)\\bmayor\\b[\\s:]*diana\\b");

    /**
     * A row that is <em>only</em> a mayor or election header, with nothing after it.
     *
     * <p>Hypixel renders TAB widgets as a column of independent rows and routinely splits one into
     * a header and an indented value ("Bank:" then " 12M"), so the mayor may well arrive as
     * "Election:" or "Mayor:" on one row and the name on the next. Requiring both words on one row
     * is a bet on a layout Hypixel has changed several times, and losing that bet is a total,
     * silent outage of the second feature.
     */
    private static final Pattern MAYOR_HEADER = Pattern.compile("(?i)^(?:mayor|election)\\s*:?$");

    /**
     * A value row naming Diana as the office holder, optionally repeating the word "Mayor".
     *
     * <p>Anchored at the start so "Minister Diana" -- the row that follows the mayor's own in
     * Hypixel's election widget -- cannot be read as the value.
     */
    private static final Pattern DIANA_VALUE = Pattern.compile("(?i)^(?:mayor\\s+)?diana\\b.*");

    /** Consecutive mayor polls inside SkyBlock that found no mayor-ish row at all. */
    private int mayorRowMisses;

    /** Set once the warning below has been issued, so a broken layout logs one line, not a stream. */
    private boolean mayorRowWarned;

    /** After this many consecutive misses the layout is reported as unreadable rather than absent. */
    private static final int MAYOR_MISS_WARN_AFTER = 3;

    private final DianaGate gate;

    /** Null means "decide from the server address"; non-null forces the answer. */
    private Boolean hypixelOverride = PROPERTY_OVERRIDE;

    /** The address last seen on join, kept so an override flip can be re-applied without one. */
    private String serverAddress;

    private long nextSidebarPollAt;
    private long nextMayorPollAt;

    HypixelContext(DianaGate gate) {
        this.gate = gate;
    }

    /**
     * Records the connection's server address and opens or closes the Hypixel half of the gate.
     *
     * @param address the address the client dialled, may be null for single-player or a Realm
     */
    void onJoin(String address) {
        this.serverAddress = address;
        applyHypixel();
        // Force the next poll to run immediately rather than up to two seconds into the session.
        nextSidebarPollAt = 0L;
        nextMayorPollAt = 0L;
    }

    /** Clears every server-derived fact. Configuration on the gate (the area whitelist) survives. */
    void onDisconnect() {
        serverAddress = null;
        gate.reset();
    }

    /**
     * Overrides the Hypixel check.
     *
     * @param value {@link Boolean#TRUE} to force "on Hypixel", {@link Boolean#FALSE} to force off,
     *              or null to go back to deciding from the server address
     */
    void setHypixelOverride(Boolean value) {
        this.hypixelOverride = value;
        applyHypixel();
    }

    /** The current override, for a status command to print. */
    Boolean hypixelOverride() {
        return hypixelOverride;
    }

    /**
     * Refreshes the sidebar-derived and TAB-derived facts, subject to this class's own throttles.
     *
     * <p>Safe to call every tick; between intervals it does nothing but compare two longs.
     *
     * <p>Both flags exist so a player who wants none of this pays for none of it. With Diana
     * switched off <em>and</em> the level feature's SkyBlock scope switched off, nothing in the
     * mod can use any of these facts, and this method stops reading the world entirely rather
     * than walking a sidebar every two seconds to feed conditions nobody asks about.
     *
     * @param mc            the client, may be null in a headless test
     * @param now           the current time in milliseconds, from the injected clock
     * @param wantSkyBlock  whether anything still needs the SkyBlock flag and the current island;
     *                      the level feature's server scope wants it even with Diana switched off
     * @param wantMayor     whether anything still needs the elected mayor, i.e. whether the Diana
     *                      feature is switched on at all
     */
    void poll(Minecraft mc, long now, boolean wantSkyBlock, boolean wantMayor) {
        if (now < nextSidebarPollAt) {
            return;
        }
        nextSidebarPollAt = now + SIDEBAR_INTERVAL_MILLIS;

        if (mc == null || mc.level == null || !wantSkyBlock) {
            // Main menu, mid-disconnect, or nothing left that wants the answer. Either way,
            // nothing the server told us is still true.
            clearServerFacts();
            return;
        }

        readSidebar(mc.level);

        // The mayor is the expensive read -- eighty TAB rows, each flattened and cleaned -- and it
        // is worth doing only for a caller that can use it, on a SkyBlock island, on Hypixel. The
        // SkyBlock test is the load-bearing one: it comes off the sidebar title just read, so it
        // is exact, whereas the address check can be defeated by a connection with no recorded
        // server (a transfer, a Realm) and must never be the sole reason a fact is unavailable.
        if (!wantMayor || !gate.onHypixel() || !gate.inSkyBlock()) {
            gate.setMayorDiana(false);
            nextMayorPollAt = 0L;
            return;
        }

        if (now >= nextMayorPollAt) {
            nextMayorPollAt = now + MAYOR_INTERVAL_MILLIS;
            readMayor(mc);
        }
    }

    /** Drops everything that came from a server, and re-arms the mayor poll for the next one. */
    private void clearServerFacts() {
        gate.setInSkyBlock(false);
        gate.setArea(null);
        gate.setMayorDiana(false);
        nextMayorPollAt = 0L;
        mayorRowMisses = 0;
    }

    /**
     * Reads the SkyBlock flag and the current island off the sidebar objective.
     *
     * <p>The entries are deliberately <em>not</em> sorted by score. Sorting is what you need to
     * reconstruct the sidebar top-to-bottom for display; this only has to find one glyph-prefixed
     * line and one title, and neither depends on order. Skipping the sort keeps the poll to a single
     * pass with no list allocation.
     */
    private void readSidebar(ClientLevel level) {
        Scoreboard scoreboard = level.getScoreboard();
        Objective objective = scoreboard == null ? null : scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            gate.setInSkyBlock(false);
            gate.setArea(null);
            return;
        }

        String title = TextClean.clean(objective.getDisplayName().getString());
        gate.setInSkyBlock(isSkyBlockTitle(title));

        String area = null;
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            String line = lineTextOf(scoreboard, entry);
            String candidate = areaIn(line);
            if (candidate != null) {
                area = candidate;
                break;
            }
        }
        gate.setArea(area);
    }

    /** Prefix plus suffix is where Hypixel writes a sidebar line; the entry name is a junk token. */
    private static String lineTextOf(Scoreboard scoreboard, PlayerScoreEntry entry) {
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        if (team == null) {
            return TextClean.clean(entry.owner());
        }
        return TextClean.clean(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
    }

    /**
     * True for Hypixel's SkyBlock sidebar titles.
     *
     * <p>Matched case-insensitively and by containment rather than equality, because the title picks
     * up decoration ("SKYBLOCK CO-OP") and, during the April Fools event, an intentional misspelling.
     */
    private static boolean isSkyBlockTitle(String cleanedTitle) {
        if (cleanedTitle == null || cleanedTitle.isEmpty()) {
            return false;
        }
        String upper = cleanedTitle.toUpperCase(Locale.ROOT);
        return upper.contains("SKYBLOCK") || upper.contains("SKIBLOCK");
    }

    /**
     * The island name from a sidebar line, or null when the line is not the island line.
     *
     * <p>Both glyphs Hypixel uses are accepted, and everything after the glyph is taken verbatim --
     * the gate normalises casing and spacing itself, so there is nothing to do here but strip the
     * marker.
     */
    private static String areaIn(String cleanedLine) {
        if (cleanedLine == null || cleanedLine.isEmpty()) {
            return null;
        }
        int marker = cleanedLine.indexOf(AREA_GLYPH);
        if (marker < 0) {
            marker = cleanedLine.indexOf(DEEP_CAVERNS_GLYPH);
        }
        if (marker < 0) {
            return null;
        }
        String rest = cleanedLine.substring(marker + 1).trim();
        return rest.isEmpty() ? null : rest;
    }

    /**
     * Walks the listed TAB entries looking for the mayor row.
     *
     * <p>{@code getListedOnlinePlayers()} rather than {@code getOnlinePlayers()}: the unlisted set on
     * Hypixel is large and by definition never drawn, so the widget rows we want cannot be in it.
     * The loop stops at the first match, which on a SkyBlock TAB is early -- the Election column
     * sits near the front.
     */
    private void readMayor(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            gate.setMayorDiana(false);
            return;
        }
        boolean sawMayorishRow = false;
        boolean headerPending = false;
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String row = TextClean.clean(display.getString());
            if (row == null || row.isEmpty()) {
                continue;
            }
            // A header/value split: "Election:" or "Mayor:" on its own row, the name on the next.
            if (headerPending && DIANA_VALUE.matcher(row).matches()) {
                gate.setMayorDiana(true);
                mayorRowMisses = 0;
                return;
            }
            if (namesDianaAsMayor(row)) {
                gate.setMayorDiana(true);
                mayorRowMisses = 0;
                return;
            }
            headerPending = MAYOR_HEADER.matcher(row).matches();
            sawMayorishRow |= headerPending || row.toLowerCase(Locale.ROOT).contains("mayor");
        }
        gate.setMayorDiana(false);
        noteMayorRowMiss(sawMayorishRow);
    }

    /**
     * Distinguishes "Diana is not the mayor" from "SkyPrism could not find the mayor row at all".
     *
     * <p>From the outside those are indistinguishable and have completely different fixes, and the
     * second is a total, silent outage of the Diana feature resting on a two-word guess about a
     * TAB widget layout Hypixel has changed before. One WARN after three consecutive polls inside
     * SkyBlock that found nothing mayor-shaped anywhere in eighty rows turns that outage into a
     * one-line bug report; finding any mayor row at all -- even one naming somebody else -- means
     * the reader works and clears the count.
     */
    private void noteMayorRowMiss(boolean sawMayorishRow) {
        if (sawMayorishRow) {
            mayorRowMisses = 0;
            return;
        }
        if (++mayorRowMisses >= MAYOR_MISS_WARN_AFTER && !mayorRowWarned) {
            mayorRowWarned = true;
            LOGGER.warn("SkyPrism found no mayor row anywhere in the SkyBlock TAB list after {} "
                    + "polls. Diana detection reads the elected mayor from that widget, so the "
                    + "slot machine will stay inert until it can. If Hypixel has changed the "
                    + "layout, this is the line to report.", mayorRowMisses);
        }
    }

    /**
     * True for a TAB row that says Diana is the mayor.
     *
     * <p>Hypixel writes the row as "Mayor Diana" and, in some layouts, "Mayor: Diana". Requiring the
     * word "Mayor" is what keeps a "Minister Diana" row out: a minister grants a perk, not the
     * Mythological Ritual, so treating her as the mayor would arm the whole feature on an island
     * where no burrow can spawn.
     *
     * <p>The two words must be <b>adjacent</b>, not merely in that order. Ordering alone accepts a
     * combined office row -- "Mayor Foraging Fortune | Minister Diana" -- and, because this loop
     * also walks every real player row, a player who calls themselves "MayorDiana". Both would open
     * the gate under a mayor who is not Diana, and {@code LootParser} is deliberately not
     * Diana-specific, so any rare drop earned within a stale spawn's five-minute lifetime would
     * then be recorded as Diana loot.
     */
    static boolean namesDianaAsMayor(String cleanedRow) {
        return cleanedRow != null && cleanedRow.length() >= 5
                && MAYOR_DIANA.matcher(cleanedRow).find();
    }

    /** Pushes the current Hypixel verdict into the gate. */
    private void applyHypixel() {
        if (hypixelOverride != null) {
            gate.setOnHypixel(hypixelOverride);
            return;
        }
        gate.setOnHypixel(isHypixelAddress(serverAddress));
    }

    /**
     * True when an address belongs to Hypixel.
     *
     * <p>The port is stripped, the host is lower-cased, and the match is on a dot-prefixed suffix or
     * the bare domain. Matching a bare {@code endsWith("hypixel.net")} would also accept
     * {@code nothypixel.net}, which is exactly the kind of look-alike a chat-reading mod should not
     * be pointed at.
     *
     * @param address the address the client dialled, may be null
     * @return whether it is a Hypixel address
     */
    static boolean isHypixelAddress(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        String host = address.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (String suffix : HYPIXEL_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /** The address of the server the client is currently on, or null. */
    static String currentAddress(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        ServerData server = mc.getCurrentServer();
        return server == null ? null : server.ip;
    }

    /** Reads {@code -Dskyprism.forceHypixel=true|false} once, tolerating a missing or junk value. */
    private static Boolean readPropertyOverride() {
        String raw;
        try {
            raw = System.getProperty("skyprism.forceHypixel");
        } catch (SecurityException restricted) {
            return null;
        }
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true")) {
            return Boolean.TRUE;
        }
        if (value.equals("false")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
