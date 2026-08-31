package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Catacombs boss defeated at the end of a run, in either mode, on any of the eight floors.
 *
 * <p><b>Default policy: ALWAYS.</b> One roll per run, and a run is three to ten minutes of committed
 * play that the player deliberately queued for. Nothing about that cadence can become noise, and the
 * caption carries the floor and the mode, so it reads as a run summary rather than as a slot pull.
 *
 * <p><b>What this source must not do.</b> The real loot of a dungeon run is in the reward chests,
 * and those belong to {@link LootSource#DUNGEON_REWARD_CHEST} and
 * {@link LootSource#CROESUS_CHEST}. If this source also rolled on a chest, every run would
 * double-fire. It rolls on the defeat line only. Its sibling
 * {@link LootSource#DUNGEON_RUN_COMPLETE} is the same run seen a few lines later and ships
 * disarmed for the same reason.
 *
 * <h2>The pattern</h2>
 * <p>Skyblocker's, verbatim, matched against the colour-stripped line:
 * <pre>
 *   ^\s*☠ Defeated (?&lt;boss&gt;.+) in 0?(?&lt;time&gt;[\dhms ]+?)\s*(?&lt;record&gt;\(NEW RECORD!\))?$
 * </pre>
 * <p>SkyHanni's looser twin uses {@code \w+} for the name, which truncates "The Professor" and "The
 * Watcher" to their article -- silent wrong output rather than silent no output, which is the harder
 * kind to notice. So the wide capture is kept and made safe the way {@link BossDownDetector} is: the
 * captured name must appear in the closed {@link DungeonBoss} table or the line is declined.
 *
 * <p>Evidence: Skyblocker {@code skyblock/dungeon/DungeonSplitsWidget.java}; boss and floor names
 * from SkyHanni {@code features/dungeon/DungeonFloor.kt}.
 */
public final class DungeonBossDetector extends ContextAwareDetector {

    /** The defeat line, against the colour-stripped and whitespace-collapsed message. */
    public static final Pattern DEFEATED = Pattern.compile(
            "☠ Defeated (?<boss>.+) in 0?(?<time>[\\dhms ]+?)"
                    + "\\s*(?<record>\\(NEW RECORD!\\))?");

    /**
     * The sidebar's floor token, e.g. {@code (F7)} or {@code (M7)} for Master Mode.
     *
     * <p>Applied to {@link com.skyprism.core.loot.GameContext#area()}, which is a server-controlled
     * sidebar string rather than anything a player can type, and only when a boss has already been
     * matched -- so {@code find} is both safe and rare here.
     */
    private static final Pattern SIDEBAR_FLOOR = Pattern.compile("\\((?<mode>[FM])(?<floor>\\d)\\)");

    private final DungeonRunState runState;

    /** Uses a private run state: the caption then falls back to the sidebar, or to the bare name. */
    public DungeonBossDetector() {
        this(new DungeonRunState());
    }

    /**
     * @param runState shared with {@link DungeonRunCompleteDetector}, which records the floor and
     *                 the mode from the summary header even when its own policy forbids it to roll
     */
    public DungeonBossDetector(DungeonRunState runState) {
        super(LootSource.DUNGEON_BOSS);
        this.runState = runState;
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine.indexOf("Defeated ") < 0) {
            return Optional.empty();
        }
        Matcher matcher = DEFEATED.matcher(TextClean.clean(rawLine));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        DungeonBoss boss = DungeonBoss.byDisplayName(matcher.group("boss")).orElse(null);
        if (boss == null) {
            return Optional.empty();
        }
        return Optional.of(event(caption(boss), nowMillis));
    }

    /** "Necron (Master Mode Floor VII)", or just "Necron" when the run is not identified. */
    private String caption(DungeonBoss boss) {
        String run = describeRun();
        return run.isEmpty() ? boss.displayName() : boss.displayName() + " (" + run + ")";
    }

    /**
     * The run description, preferring the summary header this build actually saw over the sidebar.
     *
     * <p>The header is the better source because it spells the mode out; the sidebar is the fallback
     * for a run whose header was never offered to us, which is the normal case when
     * {@link LootSource#DUNGEON_RUN_COMPLETE} is not registered at all.
     */
    public String describeRun() {
        if (runState.known()) {
            return runState.describe();
        }
        String area = context().area();
        if (area.isEmpty()) {
            return "";
        }
        Matcher matcher = SIDEBAR_FLOOR.matcher(area);
        if (!matcher.find()) {
            return "";
        }
        boolean master = matcher.group("mode").charAt(0) == 'M';
        String floor = "F" + matcher.group("floor");
        return master ? "Master Mode " + floor : floor;
    }
}
