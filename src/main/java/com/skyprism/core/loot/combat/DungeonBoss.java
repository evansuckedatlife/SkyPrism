package com.skyprism.core.loot.combat;

import java.util.Locale;
import java.util.Optional;

/**
 * The eight Catacombs bosses, which are the closed set a defeat line's name capture is checked
 * against.
 *
 * <p>Master Mode does not add bosses -- it reuses the same eight, which is why one table covers both
 * modes and why the mode has to come from somewhere other than the defeat line. See
 * {@link DungeonRunState}.
 *
 * <p>Note "The Professor" and "The Watcher": SkyHanni's looser form of the defeat regex uses
 * {@code \w+} for the name and truncates both to their article. That is exactly the silent-wrong
 * -output failure the shipped {@code LootParser} javadoc warns about for a different line, so
 * Skyblocker's {@code .+} form is used here and the closed table below is what makes the wider
 * capture safe.
 */
public enum DungeonBoss {

    /** Entrance, "F0". */
    THE_WATCHER("The Watcher", "Entrance"),
    /** Floor I. */
    BONZO("Bonzo", "F1"),
    /** Floor II. */
    SCARF("Scarf", "F2"),
    /** Floor III. */
    THE_PROFESSOR("The Professor", "F3"),
    /** Floor IV. */
    THORN("Thorn", "F4"),
    /** Floor V. */
    LIVID("Livid", "F5"),
    /** Floor VI. */
    SADAN("Sadan", "F6"),
    /** Floor VII. */
    NECRON("Necron", "F7");

    private final String displayName;
    private final String floor;

    DungeonBoss(String displayName, String floor) {
        this.displayName = displayName;
        this.floor = floor;
    }

    /** The boss name exactly as the defeat line spells it, e.g. "The Professor". */
    public String displayName() {
        return displayName;
    }

    /** The floor this boss guards, in sidebar spelling: "F7", or "Entrance". */
    public String floor() {
        return floor;
    }

    /**
     * Looks a boss up by the defeat line's name capture, case-insensitively.
     *
     * <p>Unknown yields empty, and the caller must then decline the line rather than caption it. A
     * boss Hypixel adds after this build shipped degrades to no roll, never to a roll captioned with
     * an arbitrary string out of a chat line.
     */
    public static Optional<DungeonBoss> byDisplayName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (DungeonBoss boss : values()) {
            if (boss.displayName.toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(boss);
            }
        }
        return Optional.empty();
    }
}
