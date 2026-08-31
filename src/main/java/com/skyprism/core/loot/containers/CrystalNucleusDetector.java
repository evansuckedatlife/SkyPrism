package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * A full Crystal Nucleus run: all five crystals placed, the vault opened.
 *
 * <h2>One roll per run, and which line it fires on</h2>
 * <p>The run announces itself twice over: five {@code CRYSTAL FOUND (n/5)} progress lines while the
 * player collects, and a completion block ending "Pick it up near the Nucleus Vault!". Only the
 * completion fires. The five progress lines are read -- but only to remember how far the run got, so
 * the caption can say {@code "Crystal Nucleus (5/5)"} -- and produce no event of their own.
 *
 * <p>That distinction is the point. A full run is thirty to sixty minutes and happens at most twice
 * an hour, which makes it about the most deserving event in the game; firing five extra times on the
 * way there would devalue the finish it exists to celebrate. This is the same reasoning that keeps
 * the draconic sacrifice source on the BONUS LOOT line rather than the SACRIFICE line: pick the line
 * that <em>is</em> the chance, not the lines that surround it.
 *
 * <h2>Shipped policy</h2>
 * <p>{@link com.skyprism.core.loot.RollPolicy#ALWAYS}, without hesitation.
 *
 * <h2>What the reels land on</h2>
 * <p>The vault's contents are not itemised in chat -- the vault is a physical chest, so its contents
 * reach the client as a container menu. The registry's jackpot list is therefore the five crystal
 * names, which are what the run collected and what the reels should show. Nothing here pretends a
 * drop line exists.
 *
 * <p>The count resets after a completion so the next run starts clean, and the detector holds
 * nothing else: two fields, no allocation on a line it rejects.
 */
public final class CrystalNucleusDetector extends RegistryDetector {

    private static final String COMPLETE_MARKER = "Nucleus Vault";
    private static final String PROGRESS_MARKER = "CRYSTAL FOUND";

    private int crystalsFound;

    public CrystalNucleusDetector() {
        super(LootSource.CRYSTAL_NUCLEUS_RUN);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        if (rawLine.indexOf(PROGRESS_MARKER) >= 0) {
            Matcher progress = ContainerPatterns.CRYSTAL_FOUND.matcher(rawLine);
            if (progress.matches()) {
                // Progress, not payout: remembered for the caption, never rolled on.
                crystalsFound = progress.group("count").charAt(0) - '0';
            }
            return Optional.empty();
        }
        if (rawLine.indexOf(COMPLETE_MARKER) < 0
                || !ContainerPatterns.NUCLEUS_RUN_COMPLETE.matcher(rawLine).matches()) {
            return Optional.empty();
        }
        String caption = crystalsFound > 0
                ? "Crystal Nucleus (" + crystalsFound + "/5)"
                : "Crystal Nucleus Run";
        crystalsFound = 0;
        return Optional.of(event(caption, nowMillis));
    }

    /** How many crystals the run in progress has placed, for diagnostics and tests. */
    public int crystalsFound() {
        return crystalsFound;
    }
}
