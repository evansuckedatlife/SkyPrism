package com.skyprism.core.loot.combat;

/**
 * Which floor the current Catacombs run is, and whether it is Master Mode -- the two facts the
 * defeat line does not carry and the caption most wants.
 *
 * <p>"Defeated Necron in 5m 43s" is identical on Floor VII and on Master Mode Floor VII, and a
 * widget that cannot tell them apart has dropped the part of the run the player is proudest of. The
 * mode lives in two other places: the end-of-run summary header ("Master Mode The Catacombs - Floor
 * VII"), which {@link DungeonRunCompleteDetector} reads and writes here, and the sidebar
 * ("{@code ⏣ The Catacombs (M7)}"), which reaches {@link DungeonBossDetector} through the
 * game context.
 *
 * <p>Sharing one small mutable holder between the pair is what lets exactly one of them be armed --
 * they are the same run seen a few lines apart, and shipping both would double-roll every clear --
 * while the disarmed one still contributes what it knows. {@link DungeonRunCompleteDetector} records
 * the floor even on the runs where its own policy forbids it to roll.
 *
 * <p>Everything here degrades to "unknown", never to a guess: an unknown floor produces the bare
 * boss name as the caption rather than an invented one.
 *
 * <p><b>Threading:</b> client thread only, like the rest of this path.
 */
public final class DungeonRunState {

    private String floor = "";
    private boolean masterMode;

    /** The floor in sidebar spelling -- "Floor VII", "Entrance" -- or empty when not known. */
    public String floor() {
        return floor;
    }

    /** Whether the current run is Master Mode. False also means "not known". */
    public boolean masterMode() {
        return masterMode;
    }

    /** Whether anything is known about the current run. */
    public boolean known() {
        return !floor.isEmpty();
    }

    /** Records what the summary header or the sidebar said about this run. */
    public void run(String floor, boolean masterMode) {
        this.floor = floor == null ? "" : floor.trim();
        this.masterMode = masterMode;
    }

    /** Forgets the run, e.g. on leaving the dungeon. */
    public void clear() {
        floor = "";
        masterMode = false;
    }

    /**
     * The run description for a caption, e.g. "Master Mode Floor VII"; empty when nothing is known.
     */
    public String describe() {
        if (floor.isEmpty()) {
            return "";
        }
        return masterMode ? "Master Mode " + floor : floor;
    }
}
