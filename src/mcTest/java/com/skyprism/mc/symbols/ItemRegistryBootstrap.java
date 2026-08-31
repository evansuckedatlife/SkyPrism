package com.skyprism.mc.symbols;

import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;

/**
 * Brings Minecraft's item registry up far enough for a headless JUnit run.
 *
 * <p>{@link com.skyprism.mc.symbols.DropSymbols} is the one adapter in the mod that asks the
 * registry a question, and the only way to test it honestly is to ask the real registry --
 * a stub would happily agree that {@code minecraft:turtle_shell} exists, which is exactly the
 * mistake the test is there to catch. So this does the three steps a client does before its
 * first item exists, in the order Minecraft itself does them:
 *
 * <ol>
 *   <li>{@code SharedConstants.tryDetectVersion()} -- {@code Bootstrap} refuses to run
 *       without a known game version.</li>
 *   <li>{@code Bootstrap.bootStrap()} -- runs every registry's class initialiser, which is
 *       what actually puts {@code minecraft:flint} into {@code BuiltInRegistries.ITEM}.</li>
 *   <li>Binding data components. After step 2 the registry knows every id, but each holder's
 *       component map is still unbound, and {@code new ItemStack(item)} reads it -- without
 *       this step every stack construction dies on "Components not bound yet".</li>
 * </ol>
 *
 * <p>Idempotent and quiet: another test class may already have run it, and the point is to
 * be able to call it from {@code @BeforeAll} without coordinating.
 *
 * <p>Public rather than package-private because {@code com.skyprism.mc.hud} needs the same
 * three steps to check that every name on the reel strip has a drawable sprite, and two copies
 * of this sequence would be two things to keep in step with Minecraft.
 */
public final class ItemRegistryBootstrap {

    private ItemRegistryBootstrap() {
    }

    private static boolean done;

    public static synchronized void ensure() {
        if (done) {
            return;
        }
        done = true;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (DataComponentInitializers.PendingComponents<?> pending
                : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())) {
            pending.apply();
        }
    }
}
