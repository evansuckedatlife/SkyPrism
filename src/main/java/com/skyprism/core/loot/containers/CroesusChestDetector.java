package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A chest claimed from Croesus in the Dungeon Hub, rather than at the end of a run.
 *
 * <h2>Why this is a separate source from an identical GUI</h2>
 * <p>The per-chest inventory is byte-identical to {@link DungeonRewardChestDetector}'s and the
 * broadcast is the same sentence. What differs is the pacing, and pacing is the whole design problem
 * this feature has. A player clearing a backlog at Croesus opens fifteen chests in ninety seconds; a
 * player at the end of a run opens one. Those want different switches, and the registry gives them
 * different switches, so the detector has to be able to tell them apart.
 *
 * <h2>How it tells them apart, and the trade-off in the window</h2>
 * <p>By arming on the Croesus run-list title and claiming chests for {@link #ARMED_MILLIS} after it
 * was last seen. The bus dispatches in registration order and stops at the first event, so
 * registering this detector <em>before</em> the Catacombs one is the whole mechanism: while armed
 * this source takes the chest, and the moment the window lapses the Catacombs source gets it
 * instead. Neither detector holds a reference to the other.
 *
 * <p>Two minutes is a deliberate compromise. Longer, and a player who visits Croesus and then
 * immediately runs a dungeon has their in-run chest captioned as a backlog chest. Shorter, and a
 * player reading the run list carefully before opening loses the distinction. Opening a chest does
 * not re-arm the window -- only returning to the run list does -- because a self-re-arming window
 * would never lapse during a session.
 *
 * <p><b>If nothing ever feeds screen titles, this detector never fires at all</b>, and every Croesus
 * chest is claimed by {@link DungeonRewardChestDetector}. That is the safe direction: the event is
 * still detected and still rolls, it is merely captioned and switched as an in-run chest.
 *
 * <h2>Kuudra runs in the Croesus list</h2>
 * <p>Croesus also lists Kuudra runs, and their chests open through the same menu. This detector
 * claims only the six Catacombs tiers and leaves Free and Paid to {@link
 * KuudraRewardChestDetector}, because the registry's jackpot list here is the Obsidian and Bedrock
 * tiers themselves -- this source is about the Catacombs tier lottery, not about every chest a hub
 * NPC can hand over.
 */
public final class CroesusChestDetector extends ChestBroadcastDetector {

    /** How long after the Croesus run list was last open this source claims chests. */
    public static final long ARMED_MILLIS = 120_000L;

    private boolean everArmed;
    private long armedAt;

    /** @param localPlayerName the client's own username, for the broadcast's ownership check */
    public CroesusChestDetector(Supplier<String> localPlayerName) {
        super(LootSource.CROESUS_CHEST, localPlayerName);
    }

    @Override
    Set<String> tiers() {
        return ContainerPatterns.DUNGEON_CHEST_TIERS;
    }

    @Override
    Set<String> titles() {
        return ContainerPatterns.DUNGEON_CHEST_TITLES;
    }

    @Override
    boolean claimable(long nowMillis) {
        return everArmed && nowMillis >= armedAt && nowMillis - armedAt < ARMED_MILLIS;
    }

    /**
     * Arms on the run list, then defers to the shared chest handling.
     *
     * <p>Opening the run list is not itself a payout, so it returns empty: the player has looked at
     * a menu, not received anything. Only the chest inside it produces an event.
     */
    @Override
    public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        if (title == null || title.isEmpty()) {
            return Optional.empty();
        }
        String clean = TextClean.clean(title);
        if (clean != null && ContainerPatterns.CROESUS_TITLE.matcher(clean).matches()) {
            everArmed = true;
            armedAt = nowMillis;
            return Optional.empty();
        }
        return super.onScreenTitle(title, nowMillis);
    }

    /** Whether the Croesus run list has been open recently enough for this source to claim chests. */
    public boolean isArmed(long nowMillis) {
        return claimable(nowMillis);
    }
}
