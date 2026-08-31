package com.skyprism.mc.chat;

/**
 * The chat module's whole view of the Diana feature.
 *
 * <p><b>Why an interface rather than a direct call.</b> The chat hook has to do three
 * things with a Diana-shaped line: hand it to the roll, skip everything when Diana is not
 * the active mayor, and decide whether to hide the server's own drop announcement. Only
 * the first is a plain method call; the other two are questions about live roll state that
 * the chat module has no business owning. Routing all three through one small interface
 * keeps {@code com.skyprism.mc.diana.DianaController} named in exactly one place -- the
 * default implementation in {@link ChatRouter} -- which is what lets the chat pipeline be
 * exercised with a two-line stub and what stops a change in the Diana module rippling into
 * the chat hook.</p>
 *
 * <p>Every method is called on the client thread, from inside Fabric's {@code ALLOW_GAME}
 * dispatch, and must not block or throw.</p>
 */
public interface DianaChatBridge {

    /** {@link #capturedDropCount()}'s answer when the roll state cannot be observed. */
    int UNKNOWN = -1;

    /**
     * Hands one chat line to the Diana pipeline.
     *
     * <p>The line arrives with its legacy formatting codes intact, reconstructed by
     * {@link com.skyprism.mc.text.LegacyText#toLegacy}, because {@code DianaPatterns} and {@code LootParser}
     * match on those codes and are anchored -- a plain-text line matches none of them.
     *
     * <p>The chat module has already applied a cheap marker reject, so implementations see
     * only lines carrying at least one Diana keyword. They must not assume the line is
     * actually a Diana line: the reject is a filter, not a parser.
     *
     * @param raw       the line with formatting codes intact, never null
     * @param nowMillis the wall clock to attribute the line to, so a replayed fixture can
     *                  drive a {@code FixedClock} instead of real time
     */
    void onChatMessage(String raw, long nowMillis);

    /**
     * Whether Diana is live at all -- on Hypixel, in SkyBlock, Diana is mayor, right area.
     *
     * <p>This is the {@code DianaGate.isOpen()} question, asked before the chat hook builds
     * any string at all, so that a player who is not on a Diana run pays one boolean read
     * per chat line and nothing else. The default is {@code true} so a bridge with no gate
     * -- a test stub -- still sees every line.
     *
     * @return false to make the chat hook skip the Diana path entirely
     */
    default boolean isOpen() {
        return true;
    }

    /**
     * How many drops the live roll has captured so far, or {@link #UNKNOWN}.
     *
     * <p><b>This is the entire suppression mechanism, and the shape is deliberate.</b> The
     * chat hook reads this immediately before and immediately after
     * {@link #onChatMessage}; a line is hidden only when the count went <em>up</em>, which
     * is to say only when the running roll positively accepted this line's drop and will
     * therefore reveal it on a reel.
     *
     * <p>Everything conservative about the feature falls out of that one rule for free. A
     * line the {@code LootParser} could not decompose changes no count and survives. A
     * genuine drop that arrives after the loot window has closed is refused by
     * {@code SlotRoll} itself, changes no count, and survives. A slayer drop while no roll
     * is running finds {@code capturedDrops()} empty on both reads and survives. Nothing
     * here re-derives {@code SlotRoll}'s timing, which the core explicitly warns adapters
     * not to do, and nothing here re-runs the parser -- the roll's own acceptance is the
     * answer.
     *
     * <p>The default is {@link #UNKNOWN}, which suppresses nothing. An unwired bridge must
     * fail towards showing the player their loot.
     *
     * @return the current captured-drop count, or {@link #UNKNOWN} when unobservable
     */
    default int capturedDropCount() {
        return UNKNOWN;
    }
}
