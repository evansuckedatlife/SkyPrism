package com.skyprism.core.loot.gathering;

import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines inside a Galatea tree gift block, as pure functions.
 *
 * <h2>Why the reward parsers exist even though no detector fires on them</h2>
 * <p>A tree gift is a block: a bold separator opens it, a header names it, a contribution line says
 * how much of the tree you felled, a "+N rewards gained!" line summarises it, and the bonus
 * sub-block lists any bonus drops one per line with their odds printed in brackets. Only the two
 * header lines carry a registry chat marker, so only the two header lines are reachable through the
 * filtered bus; the detectors therefore fire on those, and {@link TreeGiftDetector} says so.
 *
 * <p>The rest is parsed here anyway, tested here, and used by nothing yet, which is a deliberate
 * choice rather than dead code. The bonus reward line is the single most future-proof signal found
 * anywhere in this feature: <b>Hypixel prints the drop rate on the line</b>. "Tree the Fish
 * (0.05%)" and "Stretching Sticks (20%)" carry their own rarity, so the jackpot decision can be a
 * numeric threshold on a captured group instead of a hard-coded item list -- the only rule in the
 * whole feature that cannot go stale when Hypixel adds an item. The moment something can read the
 * block's body (the bus running unfiltered, or a hover reader for the gift's item list), {@link
 * #isJackpotOdds(double)} turns that into a celebration with no list to maintain.
 *
 * <p>Every pattern below is transcribed from SkyHanni ForagingTrackerLegacy.kt
 * (foraging.treegift.*), each one sitting beside its own captured game lines. Section signs are
 * written as unicode escapes here and as a bracketed letter in the javadoc samples, so the file's
 * encoding cannot change what the patterns mean.
 */
public final class TreeGiftLines {

    /** A bonus reward: the item as printed, and the drop chance Hypixel put beside it. */
    public record BonusReward(String item, double percentage) {
    }

    /**
     * Below this percentage a bonus drop is worth the three-of-a-kind flourish.
     *
     * <p>One percent sits in a real gap in the printed odds rather than at a round number chosen
     * for looking tidy: the captured lines run 20% (Stretching Sticks) and 1% (Sweep Booster) on
     * one side, then 0.5% (Foraging Wisdom Booster), 0.4%, 0.2%, 0.08% (Chameleon), 0.05% (Tree the
     * Fish) and 0.02% (Karma I) on the other. Anything under one percent is a drop a forager would
     * screenshot.
     */
    public static final double JACKPOT_ODDS_THRESHOLD = 1.0d;

    /** Captured: 32 spaces, then (r)(9)(l) then "TREE GIFT". */
    private static final Pattern GIFT_HEADER = Pattern.compile(" *(?:\u00A7.)+TREE GIFT");

    /** Captured: 32 spaces, then (r)(d)(l) then "BONUS GIFT". */
    private static final Pattern BONUS_HEADER = Pattern.compile(" *(?:\u00A7.)+BONUS GIFT");

    /**
     * The contribution line, which is the only place the tree type appears.
     *
     * <p>Captured, with section signs shown as bracketed letters: "(r)(7)You helped cut (r)(a)100%
     * (r)(7)of the (r)(a)Fig Tree(r)(7)." Verified tree types: Fig, Mangrove, Helix.
     */
    private static final Pattern CONTRIBUTION = Pattern.compile(
            " *(?:\u00A7.)+You helped cut (?:\u00A7.)+(?<percentage>[\\d.]+)% "
                    + "(?:\u00A7.)+of the (?:\u00A7.)+(?<type>.*) Tree(?:\u00A7.)+\\.");

    /**
     * One bonus drop and its odds.
     *
     * <p>Captured: "(r)(7)(r)(c)Tree the Fish (r)(8)[(r)(a)0.05%(r)(8)]" where the square brackets
     * are really round ones. The reference mod's own negative test for this pattern is the phantom
     * line, which is why {@link TreePhantomDetector} can be a separate source without the two
     * fighting over one line.
     */
    private static final Pattern BONUS_REWARD = Pattern.compile(
            " *(?:\u00A7.)*\u00A7r(?<item>.*) \u00A7r\u00A78"
                    + "\\((?:\u00A7.)+(?<percentage>[\\d.]+)%(?:\u00A7.)+\\)");

    /**
     * An enchanted book bonus, whose name lives inside the brackets.
     *
     * <p>Captured: "(a)Enchanted Book [(r)(d)(l)First Impression I(r)(a)]" and "(f)Enchanted Book
     * [Karma I(r)(f)]", square brackets again standing in for round ones. Worth its own pattern
     * because the generic item group otherwise yields a string with formatting codes buried in the
     * middle of it.
     */
    private static final Pattern ENCHANTED_BOOK = Pattern.compile(
            " *(?:\u00A7.)*Enchanted Book \\((?:\u00A7.)*(?<book>.*) (?<tier>[IVXLC]+)(?:\u00A7.)*\\)");

    private TreeGiftLines() {
    }

    /** Whether this is the "TREE GIFT" header that opens an ordinary gift block. */
    public static boolean isGiftHeader(String rawLine) {
        return rawLine != null && GIFT_HEADER.matcher(rawLine).matches();
    }

    /** Whether this is the "BONUS GIFT" sub-header, which only prints when a bonus rolled. */
    public static boolean isBonusGiftHeader(String rawLine) {
        return rawLine != null && BONUS_HEADER.matcher(rawLine).matches();
    }

    /** The tree type from a contribution line: "Fig", "Mangrove", "Helix". */
    public static Optional<String> treeType(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher matcher = CONTRIBUTION.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String type = TextClean.clean(matcher.group("type"));
        return type.isEmpty() ? Optional.empty() : Optional.of(type);
    }

    /** One bonus drop, with the odds Hypixel printed beside it. */
    public static Optional<BonusReward> bonusReward(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher matcher = BONUS_REWARD.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        double percentage;
        try {
            percentage = Double.parseDouble(matcher.group("percentage"));
        } catch (NumberFormatException e) {
            // The group is [\d.]+, so "1.2.3" reaches here. A malformed number has to mean "not a
            // reward line" rather than a reward whose odds are a saturated guess.
            return Optional.empty();
        }
        String item = nameOf(matcher.group("item"));
        return item.isEmpty() ? Optional.empty() : Optional.of(new BonusReward(item, percentage));
    }

    /** Whether a printed drop chance is rare enough to deserve the celebration. */
    public static boolean isJackpotOdds(double percentage) {
        return percentage > 0.0d && percentage < JACKPOT_ODDS_THRESHOLD;
    }

    /**
     * The book name inside an enchanted book reward, e.g. "First Impression I".
     *
     * @param rawItem the item as the reward line printed it, codes intact
     */
    public static Optional<String> enchantedBook(String rawItem) {
        if (rawItem == null) {
            return Optional.empty();
        }
        Matcher matcher = ENCHANTED_BOOK.matcher(rawItem);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String book = TextClean.clean(matcher.group("book"));
        return book.isEmpty() ? Optional.empty() : Optional.of(book + " " + matcher.group("tier"));
    }

    /** Names a bonus drop, preferring the book name when the item is an enchanted book. */
    private static String nameOf(String itemGroup) {
        return enchantedBook(itemGroup)
                .map(book -> "Enchanted Book (" + book + ")")
                .orElseGet(() -> TextClean.clean(itemGroup));
    }
}
