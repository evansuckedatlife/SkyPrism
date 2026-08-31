package com.skyprism.core.diana;

import com.skyprism.core.util.TextClean;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The cheap guard that keeps the whole Diana feature at literally zero cost whenever it could not
 * possibly apply.
 *
 * <p>Diana loot only exists on Hypixel, inside SkyBlock, while Diana is the elected mayor, and only
 * in the areas the player has whitelisted. Checking those four conditions inside every chat line and
 * every HUD frame would be wasteful and easy to get wrong in four different places, so they are
 * folded once into {@link #isOpen()}.
 *
 * <h2>Why {@link #consumeChanged()} exists</h2>
 * <p>The real saving is not the boolean test -- it is <em>not being subscribed at all</em>. Callers
 * want to attach their chat and render listeners on the rising edge and detach on the falling edge,
 * which needs a signal that fires exactly once per transition. {@code consumeChanged()} is that
 * signal: it reports whether {@link #isOpen()} has flipped since it was last asked, and clears
 * itself.
 *
 * <p>Crucially the edge is an edge of <b>openness</b>, not of the individual inputs. Walking between
 * two allowed areas changes {@link #setArea(String)} but not {@code isOpen()}, and must not churn
 * listener registration, so it raises no edge. Setting a field to the value it already holds raises
 * none either.
 *
 * <h2>Area matching</h2>
 * <p>Areas arrive from the scoreboard, formatted and inconsistently spaced, so both the whitelist
 * and the current area are normalised through {@link TextClean#clean(String)} and lower-cased before
 * comparison. An <b>empty allowed-area set means "any area"</b> -- that polarity is chosen so a
 * default, unconfigured gate works everywhere rather than silently working nowhere, which would look
 * exactly like a broken feature. With a non-empty whitelist an unknown ({@code null}) area is closed,
 * because the player asked to be restricted and "I do not know where I am" is not a match.
 *
 * <p><b>Threading:</b> not thread safe; expected to be touched only from the client thread.
 */
public final class DianaGate {

    private boolean onHypixel;
    private boolean inSkyBlock;
    private boolean mayorDiana;

    /** Normalised current area, or null when unknown. */
    private String area;

    /** Normalised whitelist; empty means "any area". */
    private Set<String> allowedAreas = Set.of();

    private boolean open;
    private boolean changed;

    public void setOnHypixel(boolean value) {
        if (onHypixel != value) {
            onHypixel = value;
            recompute();
        }
    }

    public void setInSkyBlock(boolean value) {
        if (inSkyBlock != value) {
            inSkyBlock = value;
            recompute();
        }
    }

    public void setMayorDiana(boolean value) {
        if (mayorDiana != value) {
            mayorDiana = value;
            recompute();
        }
    }

    /**
     * Sets the area the player is currently in.
     *
     * @param area raw area name, formatting codes and stray spacing allowed; null or blank means
     *             "unknown", which only matters when a whitelist is configured
     */
    public void setArea(String area) {
        String normalised = normalise(area);
        if (!java.util.Objects.equals(this.area, normalised)) {
            this.area = normalised;
            recompute();
        }
    }

    /**
     * Replaces the area whitelist.
     *
     * @param areas the allowed areas; null or empty means "any area". Entries are normalised, and
     *              blank entries are discarded so a stray empty config row cannot match everything.
     */
    public void setAllowedAreas(Set<String> areas) {
        Set<String> normalised = new LinkedHashSet<>();
        if (areas != null) {
            for (String a : areas) {
                String n = normalise(a);
                if (n != null) {
                    normalised.add(n);
                }
            }
        }
        if (!allowedAreas.equals(normalised)) {
            allowedAreas = Set.copyOf(normalised);
            recompute();
        }
    }

    /** True when every condition holds and the feature should be running. */
    public boolean isOpen() {
        return open;
    }

    /**
     * Whether the client is connected to Hypixel.
     *
     * <p>Published separately from {@link #isOpen()} because it is the one condition that is
     * settled from the connection address alone, before a single packet of world state arrives.
     * Anything whose work is pointless off Hypixel -- the sidebar and TAB polls that feed the other
     * three conditions, most obviously -- can read this and skip the work outright rather than
     * throttling it.
     *
     * @return whether the Hypixel condition currently holds
     */
    public boolean onHypixel() {
        return onHypixel;
    }

    /**
     * Whether the player is inside SkyBlock.
     *
     * <p>Also published separately, because SkyBlock is where the <em>other</em> feature applies
     * too: a SkyBlock level prefix only exists on SkyBlock, so the level recolour has the same
     * question to ask and no reason to answer it a second way.
     *
     * @return whether the SkyBlock condition currently holds
     */
    public boolean inSkyBlock() {
        return inSkyBlock;
    }

    /** Whether Diana is the elected mayor, as last read from TAB. */
    public boolean mayorDiana() {
        return mayorDiana;
    }

    /** The normalised current area, or null when it is unknown. */
    public String area() {
        return area;
    }

    /**
     * A one-line account of why the gate is where it is, for a log line or a status command.
     *
     * <p>The gate closing is a total, silent outage of the Diana feature, and from the outside
     * "Diana is not the mayor" and "the mod could not read the mayor row" look identical. Naming
     * the failing condition is what turns the second into a one-line bug report.
     *
     * @return e.g. {@code "open"} or {@code "closed (not on Hypixel, mayor is not Diana)"}
     */
    public String describe() {
        if (open) {
            return "open";
        }
        StringBuilder out = new StringBuilder("closed (");
        int before = out.length();
        if (!onHypixel) {
            out.append("not on Hypixel");
        }
        if (!inSkyBlock) {
            append(out, before, "not in SkyBlock");
        }
        if (!mayorDiana) {
            append(out, before, "mayor is not Diana");
        }
        if (!areaAllowed()) {
            append(out, before, "area " + (area == null ? "unknown" : "'" + area + "'")
                    + " is not whitelisted");
        }
        return out.append(')').toString();
    }

    private static void append(StringBuilder out, int firstReasonAt, String reason) {
        if (out.length() > firstReasonAt) {
            out.append(", ");
        }
        out.append(reason);
    }

    /**
     * Whether {@link #isOpen()} has flipped since this was last called, clearing the flag.
     *
     * <p>This is a "something moved, go and look" signal rather than a queue of transitions: a
     * caller that reacts to it must read {@link #isOpen()} to find out which way. If the gate
     * opened and closed again between two calls it is reported once, not twice, and a change that
     * left openness alone is not reported at all.
     *
     * @return true exactly once per open/closed transition
     */
    public boolean consumeChanged() {
        boolean was = changed;
        changed = false;
        return was;
    }

    /**
     * Clears the live state on world change or disconnect, closing the gate.
     *
     * <p>The whitelist survives because it is configuration, not session state; everything the
     * server told us does not. If the gate was open this is a closing edge, so
     * {@link #consumeChanged()} will report it.
     */
    public void reset() {
        onHypixel = false;
        inSkyBlock = false;
        mayorDiana = false;
        area = null;
        recompute();
    }

    private void recompute() {
        boolean next = onHypixel && inSkyBlock && mayorDiana && areaAllowed();
        if (next != open) {
            open = next;
            changed = true;
        }
    }

    private boolean areaAllowed() {
        if (allowedAreas.isEmpty()) {
            return true;
        }
        return area != null && allowedAreas.contains(area);
    }

    /** Formatting-stripped, whitespace-collapsed, lower-cased; null for null or blank. */
    private static String normalise(String raw) {
        String cleaned = TextClean.clean(raw);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }
}
