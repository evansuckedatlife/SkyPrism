package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LineOwnership;

/**
 * The cheap rejections every combat banner detector applies before it touches a {@code Pattern}.
 *
 * <h2>Two different jobs, both cheap</h2>
 * <p>The first is <b>ownership</b>. Hypixel broadcasts other people's loot in the same breath as
 * yours, and the wording is the only tell. That question is not answered here: it is
 * {@link LineOwnership}'s, and {@link #announcesAnotherPlayer(String)} is a delegate to it.
 *
 * <p>It used to be answered here, by a five-needle {@code indexOf} list, while
 * {@code LootParser.isThirdPartyLine} answered it with a four-needle one. Two copies of one corpus,
 * already differing by two entries -- and both were over-eager in the same way: they refused the
 * player's <em>own</em> {@code "You found a ..."} and their own Crystal Hollows
 * {@code "<name> has obtained ..."}, so the machine silently did not spin for loot the player had
 * actually received. The rule now compares the captured actor against the local player's username;
 * see {@link LineOwnership} for it, including why an unknown username still refuses.
 *
 * <p>The second job is <b>cross-source separation</b>. Several banners share a word with a banner
 * that belongs to a different detector: the Diana treasure line is a {@code RARE DROP!}, a Crop
 * Fever proc is a {@code RARE DROP!}, a Garden pest drop is a {@code RARE DROP!}. Registration order
 * in {@link com.skyprism.core.loot.LootEventBus} decides which source claims a line two detectors
 * both match, but relying on order alone means a re-ordering silently steals another source's
 * events. So the catch-all detectors additionally decline the lines they know are somebody else's.
 * That list is local to combat and stays here.
 *
 * <h2>The colon guard</h2>
 * <p>{@link #looksPlayerAuthored(String)} rejects any line containing a colon. That is Skyblocker's
 * own guard, shipped on this exact Minecraft version, and it is both cheaper and stricter than a
 * regex prefix: every player-authored line in the game -- all chat, party, guild, co-op, an NPC
 * quote -- carries "{@code name: }", and no rare-drop banner Hypixel prints does. It is the reason a
 * party member cannot type a drop banner and spin somebody else's HUD, which is the same threat
 * {@code DianaPatterns} documents and defends with anchoring.
 *
 * <p>It also runs <b>first</b> in {@link #rejects(String)}, and that order is load-bearing now that
 * ownership consults a name: a party member who types "{@code <yourName> has obtained a Chimera}"
 * would otherwise present the local player's own name to the ownership check and be believed. The
 * colon stops the line before the name is ever read.
 *
 * <p>Every method here is a handful of {@code String.indexOf} calls on a line that already survived
 * the bus pre-filter. Nothing allocates on the common path, nothing compiles a pattern, nothing runs
 * on ordinary chat.
 */
public final class CombatChatGuards {

    /**
     * Lines that carry a banner word this detector's source does not own.
     *
     * <p>Each entry names the source that does own it: {@code You dug out} is Diana's treasure line,
     * {@code You dropped} is the Garden's Crop Fever, {@code Cocoaleech} is the Garden pest drop's
     * own trailing bracket, {@code gift with} is the Season of Jerry gift family.
     */
    private static final String[] OTHER_SOURCE = {
            "You dug out",
            "You dropped",
            "Cocoaleech",
            "gift with",
    };

    private CombatChatGuards() {
    }

    /**
     * Whether the line announces somebody else's loot rather than the local player's.
     *
     * <p>One rule, one place: this is {@link LineOwnership#announcesAnotherPlayer(String)}, the same
     * call {@code LootParser.isThirdPartyLine} makes. The two layers cannot disagree because there
     * is only one of them.
     */
    public static boolean announcesAnotherPlayer(String raw) {
        return LineOwnership.announcesAnotherPlayer(raw);
    }

    /**
     * {@link #announcesAnotherPlayer(String)} against an explicit username rather than the installed
     * source. Null or blank is the unknown case, which refuses.
     */
    public static boolean announcesAnotherPlayer(String raw, String localPlayer) {
        return LineOwnership.announcesAnotherPlayer(raw, localPlayer);
    }

    /**
     * Whether the line belongs to a source outside combat that shares a banner word with one of
     * ours.
     */
    public static boolean belongsToAnotherSource(String raw) {
        return containsAny(raw, OTHER_SOURCE);
    }

    /**
     * Whether the line was typed by a player rather than printed by the server.
     *
     * <p>One {@code indexOf} for a colon. See the class notes for why that is sufficient, why it
     * must not be relaxed, and why it must keep running before the ownership check.
     */
    public static boolean looksPlayerAuthored(String raw) {
        return raw.indexOf(':') >= 0;
    }

    /**
     * All three guards at once: the standard front door for a universal banner detector.
     *
     * <p>The colon guard is first on purpose -- see the class notes.
     */
    public static boolean rejects(String raw) {
        return looksPlayerAuthored(raw)
                || announcesAnotherPlayer(raw)
                || belongsToAnotherSource(raw);
    }

    private static boolean containsAny(String raw, String[] needles) {
        for (int i = 0; i < needles.length; i++) {
            if (raw.indexOf(needles[i]) >= 0) {
                return true;
            }
        }
        return false;
    }
}
