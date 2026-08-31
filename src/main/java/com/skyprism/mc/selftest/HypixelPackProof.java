package com.skyprism.mc.selftest;

import com.skyprism.mc.symbols.DropSymbols;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proves the reel can wear Hypixel's own item art, against Hypixel's own resource pack.
 *
 * <h2>What was actually broken</h2>
 *
 * <p>SkyBlock now ships an official server resource pack. Its 1092 item definitions are addressed
 * by the vanilla {@code minecraft:item_model} component: Hypixel puts
 * {@code hypixel_skyblock:item/community_center/mayor/diana/daedalus_blade} on the stack, and the
 * pack supplies {@code assets/hypixel_skyblock/items/item/.../daedalus_blade.json} pointing at a
 * model and a texture. The reel used to draw a stack it had <em>synthesised</em> from a chat name
 * -- {@code Items.STICK} for "Daedalus Stick" -- and a synthesised stack carries no
 * {@code item_model}, so it could never be dressed by the pack no matter what the pack contained.
 * The same item in the player's own inventory showed the real art. That mismatch is the bug.</p>
 *
 * <h2>What this class demonstrates, and what it deliberately does not</h2>
 *
 * <p>It demonstrates the <em>render</em> half: that a stack carrying an {@code item_model} which
 * exists in the loaded pack draws Hypixel's art through the shipped render path, and that the
 * synthesised fallback next to it still draws plain vanilla, so the difference is visible rather
 * than asserted.</p>
 *
 * <p>It does <strong>not</strong> demonstrate the capture half. There is no Hypixel server in a
 * dev client, so the stacks below are <em>constructed</em> here from ids read out of the pack --
 * they are not captured off a live inventory. What they exercise is the same
 * {@link DropSymbols#learnFrom} entry point a real capture calls, so everything downstream of the
 * capture is genuine: the learned row, the bounded memory, the lazily built stack, the resolution
 * order and the sprite. Only the arrival of the stack is staged. Every summary line this class
 * writes says so in as many words, because a screenshot that quietly implies a live capture would
 * be worse than no screenshot at all.</p>
 *
 * <h2>Why the ids are not invented</h2>
 *
 * <p>A model id that is not in the pack renders as the missing-texture cube, which photographs as
 * a confident-looking failure. Every id below was enumerated from the pack zip, and
 * {@link #packReport()} re-checks each one against the client's own {@link ResourceManager} at run
 * time -- the item definition, the model and the texture -- so a run against a pack that is absent,
 * rejected for its format or simply different reports that instead of photographing cubes.</p>
 *
 * <h2>Where the base items come from</h2>
 *
 * <p>Each row's base item is the vanilla item the pack's own model file gives as its
 * {@code parent}: {@code daedalus_blade}'s model is {@code {"parent":"item/iron_sword", ...}}, so
 * the base here is {@code minecraft:iron_sword}. That is a guess at what Hypixel sends, and it is
 * only a guess -- but it is a guess taken from Hypixel's file rather than from taste, and the base
 * item is in any case not what decides the sprite. The {@code item_model} component is.</p>
 *
 * <h2>Loading</h2>
 *
 * <p>Nothing here builds an {@link ItemStack} at class-initialisation time. Item components are
 * bound late on a client, and a stack built before they are throws
 * {@code NullPointerException: Components not bound yet} -- which once poisoned a whole class with
 * an {@code ExceptionInInitializerError} and made the slot machine draw nothing at all. The rows
 * below are strings; stacks are built inside methods the script calls after
 * {@link ItemComponents#bindDefaults()}.</p>
 */
final class HypixelPackProof {

    private HypixelPackProof() {
    }

    /** The namespace Hypixel's pack publishes under. */
    static final String NAMESPACE = "hypixel_skyblock";

    /**
     * One demonstration row.
     *
     * @param dropName the display name a chat line would carry, and the key the reel looks up
     * @param baseItem the vanilla item id the stack is built on, taken from the pack model parent
     * @param modelId  the {@code item_model} value, verbatim from the pack
     * @param onStrip  whether this name is one of the ten the reel's filler strip shows
     */
    record Row(String dropName, String baseItem, String modelId, boolean onStrip) {
    }

    /**
     * The rows, every id verified present in the pack before it was written down here.
     *
     * <p>Ordered so the reel demonstration takes the first three, which is why the row that is
     * genuinely on the filler strip comes first: it is the only one of the four whose name the
     * mod would have looked up on a real spin whether or not this class existed.</p>
     */
    private static final List<Row> ROWS = List.of(
            // On the filler strip, and dressed by the pack -- the one row that is end-to-end real.
            // Pack model: {"parent":"item/paper", ...}
            new Row("Control Switch", "minecraft:paper",
                    NAMESPACE + ":item/uncategorized/control_switch", true),
            // The id the brief named. Pack model: {"parent":"item/iron_sword", ...}
            new Row("Daedalus Blade", "minecraft:iron_sword",
                    NAMESPACE + ":item/community_center/mayor/diana/daedalus_blade", false),
            // Pack model: {"parent":"item/wooden_shovel", ...}
            new Row("Ancestral Spade", "minecraft:wooden_shovel",
                    NAMESPACE + ":item/community_center/mayor/diana/ancestral_spade", false),
            // Pack model: {"parent":"item/wooden_sword", ...}
            new Row("Sword of Revelations", "minecraft:wooden_sword",
                    NAMESPACE + ":item/community_center/mayor/diana/sword_of_revelations", false));

    /**
     * A name the pack has no art for, kept as the control.
     *
     * <p>"Daedalus Stick" is the example the brief opens with and it is a real Diana drop, but this
     * pack build carries no {@code daedalus_stick} anywhere -- the only Daedalus assets in it are
     * for the blade. Teaching this name the blade's model to make a prettier screenshot would be
     * inventing a mapping Hypixel does not publish, so it is left alone and photographed exactly as
     * it is: the synthesised vanilla stick, which is the correct answer for it today.</p>
     */
    static final String CONTROL_NAME = "Daedalus Stick";

    /** The rows, in the order the reel demonstration consumes them. */
    static List<Row> rows() {
        return ROWS;
    }

    /** The first {@code count} row names, for the drops a demonstration roll is built from. */
    static List<String> reelNames(int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < Math.min(count, ROWS.size()); i++) {
            names.add(ROWS.get(i).dropName());
        }
        return names;
    }

    // ------------------------------------------------------------------ the pack itself

    /**
     * What the client's own resource manager can see, checked asset by asset.
     *
     * <p>This is the step that decides whether any of the pictures below mean anything. It reports
     * the selected pack ids, the namespaces the resource manager knows about, and for every row
     * whether the item definition, the model and the texture are all reachable. A run where the
     * pack was rejected for its {@code pack_format} looks exactly like a run where it was loaded,
     * right up until this line says the namespace is absent.</p>
     *
     * @return a multi-line report for the summary
     * @throws IllegalStateException when the pack is not loaded, so the run says "the pack is not
     *                               here" rather than photographing missing-texture cubes and
     *                               calling it a pass
     */
    static String packReport() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            throw new IllegalStateException("no client");
        }

        StringBuilder out = new StringBuilder(1024);

        PackRepository repo = client.getResourcePackRepository();
        out.append("selected resource packs: ").append(repo.getSelectedIds()).append('\n');
        out.append("available resource packs: ").append(repo.getAvailableIds()).append('\n');

        ResourceManager resources = client.getResourceManager();
        boolean namespacePresent = resources.getNamespaces().contains(NAMESPACE);
        out.append("resource namespaces include '").append(NAMESPACE).append("': ")
                .append(namespacePresent).append('\n');

        if (!namespacePresent) {
            out.append("Hypixel's pack is NOT loaded. Every item_model below would resolve to the "
                    + "missing model, so the captures after this point would show untextured cubes "
                    + "rather than Hypixel art. Check the pack_format: this build of the pack "
                    + "declares 84 (min 84, max 84), which is Minecraft 26.1.2's resource format. "
                    + "26.2 is resource format 88 and will reject it as incompatible.");
            throw new IllegalStateException(out.toString());
        }

        int missing = 0;
        for (Row row : ROWS) {
            Identifier model = Identifier.parse(row.modelId());
            Identifier definition = Identifier.fromNamespaceAndPath(
                    model.getNamespace(), "items/" + model.getPath() + ".json");
            Identifier modelFile = Identifier.fromNamespaceAndPath(
                    model.getNamespace(), "models/" + model.getPath() + ".json");
            Identifier texture = Identifier.fromNamespaceAndPath(
                    model.getNamespace(), "textures/" + model.getPath() + ".png");

            boolean hasDefinition = resources.getResource(definition).isPresent();
            boolean hasModel = resources.getResource(modelFile).isPresent();
            boolean hasTexture = resources.getResource(texture).isPresent();
            boolean baked = bakedModelResolved(client.getModelManager(), model);

            if (!(hasDefinition && hasModel && hasTexture && baked)) {
                missing++;
            }
            out.append("  ").append(row.dropName()).append(" -> ").append(row.modelId())
                    .append("  definition=").append(hasDefinition)
                    .append(" model=").append(hasModel)
                    .append(" texture=").append(hasTexture)
                    .append(" baked=").append(baked)
                    .append('\n');
        }

        if (missing > 0) {
            throw new IllegalStateException(out + "\n" + missing + " of " + ROWS.size()
                    + " demonstration ids are not fully present in the loaded pack; those cells "
                    + "would photograph as missing-texture cubes");
        }
        out.append("all ").append(ROWS.size())
                .append(" demonstration ids resolve to a real definition, model, texture and baked "
                        + "item model in the loaded pack");
        return out.toString();
    }

    /**
     * Whether the model manager baked a real model for this id, rather than handing back the
     * missing one.
     *
     * <p>Asked by comparing against the model for an id nothing can possibly have supplied: the
     * manager answers every unknown id with the same missing-model instance, so identity with that
     * instance is exactly the question "did this id resolve". That is a sturdier test than
     * inspecting the model's own type, which changes shape between versions.</p>
     */
    private static boolean bakedModelResolved(ModelManager models, Identifier id) {
        try {
            ItemModel missing = models.getItemModel(Identifier.fromNamespaceAndPath(
                    NAMESPACE, "item/skyprism_selftest_this_id_cannot_exist"));
            return models.getItemModel(id) != missing;
        } catch (RuntimeException | LinkageError unanswerable) {
            return false;
        }
    }

    // ------------------------------------------------------------------ before and after

    /**
     * Asserts every demonstration name is still on the synthesised fallback.
     *
     * <p>The before/after pair is only worth photographing if "before" really is before. A learned
     * row persists to {@code drop_item_models.json} in the run directory, so a second run in the
     * same directory would start already-taught and the pair would be two identical pictures with a
     * caption claiming otherwise. This turns that into a failure with a reason.</p>
     *
     * @return the evidence line for the summary
     */
    static String requireUntaught() {
        StringBuilder out = new StringBuilder(256);
        List<String> wrong = new ArrayList<>();
        for (Row row : ROWS) {
            DropSymbols.SymbolSource source = DropSymbols.sourceFor(row.dropName());
            out.append("  ").append(row.dropName()).append(": ").append(source).append('\n');
            if (source != DropSymbols.SymbolSource.FALLBACK) {
                wrong.add(row.dropName() + " is already " + source);
            }
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("the 'before' frame would not be a before frame: "
                    + wrong + ". A previous run left rows in " + memoryPath() + "; delete it and "
                    + "run again.\n" + out);
        }
        return "every demonstration name resolves to the synthesised vanilla fallback, which is "
                + "what the reel drew before this change:\n" + out
                + "  (" + CONTROL_NAME + ", the control, is "
                + DropSymbols.sourceFor(CONTROL_NAME) + " and stays that way)";
    }

    /**
     * Teaches each row through the same entry point a live capture uses.
     *
     * <p>{@link DropSymbols#learnFrom} is what {@code IconCapture} calls when it matches a stack in
     * the player's inventory to a drop the chat pipeline named. Handing it a constructed stack here
     * exercises everything past that call -- the {@code item_model} read, the base-item read, the
     * bounded memory write, the learned tier -- with only the stack's provenance staged.</p>
     *
     * @return the evidence line for the summary
     */
    static String teach() {
        StringBuilder out = new StringBuilder(512);
        for (Row row : ROWS) {
            ItemStack staged = stageStack(row);
            DropSymbols.learnFrom(row.dropName(), staged);
            out.append("  learnFrom(\"").append(row.dropName()).append("\", ")
                    .append(row.baseItem()).append(" + item_model=").append(row.modelId())
                    .append(")\n");
        }
        return "handed " + ROWS.size() + " CONSTRUCTED stacks to DropSymbols.learnFrom -- the same "
                + "call IconCapture makes for a stack matched in the player's inventory. These were "
                + "built here, not captured from Hypixel; a dev client has no SkyBlock server to "
                + "capture from. What is exercised is everything downstream of the capture.\n" + out;
    }

    /**
     * Asserts the learning took, and that the stack the reel will draw carries the exact id.
     *
     * @return the evidence line for the summary
     */
    static String requireTaught() {
        StringBuilder out = new StringBuilder(512);
        List<String> wrong = new ArrayList<>();
        for (Row row : ROWS) {
            DropSymbols.SymbolSource source = DropSymbols.sourceFor(row.dropName());
            ItemStack drawn = DropSymbols.iconForName(row.dropName());
            Identifier onStack = drawn.isEmpty() ? null : drawn.get(DataComponents.ITEM_MODEL);
            String itemId = drawn.isEmpty()
                    ? "<empty>" : BuiltInRegistries.ITEM.getKey(drawn.getItem()).toString();

            out.append("  ").append(row.dropName()).append(": ").append(source)
                    .append(", stack=").append(itemId)
                    .append(", item_model=").append(onStack).append('\n');

            if (source != DropSymbols.SymbolSource.REAL
                    && source != DropSymbols.SymbolSource.LEARNED) {
                wrong.add(row.dropName() + " resolved from " + source);
            } else if (onStack == null || !row.modelId().equals(onStack.toString())) {
                wrong.add(row.dropName() + " draws a stack whose item_model is " + onStack
                        + ", not " + row.modelId());
            }
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("the taught rows did not come back: " + wrong + "\n"
                    + out);
        }
        return "every demonstration name now resolves to a stack carrying Hypixel's own "
                + "item_model, so graphics.item() renders it through the pack:\n" + out
                + "  (" + CONTROL_NAME + ", the control, is still "
                + DropSymbols.sourceFor(CONTROL_NAME)
                + " -- this pack build has no art for it, and none was invented)";
    }

    // ------------------------------------------------------------------ stacks for the screen

    /**
     * The stack a captured Hypixel item would look like, for one row.
     *
     * <p>Built on demand, never cached in a static: see the class javadoc on component binding.</p>
     */
    static ItemStack stageStack(Row row) {
        Optional<Item> base = BuiltInRegistries.ITEM.getOptional(Identifier.parse(row.baseItem()));
        ItemStack stack = new ItemStack(base.orElseThrow(
                () -> new IllegalStateException("no such base item: " + row.baseItem())));
        stack.set(DataComponents.ITEM_MODEL, Identifier.parse(row.modelId()));
        return stack;
    }

    /** Where the learned rows are written, for the message that tells somebody to delete it. */
    private static String memoryPath() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("drop_item_models.json").toString();
        } catch (RuntimeException | LinkageError notLoaded) {
            return "the config directory's drop_item_models.json";
        }
    }
}
