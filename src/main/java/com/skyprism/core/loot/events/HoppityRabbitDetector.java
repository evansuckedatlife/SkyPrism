package com.skyprism.core.loot.events;

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
 * A Hoppity's Hunt rabbit revealed, with its rarity printed inside the line.
 *
 * <h2>Read this before touching the pattern: the formatting codes are UPPER CASE</h2>
 * <p>Hypixel really sends {@code \u00A7D\u00A7LHOPPITY'S HUNT \u00A77You found \u00A76Solomon
 * \u00A77(\u00A76\u00A7LLEGENDARY\u00A77)!} -- capital {@code D}, capital {@code L}. This is not a transcription
 * slip; it is what the reference corpus captured off the live server, on this one line, and it is
 * the reason a hand-rolled pattern that assumes lower-case codes produces a feature that passes its
 * own unit test and never fires in game. Every code position below therefore accepts either case.
 *
 * <h2>Default policy: ALWAYS, with the jackpot keyed on the rarity group</h2>
 * <p>The whole event is one SkyBlock season a year and the rarity is handed to us <em>inside the
 * line</em>, which is the best-behaved signal shape in the game: no item list, nothing that can
 * drift when Hypixel adds a rabbit. So the roll is ALWAYS and the three-of-a-kind flourish is
 * reserved for {@link #JACKPOT_RARITIES}. A player who finds a rabbit per egg too chatty gets
 * exactly the right behaviour by switching to {@code ON_RARE_BANNER}, which maps onto the same
 * rarity test with no extra parsing -- which is why {@link #isJackpotRarity(String)} is public.
 */
public final class HoppityRabbitDetector extends RegistryDetector {

    /**
     * A rabbit found, with its rarity in a group.
     *
     * <p>Derived from SkyHanni's {@code HoppityEggsManager} pattern
     * {@code \u00A7D\u00A7LHOPPITY'S HUNT \u00A77You found (?<name>.*) \u00A77\((?<rarityColor>\u00A7.)\u00A7L(?<rarity>.*)\u00A77\)!}
     * with two safety changes and no loosening: every format-code letter accepts either case, and
     * the name and rarity groups exclude section signs instead of using {@code .*}, so a greedy
     * group cannot swallow a following clause. Captured lines this must match:
     * {@code ...\u00A7fArnie \u00A77(\u00A7F\u00A7LCOMMON\u00A77)!}, {@code ...\u00A7aPenelope \u00A77(\u00A7A\u00A7LUNCOMMON\u00A77)!},
     * {@code ...\u00A76Solomon \u00A77(\u00A76\u00A7LLEGENDARY\u00A77)!}
     */
    private static final Pattern RABBIT = Pattern.compile(
            "\u00A7[Dd]\u00A7[Ll]HOPPITY'S HUNT \u00A77You found (?:\u00A7.)*(?<name>[^\u00A7]+?) "
                    + "\u00A77\\((?:\u00A7.)*\u00A7[Ll](?<rarity>[A-Za-z]+)\u00A77\\)!");

    /**
     * The rarities worth the casino flourish.
     *
     * <p>Keyed on the rarity word rather than on a list of rabbit names precisely so it cannot go
     * stale: Hypixel adds rabbits every year and has never added a rarity tier.
     */
    private static final Set<String> JACKPOT_RARITIES =
            Set.of("EPIC", "LEGENDARY", "MYTHIC", "DIVINE");

    public HoppityRabbitDetector() {
        super(LootSource.HOPPITY_RABBIT);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = RABBIT.matcher(rawLine);
        if (!m.matches()) {
            return Optional.empty();
        }
        String name = TextClean.clean(m.group("name"));
        return Optional.of(event(name.isEmpty() ? "Hoppity Rabbit" : name, nowMillis));
    }

    /**
     * The rarity word this line announced, if it is a rabbit line at all.
     *
     * <p>Exposed because the roll needs it and the widget wants it, and re-running the regex in two
     * places is how the two answers drift apart.
     */
    public static Optional<String> rarityOf(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = RABBIT.matcher(rawLine);
        return m.matches()
                ? Optional.of(m.group("rarity").toUpperCase(Locale.ROOT))
                : Optional.empty();
    }

    /** Whether a rarity word earns the three-of-a-kind celebration. */
    public static boolean isJackpotRarity(String rarity) {
        return rarity != null && JACKPOT_RARITIES.contains(rarity.trim().toUpperCase(Locale.ROOT));
    }
}
