package com.skyprism.core.loot.gathering;

import com.skyprism.core.diana.LootParser;

/**
 * "Is this line a server rare-drop banner at all?" -- nothing more.
 *
 * <h2>Why this is not the drop parser, and no longer a second copy of it either</h2>
 * <p>What a couple of detectors in this package need is strictly less than a decomposed drop: a
 * yes-or-no on whether Hypixel flagged the line as a rare drop, so a source whose loot is announced
 * only by the universal banner has something to fire on. That question stays here, because it is
 * this package's policy question. The <em>vocabulary</em> and the <em>anchoring</em> do not: they
 * are now {@link LootParser#looksLikeBanner(String)}, which is the one place the banner corpus
 * lives.
 *
 * <p>This class used to spell the alternation out for itself, and the drift was measurable. Its
 * copy accepted {@code UNCOMMON DROP!} when the drop parser did not, so a detector could fire on a
 * line nothing could then decompose and the roll settled on "No Drop" -- a feature that looks like
 * it works and does not. Its prefix also admitted any run of non-alphanumerics, so a completely
 * unformatted "RARE DROP! Hunter Ring" matched from the first character. Both are gone with the
 * copy.
 *
 * <h2>The anchoring, which is the whole safety argument</h2>
 * <p>{@link LootParser} requires at least one formatting code in front of the banner and matches
 * from the start of the line. A player typing "RARE DROP! Hunter Ring" into chat reaches the client
 * as their name, a colon and then their text, and a name is neither a code nor a space -- so it
 * cannot match. That is the same rule {@code DianaPatterns} documents for boss lines, and it
 * matters more here, because the sources that use this are the ones whose only signal is a line
 * anybody can type.
 *
 * <p>The banner vocabulary is the verified one: RARE, VERY RARE, CRAZY RARE, INSANE, UNCOMMON and
 * PET, each followed by "DROP!". Note what is <em>not</em> in it -- "RARE CROP!", the Garden's own
 * banner, which differs by one letter and belongs to {@link RareCropDetector}.
 */
public final class BannerLines {

    /**
     * The Diana treasure dig, which wears the same banner and is emphatically not ours.
     *
     * <p>"§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!" is a burrow payout, and Diana
     * burrows spawn on the Hub and the Farming Islands -- the Farming Islands being exactly where
     * the trapper detector is armed. Without this guard, digging a burrow next to Trevor would
     * caption a Diana treasure as a trapper drop and spin a second machine for an event Diana
     * already owns. The shipped Diana parser guards the same sentence for the same reason.
     *
     * <p>Kept here rather than folded into {@link LootParser#looksLikeBanner(String)}: that method
     * answers "is this the family", and a treasure dig genuinely is. Whose line it is, is this
     * package's question.
     */
    private static final String DIANA_TREASURE_SENTENCE = "You dug out";

    private BannerLines() {
    }

    /**
     * Whether {@code rawLine} opens with a server rare-drop banner that belongs to nobody in
     * particular.
     *
     * @param rawLine the chat line with its formatting codes intact; null is not a banner
     */
    public static boolean isRareDropBanner(String rawLine) {
        if (rawLine == null || rawLine.indexOf(DIANA_TREASURE_SENTENCE) >= 0) {
            return false;
        }
        return LootParser.looksLikeBanner(rawLine);
    }
}
