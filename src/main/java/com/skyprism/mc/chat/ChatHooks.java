package com.skyprism.mc.chat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attaches {@link ChatRouter} to Fabric's message events. This class is the only thing in
 * the chat module that knows Fabric exists.
 *
 * <h2>Which events, and why there is no third one</h2>
 *
 * <p>{@code ClientReceiveMessageEvents} exposes seven constants and exactly one of them can
 * rewrite a message: {@code MODIFY_GAME}. There is no {@code MODIFY_CHAT} -- signed player
 * chat can be allowed or observed but never altered -- so the level-tag recolouring only
 * reaches system messages. That is not a limitation in practice: on Hypixel the lines that
 * carry a {@code [451]} prefix are server-composed system messages, not signed player
 * chat.</p>
 *
 * <p>{@code ALLOW_GAME} and {@code MODIFY_GAME} both cover the action bar as well as the
 * chat box, distinguished by the boolean the handler receives. {@link ChatRouter} discards
 * overlay messages on its first line.</p>
 *
 * <p>The read-only {@code GAME} event is deliberately not used. The Diana feed lives in the
 * allow phase instead, for the reason set out in {@link ChatRouter}'s class documentation:
 * the suppression decision depends on what the feed did, so the two cannot be in different
 * phases without reconstructing the raw line twice.</p>
 *
 * <h2>Registration order</h2>
 *
 * <p>Both events are registered without a phase {@code Identifier}, so SkyPrism runs in
 * default phase in registration order. That is the right default: SkyPrism only recolours a
 * span it can prove is a level prefix, and it does so by restyling rather than by replacing
 * text, so it composes with any other mod's rewrite regardless of who goes first.</p>
 *
 * <h2>One thing for the integrator to decide</h2>
 *
 * <p>{@code DianaController.init()} registers an {@code ALLOW_GAME} listener that does the
 * same Diana feed and the same drop-line suppression this module's does. Both are correct
 * and running both is safe -- the controller drops a line it has already seen, and Fabric
 * hides a line if either listener vetoes it -- but it is redundant work on every chat line
 * while a Diana run is live. Keep one. To retire this one, call
 * {@link ChatRouter#setDianaFeedEnabled(boolean)} with false after {@link #register()};
 * level recolouring is in the other callback and is unaffected.</p>
 */
public final class ChatHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Chat");

    /**
     * Registration is global and permanent -- Fabric events have no unregister -- so a
     * second call would silently double every chat line's work. Guarding is cheaper than
     * relying on the initialiser being called exactly once.
     */
    private static boolean registered;

    private ChatHooks() {
    }

    /**
     * Registers the chat hooks. Safe to call more than once; the second call does nothing.
     *
     * <p>Call this from the client initialiser. It touches no configuration and no Diana
     * state, so it is order-independent with respect to the other modules' setup: the
     * handlers read {@code ConfigManager} and the Diana bridge lazily, at the moment a
     * message actually arrives.
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // Order within this method is irrelevant -- Fabric dispatches ALLOW before MODIFY
        // for every message regardless of which was registered first.
        ClientReceiveMessageEvents.ALLOW_GAME.register(ChatRouter::allowGameMessage);
        ClientReceiveMessageEvents.MODIFY_GAME.register(ChatRouter::modifyGameMessage);

        LOGGER.debug("chat hooks registered (ALLOW_GAME for Diana, MODIFY_GAME for level colours)");
    }

    /**
     * @return whether {@link #register()} has already run
     */
    public static boolean registered() {
        return registered;
    }
}
