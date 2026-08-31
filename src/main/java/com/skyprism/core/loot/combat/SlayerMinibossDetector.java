package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.util.TextClean;

import java.util.Optional;

/**
 * A slayer miniboss during the grind phase.
 *
 * <p><b>Default policy: NEVER, and it ships switched off.</b> Same island as
 * {@link SlayerBossDetector}, same quest, opposite cadence: minibosses spawn continuously and die
 * several times a minute, so rolling on each would turn the widget into a strobe within a minute of
 * a slayer session. It is enumerated and switchable because a player who specifically farms
 * minibosses may want it, and ON_RARE_BANNER is the sensible second choice there -- the drops arrive
 * on the ordinary banner, so the server's own rarity classification is available.
 *
 * <h2>An unverified pattern, stated as such</h2>
 * <p><b>{@link #MINIBOSS_PREFIX} is a prefix match, not a whole line, because the rest of the line
 * is not verified.</b> Skyblocker matches this with a {@code startsWith} and neither reference mod
 * records what follows "SLAYER MINI-BOSS", so writing a tail here would be inventing one. A prefix
 * is weaker anchoring than the exact equality {@link SlayerBossDetector} uses, which is why the
 * cleaned line is tested rather than the raw one: a party message cleans to
 * "Party &gt; Steve: SLAYER MINI-BOSS ..." and so does not start with the literal.
 *
 * <p>The assist line, by contrast, is a verified verbatim literal and is matched by equality.
 *
 * <p>Evidence: Skyblocker {@code skyblock/slayers/SlayerManager.java} for the prefix; SkyHanni
 * {@code features/chat/ChatFilter.kt} for the assist literal.
 */
public final class SlayerMinibossDetector extends ContextAwareDetector {

    /** The verified prefix of the kill line. <b>Unverified beyond it</b> -- see the class notes. */
    public static final String MINIBOSS_PREFIX = "SLAYER MINI-BOSS";

    /** The party-assist credit line, verified verbatim, formatting stripped. */
    public static final String ASSIST_CREDIT =
            "You received kill credit for assisting on a slayer miniboss!";

    private final SlayerQuestState questState;

    /** Uses a private quest state, which nothing reports to: the detector stays armed everywhere. */
    public SlayerMinibossDetector() {
        this(new SlayerQuestState());
    }

    /**
     * @param questState the shared sidebar-backed state; see {@link SlayerQuestState} for why an
     *                   unreported state deliberately leaves this detector armed
     */
    public SlayerMinibossDetector(SlayerQuestState questState) {
        super(LootSource.SLAYER_MINIBOSS);
        this.questState = questState;
    }

    @Override
    public boolean gateOpen(GameContext ctx) {
        return super.gateOpen(ctx) && questState.mayBeActive();
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        String cleaned = TextClean.clean(rawLine);
        if (!cleaned.startsWith(MINIBOSS_PREFIX) && !ASSIST_CREDIT.equals(cleaned)) {
            return Optional.empty();
        }
        return Optional.of(event(questState.captionOr(info().displayName()), nowMillis));
    }
}
