package com.skyprism.mixin;

import com.skyprism.mc.surfaces.LevelNameMemoHolder;
import com.skyprism.mc.surfaces.LevelSurfaces;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Recolours the SkyBlock level prefix on the nametag floating above a player's head.
 *
 * <h2>Why {@code getNameTag} is the only sane target in 26.x</h2>
 * <p>There is no {@code renderNameTag} any more. Nametags go through the render-state
 * pipeline: the renderer reads the component once during extraction and stores it on an
 * {@code EntityRenderState} for the render pass to draw later. Where that read happens moved
 * between the two target versions -- 26.1.2 does it inside {@code extractRenderState}, and
 * 26.2 does it inside the new {@code extractNameTags(T, S, float)} -- so hooking either of
 * those would need a Stonecutter block and would break again at the next refactor.</p>
 *
 * <p>The call being made is identical in both, though. Disassembly of both jars shows the
 * same three instructions, {@code invokevirtual getNameTag} then {@code putfield
 * EntityRenderState.nameTag}, merely relocated. So this injects into the callee:
 * {@code protected Component getNameTag(T)}, whose erased descriptor is
 * {@code (Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;} in
 * 26.1.2 and 26.2 alike. One injection, no version guard, and it survives whatever the next
 * version does to the extraction plumbing.</p>
 *
 * <p>The one thing a base-class injection cannot catch is a subclass that overrides
 * {@code getNameTag} without calling {@code super}. Scanning every one of the 286 renderer
 * classes in both jars with {@code javap} turns up exactly one override --
 * {@code ItemFrameRenderer}, which names a map or a framed item, never a player. Nothing on
 * the player path overrides it, so nothing is missed.</p>
 *
 * <h2>Cost when it does nothing, which is most of the time</h2>
 * <p>The first line is a boolean read. The second is one {@code instanceof}, which rejects
 * every entity that is not a client player and, for the ones that pass, hands back that
 * player's own cache cell. A frame full of named mobs and Hypixel's armour-stand holograms
 * therefore never reaches the scanner. Vanilla has already built the component this hook
 * inspects, so a cache hit adds no allocation of its own.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNameTagMixin {

    @Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)"
            + "Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void skyprism$recolourNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (!LevelSurfaces.nameTagsEnabled()) {
            return;
        }
        // Doubles as the player filter: only AbstractClientPlayer carries the memo interface.
        if (!(entity instanceof LevelNameMemoHolder holder)) {
            return;
        }
        Component nameTag = cir.getReturnValue();
        if (nameTag == null) {
            return;
        }
        Component recoloured = LevelSurfaces.nameTag(holder.skyprism$levelNameMemo(), nameTag);
        if (recoloured != null) {
            cir.setReturnValue(recoloured);
        }
    }
}
