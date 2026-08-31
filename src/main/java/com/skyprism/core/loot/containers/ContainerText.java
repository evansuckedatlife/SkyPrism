package com.skyprism.core.loot.containers;

import com.skyprism.core.util.TextClean;

/**
 * The two string tidies the container detectors need, kept out of the detectors so they can be
 * tested against the awkward real cases rather than the tidy imagined ones.
 *
 * <p>Neither is a general-purpose utility and neither should grow into one. They exist because two
 * specific Hypixel habits leak into captions: it prints a rank prefix in front of a player name in a
 * broadcast, and it prints an item's stack count inside the same capture group as the item's name.
 */
public final class ContainerText {

    private ContainerText() {
    }

    /**
     * Reduces a broadcast's player field to the bare username.
     *
     * <p>Hypixel writes the name with its rank prefix attached -- {@code "[MVP+] Leebys"} once the
     * formatting is gone -- and sometimes with a guild tag after it. The username is the last
     * whitespace-separated token that is not itself bracketed, which handles the prefix, the
     * prefixless default rank, and a trailing tag, without needing a list of every rank Hypixel
     * sells.
     *
     * @param raw the captured player field, formatted or not; may be null
     * @return the bare username, or {@code ""} when nothing usable was found
     */
    public static String playerName(String raw) {
        String clean = TextClean.clean(raw);
        if (clean == null || clean.isEmpty()) {
            return "";
        }
        String best = "";
        int from = 0;
        while (from <= clean.length()) {
            int space = clean.indexOf(' ', from);
            int end = space < 0 ? clean.length() : space;
            if (end > from) {
                char first = clean.charAt(from);
                if (first != '[' && first != '<') {
                    best = clean.substring(from, end);
                }
            }
            if (space < 0) {
                break;
            }
            from = space + 1;
        }
        return best;
    }

    /**
     * Reduces a captured loot field to the item name a reel and a jackpot list can match.
     *
     * <p>Strips the formatting, drops a trailing {@code x12} stack count -- Hypixel puts the count
     * inside the loot group on a Metal Detector line, so {@code "☘ Flawed Jade Gemstone x2"} arrives
     * as one string -- and drops a leading glyph token such as the gemstone symbol.
     *
     * <p>The glyph is dropped rather than matched. Those symbols are private-use codepoints that
     * Hypixel has already moved once, so anything that depends on their exact value is a standing
     * liability; {@code LootParser} makes the same argument about the Magic Find icon and solves it
     * the same way. What survives is the plain-English name, which is what the registry's jackpot
     * lists are written in.
     *
     * @param raw the captured loot field; may be null
     * @return the item name, or {@code ""} when nothing usable was found
     */
    public static String itemCaption(String raw) {
        String clean = TextClean.clean(raw);
        if (clean == null || clean.isEmpty()) {
            return "";
        }
        clean = stripTrailingCount(clean);
        clean = stripLeadingGlyph(clean);
        return clean.strip();
    }

    /** Removes a trailing {@code " x123"} or {@code " x1,234"} stack count. */
    private static String stripTrailingCount(String text) {
        int space = text.lastIndexOf(' ');
        if (space < 0 || space + 2 >= text.length() || text.charAt(space + 1) != 'x') {
            return text;
        }
        for (int i = space + 2; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < '0' || c > '9') && c != ',') {
                return text;
            }
        }
        return text.substring(0, space);
    }

    /**
     * Removes a leading single-character token that is not a letter or digit.
     *
     * <p>Only ever removes a whole token, so an item whose name genuinely begins with punctuation
     * loses nothing, and only ever removes one, so a name is never eaten by a runaway loop.
     */
    private static String stripLeadingGlyph(String text) {
        int space = text.indexOf(' ');
        if (space != 1 || space + 1 >= text.length()) {
            return text;
        }
        char first = text.charAt(0);
        return Character.isLetterOrDigit(first) ? text : text.substring(space + 1);
    }
}
