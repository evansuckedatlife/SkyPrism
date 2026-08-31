package com.skyprism.mc.chat;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.mc.command.Metrics;
import com.skyprism.mc.config.ConfigManager;
import com.skyprism.mc.diana.DianaController;
import com.skyprism.mc.surfaces.LevelSurfaces;
import com.skyprism.mc.text.ComponentRewriter;
import com.skyprism.mc.text.LegacyText;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything SkyPrism does to an incoming chat line, in one place and with no Fabric event
 * plumbing in sight.
 *
 * <p>{@link ChatHooks} owns the registration; this class owns the decisions. The split
 * exists so the pipeline can be driven from a command ({@link #replay}) or from a test
 * without a running game, and so that the two Fabric callbacks stay one line each.</p>
 *
 * <h2>Two callbacks, because Fabric has no third option</h2>
 *
 * <p>Fabric's message API can veto a game message ({@code ALLOW_GAME}, returns boolean) or
 * rewrite it ({@code MODIFY_GAME}, returns a component), and neither can do both. SkyPrism
 * needs both -- Diana hides a drop line, levels recolour a name -- so the work is split
 * along exactly that seam:</p>
 *
 * <ul>
 *   <li>{@link #allowGameMessage} feeds the Diana pipeline and decides suppression.</li>
 *   <li>{@link #modifyGameMessage} recolours level tags.</li>
 * </ul>
 *
 * <p>The Diana feed lives in the <em>allow</em> phase rather than the read-only
 * {@code GAME} phase for one reason: the suppression decision needs to know whether the
 * roll accepted this line's drop, and that is only knowable after the line has been fed.
 * Doing both in one callback means the raw line is reconstructed once instead of twice.
 * The cost of that choice is honest and worth stating: {@code ALLOW_GAME} short-circuits on
 * the first listener that returns false, so a third-party mod registered ahead of SkyPrism
 * that cancels a Hypixel line would hide it from the Diana parser too. {@code GAME} would
 * lose the same line for the same reason, so nothing is given up by preferring the phase
 * that can actually act.</p>
 *
 * <h2>The cost of a chat line that is not for us</h2>
 *
 * <p>Hypixel is a chatty server and both callbacks run on every single system message, so
 * the ordering of the early-outs is a feature and not an accident:</p>
 *
 * <ol>
 *   <li><b>Action-bar messages leave immediately.</b> The overlay flag is the cheapest test
 *       there is, and the action bar can carry neither a level tag worth recolouring nor a
 *       Diana line.</li>
 *   <li><b>The relevant config toggle is read next</b> -- three field loads off an already
 *       sanitised, always-non-null settings tree.</li>
 *   <li><b>The level path never builds a string and never runs a regex.</b>
 *       {@link ComponentRewriter#mightContainLevelTag(Component)} walks the component with
 *       the style-free visitor and stops at the first candidate, so a line with no
 *       {@code [digits]} in it costs a walk, no flattened string and no component rebuild.
 *       (It is not literally allocation-free, as this list once claimed: a component with
 *       siblings -- which is every Hypixel chat line -- costs one small scanner object. The
 *       scanner is not shared and made resettable, because this class is documented as safe to
 *       call from the render thread and the network thread alike and one static mutable scanner
 *       would quietly retract that.)</li>
 *   <li><b>The Diana path asks the gate first,</b> then allocates one plain string, then
 *       rejects on {@link DianaLineFilter}'s literal substring searches. Only a line that
 *       survives all of that is worth reconstructing into its legacy form, which is the one
 *       genuinely expensive step, and it happens for a handful of lines per Diana run.</li>
 * </ol>
 *
 * <p>No regex runs anywhere in this class. The only regexes in the chat path are the core's
 * own, inside {@code LevelTagLocator} and {@code DianaPatterns}, and both sit behind the
 * rejects above.</p>
 *
 * <h2>Chroma in chat is a snapshot, on purpose</h2>
 *
 * <p>{@code MODIFY_GAME} runs once, when the line arrives, and the component it returns is
 * what the chat history stores forever after. A chroma-animated level therefore gets the
 * colour it had at the moment the message was received and does not shimmer in the
 * scrollback. Animating it would mean re-rendering every line of chat history every frame,
 * which is precisely the kind of per-frame cost the mod is built to avoid; the TAB list and
 * nametags, which are rebuilt every frame anyway, are where chroma actually moves.</p>
 *
 * <p>All state is static and touched only from the client thread.</p>
 */
public final class ChatRouter {

    /** Swappable so tests, and the Diana module itself, can substitute their own. */
    private static volatile DianaChatBridge bridge = new ControllerBridge();

    /**
     * Whether {@link #allowGameMessage} does the Diana half of its job.
     *
     * <p><b>This exists because the work is currently done twice.</b>
     * {@code DianaController.init()} registers an {@code ALLOW_GAME} listener of its own
     * that performs the same reject, the same reconstruction, the same feed and the same
     * suppression. Both copies were briefed into existence independently and both are
     * correct; running both is merely wasteful, not wrong, because the controller ignores a
     * line it has already seen within its 50 ms duplicate window and Fabric hides a line if
     * <em>either</em> listener vetoes it. Whichever of the two the project keeps, the other
     * should be switched off, and this is the switch for this one.
     *
     * <p>It defaults to on because that is the failure-safe default: with both listeners
     * live the feature works and costs one redundant {@code getString()} per chat line while
     * the gate is open, whereas defaulting to off would silently kill Diana chat parsing
     * outright if the controller's listener is the copy that gets removed.
     */
    private static volatile boolean dianaFeedEnabled = true;

    /** Lines hidden so the reels could be the reveal. Client thread only; display counter. */
    private static long suppressedLines;

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Chat");

    /**
     * Scratch for {@link ComponentRewriter}'s out-parameters: tag count, then highest level.
     *
     * <p>Client thread only, like everything else here. It exists so the profiler's tag count can
     * be read off the scan the rewriter already did rather than recomputed from the result -- a
     * full flatten, a second string and a second regex pass, on every recoloured line, which on
     * Hypixel is very nearly every line.
     */
    private static final int[] SCAN = new int[2];

    /** Set once a recolour has thrown, so the log carries one stack trace rather than thousands. */
    private static boolean recolourFailureLogged;

    private ChatRouter() {
    }

    // ------------------------------------------------------------------ the two callbacks

    /**
     * The {@code ALLOW_GAME} body: feeds Diana, and answers whether the line may be shown.
     *
     * <p>Returning false hides the line from the chat HUD entirely. That only ever happens
     * for a line whose drop the running roll just accepted, and only when the player asked
     * for it with {@code diana.suppressDropChatLines}; see
     * {@link DianaChatBridge#capturedDropCount()} for why that rule is the conservative
     * one.
     *
     * @param message the incoming message
     * @param overlay true when this is an action-bar message rather than a chat line
     * @return false to swallow the line, true to let it through
     */
    public static boolean allowGameMessage(Component message, boolean overlay) {
        if (overlay || message == null || !dianaFeedEnabled) {
            return true;
        }
        SkyPrismConfig.DianaSettings diana = ConfigManager.get().config().diana;
        if (!diana.enabled) {
            return true;
        }
        DianaChatBridge target = bridge;
        if (!target.isOpen()) {
            return true;
        }
        String plain = message.getString();
        if (!mightMatterToDiana(plain)) {
            return true;
        }
        boolean suppress = pump(target, LegacyText.toLegacy(message),
                System.currentTimeMillis(), diana.suppressDropChatLines);
        if (suppress) {
            suppressedLines++;
        }
        return !suppress;
    }

    /**
     * The {@code MODIFY_GAME} body: recolours level tags.
     *
     * <p>Returns the argument unchanged whenever there is nothing to do, which is the
     * documented no-op for a chained modify listener and is also what
     * {@link ComponentRewriter#recolourLevels} returns by reference identity, so a line
     * with no tag in it costs no allocation anywhere in the chain.
     *
     * @param message the incoming message, possibly already rewritten by another listener
     * @param overlay true when this is an action-bar message rather than a chat line
     * @return the recoloured message, or {@code message} itself
     */
    public static Component modifyGameMessage(Component message, boolean overlay) {
        if (overlay || message == null) {
            return message;
        }
        boolean measure = Metrics.enabled();
        long startNanos = measure ? System.nanoTime() : 0L;
        try {
            SkyPrismConfig.LevelSettings levels = ConfigManager.get().config().levels;
            if (!levels.enabled || !levels.applyToChat) {
                return message;
            }
            // The server check, shared with the TAB and nametag surfaces so all three agree on
            // what "in scope" means. A SkyBlock level prefix only exists inside SkyBlock, and
            // nothing about the token's shape distinguishes one bracketed number from another, so
            // without this a teammate typing "we need [2] more" in a Bedwars lobby gets repainted
            // with the SkyBlock ramp.
            if (!LevelSurfaces.levelScopeSatisfied(levels)) {
                return message;
            }
            // Cheap pre-filter: no '[digits]' anywhere means no possible tag, and this is the
            // branch almost every Hypixel line takes. It runs no regex, builds no flattened
            // string and rebuilds nothing; a single-literal component with no siblings is scanned
            // straight out of its String with no allocation at all, and anything with siblings
            // costs one small scanner object.
            if (!ComponentRewriter.mightContainLevelTag(message)) {
                return message;
            }
            return recolour(message, levels, System.currentTimeMillis(), measure);
        } catch (Throwable broken) {
            // The rewriter promises not to throw and now catches widely enough to keep that
            // promise, so reaching here means something outside it failed. Either way this
            // callback runs once per received Hypixel line: it must degrade to the server's own
            // colours rather than throwing inside the client's message dispatch on every line.
            if (!recolourFailureLogged) {
                recolourFailureLogged = true;
                LOGGER.warn("SkyPrism chat recolour failed; leaving the line as the server sent it. "
                        + "Further failures will not be logged.", broken);
            }
            return message;
        } finally {
            if (measure) {
                Metrics.chatMessage(System.nanoTime() - startNanos);
            }
        }
    }

    // ------------------------------------------------------------------ the testable seam

    /**
     * Runs a raw line through the whole pipeline exactly as a received message would go,
     * and hands back what chat would have shown.
     *
     * <p>This is what backs {@code /skyprism replay} and {@code /skyprism simulate}: a
     * fixture captured from Hypixel is a legacy section-sign string, and to be a fair test
     * it has to travel the same road a real message does -- the same Diana feed, the same
     * suppression rule, the same recolouring, the same config toggles. Anything that
     * bypassed one of those would be a demonstration of code that does not run in
     * production.
     *
     * <p>The Diana marker reject is applied to {@code raw} directly rather than to the
     * parsed component's plain text. The two agree for every line the core can match, since
     * no marker in {@link DianaLineFilter#MARKERS} has a formatting code spliced into the
     * middle of it, and using the fixture verbatim keeps a hand-written test line honest.
     *
     * @param raw the line with legacy formatting codes intact, may be null
     * @return the component chat would display, or null when the line was suppressed or the
     *         input was null
     */
    public static Component replay(String raw) {
        if (raw == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        SkyPrismConfig config = ConfigManager.get().config();
        DianaChatBridge target = bridge;

        if (config.diana.enabled && target.isOpen() && mightMatterToDiana(raw)
                && pump(target, raw, now, config.diana.suppressDropChatLines)) {
            suppressedLines++;
            return null;
        }

        Component built = LegacyText.fromLegacy(raw);
        // Deliberately not gated on onlyOnSkyBlock. Replay is an explicit request to run the
        // pipeline against a fixture, usually from a dev client that is not on Hypixel at all;
        // refusing to colour it there would make the one command written to demonstrate the
        // feature the one command that cannot.
        if (!config.levels.enabled || !config.levels.applyToChat
                || !ComponentRewriter.mightContainLevelTag(built)) {
            return built;
        }
        return recolour(built, config.levels, now, false);
    }

    /**
     * Feeds one already-reconstructed raw line to the Diana pipeline, ignoring every
     * config toggle and every gate.
     *
     * <p>Offered for {@code /skyprism simulate}, where the whole point is to drive the
     * feature while Diana is not mayor and the gate is shut. It deliberately cannot
     * suppress anything: a simulated line was never in the player's chat to begin with.
     *
     * @param raw       the line with formatting codes intact; null is ignored
     * @param nowMillis the timestamp to attribute it to
     */
    public static void inject(String raw, long nowMillis) {
        if (raw != null) {
            bridge.onChatMessage(raw, nowMillis);
        }
    }

    /**
     * The cheap reject that keeps the core's anchored regexes off the chat thread.
     *
     * <p>Kept on {@code ChatRouter} because callers and tests already reach for it here, but
     * it is now one line: the marker list and the scan live in {@link DianaLineFilter},
     * which has no Minecraft imports and so can be enumerated against
     * {@code DianaPatterns} from a test. That move is the whole point -- the list is a
     * contract with the Diana module, and a core pattern sharing none of its words used to
     * be able to disappear from the pipeline with nothing anywhere reporting it.
     *
     * @param text the line to test, plain or raw; null yields false
     * @return true when the line contains a word that could belong to a Diana pattern
     */
    public static boolean mightMatterToDiana(String text) {
        return DianaLineFilter.mightMatterToDiana(text);
    }

    // ------------------------------------------------------------------ wiring

    /**
     * Replaces the Diana bridge.
     *
     * <p>The Diana module calls this once it has built its controller, so that suppression
     * and the gate check answer from live roll state instead of the defaults. Passing null
     * restores the built-in bridge rather than disabling the feed, because a null bridge
     * would turn every chat line into a null check.
     *
     * @param value the new bridge, or null to restore the default
     */
    public static void setDianaBridge(DianaChatBridge value) {
        bridge = value == null ? new ControllerBridge() : value;
    }

    /**
     * @return the bridge currently receiving Diana lines; never null
     */
    public static DianaChatBridge dianaBridge() {
        return bridge;
    }

    /**
     * Turns the Diana half of the chat hook on or off.
     *
     * <p>Call {@code setDianaFeedEnabled(false)} from the initialiser if the project keeps
     * {@code DianaController}'s own {@code ALLOW_GAME} listener instead of this one; see the
     * field documentation for why both currently exist. Level recolouring is unaffected --
     * it lives in the other callback.
     *
     * <p>{@link #replay} and {@link #inject} ignore this flag. They are explicit requests to
     * run the pipeline, not passive observation of chat.
     *
     * @param value false to stop feeding and suppressing from this module
     */
    public static void setDianaFeedEnabled(boolean value) {
        dianaFeedEnabled = value;
    }

    /**
     * @return whether this module is feeding the Diana pipeline from chat
     */
    public static boolean dianaFeedEnabled() {
        return dianaFeedEnabled;
    }

    /**
     * @return how many chat lines have been hidden this session so the reels could reveal
     *         them; for {@code /skyprism status}
     */
    public static long suppressedLines() {
        return suppressedLines;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Reads the captured-drop count, feeds the line, reads it again.
     *
     * <p>The two reads bracket exactly one call so that "the roll took this line's drop" is
     * observed rather than inferred. The count is only read at all when suppression is
     * switched on, so the ordinary path pays for the feed and nothing else.
     */
    private static boolean pump(DianaChatBridge target, String raw, long nowMillis,
                                boolean suppressEnabled) {
        if (!suppressEnabled) {
            target.onChatMessage(raw, nowMillis);
            return false;
        }
        int before = target.capturedDropCount();
        target.onChatMessage(raw, nowMillis);
        int after = target.capturedDropCount();
        return before != DianaChatBridge.UNKNOWN && after > before;
    }

    /**
     * The recolour proper, plus the metrics bookkeeping that only happens when the profiler
     * is switched on.
     *
     * <p>The tag count reported to {@link Metrics} comes back through the rewriter's
     * out-parameter. It used to be <em>recounted</em> from the result -- another
     * {@code getString()}, another {@code stripFormatting}, another run of the locator's regex --
     * on the stated grounds that the cost was confined to sessions with profiling enabled. It was
     * not: {@code Metrics.enabled} defaults to true, so every player paid it, on every recoloured
     * line, to serve a counter only {@code /skyprism profile} ever reads. The rewriter already
     * knows the number; a caller-owned scratch array is what lets it hand it over without putting
     * an allocation on the path.
     */
    private static Component recolour(Component message, SkyPrismConfig.LevelSettings levels,
                                      long nowMillis, boolean measure) {
        ConfigManager config = ConfigManager.get();
        Component result = ComponentRewriter.recolourLevels(
                message, config.palette(), config.locator(), levels.recolourBrackets, nowMillis,
                SCAN);
        if (measure && result != message) {
            Metrics.chatRewrite(SCAN[0]);
        }
        return result;
    }

    /**
     * The default bridge: the Diana module's own singleton, for all three questions.
     *
     * <p>{@code DianaController} publishes {@code gate()} and {@code roll()} alongside
     * {@code onChatMessage}, so the gate check and the captured-drop count come straight
     * from live state with no registration step for an integrator to forget. Suppression
     * therefore works out of the box rather than waiting for something to be wired.
     *
     * <p>{@code roll()} constructs the roll on first use, so the first Diana-marked line of
     * a session pays for that construction here. That is once per session and it happens on
     * a line the player is about to see a slot machine for anyway.
     */
    private static final class ControllerBridge implements DianaChatBridge {

        @Override
        public void onChatMessage(String raw, long nowMillis) {
            DianaController.get().onChatMessage(raw, nowMillis);
        }

        @Override
        public boolean isOpen() {
            return DianaController.get().gate().isOpen();
        }

        @Override
        public int capturedDropCount() {
            SlotRoll roll = DianaController.get().roll();
            return roll == null ? UNKNOWN : roll.capturedDropCount();
        }

        @Override
        public String toString() {
            return "DianaController bridge";
        }
    }
}
