package com.skyprism.mc.surfaces;

/**
 * Implemented by Mixin on the objects that own a cached level recolour, so the render hooks
 * can reach a per-entry cache with one {@code instanceof} and no map lookup.
 *
 * <p>Two classes get it: {@code net.minecraft.client.multiplayer.PlayerInfo} for the TAB
 * list, and {@code net.minecraft.client.player.AbstractClientPlayer} for nametags. Both
 * live exactly as long as the thing they cache for, so the cache is collected with the
 * player and never needs sweeping.</p>
 *
 * <p>On the nametag path the {@code instanceof} against this interface does double duty:
 * it fetches the cache <em>and</em> narrows the hook to client player entities, so a world
 * full of named mobs and armour-stand holograms is never scanned for level tags at all.
 * That is the single biggest reason the nametag hook costs nothing on a normal frame.</p>
 *
 * <p>The {@code skyprism$} prefix is the Mixin convention for a member injected into a
 * class the mod does not own, and exists to guarantee no collision with a vanilla or
 * third-party member of the same name.</p>
 */
public interface LevelNameMemoHolder {

    /**
     * The cache cell for this object, created on first use.
     *
     * @return this object's memo; never null, and the same instance on every call
     */
    LevelNameMemo skyprism$levelNameMemo();
}
