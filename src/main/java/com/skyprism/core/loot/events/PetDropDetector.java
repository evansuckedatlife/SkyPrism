package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Any {@code PET DROP!} banner, anywhere in the game.
 *
 * <h2>Why this is a separate source from the generic catch-all</h2>
 * <p>Mechanically a pet drop is one more member of the universal banner family, and
 * {@link GenericRareDropDetector} would happily match it. It is split out anyway for three reasons
 * that are all about what the player sees: the widget can caption it "Pet Drop" rather than "Rare
 * Mob Drop", the reel can be coloured by the pet's own rarity code, and the player can switch pets
 * on while leaving the general banner off. A Foul Flesh and a Golden Dragon are not the same
 * feeling and should not share a switch.
 *
 * <h2>Default policy: ALWAYS</h2>
 * <p>A pet drop is rare by construction -- the banner <em>is</em> the rarity gate -- so there is no
 * spam risk and no reason to gate it further. Hours apart in normal play, minutes apart only in a
 * dedicated pet grind.
 *
 * <h2>The jackpot rule is the rarity colour, not a name list</h2>
 * <p>{@link #isJackpotRarityColor(String)} keys the three-of-a-kind on LEGENDARY ({@code \u00A76}) and
 * MYTHIC ({@code \u00A7d}), which stays correct as Hypixel adds pets. A hard-coded list of pet names
 * would be stale within a year, and a stale jackpot list fails silently -- the celebration simply
 * stops happening for new content, with nothing to notice.
 */
public final class PetDropDetector extends RegistryDetector {

    /** LEGENDARY and MYTHIC, as legacy colour codes; the two tiers worth the flourish. */
    private static final Set<String> JACKPOT_COLORS = Set.of("6", "d");

    public PetDropDetector() {
        super(LootSource.PET_DROP);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        Optional<RareDropBanner.Banner> banner = RareDropBanner.match(rawLine);
        if (banner.isEmpty() || !banner.get().pet()) {
            return Optional.empty();
        }
        // The caption is the source, not the pet: the pet name is the drop and belongs on the reel,
        // where the shared symbol path can render its real item art. Two homes for one string is how
        // a caption and a reel end up disagreeing.
        return Optional.of(event(nowMillis));
    }

    /** The pet named by a pet-drop line, for the reel, if this line is one. */
    public static Optional<RareDropBanner.Banner> petOf(String rawLine) {
        return RareDropBanner.match(rawLine).filter(RareDropBanner.Banner::pet);
    }

    /** Whether a pet's rarity colour code earns the three-of-a-kind celebration. */
    public static boolean isJackpotRarityColor(String colorCode) {
        return colorCode != null
                && JACKPOT_COLORS.contains(colorCode.trim().toLowerCase(Locale.ROOT));
    }
}
