package com.skyprism.core.loot.combat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single highest-leverage trigger in combat: Hypixel's universal
 * "<code>&lt;NAME&gt; DOWN!</code>" boss-defeat banner.
 *
 * <p>One shape covers Arachne, all seven Ender Dragon types, the Endstone Protector, all five
 * Crimson Isle minibosses and -- with the same words in a different colour run -- Kuudra. That is
 * fourteen bosses across four islands served by one compiled {@code Pattern}, which is exactly why
 * "a slot machine for the whole game" does not mean fourteen listeners.
 *
 * <h2>What was copied and what was deliberately generalised</h2>
 * <p>The reference forms are, verbatim:
 * <ul>
 *   <li>{@code \u00A7f\s*\u00A7r\u00A76\u00A7l(?<name>.+) DOWN!} -- SkyHanni's generic Crimson
 *       miniboss form.</li>
 *   <li>{@code \u00A7r\u00A7f {27}\u00A7r\u00A76\u00A7l(?<type>.*) DOWN!\u00A7r} -- the End variant,
 *       read off a system message, carrying an extra leading and trailing reset.</li>
 *   <li>{@code \u00A7.\s*(?:\u00A7.)*KUUDRA DOWN!} -- Kuudra, same words behind a different prefix.</li>
 * </ul>
 * <p>Those three disagree only about how many section-sign codes and how much padding sit in front
 * of the words, and they disagree because they were captured on different code paths. Pinning any
 * one of them here would produce a detector that fires for three bosses and silently never fires for
 * the rest -- and this project already knows the padding varies, because
 * {@code LegacyText.toLegacy} inserts a reset before every styled run after the first, so the exact
 * byte layout SkyPrism sees is a function of SkyPrism's own text pipeline rather than of Hypixel.
 *
 * <p>So {@link #PATTERN} accepts <em>any</em> interleaving of formatting codes and whitespace before
 * the name and after the bang, and nothing else. That is strictly more permissive than the three
 * captured forms about padding and strictly no more permissive about content.
 *
 * <h2>Why that is still safe, and the rule that keeps it safe</h2>
 * <p>The match is anchored with {@link Matcher#matches()}, never {@code find()} -- the same defence
 * {@code DianaPatterns} documents at length. A player can put "ARACHNE DOWN!" inside a party message,
 * but the party prefix ("{@code \u00A79Party \u00A78> Steve\u00A7f: }") contains letters that are
 * neither a section-sign pair nor whitespace, so the anchored prefix cannot span it and the name
 * class -- upper case, digits, apostrophe, space, hyphen -- cannot swallow a lower-case rank or
 * nickname.
 *
 * <p>The second half of the defence is not in the regex at all: <b>every caller must match the
 * captured name against a closed table and reject anything else.</b> Accepting an arbitrary capture
 * would make any line ending in " DOWN!" a remote control for somebody else's HUD, and would also
 * caption the widget with whatever a stranger typed. {@link BossDownDetector} enforces this by
 * construction -- there is no way to build one without supplying the table.
 */
public final class BossDownBanner {

    /**
     * The anchored universal boss-down banner.
     *
     * <p>{@code (?:\u00A7.|\s)*} is the generalised padding described in the class notes: a run of
     * formatting codes and whitespace in any order, which subsumes every captured variant. The name
     * class deliberately excludes lower case, so it stops dead at a player name or a rank.
     */
    public static final Pattern PATTERN = Pattern.compile(
            "(?:\u00A7.|\\s)*(?<subject>[A-Z0-9' -]+) DOWN!(?:\u00A7.|\\s)*");

    private BossDownBanner() {
    }

    /**
     * The upper-case boss name a defeat banner announced, or null when the line is not one.
     *
     * <p>Returns the raw capture. It is the caller's job to look it up in a closed table; a name
     * that is not in one must be discarded rather than captioned.
     */
    public static String subjectOf(String rawLine) {
        if (rawLine == null || rawLine.isEmpty() || rawLine.indexOf(" DOWN!") < 0) {
            return null;
        }
        Matcher matcher = PATTERN.matcher(rawLine);
        return matcher.matches() ? matcher.group("subject") : null;
    }
}
