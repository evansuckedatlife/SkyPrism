package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A trophy fish caught in the lava on the Crimson Isle.
 *
 * <p>The best-shaped gathering source in the game for a slot machine, because Hypixel hands you the
 * rarity in the trigger line itself: the tier word is printed, in its own colour, at the end of the
 * sentence. That is what lets the split happen <b>at the detector rather than at the policy</b>,
 * which is the whole trick here. {@link #rare()} fires only on GOLD (2% of trophy catches) and
 * DIAMOND (0.2%), which are the two a player screenshots, and can therefore default to ALWAYS with
 * no argument at all. {@link #ordinary()} takes BRONZE (100% of trophy catches) and SILVER (25%),
 * arriving every few seconds during a lava session, and ships on NEVER -- there is no configuration
 * of a slot machine that survives a Bronze trophy fish every few seconds. One regex, two constants,
 * and no line either of them can both claim.
 *
 * <h2>The pattern, and the one place it is deliberately looser than its source</h2>
 * <p>Transcribed from SkyHanni TrophyFishMessages.kt (fishing.trophy.trophyfish), which carries
 * five captured lines beside it. Its literal prefix is a gold section sign, then U+E02A -- the
 * private-use codepoint Hypixel currently uses for the Trophy Fish Chance icon -- then the bold
 * banner. Anchoring on that codepoint would be a standing liability: Hypixel has already moved one
 * of these icons once (Magic Find used to be a literal star), and a resource pack can override the
 * glyph. So the prefix here admits formatting codes and any character that is not a letter, a digit
 * or a colon.
 *
 * <p>That keeps the match un-spoofable while surviving the icon changing underneath it. The pattern
 * is used with matches(), so a player quoting the banner in chat cannot match: their name and the
 * colon Hypixel puts after it sit in the prefix, and both are excluded from it.
 *
 * <p>The name group admits an obfuscation code, because that is how Hypixel prints a trophy fish
 * the player has not yet discovered; it is stripped before the caption is built.
 */
public final class TrophyFishDetector extends RegistryDetector {

    /**
     * Formatting codes, and otherwise nothing that could spell a player's name or the colon after
     * it -- which is what makes an anchored match un-spoofable by a player-authored line while
     * still admitting whatever glyph Hypixel currently prefixes the banner with.
     */
    private static final String PREFIX = "(?:§.|[^\\p{L}\\p{N}:])*";

    /**
     * Verbatim from SkyHanni beyond the prefix. One of the captured lines it was built against,
     * with section signs written as ampersands: "&amp;6[icon] &amp;r&amp;6&amp;lTROPHY FISH!
     * &amp;r&amp;fYou caught a &amp;r&amp;9Lavahorse &amp;r&amp;6&amp;lGOLD&amp;r&amp;f!".
     */
    private static final Pattern TROPHY_FISH = Pattern.compile(
            PREFIX + "TROPHY FISH! §r§fYou caught an? §r"
                    + "(?<name>§[0-9a-f](?:§k)?[\\w -]+) "
                    + "§r§[0-9a-f]§l(?<tier>\\w+)§r§f!");

    /** The two tiers worth a spin. Verified rates: Gold 2%, Diamond 0.2% of trophy catches. */
    private static final Set<String> RARE_TIERS = Set.of("GOLD", "DIAMOND");

    /** The two that are not. Bronze is 100% of trophy catches, Silver 25%. */
    private static final Set<String> COMMON_TIERS = Set.of("BRONZE", "SILVER");

    private final Set<String> wanted;

    private TrophyFishDetector(LootSource source, Set<String> wanted) {
        super(source);
        this.wanted = wanted;
    }

    /** Gold and Diamond only. Ships armed. */
    public static TrophyFishDetector rare() {
        return new TrophyFishDetector(LootSource.FISHING_TROPHY_FISH_RARE, RARE_TIERS);
    }

    /** Bronze and Silver only. Ships on NEVER; see the class notes. */
    public static TrophyFishDetector ordinary() {
        return new TrophyFishDetector(LootSource.FISHING_TROPHY_FISH, COMMON_TIERS);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("TROPHY FISH!") < 0) {
            return Optional.empty();
        }
        Matcher matcher = TROPHY_FISH.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String tier = matcher.group("tier").toUpperCase(Locale.ROOT);
        if (!wanted.contains(tier)) {
            return Optional.empty();
        }
        String name = TextClean.clean(matcher.group("name"));
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(event(name + " (" + titleCase(tier) + ")", nowMillis));
    }

    /** "GOLD" reads as shouting in a caption; "Gold" does not. */
    private static String titleCase(String tier) {
        return tier.charAt(0) + tier.substring(1).toLowerCase(Locale.ROOT);
    }
}
