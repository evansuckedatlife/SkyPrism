package com.skyprism.mc.symbols;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;
import java.util.Optional;

/**
 * SkyBlock's own item id, read off a stack the server sent.
 *
 * <h2>Where it lives</h2>
 * <p>SkyBlock has always kept its per-item metadata in an {@code ExtraAttributes} compound, and
 * the id inside it -- {@code DAEDALUS_STICK}, {@code CROWN_OF_GREED} -- is the one name Hypixel
 * treats as an identity rather than as copy. On 26.x that legacy tag arrives inside the vanilla
 * {@code minecraft:custom_data} component, which is where every non-vanilla NBT ends up. Both
 * shapes are read here: the id nested under {@code ExtraAttributes}, which is what the server
 * actually sends, and the id sitting at the top of {@code custom_data}, because that is the
 * shape a flattening pass would leave behind and it costs one map lookup to tolerate.
 *
 * <h2>Why it is worth the trouble</h2>
 * <p>Everything else in this module is keyed by display name, because a chat line is all we get.
 * A display name is also the thing Hypixel is most likely to change: re-worded, re-coloured,
 * re-capitalised, pluralised. The id is not. So it is preferred when matching a stack in the
 * inventory to a drop the chat pipeline named, and it is stored alongside the learned model so a
 * later capture under a new name can supersede the row the old name owned instead of leaving a
 * stale duplicate behind.
 *
 * <h2>Cost</h2>
 * <p>Reading it copies the tag, which is the one genuinely allocating step. That is why the caller
 * only asks about a slot whose {@link ItemStack} reference actually changed since the last scan,
 * and only while a roll is live -- never per frame, and never on a tick with nothing to look for.
 *
 * <p>Nothing here throws, for anything, ever.
 */
final class SkyBlockIds {

    private SkyBlockIds() {
    }

    /** Hypixel's own compound inside {@code custom_data}. */
    private static final String EXTRA_ATTRIBUTES = "ExtraAttributes";

    /** The member holding the id, in both shapes. */
    private static final String ID = "id";

    /** An id longer than this is not one of Hypixel's; it is a server trying something. */
    private static final int MAX_ID_LENGTH = 128;

    /**
     * SkyBlock's id for this stack.
     *
     * @param stack any stack, may be null or empty
     * @return the id verbatim, e.g. {@code DAEDALUS_STICK}, or null when the stack carries none
     */
    static String of(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            if (custom == null || custom.isEmpty()) {
                return null;
            }
            CompoundTag tag = custom.copyTag();
            String nested = read(tag.getCompound(EXTRA_ATTRIBUTES).orElse(null));
            return nested != null ? nested : read(tag);
        } catch (Exception | LinkageError never) {
            return null;
        }
    }

    /**
     * The id turned into the same shape a display name normalises to, so the two can be compared.
     *
     * <p>{@code DAEDALUS_STICK} becomes {@code daedalus stick}, which is exactly what
     * {@link DropSymbols#matchKey} makes of the chat line "Daedalus Stick". That is the whole
     * point: it lets a stack be matched to a parsed drop without trusting the display name to
     * have stayed put.
     *
     * @param stack any stack, may be null or empty
     * @return the comparable key, or null when the stack carries no id
     */
    static String matchKeyOf(ItemStack stack) {
        String id = of(stack);
        if (id == null) {
            return null;
        }
        String key = DropSymbols.matchKey(id.replace('_', ' ').toLowerCase(Locale.ROOT));
        return key.isEmpty() ? null : key;
    }

    /** The {@code id} member of a compound, if it is a plausible SkyBlock id. */
    private static String read(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        Optional<String> id = tag.getString(ID);
        if (id.isEmpty()) {
            return null;
        }
        String value = id.get().strip();
        return value.isEmpty() || value.length() > MAX_ID_LENGTH ? null : value;
    }
}
