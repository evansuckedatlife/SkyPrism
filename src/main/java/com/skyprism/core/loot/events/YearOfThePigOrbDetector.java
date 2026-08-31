package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Year of the Pig shiny orb cracked open.
 *
 * <h2>SHINY! is not a rare-drop banner, and getting that wrong would be expensive</h2>
 * <p>It reads like one, and it is easy to file alongside {@code RARE DROP!} and {@code PET DROP!}.
 * It is not. Every occurrence of {@code SHINY!} anywhere in either reference corpus belongs to this
 * one event -- the orb charging, and the orb being extracted -- plus a mob name and an optional
 * prefix in item lore. Adding a general {@code SHINY} branch to {@link RareDropBanner} would be
 * adding a branch to the universal parser for a single centennial source, so it lives here instead.
 *
 * <h2>Default policy: ALWAYS</h2>
 * <p>The Year of the Pig is a once-per-SkyBlock-century celebration and the orb is on a cooldown, so
 * this is the safest {@code ALWAYS} in the mod: the gate is shut essentially permanently, and when
 * it is not, every trigger is a discrete self-announcing reward the player is already watching.
 *
 * <h2>The charge line is not the payout</h2>
 * <p>{@code SHINY! The orb is charged! Click on it for loot!} announces that the orb is ready, not
 * that anything was received. Only the extraction line rolls. Handling the charge line explicitly --
 * rather than letting a looser pattern swallow both -- is what keeps one orb from spinning twice.
 */
public final class YearOfThePigOrbDetector extends RegistryDetector {

    /**
     * The extraction, colourless.
     *
     * <p>Verbatim from SkyHanni's repo constants under {@code event.year-of-the-pig}:
     * {@code SHINY! You extracted Shiny Token and (?<reward>.+) from the piglet's orb!}. Matched
     * against the colour-stripped line because the reward run carries its own colours.
     */
    private static final Pattern EXTRACTED = Pattern.compile(
            "SHINY! You extracted Shiny Token and (?<reward>.+) from the piglet's orb!");

    public YearOfThePigOrbDetector() {
        super(LootSource.YEAR_OF_THE_PIG_ORB);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("SHINY!") < 0) {
            return Optional.empty();
        }
        Matcher m = EXTRACTED.matcher(TextClean.clean(rawLine));
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(event("Shiny Orb", nowMillis));
    }

    /** The reward text the orb announced, for the reel, if this line was an extraction. */
    public static Optional<String> rewardOf(String rawLine) {
        if (rawLine == null || rawLine.indexOf("SHINY!") < 0) {
            return Optional.empty();
        }
        Matcher m = EXTRACTED.matcher(TextClean.clean(rawLine));
        return m.matches() ? Optional.of(m.group("reward")) : Optional.empty();
    }
}
