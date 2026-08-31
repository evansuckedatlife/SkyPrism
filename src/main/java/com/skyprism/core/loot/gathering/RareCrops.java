package com.skyprism.core.loot.gathering;

import java.util.List;
import java.util.Optional;

/**
 * The twenty-seven drops Hypixel announces with the RARE CROP banner in the Garden.
 *
 * <p>The list is SkyHanni's own {@code RareCropDropType} enum, read entry for entry
 * (garden.rarecrops.*), which builds one colourless pattern per name from the template
 * "(?:VERY )?RARE CROP! &lt;name&gt;(?: .*)?". Reproducing the template once and matching the name
 * against this list is the same thing with one compiled pattern instead of twenty-seven.
 *
 * <p>The order matters in one place only, and it is worth saying: {@link #startingWith(String)}
 * returns the <em>longest</em> match, so "Salted Sunflower Seeds" cannot be shadowed by a shorter
 * name that happens to be a prefix of it. Nothing in the current list actually is, but a list that
 * only works because of an accident of its contents is one entry away from a silent mis-caption.
 */
public final class RareCrops {

    /**
     * Every verified name. Six of them -- Cropie, Squash, Fermento, Helianthus, Seasoning and the
     * pest drops -- are the everyday tier; Warty, Fermento, Helianthus and the Rarefinder Chip are
     * the ones the registry marks as jackpots.
     */
    public static final List<String> NAMES = List.of(
            "Cropie",
            "Squash",
            "Fermento",
            "Helianthus",
            "Seasoning",
            "Cornucopia",
            "Carrot Zest",
            "Deepfries",
            "Aggourdian",
            "Cane Knot",
            "Melon Juice",
            "Cactus Flower",
            "Designer Coffee Beans",
            "Feastfungus",
            "Botroot",
            "Salted Sunflower Seeds",
            "Crystalized Moonlight",
            "Floral Gelatin",
            "Rarefinder Chip",
            "Burrowing Spores",
            "Warty",
            "Compost",
            "Plant Matter",
            "Dung",
            "Honey Jar",
            "Tasty Cheese",
            "Jelly");

    private RareCrops() {
    }

    /**
     * The crop a banner tail names, if any.
     *
     * <p>The tail is everything after the banner word, so it is the name plus whatever Hypixel put
     * after it -- a farming fortune bracket, "(automatically donated)", or nothing at all. A name
     * matches when the tail is exactly it or the tail continues with a space, which is what keeps
     * "Cropie (+97)" a Cropie and stops "Cropiexyz" from being one.
     *
     * @param tail the text after "RARE CROP! ", formatting already stripped
     * @return the crop name, or empty when the tail names nothing known
     */
    public static Optional<String> startingWith(String tail) {
        if (tail == null || tail.isEmpty()) {
            return Optional.empty();
        }
        String best = null;
        for (int i = 0; i < NAMES.size(); i++) {
            String name = NAMES.get(i);
            if (!tail.startsWith(name)) {
                continue;
            }
            if (tail.length() != name.length() && tail.charAt(name.length()) != ' ') {
                continue;
            }
            if (best == null || name.length() > best.length()) {
                best = name;
            }
        }
        return Optional.ofNullable(best);
    }
}
