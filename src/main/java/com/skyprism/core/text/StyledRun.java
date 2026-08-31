package com.skyprism.core.text;

import java.util.Objects;

/**
 * One contiguous piece of text that carries a single style.
 *
 * <p>Hypixel does not send chat as a flat string: a line arrives as a tree of
 * components, and the interesting ones -- a player's name, an item drop -- carry
 * hover and click events that show the player's profile or run a command. Rebuilding
 * such a line from its plain text silently throws all of that away, which is the
 * classic way a "recolour the name tag" mod breaks clicking a name in chat.</p>
 *
 * <p>So SkyPrism flattens a component tree into a list of runs, matches against the
 * flattened string, and then rewrites only the styles of the fragments it matched.
 * The style is a type parameter purely so this package stays Minecraft-free: the
 * real adapter instantiates it with Minecraft's {@code Style}, tests use
 * {@link String}. Nothing here ever inspects a style -- it is carried, compared by
 * reference, and handed back to the caller's restyler.</p>
 *
 * @param <S>   the opaque style type
 * @param text  the run's characters, never null but possibly empty; an empty run is
 *              legal and is preserved verbatim, because a component tree can contain
 *              text-less nodes that still own children or events
 * @param style the style to apply to {@code text}; may be null, since "inherit from
 *              the parent" is a real state in a component tree
 */
public record StyledRun<S>(String text, S style) {

    public StyledRun {
        Objects.requireNonNull(text, "text");
    }

    /** @return the run's length in {@code char}s, i.e. the width it occupies in the flattened string */
    public int length() {
        return text.length();
    }

    /** @return true when this run contributes no characters to the flattened string */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    /**
     * Returns this run with a different style, or {@code this} when the style is the
     * very same object.
     *
     * <p>The reference check is deliberate rather than {@code equals}: it lets the
     * rewriter hand back the original instances when a restyler decided to change
     * nothing, without assuming the style type has a meaningful (or cheap)
     * {@code equals}.</p>
     *
     * @param newStyle the replacement style, may be null
     * @return this run, or a new run with the same text
     */
    public StyledRun<S> withStyle(S newStyle) {
        return newStyle == style ? this : new StyledRun<>(text, newStyle);
    }
}
