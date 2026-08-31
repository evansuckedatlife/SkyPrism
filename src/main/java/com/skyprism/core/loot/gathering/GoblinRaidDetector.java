package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;

/**
 * A Golden or Diamond Goblin spawning in the Dwarven Mines.
 *
 * <p>ALWAYS. Minutes to hours apart, and rare enough that the reference mod gives it a screen title
 * and a sound of its own -- which is a decent external check on the judgement, since that mod is
 * tuned by people who mine for a living.
 *
 * <p>This is the spawn, not the kill: the goblin's drops arrive afterwards on the ordinary server
 * banner, so this line opens the loot window and the shared parser supplies what lands on the
 * reels. That is the same shape Diana already ships -- a trigger that names the subject, loot that
 * arrives separately -- rather than a special case.
 *
 * <p>Both literals verbatim from SkyHanni MiningNotifications.kt (mining.notifications.goblin.*).
 * Compared for equality on the cleaned line rather than matched with colour codes pinned, because
 * the Diamond form colours the goblin's name mid-sentence and the Golden form does not; equality on
 * the whole cleaned sentence is also what makes it un-spoofable, since a player typing it arrives
 * with a name and colon in front.
 */
public final class GoblinRaidDetector extends RegistryDetector {

    /** Captured: "(6)A Golden Goblin has spawned!" */
    private static final String GOLDEN = "A Golden Goblin has spawned!";

    /** Captured: "(6)A (r)(b)Diamond Goblin (r)(6)has spawned!" */
    private static final String DIAMOND = "A Diamond Goblin has spawned!";

    public GoblinRaidDetector() {
        super(LootSource.MINING_GOBLIN_RAID);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("Goblin") < 0) {
            return Optional.empty();
        }
        String clean = TextClean.clean(rawLine);
        if (GOLDEN.equals(clean)) {
            return Optional.of(event("Golden Goblin", nowMillis));
        }
        if (DIAMOND.equals(clean)) {
            return Optional.of(event("Diamond Goblin", nowMillis));
        }
        return Optional.empty();
    }
}
