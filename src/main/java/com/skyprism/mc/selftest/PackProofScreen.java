package com.skyprism.mc.selftest;

import com.skyprism.mc.symbols.DropSymbols;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One frame that puts the old sprite and the new one side by side, with the id between them.
 *
 * <h2>Why a dedicated screen</h2>
 *
 * <p>The reel captures either side of the change are the honest demonstration -- they come from
 * {@code SlotMachineHud.extractRenderState}, so they show the shipped render path drawing the real
 * widget. What they cannot do is put the two states in one image. A reader comparing two PNGs taken
 * seconds apart has to trust that nothing else moved between them, and on a settled reel plenty
 * has: the strip has scrolled, the fade curve is at a different point, the captions differ.</p>
 *
 * <p>So this screen draws both stacks for each row at once, through the same
 * {@code graphics.item()} call the reel uses, at the same size, on the same background, on the same
 * frame. If the right column is Hypixel's art and the left is plain vanilla, the pack applied. If
 * both columns match, it did not, and no amount of caption can hide it.</p>
 *
 * <h2>The control row</h2>
 *
 * <p>The last row is a name this pack build has no art for. Its two columns are <em>meant</em> to
 * be identical, and they are labelled as such. It is there because a demonstration where every row
 * changes cannot distinguish "the pack dressed these items" from "something is dressing
 * everything", and because it is the truthful answer for a drop Hypixel has not published art
 * for.</p>
 *
 * <h2>Why the layout is computed rather than written down</h2>
 *
 * <p>The first version used fixed coordinates, and at the GUI scale the capture client actually
 * runs at -- 480x270 logical pixels, not the 640-odd the numbers assumed -- the title ran off the
 * right edge, the ids overprinted the sprites and the last row fell off the bottom. A screenshot
 * whose evidence is cropped away proves nothing, so every position below is derived from the
 * screen it is given and the number of rows it was handed.</p>
 */
final class PackProofScreen extends Screen {

    /** Nominal sprite size, in GUI pixels, before the zoom the layout picks. */
    private static final int SPRITE = 16;

    /** Where the rows start, leaving room for the two title lines and the column headers. */
    private static final int TOP = 46;

    /** Vertical room a row's three text lines need, whatever the sprites do. */
    private static final int TEXT_BLOCK = 34;

    private static final int TITLE_RGB = 0xFF7DD3FC;
    private static final int NAME_RGB = 0xFFE6EDF3;
    private static final int ID_RGB = 0xFF9AA7B4;
    private static final int GOOD_RGB = 0xFF86EFAC;
    private static final int FLAT_RGB = 0xFFFBBF24;
    private static final int STRIPE_RGB = 0x18FFFFFF;

    /**
     * A row of the comparison: the same drop name drawn twice.
     *
     * @param name   the drop name
     * @param before the stack the reel drew before anything was learned
     * @param after  the stack it draws now
     * @param note   what the row is showing, in a few words
     * @param good   whether this row is expected to differ (false for the control row)
     */
    private record Pair(String name, ItemStack before, ItemStack after, String note, boolean good) {
    }

    private final List<Pair> pairs = new ArrayList<>();

    PackProofScreen() {
        super(Component.literal("SkyPrism: Hypixel pack proof"));
    }

    /**
     * Records what a name drew before it was taught.
     *
     * <p>Copied, not referenced. {@link DropSymbols#iconForName} hands back a shared stack, and the
     * whole point of this screen is to still be holding the old one after the module has moved on
     * to a new one -- a reference the module later re-points would turn the comparison into two
     * pictures of the same thing.</p>
     *
     * @param name the drop name to photograph on both sides of the change
     */
    void recordBefore(String name) {
        ItemStack before = DropSymbols.iconForName(name);
        pairs.add(new Pair(name, before.isEmpty() ? ItemStack.EMPTY : before.copy(),
                ItemStack.EMPTY, "", true));
    }

