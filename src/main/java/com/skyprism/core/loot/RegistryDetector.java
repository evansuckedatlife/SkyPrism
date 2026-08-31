package com.skyprism.core.loot;

import java.util.List;
import java.util.Objects;

/**
 * A {@link SourceDetector} that takes its gate, its markers and its sample lines straight from
 * {@link LootSourceRegistry}, leaving a subclass with nothing to write but the match itself.
 *
 * <p>This exists to make the pre-filter contract hold <em>by construction</em> rather than by
 * remembering. A detector that hand-rolls its markers can declare a set that does not cover its own
 * regex, and the symptom is a feature that passes its unit test and never fires in game. A detector
 * extending this one cannot: its markers and its documented triggers come from the same table entry,
 * and the registry's own invariant test already checks that entry is self-consistent.
 *
 * <p>It also keeps the gate honest. Subclasses that need something finer than the registry's coarse
 * condition -- a slayer detector really wants "the scoreboard has a Slayer Quest section", which is
 * stricter than any island test -- should override {@link #gateOpen(GameContext)} and call {@code
 * super.gateOpen(ctx)} first, so the cheap coarse test still runs before the expensive one.
 */
public abstract class RegistryDetector implements SourceDetector {

    private final LootSourceInfo info;

    protected RegistryDetector(LootSource source) {
        this.info = LootSourceRegistry.info(Objects.requireNonNull(source, "source"));
    }

    @Override
    public final LootSource source() {
        return info.source();
    }

    /** The whole registry entry, for a subclass that wants the jackpot list or the note. */
    protected final LootSourceInfo info() {
        return info;
    }

    @Override
    public boolean gateOpen(GameContext ctx) {
        return info.gate().isOpen(ctx);
    }

    @Override
    public final List<String> chatMarkers() {
        return info.chatMarkers();
    }

    @Override
    public final List<String> triggerSamples() {
        return info.triggerSamples();
    }

    @Override
    public boolean readsChat() {
        return info.chatDriven();
    }

    /** Builds an event for this source at {@code nowMillis}, captioned {@code subject}. */
    protected final LootEvent event(String subject, long nowMillis) {
        return new LootEvent(info.source(), subject, nowMillis);
    }

    /** Builds an event captioned with the source's own display name. */
    protected final LootEvent event(long nowMillis) {
        return LootEvent.of(info.source(), nowMillis);
    }
}
