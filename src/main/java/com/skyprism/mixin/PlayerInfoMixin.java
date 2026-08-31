package com.skyprism.mixin;

import com.skyprism.mc.surfaces.LevelNameMemo;
import com.skyprism.mc.surfaces.LevelNameMemoHolder;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every TAB-list entry somewhere to keep its recoloured name.
 *
 * <p>This mixin injects no behaviour at all: it only widens {@code PlayerInfo} with one
 * field and the accessor {@link LevelNameMemoHolder} declares, so
 * {@link PlayerTabOverlayMixin} can reach a per-player cache with a cast instead of a hash
 * lookup. {@code PlayerTabOverlay.getNameForDisplay} is called once per listed player per
 * frame -- eighty times a frame on a full Hypixel lobby -- and probing a shared map that
 * often, on a path forbidden to allocate, is precisely what this avoids.</p>
 *
 * <p>Attaching the cache to {@code PlayerInfo} also solves invalidation for free. The client
 * drops a {@code PlayerInfo} when the player leaves and builds a new one when they return,
 * so a stale entry cannot outlive the thing it describes and nothing ever has to sweep.</p>
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin implements LevelNameMemoHolder {

    /**
     * Created on first use rather than in a field initialiser: a mixin field initialiser has
     * to be merged into the target's constructors, and there is no reason to pay an
     * allocation for the many {@code PlayerInfo} objects that are never drawn in TAB.
     */
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
