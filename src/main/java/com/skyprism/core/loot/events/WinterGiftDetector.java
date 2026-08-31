package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Season of Jerry gift opened.
 *
 * <h2>The best-behaved source found anywhere in SkyBlock</h2>
 * <p>Hypixel prints the rarity word itself, in the first field of the line: {@code COMMON!},
 * {@code RARE!}, {@code SWEET!}, {@code SANTA TIER!}, {@code PARTY TIER!}. That means this source
 * needs no item list at all, and nothing about it can drift when the gift loot table changes -- the
 * server does the rarity classification and we take it at its word. Every other high-frequency
 * source in the mod would love to be shaped like this one.
 *
 * <h2>Default policy: ON_RARE_BANNER, meaning "not COMMON"</h2>
 * <p>A gifting session opens gifts in stacks of dozens and COMMON is the overwhelming majority, so
 * {@code ALWAYS} would be pure noise. {@link #isRareTier(String)} is what the policy keys on:
 * SWEET, SANTA TIER and PARTY TIER are the ones people screenshot. RARE is deliberately excluded
 * despite the word -- it is the second of five tiers and is common in practice, which is a good
 * reminder that Hypixel's tier names are marketing rather than a rarity scale.
 *
 * <h2>One line this cannot see, stated rather than hidden</h2>
 * <p>The bonus line {@code \u00A75\u00A7lEXTRA! \u00A7d+5 North Stars} carries no "gift with" clause,
 * and "gift with" is the marker the registry declares for this source. The bus's pre-filter is
 * derived from those markers, so an EXTRA line never reaches this detector. That is a deliberate,
 * documented gap rather than an oversight: it is a bonus attached to a gift that already rolled, so
 * losing it costs one duplicate spin and nothing else, and widening the marker to {@code "!"} would
 * put this detector on the path of every exclamation in the game.
 */
public final class WinterGiftDetector extends RegistryDetector {

    /**
     * Any gift-opening line, tier captured.
     *
     * <p>Assembled from the six shapes SkyHanni's {@code GiftProfitTracker} carries -- coins, skill
     * XP, XP-boost potion, enchantment book, generic item, and the North Stars bonus -- all of which
     * share one skeleton: a bold tier word, the reward, then {@code gift with <player>}. Matching
     * the skeleton once rather than the six payloads separately is what keeps this source immune to
     * a new payload type appearing.
     *
     * <p>Captured lines: {@code \u00A79\u00A7lRARE! \u00A7r\u00A76+20,000 Coins \u00A7r\u00A7egift with \u00A7r\u00A7a[VIP\u00A7r\u00A76+\u00A7r\u00A7a]
     * Grazma\u00A7r\u00A7f\u00A7r\u00A7e!}, {@code \u00A7f\u00A7lCOMMON! \u00A7r\u00A73+500 Enchanting XP \u00A7r\u00A7egift with \u00A7r...},
     * {@code \u00A7e\u00A7lSWEET! \u00A7r\u00A75Snow Suit Helmet \u00A7r\u00A7egift with ...},
     * {@code \u00A79\u00A7lRARE! \u00A7r\u00A7f◆ Ice Rune \u00A7r\u00A7egift with ...}
     */
    private static final Pattern GIFT = Pattern.compile(
            "\u00A7.\u00A7l(?<tier>COMMON|RARE|SWEET|SANTA TIER|PARTY TIER)! "
                    + "\u00A7r\u00A7.(?<reward>[^\u00A7]+) \u00A7r\u00A7egift with \u00A7r.*");

    /**
     * The tiers that earn a spin under {@code ON_RARE_BANNER}.
     *
     * <p>A set of three words, not a list of items: the whole value of this source is that it can
     * never need updating.
     */
    private static final Set<String> RARE_TIERS = Set.of("SWEET", "SANTA TIER", "PARTY TIER");

    public WinterGiftDetector() {
        super(LootSource.WINTER_GIFT);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = GIFT.matcher(rawLine);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(event(m.group("tier") + " Gift", nowMillis));
    }

    /** The tier word a gift line announced, if it is one. */
    public static Optional<String> tierOf(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = GIFT.matcher(rawLine);
        return m.matches() ? Optional.of(m.group("tier")) : Optional.empty();
    }

    /** Whether a tier word is one the machine should spin for. */
    public static boolean isRareTier(String tier) {
        return tier != null && RARE_TIERS.contains(tier.trim().toUpperCase(Locale.ROOT));
    }
}
