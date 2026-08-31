package com.skyprism.core.diana;

import java.util.Objects;
import java.util.Optional;

/**
 * A single reward Hypixel announced in chat: what dropped, how many, and -- when the server said
 * so -- the Magic Find the roll was made at.
 *
 * <h2>Magic Find is reported, or it is not; it is never zero by default</h2>
 * <p>Hypixel appends the player's Magic Find stat to most rare-drop banners
 * (<code>&#167;r&#167;b(+&#167;r&#167;b168% &#167;r&#167;b* Magic Find&#167;r&#167;b)</code>) and
 * omits it entirely on others -- pet drops with no roll, Diana treasure digs, and any line where it
 * simply did not send one. Those are different facts and the jackpot reveal must not conflate them:
 * showing "+0%" for an unreported stat asserts something the server never said, and a player who
 * knows their Magic Find is 240 can see the lie. So {@code magicFind} is {@code null} when nothing
 * was reported, and a {@link MagicFind} carrying an explicit {@code 0} when the server really did
 * send {@code (+0% Magic Find)}. {@link #magicFindReported()} is the question to ask; never
 * {@code magicFind().value() == 0}.
 *
 * <h2>Magic Find is provenance, not identity -- and {@code equals} says so</h2>
 * <p>This record deliberately overrides {@link #equals(Object)} and {@link #hashCode()} to compare
 * only the four components that say <em>what dropped</em>. Two Griffin Feathers are the same drop
 * whether one was rolled at 168% and the other at 240%; the stat describes the roll, not the item.
 * That keeps every existing caller, dedupe set and reel comparison meaning exactly what it meant
 * before Magic Find existed, and it is why {@link #toString()} still prints the stat -- so a failing
 * assertion shows it even though the comparison ignored it. Assert on {@link #magicFind()} directly
 * when the stat is what you are testing; there are tests in {@code LootDropTest} pinning both halves
 * of this contract so it cannot be changed by accident.
 *
 * @param itemName   display name with all formatting codes already stripped, e.g. "Chimera"
 * @param colorCode  the legacy colour code the server used for the item name, e.g. "d" for
 *                   light purple, or {@code null} when the line carried no colour
 * @param count      how many were obtained; always &gt;= 1
 * @param rare       true when the server announced this via a RARE DROP!/CRAZY RARE DROP! banner
 * @param magicFind  the Magic Find the server reported for this roll, or {@code null} when it
 *                   reported none. Never substitute a zero for an absent reading.
 */
public record LootDrop(String itemName, String colorCode, int count, boolean rare,
                       MagicFind magicFind) {

    /**
     * A Magic Find reading exactly as Hypixel sent it.
     *
     * <p><b>Why the percent sign is a field.</b> Hypixel emits both {@code (+208% Magic Find)} and
     * {@code (+208 Magic Find)} for the same event -- SkyHanni and Skyblocker each pin both forms as
     * separate regression cases and each writes the sign as {@code %?}. The mod's job is to echo
     * what arrived, so the sign is captured rather than assumed; appending a {@code %} the server
     * did not send would be inventing a unit.
     *
     * <p><b>What the number means.</b> It is the player's total Magic Find stat at the instant the
     * drop rolled -- not a bonus over a baseline and not a probability. SkyHanni averages these
     * across kills to report "Average Magic Find", which only makes sense for a stat reading.
     *
     * <p><b>No glyph is stored.</b> The icon Hypixel draws beside the words is a private-use
     * codepoint (U+E01A today, U+272F historically, and absent altogether on dungeon lines). It
     * renders as tofu for anyone without Hypixel's resource pack, so this record keeps the
     * plain-English text and lets the renderer choose its own mark -- the same rule
     * {@code TrophyFishDetector} and {@code ContainerText} already follow.
     *
     * @param value       the reported stat, &gt;= 0. Only ever zero when the server sent a zero.
     * @param percentSign whether Hypixel wrote a {@code %} after the number
     */
    public record MagicFind(int value, boolean percentSign) {

        public MagicFind {
            if (value < 0) {
                throw new IllegalArgumentException("magic find must be >= 0 but was " + value);
            }
        }

        /** The reading with its sign, e.g. {@code "+240%"} or {@code "+240"}. */
        public String format() {
            return percentSign ? "+" + value + "%" : "+" + value;
        }

        /** The reading spelled out for a reveal screen, e.g. {@code "+240% Magic Find"}. */
        public String describe() {
            return format() + " Magic Find";
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    public LootDrop {
        Objects.requireNonNull(itemName, "itemName");
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1 but was " + count);
        }
    }

    /**
     * The pre-Magic-Find shape, kept so the hundreds of call sites that predate the stat compile and
     * behave unchanged. A drop built this way reports no Magic Find, which is the truth: nobody told
     * it one.
     */
    public LootDrop(String itemName, String colorCode, int count, boolean rare) {
        this(itemName, colorCode, count, rare, null);
    }

    public static LootDrop of(String itemName) {
        return new LootDrop(itemName, null, 1, false, null);
    }

    /** Whether the server reported a Magic Find for this drop at all. */
    public boolean magicFindReported() {
        return magicFind != null;
    }

    /** The reading as an {@link Optional}, for callers that would rather not test for null. */
    public Optional<MagicFind> magicFindIfReported() {
        return Optional.ofNullable(magicFind);
    }

    /**
     * The reading spelled out for a reveal screen, or {@code null} when none was reported.
     *
     * <p>Null rather than a placeholder on purpose: the caller decides how to say "not reported",
     * and a widget that silently prints "+0% Magic Find" for an absent reading is the exact failure
     * this record's javadoc exists to prevent.
     */
    public String magicFindText() {
        return magicFind == null ? null : magicFind.describe();
    }

    /** The same drop carrying {@code reading} instead, which may be null for "not reported". */
    public LootDrop withMagicFind(MagicFind reading) {
        return new LootDrop(itemName, colorCode, count, rare, reading);
    }

    /**
     * The same drop flagged rare, keeping everything else -- Magic Find included.
     *
     * <p>Exists because rebuilding a promoted drop by hand
     * ({@code new LootDrop(d.itemName(), d.colorCode(), d.count(), true)}) silently discards the
     * stat, and a jackpot reveal is precisely where the player expects to see it.
     */
    public LootDrop asRare() {
        return rare ? this : new LootDrop(itemName, colorCode, count, true, magicFind);
    }

    /**
     * Identity is what dropped: name, colour, count and the rare flag. Magic Find is excluded --
     * see the class javadoc for why, and {@code LootDropTest} for the tests that pin it.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LootDrop that)) {
            return false;
        }
        return count == that.count
                && rare == that.rare
                && itemName.equals(that.itemName)
                && Objects.equals(colorCode, that.colorCode);
    }

    /** Consistent with {@link #equals(Object)}: the same four components, and not the stat. */
    @Override
    public int hashCode() {
        return Objects.hash(itemName, colorCode, count, rare);
    }
}
