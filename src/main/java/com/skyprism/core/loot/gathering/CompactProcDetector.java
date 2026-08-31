package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Compact enchantment proc while mining.
 *
 * <p><b>NEVER.</b> It fires continuously while breaking stone with a Compact tool -- comparable in
 * volume to the sea creature line, which is the busiest thing in the game. Arming it would spin the
 * reels faster than they can settle. It is enumerated so the config screen can show it greyed out
 * rather than leaving a player wondering why their Compact procs are not celebrated.
 *
 * <p>Only one form is verified in mining: SkyHanni PowderTracker.kt (mining.compacted.colorless),
 * captured as "COMPACT! You found an Enchanted Hard Stone!". A sibling line exists on Jerry's
 * Workshop for Enchanted Ice, which belongs to the Frozen Treasure source and is outside this
 * source's Dwarven Mines and Crystal Hollows gate anyway. The item is captured rather than pinned
 * to Hard Stone so a third material starts working the day Hypixel adds one.
 */
public final class CompactProcDetector extends RegistryDetector {

    /** Captured: "COMPACT! You found an Enchanted Hard Stone!" (colourless). */
    private static final Pattern COMPACT = Pattern.compile("^COMPACT! You found an? (?<item>[^!]+)!$");

    public CompactProcDetector() {
        super(LootSource.MINING_COMPACT);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("COMPACT!") < 0) {
            return Optional.empty();
        }
        Matcher matcher = COMPACT.matcher(TextClean.clean(rawLine));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String item = matcher.group("item").trim();
        return item.isEmpty() ? Optional.empty() : Optional.of(event(item, nowMillis));
    }
}
