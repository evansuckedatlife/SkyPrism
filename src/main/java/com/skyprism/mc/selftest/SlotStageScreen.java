package com.skyprism.mc.selftest;

import com.skyprism.mc.hud.SlotMachineHud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A backdrop that draws the <em>real</em> slot machine, so it can be photographed without a world.
 *
 * <h2>Why this screen has to exist</h2>
 *
 * <p>{@link SlotMachineHud} is a Fabric {@code HudElement}. The HUD is only drawn while a world is
 * being rendered, so at the title screen -- where every other step of the self test runs -- the
 * widget draws nothing at all, and it never has. The obvious substitute, {@code HudPlacementScreen},
 * deliberately draws a <em>sketch</em>: its own documentation explains that matching the HUD pixel
 * for pixel would duplicate another module and then rot. That is the right call for a placement
 * tool and exactly the wrong one for a self test, because a sketch cannot show a bug in the thing
 * it is standing in for.</p>
 *
 * <p>So this screen calls {@link SlotMachineHud#extractRenderState} directly, with the same
 * {@link GuiGraphicsExtractor} a HUD frame would get and the client real
 * {@link DeltaTracker}. Every pixel below the caption comes from the shipped render path -- the
 * reel strip, the scissor clipping, the fade curve, the jackpot pulse -- and not from a stand-in
 * written to look like it. The widget reads {@code graphics.guiWidth()} and
 * {@code guiHeight()} for its anchor, which are the screen dimensions here just as they are
 * in-game, so its placement maths is exercised too.</p>
 *
 * <h2>The backdrop</h2>
 *
 * <p>A flat fill would hide the widget own translucent backing, which is the setting most likely
 * to be wrong and the hardest to notice. The checkerboard makes {@code hud.backgroundOpacity}
 * legible in a still frame: if the squares show through the panel, the alpha is doing something.</p>
 */
final class SlotStageScreen extends Screen {

    /** Side of one checker square, in GUI pixels. */
    private static final int CHECKER = 24;

    private String caption = "";

    SlotStageScreen() {
        super(Component.literal("SkyPrism self test"));
    }

    /**
     * Labels the frame, so a screenshot says which phase of the roll it was meant to catch.
     *
     * @param text the caption; null is treated as empty
     */
    void caption(String text) {
        this.caption = text == null ? "" : text;
    }

    /** Never pause: a paused client would stop ticking and the script would stall. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The script owns the screen stack; a stray Escape must not take the stage away from it. */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                  float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF10141A);
        for (int y = 0; y < height; y += CHECKER) {
            int offset = ((y / CHECKER) % 2) * CHECKER;
            for (int x = offset; x < width; x += CHECKER * 2) {
                graphics.fill(x, y, Math.min(x + CHECKER, width), Math.min(y + CHECKER, height),
                        0xFF161C25);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        graphics.text(font, "SkyPrism self test - live SlotMachineHud.extractRenderState",
                8, 8, 0xFF7DD3FC);
        if (!caption.isEmpty()) {
            graphics.text(font, caption, 8, 20, 0xFFE6EDF3);
        }

        DeltaTracker delta = minecraft == null ? DeltaTracker.ZERO : minecraft.getDeltaTracker();
        SlotMachineHud.get().extractRenderState(graphics, delta);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
