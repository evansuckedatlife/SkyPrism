package com.skyprism.core.loot.events;

import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.LootParser;

import java.util.Optional;

/**
 * The universal rare-drop banner, decomposed into an item, for the general loot bus.
 *
 * <p>This is the single highest-leverage pattern in the whole feature: Hypixel prints one banner
 * family across nearly all content, so one matcher covers every rare mob drop in the game and is
 * what makes "the slot machine everywhere" tractable without a listener per mob.
 *
 * <h2>This class no longer owns a copy of that matcher</h2>
 * <p>It used to. {@code com.skyprism.core.diana.LootParser} and this class independently encoded the
 * same banner corpus, and the javadoc here claimed the Diana parser was "deliberately left exactly
 * as it is". Two implementations of one corpus drift, and these two had: a bare unformatted line
 * matched here and not there, a comma-only count produced a stack of 2,147,483,647 here and a stack
 * of one there, and a Crop Fever sentence became an item name in both but by different routes. That
 * divergence <em>is</em> the "some drops parse and some do not" the report described.
 *
 * <p>So the corpus now lives in {@link LootParser} -- once -- and this class is the adapter the
 * loot bus's five detectors already call. It contributes exactly two things of its own: the
 * {@link Banner} shape those detectors read, and the policy decision below about sentence-form
 * drops. Everything else is delegation.
 *
 * <h2>The one policy this class adds: sentence drops do not arm a source</h2>
 * <p>{@link LootParser} reads Garden Crop Fever's "RARE DROP! You dropped 48x Enchanted Melon
 * Slice!" properly now, because those items belong on the reels. They must not each <em>fire</em> a
 * source: a fever window prints many of them in sixty seconds, and
 * {@code CropFeverDetector} already documents that rolling on each one would turn a single event
 * into a strobe. The fever start is the roll; the drops inside it are loot. So {@link #match}
 * filters the sentence shape out, and says so here rather than by keeping a second regex that
 * cannot see it.
 *
 * <h2>Anchoring is the security property, and it is now real</h2>
 * <p>Every match uses {@code matches()}, never {@code find()}, and the leading run requires at least
 * one formatting code, so it can absorb <code>&#167;6&#167;l</code> but cannot skip over ordinary
 * text. Together those mean a player typing "RARE DROP! Crown of Greed" into party chat cannot spin
 * anybody else's widget, because their name and a colon sit between the codes and the banner. The
 * copy this class used to carry wrote that run with a {@code *} while its javadoc made this exact
 * argument, so an entirely unformatted line matched it; delegating is what fixed that.
 *
 * <p>Ownership is a separate question, answered by {@link #isThirdPartyLine(String)}: Hypixel does
 * not change the banner wording for a party member's drop, it changes the <em>sentence</em>, so the
 * reliable tells are third-person phrases rather than anything in the banner itself.
 */
public final class RareDropBanner {

    private RareDropBanner() {
    }

    /**
     * One decomposed banner line.
     *
     * <p>The reward is a {@link LootDrop} rather than a parallel set of name/colour/count fields, so
     * the loot bus and the reels share one representation of "an item Hypixel announced" -- and so
     * the Magic Find the banner reported reaches a caller through the same object the reel already
     * carries, instead of needing a second channel.
     *
     * @param banner the banner word including its exclamation, e.g. {@code "VERY RARE DROP!"}
     * @param drop   the reward, formatting stripped, with its Magic Find when the line had one
     */
    public record Banner(String banner, LootDrop drop) {

        /** The item name, formatting stripped and whitespace collapsed. */
        public String item() {
            return drop.itemName();
        }

        /** The item's legacy colour code as a one-character string, or {@code null}. */
        public String color() {
            return drop.colorCode();
        }

        /** The stack size, at least 1. */
        public int count() {
            return drop.count();
        }

        /**
         * The Magic Find the server reported for this roll, or {@code null} when it reported none.
         *
         * <p>Absent is not zero -- see {@link LootDrop} -- so a reveal screen must ask
         * {@link LootDrop#magicFindReported()} rather than reading a value and defaulting it.
         */
        public LootDrop.MagicFind magicFind() {
            return drop.magicFind();
        }

        /** Whether this line is a pet drop rather than an item drop. */
        public boolean pet() {
            return "PET DROP!".equals(banner);
        }

        /**
         * Whether the server flagged this as a rare drop.
         *
         * <p>True for every banner word in the family except {@code UNCOMMON DROP!}, which is in the
         * vocabulary so the line can be decomposed but is not worth a flourish.
         */
        public boolean rare() {
            return LootParser.isRareBanner(banner);
        }
    }

    /**
     * Decomposes a raw chat line if, and only if, it is one of this family's own drops.
     *
     * <p>Lines announcing somebody else's loot are rejected here rather than by the caller, because
     * every caller would otherwise have to remember to, and forgetting is invisible. Diana treasure
     * digs are rejected too -- the shipped Diana path owns those lines and this one must never race
     * it -- and so are Crop Fever's sentence drops, for the reason in the class notes.
     *
     * @param rawLine the line as the server sent it, formatting codes intact; may be null
     * @return the decomposed banner, or empty when the line is not one
     */
    public static Optional<Banner> match(String rawLine) {
        return LootParser.matchBanner(rawLine)
                .filter(found -> found.shape() != LootParser.Shape.SENTENCE)
                .map(found -> new Banner(found.banner(), found.drop()));
    }

    /** Whether a line is any member of this banner family at all, ownership included. */
    public static boolean isBanner(String rawLine) {
        return match(rawLine).isPresent();
    }

    /**
     * Whether this line announces somebody else's drop.
     *
     * <p>Public because the arming detectors ask the same question about the lines they handle
     * themselves, and one shared list is the only way the answer stays consistent. The list lives in
     * {@link LootParser#isThirdPartyLine(String)}, which documents why it errs towards refusing a
     * line rather than claiming it.
     */
    public static boolean isThirdPartyLine(String rawLine) {
        return LootParser.isThirdPartyLine(rawLine);
    }
}
