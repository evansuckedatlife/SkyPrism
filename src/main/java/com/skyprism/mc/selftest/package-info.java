/**
 * The in-client self test: a scripted tour of every SkyPrism screen, photographed.
 *
 * <h2>Why this package exists</h2>
 *
 * <p>SkyPrism's four mixins all <em>apply</em> on both Minecraft nodes and its 604 core tests
 * all pass, but until this package was written no mixin body had ever executed and the slot
 * machine had never drawn a frame. Everything visual was inferred from code review. The real
 * target -- Hypixel during a Diana mayor term -- cannot be reached on demand, so the only way
 * to actually look at the mod was to build something that drives it without a server.</p>
 *
 * <p>That is all this is: a tick-driven script that opens each screen in turn, waits for it to
 * lay out, and asks Minecraft's own screenshot facility for a PNG. It proves nothing about
 * Hypixel. It proves the screens draw, the palette animates, the widget renders and the
 * rewriter preserves a component tree -- which is four more things than were known before.</p>
 *
 * <h2>Why it is safe to ship</h2>
 *
 * <p>Nothing in this package is reachable unless {@code -Dskyprism.selftest=true} was passed on
 * the command line. {@link com.skyprism.SkyPrismClient} evaluates that property once, and the
 * call into {@link com.skyprism.mc.selftest.SelfTest} sits inside the {@code if}. A player who
 * did not set the property never loads a class from here, never registers a listener and never
 * allocates a byte on its behalf; the classes are dead weight in the jar and nothing more.</p>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It never writes the configuration file. It stages the settings it needs in memory through
 * {@code ConfigManager.refresh()}, which republishes the derived palette and locator without
 * touching disk, so a developer running the self test does not come back to a rewritten
 * {@code config.json}. And it does not create a world: see
 * {@link com.skyprism.mc.selftest.SelfTest} for why that step is reported as skipped rather
 * than attempted.</p>
 */
package com.skyprism.mc.selftest;
