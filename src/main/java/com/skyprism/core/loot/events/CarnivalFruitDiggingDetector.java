package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Carnival's fruit-digging minigame in the Hub.
 *
 * <h2>Default policy: NEVER, shipped opt-in</h2>
 * <p>This is barely a lottery. The board is a solvable minesweeper variant, so the outcome is skill,
 * and it pays Carnival Tokens rather than items, so there is almost nothing for a reel to lock onto.
 * The one genuinely rare reveal is the Dragonfruit, which is why the constant exists at all and why
 * the jackpot list has exactly one entry. A player who wants it can switch the source to
 * {@code ON_JACKPOT_ITEM_ONLY} and get a celebration on the Dragonfruit and nothing else, which is
 * the only defensible armed configuration.
 *
 * <h2>The marker collision worth knowing about</h2>
 * <p>This source's registry marker is {@code "TREASURE!"} -- which is also a substring of
 * {@code "FROZEN TREASURE!"}, the Jerry's Workshop ice-mining line. Both lines therefore reach both
 * detectors' {@code onChat} whenever both gates are open. That is exactly why the pattern below is
 * anchored at the start of the line with {@link Matcher#matches()}: a Frozen Treasure line begins
 * with {@code FROZEN}, so it cannot match, and the cross-source false positive that would otherwise
 * be the likeliest bug in this whole feature is closed by construction rather than by ordering.
 */
public final class CarnivalFruitDiggingDetector extends RegistryDetector {

    /**
     * The dig reveal, colourless.
     *
     * <p>Verbatim from SkyHanni's repo constants: {@code ^TREASURE! There is an? (?<fruit>.*)
     * nearby\.$}. The sibling lines, which are deliberately <em>not</em> triggers, are the bomb
     * warning {@code MINES! There are 3 bombs hidden nearby.} and the empty result
     * {@code TREASURE! There are no fruits nearby!} -- the second of which this pattern excludes on
     * its own, because it ends in an exclamation rather than a full stop and has no article.
     */
    private static final Pattern TREASURE = Pattern.compile(
            "TREASURE! There is an? (?<fruit>.+) nearby\\.");

    public CarnivalFruitDiggingDetector() {
        super(LootSource.CARNIVAL_FRUIT_DIGGING);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("TREASURE!") < 0) {
            return Optional.empty();
        }
        Matcher m = TREASURE.matcher(TextClean.clean(rawLine));
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(event(m.group("fruit"), nowMillis));
    }
}
