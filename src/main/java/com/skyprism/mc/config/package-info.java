/**
 * Minecraft-side configuration lifecycle: where the settings file lives, when it is read
 * and written, how a settings change becomes the derived objects the renderers use, and
 * how that change is broadcast to the caches that must drop their contents.
 *
 * <p>The settings themselves, their validation, their migrations and their on-disk format
 * all live in {@code com.skyprism.core.config} and are covered by the core test suite.
 * Nothing here re-implements any of that.</p>
 */
package com.skyprism.mc.config;
