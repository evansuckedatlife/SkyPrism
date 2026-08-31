package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared machinery for the three sources that open the same GUI and share the same broadcast: a
 * Catacombs reward chest, a Kuudra Free or Paid chest, and a backlog of chests cleared at Croesus.
 *
 * <h2>Two triggers, one source, and why both exist</h2>
 * <p>A reward chest announces itself twice. The inventory opens, with a title naming its tier; and,
 * when the contents were worth it, Hypixel broadcasts {@code RARE REWARD!} to the party naming the
 * player, the item and the tier. Both are wired, because the two serve different players. The
 * broadcast is the shipped default -- it is Hypixel's own rarity flag, which is what makes {@link
 * com.skyprism.core.loot.RollPolicy#ON_RARE_BANNER ON_RARE_BANNER} implementable here at all -- and
 * the title is what a player who sets {@code ALWAYS} is asking for.
 *
 * <p>Wiring both means one chest can produce two events, so this class suppresses the second: an
 * event for the same tier within {@link #DEDUPE_MILLIS} of the previous one is dropped. Keying the
 * suppression on the tier rather than on time alone matters at Croesus, where a player clears
 * fifteen chests in ninety seconds and a blanket time window would silently eat half of them.
 *
 * <h2>Ownership</h2>
 * <p>The broadcast fires for every party member. Without the name check, four other players hold a
 * button that spins the local machine -- so the check is mandatory and it fails closed. See {@link
 * RareRewardBroadcast#isOwnedBy(String)}, which argues that direction at length.
 *
 * <h2>No island gate, on purpose</h2>
 * <p>These three sources sit behind {@link com.skyprism.core.loot.SourceGate#screen(String)}, which
 * is "in SkyBlock" plus a title match, and they do <em>not</em> narrow it to the Catacombs or to
 * Kuudra. They do not need to: the chest tier in the line already tells the three apart exactly, and
 * a tier is a fact about the chest rather than a guess about where the player is standing. An island
 * gate here would add nothing and would introduce the one failure this design is most afraid of --
 * a gate that never opens because the island string the client reports is spelled differently from
 * the one the gate was written against, producing a feature that silently never fires.
 */
abstract class ChestBroadcastDetector extends RegistryDetector {

    /**
     * How long after an event the same tier is considered a duplicate.
     *
     * <p>Three seconds covers the gap between a chest GUI opening and its own broadcast landing,
     * and is comfortably shorter than the six-second-per-chest pace of a Croesus backlog burst.
     */
    static final long DEDUPE_MILLIS = 3_000L;

    private final Supplier<String> localPlayerName;

    private boolean hasFired;
    private String lastTier = "";
    private long lastEventAt;

    ChestBroadcastDetector(LootSource source, Supplier<String> localPlayerName) {
        super(source);
        this.localPlayerName = Objects.requireNonNull(localPlayerName, "localPlayerName");
    }

    /** The chest tiers this source owns, as they appear in the broadcast and in the GUI title. */
    abstract Set<String> tiers();

    /** The exact inventory titles this source owns. */
    abstract Set<String> titles();

    /**
     * Whether this detector may claim an event at all right now.
     *
     * <p>Always true except at Croesus, which claims only while its run list has recently been open.
     * The bus dispatches in registration order and stops at the first event, so a Croesus detector
     * registered ahead of the Catacombs one takes the chests it recognises and leaves the rest --
     * no coupling between the two detectors, just an ordering the factory documents.
     */
    boolean claimable(long nowMillis) {
        return true;
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf(ContainerPatterns.RARE_REWARD_MARKER) < 0) {
            return Optional.empty();
        }
        if (!claimable(nowMillis)) {
            return Optional.empty();
        }
        Optional<RareRewardBroadcast> parsed = RareRewardBroadcast.parse(rawLine);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        RareRewardBroadcast broadcast = parsed.get();
        if (!tiers().contains(broadcast.tier())) {
            return Optional.empty();
        }
        if (!broadcast.isOwnedBy(localPlayerName.get())) {
            return Optional.empty();
        }
        return emit(broadcast.tier(), broadcast.chestCaption(), nowMillis);
    }

    @Override
    public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        if (title == null || title.isEmpty() || !claimable(nowMillis)) {
            return Optional.empty();
        }
        String clean = TextClean.clean(title);
        if (clean == null || !titles().contains(clean)) {
            return Optional.empty();
        }
        return emit(tierOfTitle(clean), clean, nowMillis);
    }

    /**
     * Emits an event unless it duplicates the previous one for the same tier.
     *
     * @param tier    the dedupe key
     * @param caption the subject the widget shows
     */
    private Optional<LootEvent> emit(String tier, String caption, long nowMillis) {
        // A boolean rather than a sentinel timestamp: Long.MIN_VALUE as "never" makes the very
        // first subtraction overflow, and an overflowed difference happens to look like a duplicate.
        if (hasFired && tier.equals(lastTier)
                && nowMillis >= lastEventAt && nowMillis - lastEventAt < DEDUPE_MILLIS) {
            return Optional.empty();
        }
        hasFired = true;
        lastTier = tier;
        lastEventAt = nowMillis;
        return Optional.of(event(caption, nowMillis));
    }

    /**
     * The tier word inside an inventory title.
     *
     * <p>Hypixel writes a chest title as either the bare tier or the tier plus the word "Chest",
     * and for Kuudra it sometimes writes "Chest" twice. The first token is the tier in all of them.
     */
    private static String tierOfTitle(String title) {
        int space = title.indexOf(' ');
        return space < 0 ? title : title.substring(0, space);
    }
}
