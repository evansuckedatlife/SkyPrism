package com.skyprism.mc.text;

import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTag;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.core.text.RunRewriter;
import com.skyprism.core.text.RunText;
import com.skyprism.core.text.Span;
import com.skyprism.core.text.StyledRun;
import com.skyprism.core.util.TextClean;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a Minecraft {@link Component} into styled runs, and back, so the core's span
 * rewriter can recolour a SkyBlock level prefix without destroying anything else on the
 * line.
 *
 * <h2>Why this class exists at all</h2>
 * <p>The obvious way to recolour {@code [451]} is to read the line's plain text, splice a
 * colour code in, and re-emit it as one fresh {@code Component.literal(...)}. That is also
 * the classic way a Hypixel mod quietly breaks the game: Hypixel hangs a
 * {@link net.minecraft.network.chat.HoverEvent} and a
 * {@link net.minecraft.network.chat.ClickEvent} off the player-name component in chat and
 * on every TAB entry, so hovering a name shows their profile and clicking it runs a
 * command. Those live on {@link Style}, not on the text. Flattening to a string throws
 * away every one of them, and the breakage is invisible to whoever wrote the mod, because
 * the colours look right.</p>
 *
 * <p>So the pipeline here never loses a style. {@link #toRuns(Component)} decomposes the
 * tree into contiguous same-style runs with every ancestor style already merged in,
 * {@link RunRewriter} splits only the runs a matched span actually touches and returns
 * every other run as the identical instance, and {@link #fromRuns(List)} reassembles.
 * Colour is the only attribute that changes, and it changes via
 * {@link Style#withColor(int)}, which copies the style and replaces one field -- hover,
 * click, insertion, font, bold, italic, underline, strikethrough and obfuscated all ride
 * through untouched.</p>
 *
 * <h2>Emblems</h2>
 * <p>Prefix emblems -- the diamond-like symbols earned every ten levels -- render to the
 * <em>right</em> of a player's name and must never be recoloured. Nothing in this class
 * decides that; {@link LevelTagLocator} only ever matches bracketed digit runs, so an
 * emblem cannot produce a span. This class must not add any matching of its own.</p>
 *
 * <h2>Performance contract</h2>
 * <p>{@link #recolourLevels} runs from the chat hook on every received message and, through
 * the TAB memoiser, potentially for eighty entries. Its first real act is
 * {@link #mightContainLevelTag(Component)}, a branchy character scan with no regex and no
 * flattening, which for the common single-literal component allocates nothing at all. Only
 * a component that survives the filter pays for runs, the locator's regex and a rebuild.
 * When nothing matches the <em>same instance</em> comes back, so a caller can detect a
 * no-op with {@code result == source} and skip its own downstream work -- which is exactly
 * what makes the TAB cache cheap.</p>
 *
 * <p>All methods are static and the class holds no mutable state, so it is safe to call
 * from the render thread and the network thread alike.</p>
 */
public final class ComponentRewriter {

    /** Scanner state: nothing promising seen yet. */
    private static final int SEEKING_BRACKET = 0;
    /** Scanner state: an opening bracket was seen; the next visible character must be a digit. */
    private static final int SEEKING_DIGIT = 1;
    /** Scanner state: inside a digit run that began right after an opening bracket. */
    private static final int IN_DIGITS = 2;

    private ComponentRewriter() {
    }

    /**
     * Recolours every SkyBlock level prefix in {@code source} using {@code palette},
     * preserving every other style attribute and every hover and click event.
     *
     * <p>The steps, in order: cheap pre-filter; decompose to runs; flatten; strip formatting
     * codes (Hypixel normally sends real styles rather than legacy codes, but a proxy or a
     * plugin can still put a literal section sign in the text, and the locator documents
     * that it must be handed already-plain text); locate tags; project each tag back into
     * flattened-text coordinates; hand the spans to
     * {@link RunRewriter#restyle(List, List, java.util.function.BiFunction)} with the level
     * carried in {@link Span#payload()} so the restyler never re-parses the digits; rebuild.</p>
     *
     * <p>The strip and the projection are both
     * {@link TextClean#stripFormattingWithOffsets(String)}'s job. This class used to carry
     * its own copy of the escape rules purely so it could record where each surviving
     * character came from -- {@link TextClean#stripFormatting(String)} returns only a
     * string, and a tag located in that string is at the wrong index for the flattened text
     * a {@link Span} has to address. Two copies of "what is a legacy code" is one rule
     * written twice and free to drift, and it was: the fix belongs in the core, where it is
     * tested, and this class now just asks. {@link TextClean.StripResult#sourceEndOf(int)}
     * carries the end-offset rule that used to live here -- map the range's <em>last</em>
     * character and add one, so that a code inside the tag falls inside the span (invisible)
     * while a code after the closing bracket stays outside it.</p>
     *
     * <p><b>Returns {@code source} itself</b> whenever there is nothing to do -- a null
     * component, one the pre-filter rejects, or one the locator finds no tag in. Callers
     * rely on that reference identity to skip work, so it must not be "tidied up" into a
     * defensive copy.</p>
     *
     * <p><b>It does not throw</b>, and the catch is written wide enough to mean it.
     * {@code RunRewriter} rejects malformed spans loudly, which is right for a parser but wrong
     * three frames deep in a render loop: an exception here would take out the chat line or the
     * whole TAB overlay rather than merely leaving a tag in Hypixel's own colour. The spans built
     * below cannot legitimately be malformed -- the locator guarantees ordered, non-overlapping
     * matches, and a bracket and its digits are ASCII so no surrogate pair can be split -- so a
     * throw would mean a bug upstream, and the graceful answer to that is an uncoloured tag rather
     * than a broken screen. Note that this contract is only worth as much as the catch: the TAB
     * and nametag surfaces have a failure budget behind them, but the chat hook has nothing, so a
     * class of failure that escaped here would repeat once per received Hypixel line, forever,
     * from inside the client's own chat handler.</p>
     *
     * @param source              the component to recolour; may be null, in which case null comes back
     * @param palette             supplies the colour for a level, animated or not; not null
     * @param locator             decides what counts as a level tag; not null
     * @param recolourBracketsToo true to tint the square brackets along with the number,
     *                            false to tint only the digits and leave the brackets in
     *                            whatever colour Hypixel sent
     * @param nowMillis           wall-clock milliseconds, read only for chroma-animated
     *                            levels; passing a value that advances no faster than the
     *                            configured refresh rate is what caps the animation's cost
     * @return the recoloured component, or {@code source} unchanged when nothing matched
     */
    public static Component recolourLevels(Component source, LevelPalette palette,
                                           LevelTagLocator locator, boolean recolourBracketsToo,
                                           long nowMillis) {
        return recolourLevels(source, palette, locator, recolourBracketsToo, nowMillis, null);
    }

    /**
     * {@link #recolourLevels(Component, LevelPalette, LevelTagLocator, boolean, long)}, additionally
     * reporting what it found.
     *
     * <p>Two callers need a fact about the tags that this method has already established and that
     * re-deriving costs another full pass:</p>
     * <ul>
     *   <li>the TAB and nametag surfaces need the <b>highest level</b> in the component, to decide
     *       whether the entry animates and so has to be re-rendered at the chroma rate. Answering
     *       that by rescanning -- flatten the result again, strip it again, run the locator's
     *       regex over it again -- doubled the steady-state cost of the one configuration the
     *       feature's headline mode creates, and the second scan could only ever agree with the
     *       first;</li>
     *   <li>the profiler needs the <b>tag count</b>, which the chat hook used to recompute the
     *       same way, on every recoloured line, in every session, because profiling is on by
     *       default.</li>
     * </ul>
     *
     * <p>Reported through a caller-owned {@code int[]} rather than a return record so the hot path
     * stays allocation-free: both callers keep one array for the life of the process and both are
     * single-threaded. {@code out[0]} receives the number of tags matched and {@code out[1]}, when
     * the array is long enough, the highest level among them; both are set to 0 when nothing
     * matched, so a caller never reads a stale value from a previous call.
     *
     * @param out a scratch array of length 1 or more, or null when neither fact is wanted
     * @return the recoloured component, or {@code source} unchanged when nothing matched
     */
    public static Component recolourLevels(Component source, LevelPalette palette,
                                           LevelTagLocator locator, boolean recolourBracketsToo,
                                           long nowMillis, int[] out) {
        report(out, 0, 0);
        if (source == null) {
            return null;
        }
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(locator, "locator");

        // The pre-filter is inside the try as well: it walks the component, and a component
        // assembled by another mod is exactly as capable of throwing during that walk as the
        // pipeline behind it is.
        try {
            if (!mightContainLevelTag(source)) {
                return source;
            }
            return rewrite(source, palette, locator, recolourBracketsToo, nowMillis, out);
        } catch (RuntimeException | LinkageError broken) {
            // The javadoc above promises in bold that this method does not throw, and the promise
            // is what the chat hook relies on -- unlike the two render surfaces it has no failure
            // budget behind it, and anything escaping here lands inside Fabric's message dispatch
            // and repeats on every line Hypixel sends. The catch used to name only
            // IllegalArgumentException, which left every other way the pipeline can fail
            // (TextClean.StripResult.sourceEndOf documents an IndexOutOfBoundsException;
            // RunText.flatten documents a NullPointerException) escaping a method documented not
            // to. An uncoloured tag is the right answer to all of them.
            report(out, 0, 0);
            return source;
        }
    }

    /** Writes the out-parameters, tolerating a null or short array. */
    private static void report(int[] out, int tagCount, int maxLevel) {
        if (out == null || out.length == 0) {
            return;
        }
        out[0] = tagCount;
        if (out.length > 1) {
            out[1] = maxLevel;
        }
    }

    private static Component rewrite(Component source, LevelPalette palette,
                                     LevelTagLocator locator, boolean recolourBracketsToo,
                                     long nowMillis, int[] out) {
        List<StyledRun<Style>> runs = toRuns(source);
        String flat = RunText.flatten(runs);

        // The locator's precondition is already-plain text. When the flattened string holds no
        // section sign -- every well-formed Hypixel line -- stripping is the identity and tag
        // offsets are already flattened-text offsets, so the projection is skipped entirely.
        // Only a line that really carries a legacy code pays for building one.
        List<LevelTag> tags;
        TextClean.StripResult stripped = null;
        if (flat.indexOf(TextClean.SECTION) < 0) {
            tags = locator.find(flat);
        } else {
            stripped = TextClean.stripFormattingWithOffsets(flat);
            tags = locator.find(stripped.stripped());
        }
        if (tags.isEmpty()) {
            return source;
        }

        List<Span> spans = new ArrayList<>(tags.size());
        int maxLevel = 0;
        for (LevelTag tag : tags) {
            int from = recolourBracketsToo ? tag.start() : tag.digitsStart();
            int to = recolourBracketsToo ? tag.end() : tag.digitsEnd();
            if (stripped != null) {
                from = stripped.sourceIndexOf(from);
                to = stripped.sourceEndOf(to);
            }
            spans.add(new Span(from, to, tag.level()));
            maxLevel = Math.max(maxLevel, tag.level());
        }

        List<StyledRun<Style>> restyled = RunRewriter.restyle(runs, spans,
                (style, span) -> (style == null ? Style.EMPTY : style)
                        .withColor(palette.colorFor(span.payload(), nowMillis)));
        report(out, tags.size(), maxLevel);
        return fromRuns(restyled);
    }

    /**
     * Decomposes a component into contiguous runs of identical style.
     *
     * <p>Uses {@link Component#visit(FormattedText.StyledContentConsumer, Style)}, whose
     * entire job is this: it walks the tree in visual order and hands each chunk of text the
     * style already resolved against every ancestor. That resolution is the part nobody
     * should hand-roll -- a child's {@code Style} carries nulls meaning "inherit", and
     * getting the merge order wrong silently drops a parent's hover event.</p>
     *
     * <p>Consequently {@code RunText.flatten(toRuns(c))} equals {@code c.getString()} exactly,
     * which is the invariant every offset in this class depends on. Empty chunks are dropped
     * rather than recorded: they contribute no characters, so they cannot carry an offset,
     * and keeping them would only add nodes for {@link #fromRuns(List)} to discard again.</p>
     *
     * @param source the component to decompose; may be null, which yields an empty list
     * @return a mutable list of runs in visual order, never null
     */
    public static List<StyledRun<Style>> toRuns(Component source) {
        List<StyledRun<Style>> runs = new ArrayList<>();
        if (source == null) {
            return runs;
        }
        source.visit((style, text) -> {
            if (!text.isEmpty()) {
                runs.add(new StyledRun<>(text, style));
            }
            return Optional.empty(); // an empty Optional means "keep walking"
        }, Style.EMPTY);
        return runs;
    }

    /**
     * Rebuilds a component from runs, one child node per run.
     *
     * <p>Every run's style is already fully resolved, so the children hang off an empty root
     * ({@link Style#EMPTY}) and inherit nothing they did not already carry. The result
     * renders identically to the original and, crucially, still carries the original hover
     * and click events on the runs that had them.</p>
     *
     * <p>Two size reductions that change no behaviour: empty runs are dropped, since they
     * contribute no characters and their style can reach nothing; and adjacent runs sharing
     * the very same {@code Style} <em>instance</em> are merged. Reference equality is
     * deliberate rather than {@link Object#equals} -- after a restyle the untouched runs come
     * back as their original instances, so identity catches every merge that matters, while a
     * deep style comparison (which would walk hover-event component trees) can easily cost
     * more than the node it saves.</p>
     *
     * @param runs the runs to reassemble; not null, containing no nulls
     * @return a component whose plain string equals {@code RunText.flatten(runs)}
     */
    public static Component fromRuns(List<StyledRun<Style>> runs) {
        Objects.requireNonNull(runs, "runs");

        List<StyledRun<Style>> merged = new ArrayList<>(runs.size());
        for (StyledRun<Style> run : runs) {
            if (run.text().isEmpty()) {
                continue;
            }
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).style() == run.style()) {
                StyledRun<Style> previous = merged.get(last);
                merged.set(last, new StyledRun<>(previous.text() + run.text(), previous.style()));
            } else {
                merged.add(run);
            }
        }

        if (merged.isEmpty()) {
            return Component.empty();
        }
        if (merged.size() == 1) {
            return literalOf(merged.get(0));
        }
        MutableComponent root = Component.empty();
        for (StyledRun<Style> run : merged) {
            root.append(literalOf(run));
        }
        return root;
    }

    /** One run as a styled literal node, tolerating a null style by reading it as "inherit". */
    private static MutableComponent literalOf(StyledRun<Style> run) {
        Style style = run.style();
        return Component.literal(run.text()).setStyle(style == null ? Style.EMPTY : style);
    }

    /**
     * The cheap pre-filter: could this component possibly contain a level tag?
     *
     * <p>A false positive costs one wasted flatten-and-match. A false negative silently
     * leaves a tag uncoloured and is close to impossible to notice in testing, so this method
     * is written to over-accept and is never allowed to under-accept.</p>
     *
     * <p>It runs no regex and never builds the flattened string. A component that is a single
     * plain literal with no siblings -- which is what a TAB entry usually is -- is scanned
     * straight out of its {@code String} with <b>zero allocation</b>. Anything else goes
     * through {@link Component#visit(FormattedText.ContentConsumer)}, the style-free visitor,
     * which is cheaper than the styled one because it never merges a {@code Style}; the walk
     * stops the instant a candidate completes.</p>
     *
     * <p>The scan is a three-state machine looking for {@code '['}, then at least one ASCII
     * digit, then {@code ']'}, skipping legacy formatting codes exactly the way
     * {@link TextClean#stripFormatting(String)} skips them, because that is the string the
     * locator will actually see. State is carried across chunk boundaries, so a tag Hypixel
     * happened to split across three components ({@code "["}, {@code "451"}, {@code "]"}) is
     * still caught. Everything the locator additionally demands -- no leading zeros, a
     * digit-count cap, the letter-or-digit boundary rule, the configured level range -- only
     * narrows that set further, so anything the locator can match this filter accepts.</p>
     *
     * @param source the component to test; may be null, which yields false
     * @return false only when the component provably contains no level tag
     */
    public static boolean mightContainLevelTag(Component source) {
        if (source == null) {
            return false;
        }
        if (source.getSiblings().isEmpty() && source.getContents() instanceof PlainTextContents plain) {
            return mightContainLevelTag(plain.text());
        }
        return source.visit(new TagScanner()).isPresent();
    }

    /**
     * The same pre-filter over a bare string, for callers that already hold one -- a
     * scoreboard row or a raw chat line, say -- and would otherwise wrap it in a component
     * purely to ask the question.
     *
     * @param text the text to test; may be null, which yields false
     * @return false only when the text provably contains no level tag
     */
    public static boolean mightContainLevelTag(String text) {
        if (text == null || text.length() < 3) {
            return false;
        }
        TagScanner scanner = new TagScanner();
        scanner.feed(text);
        return scanner.found;
    }

    /**
     * True for the colour, style and reset characters Mojang's legacy format defines.
     *
     * <p>Kept here, rather than borrowed from {@link TextClean}, only because
     * {@link TagScanner} needs it and {@code TextClean}'s copy is private. It answers a
     * looser question than the stripper does -- the scanner never has to decide whether a
     * {@code section-x} RGB run is well formed, since either way those characters are
     * invisible to the locator and must not break a digit run. Nothing that produces
     * <em>offsets</em> is duplicated any more; that is
     * {@link TextClean#stripFormattingWithOffsets(String)}'s job.</p>
     */
    private static boolean isLegacyCode(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o')
                || (c >= 'K' && c <= 'O')
                || c == 'r'
                || c == 'R';
    }

    /**
     * The resumable bracketed-digits scanner behind {@link #mightContainLevelTag(Component)}.
     *
     * <p>It is a {@link FormattedText.ContentConsumer} so it can be handed straight to
     * {@code Component.visit} and stop the walk early -- returning a present {@link Optional}
     * is Minecraft's "I am done" signal. Its state lives in fields rather than locals
     * precisely so a tag split across sibling components still matches.</p>
     *
     * <p>{@code pendingCode} is the one subtle field. A section sign introduces a formatting
     * code only when a legacy code character follows it, and that character may live in the
     * next chunk, so the decision has to survive a call boundary.</p>
     */
    private static final class TagScanner implements FormattedText.ContentConsumer<Boolean> {

        private int state = SEEKING_BRACKET;
        private boolean pendingCode;
        private boolean found;

        @Override
        public Optional<Boolean> accept(String text) {
            feed(text);
            return found ? Optional.of(Boolean.TRUE) : Optional.empty();
        }

        /** Advances the machine over one chunk, stopping as soon as a candidate completes. */
        void feed(String text) {
            for (int i = 0, n = text.length(); i < n && !found; i++) {
                char c = text.charAt(i);
                if (pendingCode) {
                    pendingCode = false;
                    // A legacy code, or the marker of the section-x RGB form, is invisible to
                    // the locator and so must not break up a digit run.
                    if (isLegacyCode(c) || c == 'x' || c == 'X') {
                        continue;
                    }
                    // Not a code after all, so the section sign was ordinary text -- and
                    // ordinary text that is neither a bracket nor a digit ends any candidate.
                    state = SEEKING_BRACKET;
                }
                if (c == TextClean.SECTION) {
                    pendingCode = true;
                    continue;
                }
                switch (state) {
                    case SEEKING_DIGIT -> {
                        if (c >= '0' && c <= '9') {
                            state = IN_DIGITS;
                        } else {
                            state = c == '[' ? SEEKING_DIGIT : SEEKING_BRACKET;
                        }
                    }
                    case IN_DIGITS -> {
                        if (c == ']') {
                            found = true;
                        } else if (c < '0' || c > '9') {
                            state = c == '[' ? SEEKING_DIGIT : SEEKING_BRACKET;
                        }
                    }
                    default -> {
                        if (c == '[') {
                            state = SEEKING_DIGIT;
                        }
                    }
                }
            }
        }
    }
}
