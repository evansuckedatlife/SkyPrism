package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.util.TextClean;

import java.util.Optional;

/**
 * A slayer quest boss slain -- all six bosses, all five tiers, one pair of literals.
 *
 * <p><b>Default policy: ALWAYS.</b> This is the closest analogue in the game to the shipped Diana
 * behaviour: a deliberate, discrete, self-initiated kill the player is actively waiting on, arriving
 * every thirty seconds to three minutes depending on tier and gear -- the same cadence as a burrow
 * chain, which is the calibration point that already works on the live server.
 *
 * <p><b>Why not ON_RARE_BANNER.</b> It would gut the source. The entire point of a slayer run is the
 * moment of truth on a boss that usually drops nothing, and reels stopping on No Drop is the honest
 * -- and frankly the funnier -- outcome. The right knob for the one population a per-boss roll could
 * annoy, a T1 Revenant farmer, is {@link #minimumTier(int)}: a tier floor fixes exactly that
 * population without touching anyone else.
 *
 * <h2>The two lines, and why only one roll comes out</h2>
 * <p>Hypixel prints, in sequence:
 * <pre>
 *   "  \u00A7r\u00A76\u00A7lNICE! SLAYER BOSS SLAIN!"
 *   "  \u00A7r\u00A7a\u00A7lSLAYER QUEST COMPLETE!"
 * </pre>
 * <p>The first is the kill and the second is the bookkeeping. Firing on the first is what puts the
 * drop lines that follow inside the roll's loot window; firing on both would double-roll every boss.
 * So the second is matched -- it is the fallback if the first is ever filtered or reworded -- but
 * suppressed inside {@link #PAIR_WINDOW_MILLIS} of a roll this detector already produced.
 *
 * <p>Both are compared as <b>exact equality against the cleaned line</b> rather than by regex.
 * Skyblocker does the same, and exact equality is the strongest anchoring available: a party message
 * cleans to "Party &gt; Steve: NICE! SLAYER BOSS SLAIN!", which is simply not equal to the literal.
 * It also means "SLAYER QUEST STARTED!" and "SLAYER QUEST FAILED!" -- real lines, one letter apart
 * in shape -- cannot be confused for a completion. Cleaning allocates, which is fine: it happens
 * only on a line that already carried one of two very distinctive markers.
 *
 * <p>Evidence: SkyHanni {@code features/chat/ChatFilter.kt} and {@code data/SlayerApi.kt};
 * Skyblocker {@code skyblock/slayers/SlayerManager.java}, which matches these as {@code stripLeading}
 * literals.
 */
public final class SlayerBossDetector extends ContextAwareDetector {

    /** The kill line, formatting stripped and whitespace collapsed. */
    public static final String BOSS_SLAIN = "NICE! SLAYER BOSS SLAIN!";

    /** The bookkeeping line that follows it within the same tick. */
    public static final String QUEST_COMPLETE = "SLAYER QUEST COMPLETE!";

    /**
     * How long after a roll the paired second line is treated as the same event.
     *
     * <p>The two lines arrive in one tick, so anything above a few hundred milliseconds is enough;
     * three seconds is chosen to survive a client hitch without ever reaching the next boss, which
     * is thirty seconds away at the very fastest.
     */
    public static final long PAIR_WINDOW_MILLIS = 3_000L;

    private final SlayerQuestState questState;
    private int minimumTier = 1;

    /**
     * Whether this detector has ever rolled, kept separate from the timestamp on purpose.
     *
     * <p>A sentinel timestamp would be the obvious implementation and a silent bug: {@code now -
     * Long.MIN_VALUE} overflows to a negative number, which is less than the window, so the very
     * first kill of every session would be suppressed as though it were the second half of a pair.
     */
    private boolean hasFired;
    private long lastFiredMillis;

    /** Uses a private quest state, which nothing reports to: the detector stays armed everywhere. */
    public SlayerBossDetector() {
        this(new SlayerQuestState());
    }

    /**
     * @param questState the shared sidebar-backed state; see {@link SlayerQuestState} for why an
     *                   unreported state deliberately leaves this detector armed
     */
    public SlayerBossDetector(SlayerQuestState questState) {
        super(LootSource.SLAYER_BOSS);
        this.questState = questState;
    }

    /**
     * Sets the lowest tier that may roll, 1 to 5.
     *
     * <p>The tier is read from the sidebar quest, so a floor above 1 silently has no effect while
     * nothing reports quest state -- {@link SlayerQuest#atLeastTier(int)} passes an unknown tier on
     * purpose, because suppressing a roll on a fact we do not have would be the wrong direction of
     * error.
     */
    public SlayerBossDetector minimumTier(int tier) {
        this.minimumTier = Math.max(1, Math.min(5, tier));
        return this;
    }

    /** The configured tier floor. */
    public int minimumTier() {
        return minimumTier;
    }

    /**
     * Open in SkyBlock, and -- once anything reports quest state -- only while a quest is running.
     *
     * <p>{@code super.gateOpen} first, both to cache the context and because the coarse test is the
     * cheap one.
     */
    @Override
    public boolean gateOpen(GameContext ctx) {
        return super.gateOpen(ctx) && questState.mayBeActive();
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        String cleaned = TextClean.clean(rawLine);
        boolean slain = BOSS_SLAIN.equals(cleaned);
        if (!slain && !QUEST_COMPLETE.equals(cleaned)) {
            return Optional.empty();
        }
        if (hasFired && nowMillis - lastFiredMillis < PAIR_WINDOW_MILLIS) {
            // The second half of the pair, or a duplicate. One kill, one roll.
            return Optional.empty();
        }
        SlayerQuest quest = questState.quest().orElse(null);
        if (quest != null && !quest.atLeastTier(minimumTier)) {
            return Optional.empty();
        }
        hasFired = true;
        lastFiredMillis = nowMillis;
        return Optional.of(event(questState.captionOr(info().displayName()), nowMillis));
    }
}
