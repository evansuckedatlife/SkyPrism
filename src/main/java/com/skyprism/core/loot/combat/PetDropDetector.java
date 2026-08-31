package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code PET DROP!} banner, kept out of {@link MobRareDropDetector} so a pet can be captioned by
 * name and judged by its own rarity colour.
 *
 * <p><b>Default policy: ALWAYS.</b> A pet drop is rare by construction -- hours apart in ordinary
 * play, minutes apart only in a dedicated grind such as Pest slugs, Bal or ghosts -- so the banner
 * is already the rarity gate and there is no spam risk left to manage.
 *
 * <p><b>Reserve the three-of-a-kind flourish for the rarity colour, not for a list of names.</b>
 * {@link #rarityColour(String)} reads the code Hypixel puts directly in front of the pet, so
 * {@code §6} (legendary) and {@code §d} (mythic) can drive the celebration and it stays correct as
 * Hypixel adds pets. A hard-coded name list goes stale the first week a new pet ships.
 *
 * <h2>The pattern</h2>
 * <p>Verbatim from SkyHanni {@code features/chat/RareDropMessages.kt}, anchored:
 * <pre>
 *   (?&lt;start&gt;(?:§.)*PET DROP! )(?:§.)*§(?&lt;rarityColor&gt;.)(?&lt;petName&gt;[^§(.]+)(?&lt;end&gt;(?: .*)?)
 * </pre>
 * <p>The name class excluding {@code (} and {@code .} is deliberate and load-bearing: it is what
 * stops the trailing magic-find bracket, and the Garden's {@code §e(§e+78)} fortune tail, from
 * leaking into the caption. Real lines it is proved against:
 * <pre>
 *   §6§lPET DROP! §r§5Baby Yeti §r§b(+§r§b168% §r§b✯ Magic Find§r§b)
 *   §6§lPET DROP! §r§6Rat
 * </pre>
 * <p>Note the second: no suffix at all, which is why the tail group is optional.
 *
 * <h2>Two shapes it must not claim</h2>
 * <p>A pet <em>fished</em> arrives as "GOOD CATCH! You caught a [Lvl 1] ..." and belongs to the
 * fishing treasure source; it carries no {@code PET DROP!} at all, so the marker alone keeps them
 * apart. A pet somebody <em>else</em> got arrives as "... has obtained ... [Lvl 1] ...", which
 * {@link CombatChatGuards#announcesAnotherPlayer(String)} declines. Both are checked by test rather
 * than assumed.
 *
 * <p>The remaining overlap is the Garden's pest pet drop ("§6§lPET DROP! §r§6Slug §e(§e+78)"),
 * which is a genuine pet drop by shape and a Garden drop only by context. Register the Garden's
 * detector first; its own trailing-bracket pattern is the stricter match.
 */
public final class PetDropDetector extends ContextAwareDetector {

    /** The pet drop banner, anchored. See the class notes for the provenance of every group. */
    public static final Pattern PET_DROP = Pattern.compile(
            "(?:§.)*PET DROP! (?:§.)*§(?<rarityColor>.)(?<petName>[^§(.]+)(?: .*)?");

    public PetDropDetector() {
        super(LootSource.PET_DROP);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (CombatChatGuards.rejects(rawLine)) {
            return Optional.empty();
        }
        Matcher matcher = PET_DROP.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(event(matcher.group("petName"), nowMillis));
    }

    /**
     * The formatting code Hypixel put in front of the pet, e.g. {@code '6'} for a legendary.
     *
     * <p>Zero when the line is not a pet drop. The mapping is the vanilla rarity palette: {@code f}
     * common, {@code a} uncommon, {@code 9} rare, {@code 5} epic, {@code 6} legendary, {@code d}
     * mythic. Exposed rather than interpreted here because the jackpot rule, not the detector, is
     * where "which rarities deserve the flourish" belongs.
     */
    public static char rarityColour(String rawLine) {
        if (rawLine == null || rawLine.indexOf("PET DROP!") < 0) {
            return 0;
        }
        Matcher matcher = PET_DROP.matcher(rawLine);
        return matcher.matches() ? matcher.group("rarityColor").charAt(0) : 0;
    }
}
