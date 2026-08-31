package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Pristine perk proc while breaking gemstone blocks in the Crystal Hollows.
 *
 * <p><b>NEVER, and this one is not a close call.</b> With the perk maxed the line fires several
 * times a minute -- it is the mining twin of the ordinary sea creature: valuable in aggregate,
 * worthless as an individual moment. The constant exists so the config screen can list it and a
 * player who wants it can switch it on knowing what they are asking for.
 *
 * <p>Worth recording why ON_JACKPOT_ITEM_ONLY is <em>not</em> the softer fallback it looks like:
 * the Pristine line only ever announces the <b>Flawed</b> tier. There is no Fine, Flawless or
 * Perfect gemstone to put on a jackpot list, so that policy here would be a detector that can never
 * spin -- the silent-never-fires failure, arrived at by trying to be reasonable.
 *
 * <p>Pattern verbatim from SkyHanni GemstoneMoneyPerHour.kt (mining.pristine), with two captured
 * lines. The bare dot before "Flawed" is deliberate and load-bearing: it stands for the gemstone
 * glyph, a different symbol per gem, and matching those literally is the standing liability this
 * codebase already refuses elsewhere.
 */
public final class PristineGemstoneDetector extends RegistryDetector {

    /**
     * Captured, section signs shown as bracketed letters:
     * "(d)(l)PRISTINE! (r)(f)You found (r)(a)[glyph] Flawed Jade Gemstone (r)(8)x20(r)(f)!"
     */
    private static final Pattern PRISTINE = Pattern.compile(
            "§d§lPRISTINE! §r§fYou found §r§a. Flawed "
                    + "(?<gemstone>\\w+) Gemstone §r§8x(?<amount>\\d+)§r§f!");

    public PristineGemstoneDetector() {
        super(LootSource.MINING_PRISTINE_GEMSTONE);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("PRISTINE!") < 0) {
            return Optional.empty();
        }
        Matcher matcher = PRISTINE.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(event("Flawed " + matcher.group("gemstone") + " Gemstone", nowMillis));
    }
}
