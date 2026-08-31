package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Hoppity's Hunt chocolate meal egg found in the world.
 *
 * <h2>Default policy: ALWAYS</h2>
 * <p>Three meal eggs -- Breakfast, Lunch, Dinner -- per SkyBlock day per island, which is roughly
 * twenty real minutes apart, and every one of them is a discrete reward event with a guaranteed
 * rabbit inside. That is the same cadence as a Diana burrow chain, the calibration point the player
 * already finds delightful, and nothing here fires often enough to become noise. The counter-case
 * for this event family is the Chocolate Factory, which is several strays a minute and is therefore
 * a completely different source with a completely different default; see
 * {@link ChocolateFactoryStrayDetector}.
 *
 * <h2>Gate</h2>
 * <p>Hoppity's Hunt runs for one SkyBlock season a year, so the honest gate is "during Spring, and
 * not on the disallowed islands" (The Rift, Kuudra, the Catacombs, a Mineshaft, the Safari). The
 * shared {@code GameContext} carries no season token yet, so the registry's gate is the coarse
 * "in SkyBlock" and the distinctive marker does the rest -- armed out of season, but never wrong.
 * The moment a season token exists this detector needs no change: the gate is data, not code.
 *
 * <h2>What is deliberately not matched</h2>
 * <p>The rabbit that comes out of the egg is {@link HoppityRabbitDetector}'s line, not this one, and
 * the two arrive one after the other. Matching both here would double-roll a single egg.
 */
public final class HoppityEggDetector extends RegistryDetector {

    /**
     * An egg found in the world.
     *
     * <p>Verbatim from SkyHanni's {@code HoppityEggsManager}, whose own captured test line is
     * {@code \u00A7d\u00A7lHOPPITY'S HUNT \u00A7r\u00A7dYou found a \u00A7r\u00A79Chocolate Lunch Egg \u00A7r\u00A7don a ledge next to the
     * stairs up\u00A7r\u00A7d!}. The meal group admits {@code é} because Hypixel's meal names are Breakfast,
     * Lunch and Dinner today but the corpus's own character class allows an accented one, and
     * narrowing a verified pattern is how a detector silently stops firing after an update.
     */
    private static final Pattern MEAL_EGG = Pattern.compile(
            "\u00A7d\u00A7lHOPPITY'S HUNT \u00A7r\u00A7dYou found a \u00A7r\u00A7.Chocolate (?<meal>[\\wé]+) Egg \u00A7r\u00A7d.*\u00A7r\u00A7d!");

    /**
     * The Hitman egg, bought rather than found but announced through the same banner.
     *
     * <p>Verbatim from the same file. Kept as a second pattern rather than folded into the first
     * because its shape genuinely differs -- there is no meal name and no location clause -- and a
     * single pattern loose enough to cover both would be loose enough to cover a rabbit line too.
     */
    private static final Pattern HITMAN_EGG = Pattern.compile(
            "\u00A7d\u00A7lHOPPITY'S HUNT \u00A7r\u00A7dYou found a (?:\u00A7.)+Hitman Egg(?:\u00A7.)+!");

    public HoppityEggDetector() {
        super(LootSource.HOPPITY_MEAL_EGG);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher meal = MEAL_EGG.matcher(rawLine);
        if (meal.matches()) {
            return Optional.of(event("Chocolate " + meal.group("meal") + " Egg", nowMillis));
        }
        if (HITMAN_EGG.matcher(rawLine).matches()) {
            return Optional.of(event("Hitman Egg", nowMillis));
        }
        return Optional.empty();
    }
}
