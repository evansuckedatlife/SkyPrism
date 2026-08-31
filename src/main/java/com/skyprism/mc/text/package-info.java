/**
 * The bridge between Minecraft's {@code Component} tree and SkyPrism's Minecraft-free
 * text core.
 *
 * <p>Everything in {@code com.skyprism.core.text} is generic over an opaque style type so
 * that the span-splicing algorithm could be written, and heavily tested, on a bare JVM.
 * This package is the one place that instantiates that generic with
 * {@link net.minecraft.network.chat.Style} and knows how to take a component apart and
 * put it back together again without losing anything Hypixel attached to it.</p>
 *
 * <p>It deliberately contains no rendering, no events, no mixins and no configuration:
 * the chat hook, the TAB memoiser and the nametag mixin all sit on top of
 * {@link com.skyprism.mc.text.ComponentRewriter} and share its behaviour, which is why
 * the recolour rules live here exactly once instead of three times.</p>
 */
package com.skyprism.mc.text;
