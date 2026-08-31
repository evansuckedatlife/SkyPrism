package com.skyprism.mc.command;

import com.skyprism.core.config.HudAnchor;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.MythologicalCreature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Drag the slot machine where you want it.
 *
 * <p><b>Why dragging rather than two sliders.</b> The HUD position is stored as screen
 * fractions relative to an anchor, which is the right storage format - it survives a
 * resolution change - and the wrong editing format, because nobody can picture what
 * {@code x = 0.37} looks like against {@code anchor = MIDDLE_RIGHT}. Dragging writes those
 * numbers by putting the box where the eye wants it, which is the only interaction that
 * makes the anchor's existence invisible to the person using it.</p>
 *
 * <p><b>Snapping.</b> The box snaps when an edge or its centre comes within a few pixels of
 * a screen edge, the screen centre line, or the safe margin - and draws the guide it
 * snapped to, because a snap you cannot see reads as the drag being broken. Holding Alt
 * suppresses it for fine placement.</p>
 *
 * <p><b>What is drawn.</b> A representative slot machine at the configured reel count and
 * scale, with sample symbols, so the box has the size and shape the real HUD will have. On
 * open, the real HUD's own preview roll is also kicked off through
 * {@link SkyPrismServices#hud()} when that module is wired, so the live widget spins
 * wherever the game chooses to draw it - but this screen never depends on that having
 * happened. A placement tool that only worked once another module was finished would be
 * useless during exactly the period it is most needed.</p>
 */
public final class HudPlacementScreen extends Screen {

    /** How close, in pixels, an edge has to come before it snaps. */
    private static final int SNAP_RADIUS = 6;

    /** The margin from the screen edge that vanilla HUD elements sit at. */
    private static final int SAFE_MARGIN = 4;

    /**
     * Height of the footer panel.
     *
     * <p>Tall enough for two stacked bands: the two-line readout at the top and the button
     * row beneath it. See {@link #init()} for what went wrong when they shared one.</p>
     */
    private static final int FOOTER = 56;

    /** Where the button row starts inside the footer, below both readout lines. */
    private static final int BUTTON_ROW_OFFSET = 30;

    /** Width of the four fixed-label buttons. The anchor button is measured instead. */
    private static final int BUTTON_WIDTH = 48;

    /** Gap between two buttons in the row. */
    private static final int BUTTON_GAP = 4;

    /** Floor on the anchor button, so a missing translation still gives a clickable target. */
    private static final int MIN_ANCHOR_BUTTON = 100;

    /** Ceiling on the anchor button, so a runaway translation cannot push the row off-screen. */
    private static final int MAX_ANCHOR_BUTTON = 200;

    private final Screen parent;
    private final SkyPrismConfig config;

    private boolean dragging;
    private double grabOffsetX;
    private double grabOffsetY;

    /** Guides hit by the last snap, in screen pixels; -1 for "did not snap". */
    private int guideX = -1;
    private int guideY = -1;

    private Button anchorButton;

    /**
     * @param parent the screen to return to on close, may be null
     */
    public HudPlacementScreen(Screen parent) {
        super(Component.translatable("skyprism.hud.placement.title"));
        this.parent = parent;
        this.config = SkyPrismServices.config().get();
    }

    /**
     * Lays the footer out in two bands: the readout on top, the buttons underneath.
     *
     * <p>Both used to share one band. The readout was drawn at {@code top + 8} and the button
     * row placed at exactly the same {@code y}, so on any window narrow enough to matter the
     * anchor button sat on top of the position line and the hint under it -- the readout came
     * out as "x 0.5000" followed by fragments of itself poking between the buttons. The
     * buttons are drawn after the footer text, so the text lost.</p>
     *
     * <p>The anchor button is measured rather than assumed, too. It was a fixed 100 pixels
     * while "Anchor: Middle center" needs about 126, and a vanilla button centres its label
     * without clipping it, so the label overflowed symmetrically past both edges -- the
     * leading "A" fell outside the button on the left and the tail ran under the neighbouring
     * one on the right. Sizing it from the widest label any anchor can produce also means a
     * translation with longer names cannot reintroduce the bug.</p>
     */
    @Override
    protected void init() {
        int y = height - FOOTER + BUTTON_ROW_OFFSET;

        int anchorWidth = Math.min(MAX_ANCHOR_BUTTON, Math.max(MIN_ANCHOR_BUTTON, widestAnchorLabel() + 10));
        int row = anchorWidth + 4 * (BUTTON_WIDTH + BUTTON_GAP);
        int x = width / 2 - row / 2;

        anchorButton = addRenderableWidget(Button.builder(anchorLabel(), b -> cycleAnchor())
                .bounds(x, y, anchorWidth, 20)
                .build());
        x += anchorWidth + BUTTON_GAP;

        addRenderableWidget(Button.builder(
                        Component.translatable("skyprism.hud.placement.scale_down"), b -> nudgeScale(-0.05))
                .bounds(x, y, BUTTON_WIDTH, 20)
                .build());
        x += BUTTON_WIDTH + BUTTON_GAP;

        addRenderableWidget(Button.builder(
                        Component.translatable("skyprism.hud.placement.scale_up"), b -> nudgeScale(0.05))
                .bounds(x, y, BUTTON_WIDTH, 20)
                .build());
        x += BUTTON_WIDTH + BUTTON_GAP;

        addRenderableWidget(Button.builder(
                        Component.translatable("skyprism.hud.placement.reset"), b -> reset())
                .bounds(x, y, BUTTON_WIDTH, 20)
                .build());
        x += BUTTON_WIDTH + BUTTON_GAP;

        addRenderableWidget(Button.builder(
                        Component.translatable("skyprism.hud.placement.done"), b -> onClose())
                .bounds(x, y, BUTTON_WIDTH, 20)
                .build());

        SkyPrismServices.Hud hud = SkyPrismServices.hud();
        if (hud != null) {
            hud.previewRoll();
        }
    }

    /**
     * The width of the longest label {@link #cycleAnchor} can put on the anchor button.
     *
     * <p>Every anchor, not just the current one, because the button does not resize when it
     * is clicked -- a button sized to "Top left" would overflow the moment somebody cycled it
     * round to "Middle center".</p>
     */
    private int widestAnchorLabel() {
        int widest = 0;
        for (HudAnchor candidate : HudAnchor.values()) {
            Component label = Component.translatable("skyprism.hud.placement.anchor",
                    Component.translatableWithFallback(
                            "skyprism.common.anchor." + candidate.name().toLowerCase(java.util.Locale.ROOT),
                            candidate.name()));
            widest = Math.max(widest, font.width(label));
        }
        return widest;
    }

    private Component anchorLabel() {
        return Component.translatable("skyprism.hud.placement.anchor", anchorName());
    }

    /**
     * The current anchor's user-facing name.
     *
     * <p>Looked up with a fallback on the raw constant, so an anchor added to the core
     * renders as {@code BOTTOM_CENTER} rather than as a missing key until somebody writes
     * its translation.</p>
     */
    private Component anchorName() {
        HudAnchor current = anchor();
        return Component.translatableWithFallback(
                "skyprism.common.anchor." + current.name().toLowerCase(java.util.Locale.ROOT),
                current.name());
    }

    private void cycleAnchor() {
        HudAnchor[] all = HudAnchor.values();
        HudAnchor current = config.hud.anchor == null ? HudAnchor.TOP_CENTER : config.hud.anchor;

        // The box must not jump when the anchor changes: the anchor only says which of the
        // box's own points the stored fraction refers to, so re-solving the fraction against
        // the same on-screen rectangle keeps it exactly where it was.
        double[] corner = topLeft();
        config.hud.anchor = all[(current.ordinal() + 1) % all.length];
        setTopLeft(corner[0], corner[1]);

        anchorButton.setMessage(anchorLabel());
    }

    private void nudgeScale(double delta) {
        double next = config.hud.scale + delta;
        config.hud.scale = Math.max(SkyPrismConfig.HudSettings.MIN_SCALE,
                Math.min(SkyPrismConfig.HudSettings.MAX_SCALE, Math.round(next * 100.0) / 100.0));
    }

    private void reset() {
        SkyPrismConfig.HudSettings defaults = SkyPrismConfig.defaults().hud;
        config.hud.x = defaults.x;
        config.hud.y = defaults.y;
        config.hud.scale = defaults.scale;
        config.hud.anchor = defaults.anchor;
        anchorButton.setMessage(anchorLabel());
    }

    // ======================================================================
    //  Geometry
    // ======================================================================

    private int boxWidth() {
        SkyPrismServices.Hud hud = SkyPrismServices.hud();
        int base = hud != null ? hud.previewSize()[0] : SlotMachineSketch.width(config.diana.reelCount);
        return (int) Math.round(base * config.hud.scale);
    }

    private int boxHeight() {
        SkyPrismServices.Hud hud = SkyPrismServices.hud();
        int base = hud != null ? hud.previewSize()[1] : SlotMachineSketch.height(config.hud.showCreatureName);
        return (int) Math.round(base * config.hud.scale);
    }

    private HudAnchor anchor() {
        return config.hud.anchor == null ? HudAnchor.TOP_CENTER : config.hud.anchor;
    }

    /** @return the box's top-left corner in screen pixels, from the stored fractions */
    private double[] topLeft() {
        return anchor().topLeft(width, height, boxWidth(), boxHeight(), config.hud.x, config.hud.y);
    }

    /**
     * The inverse of {@link HudAnchor#topLeft}: writes the fractions that would place the
     * box at this corner.
     *
     * <p>The core defines {@code left = screenWidth * x - widgetWidth * xFraction}, so
     * {@code x = (left + widgetWidth * xFraction) / screenWidth}. Deriving it rather than
     * inventing a second placement rule is what stops this screen and the HUD renderer
     * drifting apart.</p>
     */
    private void setTopLeft(double left, double top) {
        HudAnchor a = anchor();
        config.hud.x = clamp01((left + boxWidth() * a.xFraction()) / Math.max(1, width));
        config.hud.y = clamp01((top + boxHeight() * a.yFraction()) / Math.max(1, height));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    // ======================================================================
    //  Input
    // ======================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double[] corner = topLeft();
        boolean inside = event.x() >= corner[0] && event.x() <= corner[0] + boxWidth()
                && event.y() >= corner[1] && event.y() <= corner[1] + boxHeight();
        if (!inside) {
            return false;
        }
        dragging = true;
        grabOffsetX = event.x() - corner[0];
        grabOffsetY = event.y() - corner[1];
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!dragging) {
            return super.mouseDragged(event, dragX, dragY);
        }
        boolean fine = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        applyPosition(event.x() - grabOffsetX, event.y() - grabOffsetY, !fine);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            guideX = -1;
            guideY = -1;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int step = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
        double[] corner = topLeft();
        switch (event.key()) {
            case GLFW.GLFW_KEY_LEFT -> {
                applyPosition(corner[0] - step, corner[1], false);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                applyPosition(corner[0] + step, corner[1], false);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                applyPosition(corner[0], corner[1] - step, false);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                applyPosition(corner[0], corner[1] + step, false);
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    /**
     * Moves the box, optionally snapping, and keeps it on screen.
     *
     * <p>Snapping is decided on the box's three interesting horizontal positions - its left
     * edge, its centre and its right edge - against the three interesting screen positions,
     * and likewise vertically. Doing it in pixels and only then converting to fractions is
     * what makes a snapped box land on the exact pixel rather than on a fraction that
     * rounds to one pixel off.</p>
     */
    private void applyPosition(double left, double top, boolean snap) {
        int boxW = boxWidth();
        int boxH = boxHeight();
        guideX = -1;
        guideY = -1;

        if (snap) {
            int[] screenX = {SAFE_MARGIN, width / 2, width - SAFE_MARGIN};
            int[] boxX = {0, boxW / 2, boxW};
            for (int target : screenX) {
                for (int offset : boxX) {
                    if (Math.abs(left + offset - target) <= SNAP_RADIUS) {
                        left = target - offset;
                        guideX = target;
                        break;
                    }
                }
                if (guideX >= 0) {
                    break;
                }
            }

            int[] screenY = {SAFE_MARGIN, height / 2, height - FOOTER - SAFE_MARGIN};
            int[] boxY = {0, boxH / 2, boxH};
            for (int target : screenY) {
                for (int offset : boxY) {
                    if (Math.abs(top + offset - target) <= SNAP_RADIUS) {
                        top = target - offset;
                        guideY = target;
                        break;
                    }
                }
                if (guideY >= 0) {
                    break;
                }
            }
        }

        left = Math.max(-boxW / 2.0, Math.min(width - boxW / 2.0, left));
        top = Math.max(-boxH / 2.0, Math.min(height - boxH / 2.0, top));
        setTopLeft(left, top);
    }

    // ======================================================================
    //  Drawing
    // ======================================================================

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Only a light dim: the point is to place the widget against the game as it really
        // looks, so the world has to stay visible behind it.
        graphics.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        double[] corner = topLeft();
        int left = (int) Math.round(corner[0]);
        int top = (int) Math.round(corner[1]);
        int boxW = boxWidth();
        int boxH = boxHeight();

        drawThirds(graphics);
        if (guideX >= 0) {
            graphics.fill(guideX, 0, guideX + 1, height, 0xCC7DD3FC);
        }
        if (guideY >= 0) {
            graphics.fill(0, guideY, width, guideY + 1, 0xCC7DD3FC);
        }

        SlotMachineSketch.draw(graphics, font, left, top, boxW, boxH,
                config.diana.reelCount, config.hud.showCreatureName,
                config.hud.drawBackground, config.hud.backgroundOpacity);

        graphics.outline(left - 1, top - 1, boxW + 2, boxH + 2, dragging ? 0xFF7DD3FC : 0x88FFFFFF);

        drawFooter(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Faint rule-of-thirds lines, the reference people actually place HUDs against. */
    private void drawThirds(GuiGraphicsExtractor graphics) {
        int alpha = 0x22FFFFFF;
        graphics.fill(width / 3, 0, width / 3 + 1, height, alpha);
        graphics.fill(2 * width / 3, 0, 2 * width / 3 + 1, height, alpha);
        graphics.fill(0, height / 3, width, height / 3 + 1, alpha);
        graphics.fill(0, 2 * height / 3, width, 2 * height / 3 + 1, alpha);
    }

    private void drawFooter(GuiGraphicsExtractor graphics) {
        int top = height - FOOTER;
        graphics.fill(0, top, width, height, 0xE0181C22);
        graphics.fill(0, top, width, top + 1, 0xFF2A313B);

        Component position = Component.translatable("skyprism.hud.placement.position",
                String.format(java.util.Locale.ROOT, "%.4f", config.hud.x),
                String.format(java.util.Locale.ROOT, "%.4f", config.hud.y),
                String.format(java.util.Locale.ROOT, "%.2f", config.hud.scale),
                anchorName());
        // Both lines sit above BUTTON_ROW_OFFSET, which is what keeps them out from under
        // the button row -- the buttons are drawn after this method and would overpaint them.
        graphics.text(font, position, 10, top + 6, 0xFFE6EDF3);

        Component hint = Component.translatable(SkyPrismServices.hud() == null
                ? "skyprism.hud.placement.hint.sketch"
                : "skyprism.hud.placement.hint");
        graphics.text(font, hint, 10, top + 17, 0xFF9AA4B2);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Writes the new placement to disk on the way out. Positioning a HUD and then losing it
     * to a crash is the one failure this screen must not have, and the file write is far too
     * cheap to justify making the user press a second button for it.
     */
    @Override
    public void onClose() {
        try {
            SkyPrismServices.config().save();
        } catch (RuntimeException failed) {
            // Never take the client down over a cosmetic setting; the status command will
            // show the position is unsaved next time it is asked.
        }
        SkyPrismServices.level().invalidate();
        minecraft.setScreenAndShow(parent);
    }

    /**
     * A stand-in drawing of the slot machine.
     *
     * <p>It is a sketch on purpose: matching the real HUD pixel for pixel would duplicate
     * another module's rendering code and then rot the moment that module changed. What has
     * to be right is the <em>footprint</em> - the same reel count, the same proportions, the
     * same optional creature caption - because that is all placement depends on. When the
     * HUD module is registered its own {@code previewSize()} supplies the footprint and this
     * only fills it in.</p>
     */
    private static final class SlotMachineSketch {

        private static final int REEL_WIDTH = 54;
        private static final int REEL_GAP = 4;
        private static final int PADDING = 6;
        private static final int REEL_HEIGHT = 30;
        private static final int CAPTION_HEIGHT = 12;

        private SlotMachineSketch() {
        }

        static int width(int reels) {
            int n = Math.max(1, reels);
            return PADDING * 2 + n * REEL_WIDTH + (n - 1) * REEL_GAP;
        }

        static int height(boolean caption) {
            return PADDING * 2 + REEL_HEIGHT + (caption ? CAPTION_HEIGHT : 0);
        }

        static void draw(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                         int left, int top, int boxW, int boxH,
                         int reels, boolean caption, boolean background, double opacity) {
            if (background) {
                int alpha = (int) Math.round(Math.max(0.0, Math.min(1.0, opacity)) * 255.0);
                graphics.fill(left, top, left + boxW, top + boxH, (alpha << 24) | 0x101318);
            }

            int n = Math.max(1, reels);
            double scale = boxW / (double) width(n);
            int reelW = (int) Math.round(REEL_WIDTH * scale);
            int gap = (int) Math.round(REEL_GAP * scale);
            int pad = (int) Math.round(PADDING * scale);
            int reelH = (int) Math.round(REEL_HEIGHT * scale);

            for (int i = 0; i < n; i++) {
                int x = left + pad + i * (reelW + gap);
                int y = top + pad;
                graphics.fill(x, y, x + reelW, y + reelH, 0xFF0B0E13);
                graphics.outline(x, y, reelW, reelH, 0xFF3A4250);

                String symbol = SAMPLE_SYMBOLS[i % SAMPLE_SYMBOLS.length];
                String clipped = font.plainSubstrByWidth(symbol, Math.max(1, reelW - 6));
                graphics.text(font, clipped,
                        x + Math.max(3, (reelW - font.width(clipped)) / 2),
                        y + Math.max(2, (reelH - font.lineHeight) / 2),
                        0xFFE6EDF3);
            }

            if (caption) {
                MythologicalCreature shown = MythologicalCreature.MINOS_INQUISITOR;
                Component name = Component.translatableWithFallback(
                        "skyprism.common.creature." + shown.name().toLowerCase(java.util.Locale.ROOT),
                        shown.displayName());
                graphics.text(font, name,
                        left + Math.max(2, (boxW - font.width(name)) / 2),
                        top + boxH - Math.round((float) (CAPTION_HEIGHT * scale)) - 1,
                        0xFFF87171);
            }
        }

        /**
         * Filler for the reels when the HUD module is absent. Hypixel's own drop names, left
         * untranslated on purpose: they are sample data standing in for text the server will
         * send, not copy this mod writes.
         */
        private static final String[] SAMPLE_SYMBOLS = {
            "Chimera I", "40,000", "Griffin Feather", "Minos Relic", "Ancient Claw",
        };
    }
}
