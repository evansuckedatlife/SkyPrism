package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * A Superpairs, Chronomatron or Ultrasequencer reward claimed at the Experimentation Table.
 *
 * <h2>The chat line is the trigger; the GUI title only improves the caption</h2>
 * <p>This is the deliberate inversion of how the chest sources work, and the reason is the failure
 * mode rather than taste. SkyHanni arms its own experiment handling on the inventory being open and
 * disarms it three seconds after it closes, which is the cheapest gate in the game -- but a detector
 * that <em>requires</em> a screen title is a detector that does nothing at all if nothing ever feeds
 * it one. The claim line is highly anchored on its own (it demands the exact
 * <code>&#167;eYou claimed the &#167;r&#167;X&lt;tier&gt; &#167;r&#167;erewards!</code> shape, which
 * a player cannot type because they cannot put a section sign into chat), so it is safe to accept
 * unconditionally.
 *
 * <p>The title is therefore used for one thing only: remembering which of the three minigames the
 * player was sitting at, so the caption reads "Ultrasequencer (Metaphysical)" instead of a bare
 * tier. If the title never arrives, the caption is the tier alone and everything still works.
 *
 * <h2>No island gate</h2>
 * <p>The table can sit on a private island or be used from anywhere the player can reach one, so the
 * registry gate is "in SkyBlock" and the anchored claim line does the filtering. Gating on the
 * private island, as SkyHanni's ultra-rare alert does, would miss cases.
 *
 * <h2>Shipped policy, and why not ON_JACKPOT_ITEM_ONLY</h2>
 * <p>{@link com.skyprism.core.loot.RollPolicy#ALWAYS}. One claim per experiment, an experiment runs
 * one to three minutes, so twenty to forty an hour at the absolute ceiling and only while the player
 * is deliberately grinding the table. Every claim is a genuine roll on the enchanted-book table.
 *
 * <p>{@code ON_JACKPOT_ITEM_ONLY} was rejected on evidence: SkyHanni reads the ultra-rare books out
 * of GUI item lore rather than from any name list, so there is no verbatim list to copy and a
 * hard-coded one here would be invented. An invented list is worse than no list -- it looks like a
 * working filter and quietly never matches.
 *
 * <p>One refinement is available to whoever can confirm it: the claim line's colour code appears to
 * encode the experiment tier (the same game name has been seen with {@code §d} and with {@code §c}),
 * which would give a free "only celebrate Metaphysical" switch. The tier <em>word</em> is captured
 * here regardless, so that switch can be built on the text without depending on the colour mapping.
 */
public final class ExperimentsRewardsDetector extends RegistryDetector {

    /** How long after the last Experimentation screen its name is still used in the caption. */
    static final long GAME_MEMORY_MILLIS = 30_000L;

    private static final String MARKER = "rewards!";

    private String game = "";
    private long gameSeenAt;
    private boolean gameKnown;

    public ExperimentsRewardsDetector() {
        super(LootSource.EXPERIMENTS_REWARDS);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf(MARKER) < 0) {
            return Optional.empty();
        }
        Matcher matcher = ContainerPatterns.EXPERIMENT_CLAIM.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String tier = matcher.group("tier");
        return Optional.of(event(caption(tier, nowMillis), nowMillis));
    }

    /**
     * Remembers which minigame the player is at. Never an event on its own -- opening a menu is not
     * a payout, and the table is opened far more often than it is claimed from.
     */
    @Override
    public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        if (title == null || title.isEmpty()) {
            return Optional.empty();
        }
        String clean = TextClean.clean(title);
        if (clean == null || clean.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = ContainerPatterns.EXPERIMENT_TITLE.matcher(clean);
        if (matcher.matches() && matcher.group("game") != null) {
            game = matcher.group("game");
            gameSeenAt = nowMillis;
            gameKnown = true;
        }
        return Optional.empty();
    }

    private String caption(String tier, long nowMillis) {
        boolean fresh = gameKnown && nowMillis >= gameSeenAt
                && nowMillis - gameSeenAt < GAME_MEMORY_MILLIS;
        if (!fresh) {
            return tier;
        }
        return game + " (" + tier + ")";
    }
}
