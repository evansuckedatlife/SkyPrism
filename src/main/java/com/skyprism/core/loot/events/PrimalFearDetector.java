package com.skyprism.core.loot.events;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Primal Fear summoned during the Great Spook.
 *
 * <p>Structurally identical to {@link ReindrakeDetector} and for the same reason: the summon line is
 * verified, the defeat line is not, and neither reference mod carries one. The summon therefore arms
 * a bounded single-shot window and the next universal rare-drop banner inside it is attributed to
 * the fear; the summon itself never rolls, because it fires for other players' fears too. See
 * {@link SummonWindow} for the full argument -- it is written once, there, rather than twice.
 *
 * <h2>Default policy: ON_RARE_BANNER</h2>
 * <p>The trigger that reaches the roll is a rare banner, so the policy is satisfiable. The Great
 * Spook is a fortnight a year and Primal Fears are on a server cooldown, so nothing here needs
 * further throttling.
 *
 * <h2>Unverified, and marked as such</h2>
 * <p>The <b>defeat</b> line for a Primal Fear is unverified -- SkyHanni tracks only the summon and a
 * cooldown timer. If one is ever captured, this detector should switch to firing on it directly and
 * the window can go away. Until then the window is the honest approximation, not a stand-in for a
 * pattern somebody could have written from memory.
 */
public final class PrimalFearDetector extends RegistryDetector {

    /**
     * How long after a summon a rare drop is still credited to the fear.
     *
     * <p>Ninety seconds. Shorter than the Reindrake's window because a Primal Fear is a solo-scale
     * fight rather than a group boss, and a tighter window is a smaller blast radius for the
     * misattribution this shape inherently risks.
     */
    static final long WINDOW_MILLIS = 90_000L;

    /**
     * The summon line.
     *
     * <p>Verbatim from SkyHanni's {@code TheGreatSpook}, with its own captured test line:
     * {@code \u00A75\u00A7lFEAR. \u00A7r\u00A7eA \u00A7r\u00A7dPrimal Fear \u00A7r\u00A7ehas been summoned!}
     */
    private static final Pattern SUMMON = Pattern.compile(
            "\u00A75\u00A7lFEAR\\. \u00A7r\u00A7eA \u00A7r\u00A7dPrimal Fear \u00A7r\u00A7ehas been summoned!");

    private final SummonWindow window = new SummonWindow();

    public PrimalFearDetector() {
        super(LootSource.PRIMAL_FEAR);
    }

    /** Closes the window on any context change; see the note on the Reindrake's own override. */
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
        if (SUMMON.matcher(rawLine).matches()) {
            window.arm(nowMillis, WINDOW_MILLIS);
            return Optional.empty();
        }
        if (!window.armed(nowMillis)) {
            return Optional.empty();
        }
        Optional<RareDropBanner.Banner> banner = RareDropBanner.match(rawLine);
        if (banner.isEmpty() || banner.get().pet()) {
            return Optional.empty();
        }
        if (!window.claim(nowMillis)) {
            return Optional.empty();
        }
        return Optional.of(event("Primal Fear", nowMillis));
    }
}
