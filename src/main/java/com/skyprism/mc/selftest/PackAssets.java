package com.skyprism.mc.selftest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Hypixel resource pack as the running client can actually see it, item by item.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>{@link HypixelPackProof} carries four hand-written rows, each one a display name paired with
 * an {@code item_model} id somebody read out of the zip. That was enough to prove one item can
 * wear Hypixel's art and nothing more, and it is why every published screenshot could go on
 * drawing vanilla while the summary said the pack was loaded: four rows were dressed, they were
 * dressed after the pictures were taken, and none of the four appeared in a published frame.</p>
 *
 * <p>This reads the whole pack instead, at run time, out of the client's own
 * {@link ResourceManager}. Nothing is hardcoded and nothing is copied from an offline listing, so
 * the index is by construction a description of the pack the client really mounted rather than of
 * the pack somebody looked at once.</p>
 *
 * <h2>The join, and why it is allowed to be this simple</h2>
 *
 * <p>{@code IconCapture}'s header says there is no table anywhere turning "Daedalus Blade" into
 * {@code hypixel_skyblock:item/community_center/mayor/diana/daedalus_blade}, and for the general
 * case that is true: the pack's directories are semantic and it publishes no display-name index.
 * But the file <em>names</em> are not semantic. Every one of the 1092 item definitions is its
 * SkyBlock internal id lowercased -- {@code DAEDALUS_BLADE} becomes {@code daedalus_blade} -- and
 * an internal id is the display name with the punctuation removed and the spaces turned into
 * underscores for the overwhelming majority of items. So {@link #key(String)} does exactly that
 * and nothing cleverer, and a name that does not join simply has no entry.</p>
 *
 * <p>That deliberately answers "no" more often than a fuzzy matcher would. A wrong join is worse
 * than a missing one here: it would dress a reel in some other item's art and photograph it as
 * proof, which is the same class of mistake as photographing vanilla and calling it Hypixel.
 * Basenames that occur twice in the pack under different directories are dropped into
 * {@link #ambiguous()} rather than guessed at, for the same reason.</p>
 *
 * <h2>What an entry can answer</h2>
 *
 * <p>The {@code item_model} value needs no parsing: a definition living at
 * {@code assets/<ns>/items/<X>.json} <em>is</em> the id {@code <ns>:<X>}, which is precisely what
 * the vanilla {@code minecraft:item_model} component points at. Textures do need parsing, so they
 * are read lazily and only for the handful of items a frame audit actually looks at:
 * definition -&gt; {@code models/<model>.json} -&gt; {@code textures.layer0} -&gt; the PNG. The
 * same three-step walk works unchanged on {@code minecraft:} ids, which is how the audit gets the
 * <em>vanilla</em> texture it has to prove the frame did not draw.</p>
 */
final class PackAssets {

    /** The namespace Hypixel's pack publishes under. */
    static final String NAMESPACE = HypixelPackProof.NAMESPACE;

    /** How many item definitions a real build of the pack has, near enough to sanity-check. */
    private static final int PLAUSIBLE_DEFINITION_FLOOR = 500;

    /** How far {@link #flatTexture} will climb a model's {@code parent} chain looking for art. */
    private static final int PARENT_DEPTH = 6;

    /** Minecraft's legacy formatting marker, written as an escape so the file's encoding cannot
     * change what this compares against. */
    private static final char SECTION_SIGN = '§';

    /** Normalised basename to the {@code item_model} id that dresses it. */
    private final Map<String, Identifier> byKey;

    /** Basenames the pack uses more than once, which are therefore not safe to join on. */
    private final Set<String> ambiguous;

    /** Which packs the item definitions were actually served from, for the gate's message. */
    private final Set<String> sourcePackIds;

    private final ResourceManager resources;

    /** Parsed-once cache, because a frame audit asks the same item several questions. */
    private final Map<Identifier, Optional<Identifier>> textureCache = new HashMap<>();

    private PackAssets(ResourceManager resources, Map<String, Identifier> byKey,
                       Set<String> ambiguous, Set<String> sourcePackIds) {
        this.resources = resources;
        this.byKey = byKey;
        this.ambiguous = ambiguous;
        this.sourcePackIds = sourcePackIds;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Reads every {@code hypixel_skyblock} item definition the client can currently see.
     *
     * <p>Never throws for an absent pack: an empty index is a legitimate answer that
     * {@link PackEnforcement#requireActive} turns into the run-ending failure. Throwing here
     * instead would put the diagnosis in a stack trace rather than in a sentence.</p>
     *
     * @return the index, possibly empty
     */
    static PackAssets load() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return new PackAssets(null, Map.of(), Set.of(), Set.of());
        }
        ResourceManager resources = client.getResourceManager();

        Map<String, Identifier> byKey = new LinkedHashMap<>(2048);
        Set<String> ambiguous = new TreeSet<>();
        Set<String> packs = new TreeSet<>();

        Map<Identifier, Resource> found = resources.listResources("items",
                id -> NAMESPACE.equals(id.getNamespace()) && id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> each : found.entrySet()) {
            // "items/item/a/b/daedalus_blade.json" -> "item/a/b/daedalus_blade", which is both
            // the model path and, with the namespace, the item_model id itself. The prefix is
            // stripped only if it is there: listResources is documented to hand back the full
            // resource path, but an id that arrives already relative must not lose six characters
            // off its front and turn into a lookup that silently finds nothing.
            String path = each.getKey().getPath();
            if (path.startsWith("items/")) {
                path = path.substring("items/".length());
            }
            String modelPath = path.substring(0, path.length() - ".json".length());
            int slash = modelPath.lastIndexOf('/');
            String base = slash < 0 ? modelPath : modelPath.substring(slash + 1);
            String key = key(base);
            if (key.isEmpty()) {
                continue;
            }
            Identifier model = Identifier.fromNamespaceAndPath(NAMESPACE, modelPath);
            Identifier previous = byKey.put(key, model);
            if (previous != null && !previous.equals(model)) {
                ambiguous.add(key);
            }
            try {
                packs.add(each.getValue().sourcePackId());
            } catch (RuntimeException unanswerable) {
                packs.add("<unnamed pack>");
            }
        }
        for (String duplicate : ambiguous) {
            byKey.remove(duplicate);
        }
        return new PackAssets(resources, Collections.unmodifiableMap(byKey),
                Collections.unmodifiableSet(ambiguous), Collections.unmodifiableSet(packs));
    }

    // ------------------------------------------------------------------ the join

    /**
     * The lookup key for a display name, or for a pack basename.
     *
     * <p>Lowercased, everything that is not a letter, a digit or a space removed outright, then
     * runs of spaces collapsed to one underscore. Removing punctuation rather than replacing it
     * is what makes "Necron's Handle" and "Washed-up Souvenir" land on {@code necrons_handle} and
     * {@code washedup_souvenir}, which is how SkyBlock spells its own internal ids.</p>
     *
     * @param name a display name or a file basename; null yields an empty key
     * @return the key, or an empty string when nothing usable was left
     */
    static String key(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean pendingGap = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == SECTION_SIGN) {
                // A legacy colour code is the sign plus one character, and that character is a
                // letter or a digit -- so dropping only the sign would leave a stray "d" welded
                // to the front of the name and the join would silently find nothing.
                i++;
                pendingGap = true;
                continue;
            }
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                if (pendingGap && out.length() > 0) {
                    out.append('_');
                }
                pendingGap = false;
                out.append(c);
            } else if (c == ' ' || c == '_' || c == '\t') {
                pendingGap = true;
            }
            // Anything else -- apostrophes, hyphens, the section sign, punctuation -- is dropped
            // without leaving a gap, which is what SkyBlock's own internal ids do.
        }
        return out.toString();
    }

    /**
     * The {@code item_model} id Hypixel's pack would put on this drop, if it has art for it.
     *
     * @param displayName the name the chat pipeline parsed
     * @return the id, or empty when the pack has no item of that name
     */
    Optional<Identifier> modelFor(String displayName) {
        String key = key(displayName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Identifier direct = byKey.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        return Optional.ofNullable(byKey.get(tierRotated(key)));
    }

    /**
     * The same key with its first word moved to the end, which is how SkyBlock spells a tier.
     *
     * <p>Hypixel writes a tiered item's display name adjective first and its internal id noun
     * first: the trophy fish a player sees as "Silver Magmafish" is {@code magmafish_silver} in
     * the pack, and the direct join therefore finds nothing for it even though the art is right
     * there. Rotating one word is the whole of the rule, and it is only ever asked after the
     * direct join has missed, so it can add a match but never change one.</p>
     *
     * <p>It is a heuristic and it is treated as one. Measured against the 526 names in
     * {@code drop_symbols.json} on the shipped pack build, it produces exactly one match that the
     * direct join does not -- Silver Magmafish -- and that match is correct. The audit then holds
     * whatever it produces to Hypixel's own pixels anyway, so a wrong rotation would be caught as
     * a failing frame rather than published as a wrong sprite.</p>
     *
     * @param key an already-normalised key
     * @return the rotated key, or the key itself when there is nothing to rotate
     */
    private static String tierRotated(String key) {
        int firstGap = key.indexOf('_');
        if (firstGap <= 0 || firstGap + 1 >= key.length()) {
            return key;
        }
        return key.substring(firstGap + 1) + "_" + key.substring(0, firstGap);
    }

    /** @return how many item definitions the index holds */
    int size() {
        return byKey.size();
    }

    /** @return basenames the pack uses twice, which are excluded from the join */
    Set<String> ambiguous() {
        return ambiguous;
    }

    /** @return the pack ids the item definitions were served from */
    Set<String> sourcePackIds() {
        return sourcePackIds;
    }

    /** @return true when the index looks like a real build of the pack rather than a stub */
    boolean plausible() {
        return byKey.size() >= PLAUSIBLE_DEFINITION_FLOOR;
    }

    /** @return the floor {@link #plausible()} compares against, for the failure message */
    static int plausibleFloor() {
        return PLAUSIBLE_DEFINITION_FLOOR;
    }

    // ------------------------------------------------------------------ textures

    /**
     * The flat sprite an item-model definition ultimately draws, by walking the pack's own files.
     *
     * <p>Three hops, all of them ordinary vanilla resource-pack schema:
     * {@code items/<X>.json} names a model, {@code models/<M>.json} names a
     * {@code textures.layer0}, and that is a texture id whose PNG lives under {@code textures/}.
     * When a model has no {@code layer0} of its own the {@code parent} is followed, which is what
     * makes this work for vanilla items whose art is declared one level up.</p>
     *
     * <p>Answers empty rather than guessing for anything that is not a flat layered sprite -- a
     * block model, a composite {@code minecraft:condition} or {@code minecraft:select}
     * definition, a model whose parent chain runs out. Those items simply cannot be audited by
     * comparing a rectangle of pixels, and the audit says so instead of inventing a comparison.</p>
     *
     * @param definition the {@code item_model} id, in any namespace
     * @return the texture id, or empty when this item has no single flat sprite
     */
    Optional<Identifier> flatTexture(Identifier definition) {
        Optional<Identifier> cached = textureCache.get(definition);
        if (cached != null) {
            return cached;
        }
        Optional<Identifier> answer = resolveFlatTexture(definition);
        textureCache.put(definition, answer);
        return answer;
    }

    private Optional<Identifier> resolveFlatTexture(Identifier definition) {
        if (resources == null) {
            return Optional.empty();
        }
        JsonObject item = readJson(Identifier.fromNamespaceAndPath(definition.getNamespace(),
                "items/" + definition.getPath() + ".json"));
        if (item == null) {
            return Optional.empty();
        }
        JsonElement model = item.get("model");
        if (model == null || !model.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement modelId = model.getAsJsonObject().get("model");
        if (modelId == null || !modelId.isJsonPrimitive()) {
            // A composite item model -- a condition, a select, a range dispatch. Real, valid, and
            // not a single sprite, so there is nothing here to compare a rectangle against.
            return Optional.empty();
        }
        Identifier current = tryParse(modelId.getAsString());
        for (int depth = 0; depth < PARENT_DEPTH && current != null; depth++) {
            JsonObject json = readJson(Identifier.fromNamespaceAndPath(current.getNamespace(),
                    "models/" + current.getPath() + ".json"));
            if (json == null) {
                return Optional.empty();
            }
            JsonElement textures = json.get("textures");
            if (textures != null && textures.isJsonObject()) {
                JsonElement layer0 = textures.getAsJsonObject().get("layer0");
                if (layer0 != null && layer0.isJsonPrimitive()) {
                    Identifier texture = tryParse(layer0.getAsString());
                    return texture == null ? Optional.empty()
                            : Optional.of(Identifier.fromNamespaceAndPath(texture.getNamespace(),
                                    "textures/" + texture.getPath() + ".png"));
                }
            }
            JsonElement parent = json.get("parent");
            current = parent != null && parent.isJsonPrimitive()
                    ? tryParse(parent.getAsString()) : null;
        }
        return Optional.empty();
    }

    /**
     * The vanilla item Hypixel's own model file builds this item on.
     *
     * <p>Every one of the pack's models is a {@code layer0} sprite over a vanilla parent --
     * {@code {"parent":"item/paper", "textures":{"layer0":"hypixel_skyblock:item/.../null_atom"}}}
     * -- and {@code item/paper} is by a wide margin the commonest, because a flat generated model
     * is what most SkyBlock items are. That parent is the closest thing the pack publishes to a
     * statement of what item Hypixel actually sends, so it is what a staged stack is built on.</p>
     *
     * <p>It matters for more than tidiness. Building the staged stack on whatever vanilla
     * lookalike {@code drop_symbols.json} picked drags that item's default components along with
     * it, and {@code minecraft:nether_star} carries {@code enchantment_glint_override=true} -- so
     * a Null Atom staged on the nether star would draw Hypixel's texture under a moving glint,
     * which no exact pixel comparison can survive. Staged on {@code minecraft:paper}, which is
     * what Hypixel's own file names, it is a plain sprite and the audit can hold it to one.</p>
     *
     * @param definition the {@code item_model} id
     * @return the vanilla item id, or empty when the model names no usable parent
     */
    Optional<Identifier> parentItem(Identifier definition) {
        if (resources == null) {
            return Optional.empty();
        }
        JsonObject item = readJson(Identifier.fromNamespaceAndPath(definition.getNamespace(),
                "items/" + definition.getPath() + ".json"));
        if (item == null) {
            return Optional.empty();
        }
        JsonElement model = item.get("model");
        if (model == null || !model.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement modelId = model.getAsJsonObject().get("model");
        if (modelId == null || !modelId.isJsonPrimitive()) {
            return Optional.empty();
        }
        Identifier id = tryParse(modelId.getAsString());
        if (id == null) {
            return Optional.empty();
        }
        JsonObject json = readJson(Identifier.fromNamespaceAndPath(id.getNamespace(),
                "models/" + id.getPath() + ".json"));
        if (json == null) {
            return Optional.empty();
        }
        JsonElement parent = json.get("parent");
        if (parent == null || !parent.isJsonPrimitive()) {
            return Optional.empty();
        }
        Identifier parentId = tryParse(parent.getAsString());
        if (parentId == null) {
            return Optional.empty();
        }
        String path = parentId.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.isEmpty() ? Optional.empty()
                : Optional.of(Identifier.fromNamespaceAndPath(parentId.getNamespace(), name));
    }

    /**
     * The flat sprite a vanilla item draws, found the same way through the same schema.
     *
     * <p>Safe to ask with Hypixel's pack mounted: that pack ships 23 files under
     * {@code assets/minecraft/} and not one of them is an item definition, a model or an item
     * texture, so a {@code minecraft:} id resolves to the jar's own art whether the pack is
     * present or not. That is exactly why a log line saying the pack loaded proved nothing, and
     * it is also what makes the negative half of the audit trustworthy.</p>
     *
     * @param itemId a vanilla item id such as {@code minecraft:stick}
     * @return the texture id, or empty for anything without a single flat sprite
     */
    Optional<Identifier> vanillaTexture(Identifier itemId) {
        return flatTexture(itemId);
    }

    /**
     * Decodes a texture the client can see.
     *
     * @param texture a {@code textures/....png} id
     * @return the decoded image, or empty when the resource is absent or unreadable
     */
    Optional<NativeImage> image(Identifier texture) {
        if (resources == null) {
            return Optional.empty();
        }
        Optional<Resource> resource = resources.getResource(texture);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = resource.get().open()) {
            return Optional.of(NativeImage.read(in));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    /** @return true when the client can see this resource at all */
    boolean has(Identifier id) {
        return resources != null && resources.getResource(id).isPresent();
    }

    /** @return the namespaces the client's resource manager currently knows about */
    Set<String> namespaces() {
        return resources == null ? Set.of() : new TreeSet<>(resources.getNamespaces());
    }

    /**
     * Whether the model manager baked a real model for an id rather than the missing one.
     *
     * <p>Asked by comparing against an id nothing can have supplied: the manager answers every
     * unknown id with the same missing-model instance, so identity with that instance is exactly
     * the question "did this id resolve".</p>
     */
    static boolean baked(Identifier id) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        try {
            ModelManager models = client.getModelManager();
            ItemModel missing = models.getItemModel(Identifier.fromNamespaceAndPath(
                    NAMESPACE, "item/skyprism_selftest_this_id_cannot_exist"));
            return models.getItemModel(id) != missing;
        } catch (RuntimeException | LinkageError unanswerable) {
            return false;
        }
    }

    /** A stable sample of the index, for a report line that has to fit on a screen. */
    List<String> sample(int count) {
        List<String> keys = new ArrayList<>(new TreeSet<>(byKey.keySet()));
        return keys.subList(0, Math.min(count, keys.size()));
    }

    /** @return every joinable key, for the dressing pass */
    Set<String> keys() {
        return new HashSet<>(byKey.keySet());
    }

    // ------------------------------------------------------------------ plumbing

    private JsonObject readJson(Identifier id) {
        Optional<Resource> resource = resources.getResource(id);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream in = resource.get().open();
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static Identifier tryParse(String raw) {
        try {
            return Identifier.parse(raw);
        } catch (RuntimeException notAnId) {
            return null;
        }
    }
}
