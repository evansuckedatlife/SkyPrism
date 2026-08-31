package com.skyprism.core.loot.events;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Reindrake summoned from the depths during the Season of Jerry.
 *
 * <h2>Only the summon is verified, so the summon only arms</h2>
 * <p>Neither reference mod carries a Reindrake kill line, and both fall back to entity detection --
 * decent evidence that Hypixel prints none. What is verified is the lobby-wide summon broadcast
 * {@code WOAH! [VIP] Georeek summoned a Reindrake from the depths!}. Rolling on that would be wrong:
 * it fires for every player in the lobby, most of whom will never touch the fight. Inventing a kill
 * regex would be worse -- a detector that looks like a working feature and never fires.
 *
 * <p>So the summon arms a bounded, single-shot window (see {@link SummonWindow}) and the next
 * universal rare-drop banner inside it is attributed to the Reindrake. That is precisely what the
 * registry's own note prescribes, and it is the only arrangement under which this source's shipped
 * {@code ON_RARE_BANNER} policy can ever be satisfied.
 *
 * <h2>Default policy: ON_RARE_BANNER</h2>
 * <p>Inherited from the shape above rather than chosen independently: the trigger that actually
 * reaches the roll <em>is</em> a rare banner, so the policy is satisfiable, and a Reindrake is
 * seasonal and rare enough that no further throttling is wanted.
 *
 * <h2>Known imprecision, stated plainly</h2>
 * <p>The summon broadcast carries the summoner's name but no way to tell whether the local player
 * fought the thing. A bystander whose next rare drop lands inside the window gets it captioned
 * "Reindrake". The window is two minutes and single-shot, which bounds the damage to one mislabelled
 * caption per summon, and the alternative was a source that never fires at all.
 */
public final class ReindrakeDetector extends RegistryDetector {

    /**
     * How long after a summon a rare drop is still credited to the Reindrake.
     *
     * <p>Two minutes: long enough for a group to actually kill it, short enough that the window is
     * shut for the overwhelming majority of a session.
     */
    static final long WINDOW_MILLIS = 120_000L;

    /**
     * The summon broadcast, colourless.
     *
     * <p>Verbatim from SkyHanni's repo constants: {@code WOAH! .+ summoned (?:a Reindrake|TWO
     * Reindrakes) from the depths!}. Matched against the colour-stripped line because the broadcast
     * carries the summoner's rank prefix, which is a colour run of unpredictable shape.
     */
    private static final Pattern SUMMON = Pattern.compile(
            "WOAH! .+ summoned (?:a Reindrake|TWO Reindrakes) from the depths!");

    private final SummonWindow window = new SummonWindow();

    public ReindrakeDetector() {
        super(LootSource.REINDRAKE);
    }

    /**
     * Closes the window on any context change, so a summon cannot follow the player off the island.
     *
     * <p>Cheap -- one boolean write on a call that already happens only when the island, area or
     * mayor actually changed -- and it removes the one way a two-minute window could otherwise be
     * carried somewhere it means nothing.
     */
    @Override
    public boolean gateOpen(GameContext ctx) {
        window.disarm();
        return super.gateOpen(ctx);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        if (rawLine.indexOf("from the depths!") >= 0
                && SUMMON.matcher(TextClean.clean(rawLine)).matches()) {
            window.arm(nowMillis, WINDOW_MILLIS);
            // Arming is not a payout. Returning empty here is what stops a bystander's HUD from
            // spinning on somebody else's summon.
            return Optional.empty();
        }
        if (!window.armed(nowMillis)) {
            return Optional.empty();
        }
        Optional<RareDropBanner.Banner> banner = RareDropBanner.match(rawLine);
        if (banner.isEmpty() || banner.get().pet()) {
            // Pet drops belong to PET_DROP, which is captioned and coloured for them; letting this
            // source claim one would relabel a genuinely distinct moment.
            return Optional.empty();
        }
        if (!window.claim(nowMillis)) {
            return Optional.empty();
        }
        return Optional.of(event("Reindrake", nowMillis));
    }
}
