package com.skyprism.core.loot.combat;

import com.skyprism.core.diana.LootParser;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;

import java.util.Optional;

/**
 * The catch-all: any mob, anywhere, whose drop carried the server's own rare-drop banner.
 *
 * <p>This one detector is what makes the feature SkyBlock-wide without a detector per mob. Zealot
 * summoning eyes, Golden Powder, Crystal Fragments, Hunks of Blue Ice, Beating Hearts, every slayer
 * sack drop, every dungeon floor drop, everything: an ordinary mob has no kill line at all, so the
 * banner <em>is</em> the trigger.
 *
 * <p><b>Default policy: ON_RARE_BANNER, which here is definitionally the only sane value.</b> The
 * detector only ever sees lines Hypixel itself flagged as rare, so ALWAYS would either mean exactly
 * the same thing or -- worse -- invite somebody to widen the regex until it matched common drops
 * too. Frequency ranges from once an hour idling to several times a minute in a Zealot or ghost
 * grind, and being banner-gated is the only reason that is tolerable.
 *
 * <h2>The two banner shapes, one of which the shipped parser gets wrong</h2>
 * <p>Hypixel prints rare drops in two different layouts, and the second is the one that matters:
 * <pre>
 *   plain:      §6§lRARE DROP! §r§9Judgement Core §r§b(+168% ✯ Magic Find)
 *   bracketed:  §b§lRARE DROP! §r§7(§r§f§r§9Revenant Viscera§r§7) (+168% ✯ Magic Find)
 *   bracketed:  §5§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) (+168% ✯ Magic Find)
 * </pre>
 * <p>Two things in there routinely break a hand-written banner regex, both verified against real
 * lines rather than reasoned about. The bracketed form wraps the item in parentheses, so a parser
 * expecting a colour code straight after the banner captures a bare "{@code (}" as the item name.
 * And {@code VERY RARE DROP!} and {@code CRAZY RARE DROP!} in bracketed form are followed by
 * <b>two</b> spaces, not one -- SkyHanni encodes that as a literal {@code {2}} in eleven separate
 * patterns, so it is not a typo. Those two banners are precisely the tiers a slot machine exists to
 * celebrate.
 *
 * <p>This detector sidesteps both by not decomposing the drop at all. Its job is the boolean "was
 * this line a rare-drop banner from ordinary combat"; naming the item is the shared banner parser's
 * job, and it needs both shapes for its own reasons. So it asks
 * {@link LootParser#bannerWordOf(String)} for the banner word and stops there -- deliberately not
 * {@code matchBanner}, because a shape the parser cannot yet decompose should still spin the
 * machine under this source's own caption rather than be lost. A wrong item name is a bug; a rare
 * drop that produces nothing at all is the bug the player actually reports.
 *
 * <p>It used to carry its own copy of the alternation -- a fourth encoding of one corpus, missing
 * {@code UNCOMMON DROP!} and written with a {@code *} leading run that an entirely unformatted line
 * satisfied. Sharing the vocabulary costs nothing here and is what stops the two drifting apart
 * again.
 *
 * <h2>What it declines, and why that list is code rather than registration order</h2>
 * <p>Several other sources print a line beginning with the same words. The bus resolves overlaps by
 * registration order, but relying on order alone means a future re-ordering silently steals another
 * source's events, so the ones that are knowable from the text are declined outright here:
 * Diana's treasure dig ("You dug out"), the Garden's Crop Fever ("You dropped"), the Garden's pest
 * drop ("Cocoaleech"), the Season of Jerry gift family ("gift with"), and every third-person
 * broadcast of somebody else's loot. See {@link CombatChatGuards}.
 *
 * <p>{@code PET DROP!} is excluded from the alternation deliberately: {@link PetDropDetector} owns
 * it, so it can caption the pet by name and colour it by rarity.
 *
 * <p>The one overlap left to registration order is the Garden's pest drop in its
 * {@code §e(§e+134)} form, which is a genuine rare mob drop by shape and only a Garden drop by
 * context. Register the Garden's detector before this one.
 *
 * <p>Evidence: SkyHanni {@code features/chat/ChatFilter.kt} lines 186-238 and
 * {@code features/combat/ghosttracker/GhostTracker.kt}; Skyblocker
 * {@code RareDropSpecialEffects.java} for the colon guard.
 */
public final class MobRareDropDetector extends ContextAwareDetector {

    /**
     * The one banner word in the shared vocabulary this source does not own.
     *
     * <p>{@link PetDropDetector} takes it, so it can caption the pet by name and colour it by
     * rarity. {@code UNCOMMON DROP!} is excluded by {@link LootParser#isRareBanner(String)}
     * instead: it is in the vocabulary so Crop Fever's smaller procs can be decomposed, and it is
     * not something to spin a machine for.
     */
    private static final String PET_BANNER = "PET DROP!";

    public MobRareDropDetector() {
        super(LootSource.MOB_RARE_DROP);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (CombatChatGuards.rejects(rawLine)) {
            return Optional.empty();
        }
        String banner = LootParser.bannerWordOf(rawLine);
        if (banner == null || PET_BANNER.equals(banner) || !LootParser.isRareBanner(banner)) {
            return Optional.empty();
        }
        // No kill line means no mob name; the source's own caption is the honest fallback, and
        // LootEvent.of documents exactly that.
        return Optional.of(LootEvent.of(source(), nowMillis));
    }
}
