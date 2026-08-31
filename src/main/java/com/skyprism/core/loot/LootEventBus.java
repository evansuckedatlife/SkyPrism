package com.skyprism.core.loot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One chat hook, one cheap pre-filter, and only the detectors whose gate is open.
 *
 * <p>This is the piece that stops "the slot machine works everywhere" from meaning "twenty-five
 * always-on listeners". The naive shape costs frames in every corner of the game, most of them
 * corners the player is not currently standing in. The shape here costs, on a line that is somebody
 * talking in guild chat, one array-length check and a handful of {@code indexOf} calls -- and on an
 * island where nothing is armed, one array-length check and nothing else at all.
 *
 * <h2>How the cost stays where it belongs</h2>
 * <p>Two things are recomputed on {@link #updateContext(GameContext)} and on {@link
 * #register(SourceDetector)}, and never again: the array of detectors whose gate is currently open,
 * and the array of literal markers those detectors declared. Everything the per-line path touches is
 * one of those two arrays. In particular the gates are not consulted per line -- asking twenty
 * detectors "may I fire" on every chat message would be the same mistake in a different place.
 *
 * <p>Per line, in order: if nothing is open, return. If any open detector declined to declare
 * markers, dispatch to all of them (see below). Otherwise scan the marker array with {@code
 * String.indexOf}, and dispatch only if one hits. Nothing on the rejection path allocates: no
 * iterator, no substring, no matcher, no {@code Optional} until a detector actually produces an
 * event.
 *
 * <h2>The pre-filter must not be able to swallow a real trigger</h2>
 * <p>A hardcoded keyword list is the obvious implementation and the wrong one: it drifts the moment
 * somebody adds a detector and forgets the list, and the symptom is a feature that works in the unit
 * test and silently never fires in game. So the filter is <b>derived from the registered detectors
 * themselves</b> -- it is the union of what they declared, and it cannot contain anything they did
 * not ask for or omit anything they did. A detector that declares no markers opts out entirely and
 * is offered every line; that is slower and it is never wrong, which is the correct default for the
 * safe direction.
 *
 * <p>That still leaves a detector free to declare markers that do not match its own triggers, which
 * is why {@code LootEventBusPreFilterTest} takes every registered detector's {@link
 * SourceDetector#triggerSamples()} -- real captured Hypixel lines -- feeds them through this exact
 * method, and fails if any is rejected. The invariant is checked end to end against the real bus,
 * not asserted in a comment.
 *
 * <h2>One markerless detector must not switch the filter off for everybody</h2>
 * <p>The first version of this class treated "some open detector declared no markers" as "nobody can
 * be filtered", and that turned out to cost more than the whole rest of the feature. Exactly one
 * shipped detector is markerless -- the sea-creature reader, whose ninety announcements genuinely
 * share no literal -- and its gate is open on every island, because you can fish anywhere. So in
 * live play the filter was bypassed everywhere, and every line of guild chat was run past all
 * twenty-odd open detectors and their regexes. Measured on the Farming Islands: 2,566 ns per line
 * unfiltered against 184 ns with those two detectors removed, a fourteenfold cost imposed on the
 * whole mod by one source.
 *
 * <p>The fix is to stop making it an all-or-nothing decision. A detector that declared markers
 * promised that every line it can match contains one of them, so on a line the filter rejected there
 * is provably nothing for it to do; a detector that declared none made no such promise and is
 * offered the line anyway. That is one boolean test per open detector, it preserves registration
 * order exactly (the array is parallel to {@link #open}, not a partition of it), and it leaves the
 * safe direction safe -- a markerless detector still sees every line, which is what it opted into.
 *
 * <h2>First match wins</h2>
 * <p>{@link #onChat(String, long)} returns the first event any open detector produces, in
 * registration order. Sources genuinely overlap -- a slayer drop is also a rare mob drop, a dungeon
 * chest broadcast is also a Croesus broadcast -- and one line must not spin the machine twice.
 * Registration order is therefore meaningful: register the specific before the general, exactly the
 * way the shipped banner parser tries the Diana treasure shapes before the generic banner.
 *
 * <p><b>Threading:</b> not thread safe, by design. It lives on the client thread, poked by the chat
 * callback and by whatever polls the context, which are the same thread in a Minecraft client.
 */
public final class LootEventBus {

    /** Sentinel for the marker array meaning "some open detector wants every line". */
    private static final String[] UNFILTERED = null;

    private final List<SourceDetector> registered = new ArrayList<>();

    /** Detectors whose gate was open the last time the context changed. Rebuilt, never scanned. */
    private SourceDetector[] open = new SourceDetector[0];

    /** Union of the open detectors' markers, or {@link #UNFILTERED}. */
    private String[] markers = new String[0];

    /**
     * Per entry of {@link #open}, whether that detector declared markers and may therefore be
     * skipped on a line the filter rejected.
     *
     * <p>This array is what stops one markerless detector from switching the filter off for
     * everybody else. See {@link #onChat(String, long)}.
     */
    private boolean[] filterable = new boolean[0];

    /** How many open detectors declared no markers and so are offered every line. */
    private int unmarkedCount;

    private GameContext context = GameContext.UNKNOWN;

    /**
     * Adds a detector.
     *
     * <p>Registration order is the dispatch order and therefore decides which source claims a line
     * two sources could both match; see the class notes. Registering the same {@link LootSource}
     * twice is rejected, because two detectors for one source would double-roll and the second would
     * be unreachable anyway.
     *
     * @throws IllegalArgumentException if a detector for the same source is already registered
     */
    public void register(SourceDetector detector) {
        Objects.requireNonNull(detector, "detector");
        LootSource source = Objects.requireNonNull(detector.source(), "detector.source()");
        for (SourceDetector existing : registered) {
            if (existing.source() == source) {
                throw new IllegalArgumentException("detector already registered for " + source);
            }
        }
        registered.add(detector);
        recompute();
    }

    /** Removes a detector, e.g. when the player switches its source off. */
    public boolean unregister(LootSource source) {
        boolean removed = registered.removeIf(d -> d.source() == source);
        if (removed) {
            recompute();
        }
        return removed;
    }

    /**
     * Replaces the coarse context, reopening and reshutting gates.
     *
     * <p>Call this when the island, area, mayor or mode actually changes, or on a poll of a few
     * seconds -- never per tick. It is the only method that asks a detector whether its gate is
     * open, and it does the whole recomputation the per-line path then rides on.
     *
     * <p>An unchanged context is a no-op, so a caller that polls indiscriminately still pays only a
     * record comparison.
     */
    public void updateContext(GameContext ctx) {
        GameContext next = ctx == null ? GameContext.UNKNOWN : ctx;
        if (next.equals(context)) {
            return;
        }
        context = next;
        recompute();
    }

    /** The context the gates were last evaluated against. */
    public GameContext context() {
        return context;
    }

    /**
     * Offers one raw chat line to whichever detectors can currently do anything with it.
     *
     * @param rawLine   the line as the server sent it; null is ignored rather than thrown, because
     *                  this is called straight from a chat callback
     * @param nowMillis the instant to stamp on the event, from the roll's own clock
     * @return the first event produced, or empty
     */
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        SourceDetector[] candidates = open;
        if (candidates.length == 0 || rawLine == null || rawLine.isEmpty()) {
            return Optional.empty();
        }
        boolean markerHit = passesPreFilter(rawLine);
        if (!markerHit && unmarkedCount == 0) {
            return Optional.empty();
        }
        boolean[] skippable = filterable;
        for (int i = 0; i < candidates.length; i++) {
            // A detector that declared markers has promised every line it can match contains one
            // of them, so on a line the filter rejected there is nothing for it to do. A detector
            // that declared none made no such promise and is offered the line regardless.
            if (!markerHit && skippable[i]) {
                continue;
            }
            Optional<LootEvent> event = candidates[i].onChat(rawLine, nowMillis);
            if (event.isPresent()) {
                return event;
            }
        }
        return Optional.empty();
    }

    /**
     * Offers a GUI title to every registered detector, gate or no gate.
     *
     * <p>Deliberately not gated: for a container source the title <em>is</em> the gate, and a
     * stricter one than any island test. A screen opens a handful of times a minute at worst, so
     * there is nothing to save by filtering first and something to lose -- Croesus lives in the
     * Dungeon Hub, the Experimentation Table can sit on a private island, and a reward chest opens
     * wherever the player happens to be.
     *
     * @param title     the inventory title, formatting stripped or not
     * @param nowMillis the instant to stamp on the event
     * @return the first event produced, or empty
     */
    public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        if (title == null || title.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < registered.size(); i++) {
            Optional<LootEvent> event = registered.get(i).onScreenTitle(title, nowMillis);
            if (event.isPresent()) {
                return event;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code rawLine} survives the pre-filter, exposed so a test can pin it directly.
     *
     * <p>The one method whose behaviour has to be provably conservative: everything it rejects is
     * lost silently, so the test that matters is not "does it reject junk" but "does it ever reject
     * a line a registered detector would have matched".
     */
    public boolean passesPreFilter(String rawLine) {
        String[] required = markers;
        if (required == UNFILTERED) {
            return true;
        }
        if (rawLine == null) {
            return false;
        }
        for (int i = 0; i < required.length; i++) {
            if (rawLine.indexOf(required[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** How many detectors are currently past their gate. Diagnostics and tests. */
    public int openDetectorCount() {
        return open.length;
    }

    /** How many detectors are registered at all, open or not. */
    public int registeredCount() {
        return registered.size();
    }

    /** The detectors currently past their gate, in registration order. */
    public List<SourceDetector> openDetectors() {
        return List.of(open);
    }

    /**
     * Whether nothing can be filtered at all, i.e. every open detector declared no markers.
     *
     * <p>Note the "every": a single markerless detector no longer bypasses the filter for the
     * others, so this is now true only in the degenerate case. Use {@link #unmarkedDetectorCount()}
     * to ask how many detectors are exempt.
     */
    public boolean unfiltered() {
        return markers == UNFILTERED;
    }

    /**
     * How many open detectors declared no markers and are therefore offered every line.
     *
     * <p>The number to watch: each one is a detector whose full cost is paid on every line of chat,
     * so it should stay very small and every entry should be a deliberate, documented choice.
     */
    public int unmarkedDetectorCount() {
        return unmarkedCount;
    }

    /** The literal substrings currently being tested, empty when unfiltered or nothing is open. */
    public List<String> activeMarkers() {
        return markers == UNFILTERED ? List.of() : List.of(markers);
    }

    /** Drops every detector, e.g. on disconnect. */
    public void clear() {
        registered.clear();
        context = GameContext.UNKNOWN;
        recompute();
    }

    /**
     * Rebuilds the two arrays the per-line path reads.
     *
     * <p>Runs on registration and on a real context change only. Allocating here is fine and
     * allocating in {@link #onChat(String, long)} is not, which is the whole reason the split
     * exists.
     */
    private void recompute() {
        List<SourceDetector> nowOpen = new ArrayList<>(registered.size());
        List<Boolean> nowFilterable = new ArrayList<>(registered.size());
        LinkedHashSet<String> union = new LinkedHashSet<>();
        int unmarked = 0;
        for (SourceDetector detector : registered) {
            if (!detector.readsChat() || !detector.gateOpen(context)) {
                continue;
            }
            List<String> declared = detector.chatMarkers();
            boolean declaredAny = false;
            if (declared != null) {
                for (String marker : declared) {
                    if (marker != null && !marker.isEmpty()) {
                        union.add(marker);
                        declaredAny = true;
                    }
                    // A blank marker would match every line. Treat it as "do not filter me" rather
                    // than as a filter that silently passes everything without saying so -- it
                    // leaves declaredAny false, so this detector joins the unmarked set.
                }
            }
            nowOpen.add(detector);
            nowFilterable.add(declaredAny);
            if (!declaredAny) {
                unmarked++;
            }
        }
        open = nowOpen.toArray(new SourceDetector[0]);
        filterable = new boolean[nowFilterable.size()];
        for (int i = 0; i < filterable.length; i++) {
            filterable[i] = nowFilterable.get(i);
        }
        unmarkedCount = unmarked;
        markers = unmarked == open.length && unmarked > 0
                ? UNFILTERED
                : union.toArray(new String[0]);
    }
}
