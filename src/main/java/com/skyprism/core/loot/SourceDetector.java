package com.skyprism.core.loot;

import java.util.List;
import java.util.Optional;

/**
 * One activity's opinion about whether a line of chat, or a GUI opening, means "a chance-based thing
 * just resolved".
 *
 * <p>A detector is small on purpose. It answers three questions -- which source am I, may I fire
 * right now, and does this line mean anything -- and holds at most the tiny amount of state a
 * multi-line block reader needs. It does not roll, it does not parse drops, it does not draw. Those
 * belong to the roll, to the shared banner parser and to the widget respectively, all of which have
 * been source-agnostic since before this package existed.
 *
 * <h2>What a detector must not cost</h2>
 * <p>{@link #gateOpen(GameContext)} is asked when the context changes and at worst on a multi-second
 * poll -- never per tick and never per line -- so it may compare strings but must not walk the world.
 * {@link #onChat(String, long)} is asked only for lines that survived the bus's pre-filter and only
 * while the gate is open, so it may run a compiled regex, but the compiled {@code Pattern} must be a static
 * final field: compiling per call, or per line, is the one mistake that turns a cheap feature into a
 * stutter. Nothing here may allocate on a line it is going to reject.
 *
 * <h2>The pre-filter contract, which is the important part</h2>
 * <p>The bus will not offer a line to a detector unless the line contains one of that detector's
 * {@link #chatMarkers()}. That is what makes ordinary chat nearly free -- one {@code indexOf} on the
 * 99.9% of lines that are somebody talking. It is also a loaded gun: a detector whose markers do not
 * actually appear in every line it can match has a feature that works in the unit test and silently
 * never fires in game, with nothing logged and nothing failing.
 *
 * <p>So the contract is stated as an invariant and tested as one. <b>Every string {@link
 * #onChat(String, long)} can match must contain at least one {@link #chatMarkers()} entry
 * verbatim.</b> {@link #triggerSamples()} exists to prove it: it returns real captured lines this
 * detector is expected to match, and {@code LootEventBusPreFilterTest} feeds every registered
 * detector's own samples through the real bus and fails if the pre-filter swallowed one. A detector
 * that returns no markers opts out of filtering entirely and is offered every line, which is slower
 * but can never be wrong -- the safe direction, chosen deliberately as the default.
 */
public interface SourceDetector {

    /** Which activity this detector speaks for. Must be stable and unique within a bus. */
    LootSource source();

    /**
     * Whether this detector can fire in the given context.
     *
     * <p>Called on context change, not per line and never per tick. A detector whose gate is shut is
     * not consulted at all and its markers are not even in the bus's filter set, so it costs
     * literally nothing: not registered for the purposes of that line, not polled, not allocating.
     */
    boolean gateOpen(GameContext ctx);

    /**
     * Offers one raw chat line, formatting codes and all.
     *
     * @param rawLine   the line as the server sent it, with section signs intact; never null
     * @param nowMillis the instant to stamp on any event, from the same clock the roll reads
     * @return the event, or empty when this line means nothing to this source
     */
    Optional<LootEvent> onChat(String rawLine, long nowMillis);

    /**
     * Offers a GUI title, for the sources whose trigger is a container opening rather than a line.
     *
     * <p>Routed without consulting {@link #gateOpen(GameContext)}, because for these sources the
     * title <em>is</em> the gate and it is a stricter one than any island test: an inventory opens a
     * handful of times a minute at worst, and Croesus, the Experimentation Table and a reward chest
     * are each identified by an exact title. Reading the title from the open-screen packet rather
     * than from the client screen field is what keeps this free of version conditionals.
     */
    default Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        return Optional.empty();
    }

    /**
     * Literal substrings, at least one of which appears in every line this detector can match.
     *
     * <p>Empty means "do not filter me", which is always correct and never fast. Prefer the most
     * distinctive literal available -- {@code "CORPSE LOOT!"} over {@code "!"} -- and remember that
     * a marker is matched against the <em>raw</em> line, so it must not span a place where Hypixel
     * puts a formatting code.
     */
    default List<String> chatMarkers() {
        return List.of();
    }

    /**
     * Real captured lines this detector is expected to match, used to prove the pre-filter honest.
     *
     * <p>Not a substitute for the detector's own unit tests: these exist so the bus can verify, for
     * every registered detector at once, that its markers cover its own triggers. Returning an empty
     * list is legal for a detector that reads no chat at all.
     */
    default List<String> triggerSamples() {
        return List.of();
    }

    /** Whether this detector reads chat at all. False for purely GUI- or entity-driven sources. */
    default boolean readsChat() {
        return true;
    }
}
