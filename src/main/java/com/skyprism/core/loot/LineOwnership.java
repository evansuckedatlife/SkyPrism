package com.skyprism.core.loot;

import com.skyprism.core.loot.containers.ContainerText;
import com.skyprism.core.util.TextClean;

import java.util.function.Supplier;

/**
 * Whose drop a chat line is about: the local player's, or somebody else's.
 *
 * <h2>Why this class exists at all</h2>
 * <p>Hypixel announces other people's loot in the same channel, in the same breath, and often in
 * the same sentence shape as yours. A slot machine that spins for a party member's Chimera is worse
 * than one that does not spin: the player looks at their inventory, nothing arrived, and the feature
 * loses its credibility in one line. So every banner path asks this question before it asks any
 * other.
 *
 * <p>It used to be answered by a substring guess, in two places that had already begun to disagree:
 * {@code LootParser} refused four needles, {@code CombatChatGuards} refused five, and neither could
 * do better because the core had no idea who the player was. That guess was <em>knowingly</em>
 * over-eager. Hypixel writes {@code "<player> has obtained ..."} for the local player's own Crystal
 * Hollows pickups too, and {@code "found a "} sits inside the player's own {@code "You found a
 * ..."}, so the guess silently refused the player's own loot -- which looks exactly like a feature
 * that does not work, rather than one that declined.
 *
 * <h2>The rule</h2>
 * <p>A line is somebody else's when, and only when:
 * <ol>
 *   <li>it carries a tell that names no actor to compare a username against -- {@code "They also
 *       received"}, {@code "from their sacrifice"}, {@code "for assisting "} -- and so cannot be
 *       claimed. Two of the three are third-person by pronoun; {@code "for assisting "} is second
 *       person and is refused as a share of somebody else's kill, which the field's own notes
 *       argue for rather than assume; or</li>
 *   <li>it carries a named third-person verb -- {@code "has obtained"}, {@code "found a "},
 *       {@code "in their "} -- and the actor written in front of that verb is <b>not</b> the local
 *       player; or</li>
 *   <li>it was typed by a player rather than printed by the server, which a colon gives away. The
 *       name comparison is the one part of this rule somebody else can steer -- by typing the local
 *       player's own username into a forged banner -- so the colon guard runs inside the rule as
 *       well as in front of it.</li>
 * </ol>
 *
 * <p>Everything else is the local player's. In particular {@code "You found a ..."} is theirs
 * <em>by construction</em>: the actor reads {@code "You"}, the server is addressing this client, and
 * no name lookup is needed or wanted. That is the case the old needle list got wrong, and it is the
 * one the player meets every time they open a Crystal Hollows chest.
 *
 * <h2>The deliberate asymmetry: an unknown name refuses</h2>
 * <p>{@link #localPlayerName()} returns null before the client has a session -- during startup, and
 * for the seconds around a reconnect. In that state a named third-person line is <b>refused</b>,
 * exactly as it was before this class existed. <b>That is not an oversight and must not be
 * "fixed".</b> The two mistakes are not symmetric. Refusing a line the player did own costs one
 * spin, in a window that is transient by construction. Accepting a line the player did not own puts
 * five party members' drops on one person's screen, and that is the failure that gets a mod turned
 * off. When we do not know who we are, we claim nothing.
 *
 * <p>Second-person lines are unaffected by the unknown state: {@code "You found a ..."} needs no
 * name to be certain of, so it is accepted whether or not the client has one yet.
 *
 * <h2>Cost</h2>
 * <p>This runs on chat lines. The common path -- an ordinary line carrying none of the six tells --
 * is a handful of {@link String#indexOf(String)} calls and returns without allocating, without
 * reading the supplier and without touching a {@link java.util.regex.Pattern}. Only a line that
 * already looks like a loot announcement pays for the strip-and-compare, and those arrive a few
 * times an hour rather than a few times a second.
 *
 * <h2>One rule, one place</h2>
 * <p>{@code LootParser.isThirdPartyLine} and {@code CombatChatGuards.announcesAnotherPlayer} are
 * both thin delegates to this class, and the rank-prefix strip is
 * {@link ContainerText#playerName(String)} -- the same one the chest broadcasts have always used --
 * rather than a second copy. Two encodings of one corpus is how the previous version drifted; there
 * is now exactly one.
 */
public final class LineOwnership {

