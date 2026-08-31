package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A RARE CROP or VERY RARE CROP announcement in the Garden.
 *
 * <p>One pattern, two sources, split on the word Hypixel itself promoted them with.
 *
 * <h2>Very Rare Crop: ALWAYS</h2>
 * <p>Hypixel gave this tier its own banner word precisely because it is rarer than the other, and
 * taking the server at its word is both correct and free. Only one drop was verified using the tier
 * -- Burrowing Spores -- so the tier's full membership is unknown; the detector matches on the
 * banner rather than on that one name, so a second member starts working the day Hypixel adds it.
 *
 * <h2>Rare Crop: ON_JACKPOT_ITEM_ONLY</h2>
 * <p>With full Fermento or Helianthus armour these fire several times a minute, and Cropie and
 * Squash are near-continuous. The twenty-seven names split cleanly by value, and the name is right
 * there in the trigger line, so a jackpot list does the filtering with no extra parsing -- which is
 * the whole reason ON_JACKPOT_ITEM_ONLY exists as a policy.
 *
 * <h2>Two things this gets right that are easy to get wrong</h2>
 * <p>First, <b>a VERY RARE CROP line contains the string "RARE CROP!"</b>, so both sources' markers
 * match it and both detectors are offered it. The ordinary detector's pattern is anchored and
 * refuses the VERY prefix, so the two can never both claim one line, in either registration order.
 *
 * <p>Second, <b>the trailing bracket is farming fortune, not a count</b>. "RARE CROP! Cropie (+97)"
 * dropped one Cropie, not ninety-seven, and "(automatically donated)" is not part of the name
 * either. The name is therefore taken by matching the tail against the known list rather than by
 * capturing up to the bracket, which is also what makes the caption exact enough for a jackpot list
 * to match it.
 *
 * <p>Matched on the cleaned line, the way the reference mod does: this banner arrives both
 * colourless and coloured, and the coloured pest-drop form omits the reset code the Diana banner
 * pattern requires.
 */
public final class RareCropDetector extends RegistryDetector {

    /**
     * The banner and its tail. Anchored: the cleaned line must start with the banner, so a player
     * quoting it -- which arrives as "PlayerName: RARE CROP! ..." -- cannot match.
     */
    private static final Pattern CROP_BANNER = Pattern.compile(
            "^(?<very>VERY )?RARE CROP! (?<tail>.+)$");

    private final boolean wantVery;

    private RareCropDetector(LootSource source, boolean wantVery) {
        super(source);
        this.wantVery = wantVery;
    }

    /** The VERY RARE CROP tier. Ships armed. */
    public static RareCropDetector veryRare() {
        return new RareCropDetector(LootSource.GARDEN_VERY_RARE_CROP, true);
    }

    /** The ordinary RARE CROP tier. Ships on ON_JACKPOT_ITEM_ONLY; see the class notes. */
    public static RareCropDetector rare() {
        return new RareCropDetector(LootSource.GARDEN_RARE_CROP, false);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("RARE CROP!") < 0) {
            return Optional.empty();
        }
        Matcher matcher = CROP_BANNER.matcher(TextClean.clean(rawLine));
        if (!matcher.matches() || (matcher.group("very") != null) != wantVery) {
            return Optional.empty();
        }
        return RareCrops.startingWith(matcher.group("tail"))
                .map(crop -> event(crop, nowMillis));
    }
}
