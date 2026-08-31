package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vermin vacuumed in the Rift's West Village.
 *
 * <h2>Default policy: NEVER, because this is not chance based at all</h2>
 * <p>It is enumerated for one reason: it is the <em>only</em> gathering-shaped chat announcement
 * anywhere in The Rift, and leaving it out would make the Rift look unexamined. Vacuuming a vermin
 * is a deterministic pickup, one line per vermin, continuously while vacuuming.
 *
 * <p>The related negative result is worth recording next to it, because it is the kind of gap that
 * otherwise gets filled by guesswork later: <b>Rift fishing does not exist</b> as a chat-detectable
 * loot source. An exhaustive pass over both reference mods' Rift features found no fishing line, no
 * Rift sea-creature variant and no rare-drop banner, and the Rift's gatherables -- Wilted Berberis,
 * Agaricus Cap, Volt, Timite, Odonata, Larva, Enigma Souls, Motes -- are deterministic pickups or
 * one-time collectibles. No constant was created for it and none should be.
 */
public final class RiftVerminDetector extends RegistryDetector {

    /**
     * The three vacuum lines, as a closed alternation.
     *
     * <p>Verbatim from SkyHanni's {@code VerminTracker}: {@code \u00A7eYou vacuumed a \u00A7r\u00A7aSilverfish\u00A7r\u00A7e!}
     * and the Spider and Fly variants. The vermin name is a closed set rather than a wildcard,
     * deliberately -- a wildcard name group in an anchored pattern is still a name somebody else's
     * message could supply, and there is no reason to accept one when the set is three long.
     */
    private static final Pattern VACUUMED = Pattern.compile(
            "\u00A7eYou vacuumed a \u00A7r\u00A7a(?<vermin>Silverfish|Spider|Fly)\u00A7r\u00A7e!");

    public RiftVerminDetector() {
        super(LootSource.RIFT_VERMIN_VACUUM);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = VACUUMED.matcher(rawLine);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(event(m.group("vermin"), nowMillis));
    }
}
