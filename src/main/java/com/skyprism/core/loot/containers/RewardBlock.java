package com.skyprism.core.loot.containers;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * The grammar of Hypixel's container reward block, as a pure function of one line.
 *
 * <h2>The single most reusable shape in the whole feature</h2>
 * <p>Almost every "you opened a thing, here is what was inside" event in SkyBlock is printed as the
 * same multi-line block: a rule of sixty-four {@code ▬}, a bold header naming the source, sometimes
 * a {@code REWARDS} sub-header, one four-space-indented line per drop, and the rule again. The
 * wrapper colour changes with the source and the shape never does. Six sources share it -- a
 * lockpicked treasure chest, a structure loot chest, a Glacite corpse, a fossil excavation and a
 * finished Nucleus run among them -- so it is worth exactly one reader, not six.
 *
 * <h2>Why no detector in this package consumes it</h2>
 * <p>This is the honest part, and it is a constraint of the bus rather than a choice. A detector is
 * only offered lines that contain one of its declared {@link
 * com.skyprism.core.loot.SourceDetector#chatMarkers() chat markers}, and those markers come from the
 * registry: {@code "CHEST LOCKPICKED"}, {@code "LOOT CHEST COLLECTED"}, {@code "EXCAVATION
 * COMPLETE"}. A block's <em>item</em> lines contain none of them, so the pre-filter -- correctly,
 * and by design -- never routes them here. Widening a detector's markers to catch them would widen
 * the bus's filter for every line of chat, which is the cost the pre-filter exists to avoid.
 *
 * <p>So the container detectors fire on the <b>header</b>, immediately, and the item lines are the
 * loot path's business. That is not a compromise: it is what the shipped Diana path already does and
 * what a slot machine should do anyway. The reels start spinning when the chest opens, while the
 * payout is still unknown, and land on the drops as they arrive. Starting the spin after the loot is
 * already on screen would be a re-reveal, which is the exact criticism the research levels at
 * rolling on a dungeon chest GUI.
 *
 * <p>This class is therefore public API for whoever owns that loot path -- the component that sees
 * every line, not the filtered subset -- and is kept here because the patterns belong with the other
 * container patterns and because it can be unit tested on a bare JVM.
 */
public final class RewardBlock {

    private RewardBlock() {
    }

    /**
     * Whether this line is a block rule, opening or closing.
     *
     * <p>Pre-filtered by length before the regex: a real rule is sixty-four identical glyphs plus a
     * colour run, so anything shorter than sixty-four characters cannot be one and is rejected
     * without allocating a matcher.
     */
    public static boolean isEdge(String rawLine) {
        if (rawLine == null || rawLine.length() < 64 || rawLine.indexOf('▬') < 0) {
            return false;
        }
        return ContainerPatterns.BLOCK_EDGE.matcher(rawLine).matches();
    }

    /**
     * The item named on a block reward line, with its count folded away.
     *
     * <p>Rejects on the four-space indent before the regex, which is the cheapest possible test and
     * removes every ordinary chat line in one comparison.
     *
     * @return the item name, or empty when this is not a reward line
     */
    public static Optional<String> itemOn(String rawLine) {
        if (rawLine == null || rawLine.length() < 6 || !startsWithIndent(rawLine)) {
            return Optional.empty();
        }
        Matcher matcher = ContainerPatterns.BLOCK_ITEM.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String item = ContainerText.itemCaption(matcher.group("item"));
        return item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    /**
     * The stack count on a block reward line, or {@code 1} when the line carries none.
     *
     * <p>Returns {@code 1} rather than throwing on an unparseable count: Hypixel writes these with
     * thousands separators and a malformed one has to mean "one of these", not "abort the block".
     */
    public static int countOn(String rawLine) {
        if (rawLine == null || !startsWithIndent(rawLine)) {
            return 1;
        }
        Matcher matcher = ContainerPatterns.BLOCK_ITEM.matcher(rawLine);
        if (!matcher.matches()) {
            return 1;
        }
        String amount = matcher.group("amount");
        if (amount == null) {
            return 1;
        }
        long total = 0;
        for (int i = 0; i < amount.length(); i++) {
            char c = amount.charAt(i);
            if (c == ',') {
                continue;
            }
            total = total * 10 + (c - '0');
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return total <= 0 ? 1 : (int) total;
    }

    private static boolean startsWithIndent(String line) {
        return line.length() > 4
                && line.charAt(0) == ' ' && line.charAt(1) == ' '
                && line.charAt(2) == ' ' && line.charAt(3) == ' '
                && line.charAt(4) != ' ';
    }
}
