package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Ubik's Split or Steal in The Rift: thematically the most perfect trigger in SkyBlock for a slot
 * machine, and the one whose payout line nobody has ever captured.
 *
 * <h2>Default policy: ALWAYS</h2>
 * <p>It is a literal gamble on a multi-hour cooldown. There is no frequency argument to have.
 *
 * <h2>Why the GUI title is the trigger and the chat line is not</h2>
 * <p>The only verified chat line is the <em>refusal</em>: {@code SPLIT! You need to wait 4h 12m
 * before you can play again.} That is the opposite of a payout, and a detector that rolled on it
 * would spin the machine precisely when the player got nothing. So the roll fires on the inventory
 * opening -- which is the moment the gamble is taken -- and the cooldown line is used as a
 * <b>suppressor</b>: seeing it proves the game was not playable, so title openings are ignored for a
 * while afterwards. That turns the one verified line from a false trigger into a correctness
 * improvement.
 *
 * <p>Two rate limits keep an inventory-driven trigger honest. A player who opens and closes the same
 * GUI three times in ten seconds has gambled once, so consecutive openings inside
 * {@link #MIN_GAP_MILLIS} produce one event; and an opening inside {@link #SUPPRESS_MILLIS} of a
 * cooldown refusal produces none at all.
 *
 * <h2>Unverified, and marked as such</h2>
 * <p>There is <b>no verified win or lose line</b> for Split or Steal in either reference mod --
 * only the cooldown. The reward is Motes, not an item, so a caption should say so rather than
 * implying the reels are showing loot. None of that is papered over here: what is implemented is
 * exactly what is verified.
 */
public final class SplitOrStealDetector extends RegistryDetector {

    /** The inventory title, verbatim from SkyHanni's repo constants. */
    private static final String TITLE = "Split or Steal";

    /**
     * The cooldown refusal, colourless.
     *
     * <p>Verbatim: {@code SPLIT! You need to wait (?<duration>.+) before you can play again\.}
     */
    private static final Pattern COOLDOWN = Pattern.compile(
            "SPLIT! You need to wait (?<duration>.+) before you can play again\\.");

    /** How long a cooldown refusal suppresses title-driven events. */
    static final long SUPPRESS_MILLIS = 300_000L;

    /** How close together two openings have to be to count as the same gamble. */
    static final long MIN_GAP_MILLIS = 60_000L;

    private long suppressedUntil = Long.MIN_VALUE;

    /**
     * The earliest instant a title opening may roll again.
     *
     * <p>Stored as a deadline rather than as "when did we last fire", deliberately. The subtraction
     * form -- {@code now - lastEventAt < MIN_GAP_MILLIS} -- overflows on the very first call,
     * because {@code lastEventAt} starts at {@link Long#MIN_VALUE} and the difference wraps
     * negative, so the first and most important gamble of the session is silently suppressed. That
     * is not hypothetical: it is what this field was before the test for it was written. A deadline
     * has no arithmetic left to overflow.
     */
    private long earliestNextEvent = Long.MIN_VALUE;

    public SplitOrStealDetector() {
        super(LootSource.RIFT_UBIK_SPLIT_OR_STEAL);
    }

    /**
     * Consumes the cooldown refusal and never rolls on it.
     *
     * <p>Returning empty here is the point of the method, not an omission: the line means the player
     * was turned away.
     */
    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("SPLIT!") < 0) {
            return Optional.empty();
        }
        if (COOLDOWN.matcher(TextClean.clean(rawLine)).matches()) {
            suppressedUntil = nowMillis + SUPPRESS_MILLIS;
        }
        return Optional.empty();
    }

    @Override
    public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        if (title == null || !TITLE.equals(TextClean.clean(title))) {
            return Optional.empty();
        }
        if (nowMillis <= suppressedUntil || nowMillis < earliestNextEvent) {
            return Optional.empty();
        }
        earliestNextEvent = nowMillis + MIN_GAP_MILLIS;
        return Optional.of(event(TITLE, nowMillis));
    }
}
