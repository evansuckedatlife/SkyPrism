/**
 * The settings screen and its ModMenu button.
 *
 * <p>YACL is an <em>optional</em> dependency, and this package is shaped around that. A
 * missing optional library fails at class verification rather than at a call, so every
 * {@code dev.isxander} import in SkyPrism is confined to the package-private
 * {@code SkyPrismConfigScreen}; the rest of the mod goes through
 * {@link com.skyprism.mc.gui.ConfigGui}, whose whole surface is typed in terms of
 * {@link net.minecraft.client.gui.screens.Screen}. Without YACL the mod loads and runs in
 * full, and only the GUI is missing.</p>
 */
package com.skyprism.mc.gui;
