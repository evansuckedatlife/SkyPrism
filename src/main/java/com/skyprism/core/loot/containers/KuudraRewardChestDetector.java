package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootSource;

import java.util.Set;
import java.util.function.Supplier;

/**
 * A Kuudra Free or Paid chest.
 *
 * <h2>Pacing</h2>
 * <p>A fast T5 team clears in three to five minutes and takes two chests per run, which is twenty to
 * thirty chest openings an hour -- squarely in the range where {@code ALWAYS} stops being a
 * celebration and becomes wallpaper. Hence the shipped {@link
 * com.skyprism.core.loot.RollPolicy#ON_RARE_BANNER}. The Free chest is genuinely low stakes and a
 * player who wants only the Paid one celebrated is better served by the config than by a second
 * source constant.
 *
 * <h2>The uncertainty, stated rather than hidden</h2>
 * <p>The {@code RARE REWARD!} broadcast is <b>confirmed only for an Obsidian Chest</b>. SkyHanni's
 * pattern captures the tier with {@code (.*)}, so it would match "Paid", but nobody has recorded it
 * doing so. If it turns out Hypixel does not broadcast for Kuudra chests, this source's shipped
 * default is a policy that can never be satisfied -- the exact silent-never-fires failure the design
 * is built to avoid.
 *
 * <p>That is why the GUI title is wired here too, and why the fallback documented in the registry is
 * to move this source to {@code ALWAYS} on the Paid chest rather than to a quieter policy. A source
 * that rolls too often is a complaint; a source that never rolls is invisible.
 *
 * <h2>The doubled title</h2>
 * <p>Hypixel writes the inventory name as "Paid Chest Chest" some of the time, duplicating the word
 * that is already in the item's own name. Both reference mods work around it rather than assume it
 * will be fixed, and so does {@link ContainerPatterns#KUUDRA_CHEST_TITLES}: all four spellings are
 * accepted, and the tier is read from the first token, which is correct for every one of them.
 */
public final class KuudraRewardChestDetector extends ChestBroadcastDetector {

    /** @param localPlayerName the client's own username, for the broadcast's ownership check */
    public KuudraRewardChestDetector(Supplier<String> localPlayerName) {
        super(LootSource.KUUDRA_REWARD_CHEST, localPlayerName);
    }

    @Override
    Set<String> tiers() {
        return ContainerPatterns.KUUDRA_CHEST_TIERS;
    }

    @Override
    Set<String> titles() {
        return ContainerPatterns.KUUDRA_CHEST_TITLES;
    }
}
