package com.skyprism.mc.selftest;

import com.skyprism.core.diana.LootDrop;
import com.skyprism.mc.symbols.DropSymbols;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Makes Hypixel's art mandatory in a capture rather than incidental, and proves it on the pixels.
 *
 * <h2>What went wrong, in one paragraph</h2>
 *
 * <p>The mod never dressed a reel from the pack. A drop name resolves through
 * {@code drop_symbols.json} to a plain vanilla item -- {@code minecraft:stick} for "Daedalus
 * Stick" -- and a plain vanilla stack carries no {@code minecraft:item_model}, so Hypixel's pack
 * has nothing to hang its model on. The pack contains 3733 files under
 * {@code assets/hypixel_skyblock/} and 23 under {@code assets/minecraft/}, none of them an item
 * texture, so a vanilla stack renders byte-identically whether the pack is mounted or not. The
 * only step that ever attached an {@code item_model} taught four names, and it ran <em>after</em>
 * shots 01 to 15 were already on disk. Every check in the run was upstream of a pixel -- the pack
 * is selected, the namespace resolves, the model is baked -- and every one of them passed while
 * the published pictures were vanilla.</p>
 *
 * <h2>The fix, and then the enforcement</h2>
 *
 * <p>{@link #dress} is the fix: before a single frame is captured, every drop name the mod can
 * draw is joined against the pack's own index of item definitions, and the ones that join get
 * Hypixel's {@code item_model} attached through the same {@code DropSymbols.learnFrom} entry point
 * a live capture uses. That is what a session on the real server converges to; doing it up front
 * is the only difference.</p>
 *
 * <p>{@link #audit} is the enforcement, and it does not trust any of that. It reads the PNGs back
 * off disk and, for every drop each published frame was loaded with, looks for two textures in the
 * picture: Hypixel's, which has to be there, and the vanilla one the reel used to draw, which must
 * not be. A frame that fails is renamed {@code REJECTED-...png} on the spot, so a capture that
 * used vanilla art cannot be copied into {@code docs/images/} by somebody who did not read the
 * summary.</p>
 *
 * <h2>The three-valued verdict</h2>
 *
 * <p>Sixteen of Diana's seventeen strip entries have no Hypixel art in this pack build at all --
 * no {@code griffin_feather}, no {@code minos_relic}, no {@code daedalus_stick}. A rule saying
 * "every sprite must come from the pack" would therefore fail every Diana frame forever for a
 * reason that is not a bug. So each item on each frame lands in one of three states:</p>
 *
 * <ul>
 *   <li><b>dressed</b> -- the pack has this item, so the frame must show the pack's texture and
 *       must not show the vanilla one. Anything else fails the run.</li>
 *   <li><b>vanilla by necessity</b> -- the pack has no item of this name, re-proved against the
 *       live index on every run rather than taken from a list. Vanilla art is the correct answer,
 *       and the report says so by name so nobody has to wonder.</li>
 *   <li><b>unauditable</b> -- the item has no single flat sprite to compare, or the frame washes
 *       it in gold or glint. Stated, never silently counted as a pass.</li>
 * </ul>
 *
 * <p>The moment Hypixel ships art for Griffin Feather, that name moves from the second state to
 * the first on its own and the frame starts being held to it. Nothing has to be remembered.</p>
 */
final class PackEnforcement {

    private PackEnforcement() {
    }

    /** The name of the audit's own report, written beside the captures. */
    static final String REPORT_FILE = "pack-audit.txt";

    /** What a rejected capture is renamed to, so it cannot be published by mistake. */
    static final String REJECTED_PREFIX = "REJECTED-";

    /** What a frame's sprites are allowed to be, and what the audit will insist on. */
    enum Mode {
        /**
         * Every dressed drop must be visible as Hypixel's own texture. The published frames.
         */
        PACK_ART,
        /**
         * The deliberate "before" picture, captured while these names are still on the synthesised
         * fallback. Nothing here can fail -- vanilla art is the whole point of the frame -- but a
         * sprite located in it counts towards the run-level proof that the search works at all.
         */
        VANILLA_ART,
        /**
         * No pixel claim is made. Mid-spin frames show filler rather than the drops, and the gold
         * wash and the enchantment glint composite over a sprite so an exact match cannot hold.
         */
        STRUCTURE_ONLY
    }

    /**
     * One frame the audit has an opinion about.
     *
     * @param file  the PNG's name in the output directory
     * @param items the drop names that frame's roll was loaded with
     * @param mode  what the audit will insist on
     * @param why   why the frame is in that mode, quoted into the report
     */
    record Frame(String file, List<String> items, Mode mode, String why) {
    }

    /**
     * One item's verdict on one frame.
     *
     * @param frame          which capture
     * @param item           which drop name
     * @param failed         whether this alone is enough to reject the capture
     * @param locatedPack    whether Hypixel's own texture was located byte for byte
     * @param locatedAnything whether any texture at all was located, which is what proves the
     *                       search is working and therefore what its silences are worth
     * @param line           the sentence written into the report
     */
    private record Verdict(String frame, String item, boolean failed, boolean locatedPack,
                           boolean locatedAnything, String line) {

        static Verdict pass(Frame frame, String item, String line) {
            return new Verdict(frame.file(), item, false, false, false, line);
        }

        static Verdict fail(Frame frame, String item, String line) {
            return new Verdict(frame.file(), item, true, false, false, line);
        }

        static Verdict sawVanilla(Frame frame, String item, String line) {
            return new Verdict(frame.file(), item, false, false, true, line);
        }

        static Verdict sawPack(Frame frame, String item, String line) {
            return new Verdict(frame.file(), item, false, true, true, line);
        }
    }

    /**
     * What each drop name drew before {@link #dress} ran, so the audit knows what vanilla is.
     *
     * <p>Keyed by {@link PackAssets#key}, not by the name as written. {@code DropSymbols} hands
     * out already-normalised names ("griffin feather") while the fixtures are written the way
     * Hypixel prints them ("Griffin Feather"), and a map keyed by either one directly would miss
     * every lookup from the other -- which would cost the audit its negative control silently,
     * because "no vanilla texture to compare against" reads exactly like "no vanilla art here".</p>
     */
    private static final Map<String, Identifier> FALLBACK_BEFORE_DRESSING = new TreeMap<>();

    /** The names {@link #dress} attached a pack model to, for the report. */
    private static final Set<String> DRESSED = new LinkedHashSet<>();

    // ================================================================== the gate

    /**
     * Refuses to let the run take a single screenshot unless Hypixel's pack is genuinely active.
     *
     * <p>Called before shot 01, and its failure ends the run rather than being recorded and
     * stepped over. That ordering is the point of the whole step: the previous arrangement
     * checked the pack in step 5b, after fifteen captures were already written, so a run against
     * a missing pack produced a full set of vanilla screenshots and one failed line in a JSON
     * file nobody reads before copying a PNG.</p>
     *
     * <p>Four questions, in the order in which they stop being cheap. Is the namespace mounted at
     * all; did the item definitions really come from a pack rather than from the jar; are there
     * enough of them to be a real pack build; and does a spot check of actual entries resolve all
     * the way to a texture and a baked model. The last one is what a stub or a truncated zip
     * fails.</p>
     *
     * @param pack the index just loaded
     * @return the evidence line for the summary
     * @throws IllegalStateException when a capture taken now would be worthless
     */
    static String requireActive(PackAssets pack) {
        StringBuilder out = new StringBuilder(1024);
        out.append("resource namespaces: ").append(pack.namespaces()).append('\n');
        out.append("item definitions under '").append(PackAssets.NAMESPACE).append("': ")
                .append(pack.size()).append('\n');
        out.append("served from packs: ").append(pack.sourcePackIds()).append('\n');

        if (!pack.namespaces().contains(PackAssets.NAMESPACE)) {
            throw new IllegalStateException(out
                    + "\nHYPIXEL'S RESOURCE PACK IS NOT ACTIVE, so this run is aborted before it "
                    + "writes a single PNG.\n"
                    + "Every reel would draw the synthesised vanilla item, which is exactly the "
                    + "failure this gate exists to make impossible to publish.\n"
                    + "To fix it: put hypixel_server_pack.zip in this node's run/resourcepacks/, "
                    + "then in run/options.txt set\n"
                    + "  resourcePacks:[\"file/hypixel_server_pack.zip\"]\n"
                    + "and, on Minecraft 26.2 only, also\n"
                    + "  incompatibleResourcePacks:[\"file/hypixel_server_pack.zip\"]\n"
                    + "because the pack declares pack_format 84 and 26.2 is format 88; vanilla "
                    + "applies an out-of-range pack only once the player has confirmed it, and "
                    + "that second line is what records the confirmation. See docs/TESTING.md.");
        }
        if (!pack.plausible()) {
            throw new IllegalStateException(out
                    + "\nThe namespace is mounted but it holds only " + pack.size()
                    + " item definitions, under the " + PackAssets.plausibleFloor()
                    + " a real build of the pack carries. Something is mounted; it is not the "
                    + "SkyBlock server pack. Aborting before any capture is written.");
        }

        List<String> broken = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (String key : pack.sample(8)) {
            Optional<Identifier> model = pack.modelFor(key);
            if (model.isEmpty()) {
                broken.add(key + " vanished from the index between listing and lookup");
                continue;
            }
            Identifier id = model.get();
            Optional<Identifier> texture = pack.flatTexture(id);
            boolean baked = PackAssets.baked(id);
            boolean hasTexture = texture.isPresent() && pack.has(texture.get());
            checked.add("  " + key + " -> " + id
                    + "  texture=" + texture.map(Identifier::toString).orElse("<none>")
                    + " present=" + hasTexture + " baked=" + baked);
            if (!hasTexture || !baked) {
                broken.add(key + " (texture present=" + hasTexture + ", baked=" + baked + ")");
            }
        }
        for (String line : checked) {
            out.append(line).append('\n');
        }
        if (!broken.isEmpty()) {
            throw new IllegalStateException(out
                    + "\n" + broken.size() + " of the spot-checked entries do not resolve to a "
                    + "texture and a baked model: " + broken
                    + ".\nThose cells would photograph as missing-texture cubes, which is a more "
                    + "confident-looking failure than vanilla art. Aborting before any capture.");
        }
        if (!pack.ambiguous().isEmpty()) {
            out.append("basenames the pack uses twice, excluded from the join rather than guessed"
                    + " at: ").append(pack.ambiguous()).append('\n');
        }
        out.append("the pack is active and its items resolve end to end, so a capture taken now "
                + "can be held to it");
        return out.toString();
    }

    // ================================================================== the fix

    /**
     * Attaches Hypixel's {@code item_model} to every drop name the pack has art for.
     *
     * <p>This is the root-cause fix and it runs before any screenshot. For each name the mod can
     * draw, the pack index is asked whether it holds an item of that name; when it does, the
     * name's existing stack is copied, the pack's id is set on it as
     * {@code minecraft:item_model}, and the result goes through
     * {@link DropSymbols#learnFrom} -- the same call {@code IconCapture} makes for a stack matched
     * in a player's inventory. Everything downstream of the capture is therefore genuine: the
     * learned row, the bounded memory, the resolution order, the sprite.</p>
     *
     * <p>The stacks are <em>constructed</em>, not captured. A dev client has no SkyBlock server to
     * capture from, and saying otherwise would be the same class of dishonesty as photographing
     * vanilla and calling it Hypixel. What is staged is the arrival of the stack; what is proved
     * is that the id is Hypixel's own, taken from the pack's own file layout, and that the render
     * path draws it.</p>
     *
     * <p>{@link HypixelPackProof}'s four demonstration rows are deliberately left alone. They are
     * the before-and-after pair, and dressing them here would turn "before" into a second copy of
     * "after" -- which {@code HypixelPackProof.requireUntaught()} would then fail the run over,
     * correctly.</p>
     *
     * @param pack the live index
     * @return the evidence line for the summary
     */
    static String dress(PackAssets pack, List<Frame> frames) {
        Set<String> reserved = new LinkedHashSet<>();
        for (HypixelPackProof.Row row : HypixelPackProof.rows()) {
            reserved.add(PackAssets.key(row.dropName()));
        }
        reserved.add(PackAssets.key(HypixelPackProof.CONTROL_NAME));

        // The fixture names first, and by name rather than by whatever DropSymbols happens to
        // know: a drop with no row in the table has no entry in knownNames() at all, and the
        // audit still has to be able to say what it drew instead.
        for (Frame frame : frames) {
            for (String item : frame.items()) {
                rememberFallback(item);
            }
        }

        List<String> dressed = new ArrayList<>();
        List<String> noArt = new ArrayList<>();
        for (String name : new ArrayList<>(DropSymbols.knownNames())) {
            rememberFallback(name);
            if (reserved.contains(PackAssets.key(name))) {
                continue;
            }
            Optional<Identifier> model = pack.modelFor(name);
            if (model.isEmpty()) {
                noArt.add(name);
                continue;
            }
            ItemStack staged = stage(pack, model.get(), name);
            if (staged.isEmpty()) {
                continue;
            }
            DropSymbols.learnFrom(name, staged);
            DRESSED.add(PackAssets.key(name));
            dressed.add(name + " -> " + model.get() + " on "
                    + BuiltInRegistries.ITEM.getKey(staged.getItem()));
        }

        StringBuilder out = new StringBuilder(2048);
        out.append("joined ").append(dressed.size()).append(" of ")
                .append(dressed.size() + noArt.size())
                .append(" drop names against the pack's ").append(pack.size())
                .append(" item definitions and attached Hypixel's own item_model to each, through "
                        + "DropSymbols.learnFrom -- the call IconCapture makes for a stack matched "
                        + "in the player's inventory. The stacks are CONSTRUCTED from the pack's "
                        + "file layout, not captured from a server; a dev client has none. Every "
                        + "capture from here on therefore draws Hypixel's art wherever Hypixel has "
                        + "art, which is what a live session converges to.\n");
        out.append("  reserved for the before/after pair, left undressed on purpose: ")
                .append(reserved).append('\n');
        for (String line : dressed) {
            out.append("  ").append(line).append('\n');
        }
        out.append("  ").append(noArt.size())
                .append(" names have no item of that name in this pack build, so vanilla art is "
                        + "the correct answer for them and none was invented");
        return out.toString();
    }

    /**
     * The stack a captured Hypixel item would look like, for one name.
     *
     * <p>Built on the vanilla item Hypixel's own model file names as its parent, not on the
     * lookalike {@code drop_symbols.json} picked. See {@link PackAssets#parentItem} for why that
     * distinction decides whether the sprite can be audited at all. When the parent cannot be
     * read the existing fallback item is used, which is still correct -- the {@code item_model}
     * component, not the base item, is what chooses the sprite -- it simply drags that item's own
     * default components along with it.</p>
     */
    private static ItemStack stage(PackAssets pack, Identifier model, String name) {
        Optional<Identifier> parent = pack.parentItem(model);
        if (parent.isPresent()) {
            Optional<Item> base = BuiltInRegistries.ITEM.getOptional(parent.get());
            if (base.isPresent()) {
                ItemStack stack = new ItemStack(base.get());
                stack.set(DataComponents.ITEM_MODEL, model);
                return stack;
            }
        }
        ItemStack fallback = DropSymbols.iconForName(name);
        if (fallback.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = fallback.copyWithCount(1);
        stack.set(DataComponents.ITEM_MODEL, model);
        return stack;
    }

    /** Snapshots what a name draws before anything is taught, for the audit's negative control. */
    private static void rememberFallback(String name) {
        String key = PackAssets.key(name);
        if (key.isEmpty() || FALLBACK_BEFORE_DRESSING.containsKey(key)) {
            return;
        }
        ItemStack stack = DropSymbols.iconForName(name);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Item item = stack.getItem();
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null) {
            FALLBACK_BEFORE_DRESSING.put(key, id);
        }
    }

    // ================================================================== the audit

    /**
     * Reads every published frame back off disk and compares its pixels to the pack's own art.
     *
     * <p>This is the check that would have caught the bug. It is not "the pack mounted", which was
     * true throughout; it is "the sixteen-by-sixteen block of colours Hypixel ships for Judgement
     * Core is, or is not, inside this PNG".</p>
     *
     * <p>Any frame with a failing item is renamed with {@value #REJECTED_PREFIX} before this
     * method returns, so the failure survives being ignored.</p>
     *
     * @param outDir where the captures were written
     * @param pack   the live index
     * @param frames what each frame is expected to be showing
     * @return the evidence line for the summary
     * @throws Exception when a frame drew vanilla art it should not have, or when the audit could
     *                   not prove itself
     */
    static String audit(Path outDir, PackAssets pack, List<Frame> frames) throws Exception {
        List<Verdict> verdicts = new ArrayList<>();
        Map<String, List<String>> perFrame = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        int packSpritesFound = 0;
        int anySpritesFound = 0;

        for (Frame frame : frames) {
            Path png = outDir.resolve(frame.file());
            List<String> lines = new ArrayList<>();
            perFrame.put(frame.file(), lines);
            lines.add("mode " + frame.mode() + " -- " + frame.why());

            if (!Files.isRegularFile(png)) {
                lines.add("  the capture was never written, so there is nothing to audit");
                continue;
            }
            SpriteSearch.Pixels image;
            try {
                image = SpriteSearch.decode(Files.readAllBytes(png));
            } catch (Exception unreadable) {
                lines.add("  the capture could not be decoded: " + unreadable);
                verdicts.add(new Verdict(frame.file(), "<the file>", true, false, false,
                        "the PNG could not be decoded: " + unreadable));
                continue;
            }
            lines.add("  " + image.width() + "x" + image.height() + " pixels");

            for (String item : frame.items()) {
                Verdict verdict = judge(frame, item, image, pack);
                verdicts.add(verdict);
                lines.add("  " + verdict.line());
                if (verdict.locatedPack()) {
                    packSpritesFound++;
                }
                if (verdict.locatedAnything()) {
                    anySpritesFound++;
                }
            }
        }

        // The audit has to prove itself before its silences are worth anything. A search that
        // never locates a sprite reports "no vanilla art here" for every frame in the set, which
        // reads exactly like a pass and is exactly the failure this class replaces. So two
        // run-level questions, both of which the published set answers by itself: did the search
        // locate any sprite at all -- the frames are full of drops the pack has no art for, and
        // those are drawn in flat vanilla textures it can find -- and did it locate a Hypixel
        // one, without which nothing in the set is evidence of Hypixel art.
        List<String> structural = new ArrayList<>();
        if (anySpritesFound == 0) {
            structural.add("the pixel search did not locate a single sprite of any kind in any "
                    + "frame, so it is not demonstrably working and none of its other answers -- "
                    + "including every 'no vanilla art here' -- can be trusted");
        }
        if (packSpritesFound == 0) {
            structural.add("not one Hypixel sprite was located in any published frame, so no "
                    + "frame in this set is evidence that the pack's art reached a capture");
        }

        for (Map.Entry<String, List<String>> entry : perFrame.entrySet()) {
            boolean failed = verdicts.stream()
                    .anyMatch(v -> v.frame().equals(entry.getKey()) && v.failed());
            if (failed) {
                rejected.add(entry.getKey());
                quarantine(outDir, entry.getKey());
            }
        }

        String report = writeReport(outDir, perFrame, rejected, structural, pack);
        List<String> failures = new ArrayList<>();
        for (Verdict verdict : verdicts) {
            if (verdict.failed()) {
                failures.add(verdict.frame() + " / " + verdict.item() + ": " + verdict.line());
            }
        }
        failures.addAll(structural);

        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder(2048);
            message.append("THE CAPTURES DID NOT USE HYPIXEL'S ART. ").append(failures.size())
                    .append(failures.size() == 1 ? " finding" : " findings")
                    .append(", across ").append(rejected.size())
                    .append(rejected.size() == 1 ? " frame" : " frames").append(":\n");
            for (String failure : failures) {
                message.append("  ").append(failure).append('\n');
            }
            if (!rejected.isEmpty()) {
                message.append("Renamed so they cannot be published: ").append(rejected)
                        .append(" (each is now ").append(REJECTED_PREFIX).append("<name>.png)\n");
            }
            message.append("Full audit: ").append(report);
            throw new IllegalStateException(message.toString());
        }
        return "every published frame was decoded and searched for Hypixel's own texture for each "
                + "drop it carries, and for the vanilla texture that drop used to draw. "
                + packSpritesFound + " Hypixel sprites located byte for byte, out of "
                + anySpritesFound + " sprites of any kind located -- the second figure is what "
                + "proves the search works, so its silences mean something. Full per-item audit: "
                + report;
    }

    /**
     * One item on one frame: what the pack says it should look like, and what the PNG shows.
     *
     * <p>The order of the questions is the order in which they can be answered cheaply and
     * decisively. Does the pack have art for this name at all -- if not, vanilla is correct and
     * the report says which vanilla texture it is. Did the dressing pass reach the stack the reel
     * draws -- if not, no arrangement of pixels can make this frame Hypixel art. Can the sprite be
     * compared at all -- a glinting or non-flat item cannot. Only then: is Hypixel's texture in
     * this picture, and is the vanilla one absent.</p>
     */
    private static Verdict judge(Frame frame, String item, SpriteSearch.Pixels image,
                                 PackAssets pack) {
        Optional<Identifier> model = pack.modelFor(item);
        Identifier fallbackItem = FALLBACK_BEFORE_DRESSING.get(PackAssets.key(item));
        Optional<Identifier> vanillaTexture = fallbackItem == null
                ? Optional.empty() : pack.vanillaTexture(fallbackItem);

        if (frame.mode() == Mode.VANILLA_ART) {
            // The before-picture, captured while these names are still on the synthesised
            // fallback. Informational: it is the one frame in the set that is SUPPOSED to be
            // vanilla, so nothing here can fail, but a sprite located here counts towards the
            // proof that the search works.
            SpriteSearch.Result found = search(image, pack, vanillaTexture);
            if (found != null && found.isFound()) {
                return Verdict.sawVanilla(frame, item, "before  " + item + " drew the vanilla "
                        + vanillaTexture.orElseThrow() + " -- " + found.note()
                        + ", which is what this frame is for");
            }
            return Verdict.pass(frame, item, "before  " + item + " was not located as "
                    + vanillaTexture.map(Identifier::toString).orElse("<no flat vanilla texture>")
                    + (found == null ? "" : " (" + found.note() + ")"));
        }

        if (model.isEmpty()) {
            // Vanilla by necessity, re-proved against the live index rather than allowlisted.
            // The moment Hypixel ships art under this name the branch below takes over and the
            // frame starts being held to it, with nothing for anybody to remember to update.
            SpriteSearch.Result found = search(image, pack, vanillaTexture);
            String preamble = "no-pack-art  " + item + ": this pack build has no item named '"
                    + PackAssets.key(item) + "', checked against the live index of " + pack.size()
                    + " definitions, so vanilla art is correct for it and none was invented";
            if (found != null && found.isFound()) {
                return Verdict.sawVanilla(frame, item, preamble + ". The frame does show the "
                        + "vanilla " + vanillaTexture.orElseThrow() + " -- " + found.note());
            }
            return Verdict.pass(frame, item, preamble);
        }

        Identifier packModel = model.get();

        // Before looking at pixels: did the dressing actually reach the stack the reel draws? A
        // frame can only be showing Hypixel's art if the stack carried Hypixel's id.
        ItemStack drawn = DropSymbols.iconForName(item);
        Identifier onStack = drawn.isEmpty() ? null : drawn.get(DataComponents.ITEM_MODEL);
        if (onStack == null || !onStack.equals(packModel)) {
            return Verdict.fail(frame, item, "NO ITEM_MODEL  " + item
                    + " draws a stack whose item_model is " + onStack + ", not the pack's "
                    + packModel + " (DropSymbols.sourceFor says " + DropSymbols.sourceFor(item)
                    + "). The dressing pass did not reach this name, so this frame cannot be "
                    + "showing Hypixel's art whatever it looks like");
        }

        if (frame.mode() == Mode.STRUCTURE_ONLY) {
            return Verdict.pass(frame, item, "dressed  " + item + " carries " + packModel
                    + " on the stack the reel draws; no pixel claim is made for this frame because "
                    + frame.why());
        }

        Optional<Identifier> packTexture = pack.flatTexture(packModel);
        if (packTexture.isEmpty()) {
            return Verdict.pass(frame, item, "unauditable  " + item + " -> " + packModel
                    + " has no single flat layer0 texture (a composite or a block model), so there "
                    + "is no rectangle to compare; the stack carries the pack's id and that is all "
                    + "this frame can prove about it");
        }
        if (drawn.hasFoil()) {
            // A glint is an animated additive layer over the sprite, so no exact comparison can
            // hold. Said out loud rather than counted as a pass: this is a real hole, and the
            // report names the item that falls into it rather than letting it read as proof.
            return Verdict.pass(frame, item, "unauditable  " + item + " -> " + packModel
                    + " draws with an enchantment glint over it ("
                    + BuiltInRegistries.ITEM.getKey(drawn.getItem())
                    + " carries enchantment_glint_override), and a glint is an animated additive "
                    + "layer that no exact texel comparison survives. The stack carries the pack's "
                    + "id; the pixels are not checked");
        }

        SpriteSearch.Result hypixel = search(image, pack, packTexture);
        SpriteSearch.Result vanilla = search(image, pack, vanillaTexture);
        boolean vanillaVisible = vanilla != null && vanilla.isFound();

        if (hypixel != null && hypixel.isFound() && !vanillaVisible) {
            return Verdict.sawPack(frame, item, "PACK ART  " + item + " drew "
                    + packTexture.orElseThrow() + " -- " + hypixel.note()
                    + "; the vanilla " + vanillaTexture.map(Identifier::toString).orElse("<none>")
                    + " is not in this frame");
        }
        if (vanillaVisible && (hypixel == null || !hypixel.isFound())) {
            return Verdict.fail(frame, item, "VANILLA ART  " + item
                    + " drew the VANILLA texture " + vanillaTexture.orElseThrow() + " -- "
                    + vanilla.note() + " -- instead of Hypixel's " + packTexture.orElseThrow()
                    + ", which is not in this frame ("
                    + (hypixel == null ? "the pack texture could not be decoded" : hypixel.note())
                    + "). The pack has art for this item and the capture did not use it");
        }
        if (vanillaVisible) {
            return Verdict.fail(frame, item, "AMBIGUOUS  " + item + " shows BOTH "
                    + packTexture.orElseThrow() + " and the vanilla "
                    + vanillaTexture.orElseThrow() + " somewhere in this frame, so the audit "
                    + "cannot say which one the reel cell drew");
        }
        return Verdict.fail(frame, item, "NOT FOUND  " + item + ": neither Hypixel's "
                + packTexture.orElseThrow() + " ("
                + (hypixel == null ? "undecodable" : hypixel.note()) + ") nor the vanilla "
                + vanillaTexture.map(Identifier::toString).orElse("<none>")
                + " is anywhere in this frame at any whole magnification. Either the cell is "
                + "clipped by the reel window, or it was drawn at a fractional scale or tinted, "
                + "and in every one of those cases this frame is not evidence of Hypixel art");
    }

    /** Decodes a texture and searches for it, or answers null when it cannot be read. */
    private static SpriteSearch.Result search(SpriteSearch.Pixels image, PackAssets pack,
                                              Optional<Identifier> texture) {
        if (texture.isEmpty()) {
            return null;
        }
        Optional<SpriteSearch.Template> template =
                pack.image(texture.get()).flatMap(SpriteSearch::templateOf);
        return template.map(value -> SpriteSearch.find(image, value)).orElse(null);
    }

    /** Renames a failing capture so it cannot be copied into the docs by accident. */
    private static void quarantine(Path outDir, String file) {
        try {
            Path from = outDir.resolve(file);
            if (Files.isRegularFile(from)) {
                Files.move(from, outDir.resolve(REJECTED_PREFIX + file),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception cannotRename) {
            // Reported through the thrown failure either way; a rename that could not happen must
            // not swallow the reason the frame was being rejected.
        }
    }

    private static String writeReport(Path outDir, Map<String, List<String>> perFrame,
                                      List<String> rejected, List<String> structural,
                                      PackAssets pack) throws Exception {
        StringBuilder text = new StringBuilder(8192);
        text.append("SkyPrism capture audit -- what each published frame actually drew\n");
        text.append("================================================================\n\n");
        text.append("Pack index: ").append(pack.size())
                .append(" item definitions under ").append(PackAssets.NAMESPACE)
                .append(", served from ").append(pack.sourcePackIds()).append('\n');
        text.append("Names dressed with a pack item_model before any capture: ")
                .append(DRESSED.size()).append('\n');
        text.append("Each frame's drops are searched for twice: once for Hypixel's own texture,\n"
                + "once for the vanilla texture the reel drew before this change. The search is\n"
                + "exact -- every fully opaque texel, at a whole magnification, byte for byte.\n\n");
        for (Map.Entry<String, List<String>> entry : perFrame.entrySet()) {
            text.append(entry.getKey()).append('\n');
            for (String line : entry.getValue()) {
                text.append(line).append('\n');
            }
            text.append('\n');
        }
        if (!rejected.isEmpty()) {
            text.append("REJECTED, renamed to ").append(REJECTED_PREFIX)
                    .append("<name>.png so they cannot be published: ").append(rejected)
                    .append('\n');
        }
        for (String line : structural) {
            text.append("AUDIT CANNOT PROVE ITSELF: ").append(line).append('\n');
        }
        Path file = outDir.resolve(REPORT_FILE);
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return file.toAbsolutePath().toString();
    }

    // ================================================================== housekeeping

    /**
     * Deletes the captures this run intends to write, before it writes any of them.
     *
     * <p>An aborted run leaves the previous run's PNGs sitting in the output directory with no
     * hint that they are stale, and stale is exactly how a vanilla screenshot gets published a
     * second time. Only the file names this run owns are touched, and the rejected copies from a
     * previous audit go with them.</p>
     *
     * @param outDir where the captures land
     * @param files  the names this run will write
     * @return the evidence line for the summary
     */
    static String clearStale(Path outDir, List<String> files) throws Exception {
        int removed = 0;
        for (String file : files) {
            if (Files.deleteIfExists(outDir.resolve(file))) {
                removed++;
            }
            if (Files.deleteIfExists(outDir.resolve(REJECTED_PREFIX + file))) {
                removed++;
            }
        }
        Files.deleteIfExists(outDir.resolve(REPORT_FILE));
        return "removed " + removed + " capture" + (removed == 1 ? "" : "s") + " left by an "
                + "earlier run, so nothing in " + outDir.toAbsolutePath()
                + " can be mistaken for output of this one";
    }

    /** The drop names of a fixture list, in column order. */
    static List<String> namesOf(List<LootDrop> drops) {
        List<String> names = new ArrayList<>(drops.size());
        for (LootDrop drop : drops) {
            names.add(drop.itemName());
        }
        return names;
    }
}
