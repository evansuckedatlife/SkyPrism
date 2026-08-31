package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootSource;

import java.util.Set;
import java.util.function.Supplier;

/**
 * A Catacombs reward chest -- Wood through Bedrock -- opened at the end of a run.
 *
 * <h2>Why the shipped default is ON_RARE_BANNER and not ALWAYS</h2>
 * <p>A Catacombs grinder opens one to five chests per run and ten to twenty an hour, so {@code
 * ALWAYS} would spin the machine on every Wood chest of Enchanted Bread. Worse, and this is the
 * argument that actually settles it: the chest GUI shows its contents <em>before</em> the player
 * pays, which is how both reference mods compute a pre-purchase profit figure. A roll fired on
 * opening therefore dramatises loot the player has already read off the screen. The {@code RARE
 * REWARD!} broadcast is Hypixel's own rarity flag, it names the item and the tier in one line, and
 * it lands about as often as a celebration should.
 *
 * <p>The GUI title is still wired, because a player who runs Master Mode 7 occasionally rather than
 * grinding Floor 4 is asking for exactly the {@code ALWAYS} behaviour and should get it by changing
 * one setting rather than by needing new code.
 *
 * <h2>Ordering</h2>
 * <p>Register {@link CroesusChestDetector} <em>before</em> this one. The two share the six chest
 * tiers and the identical per-chest GUI; Croesus claims them only while its run list has recently
 * been open, and the bus's first-match-wins dispatch then leaves in-run chests here. Registering
 * them the other way round would give every Croesus chest this source's caption and this source's
 * on/off switch, which is precisely the pacing distinction the registry split them to preserve.
 */
public final class DungeonRewardChestDetector extends ChestBroadcastDetector {

    /**
     * @param localPlayerName the client's own username; see {@link
     *                        RareRewardBroadcast#isOwnedBy(String)} for what happens when it is
     *                        not yet known
     */
    public DungeonRewardChestDetector(Supplier<String> localPlayerName) {
        super(LootSource.DUNGEON_REWARD_CHEST, localPlayerName);
    }

    @Override
    Set<String> tiers() {
        return ContainerPatterns.DUNGEON_CHEST_TIERS;
    }

    @Override
    Set<String> titles() {
        return ContainerPatterns.DUNGEON_CHEST_TITLES;
    }
}
