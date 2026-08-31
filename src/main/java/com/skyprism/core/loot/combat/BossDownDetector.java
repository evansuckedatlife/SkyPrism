package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;

import java.util.Map;
import java.util.Optional;

/**
 * A detector for one source in the {@link BossDownBanner} family: it matches the universal
 * defeat banner, then accepts the name only if it appears in this source's own closed table.
 *
 * <p>The table is not an optimisation, it is the security boundary. See {@link BossDownBanner} for
 * why an arbitrary captured name must never reach {@link LootEvent#subject()}, and note that the
 * table is also what keeps two sources in this family from claiming each other's lines: the End's
 * detector does not know the word {@code ASHFANG}, the Crimson Isle's does not know
 * {@code PROTECTOR DRAGON}, and the island gates would keep them apart even if they did.
 *
 * <p>Subclasses exist only where a caption needs more than the table can say -- Kuudra's tier, which
 * lives on the sidebar rather than in the line.
 */
public class BossDownDetector extends ContextAwareDetector {

    /** Upper-case banner name to the caption the widget should show. */
    private final Map<String, String> names;

    /**
     * @param source the source this speaks for
     * @param names  upper-case banner name to caption; the closed set this detector will accept
     */
    protected BossDownDetector(LootSource source, Map<String, String> names) {
        super(source);
        this.names = Map.copyOf(names);
    }

    /** The names this detector accepts, for tests and for {@code /skyprism} diagnostics. */
    public final Map<String, String> acceptedNames() {
        return names;
    }

    @Override
    public final Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        String subject = BossDownBanner.subjectOf(rawLine);
        if (subject == null) {
            return Optional.empty();
        }
        String caption = names.get(subject);
        if (caption == null) {
            // A DOWN! banner for a boss that is not ours, or a name nobody verified. Declining is
            // the whole point: see BossDownBanner's notes on the closed table.
            return Optional.empty();
        }
        return Optional.of(event(caption(caption), nowMillis));
    }

    /**
     * The caption for an accepted name. Overridden where the sidebar carries a tier or a floor the
     * banner itself omits.
     */
    protected String caption(String tableCaption) {
        return tableCaption;
    }
}
