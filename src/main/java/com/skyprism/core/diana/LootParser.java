package com.skyprism.core.diana;

import com.skyprism.core.loot.LineOwnership;
import com.skyprism.core.util.TextClean;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Hypixel drop announcement into {@link LootDrop}s. <b>This is the one place the banner
 * corpus is encoded.</b>
 *
 * <h2>One corpus, one implementation</h2>
 * <p>This class used to have a twin: {@code com.skyprism.core.loot.events.RareDropBanner} carried a
 * second copy of the same patterns for the general loot bus, and the twin's javadoc claimed this
 * parser was "deliberately left exactly as it is". They drifted, exactly as two copies of one regex
 * always do, and the drift was live: on a bare unformatted line the twin matched and this one did
 * not; on a comma-only count the twin reported a stack of 2,147,483,647; on a Crop Fever line the
 * two disagreed about whether an English sentence was an item name. That is what "some drops parse
 * and some do not" looks like from the player's chair. So the patterns now live here, once, and
 * {@code RareDropBanner} is a thin adapter over {@link #matchBanner(String)} that keeps its own
 * record shape for the five detectors that use it. Nothing in this file is duplicated there.
 *
 * <p>There were two further copies, and they were worse, because each was a yes/no rather than a
 * parse and so could disagree about whether a line was a drop at all without disagreeing about
 * what dropped. {@code core.loot.gathering.BannerLines} accepted {@code UNCOMMON DROP!} when
 * neither parser did, so a detector armed on a line nothing could then decompose and the roll
 * settled on "No Drop" -- a feature that looks like it works. {@code
 * core.loot.combat.MobRareDropDetector} carried a fourth alternation, missing that same word.
 * Both now call {@link #looksLikeBanner(String)} / {@link #bannerWordOf(String)}: same vocabulary,
 * same anchoring, and deliberately no decomposition, because those two must not lose a line whose
 * reward this parser cannot yet name. See {@link #BANNER_WORD_ONLY}.
 *
 * <p><b>Why chat and not the inventory.</b> SkyHanni tracks Diana loot by diffing the player's
 * inventory, which is more complete but needs a booted game to test and a live item repository to
 * name things. The slot machine only has to lock its reels onto what the player visibly received,
 * and every reward worth celebrating is announced in chat with a banner, so chat is both sufficient
 * and unit-testable on a bare JVM.
 *
 * <h2>The line shapes</h2>
 * <p>Verified against SkyHanni {@code beta} ({@code DianaProfitTracker.kt},
 * {@code RareDropMessages.kt}, {@code PestProfitTracker.kt}, {@code CropFeverTracker.kt},
 * {@code DungeonChatFilter.kt}), Skyblocker {@code main}
 * ({@code RareDropSpecialEffects.java}, {@code SkyBlockIcons.java}) and Skytils {@code 1.x}:
 * <ol>
 *   <li><b>Treasure coins</b> --
 *       <code>&#167;6&#167;lWow! &#167;r&#167;eYou dug out &#167;r&#167;62,500
 *       coins&#167;r&#167;e!</code>. Modelled as an item literally named "Coins" whose
 *       {@code count} is the amount, so a reel can show it like anything else.</li>
 *   <li><b>Treasure item</b> --
 *       <code>&#167;6&#167;lRARE DROP! &#167;r&#167;eYou dug out a
 *       &#167;r&#167;9Griffin Feather&#167;r&#167;e!</code>. The reward is inside the
 *       sentence, not after the banner.</li>
 *   <li><b>Bracketed drop</b> --
 *       <code>&#167;b&#167;lRARE DROP! &#167;r&#167;7(&#167;r&#167;f&#167;r&#167;9Revenant
 *       Viscera&#167;r&#167;7) (+123% Magic Find)</code>. Slayers, and every combat drop
 *       that lands straight in a sack, wrap the reward in a grey bracket.</li>
 *   <li><b>Sentence drop</b> --
 *       <code>&#167;9&#167;lRARE DROP! &#167;r&#167;aYou dropped 48x Enchanted Melon
 *       Slice!</code>. Garden Crop Fever puts the reward inside an English sentence, the same
 *       way a treasure dig does. Both parsers used to hand the whole sentence back as an item
 *       name and record it forever; see {@link #SENTENCE_DROP}.</li>
 *   <li><b>Plain drop</b> --
 *       <code>&#167;6&#167;lRARE DROP! &#167;r&#167;9Dwarf Turtle Shelmet
 *       &#167;r&#167;b(+&#167;r&#167;b168% &#167;r&#167;b* Magic Find&#167;r&#167;b)</code>,
 *       with the trailing bracket absent when the player has no bonus. The banner varies:
 *       Hypixel uses RARE DROP!, VERY RARE DROP!, CRAZY RARE DROP!, INSANE DROP!,
 *       UNCOMMON DROP! and PET DROP! for the same shape.</li>
 * </ol>
 *
 * <h2>Order is load-bearing, three times</h2>
 * <p>The treasure shapes are tried first, because a treasure line also satisfies the plain shape and
 * would otherwise yield an item helpfully named "You dug out a". A line that
 * {@link DianaPatterns#isTreasureDig} recognises but this class cannot decompose returns nothing
 * rather than falling through to the drop branches: a missed reel is a nuisance, a reel showing a
 * fragment of an English sentence is a bug report. The bracketed shape is then tried before the
 * plain one for the same reason in miniature -- a bracketed line matches the plain shape too, and
 * yields an item literally named "(". And the sentence shape is tried before the plain one so its
 * count, which lives inside the sentence, is read rather than lost.
 *
 * <h2>What this class deliberately does not do</h2>
 * <p>It does not check that the drop is a Diana drop. The banner shapes are server-wide, so a
 * slayer's Judgement Core parses here too. Gating on island, mayor and recency is the caller's job.
 *
 * <p>It does not own the loot families that carry no banner word from this alternation. TROPHY
 * FISH!, GOOD CATCH!, PRISTINE!, HOPPITY'S HUNT, FLOOR DROP!, CAPTURE!, FUSION!, RARE REWARD!, the
 * indented container reward blocks and the {@code [Sacks]} hover lines each have a dedicated
 * detector under {@code com.skyprism.core.loot}, and several of them are only reachable from a chat
 * <em>component</em> rather than a flat string at all. Widening the alternation to claim them would
 * make one line spin two machines. The boundary is the banner word, and it is deliberate.
 *
 * <h2>Ownership: whose drop is it</h2>
 * <p>Hypixel never rewords this banner family for somebody else's loot -- a party member's drop
 * arrives as a different sentence that names them. {@link #isThirdPartyLine(String)} is the shared
 * answer, and it is checked before any banner pattern runs. It is a delegate to
 * {@link com.skyprism.core.loot.LineOwnership}, which is the mod's single implementation of the
 * ownership rule: it captures the actor written in front of the third-person verb, strips the rank
 * prefix, and compares it to the local player's name. That is the only test that actually separates
 * "Steve has obtained" from the player's own pickup, and it is what this class used to be unable to
 * do. When the name is not yet known -- before login, around a reconnect -- the named third-person
 * shapes are refused instead, which is the old behaviour kept on purpose for that window: a missed
 * roll costs a spin, celebrating five party members' Chimeras costs the feature's credibility.
 *
 * <p>Instances are immutable and stateless; one can be shared across threads.
 */
public final class LootParser {

    /**
     * The banner words Hypixel prints, longest alternative first so the alternation reads in
     * the order a human would check it. Java alternation is ordered, but the leading
     * formatting run cannot skip over letters, so no alternative can ever shadow another
     * here; the ordering is for the reader.
     *
     * <p>{@code UNCOMMON DROP!} is in the list, and its absence used to be a silent half-failure:
     * {@code com.skyprism.core.loot.gathering.BannerLines} -- the yes/no arming test three files
     * away -- has always accepted it, so a detector could fire on a line neither parser could then
     * decompose, and the roll settled on "No Drop". One vocabulary, in one place, is the fix.
     * {@link #isRareBanner(String)} is what keeps UNCOMMON from being flagged as a rare drop.
     *
     * <p>Not in the list, on purpose: {@code RARE CROP!} (the Garden's own banner, one letter away,
     * owned by {@code RareCropDetector}), {@code RARE REWARD!} (a chest broadcast that never places
     * the item after the banner) and {@code FLOOR DROP!} (an attribute-shard sentence). Each would
     * capture something that is not an item name.
     */
    private static final String BANNER_WORDS =
            "VERY RARE DROP!|CRAZY RARE DROP!|INSANE DROP!|UNCOMMON DROP!|RARE DROP!|PET DROP!";

    /** The one banner word in {@link #BANNER_WORDS} that is not a rare drop. */
    private static final String UNCOMMON_BANNER = "UNCOMMON DROP!";

    /** Legacy formatting code characters, both cases, as Hypixel really sends them. */
    private static final String CODE = "[0-9a-fk-orA-FK-OR]";

    /**
     * A colour code and any style codes Hypixel stacked on top of it.
     *
     * <p>A single code group could not read <code>&#167;r&#167;6&#167;lDivan's Alloy</code>: the
     * bold code left a section sign where the pattern needed a letter and the whole match died, so
     * a bold item name was a silent miss. Hypixel does paint names with two codes -- the Hoppity
     * rarity words, the Chocolate Factory's Golden Rabbit, an obfuscated undiscovered name -- and
     * {@code TrophyFishDetector} already carries a {@code (?:&#167;k)?} for exactly this reason.
     */
    private static final String COLOR_RUN = "(?:\u00A7(?<color>" + CODE + ")(?:\u00A7[k-oK-O])*)?";

    /**
     * The formatting run in front of a banner.
     *
     * <p><b>At least one formatting code is required, and that is a security property.</b> This run
     * absorbs the leading <code>&#167;6&#167;l</code> and, crucially, cannot skip over ordinary
     * text -- so a player quoting "RARE DROP! Crown of Greed" in party chat does not match, because
     * "Party " sits between the codes and the banner. Once the reset <em>after</em> the banner
     * became optional (see {@link #BANNER_DROP}), a {@code *} here would have left a bare, entirely
     * unformatted "RARE DROP! Crown of Greed" matching from the very first character -- letting
     * anyone spin anyone else's machine by typing it. The twin parser this class absorbed used
     * {@code *} while its javadoc claimed the anchoring argument verbatim; that is why the corpus
     * now lives in one file.
     *
     * <p><b>Spaces are admitted between and before the codes, but never instead of them.</b>
     * Hypixel really does send <code>&#167;6 &#167;r&#167;6&#167;lGOOD CATCH!</code> and
     * <code>  &#167;r&#167;6&#167;lCHEST LOCKPICKED</code>, so a run that allowed codes only missed
     * whole shapes. The first character after any leading indent must still be a section sign, so
     * an indented line a player typed remains unmatchable.
     *
     * <p>The mixed run is bounded at thirty-two units for the same stack-depth reason given on
     * {@link #MAGIC_FIND}: Java recurses once per iteration of a group repetition, and no real
     * banner prefix is longer than four.
     */
    private static final String LEADING_CODES = "[ ]*\u00A7.(?:[ ]|\u00A7.){0,32}";

    /** A trailing run that is kept rather than discarded, so the Magic Find can be read off it. */
    private static final String TAIL = "(?<tail>[^\\n\\r]*)";

    /**
     * Treasure-burrow item payout. The optional {@code (?: an?)?} covers "dug out a" and
     * "dug out an"; the colour group is optional because SkyHanni's own treasure pattern
     * marks the second section sign optional, so uncoloured rewards exist.
     *
     * <p><b>Why the item group can cross a same-colour reset.</b> The string this matches is
     * reconstructed by {@code LegacyText.toLegacy}, which injects a <code>&#167;r</code> in front
     * of every run after the first -- deliberately, and even between two runs whose styles are
     * identical, because the drop banners need exactly that shape. So any component boundary
     * Hypixel happens to place <em>inside</em> an item name ("Griffin" and "Feather" as two nodes,
     * which is what happens when part of a name carries its own hover or rarity styling) arrives
     * here as <code>Griffin&#167;r&#167;9Feather</code>. A plain {@code [^&#167;]+} group cannot
     * cross that, the match fails outright, and {@link #parse} then hits the treasure guard and
     * returns nothing -- a real drop shown on the reel as "No Drop", with nothing logged.
     * Admitting a repeat of the item's <em>own</em> colour code, by backreference, spans the split
     * while still stopping dead at any run that changes colour, which is what the sentence's
     * closing <code>&#167;r&#167;e!</code> does.
     *
     * <p><b>The reset before the colour is optional, and that part is INFERRED.</b> Every
     * captured Diana treasure line carries it, and {@link DianaPatterns#TREASURE_DUG} still
     * requires it, so this can only rescue a line the parser silently drops today. It is here
     * because the same over-strict reset is what stopped Garden pest drops parsing at all, and
     * a shape nobody has captured is exactly the shape that fails silently.
     */
    private static final Pattern TREASURE_ITEM = Pattern.compile(
            "\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out(?: an?)? "
                    + "(?:\u00A7r)?(?:\u00A7(?<color>" + CODE + "))?"
                    + "(?<item>[^\u00A7]++(?:\u00A7r\u00A7\\k<color>[^\u00A7]++)*)"
                    + "\u00A7r\u00A7e!");

    /**
     * Treasure-burrow coin payout. Hypixel writes the amount with thousands separators
     * ("1,000,000"), which is why the digits group has to admit commas.
     *
     * <p>The group is {@code \\d[\\d,]*} rather than {@code [\\d,]+} on purpose: the looser
     * form also matched a line whose amount was punctuation only (a bare ","), and
     * {@link #parseAmount} then failed to read it and saturated, putting a payout of
     * 2,147,483,647 coins on the reel. A malformed amount has to mean "not a coin line" so
     * it falls through to the treasure guard and produces nothing.
     *
     * <p>The reset before the colour is optional here for the same INFERRED reason given on
     * {@link #TREASURE_ITEM}, with the same guarantee: greedy, so no line that parses today
     * parses differently.
     */
    private static final Pattern TREASURE_COINS = Pattern.compile(
            "\u00A76\u00A7lWow! \u00A7r\u00A7eYou dug out "
                    + "(?:\u00A7r)?(?:\u00A7(?<color>" + CODE + "))?"
                    + "(?<amount>\\d[\\d,]*) coins\u00A7r\u00A7e!");

    /**
     * A trailing stack count, as a separate dark-grey run: <code>Mutant Nether Wart &#167;8x9</code>.
     *
     * <p>{@code \\d[\\d,]{0,12}} rather than {@code [\\d,]{1,13}}: the looser form matched a count
     * that was punctuation only, {@link #parseAmount} then failed to read the empty string and
     * saturated, and the reel showed a stack of 2,147,483,647. The absorbed twin parser had exactly
     * that bug in both of its count groups.
     */
    private static final String TRAILING_COUNT =
            "(?:(?:\u00A7r)?\u00A78x(?<count>\\d[\\d,]{0,12}))?";

    /**
     * The bracketed banner shape: slayer drops, and every combat drop that goes straight to a
     * sack.
     *
     * <p><b>Why this exists at all.</b> Run the plain shape on one of these lines and it
     * matches -- and captures an item literally named {@code "("}, because the item group
     * starts right after the grey colour code and stops at the very next section sign. That is
     * worse than a miss: the reel locks onto an open bracket, no jackpot list can ever match
     * it, and the stats file records it forever. So this pattern is tried first.
     *
     * <p>Captured lines this must handle, with section signs written as {@code &}:
     * <pre>
     * &amp;b&amp;lRARE DROP! &amp;r&amp;7(&amp;r&amp;f&amp;r&amp;9Revenant Viscera&amp;r&amp;7) (+123% Magic Find)
     * &amp;b&amp;lRARE DROP! &amp;r&amp;7(&amp;r&amp;f&amp;r&amp;72x &amp;r&amp;f&amp;r&amp;9Foul Flesh&amp;r&amp;7) (...)
     * &amp;5&amp;lVERY RARE DROP!  &amp;r&amp;7(&amp;r&amp;f&amp;r&amp;5Revenant Catalyst&amp;r&amp;7) (...)
     * &amp;9&amp;lVERY RARE DROP!  &amp;r&amp;7(&amp;r&amp;fMana Steal I&amp;r&amp;7) (...)
     * </pre>
     *
     * <p>The colour run after the <code>&#167;f</code> is optional because the last of those --
     * an enchanted-book drop -- carries no colour at all. Making it mandatory would drop
     * precisely the VERY RARE tier a slot machine exists to celebrate. The count group is the
     * bracketed shape's own stacking form, {@code &r&72x &r&f}, which is not the leading
     * "3x " the plain shape uses. The Magic Find tail on this shape carries no colour code of
     * its own, which is why {@link #MAGIC_FIND} anchors on the words rather than on aqua.
     */
    private static final Pattern BRACKETED_DROP = Pattern.compile(
            LEADING_CODES + "(?<banner>" + BANNER_WORDS + ") {1,2}"
                    + "\u00A7r\u00A77\\((?:\u00A7r)?\u00A7f"
                    + "(?:\u00A7r\u00A77(?<count>\\d[\\d,]{0,12})x \u00A7r\u00A7f)?"
                    + "(?:(?:\u00A7r)?" + COLOR_RUN + ")?"
                    + "(?<item>[^\u00A7\\n\\r]++)"
                    + "\u00A7r\u00A77\\)" + TAIL);

    /**
     * The sentence shape: Garden Crop Fever, whose reward sits inside an English sentence.
     *
     * <p><b>This was a confirmed live corruption in both parsers.</b> Run either of them on
     * <code>&#167;9&#167;lRARE DROP! &#167;r&#167;aYou dropped 48x Enchanted Melon Slice!</code> and
     * the item name came back as the literal string "You dropped 48x Enchanted Melon Slice!" -- a
     * whole English sentence on a reel, recorded in the stats file, matching no jackpot entry ever.
     * The plain shape's {@code (?!You )} lookahead now refuses the sentence outright, and this
     * pattern reads it properly instead, so the reel gets "Enchanted Melon Slice" with a count of 48.
     *
     * <p><b>The count is inside the sentence</b>, before the crop name, which is why neither the
     * leading-multiplier rule nor the trailing dark-grey rule could ever have found it.
     *
     * <p><b>The colour codes here are inferred; the sentence is verified.</b> SkyHanni matches this
     * on the colour-stripped line ({@code CropFeverTracker.kt}, two captured cases), so the wording
     * is certain and the formatting is not. The pattern therefore fixes nothing about the colours --
     * the reset and the colour run are both optional -- while still requiring {@link #LEADING_CODES}
     * in front of the banner. That last part is a deliberate trade: it means the colour-stripped
     * form SkyHanni matches is not claimed here, because admitting it would also admit a player
     * typing "RARE DROP! You dropped 64x Enchanted Diamond!" into party chat.
     */
    private static final Pattern SENTENCE_DROP = Pattern.compile(
            LEADING_CODES + "(?<banner>" + BANNER_WORDS + ") {1,2}"
                    + "(?:\u00A7r)?" + COLOR_RUN
                    + "You dropped (?<count>\\d[\\d,]{0,12})x "
                    + "(?<item>[^\u00A7!\\n\\r]++)!" + TAIL);

    /**
     * The plain banner shape: the overwhelming majority of drops, everywhere in the game.
     *
     * <p><b>Two spaces, not one.</b> Hypixel follows VERY RARE DROP! and CRAZY RARE DROP! with
     * <em>two</em> spaces. SkyHanni encodes that as a literal {@code {2}} in eleven separate
     * patterns, so it is a server quirk rather than a transcription slip, and a hardcoded
     * single space misses exactly the two tiers worth celebrating. {@code " {1,2}"} takes both.
     *
     * <p><b>The reset after the banner is not always sent.</b> Garden pest drops arrive as
     * <code>&#167;6&#167;lRARE DROP! &#167;9Mutant Nether Wart ...</code> with no
     * <code>&#167;r</code> at all, so the reset is optional. It is greedy, so every line that
     * does carry one consumes it exactly as before; and {@link #LEADING_CODES} is what stops
     * the relaxation from admitting unformatted player text.
     *
     * <p><b>{@code (?!You )} is the real sentence guard.</b> The
     * {@link DianaPatterns#isTreasureDig} check in {@link #parse} only catches a treasure
     * line that ends exactly on {@code \u00A7r\u00A7e!}. Give the same line any tail at
     * all -- a trailing magic-find bracket, a truncated final "!" -- and it stopped being a
     * treasure dig, fell through to here, and the banner shape happily reported an item
     * literally named "You dug out a". Crop Fever's "You dropped 48x ..." is the same failure with
     * a different verb, and it was live. The lookahead now refuses <em>any</em> second-person
     * sentence: no Hypixel item name begins with "You " (note that "Young Dragon Boots" is
     * unaffected -- the lookahead requires the space), so the worst case is a missed reel, and the
     * shapes worth reading are read by {@link #TREASURE_ITEM} and {@link #SENTENCE_DROP} above.
     *
     * <p><b>{@code (?!\\()} is the bracketed shape's failure made terminal.</b> A bracketed line
     * satisfies this pattern too, as an item literally named "(" -- the item group starts right
     * after the grey colour code and stops at the very next section sign. {@link #BRACKETED_DROP}
     * runs first and normally claims those, so the hole only opens when the bracketed pattern
     * fails for some <em>other</em> reason: a truncated closing bracket, a malformed count run, a
     * shape Hypixel has not sent yet. The line then fell through to here and the reel locked onto
     * an open bracket -- which no jackpot list can ever match and which {@code DianaStats} then
     * records forever. That is strictly worse than a miss, and it is the same class of corruption
     * the sentence guard above exists for. No item Hypixel names begins with an open bracket, so
     * refusing one costs nothing and turns a wrong reel into an absent one. The catch-all detector
     * on the loot bus still fires on such a line -- it asks {@link #bannerWordOf(String)}, not this
     * pattern -- so the machine still spins, captioned with the source's own name.
     *
     * <p><b>The item group crosses a same-colour reset and nothing else.</b> A component
     * boundary inside an item name reaches this pattern as an injected
     * <code>&#167;r</code> plus the run's own codes -- see the note on {@link #TREASURE_ITEM} --
     * so an item name split across two nodes would otherwise match only up to the split, and this
     * shape fails <em>worse</em> than the treasure one: the trailing group swallows the
     * remainder, so the match still succeeds and the reel locks onto a truncated name that
     * {@code jackpotItems} can never match and {@code DianaStats} then records forever. The
     * continuation is admitted by backreference to the item's own colour.
     *
     * <p><b>...and it stops at a stat bracket even when the colours agree.</b> The backreference
     * used to be the only thing keeping the aqua Magic Find tail out of the name, on the argument
     * that "an aqua tail cannot be mistaken for a continuation of a name Hypixel painted in a
     * rarity colour". That argument is false: DIVINE rarity <em>is</em> aqua, so every DIVINE drop
     * welded its own Magic Find bracket onto its item name -- one whole rarity tier, corrupted, and
     * the very stat this parser now has to report. The {@code (?!\\(\\+)} guard on the continuation
     * is the fix: a run that opens with "(+" is a stat bracket, never the second half of an item
     * name.
     *
     * <p><b>The trailing count.</b> A stack arrives as
     * <code>Mutant Nether Wart &#167;8x9</code> -- a separate dark-grey run after the name, not
     * the leading "9x " the treasure lines use. Left to the tail group it vanished, and a stack
     * of sixteen rendered as one item. It is read here instead. The one shape this cannot serve
     * is a genuinely dark-grey item name split across a component boundary, whose continuation
     * would eat its own {@code x16}; no rarity Hypixel paints is dark grey, so that trade is
     * one-sided.
     *
     * <p><b>The item quantifier is possessive and the trailing group no longer starts with
     * {@code \\s*}.</b> A lazy {@code [^\u00A7]+?} followed by {@code \\s*} lets the two
     * split a run of spaces in every possible way, which is quadratic: a 20,000-character
     * line took 2.3 seconds and a 200,000-character one never finished, on the chat thread.
     * A greedy possessive run to the next section sign is a single deterministic pass, and
     * {@link TextClean#clean} was already trimming the trailing space the {@code \\s*} was
     * there to eat. Line terminators are excluded as well, so a component carrying an
     * embedded newline yields nothing rather than an item name with a second line welded on.
     */
    private static final Pattern BANNER_DROP = Pattern.compile(
            LEADING_CODES + "(?<banner>" + BANNER_WORDS + ") {1,2}"
                    + "(?:\u00A7r)?" + COLOR_RUN
                    + "(?<item>(?!You )(?!\\()[^\u00A7\\n\\r]++"
                    + "(?:\u00A7r\u00A7\\k<color>(?!\\(\\+)[^\u00A7\\n\\r]++)*+)"
                    + TRAILING_COUNT + TAIL);

    /**
     * The Magic Find the server reported for this roll, read off the tail of an already-matched
     * banner and never off a bare chat line.
     *
     * <h2>Why it is applied to the tail only</h2>
     * <p>Magic Find appears on lines that are not drops at all:
     * <code>&#167;a&#167;l+5 Kill Combo &#167;r&#167;8+&#167;r&#167;b3% &#167;r&#167;b* Magic
     * Find</code> is a temporary buff grant with no banner and no parentheses. A free-standing
     * matcher run over chat would fire on it and staple 3% onto whatever drop happened to be on
     * screen. So this pattern is only ever offered the residue of a line that already matched a
     * banner shape.
     *
     * <h2>Every form Hypixel sends, and nothing that merely looks like one</h2>
     * <ul>
     *   <li><code>&#167;r&#167;b(+&#167;r&#167;b168% &#167;r&#167;b* Magic Find&#167;r&#167;b)</code>
     *       -- the dominant shape, colour codes interleaved through the middle of the number.</li>
     *   <li>the same with <b>no percent sign</b>. Hypixel emits both; SkyHanni and Skyblocker each
     *       pin the two as separate cases and both write it {@code %?}. The sign is captured, not
     *       required, and is echoed back exactly as it arrived.</li>
     *   <li><code> (+123% * Magic Find)</code> -- the bracketed/sack form, one unstyled run after
     *       the closing grey bracket with no colour code of its own. A matcher anchored on aqua
     *       misses every sack drop.</li>
     *   <li><code>&#167;r&#167;b(+240% Magic Find!)</code> -- the DUNGEONS form: <b>no icon at all</b>
     *       and a trailing exclamation mark inside the bracket. A pattern that requires the glyph
     *       cannot read Magic Find anywhere in Catacombs, which is where people grind for it.</li>
     *   <li>absent entirely, which is a real answer and not a zero. See {@link LootDrop}.</li>
     * </ul>
     *
     * <p><b>Neither icon codepoint appears in this pattern.</b> Hypixel has already moved the glyph
     * once -- U+272F historically, U+E01A today, and nothing at all on dungeon lines -- so the only
     * durable anchor is the literal words "Magic Find". That is the same standing-liability
     * argument {@code TrophyFishDetector} and {@code PestDropDetector} already make, and it is what
     * lets one pattern read all five forms.
     *
     * <p><b>What it must never claim.</b> Requiring the words is also what keeps the look-alikes
     * out: pet luck <code>&#167;6(&#167;6+1300)</code> with its shamrock, the Garden's bare farming
     * fortune <code>&#167;e(&#167;e+134)</code>, and SkyHanni's own injected "SkyHanni User Luck"
     * line all sit in exactly the same position after exactly the same banner, and none of them
     * says "Magic Find". A rule of "the parenthesised number after the item" would have put 1300 on
     * a jackpot screen as a Magic Find.
     *
     * <p>The filler between the number and the words admits formatting codes and any non-alphanumeric
     * character -- which covers a space, the legacy star and a private-use glyph alike -- and the two
     * alternatives are disjoint (the second excludes the section sign), so the run cannot backtrack
     * exponentially the way a naive {@code (?:a|b)*} of overlapping alternatives would.
     *
     * <p><b>Every repetition here is bounded, and that is a stack-depth property.</b> Java compiles
     * a {@code (?:...)*} over a multi-character group into a recursive matcher: one JVM frame per
     * iteration. An unbounded run therefore overflows the stack -- not slowly, but with a
     * {@code StackOverflowError} out of the chat thread -- on a line carrying twenty thousand
     * separator characters, which a hostile or merely broken component can produce. The real forms
     * need eight characters between the number and the words; sixty-four is generous, and it caps
     * the recursion at sixty-four frames.
     */
    private static final Pattern MAGIC_FIND = Pattern.compile(
            "\\((?:\u00A7.){0,8}\\+(?:\u00A7.){0,8}"
                    + "(?<value>\\d[\\d,]{0,12})(?<pct>%)?"
                    + "(?:\u00A7.|[^\u00A7()\\p{L}\\p{N}]){0,64}"
                    + "Magic Find"
                    + "(?:\u00A7.){0,8}!?(?:\u00A7.){0,8}\\)");

    /**
     * The banner word alone: is this line one of the family, without asking what dropped.
     *
     * <p><b>Why a second pattern rather than a second parser.</b> Two callers outside this class
     * need strictly less than {@link #matchBanner} gives them. {@code BannerLines} answers a
     * yes/no for the sources whose only signal is the banner, and {@code MobRareDropDetector} --
     * the catch-all that makes the feature SkyBlock-wide -- deliberately does not decompose the
     * drop, because a shape this parser cannot read yet should still spin the machine under the
     * source's own caption rather than be lost. Requiring a full decomposition from them would
     * trade a wrong item for no roll at all, which is the wrong direction for a report that says
     * some drops do not fire.
     *
     * <p>So they get the vocabulary and the anchoring, and keep their own strictness. Both used to
     * carry a private copy of the alternation instead -- one of them missing {@code UNCOMMON
     * DROP!}, both of them writing the leading run with a {@code *} that let an entirely
     * unformatted line through. Four encodings of one corpus is how "some drops parse and some do
     * not" happens; this is the third and last of them folded in.
     *
     * <p>{@code .+} only asserts that <em>something</em> followed the banner. Naming it is
     * {@link #matchBanner}'s job.
     */
    private static final Pattern BANNER_WORD_ONLY = Pattern.compile(
            LEADING_CODES + "(?<banner>" + BANNER_WORDS + ") {1,2}[^\\n\\r]+");

    /**
     * A leading multiplier on an item name, as in "3x Enchanted Ancient Claw". Hypixel does
     * not use it for every drop, but it does appear on stacked rewards, and a reel labelled
     * "3x Enchanted Ancient Claw" reads worse than one labelled "Enchanted Ancient Claw"
     * with a count beside it.
     */
    private static final Pattern LEADING_COUNT = Pattern.compile("(?<count>\\d{1,9})x (?<rest>.+)");

    /** The name given to a coin payout so it can occupy a reel like any other reward. */
    public static final String COINS_ITEM_NAME = "Coins";

    /** Which of the five banner shapes a line turned out to be. */
    public enum Shape {
        /** {@code RARE DROP! (item)} -- slayers and everything that lands in a sack. */
        BRACKETED,
        /** {@code RARE DROP! You dropped 48x Crop!} -- Garden Crop Fever. */
        SENTENCE,
        /** {@code RARE DROP! Item} -- everything else. */
        PLAIN
    }

    /**
     * One decomposed banner line: which banner word Hypixel used, what shape it was in, and the
     * reward it announced.
     *
     * <p>The reward is a {@link LootDrop} rather than a second set of name/colour/count fields, so
     * there is exactly one representation of "an item Hypixel announced" in the mod.
     *
     * @param banner the banner word including its exclamation, e.g. {@code "VERY RARE DROP!"}
     * @param shape  which pattern claimed the line
     * @param drop   the reward, with its Magic Find when the line reported one
     */
    public record BannerMatch(String banner, Shape shape, LootDrop drop) {
    }

    /**
     * Parses one chat line.
     *
     * @param rawLine the chat line with its formatting codes intact, may be null
     * @return the rewards the line announced, or an empty list when it announced none.
     *         Hypixel sends one reward per line, so a non-empty result currently always has
     *         exactly one element; the list is the return type so a future multi-reward
     *         line does not force a signature change on every caller.
     */
    public List<LootDrop> parse(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return List.of();
        }

        Matcher coins = TREASURE_COINS.matcher(rawLine);
        if (coins.matches()) {
            return List.of(new LootDrop(
                    COINS_ITEM_NAME,
                    coins.group("color"),
                    parseAmount(coins.group("amount")),
                    false));
        }

        Matcher treasure = TREASURE_ITEM.matcher(rawLine);
        if (treasure.matches()) {
            return single(treasure.group("item"), treasure.group("color"), true, null, null);
        }

        // A treasure line this class could not decompose must not reach the drop branches;
        // see the class javadoc on why order is load-bearing.
        if (DianaPatterns.isTreasureDig(rawLine)) {
            return List.of();
        }

        return matchBanner(rawLine)
                .<List<LootDrop>>map(match -> List.of(match.drop()))
                .orElse(List.of());
    }

    /**
     * Decomposes a raw chat line if, and only if, it is one of this banner family's own drops.
     *
     * <p>This is the single implementation of the banner corpus. {@code RareDropBanner} on the
     * general loot bus is an adapter over this method, and {@link #parse} calls it for everything
     * that is not a Diana treasure dig, so a shape fixed here is fixed everywhere at once.
     *
     * <p>Ownership is checked first, before any pattern runs -- see {@link #isThirdPartyLine}.
     * Diana's two treasure sentences are <em>not</em> handled here: they belong to {@link #parse},
     * which owns them and their ordering, and the {@code (?!You )} guard in {@link #BANNER_DROP}
     * keeps them from leaking into the plain branch even when their own patterns fail.
     *
     * @param rawLine the line as the server sent it, formatting codes intact; may be null
     * @return the decomposed banner, or empty when the line is not one
     */
    public static Optional<BannerMatch> matchBanner(String rawLine) {
        // Every word in the alternation ends in "DROP!", so this rejects the overwhelming
        // majority of chat lines without allocating a Matcher.
        if (rawLine == null || rawLine.indexOf("DROP!") < 0 || isThirdPartyLine(rawLine)) {
            return Optional.empty();
        }

        // Bracketed before plain: a bracketed line matches the plain shape too, as an item
        // named "(". Sentence before plain so its inner count is read rather than lost.
        Matcher bracketed = BRACKETED_DROP.matcher(rawLine);
        if (bracketed.matches()) {
            return build(Shape.BRACKETED, bracketed);
        }

        Matcher sentence = SENTENCE_DROP.matcher(rawLine);
        if (sentence.matches()) {
            return build(Shape.SENTENCE, sentence);
        }

        Matcher banner = BANNER_DROP.matcher(rawLine);
        if (banner.matches()) {
            return build(Shape.PLAIN, banner);
        }
        return Optional.empty();
    }

    /**
     * Whether this line announces somebody else's drop.
     *
     * <p>A delegate, and nothing more. {@link LineOwnership} is the one implementation of the
     * ownership rule in the mod; this method and
     * {@code CombatChatGuards.announcesAnotherPlayer(String)} are its two front doors, so the two
     * layers cannot disagree about whose Chimera just dropped. It used to carry its own four-needle
     * list and the combat guards carried a five-needle one; they had already drifted.
     *
     * <p>Public because the arming detectors ask the same question about the lines they handle
     * themselves.
     *
     * @param rawLine the line as the server sent it, formatting codes intact; may be null
     */
    public static boolean isThirdPartyLine(String rawLine) {
        return LineOwnership.announcesAnotherPlayer(rawLine);
    }

    /**
     * {@link #isThirdPartyLine(String)} against an explicit local username.
     *
     * <p>For a caller that already holds the name, and for tests that must not install global
     * state. A null or blank {@code localPlayer} is the unknown case, which refuses every named
     * third-person shape -- see {@link LineOwnership} for why that asymmetry is deliberate.
     */
    public static boolean isThirdPartyLine(String rawLine, String localPlayer) {
        return LineOwnership.announcesAnotherPlayer(rawLine, localPlayer);
    }

    /**
     * Whether a banner word means the server flagged the drop as rare.
     *
     * <p>Every word in the family does except {@code UNCOMMON DROP!}, which is in the vocabulary so
     * the line can be decomposed at all -- it is what Garden Crop Fever prints for its smaller
     * procs -- but is not something to set off a jackpot flourish for.
     */
    public static boolean isRareBanner(String bannerWord) {
        return bannerWord != null && !UNCOMMON_BANNER.equals(bannerWord);
    }

    /**
     * Which banner word this line opens with, without decomposing what dropped.
     *
     * <p>The cheap half of {@link #matchBanner}, for the two callers that want the vocabulary and
     * the anchoring but must not lose a line whose reward this parser cannot yet name. See
     * {@link #BANNER_WORD_ONLY} for why that is the right trade for them and the wrong one for the
     * reels.
     *
     * <p>Ownership is <em>not</em> checked here. {@code MobRareDropDetector} applies
     * {@code CombatChatGuards}, which asks {@link com.skyprism.core.loot.LineOwnership} the same
     * question this class's {@link #isThirdPartyLine} does and adds the colon guard on top; making
     * this method refuse third-party lines as well would only hide which check is doing the work.
     *
     * @param rawLine the line as the server sent it, formatting codes intact; may be null
     * @return the banner word including its exclamation, or null when the line is not one
     */
    public static String bannerWordOf(String rawLine) {
        if (rawLine == null || rawLine.indexOf("DROP!") < 0) {
            return null;
        }
        Matcher matcher = BANNER_WORD_ONLY.matcher(rawLine);
        return matcher.matches() ? matcher.group("banner") : null;
    }

    /** Whether the line opens with any word in this banner family. */
    public static boolean looksLikeBanner(String rawLine) {
        return bannerWordOf(rawLine) != null;
    }

    /**
     * Reads the Magic Find off the residue of a matched banner line.
     *
     * <p>Package-private and taking an already-isolated tail, so it cannot accidentally be pointed
     * at a whole chat line; that mistake would attach a kill-combo buff's Magic Find to an
     * unrelated drop. See {@link #MAGIC_FIND}.
     *
     * @param tail the part of the line after the item and its count, or null
     * @return the reading, or null when the server reported none
     */
    static LootDrop.MagicFind magicFindIn(String tail) {
        if (tail == null || tail.indexOf("Magic Find") < 0) {
            return null;
        }
        Matcher matcher = MAGIC_FIND.matcher(tail);
        if (!matcher.find()) {
            return null;
        }
        return new LootDrop.MagicFind(
                parseMagicFind(matcher.group("value")),
                matcher.group("pct") != null);
    }

    /** Builds the result of one matched banner shape. */
    private static Optional<BannerMatch> build(Shape shape, Matcher matcher) {
        String bannerWord = matcher.group("banner");
        List<LootDrop> drop = single(
                matcher.group("item"),
                matcher.group("color"),
                isRareBanner(bannerWord),
                matcher.group("count"),
                magicFindIn(matcher.group("tail")));
        return drop.isEmpty()
                ? Optional.empty()
                : Optional.of(new BannerMatch(bannerWord, shape, drop.get(0)));
    }

    /**
     * Builds the single-element result, resolving the stack size and normalising the name
     * through {@link TextClean#clean} so a stray double space cannot stop the name matching a
     * configured jackpot entry.
     *
     * <p>A count can reach this from either end of the name -- a leading "3x " inside the item
     * text, or a separate trailing run the pattern captured -- and a line could carry both. The
     * leading one wins, because it is the one Hypixel writes into the name the player reads.
     * They are never multiplied together: a wrong count on a reel is a wrong answer, and
     * guessing that two independent counts compose is exactly the kind of guess that produces
     * one.
     *
     * @param suffixCount the trailing {@code xN} run, or null when the line carried none
     * @param magicFind   the reported Magic Find, or null when the line reported none
     */
    private static List<LootDrop> single(String rawItem, String color, boolean rare,
                                         String suffixCount, LootDrop.MagicFind magicFind) {
        String name = TextClean.clean(rawItem);
        int count = suffixCount == null ? 1 : parseAmount(suffixCount);

        Matcher stacked = LEADING_COUNT.matcher(name);
        if (stacked.matches()) {
            count = parseAmount(stacked.group("count"));
            name = TextClean.clean(stacked.group("rest"));
        }

        if (name.isEmpty()) {
            return List.of();
        }
        return List.of(new LootDrop(name, color, count, rare, magicFind));
    }

    /**
     * Reads a possibly comma-grouped amount, saturating instead of throwing. A coin payout
     * cannot currently overflow an int, but the digit group is unbounded and a chat handler
     * is the wrong place to discover that: saturating keeps the reel spinning on a line
     * that would otherwise take down the chat pipeline.
     */
    private static int parseAmount(String digits) {
        String bare = digits.replace(",", "");
        try {
            long value = Long.parseLong(bare);
            return (int) Math.min(Math.max(value, 1L), Integer.MAX_VALUE);
        } catch (NumberFormatException tooBig) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The same, but a Magic Find of zero is a real reading rather than a floor to clamp away.
     * A player with no Magic Find at all still gets "(+0% Magic Find)" on the line, and reporting
     * that as 1% would be a small invented fact on the one screen that exists to state a fact.
     */
    private static int parseMagicFind(String digits) {
        try {
            long value = Long.parseLong(digits.replace(",", ""));
            return (int) Math.min(Math.max(value, 0L), Integer.MAX_VALUE);
        } catch (NumberFormatException tooBig) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Case-insensitive, whitespace-normalised form of an item name, so
     * {@link JackpotRule} and this parser agree on what "the same item" means.
     */
    static String normalise(String itemName) {
        String cleaned = TextClean.clean(itemName);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }
}
