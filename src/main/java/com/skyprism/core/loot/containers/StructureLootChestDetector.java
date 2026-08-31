package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;

/**
 * A fixed structure loot chest: the Jungle Temple, the Mines of Divan, the Lost Precursor City, the
 * Goblin Queen's Den, the Fairy Grotto, and the Glacite Mineshaft.
 *
 * <h2>Shipped policy</h2>
 * <p>{@link com.skyprism.core.loot.RollPolicy#ALWAYS}. Five to ten an hour, each a deliberate detour
 * to a named structure with a genuinely fat loot table -- the pacing the machine was built for, and
 * the same order as a Diana burrow chain. The one player this annoys is someone key-spamming the
 * Jungle Temple, whose chests refill on every key; they want {@code ON_JACKPOT_ITEM_ONLY}, and the
 * config gives it to them.
 *
 * <h2>The caption comes free</h2>
 * <p>{@link GameContext#area()} already carries "Jungle Temple" or "Fairy Grotto" off the sidebar
 * this mod reads anyway, so the subject costs nothing to produce. It is cached in {@link
 * #gateOpen(GameContext)} rather than read per line, which is legitimate precisely because that
 * method is called on context change and nowhere else -- the same property the whole gate design
 * rests on. When the area is unknown the caption falls back to the source's own display name, which
 * is the honest answer rather than a guessed structure.
 *
 * <p>The chest also opens inside a Glacite Mineshaft, where the area is not a structure name; that
 * is why the fallback exists and why the gate admits both islands.
 */
public final class StructureLootChestDetector extends RegistryDetector {

    private static final String MARKER = "LOOT CHEST COLLECTED";

    private String area = "";

    public StructureLootChestDetector() {
        super(LootSource.LOOT_CHEST);
    }

    @Override
    public boolean gateOpen(GameContext ctx) {
        boolean open = super.gateOpen(ctx);
        // Cached here, not in onChat: this runs on a context change, never per line and never per
        // tick, which is the only reason reading the context from a detector is affordable at all.
        area = open ? ctx.area() : "";
        return open;
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf(MARKER) < 0) {
            return Optional.empty();
        }
        if (!ContainerPatterns.LOOT_CHEST_COLLECTED.matcher(rawLine).matches()) {
            return Optional.empty();
        }
        return Optional.of(area.isEmpty() ? event(nowMillis) : event(area, nowMillis));
    }
}
