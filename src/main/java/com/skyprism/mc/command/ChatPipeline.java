package com.skyprism.mc.command;

import com.skyprism.core.util.TextClean;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

/**
 * Pushes a synthetic chat line through the mod's real chat handling.
 *
 * <p><b>Why this exists.</b> {@code /skyprism replay} has to answer "what would SkyPrism do
 * with this captured log line?" The only trustworthy answer comes from the code that
 * actually handles chat - the level recolourer on {@code MODIFY_GAME}, the loot parser on
 * {@code GAME}, the drop suppressor on {@code ALLOW_GAME}. Re-implementing that dispatch
 * here would produce a test harness that could pass while the real thing was broken.</p>
 *
 * <p>So instead of naming any SkyPrism handler, this class invokes Fabric's own event
 * chain, in Fabric's own order, through {@code Event.invoker()}. Whatever is registered
 * runs, including handlers added after this class was written, and including ones belonging
 * to other mods.</p>
 *
 * <p><b>The one caveat, stated plainly:</b> other mods' listeners run too. That is
 * unavoidable if the point is fidelity - a replay that skipped them would not be a replay -
 * and it is why {@code /skyprism replay} is documented as a debugging tool rather than a
 * general chat injector. Nothing here reaches the network in either direction; the message
 * is a client-side {@link Component} that never existed as a packet.</p>
 */
public final class ChatPipeline {

    private ChatPipeline() {
    }

    /**
     * The outcome of pushing one line through, so the caller can report what happened
     * rather than just that something did.
     *
     * @param delivered whether the message survived {@code ALLOW_GAME}
     * @param result    the message after {@code MODIFY_GAME}, or the original when it was
     *                  cancelled
     * @param changed   whether {@code MODIFY_GAME} returned a different component, which is
     *                  the visible sign the level recolourer engaged
     */
    public record Outcome(boolean delivered, Component result, boolean changed) {
    }

    /**
     * Runs one message through allow, then modify, then observe.
     *
     * <p>The order mirrors Fabric's {@code ChatListenerMixin} exactly: a listener that
     * returns false from {@code ALLOW_GAME} suppresses the message, {@code GAME_CANCELED}
     * fires instead of {@code GAME}, and {@code MODIFY_GAME} only sees messages that
     * survived. Getting that order wrong would make the replay disagree with live chat in
     * precisely the case - a suppressed Diana drop line - that it exists to test.</p>
     *
     * @param message the synthetic message, formatting codes and all
     * @return what the pipeline did with it
     */
    public static Outcome push(Component message) {
        boolean allowed = ClientReceiveMessageEvents.ALLOW_GAME.invoker()
                .allowReceiveGameMessage(message, false);
        if (!allowed) {
            ClientReceiveMessageEvents.GAME_CANCELED.invoker()
                    .onReceiveGameMessageCanceled(message, false);
            return new Outcome(false, message, false);
        }

        Component modified = ClientReceiveMessageEvents.MODIFY_GAME.invoker()
                .modifyReceivedGameMessage(message, false);
        if (modified == null) {
            modified = message;
        }
        ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(modified, false);
        return new Outcome(true, modified, modified != message);
    }

    /**
     * Turns a line of a capture file into the raw string Hypixel would have sent.
     *
     * <p>Captured logs reach us in three shapes and all three have to work, because the
     * player pasting a log should not have to know which one their tool produced:</p>
     *
     * <ul>
     *   <li>a literal section sign, which survives a UTF-8 capture;</li>
     *   <li>{@code &} before a formatting character, the convention every Minecraft config
     *       file in existence uses, and the only one that survives a copy-paste through an
     *       editor that mangles high bytes;</li>
     *   <li>the six-character text {@code \}{@code u00A7}, which is what a Java or JSON log
     *       dump produces.</li>
     * </ul>
     *
     * <p>A doubled {@code &&} escapes a literal ampersand, so a message that genuinely
     * contains "Tom &amp; Jerry" is still expressible.</p>
     *
     * @param line one line from the capture file
     * @return the line with every escape resolved to a section sign
     */
    public static String unescape(String line) {
        if (line == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);

            if (c == '&' && i + 1 < n) {
                char next = line.charAt(i + 1);
                if (next == '&') {
                    out.append('&');
                    i += 2;
                    continue;
                }
                if (isFormatCode(next)) {
                    out.append(TextClean.SECTION).append(next);
                    i += 2;
                    continue;
                }
            }

            if (c == '\\' && i + 5 < n
                    && (line.charAt(i + 1) == 'u' || line.charAt(i + 1) == 'U')
                    && line.regionMatches(true, i + 2, "00a7", 0, 4)) {
                out.append(TextClean.SECTION);
                i += 6;
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The characters Minecraft accepts after a section sign. */
    private static boolean isFormatCode(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O')
                || c == 'r' || c == 'R'
                || c == 'x' || c == 'X';
    }
}
