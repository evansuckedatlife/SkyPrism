package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * A Metal Detector dig in the Mines of Divan.
 *
 * <h2>Shipped policy, and why this source is unusually well behaved</h2>
 * <p>{@link com.skyprism.core.loot.RollPolicy#ON_JACKPOT_ITEM_ONLY}. The line fires on every dig,
 * including the plain Rough gemstones that make up most of them -- dozens per visit -- so {@code
 * ALWAYS} is unusable. But the four Scavenged tools are the whole point of the area at roughly
 * eighteen percent a chest, which paces a celebration to about one dig in five or six, and the
 * Pickonimbus at 0.06% stays the genuine lottery win. SkyHanni's own chat filter draws exactly this
 * line: it hides the message unless the loot starts with a Scavenged tool.
 *
 * <p>What makes this source unusually easy is that <b>the trigger line names the item</b>. The
 * subject of the emitted event is the item name verbatim, so a policy layer can answer {@code
 * sawJackpotItem} by testing {@code event.subject()} against {@code
 * LootSourceRegistry.info(METAL_DETECTOR_SCAVENGE).jackpotItems()} directly, with no loot window and
 * no waiting. That is not true of the block-based container sources, which fire before any item is
 * known -- see {@link PowderChestDetector} for the consequence.
 *
 * <h2>What the caption strips</h2>
 * <p>Hypixel puts the stack count inside the same capture group as the name ("Flawed Jade Gemstone
 * x2") and prefixes gemstones with a tier glyph. {@link ContainerText#itemCaption(String)} removes
 * both, so the subject is the plain-English name the jackpot lists are written in. The glyph is
 * dropped rather than matched, because those are private-use codepoints Hypixel has already moved
 * once.
 *
 * <h2>One flagged name</h2>
 * <p>The registry's jackpot list contains "Scavenged Lapis Sword", which is the one of the four
 * tool names the research never saw quoted verbatim -- the other three appear in SkyHanni's own
 * captured test lines. It is listed because the four Keepers of the Mines of Divan are of Gold,
 * Diamond, Emerald and Lapis, so a fourth tool exists; the exact wording is unverified. If it is
 * wrong the effect is one jackpot that never triggers, not a wrong match.
 */
public final class MetalDetectorScavengeDetector extends RegistryDetector {

    private static final String MARKER = "Metal Detector";

    public MetalDetectorScavengeDetector() {
        super(LootSource.METAL_DETECTOR_SCAVENGE);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf(MARKER) < 0) {
            return Optional.empty();
        }
        Matcher matcher = ContainerPatterns.METAL_DETECTOR.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String item = ContainerText.itemCaption(matcher.group("loot"));
        return Optional.of(item.isEmpty() ? event(nowMillis) : event(item, nowMillis));
    }
}
