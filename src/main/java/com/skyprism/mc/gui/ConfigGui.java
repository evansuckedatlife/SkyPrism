package com.skyprism.mc.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The only door into SkyPrism's settings screen, and the reason the mod still loads when
 * YACL is not installed.
 *
 * <p><b>Why a facade instead of a try/catch.</b> YACL is an optional dependency. The
 * failure mode of a missing optional library is not an exception you can catch: the JVM
 * verifies a class when it is first linked, and verification fails with
 * {@code NoClassDefFoundError} <em>before</em> a single instruction of that class's methods
 * runs. Wrapping the call site in a try/catch therefore does nothing if the calling method
 * itself mentions a YACL type. The fix is structural: every {@code dev.isxander} import in
 * the mod lives in exactly one class, {@link SkyPrismConfigScreen}, and callers reach it
 * only through the {@link Screen}-typed methods here. Nothing outside this package can even
 * name the quarantined class -- it is package-private.</p>
 *
 * <p>The one subtlety that makes this work: {@link #open(Screen)} refers to
 * {@code SkyPrismConfigScreen.create}, whose descriptor mentions only {@code Screen}. The
 * JVM resolves that method reference lazily, at the moment the {@code invokestatic}
 * executes -- which, when YACL is absent, never happens because {@link #available()} is
 * false. Verifying this class loads nothing from YACL.</p>
 *
 * <p><b>Degradation contract.</b> With YACL missing, {@link #open(Screen)} returns null.
 * ModMenu greys its config button out for a null factory result, and the keybind and
 * {@code /skyprism gui} paths are expected to say so in chat rather than doing nothing
 * silently -- see {@link #unavailableMessage()}.</p>
 */
public final class ConfigGui {

    /**
     * YACL's own mod id, read out of the metadata in both the {@code +26.1-fabric} and
     * {@code +26.2-fabric} jars. The underscores and the {@code _v3} suffix are real; the
     * Maven coordinate's spelling is different and would silently never match.
     */
    public static final String YACL_MOD_ID = "yet_another_config_lib_v3";

    private static final boolean AVAILABLE = detect();

    private ConfigGui() {
    }

    /**
     * Whether a settings screen can be opened at all.
     *
     * <p>Resolved once at class-init because mods cannot be added mid-session, so re-asking
     * per keypress would be pure overhead.</p>
     *
     * @return true when YACL is present
     */
    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Builds the settings screen.
     *
     * @param parent the screen to return to when the user closes the settings; may be null
     * @return the screen, or null when YACL is not installed. Callers that hand this
     *         straight to {@code Minecraft.setScreenAndShow} must check for null first --
     *         passing null there closes the current screen, which would look like the
     *         keybind had opened and instantly dismissed the menu
     */
    public static Screen open(Screen parent) {
        return AVAILABLE ? SkyPrismConfigScreen.create(parent) : null;
    }

    /**
     * Plain-language text for the "you pressed the settings key and nothing happened" case.
     *
     * <p>Kept here rather than duplicated in the command and keybind modules so both say
     * the same thing, and returned as a String so this class stays free of any dependency
     * on the chat helpers. The wording itself lives in the language file under
     * {@code skyprism.config.yacl_missing}; resolving it here rather than handing callers a
     * key keeps the String-returning contract those callers were written against.</p>
     *
     * @return one sentence naming the missing mod and what installing it buys
     */
    public static String unavailableMessage() {
        return Component.translatable("skyprism.config.yacl_missing").getString();
    }

    private static boolean detect() {
        try {
            return FabricLoader.getInstance().isModLoaded(YACL_MOD_ID);
        } catch (RuntimeException | LinkageError noLoader) {
            // No loader means no YACL either, and this must never be the thing that throws.
            return false;
        }
    }
}