    /**
     * Tells that name no actor this class could compare a username against, and are refused
     * outright for that reason.
     *
     * <p>{@code "BONUS LOOT! They also received 17x Wise Dragon Fragment from their sacrifice!"}
     * refers back to a player named on an earlier line, so there is genuinely nothing here to
     * compare.
     *
     * <p>{@code "for assisting "} is the awkward one and the entry to be honest about. {@code "LOOT
     * SHARE You received 2 Mossybit Shards for assisting FallenYeti!"} and {@code "You received
     * kill credit for assisting on a slayer miniboss!"} are <b>second person, and the shards really
     * do land in the local player's inventory</b> -- so refusing them is a deliberate choice about
     * a share of somebody else's kill, not a statement that the loot was not the player's. It costs
     * nothing today: both lines belong to {@code COMBAT_SHARD} and {@code SLAYER_MINIBOSS}, which
     * ship {@link com.skyprism.core.loot.RollPolicy#NEVER} and whose detectors do not consult this
     * class at all. It would start costing something the moment either source armed <em>and</em>
     * routed its line through here, and the fix then is to read the actor after
     * {@code "for assisting "} rather than to drop the needle -- the name after it is the other
     * player, so the sense of the comparison inverts.
     */
    private static final String[] PRONOUN_TELLS = {
            "They also received",
            "from their sacrifice",
            "for assisting ",
    };

    /**
     * Third-person verbs that follow an actor's name.
     *
     * <p>No leading space on {@code "found a "}: on the raw line the {@code RARE REWARD} broadcast
     * puts a formatting code immediately in front of the verb, so a space-anchored needle silently
     * never matches it. The actor is read from the <em>cleaned</em> line, where the space is back.
     *
     * <p>{@code "in their "} is in the list but is never the verb an actor is read from in
     * practice. It belongs to the same sentence as {@code "found a "} ({@code "... found a X in
     * their Obsidian Chest"}), and {@link #firstVerbIn(String)} takes the <em>earliest</em> match,
     * so the actor is read from in front of the verb that has one. A line carrying
     * {@code "in their "} and nothing else yields a fragment of item text as its actor, which
     * matches no username and is therefore refused -- the conservative direction, and the same
     * answer the old needle list gave.
     */
    private static final String[] NAMED_VERBS = {
            "has obtained",
            "found a ",
            "in their ",
    };

    /**
     * Every tell, for the one cheap sweep that lets the common path out early.
     *
     * <p><b>Derived, never written out.</b> This array is the gate in front of the whole rule: a
     * needle that is in {@link #NAMED_VERBS} or {@link #PRONOUN_TELLS} but missing from here is
     * never even looked for, so the line carrying it is silently called the local player's. That
     * is the fail-<em>open</em> direction -- somebody else's Chimera on your screen -- and it is
     * the exact drift the two old needle lists died of, reintroduced one level down. Concatenating
     * the two sources costs one array copy at class-load and nothing per line, and makes the
     * mistake unwriteable.
     */
    private static final String[] ALL_TELLS = allTells();

    private static String[] allTells() {
        String[] all = new String[NAMED_VERBS.length + PRONOUN_TELLS.length];
        System.arraycopy(NAMED_VERBS, 0, all, 0, NAMED_VERBS.length);
        System.arraycopy(PRONOUN_TELLS, 0, all, NAMED_VERBS.length, PRONOUN_TELLS.length);
        return all;
    }

    /**
     * The actor Hypixel writes when it is talking to this client.
     *
     * <p>A Minecraft username cannot be {@code "You"} -- Mojang would have to have sold it, and it
     * would break considerably more than this -- so there is no ambiguity to resolve.
     */
    private static final String SECOND_PERSON = "You";

    /** Supplies the client's own username; yields null until the mc layer installs a real one. */
    private static volatile Supplier<String> nameSource = () -> null;

    private LineOwnership() {
    }

    /**
     * Installs the source of the local player's username.
     *
     * <p>Called once, from the mc layer, with something that reads the live client rather than a
     * captured string: the name is not known when the mod starts and this class must see it the
     * moment it becomes known. The supplier may return null or blank for as long as it likes; see
     * the class notes for what that means.
     *
     * @param supplier the source, or null to go back to "we do not know who we are"
     */
    public static void useLocalPlayerName(Supplier<String> supplier) {
        nameSource = supplier == null ? () -> null : supplier;
    }

