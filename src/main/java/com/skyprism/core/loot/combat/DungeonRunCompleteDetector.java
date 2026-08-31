package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Catacombs end-of-run summary header, which opens the block the essence lines sit in.
 *
 * <p><b>Default policy: NEVER, and that is the whole point of it existing.</b> This is the same run
 * as {@link DungeonBossDetector} arriving a few lines later, so shipping both armed means two rolls
 * per clear. The constant exists so a player can swap <em>which</em> of the pair fires -- the summary
 * arrives after the essence lines and therefore has more for the reels to lock onto -- but exactly
 * one of the two should ever be live at a time.
 *
 * <h2>It earns its keep even while disarmed</h2>
 * <p>The header is the only place the mode is spelled out in words, so this detector records the
 * floor and Master Mode into the shared {@link DungeonRunState} <b>before</b> deciding whether to
 * emit. Registering it with a NEVER policy therefore still improves
 * {@link DungeonBossDetector}'s caption from "Necron" to "Necron (Master Mode Floor VII)", at the
 * cost of one regex on a line that happens once per run.
 *
 * <p>Because the header <em>precedes</em> the defeat line in the summary block, the floor is already
 * recorded by the time the boss is captioned. That ordering is the reason this works at all; if
 * Hypixel ever reverses it, the caption falls back to the sidebar rather than going wrong.
 *
 * <p>Pattern verbatim from SkyHanni {@code features/dungeon/DungeonApi.kt}, matched against the
 * colour-stripped line: {@code \s+(?:Master Mode )?The Catacombs - (?:Floor [IV]{1,3}|Entrance)}.
 * The leading {@code \s+} is dropped here only because {@link TextClean#clean(String)} has already
 * trimmed it, and anchoring the cleaned line with {@code matches} is what keeps a party message from
 * driving this.
 */
public final class DungeonRunCompleteDetector extends ContextAwareDetector {

    /** The summary header, against the colour-stripped and whitespace-collapsed message. */
    public static final Pattern SUMMARY_HEADER = Pattern.compile(
            "(?<master>Master Mode )?The Catacombs - (?<floor>Floor [IV]{1,3}|Entrance)");

    private final DungeonRunState runState;

    /** Uses a private run state, so nothing downstream sees what this detector learns. */
    public DungeonRunCompleteDetector() {
        this(new DungeonRunState());
    }

    /**
     * @param runState shared with {@link DungeonBossDetector}, which reads the floor for its caption
     */
    public DungeonRunCompleteDetector(DungeonRunState runState) {
        super(LootSource.DUNGEON_RUN_COMPLETE);
        this.runState = runState;
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine.indexOf("The Catacombs - ") < 0) {
            return Optional.empty();
        }
        Matcher matcher = SUMMARY_HEADER.matcher(TextClean.clean(rawLine));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String floor = matcher.group("floor");
        boolean master = matcher.group("master") != null;
        // Recorded before the emit decision: this is useful to DungeonBossDetector even on the
        // shipped default, where this source is switched off and never rolls at all.
        runState.run(floor, master);
        return Optional.of(event(master ? "Master Mode " + floor : floor, nowMillis));
    }
}
