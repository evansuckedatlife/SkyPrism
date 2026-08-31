/**
 * The Minecraft-side feeds that reach the loot bus, for the triggers Fabric publishes no event
 * for.
 *
 * <p>Chat already has a route -- {@code com.skyprism.mc.chat} and {@code DianaController} both
 * hand lines to {@code LootMachine.onChat}. A screen opening does not: Fabric has no
 * container-opened event, so the only place the title exists is the {@code ClientboundOpenScreenPacket}
 * vanilla is in the middle of handling. {@link com.skyprism.mc.loot.ScreenTitleFeed} is the far
 * side of the one mixin that reaches it, and holds everything that mixin would otherwise have to
 * do itself -- the gate, the flatten, the strip and the failure budget -- so the mixin stays a
 * single call, as the rules in {@code com.skyprism.mixin}'s own package documentation require.
 */
package com.skyprism.mc.loot;
