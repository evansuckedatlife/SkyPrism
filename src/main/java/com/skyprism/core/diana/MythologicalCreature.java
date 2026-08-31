package com.skyprism.core.diana;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The twelve creatures that spawn from Griffin Burrows during Diana's Mythological Ritual.
 *
 * <p>Display names, the rare flag and the short aliases are taken verbatim from SkyHanni's
 * community-maintained constants ({@code constants/events/Diana.json}), which is the most
 * accurate public source for these strings. The colour code is the one the server uses for
 * the creature's name: {@code 2} (dark green) for ordinary creatures, {@code c} (red) for rare ones.
 *
 * <p>Aliases are used by config and by {@code /skyprism simulate}; they are matched
 * case-insensitively and must stay unambiguous across the whole enum.
 */
public enum MythologicalCreature {

    GAIA_CONSTRUCT("Gaia Construct", false, "2", List.of("gaia", "construct")),
    MINOTAUR("Minotaur", false, "2", List.of("minotaur", "taur")),
    MINOS_CHAMPION("Minos Champion", false, "2", List.of("champion", "champ")),
    SIAMESE_LYNXES("Siamese Lynxes", false, "2", List.of("siamese", "lynx", "lynxes")),
    MINOS_HUNTER("Minos Hunter", false, "2", List.of("hunter")),
    CRETAN_BULL("Cretan Bull", false, "2", List.of("cretan", "bull")),
    HARPY("Harpy", false, "2", List.of("harpy")),
    STRANDED_NYMPH("Stranded Nymph", false, "2", List.of("stranded", "nymph")),

    SPHINX("Sphinx", true, "c", List.of("sphinx")),
    MINOS_INQUISITOR("Minos Inquisitor", true, "c", List.of("inquisitor", "inq", "inquis")),
    KING_MINOS("King Minos", true, "c", List.of("king", "minos")),
    MANTICORE("Manticore", true, "c", List.of("manticore", "manti", "core"));

    private final String displayName;
    private final boolean rare;
    private final String colorCode;
    private final List<String> aliases;

    MythologicalCreature(String displayName, boolean rare, String colorCode, List<String> aliases) {
        this.displayName = displayName;
        this.rare = rare;
        this.colorCode = colorCode;
        this.aliases = List.copyOf(aliases);
    }

    public String displayName() {
        return displayName;
    }

    /** True for the four creatures Hypixel treats as rare: Sphinx, Inquisitor, King Minos, Manticore. */
    public boolean rare() {
        return rare;
    }

    /** Legacy colour code the server paints the name with, without the section sign. */
    public String colorCode() {
        return colorCode;
    }

    public List<String> aliases() {
        return aliases;
    }

    /**
     * Case-insensitive match on the display name, e.g. "Minos Inquisitor".
     *
     * <p>A leading indefinite article is stripped first. The spawn line reads "You dug out a
     * &#167;r&#167;2Minotaur", and whether Hypixel writes that "a" <em>outside</em> the coloured run
     * or <em>inside</em> it decides whether the article lands in the regex's creature group -- a
     * detail nothing in this project has ever seen on the wire. Getting it wrong is not a
     * cosmetic miss: {@code matchSpawn} would return empty for every one of the twelve creatures,
     * the tracker would never arm, and the whole Diana trigger path would be silently dead with
     * nothing logged and no test failing. Tolerating the article here costs one comparison and
     * makes the answer independent of that guess. No creature's own name begins with "a " or
     * "an ", so nothing legitimate is shortened.
     *
     * @param name the captured name, with or without a leading article; may be null
     * @return the creature, or empty when nothing matches
     */
    public static Optional<MythologicalCreature> byDisplayName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = stripArticle(name.trim());
        for (MythologicalCreature c : values()) {
            if (c.displayName.equalsIgnoreCase(needle)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** Removes a leading "a " or "an ", case-insensitively; see {@link #byDisplayName(String)}. */
    private static String stripArticle(String name) {
        if (name.length() > 2 && (name.charAt(0) == 'a' || name.charAt(0) == 'A')) {
            if (name.charAt(1) == ' ') {
                return name.substring(2).trim();
            }
            if (name.length() > 3 && (name.charAt(1) == 'n' || name.charAt(1) == 'N')
                    && name.charAt(2) == ' ') {
                return name.substring(3).trim();
            }
        }
        return name;
    }

    /** Case-insensitive match on the display name or any alias, e.g. "manti" or "inq". */
    public static Optional<MythologicalCreature> byNameOrAlias(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String needle = token.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        Optional<MythologicalCreature> exact = byDisplayName(needle);
        if (exact.isPresent()) {
            return exact;
        }
        for (MythologicalCreature c : values()) {
            if (c.aliases.contains(needle)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** The default set the slot machine rolls on: the three rare creatures worth celebrating. */
    public static List<MythologicalCreature> defaultTriggers() {
        return List.of(MINOS_INQUISITOR, KING_MINOS, MANTICORE);
    }
}
