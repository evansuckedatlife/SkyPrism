package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;

/**
 * The Golden Fish surfacing from the lava.
 *
 * <p>The rarest trophy fish in the game and the clearest {@code ALWAYS} in this package: it
 * surfaces after eight to twelve minutes of <em>continuous</em> lava fishing, on a mechanic built
 * to make the player wait, and it despawns if it is not hooked. A handful per session at most.
 *
 * <h2>One line, not four</h2>
 * <p>Hypixel prints a small story around this fish: it spots, it escapes weakened, it is weak, it
 * swims back beneath the lava. All four are verified (SkyHanni GoldenFishTimer.kt) and only the
 * first one fires here. Firing on more than one would spin the machine two or three times for a
 * single fish, which is exactly the kind of double-roll the bus's first-match rule exists to
 * prevent between sources and which no rule prevents inside one.
 *
 * <p>Which of the four to pick is a genuine design choice and worth recording rather than burying.
 * The spawn line is the anticipation -- the reels start moving at the moment the player realises
 * the fish is up and has to decide whether to chase it. The "is weak!" line is the imminence -- the
 * catch is one pull away. The spawn is chosen because it is the moment the player reacts to, and
 * because a fish that despawns uncaught is still an event that happened; the alternative is one
 * literal away in {@link #WEAK} if it ever reads better in play.
 *
 * <h2>Matched on the cleaned line</h2>
 * <p>Unlike the trophy fish banner, this is a plain coloured sentence with no distinctive
 * structure, so it is compared for equality after stripping rather than matched with a regex
 * pinning each colour code. That survives the mod's own legacy-text reconstruction injecting a
 * reset before every run, which a pinned-code pattern would not. Equality against the whole line
 * keeps it spoof-proof: a player typing the sentence arrives with their name and a colon attached.
 */
public final class GoldenFishDetector extends RegistryDetector {

    /**
     * Verbatim from SkyHanni GoldenFishTimer.kt (spawn), formatting stripped. The raw line is
     * section-9 "You spot a " section-r section-6 "Golden Fish " section-r section-9 "surface from
     * beneath the lava!".
     */
    private static final String SPAWN = "You spot a Golden Fish surface from beneath the lava!";

    /** The alternative trigger, kept for the reason given in the class notes. Verified, unused. */
    static final String WEAK = "The Golden Fish is weak!";

    public GoldenFishDetector() {
        super(LootSource.FISHING_GOLDEN_FISH);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("Golden Fish") < 0) {
            return Optional.empty();
        }
        return SPAWN.equals(TextClean.clean(rawLine))
                ? Optional.of(event("Golden Fish", nowMillis))
                : Optional.empty();
    }
}