    /** Fills in what each recorded name draws now, and what to say about it. */
    void recordAfter() {
        for (int i = 0; i < pairs.size(); i++) {
            Pair row = pairs.get(i);
            ItemStack after = DropSymbols.iconForName(row.name());
            boolean expectedToChange = !row.name().equals(HypixelPackProof.CONTROL_NAME);
            String note = expectedToChange
                    ? "now drawn from " + DropSymbols.sourceFor(row.name())
                    : "control: no art in this pack build, so both columns are correct and equal";
            pairs.set(i, new Pair(row.name(), row.before(),
                    after.isEmpty() ? ItemStack.EMPTY : after.copy(), note, expectedToChange));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                  float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF10141A);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        int rows = Math.max(1, pairs.size());
        int available = Math.max(TEXT_BLOCK, height - TOP - 4);
        int rowHeight = Math.max(TEXT_BLOCK, available / rows);

        // The sprites get whatever vertical room the text does not need, capped at 3x so a 16px
        // texture never blurs into abstraction, and floored at 1x so a very short screen still
        // draws something rather than nothing.
        zoom = Math.clamp((rowHeight - 6) / (float) SPRITE, 1.0f, 3.0f);
        int cell = Math.round(SPRITE * zoom);

        int beforeX = 10;
        int afterX = beforeX + cell + 10;
        int textX = afterX + cell + 12;

        int textRoom = Math.max(40, width - beforeX - 4);
        graphics.text(font, "SkyPrism: one drop, drawn twice", beforeX, 6, TITLE_RGB);
        fitText(graphics, "Left: the synthesised vanilla stack. Right: a stack carrying "
                + "Hypixel's item_model.", beforeX, 17, textRoom, NAME_RGB);
        fitText(graphics, "Both go through graphics.item(), the call the reel makes. Stacks are "
                + "CONSTRUCTED here, not captured from a live session.",
                beforeX, 28, textRoom, ID_RGB);

        graphics.text(font, "OLD", beforeX, TOP - 10, ID_RGB);
        graphics.text(font, "NEW", afterX, TOP - 10, ID_RGB);

        int y = TOP;
        boolean stripe = false;
        for (Pair row : pairs) {
            if (stripe) {
                graphics.fill(0, y - 2, width, y + rowHeight - 4, STRIPE_RGB);
            }
            stripe = !stripe;

            int spriteY = y + Math.max(0, (rowHeight - 4 - cell) / 2);
            sprite(graphics, row.before(), beforeX, spriteY);
            sprite(graphics, row.after(), afterX, spriteY);

            int textTop = y + Math.max(0, (rowHeight - 4 - TEXT_BLOCK) / 2);
            int room = Math.max(40, width - textX - 4);
            graphics.text(font, row.name(), textX, textTop, NAME_RGB);
            fitText(graphics, row.note(), textX, textTop + 11, room,
                    row.good() ? GOOD_RGB : FLAT_RGB);
            fitText(graphics, modelOf(row.after()), textX, textTop + 22, room, ID_RGB);

            y += rowHeight;
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The item_model on a stack, for the caption under a row.
     *
     * <p>The namespace is dropped because it is the same on every row and it is the part that
     * pushed the line off the edge; what distinguishes the rows is the path, and that is kept
     * whole.</p>
     */
    private static String modelOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "no stack";
        }
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null) {
            return "no item_model component: nothing for the pack to match on";
        }
        return model.getNamespace().equals(HypixelPackProof.NAMESPACE)
                ? "item_model = <" + HypixelPackProof.NAMESPACE + ">:" + model.getPath()
                : "item_model = " + model;
    }

    /**
     * Draws a line, shrinking it just enough to fit rather than letting it run off the edge.
     *
     * <p>The ids this screen exists to show are 50-odd characters long and the capture client's
     * GUI is 480 logical pixels wide, so at 1:1 the end of every Diana id -- which is the part
     * that names the item -- fell off the right-hand side. Truncating instead would have been
     * worse: the evidence is precisely that the id is the one in Hypixel's pack, and an id with
     * its tail replaced by an ellipsis is not evidence of anything.</p>
     *
     * @param maxWidth the room available, in GUI pixels
     */
    private void fitText(GuiGraphicsExtractor graphics, String text, int x, int y, int maxWidth,
                         int rgb) {
        int natural = font.width(text);
        if (natural <= maxWidth) {
            graphics.text(font, text, x, y, rgb);
            return;
        }
        float shrink = maxWidth / (float) natural;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, y);
            graphics.pose().scale(shrink, shrink);
            graphics.text(font, text, 0, 0, rgb);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** Draws one stack zoomed, or a marker where a stack could not be built. */
    private void sprite(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, float zoom) {
        if (stack == null || stack.isEmpty()) {
            graphics.text(font, "--", x, y + SPRITE, ID_RGB);
            return;
        }
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, y);
            graphics.pose().scale(zoom, zoom);
            graphics.item(stack, 0, 0);
        } finally {
            // Unconditional, and on the throwing path too: a pose left pushed would translate and
            // scale everything drawn after it, and blame would land on the wrong row.
            graphics.pose().popMatrix();
        }
    }

    /**
     * The zoom the current frame's layout picked.
     *
     * <p>A field rather than a parameter threaded through the row loop, and set at the top of
     * every {@code extractRenderState} before anything is drawn, so it can never be a stale value
     * from a frame at a different screen size.</p>
     */
    private float zoom = 1.0f;

    /** Convenience overload used by the layout, which has already chosen the zoom. */
    private void sprite(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        sprite(graphics, stack, x, y, zoom);
    }
}
