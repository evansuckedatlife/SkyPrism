package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crop Fever catching in the Garden.
 *
 * <p>ALWAYS, on the <b>start</b> line only. The fever is a sixty-second buff that lands occasionally
 * during a farming session, so it is event shaped: a handful an hour, each one a discrete thing the
 * player reacts to.
 *
 * <h2>Why the drops inside the window do not each roll</h2>
 * <p>During those sixty seconds Hypixel prints a stream of tiered drop lines -- "RARE DROP! You
 * dropped 48x Enchanted Melon Slice!" and its UNCOMMON, CRAZY RARE and PRAY TO RNGESUS siblings --
 * many of them in one minute. Rolling on each would turn one event into a strobe and would also
 * double-roll against the shared banner parser, which sees the same word. So the fever start is the
 * roll, the drops inside it are loot for the reels to land on, and {@link #feverDrop(String)}
 * parses them for whoever collects that loot without emitting an event of its own.
 *
 * <p>This window is, incidentally, the only place in the game that uses the banner word PRAY TO
 * RNGESUS DROP, which is why the tier group here is open rather than an alternation of the four
 * known words: a fifth tier would otherwise silently stop parsing.
 *
 * <p>Both patterns are transcribed from SkyHanni CropFeverTracker.kt (garden.cropfever.*), matched
 * against the colour-stripped line as that mod does.
 */
public final class CropFeverDetector extends RegistryDetector {

    /** Captured: "WOAH! You caught a case of the CROP FEVER for 60 seconds!" */
    private static final String START = "WOAH! You caught a case of the CROP FEVER for 60 seconds!";

    /** Captured: "GONE! Your CROP FEVER has been cured!" Verified, and deliberately not a trigger. */
    static final String END = "GONE! Your CROP FEVER has been cured!";

    /**
     * A drop inside the window. Captured: "RARE DROP! You dropped 48x Enchanted Melon Slice!" and
     * "UNCOMMON DROP! You dropped 24x Enchanted Melon Slice!".
     */
    private static final Pattern FEVER_DROP = Pattern.compile(
            "^(?<rarity>[\\w ]+)! You dropped (?<amount>\\d+)x (?<crop>[\\w ]+)!$");

    /** One drop announced during a Crop Fever window. */
    public record FeverDrop(String rarity, int amount, String crop) {
    }

    public CropFeverDetector() {
        super(LootSource.GARDEN_CROP_FEVER);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("CROP FEVER") < 0) {
            return Optional.empty();
        }
        return START.equals(TextClean.clean(rawLine))
                ? Optional.of(event(nowMillis))
                : Optional.empty();
    }

    /**
     * Parses one of the drop lines printed during the window.
     *
     * <p>Not a trigger, by design -- see the class notes. Public because the loot side of the roll
     * wants these, and because leaving a verified pattern unwritten is how it gets reinvented
     * wrongly somewhere else.
     *
     * @param rawLine the chat line, codes intact
     * @return the drop, or empty
     */
    public static Optional<FeverDrop> feverDrop(String rawLine) {
        if (rawLine == null || rawLine.indexOf("You dropped") < 0) {
            return Optional.empty();
        }
        Matcher matcher = FEVER_DROP.matcher(TextClean.clean(rawLine));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int amount;
        try {
            amount = Integer.parseInt(matcher.group("amount"));
        } catch (NumberFormatException e) {
            // The group is bounded only by \d+, so a server line with a twenty-digit count reaches
            // here. Saturating to MAX_VALUE would put a nonsense number on a reel; refusing the
            // line loses one entry of loot, which is the cheaper mistake.
            return Optional.empty();
        }
        return Optional.of(new FeverDrop(matcher.group("rarity"), amount, matcher.group("crop")));
    }
}
