package com.skyprism.mixin;

import com.skyprism.mc.surfaces.LevelNameMemoHolder;
import com.skyprism.mc.surfaces.LevelSurfaces;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Recolours the SkyBlock level prefix in the TAB player list.
 *
 * <h2>Why this target and not {@code PlayerInfo.getTabListDisplayName}</h2>
 * <p>Both would work, and {@code getTabListDisplayName} is the more obvious hook, but it is
 * the wrong one. It is the raw accessor for a stored field, read by anything that wants a
 * player's list name, so rewriting it would leak the recolour into callers that have nothing
 * to do with the TAB overlay and would make the config's {@code applyToTabList} toggle a
 * lie. {@code getNameForDisplay} is the overlay's own method, called from
 * {@code extractRenderState} for exactly the entries being drawn, and it is
 * character-identical in 26.1.2 and 26.2 -- only the constructor of the surrounding class
 * moved from taking a {@code Gui} to taking a {@code Hud}, which no injector touches. So one
 * injection compiles and applies on both Stonecutter nodes with no version guard.</p>
 *
 * <h2>Why {@code RETURN} rather than {@code HEAD}</h2>
 * <p>Vanilla's method does more than fetch the name: it copies the stored component and adds
 * italics for a spectator. Cancelling at {@code HEAD} would silently drop that. Injecting at
 * {@code RETURN} lets vanilla decorate first and recolours the finished article, so the only
 * thing this mod changes is the colour of the digits in the level tag.</p>
 *
 * <h2>What makes it cheap</h2>
 * <p>The very first line is a boolean read, so a user with the feature off pays nothing
 * measurable. Past that, the answer is memoised on the {@code PlayerInfo} itself, keyed on
 * the stored display component's identity plus the configuration generation, and a cache hit
 * returns a reference without allocating. See {@link LevelSurfaces} for the rest.</p>
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    /**
     * Verified against both jars with {@code javap}: the descriptor is
     * {@code (Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;}
     * in 26.1.2 and 26.2 alike, so it is spelled out in full rather than left to name
     * matching.
     */
    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)"
            + "Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void skyprism$recolourTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (!LevelSurfaces.tabListEnabled()) {
            return;
        }
        // Tested rather than cast outright: if PlayerInfoMixin somehow failed to apply, a raw
        // cast would throw a ClassCastException here on every entry of every frame, outside
        // the reach of the failure budget in LevelSurfaces. An instanceof costs the same and
        // degrades to "vanilla colours" instead.
        if (!(info instanceof LevelNameMemoHolder holder)) {
            return;
        }
        // The stored component is the cache key precisely because it keeps its identity until
        // the server sends another player-info packet. When the server never set one, vanilla
        // falls back to the bare profile name, which cannot carry a Hypixel level tag, so
        // there is nothing here worth scanning.
        Component source = info.getTabListDisplayName();
        if (source == null) {
            return;
        }
        Component decorated = cir.getReturnValue();
        if (decorated == null) {
            return;
        }
        Component recoloured = LevelSurfaces.tabDisplayName(
                holder.skyprism$levelNameMemo(), source, info.getGameMode(), decorated);
        if (recoloured != null) {
            cir.setReturnValue(recoloured);
        }
    }
}
