package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Trick or Treat or Party chest appearing during the Spooky Festival.
 *
 * <h2>Default policy: NEVER, and it should stay that way</h2>
 * <p>This is the one source in this package that is shipped disabled on purpose rather than for
 * pacing. The only line anyone has captured is an island-wide <em>appearance</em> broadcast. It says
 * a chest spawned somewhere. It does not say the local player opened it, it does not say what was
 * inside, and somebody else may well take it. Rolling on that would be a reel that lies -- the
 * machine would spin and settle on nothing the player ever received.
 *
 * <p>The detector exists anyway, and that is deliberate too. The constant, the pattern and the gate
 * are all real and correct; what is missing is a loot signal nobody has verified. Having the
 * detector present means the day somebody captures that line, the work is one pattern rather than a
 * new source, and in the meantime the config screen can show the player exactly what is and is not
 * supported instead of pretending the event does not exist.
 *
 * <p><b>Unverified:</b> the loot announcement for these chests. Almost certainly the four-space
 * reward block every other container uses, but that was not confirmed and is therefore not written.
 */
public final class SpookyChestDetector extends RegistryDetector {

    /**
     * The appearance broadcast.
     *
     * <p>Verbatim from SkyHanni's {@code SpookyChestAlert}, with both of its captured test lines:
     * {@code \u00A76\u00A7lSPOOKY! \u00A7r\u00A77A \u00A7r\u00A76Trick or Treat Chest \u00A7r\u00A77has appeared!} and
     * {@code \u00A7c\u00A7lPARTY! \u00A7r\u00A77A \u00A7r\u00A7cParty Chest \u00A7r\u00A77has appeared!}
     */
    private static final Pattern APPEARED = Pattern.compile(
            "\u00A7[6c]\u00A7l(?<type>SPOOKY|PARTY)! \u00A7r\u00A77A \u00A7r\u00A7[6c]"
                    + "(?<chest>Trick or Treat Chest|Party Chest) \u00A7r\u00A77has appeared!");

    public SpookyChestDetector() {
        super(LootSource.SPOOKY_CHEST);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher m = APPEARED.matcher(rawLine);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(event(m.group("chest"), nowMillis));
    }
}
