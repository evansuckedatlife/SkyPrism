package com.skyprism.core.diana;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The four Hypixel chat lines that drive the Diana (Mythological Ritual) feature.
 *
 * <p><b>Why the patterns are copied verbatim rather than "improved".</b> These four
 * expressions are reproduced character for character from SkyHanni's
 * {@code GriffinBurrowHelper} (repo-pattern keys for the burrow dig, the generic
 * mythological spawn, the treasure dig and the rare-mob waypoint share), re-read
 * against the {@code beta} branch on 2026-08-28. SkyHanni's patterns are maintained
 * against the live server by a very large user base, so an "obvious" tidy-up here is
 * far more likely to be a regression than a fix. If Hypixel changes a message,
 * re-copy from SkyHanni rather than guessing.
 *
 * <p><b>Why every match is anchored.</b> All three matcher helpers use
 * {@link Matcher#matches()}, never {@code find()}. Hypixel's own lines arrive as a whole
 * chat message, whereas any player can put these strings <em>inside</em> a party message
 * (<code>&#167;9Party &#167;8&gt; Steve&#167;f: &#167;rOh! You dug out a Minos
 * Inquisitor!</code>). Anchoring is the entire defence against someone making another
 * player's HUD spin on demand, so it must not be relaxed to {@code find()} later.
 *
 * <p><b>Section signs.</b> Every section sign is written as a {@code \\u00A7} escape so the
 * file's encoding can never change what is being matched -- the same rule
 * {@link com.skyprism.core.util.TextClean} follows.
 *
 * <p>Sources, both re-checked 2026-08-28:
 * SkyHanni {@code beta},
 * {@code src/main/java/at/hannibal2/skyhanni/features/event/diana/GriffinBurrowHelper.kt};
 * and {@code https://hypixelskyblock.minecraft.wiki/w/Mythological_Ritual} for the mob
 * and drop facts. The official {@code wiki.hypixel.net} shut down in July 2026 and must
 * not be used as a re-verification source.
 */
public final class DianaPatterns {

    /**
     * A mythological creature crawling out of a freshly dug burrow.
     *
     * <p>The exclamation is one of seven Hypixel picks at random, so it carries no
     * information and stays a non-capturing alternation. The optional article is what lets
     * the plural "You dug out Siamese Lynxes" line work alongside the singular ones.
     * {@code (?:\\u00A7[a-f0-9r])*} eats the <code>&#167;r&#167;2</code> (ordinary) or
     * <code>&#167;r&#167;c</code> (rare) colour run in front of the name.
     *
     * <p><b>The article is accepted on both sides of that colour run.</b> SkyHanni's original
     * places it before the codes, which is only correct if Hypixel writes "You dug out a
     * &#167;r&#167;2Minotaur". If it writes "You dug out &#167;r&#167;2a Minotaur" instead, the
     * one-sided form still <em>matches</em> -- the optional group matches nothing and
     * {@code [\\w\\s]+} swallows "a Minotaur" -- and then
     * {@link MythologicalCreature#byDisplayName} finds no such creature, so the line is reported as
     * "not a spawn". That failure is total (no creature ever arms the tracker), silent (nothing
     * logs, nothing throws) and invisible to every synthetic fixture, so the article is accepted in
     * either position and {@code byDisplayName} strips it as well. Two cheap defences against one
     * unverified guess about a byte layout nobody here has seen.
     *
     * <p>{@code [\\w\\s]+} then takes the name itself. This is the part worth understanding
     * before touching it: because neither {@code \\w} nor {@code \\s} matches a section
     * sign, the greedy run stops cleanly at the closing <code>&#167;r&#167;e!</code> while
     * still swallowing the internal space of "Minos Inquisitor", "Gaia Construct",
     * "Siamese Lynxes", "Stranded Nymph", "Cretan Bull", "Minos Champion", "Minos Hunter"
     * and "King Minos". Narrowing it to {@code \\w+} would silently truncate eight of the
     * twelve creatures to their first word.
     */
    public static final Pattern SPAWN = Pattern.compile(
            "\u00A7c\u00A7l(?:Oh|Uh oh|Yikes|Oi|Good Grief|Danger|Woah)! "
                    + "\u00A7r\u00A7eYou dug out (?:an? )?(?:\u00A7[a-f0-9r])*(?:an? )?"
                    + "(?<creatureType>[\\w\\s]+)\u00A7r\u00A7e!");

    /**
     * A burrow being dug, including the last one of a chain.
     *
     * <p>Both halves of the {@code type} alternation keep their trailing "!", because the
     * chain-finished form and the ordinary form differ only in that phrase; the
     * {@code (current/max)} counter that follows is identical in both and is what a HUD
     * actually wants.
     */
    public static final Pattern BURROW_DUG = Pattern.compile(
            "\u00A7eYou (?<type>finished the Griffin burrow chain!|dug out a Griffin Burrow!) "
                    + "\u00A7r\u00A77\\((?<current>\\d+)/(?<max>\\d+)\\)");

    /**
     * A treasure burrow paying out, either an item ("RARE DROP!") or coins ("Wow!").
     *
     * <p>The lone <code>&#167;?</code> after <code>&#167;r</code> is deliberate and is in
     * SkyHanni verbatim: some payout lines colour the reward and some do not, so the second
     * section sign is optional. This pattern only answers "is this line a treasure payout";
     * {@link LootParser} is what decomposes the reward into a {@link LootDrop}.
     */
    public static final Pattern TREASURE_DUG = Pattern.compile(
            "\u00A76\u00A7l(?:RARE DROP!|Wow!) \u00A7r\u00A7eYou dug out(?: a)? "
                    + "\u00A7r\u00A7?.+\u00A7r\u00A7e!");

    /**
     * The community's Minos Inquisitor waypoint broadcast, sent by another player either in
     * party chat or in all-chat.
     *
     * <p>Unlike the other three this line is <em>player-authored</em>: the coordinates are
     * whatever the sending client typed, so anything built on this pattern must treat the
     * numbers as untrusted input and range-check them before pointing a waypoint at them.
     * The {@code party} group is present exactly when the broadcast came through party chat,
     * which is the only cheap signal that the sender is someone you are actually playing
     * with. No helper is exposed for it here: the Minecraft-side adapter that renders a
     * waypoint owns that decision, and this module deliberately stops at the regex.
     */
    public static final Pattern INQUISITOR_SHARE = Pattern.compile(
            "(?<party>\u00A79Party \u00A78> )?(?<playerName>.+)\u00A7f: \u00A7r"
                    + "A MINOS INQUISITOR has spawned near \\[(?<area>.*)] at Coords "
                    + "(?<x>[^ ]+) (?<y>[^ ]+) (?<z>[^ ]+)");

    private DianaPatterns() {
    }

    /**
     * Identifies the creature announced by a spawn line.
     *
     * <p>Returns empty both when the line is not a spawn line at all and when it is one but
     * names something this build does not know. A creature added by a future Hypixel update
     * must degrade to "no roll", never to a wrong roll.
     *
     * @param rawLine the chat line with its formatting codes intact, may be null
     * @return the creature, or empty when the line is not a recognised spawn
     */
    public static Optional<MythologicalCreature> matchSpawn(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = SPAWN.matcher(rawLine);
        if (!m.matches()) {
            return Optional.empty();
        }
        return MythologicalCreature.byDisplayName(m.group("creatureType"));
    }

    /**
     * Reads the burrow counter out of a "dug out a Griffin Burrow" line.
     *
     * @param rawLine the chat line with its formatting codes intact, may be null
     * @return the counter and whether this dig closed the chain, or empty when the line is
     *         not a burrow dig
     */
    public static Optional<BurrowDig> matchBurrowDig(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = BURROW_DUG.matcher(rawLine);
        if (!m.matches()) {
            return Optional.empty();
        }
        boolean finished = m.group("type").startsWith("finished");
        return Optional.of(new BurrowDig(
                finished,
                parseCounter(m.group("current")),
                parseCounter(m.group("max"))));
    }

    /**
     * True when the line is a treasure-burrow payout, whether an item or a pile of coins.
     *
     * @param rawLine the chat line with its formatting codes intact, may be null
     * @return whether this is a treasure payout line
     */
    public static boolean isTreasureDig(String rawLine) {
        return rawLine != null && TREASURE_DUG.matcher(rawLine).matches();
    }

    /**
     * {@code \\d+} is unbounded, so a hostile or corrupted line could carry a 40-digit
     * counter and blow up {@link Integer#parseInt}. A parse failure here means "the counter
     * is nonsense", which is worth surviving rather than throwing out of a chat handler, so
     * it saturates instead.
     */
    private static int parseCounter(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException overflow) {
            return Integer.MAX_VALUE;
        }
    }
}
