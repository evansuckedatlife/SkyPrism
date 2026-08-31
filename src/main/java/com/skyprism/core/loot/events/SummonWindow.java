package com.skyprism.core.loot.events;

/**
 * A short "something was just summoned, its loot is imminent" flag, used by the two seasonal bosses
 * whose kill line nobody has ever captured.
 *
 * <h2>Why this exists at all</h2>
 * <p>The Reindrake and the Primal Fear both announce their <em>summon</em> in chat and neither
 * announces its death. That leaves two honest options and one dishonest one. Rolling on the summon
 * is wrong: both broadcasts fire lobby-wide, for bystanders who never touch the fight. Inventing a
 * kill regex is worse -- it would look like a working feature and silently never fire, which is the
 * single failure mode this whole design exists to avoid. So the third option: the summon <b>arms</b>
 * the source for a bounded window, and the next universal rare-drop banner inside that window is
 * attributed to it. That is exactly what the registry's own note for both sources prescribes ("the
 * drop banner carries it until a kill line is verified"), and it is the only shape under which their
 * shipped {@code ON_RARE_BANNER} policy can ever be satisfied.
 *
 * <h2>The two properties that keep it from stealing other sources' lines</h2>
 * <ul>
 *   <li><b>It is bounded.</b> The window closes on its own, so a detector cannot sit armed for an
 *       hour quietly relabelling every slayer drop in the game.</li>
 *   <li><b>It is single-shot.</b> {@link #claim(long)} disarms as it succeeds, so one summon
 *       produces at most one roll. A boss that drops three things gives one spin, not three, and
 *       the tail of the fight is handed back to the generic catch-all.</li>
 * </ul>
 *
 * <p>The honest caveat, which belongs in the caption and in the docs rather than being hidden: the
 * summon broadcast carries no ownership, so a bystander standing in the lobby when somebody else
 * summons can have their next rare drop captioned with that boss. The alternatives were "never
 * fires" or "fires for a spawn the player had nothing to do with", and this is the least wrong of
 * the three.
 *
 * <p>Holds one {@code long}. Not thread safe, like everything else on the chat path.
 */
final class SummonWindow {

    /** Nothing is armed until a summon line arrives. */
    private long armedUntil = Long.MIN_VALUE;

    /** Arms the window for {@code windowMillis} from {@code nowMillis}. */
    void arm(long nowMillis, long windowMillis) {
        armedUntil = nowMillis + windowMillis;
    }

    /** Whether a summon is still in its window, without consuming it. */
    boolean armed(long nowMillis) {
        return nowMillis <= armedUntil;
    }

    /**
     * Consumes the window if it is open.
     *
     * @return true exactly once per summon, and only inside the window
     */
    boolean claim(long nowMillis) {
        if (nowMillis > armedUntil) {
            return false;
        }
        armedUntil = Long.MIN_VALUE;
        return true;
    }

    /** Drops the window, e.g. when the player leaves the island the summon happened on. */
    void disarm() {
        armedUntil = Long.MIN_VALUE;
    }
}
