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
 * A Legendary, Mythic or Special visitor served on the Garden barn plot.
 *
 * <h2>The rarity filter is in the detector, not the policy</h2>
 * <p>A Garden main accepts visitors constantly -- handing over crops is the most repetitive action
 * in the game -- and the overwhelming majority are Uncommon and Rare. So this detector fires only
 * on the three top tiers, which lets its source default to ALWAYS honestly. Putting the filter in
 * the policy instead would have meant either rolling on every handover or setting a policy the
 * source cannot satisfy; doing it here is the same trick the trophy fish split uses, and for the
 * same reason.
 *
 * <p>Pattern verbatim from SkyHanni GardenVisitorCompactChat.kt (garden.visitor.fullyaccepted),
 * which carries three captured lines covering Uncommon, Legendary and Special.
 *
 * <h2>The honest limitation</h2>
 * <p><b>The reward is not in chat.</b> The only lines that follow OFFER ACCEPTED are flat counters
 * -- "+20 Copper", "+18.2k Farming XP", "+12 Bits" -- and the actual reward items live in the
 * visitor GUI's item lore, which is where both reference mods read them from. So a chat-only
 * implementation knows that a rare visitor was served and not what it gave. The caption therefore
 * carries the visitor and their rarity, which is the part chat does know, and the reels have the
 * visitor's own name to land on rather than an invented item.
 */
public final class GardenVisitorDetector extends RegistryDetector {

    /**
     * Captured, with section signs shown as bracketed letters:
     * "(6)(l)OFFER ACCEPTED (8)with (6)Sirius (8)[(6)(l)LEGENDARY(8)]", the square brackets
     * standing in for round ones.
     */
    private static final Pattern OFFER_ACCEPTED = Pattern.compile(
            "§6§lOFFER ACCEPTED §8with (?:§.)?(?<name>.*) "
                    + "§8\\((?<rarity>.*)\\)");

    /** The three tiers worth a spin. Verified visitor rarities; the rest are the everyday tier. */
    private static final Set<String> RARE_TIERS = Set.of("LEGENDARY", "MYTHIC", "SPECIAL");

    /** Longest known visitor name is comfortably inside this; a caption must stay bounded. */
    private static final int MAX_NAME_LENGTH = 32;

    public GardenVisitorDetector() {
        super(LootSource.GARDEN_VISITOR_RARE);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("OFFER ACCEPTED") < 0) {
            return Optional.empty();
        }
        Matcher matcher = OFFER_ACCEPTED.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String rarity = TextClean.clean(matcher.group("rarity")).toUpperCase(Locale.ROOT);
        if (!RARE_TIERS.contains(rarity)) {
            return Optional.empty();
        }
        String name = TextClean.clean(matcher.group("name"));
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(event(name + " (" + titleCase(rarity) + ")", nowMillis));
    }

    private static String titleCase(String rarity) {
        return rarity.charAt(0) + rarity.substring(1).toLowerCase(Locale.ROOT);
    }
}