    /**
     * The local player's username as the installed source currently reports it.
     *
     * <p>Null means unknown, which is a real and expected state rather than an error -- the client
     * has no session before it has logged in. A supplier that throws is read as unknown too: a
     * broken name lookup must not take a chat line down with it.
     *
     * @return the username, possibly with a rank prefix still attached, or null when unknown
     */
    public static String localPlayerName() {
        try {
            return nameSource.get();
        } catch (RuntimeException | LinkageError notReady) {
            return null;
        }
    }

    /**
     * Whether this line announces somebody else's loot rather than the local player's.
     *
     * <p>The whole rule, against the installed name source. See the class notes.
     *
     * @param raw the line as the server sent it, formatting codes intact; may be null
     * @return true when the line is somebody else's and must not spin anything
     */
    public static boolean announcesAnotherPlayer(String raw) {
        // The supplier is deliberately not read until a tell has already been found: the common
        // path must not reach into the game client once per chat line.
        return hasAnyTell(raw) && decide(raw, localPlayerName());
    }

    /**
     * {@link #announcesAnotherPlayer(String)} against an explicit name rather than the installed
     * source.
     *
     * <p>Exists so the rule can be exercised for a named player without installing global state,
     * and so a caller that already holds the name does not pay for a second lookup.
     *
     * @param raw       the line as the server sent it; may be null
     * @param localName the client's own username, rank prefix optional; null or blank means unknown
     */
    public static boolean announcesAnotherPlayer(String raw, String localName) {
        return hasAnyTell(raw) && decide(raw, localName);
    }

    /**
     * Reduces a name to the bare username, dropping a rank prefix and any formatting.
     *
     * <p>{@code "[MVP+] Notch"} is {@code "Notch"}. Delegates to
     * {@link ContainerText#playerName(String)} so the mod has one rank-prefix rule rather than two.
     *
     * @return the bare username, or {@code ""} when there was nothing usable
     */
    public static String bareName(String raw) {
        return ContainerText.playerName(raw);
    }

    /**
     * The cheap sweep. Six {@code indexOf} calls, no allocation, no supplier read.
     *
     * <p>This is what an ordinary chat line costs, and it is the whole reason the expensive half is
     * a separate method.
     */
    private static boolean hasAnyTell(String raw) {
        return raw != null && containsAny(raw, ALL_TELLS);
    }

    /**
     * The expensive half, reached only by a line that already carries a tell.
     *
     * @param localName the client's own username; null or blank means unknown, which refuses
     */
    private static boolean decide(String raw, String localName) {
        if (raw.indexOf(':') >= 0) {
            // Player-authored, and therefore not the server telling this client anything. The
            // colon is Skyblocker's guard and the combat detectors already apply it, but it has to
            // be here too now that the answer turns on a name: comparing names is the one part of
            // this rule a hostile party member can steer, by typing the local player's own
            // username into a forged banner. Every player-authored line in the game carries
            // "name: " and no loot banner Hypixel prints does, so this costs nothing real.
            return true;
        }
        if (containsAny(raw, PRONOUN_TELLS)) {
            return true;
        }

        String clean = TextClean.clean(raw);
        int verbAt = firstVerbIn(clean);
        if (verbAt <= 0) {
            // Either the tell survived only in the raw text -- a formatting code split it, and
            // cleaning cannot un-split what it merged -- or the verb opens the line with no actor
            // in front of it. Neither yields a name, so neither can be claimed.
            return true;
        }

        String actor = ContainerText.playerName(clean.substring(0, verbAt));
        if (actor.isEmpty()) {
            return true;
        }
        if (SECOND_PERSON.equalsIgnoreCase(actor)) {
            // The server is addressing this client. Certain without a name lookup, and the case
            // the old substring guess got wrong.
            return false;
        }
        if (localName == null) {
            return true;
        }
        String mine = ContainerText.playerName(localName);
        // Whole token, case-insensitive. A player called "Not" must not claim "Notch has
        // obtained ...", which is what any contains-style comparison would let happen.
        return mine.isEmpty() || !mine.equalsIgnoreCase(actor);
    }

    /** The earliest {@link #NAMED_VERBS} match in the cleaned line, or -1 when there is none. */
    private static int firstVerbIn(String clean) {
        int best = -1;
        for (int i = 0; i < NAMED_VERBS.length; i++) {
            int at = clean.indexOf(NAMED_VERBS[i]);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
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
