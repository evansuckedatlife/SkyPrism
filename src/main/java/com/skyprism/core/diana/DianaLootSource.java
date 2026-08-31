package com.skyprism.core.diana;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.SourceDetector;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Diana expressed as the first implementation of the general loot-source abstraction.
 *
 * <p>Diana is the only path verified on the live server, so nothing here changes what it does. This
 * class exists to say, in code, what Diana <em>is</em> in the new vocabulary: a {@link LootSource}
 * gated on the mayor, whose trigger is a creature dying rather than a line of chat, and whose events
 * are ordinary {@link LootEvent}s that the same machine consumes as every other source's.
 *
 * <h2>Why {@link #onChat(String, long)} never fires</h2>
 * <p>Because Diana's trigger is not in chat. The burrow spawn line binds which creature the player
 * is fighting; the treasure-dig lines are <em>loot</em>; and the roll begins when the bound creature
 * is actually defeated, which only the client can see. Returning empty here is therefore the honest
 * answer, not a stub -- and it is stated rather than left implicit because the alternative failure,
 * a detector that quietly matches the spawn line and starts a roll for a creature that is still
 * alive, would be a visible regression on the one path that must not regress.
 *
 * <p>The chat side of Diana stays exactly where it already works: {@link DianaPatterns} for the
 * spawn and dig lines, {@link LootParser} for the drops. This class does not duplicate either.
 */
public final class DianaLootSource implements SourceDetector {

    /** Diana's own gate, read from the registry so there is one definition of it. */
    private static final DianaLootSource INSTANCE = new DianaLootSource();

    private DianaLootSource() {
    }

    /** The single instance; it holds no state. */
    public static DianaLootSource get() {
        return INSTANCE;
    }

    /**
     * The event for a defeated Mythological creature.
     *
     * <p>What {@link SlotRoll#start(MythologicalCreature)} builds internally, exposed so a caller
     * that already speaks in {@link LootEvent}s -- a general controller, a stats path, a test -- can
     * produce Diana's events the same way it produces everybody else's.
     *
     * @param creature  the creature that died, never null
     * @param nowMillis the instant it died
     */
    public static LootEvent defeat(MythologicalCreature creature, long nowMillis) {
        Objects.requireNonNull(creature, "creature");
        return new LootEvent(LootSource.DIANA_MYTHOLOGICAL, creature.displayName(), nowMillis);
    }

    @Override
    public LootSource source() {
        return LootSource.DIANA_MYTHOLOGICAL;
    }

    /**
     * The coarse half of Diana's gate: in SkyBlock, with Diana in office.
     *
     * <p>Deliberately coarser than the shipped {@link DianaGate}, which additionally holds an area
     * whitelist and a Hypixel check with its own change-edge tracking. That gate is Diana's and stays
     * Diana's -- this one exists so a general bus can ask the cheap question without reaching into
     * it, and a caller wiring the two together should require both.
     */
    @Override
    public boolean gateOpen(GameContext ctx) {
        return LootSourceRegistry.gate(LootSource.DIANA_MYTHOLOGICAL).isOpen(ctx);
    }

    /** Always empty: Diana's trigger is an entity dying, not a line. See the class notes. */
    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        return Optional.empty();
    }

    /** False, so the bus does not put this detector or its markers on the per-line path at all. */
    @Override
    public boolean readsChat() {
        return false;
    }

    /** The dig lines the registry records for this source, for documentation and tests. */
    @Override
    public List<String> triggerSamples() {
        return LootSourceRegistry.info(LootSource.DIANA_MYTHOLOGICAL).triggerSamples();
    }
}
