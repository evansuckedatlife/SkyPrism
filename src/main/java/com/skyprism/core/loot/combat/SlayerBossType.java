package com.skyprism.core.loot.combat;

import java.util.Locale;
import java.util.Optional;

/**
 * The six slayer bosses, as the sidebar's "Slayer Quest" section names them.
 *
 * <p>Hypixel prints the <em>same two lines</em> when any of the six dies, at any tier, so this enum
 * exists purely so a roll can be captioned with what the player was actually fighting and so a
 * per-slayer minimum-tier floor has something to key on. It is not part of the match.
 *
 * <p>The display names are the exact strings the sidebar shows before the tier numeral, which is
 * what {@link SlayerQuest#parse(String)} is handed.
 */
public enum SlayerBossType {

    /** Zombie. Graveyard, Revenant Cave and the Crypts. */
    REVENANT_HORROR("Revenant Horror"),
    /** Spider. Spider Mound, Arachne's Burrow and Arachne's Sanctuary. */
    TARANTULA_BROODFATHER("Tarantula Broodfather"),
    /** Wolf. The Ruins, Howling Cave. */
    SVEN_PACKMASTER("Sven Packmaster"),
    /** Enderman. Void Sepulture, Zealot Bruiser Hideout, Dragon's Nest. */
    VOIDGLOOM_SERAPH("Voidgloom Seraph"),
    /** Blaze. The Stronghold, Smoldering Tomb, The Wasteland. */
    INFERNO_DEMONLORD("Inferno Demonlord"),
    /** Vampire. Stillgore Chateau and the Oubliette, in The Rift. */
    RIFTSTALKER_BLOODFIEND("Riftstalker Bloodfiend");

    private final String displayName;

    SlayerBossType(String displayName) {
        this.displayName = displayName;
    }

    /** The sidebar spelling, e.g. "Voidgloom Seraph". */
    public String displayName() {
        return displayName;
    }

    /**
     * Looks a boss up by its sidebar spelling, case-insensitively.
     *
     * <p>Unknown yields empty rather than throwing: a slayer Hypixel adds after this build shipped
     * must degrade to an uncaptioned roll, never to a crash on the chat path.
     */
    public static Optional<SlayerBossType> byDisplayName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (SlayerBossType type : values()) {
            if (type.displayName.toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
