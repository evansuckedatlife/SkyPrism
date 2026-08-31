package com.skyprism.core.loot;

import java.util.Objects;

/**
 * "Something that could have paid out just happened."
 *
 * <p>This is the whole interface between the twenty-odd things SkyBlock can roll a die on and the
 * one machine that dramatises them. A detector decides <em>that</em> an event occurred and what to
 * call it; everything after this point -- the reels, the loot window, the jackpot flourish -- is
 * source-agnostic and has been since {@code LootDrop} was written.
 *
 * <h2>subject</h2>
 * <p>The caption. It is what produced the payout, in the player's own vocabulary: "Minos
 * Inquisitor", "Voidgloom Seraph IV", "Obsidian Chest", "Blue Shark", "Vanguard Corpse", "Superior
 * Dragon". It is not the item, and it is not the source's display name -- {@link LootSource}
 * already supplies that, and a widget reading "Slayer Boss" where it could have read "Voidgloom
 * Seraph IV" has thrown away the only part the player cares about. Where a detector genuinely
 * cannot name the subject (a rare mob drop has no kill line to name the mob), the source display
 * name is the honest fallback, which is what {@link #of(LootSource, long)} builds.
 *
 * <p>The subject is trimmed and length-capped on construction. It arrives from a regex capture on a
 * server-controlled string, and a caption is drawn into a fixed-width widget: an unbounded name from
 * a party message is a layout bug at best. Detectors are separately expected to match subjects
 * against a closed set rather than accepting an arbitrary capture, for the reason {@code
 * DianaPatterns} already documents -- a boss-down banner is a line another player can cause.
 *
 * @param source   which activity produced this; never null
 * @param subject  the caption, e.g. "Minos Inquisitor"; never null, never blank after normalisation
 * @param atMillis the instant the trigger was observed, on the same clock the roll uses
 */
public record LootEvent(LootSource source, String subject, long atMillis) {

    /** Longest caption kept. Comfortably past the longest real subject ("Barbarian Duke X"). */
    public static final int MAX_SUBJECT_LENGTH = 64;

    public LootEvent {
        Objects.requireNonNull(source, "source");
        subject = normalise(subject, source);
    }

    /**
     * An event whose subject is the source's own display name.
     *
     * <p>For the sources that genuinely have nothing more specific to say: a rare mob drop, a
     * treasure catch, a pest drop. Better than an empty caption and much better than inventing one.
     */
    public static LootEvent of(LootSource source, long atMillis) {
        return new LootEvent(source, LootSourceRegistry.displayName(source), atMillis);
    }

    /** An event with an explicit caption. */
    public static LootEvent of(LootSource source, String subject, long atMillis) {
        return new LootEvent(source, subject, atMillis);
    }

    /** The source's configured caption, e.g. "Slayer Boss", independent of {@link #subject()}. */
    public String sourceDisplayName() {
        return LootSourceRegistry.displayName(source);
    }

    /** The researched default policy for this event's source. */
    public RollPolicy defaultPolicy() {
        return LootSourceRegistry.info(source).defaultPolicy();
    }

    private static String normalise(String subject, LootSource source) {
        if (subject == null) {
            return LootSourceRegistry.displayName(source);
        }
        String trimmed = subject.trim();
        if (trimmed.isEmpty()) {
            return LootSourceRegistry.displayName(source);
        }
        return trimmed.length() <= MAX_SUBJECT_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_SUBJECT_LENGTH).trim();
    }
}
