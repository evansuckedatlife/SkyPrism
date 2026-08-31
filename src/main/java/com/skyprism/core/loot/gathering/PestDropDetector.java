package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rare or pet drop from a Garden pest.
 *
 * <p>ALWAYS, and one of the few gathering sources where that needs no argument: the line only ever
 * appears for RARE and PET drops, so Hypixel has already done the filtering, and a pest-spraying
 * session yields a handful an hour. It also covers the entire Vinyl system, which is the part of
 * pest hunting people actually chase.
 *
 * <h2>What distinguishes this from every other RARE DROP in the game</h2>
 * <p>The trailing bracket. A pest drop ends with the farming-fortune bonus and the Overbloom glyph
 * -- "(+134[glyph])" -- or, for one drop, the literal "(Cocoaleech)". Every other rare drop in the
 * game ends with the magic-find bracket instead, "(+168% [glyph] Magic Find)", which is a different
 * shape entirely. That is what stops this detector claiming an ordinary mob's drop while the player
 * happens to be standing in the Garden.
 *
 * <p>Transcribed from SkyHanni PestProfitTracker.kt (garden.pests.tracker.raredrop), which carries
 * seven captured lines and, usefully, one captured line it must <em>not</em> match: the RARE CROP
 * banner, which is {@link RareCropDetector}'s. The two are kept apart by the banner word alone --
 * DROP against CROP -- so both are pinned in this package's cross-source test.
 *
 * <h2>The one deliberate loosening</h2>
 * <p>The reference pattern requires the Overbloom codepoint U+E02B literally. Hypixel has already
 * moved one of these private-use glyphs once, and a resource pack can replace it, so the bonus tail
 * here is "+digits" followed by at most two characters that are neither a formatting code nor a
 * closing bracket. That still rejects the magic-find bracket -- "+168% [glyph] Magic Find" has far
 * more than two characters before its bracket closes -- while surviving the glyph moving, being
 * dropped, or being rendered by a pack that substitutes something else.
 *
 * <p>Three captured shapes prove the rest of the looseness is needed rather than defensive: the
 * count arrives as a <em>trailing</em> run ("Mutant Nether Wart x9"), not the leading "3x" the
 * Diana parser knows; the reset code after the banner is sometimes absent; and the whole line is
 * sometimes prefixed by a reset.
 */
public final class PestDropDetector extends RegistryDetector {

    /**
     * Captured, with section signs shown as bracketed letters:
     * "(6)(l)RARE DROP! (9)Mutant Nether Wart (8)x9 (e)[(e)+134(glyph)]",
     * "(6)(l)PET DROP! (r)(6)Slug (e)[(e)+78(glyph)]",
     * "(6)(l)RARE DROP! (r)(a)Not Just a Pest Vinyl (r)(6)[Cocoaleech]",
     * where the square brackets are really round ones.
     */
    private static final Pattern PEST_DROP = Pattern.compile(
            "(?:\u00A7r)?\u00A76\u00A7l(?<banner>RARE|PET) DROP! (?:\u00A7r)?"
                    + "(?<item>.+?)(?: \u00A78x(?<amount>[\\d,]+))? (?:\u00A7.)*"
                    + "\\((?:\u00A7.)?(?:\\+[\\d.,]+[^\u00A7)\\n\\r]{0,2}|Cocoaleech)\\)");

    /** A caption drawn into a fixed-width widget cannot be an unbounded server string. */
    private static final int MAX_ITEM_LENGTH = 48;

    public PestDropDetector() {
        super(LootSource.GARDEN_PEST_DROP);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("DROP!") < 0) {
            return Optional.empty();
        }
        Matcher matcher = PEST_DROP.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String item = TextClean.clean(matcher.group("item"));
        if (item.isEmpty() || item.length() > MAX_ITEM_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(event(item, nowMillis));
    }
}
