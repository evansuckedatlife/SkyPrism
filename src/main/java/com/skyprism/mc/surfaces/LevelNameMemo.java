package com.skyprism.mc.surfaces;

import net.minecraft.network.chat.Component;

/**
 * One entry's remembered recolour: the answer, plus exactly enough context to know when
 * that answer went stale.
 *
 * <p>One of these is attached to every {@code PlayerInfo} and every client player entity by
 * a Mixin, so a lookup is a field read rather than a map probe. That matters more than it
 * looks: the alternative -- a shared {@code IdentityHashMap} -- would hash eighty keys per
 * frame and would then have to be swept for entries whose player left, on a code path that
 * is not allowed to allocate.</p>
 *
 * <h2>Why the key is the source component and not the result</h2>
 * <p>Neither surface hands back a stable object. {@code PlayerTabOverlay.getNameForDisplay}
 * calls {@code getTabListDisplayName().copy()} and decorates the copy, and
 * {@code Player.getDisplayName()} builds a fresh {@code MutableComponent} out of the team
 * prefix and suffix, both on every single call. So the returned component is useless as a
 * key. What is stable is the <em>input</em>: for TAB, the stored
 * {@code PlayerInfo.getTabListDisplayName()} field, which keeps its identity until the
 * server sends another player-info packet, and which therefore compares in under a
 * nanosecond. For nametags nothing keeps its identity, so the key has to be compared with
 * {@link Component#equals(Object)} -- see the note on revalidation below.</p>
 *
 * <h2>The three things that invalidate an answer</h2>
 * <ul>
 *   <li><b>The configuration generation.</b> One {@code int} compare covers every setting in
 *       the mod: palette, mode, chroma, the bracket toggle, the level range.</li>
 *   <li><b>The variant.</b> A second, surface-specific key compared by identity. TAB uses the
 *       player's {@code GameType}, because vanilla's {@code decorateName} italicises
 *       spectators, and an entry cached while someone was spectating must not survive them
 *       leaving spectator.</li>
 *   <li><b>Time, but only for chroma.</b> A level the palette animates has a colour that is a
 *       function of the clock, so its entry expires after the configured chroma interval. A
 *       level that is not animated never expires on time at all -- there would be nothing to
 *       recompute -- which is why the common path never reads a clock.</li>
 * </ul>
 *
 * <p>Not thread-safe, deliberately: both call sites are the client render thread.</p>
 */
public final class LevelNameMemo {

    /** The component the cached answer was derived from; null until the first store. */
    Component key;

    /** Surface-specific secondary key, compared by identity; null when a surface has none. */
    Object variant;

    /**
     * The recoloured component, or null meaning "this source needs no change".
     *
     * <p>Caching the no-change answer is the important half. Most names in a Hypixel lobby
     * carry a level tag, but every vanilla-shaped name, every NPC and every entry on a
     * non-Hypixel server does not, and without this those would re-run the scan every frame
     * forever.</p>
     */
    Component value;

    /** False until the first store, to tell "no answer yet" from "the answer is no change". */
    boolean valid;

    /** {@code ConfigManager.generation()} at the time of the store. */
    int generation;

    /** Quantised wall clock at the time of the store; only read when {@link #chromatic}. */
    long computedAt;

    /** Whether {@link #value} depends on the clock and therefore expires. */
    boolean chromatic;

    /** Calls served since the key was last compared in full; see {@link #keyMatches}. */
    private int sinceRevalidate;

    /**
     * Whether the stored answer belongs to this call, judging by the key alone.
     *
     * <p>Every test here is a field compare or a branch, and none of them reads a clock. That
     * is the design goal: with chroma off -- the default -- a cache hit on either surface
     * costs this method and nothing else, so the mod's per-frame cost does not scale with
     * anything but the number of entries drawn.</p>
     *
     * <h2>The structural compare is throttled, and measurably needs to be</h2>
     * <p>Identity costs under a nanosecond. {@link Component#equals(Object)} over a typical
     * four-node Hypixel name measures around 140 ns on this machine, because it walks the
     * sibling list and compares every field of every {@code Style}. Small next to the two-odd
     * microseconds a full recolour costs, but unlike the recolour it would be paid on every
     * frame forever, for every visible player.</p>
     *
     * <p>So a caller whose key can never match by identity passes a {@code revalidateEvery}
     * count: the full compare runs on one call in that many and the answer is taken on trust
     * in between. Counting calls rather than milliseconds is deliberate -- it costs no clock
     * read, and it makes the revalidation rate proportional to how often the entry is
     * actually drawn, so an entity being rendered at 240 fps is checked four times as often
     * per second as one at 60 and an entity that is not drawn at all is never checked. The
     * price is that a renamed player can take that many frames to update. The TAB list passes
     * zero and gets an exact answer for nothing.</p>
     *
     * @param source         the component the caller is about to recolour
     * @param variantKey     the surface's secondary key, compared by identity; may be null
     * @param generationNow  the current configuration generation
     * @param revalidateEvery how many calls a structurally-compared key is trusted for; zero
     *                       or one to compare on every call
     * @return true when the key still describes this call; the caller must still check
     *         {@link #chromatic} before trusting {@link #value}
     */
    boolean keyMatches(Component source, Object variantKey, int generationNow, int revalidateEvery) {
        if (!valid || generation != generationNow || variant != variantKey) {
            return false;
        }
        if (key == source) {
            return true;
        }
        if (key == null) {
            return false;
        }
        if (revalidateEvery > 1 && ++sinceRevalidate < revalidateEvery) {
            return true;
        }
        sinceRevalidate = 0;
        return key.equals(source);
    }

    /**
     * Records an answer. A null {@code result} means "the source needed no change" and is
     * cached just as firmly as a recoloured component.
     */
    void store(Component source, Object variantKey, Component result,
               int generationNow, long nowMillis, boolean isChromatic) {
        this.key = source;
        this.variant = variantKey;
        this.value = result;
        this.generation = generationNow;
        this.computedAt = nowMillis;
        this.chromatic = isChromatic;
        this.sinceRevalidate = 0;
        this.valid = true;
    }

    /** Drops the answer, forcing a recompute on the next call. */
    public void invalidate() {
        this.valid = false;
        this.key = null;
        this.variant = null;
        this.value = null;
    }
}
