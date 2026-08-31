package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A GOOD, GREAT or OUTSTANDING treasure catch -- the Treasure Chance stat paying out while fishing.
 *
 * <h2>Why ON_JACKPOT_ITEM_ONLY and not a tier split</h2>
 * <p>Treasure Chance reaches the fifties with good gear, so a GOOD CATCH lands on a large minority
 * of every catch -- tens an hour at least, and the coin form is near-continuous. The bulk of them
 * are coins and bait, which are worth nothing as a spin. So the celebration is keyed on the item:
 * the pet form and the shard form are the ones players care about, and both name the item in the
 * trigger line, which is exactly what a jackpot list keys on.
 *
 * <p>The cleaner design would have been to restrict this detector to the OUTSTANDING tier and
 * default it to ALWAYS, mirroring the trophy fish split. That is deliberately not shipped: no cited
 * rate for the OUTSTANDING tier could be found in either reference mod or on the wiki, and a tier
 * split whose rarest rung might be a third of catches is a guess dressed as a design. The tier is
 * matched and could be carried, so if it is ever measured in play the split is a small change.
 *
 * <h2>Four shapes, one banner</h2>
 * <p>All four are verified, and all four are matched here against the cleaned line, because the
 * only thing they share is the banner and Hypixel colours the payload differently in each:
 * <ul>
 *   <li>coins -- "GOOD CATCH! You caught 36,064 Coins!" (SkyHanni FishingProfitTracker.kt)</li>
 *   <li>pet -- "GREAT CATCH! You caught a [Lvl 1] Squid!" (RareDropMessages.kt, pet.fishedmessage)</li>
 *   <li>shard -- "GOOD CATCH! You caught Water Snake Shard x3!" and the singular "You caught a
 *       Water Snake Shard!" (AttributeShardsData.kt)</li>
 *   <li>bait or item -- "GOOD CATCH! You found a Fish Bait." (ChatFilter.kt; note "found", and a
 *       full stop rather than a bang)</li>
 * </ul>
 *
 * <h2>The prefix guard</h2>
 * <p>Every one of these lines begins with the private-use Treasure Chance glyph, U+E025. Matching
 * that codepoint literally is the standing liability this codebase already refuses to take on, so
 * the pattern instead allows at most a few leading characters that are neither letters, digits nor
 * spaces. A glyph passes. A resource pack's replacement glyph passes. "PlayerName:" does not,
 * because it contains letters -- which is what stops a player from quoting the banner into somebody
 * else's widget.
 */
public final class TreasureCatchDetector extends RegistryDetector {

    /**
     * The banner and its tier. The leading class is the icon-agnostic guard described above: up to
     * three characters that cannot spell a player name, then optional space, then the tier word.
     */
    private static final Pattern CATCH = Pattern.compile(
            "^[^\\p{L}\\p{N}\\s]{0,3}\\s*(?<tier>GOOD|GREAT|OUTSTANDING) CATCH! (?<what>.+)$");

    /** "You caught 36,064 Coins!" -- modelled as an item named Coins, as the Diana parser does. */
    private static final Pattern COINS = Pattern.compile("You caught (?<amount>[\\d,]+) Coins!");

    /** "You caught a [Lvl 1] Squid!" -- the level marker is what makes this a pet rather than loot. */
    private static final Pattern PET = Pattern.compile("You caught an? \\[Lvl 1] (?<pet>[^!]+)!");

    /** "You caught Water Snake Shard x3!" and "You caught a Water Snake Shard!" */
    private static final Pattern SHARD = Pattern.compile(
            "You caught(?: an?)? (?<shard>.+? Shard)(?: x(?<amount>\\d+))?!");

    /** "You found a Fish Bait." -- note the full stop; this is the only shape that does not bang. */
    private static final Pattern FOUND = Pattern.compile("You found an? (?<item>[^.!]+)[.!]");

    public TreasureCatchDetector() {
        super(LootSource.FISHING_TREASURE);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("CATCH!") < 0) {
            return Optional.empty();
        }
        Matcher banner = CATCH.matcher(TextClean.clean(rawLine));
        if (!banner.matches()) {
            return Optional.empty();
        }
        String subject = subjectOf(banner.group("what"));
        return subject == null ? Optional.empty() : Optional.of(event(subject, nowMillis));
    }

    /**
     * The caption: what was actually caught, so the jackpot list has a name to match.
     *
     * <p>The pet branch runs before the shard branch and both before the generic "found" branch,
     * whose item group is the loosest of the four; the coin branch cannot collide with any of them.
     */
    private static String subjectOf(String what) {
        Matcher pet = PET.matcher(what);
        if (pet.matches()) {
            return pet.group("pet").trim();
        }
        Matcher shard = SHARD.matcher(what);
        if (shard.matches()) {
            return shard.group("shard").trim();
        }
        Matcher coins = COINS.matcher(what);
        if (coins.matches()) {
            return coins.group("amount") + " Coins";
        }
        Matcher found = FOUND.matcher(what);
        if (found.matches()) {
            return found.group("item").trim();
        }
        // A shape nobody has captured yet. Returning null means "not a treasure catch I can name",
        // which loses a spin; inventing a caption out of the tail of an English sentence is the
        // failure the Diana parser's javadoc already argues against at length.
        return null;
    }
}
