package com.skyprism.mc.selftest;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Runs Minecraft's own data-component initialisers, so item sprites can be drawn at the title
 * screen.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>On 26.x an item's data components are not fields on the {@link Item}. They are computed by
 * {@link DataComponentInitializers} against a registry lookup and then <em>bound</em> to the
 * item's registry holder, and constructing an {@code ItemStack} dereferences that binding. On a
 * client the binding arrives with the server's registry sync
 * ({@code net.minecraft.client.multiplayer.RegistryDataCollector}), so before any world or
 * server exists {@code new ItemStack(Items.CHEST)} throws
 * {@code NullPointerException: Components not bound yet}. The title screen, where this self test
 * does all of its work, is exactly "before any world exists", and a photograph of three empty
 * reel windows documents nothing.</p>
 *
 * <p>The constraint is real, and {@code DropSymbols} respects it rather than working around it:
 * it resolves its rows to {@link Item}s and builds each stack lazily, once the components behind
 * it exist. In play they always do by the time a reel is on screen, because the HUD only draws
 * while a world is rendering. This class exists so that the same can be true of a screenshot.</p>
 *
 * <h2>What it does</h2>
 *
 * <p>The vanilla pipeline, in the two calls the client itself makes when a server hands over its
 * registries: build {@link BuiltInRegistries#DATA_COMPONENT_INITIALIZERS} against a lookup, then
 * {@code apply()} each pending batch. Same initialisers, same component maps. Nothing here
 * invents a component value or reaches past the public API into a private field.</p>
 *
 * <p>The lookup it builds against is {@code VanillaRegistries.createLookup()} rather than the
 * built-in registries alone, because the initialisers reach into the data-driven registries too
 * -- an armour trim material, a damage type -- and those are not built in. That is the same
 * three-step sequence {@code ItemRegistryBootstrap} uses to make the {@code mcTest} suite able
 * to construct stacks headlessly, so the mod has one answer to this problem rather than two.</p>
 *
 * <h2>Why it is safe to have in the jar</h2>
 *
 * <p>Nothing in {@code com.skyprism.mc.selftest} is loaded unless {@code -Dskyprism.selftest}
 * is set; see {@link SelfTest}. It runs once, before the script opens a screen, and it returns
 * immediately when the components are already bound -- which is every case a player is ever in.</p>
 */
final class ItemComponents {

    /** Set once the initialisers have been run, so they cannot be run twice. */
    private static boolean attempted;

    private ItemComponents() {
    }

    /**
     * Binds the built-in data components, unless something already has.
     *
     * @return a one-line description of what happened, for the self test summary
     * @throws IllegalStateException if the components are still unbound afterwards, because a run
     *                               that carried on would photograph empty reel windows and call
     *                               it a pass
     */
    static String bindDefaults() {
        if (bound()) {
            return "already bound; nothing to do";
        }
        if (attempted) {
            throw new IllegalStateException("the initialisers have already been run once and the "
                    + "components are still unbound");
        }
        attempted = true;

        // VanillaRegistries, not BuiltInRegistries alone. The initialisers reach into the
        // data-driven registries as well -- an armour trim material, a damage type -- and those
        // are not built in. VanillaRegistries.createLookup() is the datagen entry point that
        // builds exactly the vanilla contents of those registries in memory, which is the same
        // set a client receives from a vanilla server, so the components come out right rather
        // than merely non-null.
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();

        List<DataComponentInitializers.PendingComponents<?>> pending =
                BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup);
        for (DataComponentInitializers.PendingComponents<?> batch : pending) {
            batch.apply();
        }

        if (!bound()) {
            throw new IllegalStateException("ran " + pending.size() + " initialiser batch(es) but "
                    + "item components are still unbound, so every reel would draw an empty "
                    + "window and the captures below would be worthless");
        }
        return "ran " + pending.size() + " vanilla data-component initialiser batch(es) against "
                + "VanillaRegistries.createLookup(); " + BuiltInRegistries.ITEM.size()
                + " items can now be made into stacks, so the reels can draw their sprites "
                + "without a world";
    }

    /**
     * Whether an {@code ItemStack} can be constructed at all.
     *
     * <p>Asked of one arbitrary item rather than of all of them: the initialisers bind a whole
     * registry in a single pass, so any item's answer is every item's answer, and a chest is the
     * item {@code DropSymbols} falls back to in any case.</p>
     */
    private static boolean bound() {
        return Items.CHEST.builtInRegistryHolder().areComponentsBound();
    }
}
