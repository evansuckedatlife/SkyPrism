package com.skyprism.core.loot.containers;

import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * One parsed {@code RARE REWARD!} line: who got it, what it was, and which chest tier it came out
 * of.
 *
 * <p>Three separate sources ride on this one sentence -- a Catacombs chest opened at the end of a
 * run, a backlog of chests cleared at Croesus, and a Kuudra Free or Paid chest -- so it is parsed
 * once, here, and each detector asks whether the tier is one of its own. Parsing it three times in
 * three detectors would be the same regex three times on a line that is already rare, which is
 * affordable but pointless; having three near-identical copies of a pattern that must agree about
 * chest tiers is not.
 *
 * <h2>The ownership check is not optional</h2>
 * <p>Hypixel broadcasts this line to the whole party. A detector that fires on it without checking
 * the name has handed four other players a button that spins the local HUD, which is both wrong and
 * confusing in exactly the way that is hard to report: the machine goes off, the player looks at
 * their inventory, and nothing arrived. {@link #isOwnedBy(String)} is therefore a required step
 * rather than a refinement, and it fails <em>closed</em> when the local name is unknown -- see the
 * note on that method for why that is the right direction here even though "closed" usually means
 * "silently never fires".
 */
public record RareRewardBroadcast(String player, String item, String tier) {

    /**
     * Parses a raw {@code RARE REWARD!} chat line.
     *
     * <p>Rejects in one {@code indexOf} before allocating anything. That matters because the bus's
     * pre-filter is the union of every open detector's markers, so this method is offered plenty of
     * lines belonging to other sources and must be cheap on all of them.
     *
     * @param rawLine the line as the server sent it, formatting codes and all
     * @return the parsed broadcast, or empty when this line is not one
     */
    public static Optional<RareRewardBroadcast> parse(String rawLine) {
        if (rawLine == null || rawLine.indexOf(ContainerPatterns.RARE_REWARD_MARKER) < 0) {
            return Optional.empty();
        }
        String clean = TextClean.clean(rawLine);
        if (clean == null || clean.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = ContainerPatterns.RARE_REWARD.matcher(clean);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new RareRewardBroadcast(
                ContainerText.playerName(matcher.group("player")),
                matcher.group("item").strip(),
                matcher.group("tier").strip()));
    }

    /**
     * Whether this broadcast is about {@code localPlayer}.
     *
     * <p><b>Fails closed on an unknown local name.</b> A blank or null local name means the client
     * has not told us who we are, and in that state there is no way to tell our own Bedrock chest
     * from a party member's. Firing anyway is the failure the player notices and cannot explain;
     * not firing is a roll missed during the seconds after login. This is the one place in the
     * feature where "do nothing when unsure" beats "do something": everywhere else a shut gate
     * risks a feature that silently never works, but here the unknown state is transient by
     * construction and the wrong behaviour is user-visible and wrong forever.
     *
     * @param localPlayer the client's own username; null or blank means unknown
     */
    public boolean isOwnedBy(String localPlayer) {
        if (localPlayer == null) {
            return false;
        }
        String mine = ContainerText.playerName(localPlayer);
        return !mine.isEmpty() && mine.equalsIgnoreCase(player);
    }

    /** The caption a widget should show, e.g. {@code "Obsidian Chest"}. */
    public String chestCaption() {
        return tier + " Chest";
    }
}
