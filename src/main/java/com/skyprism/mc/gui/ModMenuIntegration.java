package com.skyprism.mc.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/**
 * Wires SkyPrism's config button into ModMenu's mod list.
 *
 * <p>Registered under the {@code "modmenu"} entrypoint key in {@code fabric.mod.json}.
 * Fabric Loader only instantiates an entrypoint whose owning mod is present, so this class
 * is never loaded when ModMenu is absent and ModMenu needs no {@code depends} entry.</p>
 *
 * <p><b>ModMenu present, YACL absent is a real setup</b> -- they are separate downloads --
 * so the factory is chosen by asking {@link ConfigGui#available()} rather than assuming.
 * This class mentions no YACL type anywhere, only {@link Screen}, so it verifies cleanly on
 * a machine with no YACL jar at all. A factory returning null is ModMenu's documented way of
 * saying "this mod has no config screen", and it greys the button out rather than throwing
 * when clicked.</p>
 */
public final class ModMenuIntegration implements ModMenuApi {

    /** Public no-arg constructor: Fabric Loader instantiates entrypoints reflectively. */
    public ModMenuIntegration() {
    }

    /**
     * The screen factory ModMenu's config button calls.
     *
     * @return a factory that builds the YACL screen, or one that yields null when YACL is
     *         missing so the button is disabled instead of dead
     */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Typed explicitly rather than returned as a bare lambda against the wildcard: the
        // method reference below has to resolve against ConfigScreenFactory<Screen>, and
        // spelling that out costs one line and removes all doubt about inference.
        if (!ConfigGui.available()) {
            ConfigScreenFactory<Screen> none = parent -> null;
            return none;
        }
        ConfigScreenFactory<Screen> factory = ConfigGui::open;
        return factory;
    }
}
