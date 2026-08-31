package com.skyprism.mc.command;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.core.level.PalettePresets;
import com.skyprism.mc.text.ComponentRewriter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A wall of level tags in their real colours, so a palette can be tuned by eye.
 *
 * <p><b>Why a screen and not a chat dump.</b> A gradient is a judgement about how hundreds
 * of colours sit <em>next to each other</em>. Printing twenty of them into chat answers a
 * different question - "is level 451 pink?" - and cannot show the thing that actually goes
 * wrong with a ramp, which is a flat stretch or a muddy crossing somewhere in the middle.
 * Seeing the whole run at once is the only way to catch that, and it is the difference
 * between a preview that is useful and one that is decorative.</p>
 *
 * <p><b>Every colour comes from the live palette.</b> The grid calls
 * {@link SkyPrismServices#level()}, which returns the palette the chat and TAB renderers
 * are holding, not a fresh one built from the same config. Those can differ - a runtime
 * change that has not been saved, a module that has not been invalidated - and a preview
 * that quietly disagrees with chat would be worse than no preview.</p>
 *
 * <p><b>Every cell is drawn by the shipped renderer, not by an imitation of it.</b> Each
 * tag is built as the component Hypixel sends - a dark grey {@code [}, the digits in
 * Hypixel's own tier colour, a dark grey {@code ]} - and then handed to the very method the
 * chat hook and the TAB memoiser call, {@link ComponentRewriter#recolourLevels}. Nothing in
 * this class decides what gets tinted. That matters because the screen used to decide, and
 * got it wrong: it painted {@code "[" + level + "]"} entirely in {@code colorFor(level)}
 * regardless of {@code recolourBrackets}, so with that setting off every previewed tag was a
 * tag the player would never see. The default has since flipped to on, which makes the old bug
 * invisible rather than absent -- exactly the kind of drift calling the pipeline prevents. A preview that re-implements the
 * pipeline is a second copy of the rules, free to drift the moment either side is touched;
 * calling the pipeline makes that drift impossible rather than merely fixed today.</p>
 *
 * <p>Three consequences fall out of that for free, each of which the old inline painting got
 * wrong. A level outside the configured detector range is drawn in Hypixel's colours, because
 * the locator declines to match it - so previewing 0..660 with {@code maxLevel = 400} shows
 * exactly where the recolour stops. With {@code levels.enabled} off, the whole grid shows what
 * the player will really see, which is Hypixel untouched. And the chroma underline marks only
 * cells the rewriter actually recoloured, since {@code recolourLevels} returns its argument by
 * identity when nothing matched.</p>
 *
 * <p><b>Chroma animates here exactly as it will in chat</b>, including the refresh-rate
 * quantisation from {@link Palettes#quantise}: the shimmer you tune is the shimmer you get,
 * at the same frame budget. Levels above the chroma threshold carry a faint underline so it
 * is obvious where the animated band begins even when a still frame is being looked at.
 * Nothing in this file knows what that threshold is: the header reads it off the live palette
 * and the underline is drawn from {@link LevelPalette#isChromatic(int)}, so a preview that
 * disagreed with the setting is not a bug that can be introduced here without deleting one of
 * those two calls. The default range is derived from it too - see {@link #defaultMaxLevel()},
 * which exists because a fixed 0..600 stopped being able to show the band at all once the
 * shipped threshold reached 600.</p>
 */
public final class LevelPreviewScreen extends Screen {

    /** Vertical space for the title and the three lines describing the current palette. */
    private static final int HEADER = 66;

    /**
     * Vertical space for the hint line and the close button, stacked rather than side by side.
     *
     * <p>They used to share one 30-pixel band, with the hint drawn from the left edge and the
     * Done button centred on top of it. That fits only while the hint is short enough to stop
     * before {@code width / 2 - 60}, which is a promise about the GUI width and the
     * translation at once -- and it broke at GUI scale 4, where the effective width is small
     * enough that "scroll or arrows to browse   levels 0-660" runs under the button and comes
     * out reading "...browse   leve". Two bands cannot collide however narrow the window gets.</p>
     */
    private static final int FOOTER = 44;

    /** Where the Done button sits inside the footer, below the hint line. */
    private static final int FOOTER_BUTTON_OFFSET = 20;

    /** Where the hint and the scroll percentage sit, above the button. */
    private static final int FOOTER_TEXT_OFFSET = 6;

    private static final int PAD = 10;
    private static final int CELL_GAP = 3;

    /** Pixels of scroll per notch of the wheel; one row is deliberately more than a notch. */
    private static final int SCROLL_STEP = 22;

    /**
     * The lowest top the default range ever has: Hypixel's thirteen vanilla tiers with room
     * above them for the levels people actually reach.
     *
     * <p>A floor rather than the answer. It is what the range comes out at whenever the
     * chroma threshold is low enough not to need more room, which was every shipped default
     * until the threshold moved to 600.</p>
     */
    private static final int BASE_MAX_LEVEL = 600;

    /**
     * Levels of chroma the default range guarantees above the configured threshold.
     *
     * <p>The screen exists partly to show what chroma looks like, and it can only do that if
     * the animated band is more than a sliver. At the widths this grid lays out at -- ten to
     * fifteen four-digit cells per row -- sixty levels is four to six full rows, enough that
     * the shimmer reads as a band with a visible edge rather than as one odd-looking cell in
     * the bottom corner.</p>
     *
     * <p>Without this the screen fails exactly where it is needed most. A fixed 0..600 range
     * against a threshold of 600 leaves one eligible level, in the last cell, on a row the
     * player has to scroll to the very end to see: the feature would be invisible in the one
     * screen built to demonstrate it.</p>
     */
    private static final int CHROMA_HEADROOM = 60;

    /**
     * Hard ceiling on the range {@link #defaultMaxLevel()} will derive.
     *
     * <p>{@code chromaMinLevel} is a free-entry field clamped only by {@code LEVEL_CEILING},
     * which is 999,999,999. Deriving a range from it unguarded would let one typed number ask
     * this screen for a billion cells. The number matches the span {@code /skyprism preview}
     * refuses beyond, so the widest range the screen opens itself with is a range a player
     * could also have asked for by hand.</p>
     */
    private static final int MAX_DERIVED_MAX_LEVEL = 20_000;

    /**
     * The colour the square brackets arrive in, and therefore the colour they keep whenever
     * {@code levels.recolourBrackets} is off.
     *
     * <p>Hypixel draws the level tag the way its own reward table does - a dim {@code [},
     * the number in the tier colour, a dim {@code ]} - and dim here is Mojang's
     * {@code dark_gray}. This is the one thing on the screen SkyPrism does not control and
     * so has to reproduce; every other colour in a cell is computed by the same code the
     * game will run. Getting it wrong makes the preview slightly optimistic about contrast,
     * which is much better than the old behaviour of not drawing the brackets' real colour
     * at all, but it is still an assumption, and it is the only one left in this file.</p>
     */
    private static final int HYPIXEL_BRACKET_RGB = 0x555555;

    /** Base colour for {@code graphics.text}; every run carries its own, so only the alpha is read. */
    private static final int OPAQUE = 0xFFFFFFFF;

    private final Screen parent;
    private final int minLevel;
    private final int maxLevel;

    private int scroll;
    private int maxScroll;
    private int columns = 1;
    private int cellWidth = 40;
    private int cellHeight = 15;

    /**
     * One rendered tag per previewed level, or null where it has not been built yet.
     *
     * <p>Running a component through the rewriter costs a flatten, a regex and a rebuild.
     * That is the price chat pays per message and TAB pays per entry, and it is the right
     * price for fidelity, but a full grid is around 150 cells and this screen redraws at the
     * frame rate. Cells that cannot change are therefore built once. Only levels the palette
     * calls chromatic are excluded, since those are the only ones whose colour depends on the
     * clock, and even they are quantised to {@code chromaUpdateHz} so a rebuild inside one
     * refresh step produces the identical component.</p>
     */
    private Cell[] cells;

    private LevelPalette cachedPalette;
    private boolean cachedEnabled;
    private boolean cachedRecolourBrackets;
    private int cachedDetectMin;
    private int cachedDetectMax;

    /**
     * One drawn tag and the three facts the grid needs about it.
     *
     * @param text       the component the pipeline produced, ready to draw
     * @param width      its rendered width, measured from the component rather than assumed
     * @param chromatic  true only when the rewriter recoloured this tag <em>and</em> the
     *                   palette animates it, which is exactly when the cell shimmers
     * @param accentRgb  the palette's colour for this level, for the animated-band underline
     */
    private record Cell(Component text, int width, boolean chromatic, int accentRgb) {
    }

    /**
     * @param parent   the screen to return to on close, may be null for "back to the game"
     * @param minLevel the lowest level to draw
     * @param maxLevel the highest level to draw, inclusive
     */
    public LevelPreviewScreen(Screen parent, int minLevel, int maxLevel) {
        super(Component.translatable("skyprism.hud.preview.title"));
        this.parent = parent;
        this.minLevel = Math.min(minLevel, maxLevel);
        this.maxLevel = Math.max(minLevel, maxLevel);
    }

    /**
     * The default range: from 0 up to whatever {@link #defaultMaxLevel()} says the live
     * chroma threshold needs, never less than {@link #BASE_MAX_LEVEL}.
     *
     * @param parent the screen to return to on close, may be null
     */
    public LevelPreviewScreen(Screen parent) {
        this(parent, 0, defaultMaxLevel());
    }

    /**
     * The top of the default range, high enough that the chroma band is actually on screen.
     *
     * <p>Derived from the live {@code chromaMinLevel} rather than hardcoded, because the two
     * numbers have to move together and only one of them is a setting. A player on 300 gets
     * the {@link #BASE_MAX_LEVEL} range they always had; the shipped 600 gets 660; a player
     * on 800 gets 860. Nothing here needs editing when the shipped default next moves.</p>
     *
     * <p>Read at construction rather than per frame, unlike everything else on this screen:
     * the range fixes the cell array and the scroll geometry, and a grid that resized itself
     * under the player's cursor because a config screen was open in the background would be
     * worse than a range that is one reopen out of date. The header and the underline still
     * track the live value, so a threshold changed while this screen is open shows up
     * immediately in the band -- only how far the grid runs past it waits.</p>
     *
     * <p>Deliberately not conditional on {@code chromaEnabled}. Somebody who opens this
     * screen, sees "chroma off", and turns it on from the config screen should find the band
     * already in range; a span that flipped with a toggle would also make the screenshot the
     * self test takes depend on which order the settings were staged in.</p>
     *
     * <p>Clamped at both ends: an unsanitised live config can hold a negative threshold, and
     * {@link #MAX_DERIVED_MAX_LEVEL} stops an enormous one asking for an enormous grid.</p>
     *
     * <p>Package-private rather than private so {@link SkyPrismCommands} can open the
     * argument-less {@code /skyprism preview} on the same range this constructor uses.
     * A command that hardcoded its own top would be the second copy of this rule, free to
     * drift from the threshold the moment either moved -- the same failure the grid avoids
     * by rendering through the shipped pipeline instead of imitating it.</p>
     *
     * @return the top of the default previewed range, inclusive
     */
    static int defaultMaxLevel() {
        int threshold = Math.max(SkyPrismConfig.LevelSettings.LEVEL_FLOOR,
                Math.min(MAX_DERIVED_MAX_LEVEL, settings().chromaMinLevel));
        return Math.min(MAX_DERIVED_MAX_LEVEL,
                Math.max(BASE_MAX_LEVEL, threshold + CHROMA_HEADROOM));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(
                        Component.translatable("skyprism.hud.preview.done"), b -> onClose())
                .bounds(width / 2 - 60, height - FOOTER + FOOTER_BUTTON_OFFSET, 120, 20)
                .build());
        layout();
    }

    /**
     * Recomputes the grid for the current window size.
     *
     * <p>The cell width is measured from the widest tag the range can produce rather than
     * assumed, because a width fixed for a three-digit top would clip the moment somebody
     * previewed to 1000, and a clipped digit in a colour picker is a bug that looks like a
     * palette problem. That is load-bearing now that the range is derived: a high enough
     * chroma threshold pushes the top of the default range into five digits on its own.</p>
     */
    private void layout() {
        cellWidth = font.width("[" + maxLevel + "]") + 12;
        cellHeight = font.lineHeight + 6;
        int usable = Math.max(cellWidth, width - PAD * 2);
        columns = Math.max(1, (usable + CELL_GAP) / (cellWidth + CELL_GAP));

        int rows = (count() + columns - 1) / columns;
        int contentHeight = rows * (cellHeight + CELL_GAP);
        int viewport = Math.max(0, height - HEADER - FOOTER);
        maxScroll = Math.max(0, contentHeight - viewport);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private int count() {
        return maxLevel - minLevel + 1;
    }

    /**
     * Scrolls so the row holding {@code level} is the first one under the header.
     *
     * <p>Exists because the default range opens on levels 0..89 and, at any sane chroma
     * threshold, none of those animate -- so the screen a caller opened to watch the shimmer
     * shows the one part of the ramp that is guaranteed to hold still. The self test hit
     * exactly that: it photographed two frames a second apart, asserted from the palette
     * object that the colour had moved, and wrote two byte-identical PNGs.</p>
     *
     * <p>Safe before {@link #init()} has run: {@link #layout()} re-clamps {@code scroll}
     * against the real {@code maxScroll} once the grid geometry is known, so an early call
     * that overshoots is corrected rather than left pointing past the end.</p>
     *
     * @param level the level to bring into view; clamped to the previewed range
     */
    /**
     * The lowest level this screen is drawing, inclusive.
     *
     * <p>Public so a caller can report the range it actually got rather than recompute it. The
     * self test does exactly that: it opens the screen with the argument-less constructor and
     * then says what came out, which is a fact about this instance instead of a second guess at
     * {@link #defaultMaxLevel()}'s arithmetic that could drift from it.</p>
     *
     * @return the first previewed level
     */
    public int minLevel() {
        return minLevel;
    }

    /**
     * The highest level this screen is drawing, inclusive.
     *
     * @return the last previewed level
     * @see #minLevel()
     */
    public int maxLevel() {
        return maxLevel;
    }

    public void scrollToLevel(int level) {
        int clamped = Math.max(minLevel, Math.min(maxLevel, level));
        int row = (clamped - minLevel) / Math.max(1, columns);
        scroll = Math.max(0, Math.min(row * (cellHeight + CELL_GAP), maxScroll));
    }

    @Override
    public void resize(int newWidth, int newHeight) {
        super.resize(newWidth, newHeight);
        layout();
    }

    /**
     * A flat dark panel instead of the vanilla blurred backdrop.
     *
     * <p>Colour judgement needs a constant, neutral, opaque ground. The default blurred
     * world behind a screen changes with where the player is standing, which would make the
     * same ramp look different in the Hub and in a cave - the exact failure this screen
     * exists to prevent.</p>
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xF0121418);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        SkyPrismConfig.LevelSettings levels = settings();
        LevelPalette palette = SkyPrismServices.level().palette();
        LevelTagLocator locator = levels.resolveLocator();
        long now = Palettes.quantise(System.currentTimeMillis(), levels);

        syncCache(palette, levels, locator);
        drawHeader(graphics, levels, palette, locator);

        int top = HEADER;
        int bottom = height - FOOTER;
        graphics.enableScissor(0, top, width, bottom);
        drawGrid(graphics, palette, locator, levels, now, top, bottom);
        graphics.disableScissor();

        drawFooter(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The live level settings, never null.
     *
     * <p>Read fresh every frame rather than captured at construction: the config screen and
     * {@code /skyprism} can both change these while this screen is open, and a header that
     * reported the values from when it opened would be the same kind of lie the grid used to
     * tell. Null-tolerant for the same reason {@link #describeSource} is - the live config is
     * mutable and a screen may be mid-edit.</p>
     */
    private static SkyPrismConfig.LevelSettings settings() {
        SkyPrismConfig config = SkyPrismServices.config().get();
        SkyPrismConfig.LevelSettings levels = config == null ? null : config.levels;
        return levels == null ? SkyPrismConfig.defaults().levels : levels;
    }

    /**
     * Throws away every built cell when something that changes what a cell looks like has
     * moved.
     *
     * <p>The five things checked are the whole of the rewriter's input besides the level and
     * the clock: which palette, whether the feature is on at all, whether brackets are
     * tinted, and the detector range that decides whether a tag matches. The palette is
     * compared by identity on purpose - the level module hands back a new instance when
     * config changes, and a deep comparison of two ramps would cost more per frame than the
     * cache saves.</p>
     */
    private void syncCache(LevelPalette palette, SkyPrismConfig.LevelSettings levels, LevelTagLocator locator) {
        if (cells != null
                && cachedPalette == palette
                && cachedEnabled == levels.enabled
                && cachedRecolourBrackets == levels.recolourBrackets
                && cachedDetectMin == locator.minLevel()
                && cachedDetectMax == locator.maxLevel()) {
            return;
        }
        cells = new Cell[count()];
        cachedPalette = palette;
        cachedEnabled = levels.enabled;
        cachedRecolourBrackets = levels.recolourBrackets;
        cachedDetectMin = locator.minLevel();
        cachedDetectMax = locator.maxLevel();
    }

    private void drawHeader(GuiGraphicsExtractor graphics, SkyPrismConfig.LevelSettings levels,
                            LevelPalette palette, LevelTagLocator locator) {
        graphics.fill(0, 0, width, HEADER - 1, 0xFF181C22);
        graphics.fill(0, HEADER - 1, width, HEADER, 0xFF2A313B);

        graphics.text(font, Component.translatable("skyprism.hud.preview.title"), PAD, 9, 0xFF7DD3FC);

        // The master switch, and the loudest thing on the screen when it is off: with it off
        // every cell below is Hypixel's own colouring, and without this line the grid would
        // look like a palette that had gone wrong rather than one that is simply not applied.
        if (!levels.enabled) {
            Component off = Component.translatableWithFallback("skyprism.hud.preview.disabled",
                    "recolour OFF - this is Hypixel's own colouring");
            graphics.text(font, off, Math.max(PAD, width - PAD - font.width(off)), 9, 0xFFF87171);
        }

        LevelColorMode mode = palette.mode();
        graphics.text(font, Component.translatable("skyprism.hud.preview.mode",
                modeName(mode), describeSource(mode, palette, levels)), PAD, 23, 0xFF9AA4B2);

        // Rate and vividness come from the palette's own clock where it has one, because that
        // clock is what colours the cells; only the refresh rate is read from config, and only
        // because that is what Palettes.quantise above is actually using.
        //
        // The threshold printed here is palette.chromaMinLevel(), the same field
        // palette.isChromatic(level) tests to decide which cells get the underline in
        // drawCell. One value, read twice, from the object doing the colouring -- so the
        // number in this line and the row the band starts on cannot disagree, whatever the
        // configured threshold is and whether or not it was changed since the screen opened.
        Component chroma = palette.chromaEnabled()
                ? Component.translatable("skyprism.hud.preview.chroma.on",
                        String.valueOf(palette.chromaMinLevel()),
                        trim(palette.chroma() == null
                                ? levels.chromaCyclesPerSecond
                                : palette.chroma().cyclesPerSecond()),
                        String.valueOf(levels.chromaUpdateHz))
                : Component.translatable("skyprism.hud.preview.chroma.off");
        graphics.text(font, chroma, PAD, 37, palette.chromaEnabled() ? 0xFFC084FC : 0xFF6B7280);

        Component surfaces = Component.translatable("skyprism.hud.preview.surfaces",
                onOff(levels.applyToChat),
                onOff(levels.applyToTabList),
                onOff(levels.applyToNameTags));
        graphics.text(font, surfaces, Math.max(PAD, width - PAD - font.width(surfaces)), 37, 0xFF9AA4B2);

        // The two settings that explain the shape of a cell rather than its colour, and the
        // two the screen used to hide: which parts of the tag get tinted, and which levels are
        // recognised as tags in the first place.
        Component brackets = levels.recolourBrackets
                ? Component.translatableWithFallback("skyprism.hud.preview.brackets.on",
                        "brackets tinted with the digits")
                : Component.translatableWithFallback("skyprism.hud.preview.brackets.off",
                        "brackets left as Hypixel drew them");
        graphics.text(font, brackets, PAD, 51, 0xFF9AA4B2);

        Component detects = Component.translatableWithFallback("skyprism.hud.preview.detects",
                "detects levels %s-%s",
                String.valueOf(locator.minLevel()), String.valueOf(locator.maxLevel()));
        graphics.text(font, detects, Math.max(PAD, width - PAD - font.width(detects)), 51, 0xFF9AA4B2);

        if (!SkyPrismServices.levelWired()) {
            Component warning = Component.translatable("skyprism.hud.preview.unwired");
            graphics.text(font, warning, Math.max(PAD, width - PAD - font.width(warning)), 23, 0xFFF5C451);
        }
    }

    /**
     * A colouring mode's user-facing name, falling back to the raw constant so a mode added
     * to the core stays readable before its key is written.
     */
    private static Component modeName(LevelColorMode mode) {
        return Component.translatableWithFallback(
                "skyprism.common.mode." + mode.name().toLowerCase(java.util.Locale.ROOT), mode.name());
    }

    /**
     * Where the live palette's colours are coming from.
     *
     * <p>Counts are taken from the <em>palette</em>, not from the config lists, for the same
     * reason the grid renders through the rewriter: the palette is what colours a cell, and
     * a count read from config would describe a table the screen is not showing whenever the
     * two have drifted apart. The preset <em>name</em> is the one thing only config knows -
     * a {@link com.skyprism.core.level.GradientRamp} does not remember which preset it came
     * from - so that alone is read from settings.</p>
     *
     * <p>Null-tolerant on purpose. This reads the <em>live</em> config, which a config screen
     * may be halfway through editing, and a preview that crashed the client because a list
     * was momentarily null would be a far worse bug than one that says "0 stops".</p>
     */
    private static Component describeSource(LevelColorMode mode, LevelPalette palette,
                                            SkyPrismConfig.LevelSettings s) {
        return switch (mode) {
            case GRADIENT -> SkyPrismConfig.LevelSettings.CUSTOM_PRESET.equals(s.gradientPreset)
                    ? Component.translatable("skyprism.hud.preview.source.gradient_custom",
                            String.valueOf(s.gradientPreset),
                            String.valueOf(palette.ramp() == null
                                    ? size(s.customStops)
                                    : palette.ramp().stops().size()))
                    : Component.translatable("skyprism.hud.preview.source.gradient",
                            String.valueOf(s.gradientPreset));
            case BRACKETS -> Component.translatable("skyprism.hud.preview.source.brackets",
                    String.valueOf(palette.table() == null
                            ? size(s.brackets)
                            : palette.table().brackets().size()));
            case VANILLA -> Component.translatable("skyprism.hud.preview.source.vanilla");
        };
    }

    private static int size(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static Component onOff(boolean value) {
        return Component.translatable(value ? "skyprism.common.on" : "skyprism.common.off");
    }

    private void drawGrid(GuiGraphicsExtractor graphics, LevelPalette palette, LevelTagLocator locator,
                          SkyPrismConfig.LevelSettings levels, long now, int top, int bottom) {
        int rowHeight = cellHeight + CELL_GAP;
        int gridLeft = PAD + Math.max(0, (width - PAD * 2 - (columns * (cellWidth + CELL_GAP) - CELL_GAP)) / 2);

        // Only the rows the viewport can show are touched. At the default range this is a
        // dozen rows instead of six hundred-odd cells, which keeps the screen at a flat cost
        // no matter how wide the previewed range gets.
        int firstRow = Math.max(0, scroll / rowHeight);
        int lastRow = (scroll + (bottom - top)) / rowHeight;

        for (int row = firstRow; row <= lastRow; row++) {
            int y = top - scroll + row * rowHeight;
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= count()) {
                    return;
                }
                int level = minLevel + index;
                int x = gridLeft + column * (cellWidth + CELL_GAP);
                drawCell(graphics, cellFor(palette, locator, levels, level, now), x, y);
            }
        }
    }

    /**
     * The cell for one level, built on first sight and kept unless the clock can change it.
     *
     * @see #cells
     */
    private Cell cellFor(LevelPalette palette, LevelTagLocator locator,
                         SkyPrismConfig.LevelSettings levels, int level, long now) {
        boolean animated = palette.isChromatic(level);
        int index = level - minLevel;
        if (!animated) {
            Cell cached = cells[index];
            if (cached != null) {
                return cached;
            }
        }
        Cell built = buildCell(palette, locator, levels, level, now);
        if (!animated) {
            cells[index] = built;
        }
        return built;
    }

    /**
     * Runs one level through the shipped recolour path and records what came back.
     *
     * <p>The source component is deliberately shaped like Hypixel's own: three runs, the
     * brackets dim and the digits in the tier colour Hypixel would have sent. That shape is
     * what makes the {@code recolourBrackets = false} case visible at all -- with a
     * single-run source there would be nothing for the rewriter to leave alone -- and it
     * makes an <em>un</em>recoloured tag, whether because the feature is off or because the
     * level falls outside the detector range, render as the thing the player would really be
     * looking at instead of vanishing into the background.</p>
     *
     * <p>{@code drawn != source} is the rewriter's documented signal that nothing matched.
     * Reading it here rather than re-deriving the range check keeps the last piece of
     * pipeline logic out of this class.</p>
     */
    private Cell buildCell(LevelPalette palette, LevelTagLocator locator,
                           SkyPrismConfig.LevelSettings levels, int level, long now) {
        Component source = Component.empty()
                .append(Component.literal("[").withColor(HYPIXEL_BRACKET_RGB))
                .append(Component.literal(Integer.toString(level))
                        .withColor(PalettePresets.vanillaBrackets().colorAt(level)))
                .append(Component.literal("]").withColor(HYPIXEL_BRACKET_RGB));

        Component drawn = levels.enabled
                ? ComponentRewriter.recolourLevels(source, palette, locator, levels.recolourBrackets, now)
                : source;
        boolean recoloured = drawn != source;

        return new Cell(drawn, font.width(drawn),
                recoloured && palette.isChromatic(level), palette.colorFor(level, now));
    }

    private void drawCell(GuiGraphicsExtractor graphics, Cell cell, int x, int y) {
        graphics.fill(x, y, x + cellWidth, y + cellHeight, cell.chromatic() ? 0xFF20242C : 0xFF1A1E25);
        if (cell.chromatic()) {
            // A one-pixel rule in the level's own colour marks the animated band, so the
            // threshold is visible in a screenshot as well as in motion.
            graphics.fill(x, y + cellHeight - 1, x + cellWidth, y + cellHeight,
                    0xFF000000 | cell.accentRgb());
        }

        int textX = x + (cellWidth - cell.width()) / 2;
        // Every run in the component carries its own colour, so this base is read for its
        // alpha alone; passing it opaque is what stops a styled run being drawn translucent.
        graphics.text(font, cell.text(), textX, y + 3, OPAQUE, true);
    }

    private void drawFooter(GuiGraphicsExtractor graphics) {
        int top = height - FOOTER;
        graphics.fill(0, top, width, height, 0xFF181C22);
        graphics.fill(0, top, width, top + 1, 0xFF2A313B);

        Component hint = Component.translatable("skyprism.hud.preview.hint",
                String.valueOf(minLevel), String.valueOf(maxLevel));
        graphics.text(font, hint, PAD, top + FOOTER_TEXT_OFFSET, 0xFF6B7280);

        if (maxScroll > 0) {
            Component position = Component.translatable("skyprism.hud.preview.scroll",
                    String.valueOf(100 * scroll / Math.max(1, maxScroll)));
            graphics.text(font, position, width - PAD - font.width(position),
                    top + FOOTER_TEXT_OFFSET, 0xFF6B7280);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = clampScroll(scroll - (int) Math.round(scrollY * SCROLL_STEP));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int page = Math.max(1, height - HEADER - FOOTER);
        switch (event.key()) {
            case GLFW.GLFW_KEY_DOWN -> {
                scroll = clampScroll(scroll + cellHeight + CELL_GAP);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                scroll = clampScroll(scroll - cellHeight - CELL_GAP);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                scroll = clampScroll(scroll + page);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                scroll = clampScroll(scroll - page);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                scroll = 0;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                scroll = maxScroll;
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, maxScroll));
    }

    /**
     * Keeps the world running underneath. Chroma is driven by the wall clock so it would
     * animate either way, but pausing a singleplayer world to look at a colour ramp is a
     * side effect nobody asked for.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private static String trim(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
