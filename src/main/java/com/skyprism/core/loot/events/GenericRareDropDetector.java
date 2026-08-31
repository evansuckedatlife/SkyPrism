package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;

/**
 * The catch-all: any rare drop, from any content, that no more specific source claimed.
 *
 * <h2>This one detector is what makes the feature SkyBlock-wide</h2>
 * <p>The request was for every chance-based event in the game, and the naive reading of that is
 * twenty-five bespoke listeners. The reason it collapses instead is that Hypixel prints one
 * near-universal rare-drop banner across nearly all content -- zealot Summoning Eyes, Golden Powder,
 * Enchanted Ender Pearls, Crystal Fragments, Hunks of Blue Ice, Beating Hearts, Arachne's Keeper
 * Fragments, every slayer sack drop, everything. So this detector, plus {@link RareDropBanner},
 * covers the entire long tail with one compiled pattern and no per-mob code at all. The bespoke
 * detectors exist for the events that announce a <em>trigger</em> of their own; this one exists for
 * everything else, which is most of the game.
 *
 * <h2>Register it LAST</h2>
 * <p>The bus dispatches in registration order and the first event wins, which is exactly right: a
 * slayer drop is also a rare mob drop, and one line must not spin the machine twice. This detector
 * claims any banner nobody else wanted, so it belongs at the end of the order and
 * {@link EventDetectors#registerAll} puts it there. Registering it early would make it swallow every
 * more specific source in the mod, which is the single easiest way to break this feature.
 *
 * <h2>Default policy: ON_RARE_BANNER, which here is definitional</h2>
 * <p>The banner <em>is</em> the trigger, so the policy can only ever be satisfied and {@code ALWAYS}
 * would mean the same thing while inviting somebody to widen the regex until it did not. Ordinary
 * common drops carry no banner at all and are correctly invisible to this source.
 *
 * <h2>What it deliberately refuses</h2>
 * <ul>
 *   <li><b>Pet drops</b>, which {@link PetDropDetector} owns so they can be captioned and coloured
 *       as pets. Refused here as well as ordered after, so the split survives a wiring mistake.</li>
 *   <li><b>Diana treasure payouts.</b> Their {@code RARE DROP!} banner is followed by "You dug out",
 *       and {@link RareDropBanner} excludes that sentence outright -- the shipped Diana path owns
 *       those lines and this one must never race it.</li>
 *   <li><b>Somebody else's loot.</b> Party and co-op drops are third-person sentences, filtered in
 *       {@link RareDropBanner#isThirdPartyLine(String)}. Without that, five people's drops spin one
 *       person's machine.</li>
 *   <li><b>Container reward broadcasts.</b> {@code RARE REWARD!} is a different banner word and is
 *       not in the family's alternation, so chest sources keep their own line.</li>
 * </ul>
 *
 * <h2>The caption</h2>
 * <p>There is no kill line here -- an ordinary mob's drop announces the item and nothing about what
 * dropped it -- so the honest subject is the source's own display name. Inventing a mob name from
 * the item would be a guess presented as fact, and the drop itself already reaches the reels through
 * the shared drop parser.
 */
public final class GenericRareDropDetector extends RegistryDetector {

    public GenericRareDropDetector() {
        super(LootSource.MOB_RARE_DROP);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        Optional<RareDropBanner.Banner> banner = RareDropBanner.match(rawLine);
        if (banner.isEmpty() || banner.get().pet()) {
            return Optional.empty();
        }
        return Optional.of(event(nowMillis));
    }

    /** The decomposed drop this line announced, for the reels, if it is one this source claims. */
    public static Optional<RareDropBanner.Banner> dropOf(String rawLine) {
        return RareDropBanner.match(rawLine).filter(b -> !b.pet());
    }
}
