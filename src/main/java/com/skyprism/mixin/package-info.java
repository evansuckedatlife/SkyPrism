/**
 * Every Mixin SkyPrism applies, and nothing else.
 *
 * <p>There are five, and they exist for one reason: Fabric publishes no event for the TAB
 * player list, none for the nametags above players' heads and none for a container opening,
 * so those surfaces cannot be reached any other way. Chat, which does have an event, is
 * deliberately not here.</p>
 *
 * <h2>What each one does</h2>
 * <ul>
 *   <li>{@link com.skyprism.mixin.ClientPacketListenerOpenScreenMixin} -- hands the title of
 *       every server-opened container to {@code com.skyprism.mc.loot.ScreenTitleFeed}, at the
 *       tail of {@code ClientPacketListener.handleOpenScreen}. The only source of that title
 *       that does not read {@code Minecraft.screen}, whose visibility differs between the two
 *       supported versions.</li>
 *   <li>{@link com.skyprism.mixin.PlayerTabOverlayMixin} -- recolours a TAB entry at the
 *       return of {@code PlayerTabOverlay.getNameForDisplay}.</li>
 *   <li>{@link com.skyprism.mixin.EntityRendererNameTagMixin} -- recolours a floating
 *       nametag at the return of {@code EntityRenderer.getNameTag}, the one point on the
 *       nametag path whose signature is identical on 26.1.2 and 26.2.</li>
 *   <li>{@link com.skyprism.mixin.PlayerInfoMixin} and
 *       {@link com.skyprism.mixin.AbstractClientPlayerMixin} -- inject no behaviour at all.
 *       They only widen their targets with a cache field and the
 *       {@code LevelNameMemoHolder} accessor, so the two hooks above can memoise per entry
 *       without a hash lookup on a path that runs a hundred-odd times a frame.</li>
 * </ul>
 *
 * <h2>House rules for anything added here later</h2>
 * <p>Keep the classes thin. Everything a mixin does beyond checking a flag, fetching a cache
 * cell and setting a return value belongs in {@code com.skyprism.mc.surfaces}, which is
 * ordinary code that can be read and compiled without a Mixin annotation processor -- and
 * the fast compile check deliberately runs without one, so logic left in here is logic
 * nothing checks until the Gradle build.</p>
 *
 * <p>Prefix every added member with {@code skyprism$}, spell {@code method} out with its full
 * descriptor after verifying it with {@code javap} against <em>both</em> jars, and register
 * the class in the {@code client} array of {@code skyprism.mixins.json}. A hook that runs per
 * frame must fail safe: catch, log once, and fall back to vanilla rather than take out the
 * screen it draws on.</p>
 */
package com.skyprism.mixin;
