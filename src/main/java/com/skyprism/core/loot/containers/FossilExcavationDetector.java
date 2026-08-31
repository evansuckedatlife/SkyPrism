package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;

/**
 * A Fossil Excavator excavation resolving, at the Fossil Research Center in the Glacite Tunnels.
 *
 * <h2>Both outcomes are triggers</h2>
 * <p>An excavation either prints the {@code EXCAVATION COMPLETE} reward block or the flat line "You
 * didn't find anything. Maybe next time!". <b>Both</b> fire an event, and that is deliberate. The
 * empty result is a resolved gamble the player has already paid a Suspicious Scrap for; letting the
 * reels spin and settle on "No Drop" is the honest outcome and it is most of the texture. Firing
 * only on success would quietly hide every near miss, which is the half of a slot machine that makes
 * the other half mean anything.
 *
 * <p>The two outcomes are mutually exclusive on the server, so a suppression window is not strictly
 * needed -- but it is cheap and it costs nothing to be certain a single excavation cannot produce
 * two rolls if Hypixel ever prints both.
 *
 * <h2>Shipped policy</h2>
 * <p>{@link com.skyprism.core.loot.RollPolicy#ALWAYS}. Each excavation costs a scrap and about a
 * minute of the tile-matching minigame, so twenty an hour is the ceiling and only while the player
 * is deliberately grinding it. It is also thematically exact: the excavation <em>is</em> a gamble
 * already paid for, and the machine simply dramatises a payout the player is sitting there waiting
 * on.
 *
 * <h2>No jackpot list, on purpose</h2>
 * <p>The registry entry has an empty jackpot set because no quoted Fossil Excavator loot table was
 * found, and inventing item names is the failure mode this whole project is built to avoid. That
 * costs nothing here: the policy is {@code ALWAYS}, which never consults the list. Whoever gets a
 * live session or a wiki pass should fill it in; until then the reels lock onto whatever the block
 * actually contained.
 *
 * <h2>The island gate is Dwarven Mines, not Glacite Tunnels</h2>
 * <p>The Fossil Research Center sits in the Glacite Tunnels, which report to the client as the
 * Dwarven Mines. SkyHanni gates its own excavator handler on exactly that island for exactly that
 * reason. Gating on "Glacite Tunnels" would produce a detector that never opens.
 */
public final class FossilExcavationDetector extends RegistryDetector {

    /** How long after one outcome the other is ignored, so an excavation cannot roll twice. */
    static final long DEDUPE_MILLIS = 3_000L;

    private static final String COMPLETE_MARKER = "EXCAVATION COMPLETE";
    private static final String EMPTY_MARKER = "You didn't find anything";

    private boolean hasFired;
    private long lastEventAt;

    public FossilExcavationDetector() {
        super(LootSource.FOSSIL_EXCAVATION);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        boolean complete = rawLine.indexOf(COMPLETE_MARKER) >= 0
                && ContainerPatterns.EXCAVATION_COMPLETE.matcher(rawLine).matches();
        boolean empty = !complete
                && rawLine.indexOf(EMPTY_MARKER) >= 0
                && ContainerPatterns.EXCAVATION_EMPTY.matcher(rawLine).matches();
        if (!complete && !empty) {
            return Optional.empty();
        }
        if (hasFired && nowMillis >= lastEventAt && nowMillis - lastEventAt < DEDUPE_MILLIS) {
            return Optional.empty();
        }
        hasFired = true;
        lastEventAt = nowMillis;
        return Optional.of(event(nowMillis));
    }
}
