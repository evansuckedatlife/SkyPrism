package com.skyprism.mixin;

import com.skyprism.mc.surfaces.LevelNameMemo;
import com.skyprism.mc.surfaces.LevelNameMemoHolder;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every client-side player somewhere to keep its recoloured nametag.
 *
 * <p>The target is deliberately {@code AbstractClientPlayer} and not {@code Entity}. Two
 * things follow from that, and both are the point.</p>
 *
 * <p>First, the cache lands only on the objects that can actually have a SkyBlock level
 * prefix above their heads, so nothing is attached to the thousands of dropped items,
 * projectiles and mobs a world contains.</p>
 *
 * <p>Second, and more useful, the {@code instanceof LevelNameMemoHolder} that
 * {@link EntityRendererNameTagMixin} performs to fetch the cache doubles as the filter that
 * narrows the nametag hook to players. A named mob or an armour-stand hologram -- and
 * Hypixel builds a great many of both -- fails that one type check and is never scanned for
 * a level tag at all. That is what keeps a hook on a per-entity render method from becoming
 * the world sweep the brief forbids.</p>
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin implements LevelNameMemoHolder {

    /** Created on first use; most players in a world are never close enough to be drawn. */
    @Unique
    private LevelNameMemo skyprism$levelNameMemo;

    @Override
    public LevelNameMemo skyprism$levelNameMemo() {
        LevelNameMemo memo = this.skyprism$levelNameMemo;
        if (memo == null) {
            memo = new LevelNameMemo();
            this.skyprism$levelNameMemo = memo;
        }
        return memo;
    }
}
