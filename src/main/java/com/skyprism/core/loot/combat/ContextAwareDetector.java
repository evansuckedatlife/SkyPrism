package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

/**
 * A {@link RegistryDetector} that remembers the last {@link GameContext} its gate was asked about,
 * so a caption can name the floor, the tier or the area the trigger line itself omits.
 *
 * <h2>Why the context is cached in the gate rather than passed to the match</h2>
 * <p>{@code SourceDetector.onChat} deliberately does not receive a context: handing one to every
 * line would either mean the bus carrying it into the hot path or the detector asking for it, and
 * both are the kind of per-line work this feature exists to avoid. But several combat captions need
 * exactly one coarse fact that only the context has -- the Catacombs floor for a boss defeat, the
 * Kuudra tier, the slayer quest -- and all of them are read off the sidebar, change on the order of
 * minutes, and are therefore already in {@link GameContext}.
 *
 * <p>{@code gateOpen} is the one place a detector sees a context, and the bus calls it on exactly
 * the right cadence: once per real context change, for every chat-reading detector, whether its gate
 * ends up open or shut. So caching there is free and always current. The cost of the alternative --
 * a second polling path just to feed captions -- is a polling path.
 *
 * <p><b>Subclasses that override {@code gateOpen} must call {@code super.gateOpen(ctx)} first</b>,
 * or the cached context goes stale and every caption silently reverts to its fallback. That failure
 * is quiet, which is why it is stated here rather than left to be noticed.
 */
public abstract class ContextAwareDetector extends RegistryDetector {

    private GameContext context = GameContext.UNKNOWN;

    protected ContextAwareDetector(LootSource source) {
        super(source);
    }

    @Override
    public boolean gateOpen(GameContext ctx) {
        this.context = ctx == null ? GameContext.UNKNOWN : ctx;
        return super.gateOpen(this.context);
    }

    /** The context this detector's gate was last evaluated against; never null. */
    protected final GameContext context() {
        return context;
    }
}
