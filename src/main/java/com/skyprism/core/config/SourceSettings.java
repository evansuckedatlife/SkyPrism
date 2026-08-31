package com.skyprism.core.config;

import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.core.util.TextClean;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What the player has said about one {@link LootSource}: whether it may spin, when, and which drops
 * earn the celebration.
 *
 * <p>Three fields, and two of them are deliberately allowed to say nothing at all.
 *
 * <h2>{@code policy == null} means "whatever this build ships"</h2>
 * <p>Sixty-odd sources with an explicit policy each would be four hundred lines of JSON that says
 * exactly what the code already says, and -- much worse -- it would freeze every one of those
 * defaults on the day the player first launched the mod. The shipped defaults are researched
 * guesses about cadence; some of them will turn out to be wrong, and a release that corrects one
 * should reach the players who never expressed an opinion. So an untouched source stores nothing,
 * reads through to {@link LootSourceRegistry#defaultPolicy(LootSource)}, and moves when that moves.
 *
 * <p>The flip side is the promise this makes to a player who <em>did</em> express an opinion: an
 * explicit policy is written to the file verbatim and is never rewritten, not even into a value
 * that happens to equal the current default. Collapsing "I chose ALWAYS" into "no opinion" because
 * ALWAYS is also today's default would silently hand their choice to the next release to change.
 * The way back to the default is the screen's reset button, or deleting the entry by hand.
 *
 * <h2>{@code jackpotItems} is empty unless {@code overrideJackpotItems} is set</h2>
 * <p>Same argument, with an extra state that a bare list cannot express. "I have not touched the
 * jackpot list", "I want these six items instead of the shipped ones" and "I want no jackpot
 * flourish from this source at all" are three different wishes, and an empty list can only encode
 * two of them. The boolean is what separates the first from the third.
 *
 * <h2>Two policies are demoted rather than obeyed</h2>
 * <p>{@link RollPolicy#ON_RARE_BANNER} on a source Hypixel prints no rarity flag for, and
 * {@link RollPolicy#ON_JACKPOT_ITEM_ONLY} on a source with an empty effective jackpot list, are both
 * detectors that can never fire. That is the single failure mode this whole feature is written to
 * avoid, because it is indistinguishable from a feature that works. {@link
 * #sanitizedCopy(LootSource)} drops such a policy back to the shipped default instead of storing
 * it, which is the same invariant {@code LootSourceInfo}'s constructor enforces on the shipped
 * table -- enforced on the player's file too, since a hand-edit can reach it just as easily.
 *
 * <p>Like every other group in {@link SkyPrismConfig} this is a mutable bag of public fields with
 * no validation, because it is what Gson binds and what a screen binds two-way controls to. The
 * invariants live in {@link #sanitizedCopy(LootSource)}, which repairs rather than rejects.
 */
public final class SourceSettings {

    /**
     * Cap on a single source's jackpot list.
     *
     * <p>Purely so a pathological hand-edited file cannot make one collapsed group in the settings
     * screen taller than the screen. Matches the Diana cap, which is the same argument.
     */
    public static final int MAX_JACKPOT_ITEMS = 128;

    /**
     * The separator the screen's single-line jackpot field splits on.
     *
     * <p>A comma rather than a newline because a YACL string field is one line, and rather than a
     * semicolon because item names never contain commas and players expect commas.
     */
    public static final String JACKPOT_SEPARATOR = ",";

    /**
     * The text that means "override the shipped list with nothing".
     *
     * <p>A blank field already means "no opinion", so the empty override needs a spelling of its
     * own. A lone hyphen is the shortest thing a player will not type by accident and the
     * description in the screen names it explicitly.
     */
    public static final String JACKPOT_NONE = "-";

    /** Whether this source may spin at all. On, so that arming it is only a matter of policy. */
    public boolean enabled = true;

    /**
     * The player's chosen policy, or null to follow this build's shipped default.
     *
     * <p>Also null after Gson meets a policy name this build does not know, which is the right
     * answer for that case too: an unreadable opinion is not an opinion.
     */
    public RollPolicy policy = null;

    /** Whether {@link #jackpotItems} replaces the shipped list rather than being unset. */
    public boolean overrideJackpotItems = false;

    /**
     * The replacement jackpot list, meaningful only when {@link #overrideJackpotItems} is set.
     *
     * <p>Matched case-insensitively against a formatting-stripped drop name, exactly as the Diana
     * list is, so a name pasted straight out of chat with its colour codes still attached works.
     */
    public Set<String> jackpotItems = new LinkedHashSet<>();

    /** A fresh, wholly unopinionated entry. */
    public SourceSettings() {
    }

    /**
     * Whether this entry says anything at all.
     *
     * <p>An entry that says nothing is dropped from the written file by {@link
     * SkyPrismConfig.LootSettings#sanitizedCopy()}, which is what keeps a config with sixty-odd
     * configurable sources down to the handful of lines a player has actually edited.
     *
     * @return true if every field is at its shipped value
     */
    public boolean isDefault() {
        return enabled
                && policy == null
                && !overrideJackpotItems
                && (jackpotItems == null || jackpotItems.isEmpty());
    }

    /**
     * The policy in force for {@code source}.
     *
     * @param source the source this entry belongs to
     * @return the explicit choice if there is one, otherwise this build's shipped default
     */
    public RollPolicy effectivePolicy(LootSource source) {
        return policy != null ? policy : shippedPolicy(source);
    }

    /**
     * The jackpot list in force for {@code source}.
     *
     * @param source the source this entry belongs to
     * @return the override if one is set -- possibly empty, which is a real answer -- otherwise the
     *         shipped list from {@link LootSourceRegistry}
     */
    public Set<String> effectiveJackpotItems(LootSource source) {
        if (overrideJackpotItems) {
            return jackpotItems == null ? Set.of() : Set.copyOf(jackpotItems);
        }
        return shippedJackpotItems(source);
    }

    /**
     * Whether a drop name earns the jackpot flourish from {@code source}.
     *
     * <p>Both sides are formatting-stripped and compared ignoring case, for the reason the Diana
     * list documents: the screen matches against the live instance while the player is still
     * typing, and a name pasted out of chat arrives carrying the colour codes it was printed with.
     *
     * @param source   the source this entry belongs to
     * @param itemName a drop name, with or without formatting codes
     * @return true if it is on the effective list; false for null, blank or unlisted
     */
    public boolean isJackpot(LootSource source, String itemName) {
        if (itemName == null) {
            return false;
        }
        String needle = TextClean.clean(itemName);
        if (needle == null || needle.isBlank()) {
            return false;
        }
        for (String entry : effectiveJackpotItems(source)) {
            String candidate = TextClean.clean(entry);
            if (candidate != null && candidate.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The jackpot list as the screen's one-line field shows it.
     *
     * <p>Round-trips exactly through {@link #applyJackpotText(String)}, which is the property that
     * lets a single text control stand in for a boolean and a set without the player ever seeing
     * either.
     *
     * @return the empty string when nothing is overridden, {@value #JACKPOT_NONE} for an override
     *         with no items, otherwise the items joined by commas
     */
    public String jackpotText() {
        if (!overrideJackpotItems) {
            return "";
        }
        if (jackpotItems == null || jackpotItems.isEmpty()) {
            return JACKPOT_NONE;
        }
        return String.join(JACKPOT_SEPARATOR + " ", jackpotItems);
    }

    /**
     * Sets the jackpot list from the screen's one-line field.
     *
     * <p>Blank clears the override rather than setting an empty one, because a player who selects
     * the text and deletes it means "forget I said anything", not "never celebrate anything from
     * here". {@value #JACKPOT_NONE} is the spelling for the latter.
     *
     * @param text the field contents; null is treated as blank
     */
    public void applyJackpotText(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            overrideJackpotItems = false;
            jackpotItems = new LinkedHashSet<>();
            return;
        }
        overrideJackpotItems = true;
        var out = new LinkedHashSet<String>();
        if (!trimmed.equals(JACKPOT_NONE)) {
            for (String part : trimmed.split(JACKPOT_SEPARATOR, -1)) {
                if (out.size() >= MAX_JACKPOT_ITEMS) {
                    break;
                }
                String cleaned = TextClean.clean(part);
                if (cleaned != null && !cleaned.isBlank()) {
                    out.add(cleaned);
                }
            }
        }
        jackpotItems = out;
    }

    /**
     * A repaired copy: nulls replaced, list cleaned and capped, impossible policy demoted.
     *
     * <p>Never throws, for any field values, because it is called from
     * {@link SkyPrismConfig#sanitized()} which is documented as never throwing.
     *
     * @param source the source this entry belongs to, needed to know what is possible for it
     * @return a corrected, independent copy
     */
    public SourceSettings sanitizedCopy(LootSource source) {
        var out = new SourceSettings();
        out.enabled = enabled;
        out.overrideJackpotItems = overrideJackpotItems;
        out.jackpotItems = cleanNames(jackpotItems);
        // Cleaning can empty a list that was full of blanks and colour codes, so the "an override
        // with nothing in it" state has to survive that -- it is a real choice, not a mistake.
        out.policy = permitted(policy, source, out) ? policy : null;
        return out;
    }

    /** A deep copy with nothing corrected, for the screen's cancel button. */
    public SourceSettings copy() {
        var out = new SourceSettings();
        out.enabled = enabled;
        out.policy = policy;
        out.overrideJackpotItems = overrideJackpotItems;
        out.jackpotItems =
                jackpotItems == null ? new LinkedHashSet<>() : new LinkedHashSet<>(jackpotItems);
        return out;
    }

    /**
     * Whether a policy can ever fire for a source, given that source's effective jackpot list.
     *
     * <p>A null policy is always permitted: it is the absence of a choice, and the shipped default
     * it reads through to is already known good, because the registry enforces the same two rules
     * on itself.
     */
    private static boolean permitted(RollPolicy candidate, LootSource source, SourceSettings state) {
        if (candidate == null) {
            return true;
        }
        return switch (candidate) {
            case ON_RARE_BANNER -> emitsRareBanner(source);
            case ON_JACKPOT_ITEM_ONLY -> !state.effectiveJackpotItems(source).isEmpty();
            case ALWAYS, NEVER -> true;
        };
    }

    /**
     * The shipped default policy for a source.
     *
     * <p>The registry has an entry for every constant and a test enforces that, so the catch is not
     * expected to run. It is here because this method sits under {@link SkyPrismConfig#sanitized()},
     * and one broken table entry must not cost the player every other setting in their file.
     */
    private static RollPolicy shippedPolicy(LootSource source) {
        try {
            return LootSourceRegistry.defaultPolicy(source);
        } catch (RuntimeException noEntry) {
            return RollPolicy.NEVER;
        }
    }

    /** The shipped jackpot list for a source, empty if the registry cannot answer. */
    private static Set<String> shippedJackpotItems(LootSource source) {
        try {
            return LootSourceRegistry.info(source).jackpotItems();
        } catch (RuntimeException noEntry) {
            return Set.of();
        }
    }

    /** Whether Hypixel prints a rarity flag this source can key on; false if unknown. */
    private static boolean emitsRareBanner(LootSource source) {
        try {
            return LootSourceRegistry.info(source).emitsRareBanner();
        } catch (RuntimeException noEntry) {
            return false;
        }
    }

    /**
     * Strips colour codes and surrounding space off each entry, drops what is left blank, and caps
     * the result. Insertion order is kept, because it is the order the screen shows them in.
     */
    private static Set<String> cleanNames(Set<String> raw) {
        var out = new LinkedHashSet<String>();
        if (raw == null) {
            return out;
        }
        for (String name : raw) {
            if (out.size() >= MAX_JACKPOT_ITEMS) {
                break;
            }
            String cleaned = TextClean.clean(name);
            if (cleaned != null && !cleaned.isBlank()) {
                out.add(cleaned);
            }
        }
        return out;
    }

    /**
     * A short, stable description for {@code /skyprism} output and for test failure messages.
     *
     * @param source the source this entry belongs to
     * @return something like {@code slayer_boss: on, ALWAYS (shipped)}
     */
    public String describe(LootSource source) {
        List<String> parts = new ArrayList<>();
        parts.add(enabled ? "on" : "off");
        parts.add(effectivePolicy(source) + (policy == null ? " (shipped)" : " (chosen)"));
        if (overrideJackpotItems) {
            int size = jackpotItems == null ? 0 : jackpotItems.size();
            parts.add(size + " custom jackpot item" + (size == 1 ? "" : "s"));
        }
        return source.id() + ": " + String.join(", ", parts).toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceSettings s
                && enabled == s.enabled
                && policy == s.policy
                && overrideJackpotItems == s.overrideJackpotItems
                && Objects.equals(jackpotItems, s.jackpotItems);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, policy, overrideJackpotItems, jackpotItems);
    }

    @Override
    public String toString() {
        return "SourceSettings[" + (enabled ? "on" : "off")
                + ", policy=" + (policy == null ? "shipped" : policy)
                + (overrideJackpotItems ? ", jackpot=" + jackpotItems : "") + "]";
    }
}
