/**
 * The two <em>rendered</em> level-colour surfaces: the TAB player list and the
 * nametags floating above players' heads.
 *
 * <p>Chat is not here. Chat arrives as an event Fabric already provides, so the chat
 * adapter is ordinary code; TAB and nametags have no event at all and must be reached
 * by Mixin. This package therefore holds the version-independent <em>logic</em> those
 * mixins call, and {@code com.skyprism.mixin} holds only the thin, annotation-heavy
 * shells that hook it up. Keeping the two apart matters for a reason beyond tidiness:
 * everything in here compiles and can be reasoned about without a Mixin annotation
 * processor, which the fast compile check deliberately does not run.</p>
 *
 * <h2>Both surfaces are hot, and hot means something specific here</h2>
 * <p>{@code PlayerTabOverlay.getNameForDisplay} runs once per listed player per frame --
 * eighty times a frame on a full Hypixel lobby -- and {@code EntityRenderer.getNameTag}
 * runs once per visible player per frame. Recolouring from scratch at that rate would
 * mean a regex, a component tree walk and a rebuild per entry per frame, which is exactly
 * the kind of mod that costs a player ten frames. So every entry carries its own
 * {@link com.skyprism.mc.surfaces.LevelNameMemo} and the answer is recomputed only when
 * the source text actually changed, when the configuration generation moved, or -- for
 * levels the palette animates -- when the configured chroma interval has elapsed. A cache
 * hit allocates nothing at all.</p>
 *
 * <h2>Failing safe</h2>
 * <p>A thrown exception inside either hook would blank a player's TAB list or their
 * nametags for the rest of the session, and would do it once per frame in the log.
 * {@link com.skyprism.mc.surfaces.LevelSurfaces} therefore catches everything, logs the
 * first failure per surface with a stack trace and the rest as a count, and switches the
 * surface off entirely after a small budget of failures. Changing any setting re-arms it,
 * so a user who fixes a bad palette gets their colours back without restarting.</p>
 */
package com.skyprism.mc.surfaces;
