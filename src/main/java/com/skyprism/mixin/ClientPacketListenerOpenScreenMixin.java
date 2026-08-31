package com.skyprism.mixin;

import com.skyprism.mc.loot.ScreenTitleFeed;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the title of every server-opened container to {@link ScreenTitleFeed}.
 *
 * <h2>Why a mixin, when the rest of the mod uses events</h2>
 * <p>Fabric publishes no container-opened event, and the six GUI-triggered loot sources need the
 * inventory title. The only place that title exists is the packet vanilla is in the middle of
 * handling, so this is the same kind of gap the two level-colour mixins fill.
 *
 * <h2>Why this target, verified rather than remembered</h2>
 * <p>{@code javap -c} against both shipped jars on 2026-08-30 gives the same 31-byte method body,
 * instruction for instruction -- only constant-pool indices differ, which are not part of any
 * descriptor -- so one injection applies on 26.1.2 and 26.2 with no version guard:
 *
 * <pre>
 *   public void handleOpenScreen(ClientboundOpenScreenPacket);
 *      0: ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())
 *     12: MenuScreens.create(packet.getType(), minecraft, packet.getContainerId(), packet.getTitle())
 *     31: return
 * </pre>
 *
 * <p><b>Do not switch this to {@code Minecraft.screen}.</b> That field is public on 26.1.2 and not
 * on 26.2; reading it would force the first Stonecutter conditional into a codebase that has none.
 *
 * <h2>Why {@code TAIL} and not {@code HEAD}</h2>
 * <p>The first thing vanilla does is {@code ensureRunningOnSameThread}, which throws
 * {@code RunningOnDifferentThreadException} on the netty thread to bounce the packet onto the
 * client thread -- so the method body is entered <em>twice</em> and a {@code HEAD} injection would
 * fire once off-thread and once on it, feeding every container to the bus twice. {@code TAIL} sits
 * after that check and after the screen is built, on the client thread, exactly once. There is a
 * single {@code return} in the method, so {@code TAIL} is unambiguous.
 *
 * <h2>Why the body is one line</h2>
 * <p>The house rule in this package: a mixin does the fetch and nothing else. Everything real --
 * the gate that makes an unarmed player pay nothing, the flatten, the strip, and the failure
 * budget that keeps a bug in a detector from taking out the player's ability to open a chest --
 * lives in {@link ScreenTitleFeed}, which is ordinary code the fast compile check and the mcTest
 * suite can both reach. {@link ScreenTitleFeed#dispatch} catches {@link Throwable} itself, so
 * nothing escapes into vanilla's packet handling from here.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerOpenScreenMixin {

    @Inject(method = "handleOpenScreen(Lnet/minecraft/network/protocol/game/ClientboundOpenScreenPacket;)V",
            at = @At("TAIL"), require = 1)
    private void skyprism$feedScreenTitle(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        // getTitle() is a field read, so the armed check that actually costs something stays
        // inside the feed, ahead of the flatten. See ScreenTitleFeed#onScreenOpened.
        ScreenTitleFeed.dispatch(packet.getTitle());
    }
}
