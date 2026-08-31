package com.skyprism.mc.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The cheap reject in front of the Diana regexes, exercised through the entry point the chat
 * hook actually calls.
 *
 * <p>{@code mightMatterToDiana} is pure string work, but {@link ChatRouter}'s static
 * initialiser pulls in {@code ConfigManager}, {@code Metrics} and the chat hooks, so it
 * cannot be reached from the Minecraft-free suite. It is worth a test anyway: it runs on
 * every chat line the client receives, and its two failure modes are opposite and both
 * silent. A false negative means a real drop never reaches the slot machine and nobody sees
 * an error; a false positive means four anchored regexes run on ordinary lobby chatter
 * forever.
 *
 * <p>These cases are concrete lines, and the rejections are the half worth having: nothing
 * else pins down that ordinary Hypixel chatter costs four {@code indexOf} calls and stops.
 * The complementary direction -- that no <em>pattern</em> in the core has been left without
 * a marker -- is {@code DianaMarkerContractMcTest}, which reaches
 * {@code DianaLineFilter} directly. This one deliberately goes through {@code ChatRouter},
 * so the delegation the hooks depend on is covered as well as the predicate.</p>
 *
 * <p>Grown from the ad-hoc {@code chatprobe/MarkerProbe} main(). That probe also had a bug
 * this rewrite fixes: its rejection helper stringified its argument before checking it for
 * null, so the {@code null} case died with a NullPointerException inside the probe rather
 * than exercising the guard it was written to check.</p>
 */
@DisplayName("ChatRouter.mightMatterToDiana")
final class ChatRouterMarkerMcTest {

    @ParameterizedTest(name = "accepts: {0}")
    @DisplayName("accepts every line a Diana pattern could match")
    @ValueSource(strings = {
        "RARE DROP! You dug out a Griffin Feather!",
        "Wow! You dug out 2,500 coins!",
        "Oh! You dug out a Minos Inquisitor!",
        "You dug out a Griffin Burrow! (3/4)",
        "You finished the Griffin burrow chain! (4/4)",
        "VERY RARE DROP!  Crown of Greed",
        "PET DROP! Griffin",
        "Party > Steve: A MINOS INQUISITOR has spawned near [Howling Cave] at Coords 1 2 3",
        // The raw, still-coded form, which is what /skyprism replay feeds in.
        "§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!",
    })
    void accepts(String line) {
        assertTrue(ChatRouter.mightMatterToDiana(line));
    }

    @ParameterizedTest(name = "rejects: {0}")
    @DisplayName("rejects ordinary Hypixel chatter, which must cost nothing")
    @EmptySource
    @ValueSource(strings = {
        "Steve: hey anyone selling a griffin",
        "You are now in a party with Alex.",
        "[451] Steve joined the lobby",
        "Your profile was changed to: Apple",
        "hi",
    })
    void rejects(String line) {
        assertFalse(ChatRouter.mightMatterToDiana(line));
    }

    @Test
    @DisplayName("null is rejected rather than thrown on")
    void rejectsNull() {
        assertFalse(ChatRouter.mightMatterToDiana(null));
    }
}
