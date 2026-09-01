package com.skyprism.mc.selftest;

import com.skyprism.core.config.HudAnchor;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.Reel;
import com.skyprism.core.diana.RollState;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.mc.command.HudPlacementScreen;
import com.skyprism.mc.command.LevelPreviewScreen;
import com.skyprism.mc.command.Palettes;
import com.skyprism.mc.config.ConfigManager;
import com.skyprism.mc.diana.DianaController;
import com.skyprism.mc.gui.ConfigGui;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.mc.hud.LootMachine;
import com.skyprism.mc.hud.SlotMachineHud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import org.lwjgl.glfw.GLFW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Drives a real client through every SkyPrism screen and photographs the result.
 *
 * <h2>The problem</h2>
 *
 * <p>SkyPrism has 604 passing core tests, four mixins that apply on both Minecraft nodes, and a
 * dev client that boots. None of that had ever caused a mixin body to execute or the slot machine
 * to draw a frame, because everything the mod shows needs either Hypixel or a Diana mayor term,
 * and neither can be summoned on demand. Every visual claim in the project was therefore an
 * inference from source. This class turns them into files somebody can open.</p>
 *
 * <h2>Why the title screen</h2>
 *
 * <p>Three of the four things worth looking at are {@link Screen}s, and a screen needs no world:
 * the settings GUI, the palette preview and the placement screen all lay out and animate at the
 * title screen exactly as they would in game. The fourth, the HUD widget, normally needs a world
 * because the HUD is only drawn while one is rendering -- so {@link SlotStageScreen} calls the
 * shipped {@code extractRenderState} directly instead. That covers the render path without
 * needing a save on disk. See the {@code in-world HUD capture} step for why a world is not
 * created anyway.</p>
 *
 * <h2>How the script runs</h2>
 *
 * <p>As a queue of small operations polled from {@code END_CLIENT_TICK}. Each returns "done" or
 * "call me again next tick", so waiting for a screen to lay out, for a reel to lock or for a GPU
 * readback to land are all the same kind of step and none of them blocks the client. A tick
 * watchdog ends the run whatever happens, because the one unacceptable outcome is a client that
 * never exits and a launcher that never returns.</p>
 *
 * <h2>What it refuses to touch</h2>
 *
 * <p>The settings it needs -- chroma on, HUD centred and doubled, generous reel timings -- are
 * staged with {@link ConfigManager#refresh()}, which republishes the palette, the locator and
 * every cache <em>without writing the file</em>. Running the self test does not rewrite a
 * developer {@code config.json}, and no step calls {@code save()}.</p>
 *
 * <h2>Cost to a normal player</h2>
 *
 * <p>Zero, and not by inspection: {@link com.skyprism.SkyPrismClient} guards its one call into
 * this class behind a single {@code Boolean.getBoolean} on {@value #ENABLE_PROPERTY}. With the
 * property absent that branch is one false test; {@link #arm()} is never invoked, so no class in
 * this package is ever loaded, no listener is registered and nothing is allocated.</p>
 */
public final class SelfTest {

    /** Set {@code -D}{@value}{@code =true} to run the self test. Read once, by SkyPrismClient. */
    public static final String ENABLE_PROPERTY = "skyprism.selftest";

    /** Optional {@code -D}{@value}{@code =<dir>} for where the screenshots and reports go. */
    public static final String OUT_PROPERTY = "skyprism.selftest.out";

    /**
     * Where captures land when {@value #OUT_PROPERTY} is not set: {@code skyprism-selftest/} beside
     * the running game.
     *
     * <p>This used to be one developer's absolute scratch directory, which was meaningless on any
     * other machine and leaked a username into shipped source. A path relative to the run directory
     * is correct everywhere and needs no configuration; anyone wanting the captures somewhere
     * specific sets {@value #OUT_PROPERTY}, which is how the tooling has always driven it anyway.</p>
     */
    private static final String DEFAULT_OUT_DIR = "skyprism-selftest";

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/selftest");

    /** Ticks per second, so the waits below can be written in seconds. */
    private static final int TPS = 20;

    /** How long to wait for the game to finish loading before giving up on readiness. */
    private static final int READY_TIMEOUT_TICKS = 90 * TPS;

    /** Settling time once the title screen is up: fonts, atlases and the panorama fade. */
    private static final int TITLE_SETTLE_TICKS = 3 * TPS;

    /** How long a screen gets to lay out before it is photographed. */
    private static final int LAYOUT_TICKS = TPS;

    /** The gap between the two palette frames. One second, as the brief asked. */
    private static final int CHROMA_GAP_TICKS = TPS;

    /** How long a GPU readback gets before the shot is called a failure. */
    private static final int SHOT_TIMEOUT_TICKS = 5 * TPS;

    /** Ceiling on the whole run. Whatever else happens, the client is asked to stop by here. */
    private static final int WATCHDOG_TICKS = 240 * TPS;

    /** The level sampled to prove chroma actually moved between the two palette frames. */
    private static final int CHROMA_SAMPLE_LEVEL = 600;

    /**
     * How many levels of still ramp to leave above the chroma threshold in the two frames.
     *
     * <p>Two rows at the usual ten columns. The point of the pair of shots is the contrast:
     * the band above the threshold is a different colour in each frame, the band below it is
     * identical in both, and a reader can see the boundary rather than take it on trust.</p>
     */
    private static final int CHROMA_LEAD_IN = 20;

    /**
     * The GUI scale the two palette frames are photographed at.
     *
     * <p>The rest of the run is shot at whatever the client picked, which on a 1920x1080
     * framebuffer is Mojang's auto scale of 4. That is right for the slot machine -- it is one
     * widget and it wants to be large enough to read the drop names under the reels -- and wrong
     * for this screen. The preview is a grid, and the thing it exists to show is the sweep of the
     * ramp from level 0 to the top of the range. At scale 4 the grid fits about twelve columns of
     * ten rows, so the published screenshot was roughly a hundred and ten consecutive levels out
     * of six hundred: a flat-looking slice, and a reader could not see a gradient in it because
     * over a hundred levels there barely is one.</p>
     *
     * <p>Halving the scale doubles the columns and doubles the rows, so about four times as many
     * cells land in the same 1920x1080 frame -- nearly the whole range at once, the chroma band
     * included. The text halves with it, which is the trade: the header stays legible at full
     * size, and the cells are colour samples whose number only has to be readable when somebody
     * opens the image, not in the README's inline thumbnail.</p>
     *
     * <p>Applied to the framebuffer through {@code Options.guiScale()} rather than to a pose
     * stack, because a pose-stack scale would shrink the drawing and leave the layout believing
     * it still had the old width -- the same twelve columns, drawn smaller. Only a real scale
     * change makes {@code layout()} choose more of them.</p>
     */
    private static final int PALETTE_GUI_SCALE = 2;

    /**
     * The window height the palette frames are photographed at, in real pixels.
     *
     * <p>Scale 2 on a 1080p window gets close to the whole ramp and then stops about five rows
     * short, which is the one framing this screenshot cannot afford. The grid draws every level
     * as its own cell, so the default range is 661 of them; at the twenty-three columns a
     * 960-wide layout chooses that is twenty-nine rows, and 1080 minus the header and footer
     * leaves room for twenty-four. Something has to fall off the frame, and both ends are
     * load-bearing: the low levels are where the palette is recognisably Hypixel's, and the top
     * is where the chroma band lives. The published 1.0.2 shot lost the bottom -- it opens at
     * level 115, so the greys, whites and yellows that make the ramp familiar are simply not in
     * the picture, and a reader is asked to believe the first fifth of it.</p>
     *
     * <p>Five extra rows is ninety scaled pixels, so the window is grown for the two palette
     * frames and given straight back. Height only: the width fixes the column count and the
     * whole point is that the columns stay where they are. 1440 leaves the range fitting with a
     * row or two to spare rather than exactly, so a font metric that differs by a pixel on
     * another machine cannot push the last row back off the bottom.</p>
     *
     * <p>Growing the window rather than dropping to GUI scale 1 keeps the cell text at the size
     * it already was. Scale 1 would fit the range twice over and produce a palette nobody can
     * read the numbers in, which defeats a screenshot whose subject is which level changed
     * colour where.</p>
     *
     * <p>1340 is the measured fit rather than a round number. The grid comes out at 29 rows of
     * 18 scaled pixels, so it needs 522 under a 66-pixel header and above a 44-pixel footer:
     * 632 scaled, 1264 real. The extra 76 leaves a margin for a font whose line height differs
     * by a pixel somewhere else without leaving a band of empty panel under the last row.</p>
     */
    private static final int PALETTE_WINDOW_HEIGHT = 1340;

    /**
     * How far under the requested height the borrowed window may land and still count.
     *
     * <p>Windows computes a client area from a window rect and loses a pixel doing it, so the
     * request never lands exactly. The number is small on purpose: it has to absorb the frame
     * arithmetic and nothing else, because the case worth failing on is a resize the window
     * manager ignored outright, and that one comes back hundreds of pixels short.</p>
     */
    private static final int WINDOW_HEIGHT_SLACK = 8;

    /** Ordinary drops: nothing rare, so {@code SlotRoll.jackpot()} stays false. */
    private static final List<LootDrop> ORDINARY_DROPS = List.of(
            new LootDrop("Griffin Feather", "9", 1, false),
            new LootDrop("Ancient Claw", "a", 2, false),
            new LootDrop("Coins", "6", 24_500, false));

    /**
     * Jackpot drops: the Daedalus Stick carries the rare banner that latches the flourish.
     *
     * <p>It also carries a Magic Find reading, because the reveal draws one and a fixture with no
     * reading cannot photograph it. 240% with the percent sign is the dungeon form the player
     * quoted -- {@code §r§b(+240% Magic Find!)} -- which is the shape a naive suffix matcher
     * misses, so it is the one worth putting in front of the camera.</p>
     */
    private static final List<LootDrop> JACKPOT_DROPS = List.of(
            new LootDrop("Griffin Feather", "9", 1, false),
            new LootDrop("Daedalus Stick", "5", 1, true,
                    new LootDrop.MagicFind(240, true)),
            new LootDrop("Crown of Greed", "6", 1, false));

    /**
     * The same jackpot with the Magic Find taken off the prize, and nothing else changed.
     *
     * <p>Deliberately the identical three items in the identical order: the pair of frames these
     * two lists produce differ in exactly one thing, so the claim they are photographed to
     * support -- that the panel does not move between a roll that reports the stat and one that
     * does not -- is readable off the two PNGs rather than taken on trust. Most rare drops really
     * are this case; Hypixel omits the stat on pet drops with no roll, on Diana treasure digs and
     * on plenty of ordinary banners, and the widget has to draw those without a placeholder, a
     * zero, or an empty row where the figure would have been.</p>
     */
    private static final List<LootDrop> JACKPOT_DROPS_NO_MAGIC_FIND = List.of(
            new LootDrop("Griffin Feather", "9", 1, false),
            new LootDrop("Daedalus Stick", "5", 1, true),
            new LootDrop("Crown of Greed", "6", 1, false));

    /**
     * Drops for the three non-Diana demonstration rolls.
     *
     * <p>Real loot from each activity rather than Diana's, because the point of those frames is
     * that nothing about the widget is Diana-shaped any more -- reusing Griffin Feathers would
     * have proved only that the caption changed.</p>
     */
    private static final List<LootDrop> SLAYER_DROPS = List.of(
            new LootDrop("Judgement Core", "5", 1, true),
            new LootDrop("Null Atom", "9", 2, false),
            new LootDrop("Coins", "6", 62_100, false));

    /** A dungeon reward chest: the classic Obsidian-chest payout. */
    private static final List<LootDrop> CHEST_DROPS = List.of(
            new LootDrop("Necron's Handle", "6", 1, true),
            new LootDrop("Wither Catalyst", "5", 1, false),
            // "Wither Essence" rather than the bare "Essence" the fixture used to carry: essence
            // ships in eight typed variants and Hypixel always names the type, so the generic word
            // was a drop nobody can receive and lost its sprite row with the rest of the fakes.
            new LootDrop("Wither Essence", "d", 32, false));

    /**
     * A Lord Jawbus kill, which is where Rare Drop banners on the water come from.
     *
     * <p>Every name here is off the published Lord Jawbus table, and the rarity colour of each is
     * the one NEU records, so the frame is a photograph of a payout a player can actually receive:
     * Silver Magmafish 16-32x and one Magma Lord Fragment are the guaranteed pair, and the
     * Radioactive Vial is the 0.5% roll that raises the banner.
     *
     * <p>It did not used to be. The fixture paid a Lava Shell and a Magma Urchin, neither of which
     * is on Jawbus's table at all -- the urchin is Crimson Isle miniboss loot -- so the published
     * fishing screenshot showed two items from the wrong content beside one that was right. That
     * is the exact complaint this pass exists to answer, and it was in the README.</p>
     */
    private static final List<LootDrop> FISHING_DROPS = List.of(
            new LootDrop("Radioactive Vial", "d", 1, true),
            new LootDrop("Magma Lord Fragment", "6", 1, false),
            new LootDrop("Silver Magmafish", "5", 24, false));

    /**
     * The five demonstration reels, keyed by the frame each one is captured into.
     *
     * <p>Exposed for one reason. These names are the loot the published screenshots actually
     * draw, and until 2026-08-30 nothing checked them: {@code DropSymbolsMcTest} walked
     * {@code LootSourceRegistry}'s jackpot lists and the wiki snapshot, neither of which knows
     * this class exists. Three of the names here -- Essence, Lava Shell and Magma Urchin -- were
     * therefore absent from {@code drop_symbols.json} while every test stayed green, and the
     * fishing frame shipped with two identical fallback chests in it. Handing the fixtures out
     * lets that test cover the exact reels the frames show, so the next missing row fails a build
     * instead of being found by looking at a PNG.
     *
     * <p>Read-only and allocation-free at the call site: every list is already immutable and the
     * map is built from them, so nothing here is on the render path.
     *
     * @return frame name to the drops that frame's reel is loaded with
     */
    public static Map<String, List<LootDrop>> demonstrationRolls() {
        return Map.of(
                "05-slot-spinning", ORDINARY_DROPS,
                "08-jackpot-act-one-spinning", JACKPOT_DROPS,
                "30-source-slayer-boss", SLAYER_DROPS,
                "31-source-dungeon-chest", CHEST_DROPS,
                "32-source-rare-fish", FISHING_DROPS);
    }

    /** Guards against a second {@code arm()}; the property is read once, but belt and braces. */
    private static boolean armed;

    // ---------------------------------------------------------------- run state

    private final Path outDir;
    private final Deque<Op> program = new ArrayDeque<>();
    private final List<Step> steps = new ArrayList<>();
    private final long startedAtMillis = System.currentTimeMillis();

    private int ticks;
    private boolean finished;

    /** The stage screen, once opened, so captions can be set between shots. */
    private SlotStageScreen stage;

    /** The palette preview, once opened, so it can be scrolled onto the animated band. */
    private LevelPreviewScreen preview;

    /**
     * The client's own GUI scale, saved while the palette frames borrow a smaller one.
     *
     * <p>{@link Integer#MIN_VALUE} means "nothing borrowed", which is the state every step
     * outside the two palette shots runs in. Kept so the restore is the value that was actually
     * there rather than a guess: the shipped {@code options.txt} says 0, meaning auto, and a
     * restore that wrote a literal 4 would quietly convert a player's auto setting into a fixed
     * one on any machine whose display picks something else.</p>
     */
    private int borrowedGuiScaleFrom = Integer.MIN_VALUE;

    /**
     * The window height the palette frames borrow a taller one from, in real pixels.
     *
     * <p>{@link Integer#MIN_VALUE} means "nothing borrowed", exactly like
     * {@link #borrowedGuiScaleFrom}. Only the height is remembered because only the height is
     * changed; the width is left alone so the palette frames stay the same width as every other
     * shot in the set.</p>
     */
    private int borrowedWindowHeightFrom = Integer.MIN_VALUE;

    /** Whether {@link #borrowWindowHeight()} had to un-maximise the window to resize it. */
    private boolean borrowedFromMaximised;

    /**
     * How many pixels short of the request the borrowed window actually came out.
     *
     * <p>Windows hands back a client area a pixel under what was asked for -- 1440 requested,
     * 1439 drawn, and the PNG is the 1439. Harmless for the borrow, which is deliberately
     * oversized, but the give-back has to land on the exact height the run started at or every
     * screenshot after the palette pair comes out a pixel shorter than the ones it replaces.
     * Measured once, on the real framebuffer, and added to the restore request.</p>
     */
    private int borrowedHeightShortfall;

    /**
     * The side-by-side pack comparison, created before anything is taught.
     *
     * <p>Created early on purpose: it has to be holding the fallback stacks before
     * {@link HypixelPackProof#teach()} runs, or the "before" column would be a second copy of the
     * "after" one.</p>
     */
    private PackProofScreen packProof;

    /** Wall clock at which the current demonstration roll was started. */
    private long rollStartedAt;

    /** Act one capture instants for the current roll, derived from the staged reel timings. */
    private long midSpinAt;
    private long oneLockedAt;
    private long allLockedAt;

    /**
     * Act two capture instants, all measured from the same roll start as act one.
     *
     * <p>Zero on an ordinary roll, which never reaches the second act. They are derived rather
     * than written down for the same reason act one's are: the staged durations below are widened
     * from the shipped defaults so each phase can be photographed, and a capture point written as
     * a literal would silently drift into the wrong phase the moment somebody retunes them.</p>
     *
     * <p>The wash gets three of them rather than one because it is the phase where two things
     * now happen at once -- the gold arriving and the reels already turning under it -- and a
     * single still of a half-gold machine cannot tell an overlap from a sequence.</p>
     */
    private long goldWashEarlyAt;
    private long goldWashMidAt;
    private long goldWashLateAt;
    private long jackpotSpinAt;
    private long firstMatchAt;
    private long thirdMatchAt;
    private long jackpotHoldAt;

    /** The chroma samples either side of the one-second gap. */
    private int chromaA;
    private int chromaB;

    /**
     * The widget's footprint on the jackpot roll that reported a Magic Find.
     *
     * <p>Kept so the roll that reports none can be compared against it. A panel that grew a row
     * for the figure would be a bug the two screenshots show but no assertion catches, and the
     * two frames are taken half a minute apart with a whole roll between them, so the number has
     * to be carried rather than re-derived at the moment of the comparison.</p>
     */
    private int[] magicFindPanel;

    private SelfTest(Path outDir) {
        this.outDir = outDir;
    }

    /**
     * Registers the one listener that runs the whole script.
     *
     * <p>Called from {@link com.skyprism.SkyPrismClient} inside the property guard, and from
     * nowhere else. It is the only public entry point in this package.</p>
     */
    public static void arm() {
        if (armed) {
            return;
        }
        armed = true;

        SelfTest run = new SelfTest(resolveOutDir());
        run.buildProgram();
        LOGGER.info("SkyPrism self test armed; {} operations, writing to {}",
                run.program.size(), run.outDir);
        ClientTickEvents.END_CLIENT_TICK.register(client -> run.onEndTick());
    }

    /**
     * The output directory, with a fallback so a wrong default cannot lose a run.
     *
     * @return a directory that exists, or the briefed path if even the fallback failed
     */
    private static Path resolveOutDir() {
        String configured = System.getProperty(OUT_PROPERTY);
        Path wanted = Paths.get(configured == null || configured.isBlank()
                ? DEFAULT_OUT_DIR : configured.trim());
        try {
            Files.createDirectories(wanted);
            return wanted;
        } catch (Throwable unusable) {
            LOGGER.warn("SkyPrism self test cannot use {} ({}); falling back beside the game",
                    wanted, unusable.toString());
        }
        try {
            Minecraft client = Minecraft.getInstance();
            Path fallback = (client == null ? Paths.get(".") : client.gameDirectory.toPath())
                    .resolve("skyprism-selftest");
            Files.createDirectories(fallback);
            return fallback;
        } catch (Throwable alsoUnusable) {
            LOGGER.error("SkyPrism self test has nowhere to write", alsoUnusable);
            return wanted;
        }
    }

    // ---------------------------------------------------------------- the script

    private void buildProgram() {
        awaitUntil("client reached the title screen", () -> {
            Minecraft client = Minecraft.getInstance();
            return client != null && client.isGameLoadFinished();
        }, READY_TIMEOUT_TICKS);
        delay(TITLE_SETTLE_TICKS);

        call("stage the settings this run needs", this::stageSettings);
        call("bind default item components so sprites can draw without a world",
                ItemComponents::bindDefaults);

        // --- 1. the YACL settings screen, opened the way ModMenu opens it -------------------
        call("open the YACL settings screen", () -> {
            if (!ConfigGui.available()) {
                throw new Skipped("YACL is not loaded; ConfigGui.available() is false, which is "
                        + "the same answer ModMenu would get");
            }
            Screen screen = ConfigGui.open(null);
            if (screen == null) {
                throw new Skipped("ConfigGui.open returned null despite reporting available");
            }
            show(screen);
            return "ConfigGui.open(null) gave " + screen.getClass().getName()
                    + ", the identical call ModMenuIntegration hands to ModMenu";
        });
        delay(LAYOUT_TICKS);
        shot("screenshot: YACL settings screen", "01-yacl-settings.png",
                ConfigGui::available);

        // --- 2. the palette preview, twice, to catch chroma moving --------------------------
        // Scale first, then open: the grid measures its columns in init(), so a screen opened at
        // the old scale and rescaled afterwards would be laid out twice for no reason.
        call("borrow window height " + PALETTE_WINDOW_HEIGHT + " for the palette frames",
                this::borrowWindowHeight);
        delay(LAYOUT_TICKS);
        // The resize is a request to the window manager, and a window manager is free to ignore
        // it -- Windows ignores one aimed at a maximised window and reports success. That is not
        // a hypothetical: it happened, and the only trace was a palette PNG that was still 1080
        // tall and still opened at level 115. Measure the framebuffer that the shot will actually
        // be read off, so a resize that did not take stops the run instead of quietly shipping
        // the framing this change exists to fix.
        call("the window really is taller now", () -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                throw new Skipped("no client to measure");
            }
            Window window = client.getWindow();
            int height = window.getHeight();
            borrowedHeightShortfall = Math.max(0, PALETTE_WINDOW_HEIGHT - height);

            // A pixel or two under the request is the window manager's frame arithmetic and is
            // fine -- the height was chosen with 76 to spare. Anything more means the resize did
            // not really happen, which is the failure this step exists to catch: it looks
            // identical to success everywhere except in the height of the PNG.
            if (borrowedHeightShortfall > WINDOW_HEIGHT_SLACK) {
                throw new IllegalStateException("asked for " + PALETTE_WINDOW_HEIGHT
                        + " real pixels of height and got " + window.getWidth() + "x" + height
                        + "; the palette frame would be cropped to part of the ramp again");
            }
            return "framebuffer is " + window.getWidth() + "x" + height + " real pixels ("
                    + borrowedHeightShortfall + " under the request, which the give-back adds"
                    + " back), so the grid has room for every row";
        });
        call("borrow GUI scale " + PALETTE_GUI_SCALE + " for the palette frames", this::borrowGuiScale);
        delay(LAYOUT_TICKS);
        call("open the level palette preview", () -> {
            preview = new LevelPreviewScreen(null);
            show(preview);
            return "LevelPreviewScreen(null) draws " + preview.minLevel() + ".."
                    + preview.maxLevel() + " -- the range it derived from the live chroma"
                    + " threshold, not a literal -- through SkyPrismServices.level().palette(),"
                    + " the same instance chat and TAB colour with";
        });
        delay(LAYOUT_TICKS);
        call("scroll the preview onto the chroma threshold", () -> {
            SkyPrismConfig live = ConfigManager.get().config();
            int target = Math.max(0, live.levels.chromaMinLevel - CHROMA_LEAD_IN);
            preview.scrollToLevel(target);
            return "asked for level " + target + ", so the grid straddles the chroma"
                    + " threshold at " + live.levels.chromaMinLevel + ". Without this the"
                    + " screen sits on levels 0..89, every one of them below the threshold and"
                    + " therefore static, and the two frames below came out byte-identical"
                    + " while the assertion beneath them still passed. On the borrowed window"
                    + " height the whole range fits, so scrollToLevel clamps this to the top and"
                    + " the frame holds level " + preview.minLevel() + " through "
                    + preview.maxLevel() + " at once; the request is kept because it is what"
                    + " keeps the chroma band on screen if the grid ever needs scrolling again";
        });
        // The readback hands back the frame that was already on the GPU, so a shot taken in
        // the same tick as the scroll photographs the grid as it was *before* it moved. On the
        // run that added the scroll, frame A came out byte-identical to the unscrolled run and
        // only frame B showed the chroma band -- which made the pair prove scrolling rather
        // than shimmer. Let a frame land on the new position first.
        delay(LAYOUT_TICKS);
        call("sample the animated colour, frame A", () -> {
            chromaA = sampleChroma();
            return "level " + CHROMA_SAMPLE_LEVEL + " is " + hex(chromaA);
        });
        shot("screenshot: level palette, frame A", "02-level-palette-a.png", null);
        delay(CHROMA_GAP_TICKS);
        call("sample the animated colour, frame B", () -> {
            chromaB = sampleChroma();
            return "level " + CHROMA_SAMPLE_LEVEL + " is " + hex(chromaB);
        });
        shot("screenshot: level palette, frame B one second later", "03-level-palette-b.png", null);
        call("animated chroma moved between the two frames", () -> {
            if (chromaA == chromaB) {
                throw new IllegalStateException("level " + CHROMA_SAMPLE_LEVEL + " was "
                        + hex(chromaA) + " in both frames; the two PNGs will look identical");
            }
            return "level " + CHROMA_SAMPLE_LEVEL + " went " + hex(chromaA) + " -> " + hex(chromaB);
        });
        call("the two palette PNGs are genuinely different images", () -> {
            // The step above proves the palette moved. It does not prove the screenshots did,
            // and on the first real run it did not: the preview was parked on levels 0..89,
            // every one below the chroma threshold, and 02 and 03 came out byte-identical at
            // 37,394 bytes each while that assertion passed and claimed otherwise. Compare
            // the artefacts, not the model that produced them.
            Path frameA = outDir.resolve("02-level-palette-a.png");
            Path frameB = outDir.resolve("03-level-palette-b.png");
            if (!Files.isRegularFile(frameA) || !Files.isRegularFile(frameB)) {
                throw new Skipped("one of the two frames was not written, so there is nothing"
                        + " to compare");
            }
            byte[] first = Files.readAllBytes(frameA);
            byte[] second = Files.readAllBytes(frameB);
            if (Arrays.equals(first, second)) {
                throw new IllegalStateException("02 and 03 are byte-identical at "
                        + first.length + " bytes, so the pair does not show the shimmer"
                        + " moving whatever the palette reports");
            }
            return "the two files differ (" + first.length + " and " + second.length
                    + " bytes), so the animated band really is a different colour in each";
        });
        // Everything from here on is the slot machine and the settings screen, both of which want
        // the client's own scale back.
        call("give the GUI scale back", this::restoreGuiScale);
        call("give the window height back", this::restoreWindowHeight);
        delay(LAYOUT_TICKS);

        // --- 3. the HUD placement screen ----------------------------------------------------
        call("open the HUD placement screen", () -> {
            show(new HudPlacementScreen(null));
            return "HudPlacementScreen.init() calls SkyPrismServices.hud().previewRoll(), so a "
                    + "roll is running behind it; the box it draws is its own documented sketch, "
                    + "sized from the real widget previewSize()";
        });
        delay(LAYOUT_TICKS / 2);
        shot("screenshot: HUD placement screen", "04-hud-placement.png", null);

        // --- 4. the real widget, across an ordinary roll ------------------------------------
        call("open the slot machine stage", () -> {
            stage = new SlotStageScreen();
            show(stage);
            SlotMachineHud.get().previewRoll();
            return "SlotStageScreen calls SlotMachineHud.extractRenderState directly; previewRoll"
                    + "() has primed the element cached SlotRoll reference";
        });
        call("start an ordinary roll", () -> startRoll(false));
        caption("Ordinary roll: three reels spinning");
        awaitRoll(() -> midSpinAt);
        shot("screenshot: slot machine mid-spin", "05-slot-spinning.png", this::stageReady);
        caption("Ordinary roll: first reel locked, two still spinning");
        awaitRoll(() -> oneLockedAt);
        shot("screenshot: slot machine, one reel locked", "06-slot-one-reel-locked.png",
                this::stageReady);
        caption("Ordinary roll: all three reels locked on the drops received");
        awaitRoll(() -> allLockedAt);
        shot("screenshot: slot machine, all reels locked", "07-slot-all-reels-locked.png",
                this::stageReady);
        call("the ordinary roll did not claim a jackpot", () -> {
            if (DianaController.get().roll().jackpot()) {
                throw new IllegalStateException("jackpot() is true after a roll with no rare drop");
            }
            return "SlotRoll.jackpot() is false, as it should be with no rare drop";
        });

        // --- 5. the same widget on a jackpot, both acts -------------------------------------
        //
        // Eight frames, because the point of the rework is a sequence and a sequence cannot be
        // photographed once. The first two are act one on a roll that is going to pay out, and
        // they exist to prove a negative: that a rare drop captured at offer time changes nothing
        // about the ordinary spin or the settled result.
        //
        // The other six are act two, and three of them are inside its opening wash. That is the
        // beat the whole rework turns on: the reels break loose on the first instant of the act
        // and the gold arrives over the top of them, so the two things a slot machine does are
        // simultaneous rather than consecutive. One frame cannot show that -- a still of a
        // half-gold machine is equally consistent with a machine that finished turning gold and
        // only then began to move -- so the wash is photographed early, in the middle and late,
        // and each shot asserts both halves of the claim before it is taken.
        call("start a jackpot roll", () -> startRoll(true));

        caption("JACKPOT roll, act one: an ordinary spin -- the rare drop is already captured");
        awaitRoll(() -> midSpinAt);
        call("act one of a jackpot roll is still an ordinary spin",
                () -> requireState(RollState.SPINNING, RollState.LOCKING));
        shot("screenshot: jackpot roll, act one spinning", "08-jackpot-act-one-spinning.png",
                this::stageReady);

        caption("JACKPOT roll, act one settled: the real drops, no gold anywhere");
        awaitRoll(() -> allLockedAt);
        call("the settled result of a jackpot roll is untouched", () -> {
            String where = requireState(RollState.SETTLED);
            SlotRoll roll = DianaController.get().roll();
            if (!roll.jackpot()) {
                throw new IllegalStateException("jackpot() is false after a rare drop was offered");
            }
            if (roll.inJackpotSequence()) {
                throw new IllegalStateException(
                        "the jackpot sequence has already begun during the settle phase");
            }
            if (roll.jackpotIntroProgress() != 0.0d) {
                throw new IllegalStateException("jackpotIntroProgress is "
                        + roll.jackpotIntroProgress() + " while the roll is merely settled, so "
                        + "the HUD would be drawing gold over act one");
            }
            for (Reel reel : roll.reels()) {
                if (!reel.locked()) {
                    throw new IllegalStateException("column " + reel.index()
                            + " is still spinning during the settle phase");
                }
            }
            return where + "; jackpot() is true but inJackpotSequence() is false and "
                    + "jackpotIntroProgress() is 0, so every gold term in the HUD is multiplied "
                    + "by zero and this frame is the ordinary result";
        });
        shot("screenshot: jackpot roll, settled with no gold",
                "09-jackpot-settled-no-gold.png", this::stageReady);

        caption("JACKPOT act two opens: reels ALREADY turning, gold has barely started");
        awaitRoll(() -> goldWashEarlyAt);
        call("the reels broke loose on the first instant of act two",
                () -> requireWashOverMotion(0.05d, 0.45d));
        shot("screenshot: jackpot intro, early -- moving reels, barely any gold",
                "10a-jackpot-intro-early.png", this::stageReady);

        caption("JACKPOT act two: gold half in, reels still turning under it");
        awaitRoll(() -> goldWashMidAt);
        call("the wash is half in and still nothing has landed",
                () -> requireWashOverMotion(0.30d, 0.70d));
        shot("screenshot: jackpot intro, middle -- gold half in over moving reels",
                "10b-jackpot-intro-mid.png", this::stageReady);

        caption("JACKPOT act two: gold nearly full, reels STILL turning");
        awaitRoll(() -> goldWashLateAt);
        call("the wash is nearly complete and the reels have still not landed",
                () -> requireWashOverMotion(0.55d, 0.95d));
        shot("screenshot: jackpot intro, late -- gold nearly full, reels still turning",
                "10c-jackpot-intro-late.png", this::stageReady);

        caption("JACKPOT act two: all three reels spinning up again");
        awaitRoll(() -> jackpotSpinAt);
        call("every column is moving again with none landed",
                () -> requireState(RollState.JACKPOT_SPIN) + "; "
                        + requireThreeOfAKind(0));
        shot("screenshot: jackpot re-spin", "11-jackpot-respin.png", this::stageReady);

        caption("JACKPOT act two: first reel lands");
        awaitRoll(() -> firstMatchAt);
        call("the first column has landed on the jackpot symbol",
                () -> requireState(RollState.JACKPOT_LOCK) + "; " + requireThreeOfAKind(1));
        shot("screenshot: jackpot, first reel landed", "12-jackpot-first-match.png",
                this::stageReady);

        caption("JACKPOT act two: third reel lands -- three of a kind");
        awaitRoll(() -> thirdMatchAt);
        call("all three columns show the same item", () -> {
            String where = requireState(RollState.JACKPOT_LOCK, RollState.JACKPOT_HOLD);
            int reels = ConfigManager.get().config().diana.reelCount;
            return where + "; " + requireThreeOfAKind(reels);
        });
        shot("screenshot: jackpot, three of a kind", "13-jackpot-third-match.png",
                this::stageReady);

        caption("JACKPOT held: the prize, and the Magic Find it was rolled at");
        awaitRoll(() -> jackpotHoldAt);
        call("the celebration holds on the three of a kind", () -> {
            String where = requireState(RollState.JACKPOT_HOLD);
            int reels = ConfigManager.get().config().diana.reelCount;
            return where + "; " + requireThreeOfAKind(reels);
        });
        call("the held prize carries the Magic Find it was rolled at", () -> {
            LootDrop prize = DianaController.get().roll().jackpotSymbol();
            if (prize == null) {
                throw new IllegalStateException("there is no jackpot symbol to report a stat for");
            }
            if (!prize.magicFindReported()) {
                throw new IllegalStateException("the jackpot symbol reports no Magic Find, so the"
                        + " frame about to be taken cannot be showing one");
            }
            magicFindPanel = SlotMachineHud.get().previewSize();
            return "jackpotSymbol() is " + prize.itemName() + " carrying " + prize.magicFindText()
                    + ", which is the drop all three columns converged on; the widget's footprint"
                    + " on this roll is " + magicFindPanel[0] + "x" + magicFindPanel[1];
        });
        shot("screenshot: jackpot hold, WITH the prize's Magic Find", "14-jackpot-hold.png",
                this::stageReady);

        // --- 5a. the same hold with nothing to report ---------------------------------------
        //
        // The pair is the point. Hypixel omits Magic Find from most rare-drop banners, so the
        // widget spends most of its life in this case, and the two ways to get it wrong are both
        // invisible in a single screenshot: inventing a "+0%" for a stat nobody sent, and
        // reserving a row for a figure that is not there so the panel shifts between rolls. Two
        // frames of the same prize with and without the reading show both at once.
        call("start a second jackpot roll whose prize reports no Magic Find",
                () -> startRoll(true, JACKPOT_DROPS_NO_MAGIC_FIND));
        caption("JACKPOT held: the same prize, with no Magic Find reported for it");
        awaitRoll(() -> jackpotHoldAt);
        call("a prize with no reported Magic Find is drawn without one", () -> {
            String where = requireState(RollState.JACKPOT_HOLD);
            LootDrop prize = DianaController.get().roll().jackpotSymbol();
            if (prize == null) {
                throw new IllegalStateException("there is no jackpot symbol on the second roll");
            }
            if (prize.magicFindReported()) {
                throw new IllegalStateException("the second roll's prize reports "
                        + prize.magicFindText() + ", so this frame does not document the absent "
                        + "case it was staged for");
            }
            int[] panel = SlotMachineHud.get().previewSize();
            if (magicFindPanel != null && !Arrays.equals(panel, magicFindPanel)) {
                throw new IllegalStateException("the widget is " + panel[0] + "x" + panel[1]
                        + " on a roll with no Magic Find and " + magicFindPanel[0] + "x"
                        + magicFindPanel[1] + " on one with it, so the panel resizes between them");
            }
            return where + "; jackpotSymbol() is " + prize.itemName()
                    + " and magicFindReported() is false, so the strip shows nothing at all rather"
                    + " than a zero; the footprint is " + panel[0] + "x" + panel[1]
                    + ", identical to the roll that did report one";
        });
        shot("screenshot: jackpot hold, NO Magic Find reported",
                "15-jackpot-hold-no-magic-find.png", this::stageReady);

        // --- 5b. Hypixel's own server resource pack -----------------------------------------
        //
        // The point of the whole change. SkyBlock now pushes an official resource pack that
        // dresses its items through the vanilla minecraft:item_model component, and a stack the
        // mod synthesised from a chat name carries no such component -- so it could never match,
        // whatever the pack contained. These steps load that real pack, photograph a reel drawing
        // the old synthesised stacks, teach the module Hypixel's own ids, and photograph the same
        // reel again.
        //
        // The stacks are CONSTRUCTED, not captured: a dev client has no SkyBlock server to capture
        // from. That proves the render and item_model half end to end and leaves the capture half
        // -- IconCapture matching an inventory slot to a parsed drop -- unexercised. Every line
        // these steps write says so.
        call("Hypixel's server resource pack is loaded and its Diana assets resolve",
                HypixelPackProof::packReport);
        call("before: every demonstration name is on the synthesised vanilla fallback",
                HypixelPackProof::requireUntaught);
        call("hold on to what each name draws today, for the side-by-side frame", () -> {
            packProof = new PackProofScreen();
            for (HypixelPackProof.Row row : HypixelPackProof.rows()) {
                packProof.recordBefore(row.dropName());
            }
            packProof.recordBefore(HypixelPackProof.CONTROL_NAME);
            return "copied " + (HypixelPackProof.rows().size() + 1) + " fallback stacks, including "
                    + "the control the pack has no art for. Copies, because iconForName hands back "
                    + "a shared stack and the module is about to re-point it";
        });

        call("show the stage again for the pack comparison", () -> {
            show(stage);
            return "back on SlotStageScreen, so both pack frames come from the same render path "
                    + "as shots 05-14";
        });
        call("start a roll whose drops are the demonstration names",
                () -> startRoll(false, packDrops()));
        caption("BEFORE: synthesised vanilla stacks. No item_model, so Hypixel's pack cannot "
                + "dress them.");
        awaitRoll(() -> allLockedAt);
        shot("screenshot: reel drawing the OLD synthesised vanilla stacks",
                "20-pack-before-vanilla.png", this::stageReady);

        call("learn Hypixel's item_model for each demonstration name", HypixelPackProof::teach);
        call("after: every demonstration name now carries Hypixel's own item_model",
                HypixelPackProof::requireTaught);

        call("start the same roll again, now the models are learned",
                () -> startRoll(false, packDrops()));
        caption("AFTER: the same drops, drawn from stacks carrying Hypixel's item_model.");
        awaitRoll(() -> allLockedAt);
        shot("screenshot: reel drawing Hypixel's own item art",
                "21-pack-after-hypixel.png", this::stageReady);

        call("open the side-by-side comparison", () -> {
            packProof.recordAfter();
            show(packProof);
            return "one frame holding both stacks per row, drawn with the same graphics.item() "
                    + "call, so the difference cannot be an artefact of two frames taken apart";
        });
        delay(LAYOUT_TICKS);
        shot("screenshot: old sprite and new sprite, same frame",
                "22-pack-side-by-side.png", () -> packProof != null);

        // --- 5b. the same widget speaking for sources that are not Diana ---------------------
        //
        // The whole point of the SkyBlock-wide rework is that the caption strip now names the
        // subject of any LootEvent rather than a Mythological creature, and that claim is exactly
        // the kind that reads as true in source and turns out to be laid out wrong on screen --
        // "Voidgloom Seraph IV" is half again as long as any creature name, and "Obsidian Chest"
        // and "Lord Jawbus" put a chest and a fish where a Minotaur used to be. Three sources,
        // chosen to be as unlike each other as the feature allows: a slayer kill, a container, and
        // a sea creature.
        //
        // These go through LootMachine.simulate rather than DianaController.simulate, so they
        // exercise the general entry point -- SlotRoll.startEvent(LootEvent) -- and not Diana's.
        call("show the stage again for the non-Diana captions", () -> {
            show(stage);
            return "back on SlotStageScreen, so these frames come from the same render path as "
                    + "shots 05-14 and are directly comparable with them";
        });

        call("start a slayer roll", () -> startSourceRoll(
                LootSource.SLAYER_BOSS, "Voidgloom Seraph IV", SLAYER_DROPS));
        caption("Slayer: the longest subject the feature can produce, on a boss kill");
        awaitRoll(() -> allLockedAt);
        shot("screenshot: the widget captioned for a slayer boss",
                "30-source-slayer-boss.png", this::stageReady);

        call("start a dungeon chest roll", () -> startSourceRoll(
                LootSource.DUNGEON_REWARD_CHEST, "Obsidian Chest", CHEST_DROPS));
        caption("Chest: a container, not a kill -- the subject is the chest that was opened");
        awaitRoll(() -> allLockedAt);
        shot("screenshot: the widget captioned for a dungeon reward chest",
                "31-source-dungeon-chest.png", this::stageReady);

        call("start a fishing roll", () -> startSourceRoll(
                LootSource.FISHING_RARE_SEA_CREATURE, "Lord Jawbus", FISHING_DROPS));
        caption("Fishing: a rare sea creature, the source that is open on every island");
        awaitRoll(() -> allLockedAt);
        shot("screenshot: the widget captioned for a rare sea creature",
                "32-source-rare-fish.png", this::stageReady);

        call("every non-Diana roll really did carry its own source", () -> {
            SlotRoll roll = DianaController.get().roll();
            if (roll == null) {
                throw new IllegalStateException("no roll to inspect");
            }
            LootSource source = roll.sourceAt(roll.nowMillis());
            if (source != LootSource.FISHING_RARE_SEA_CREATURE) {
                throw new IllegalStateException("the last roll reports " + source
                        + ", not the fishing source it was started with");
            }
            return "SlotRoll.sourceAt reports FISHING_RARE_SEA_CREATURE, so the widget above is "
                    + "drawing a genuine non-Diana event rather than a relabelled Diana one";
        });

        // --- 6. level recolouring, end to end, with no server -------------------------------
        call("level recolour end to end, no server", () -> {
            RecolourProbe.Result result = RecolourProbe.run(outDir.resolve("recolour-report.txt"));
            lastReport = result.report();
            if (!result.passed()) {
                throw new IllegalStateException(result.summary());
            }
            return result.summary();
        }, () -> lastReport);

        // --- 7. the step deliberately not taken ---------------------------------------------
        call("in-world HUD capture", () -> {
            throw new Skipped("not attempted. Creating a singleplayer world from a script means "
                    + "WorldOpenFlows plus a full WorldStem, which is slow, differs between the "
                    + "two nodes and can leave a half-written save behind; a flake there would "
                    + "cost the title-screen captures that are the actual deliverable. The "
                    + "shipped render path is still covered: shots 05-09 come from "
                    + "SlotMachineHud.extractRenderState itself, invoked by SlotStageScreen, not "
                    + "from a stand-in drawing");
        });

        // A last beat so the final PNG is definitely closed before the client is asked to stop.
        delay(TPS / 4);
    }

    /** Where the recolour probe wrote its report, for the summary. */
    private Path lastReport;

    // ---------------------------------------------------------------- step bodies

    /**
     * Puts the settings into the state the captures need, in memory only.
     *
     * <p>Chroma is switched on at half a cycle per second so that one second apart is half a hue
     * rotation: the two palette frames cannot accidentally match. The reel timings are widened
     * from the shipped defaults because the brief asks for a photograph of each phase and the
     * default roll passes through all three in 1.7 seconds -- fast enough that a slow readback
     * could land two shots in the same phase. The widened values are recorded in the summary so
     * nobody mistakes them for what players get.</p>
     */
    private String stageSettings() {
        SkyPrismConfig config = ConfigManager.get().config();

        config.levels.enabled = true;
        config.levels.applyToChat = true;
        config.levels.applyToTabList = true;
        config.levels.chromaEnabled = true;
        config.levels.chromaCyclesPerSecond = 0.5;
        config.levels.chromaUpdateHz = 60;

        // Pinned so the recolour probe and the two palette frames mean the same thing on every
        // machine. A developer whose config narrowed the sanity range, or who is still carrying
        // the ramp that shipped two versions ago, would otherwise get a report and a pair of
        // screenshots that were about their settings rather than about the code.
        //
        // Pinned to the *shipped defaults*, not to arbitrary values. That is the whole point:
        // these captures are the record of what a player sees out of the box, so a value staged
        // here that disagrees with the default documents something nobody has. Brackets are the
        // case in point -- they used to be pinned off while the default was off, and the default
        // is now on, so pinning them off would now photograph the wrong mod.
        //
        // chromaMinLevel is the case that caught this out. It was pinned to a literal 300 while
        // the shipped default was 400 and then 600, so every published palette screenshot carried
        // a header reading "chroma on from level 300" -- a threshold no player has ever had. It is
        // read off the shipped defaults now, with the rest, so it cannot fall behind again.
        SkyPrismConfig shipped = new SkyPrismConfig();
        config.levels.chromaMinLevel = shipped.levels.chromaMinLevel;
        config.levels.recolourBrackets = shipped.levels.recolourBrackets;
        config.levels.mode = shipped.levels.mode;
        config.levels.gradientPreset = shipped.levels.gradientPreset;
        config.levels.customStops.clear();

        // The bracket table has to be pinned for exactly the reason the mode does, and it did not
        // used to matter. While the shipped mode was GRADIENT the table was dead weight -- nothing
        // rendered it, so a stale one in a developer's config could not reach a screenshot. The
        // shipped mode is BRACKETS now, which makes this list *the palette*: pin the mode without
        // pinning the table and the run photographs the new mode drawing the old colours.
        //
        // This is not hypothetical. The dev config in versions/26.2/run is a v4 file carrying the
        // twenty-five-band fine table, and the v4->v5 guard deliberately keeps it (the player chose
        // vanilla_plus, so the migration must not overwrite their palette). Correct for a player,
        // wrong for a capture: without this line the two palette frames would show the previous
        // default under the new default's name.
        config.levels.brackets = new ArrayList<>(shipped.levels.brackets);
        config.levels.minLevel = LevelTagLocator.STANDARD_MIN;
        config.levels.maxLevel = LevelTagLocator.STANDARD_MAX;

        config.diana.enabled = true;
        config.diana.reelCount = 3;
        config.diana.spinMillis = 2_000L;
        config.diana.lockStaggerMillis = 900L;
        config.diana.settleMillis = 8_000L;
        config.diana.fadeMillis = 1_000L;

        // The second act, widened on the same principle as act one: every phase must be wide
        // enough that a slow GPU readback cannot land its shot in the neighbouring phase. The
        // shipped 600/900/280/2200 would give the JACKPOT_LOCK stagger only 280 ms of window per
        // column, and a readback can take longer than that.
        //
        // The wash is widened further than the rest, to three times what a single frame would
        // need, because three frames are taken inside it. Each is a caption, an assertion and a
        // readback -- comfortably under a second, but not under a second by so much that three
        // of them would fit in the two seconds that used to be enough for one.
        config.diana.jackpotIntroMillis = 6_000L;
        config.diana.jackpotSpinMillis = 2_500L;
        config.diana.jackpotLockStaggerMillis = 1_500L;
        config.diana.jackpotHoldMillis = 6_000L;

        config.hud.enabled = true;
        config.hud.anchor = HudAnchor.MIDDLE_CENTER;
        config.hud.x = 0.5;
        config.hud.y = 0.5;
        config.hud.scale = 2.0;
        config.hud.showCreatureName = true;
        config.hud.showDropNames = true;

        // refresh(), never save(): this republishes the palette, the locator and every memo,
        // and fires the listener that makes SlotMachineHud re-read its roll -- without touching
        // the config file. Nothing in this run writes settings to disk.
        ConfigManager.get().refresh();

        SkyPrismConfig live = ConfigManager.get().config();
        return "in memory only (ConfigManager.refresh, no save): palette pinned to the shipped "
                + "defaults (" + live.levels.mode + " " + live.levels.gradientPreset
                + ", " + live.levels.brackets.size() + " brackets spanning "
                + live.levels.brackets.get(0).minLevel() + ".."
                + live.levels.brackets.get(live.levels.brackets.size() - 1).minLevel()
                + ", recolourBrackets " + live.levels.recolourBrackets + "); chroma on from level "
                + live.levels.chromaMinLevel + " at " + live.levels.chromaCyclesPerSecond
                + " cycles/s; reels " + live.diana.reelCount + " x spin "
                + live.diana.spinMillis + " ms, stagger " + live.diana.lockStaggerMillis
                + " ms, settle " + live.diana.settleMillis + " ms (widened from the shipped "
                + "1200/250/2500 so each phase can be photographed); jackpot act intro "
                + live.diana.jackpotIntroMillis + " / spin " + live.diana.jackpotSpinMillis
                + " / stagger " + live.diana.jackpotLockStaggerMillis + " / hold "
                + live.diana.jackpotHoldMillis + " ms (widened from the shipped 600/900/280/2200 "
                + "for the same reason); HUD " + live.hud.anchor + " at scale " + live.hud.scale
                + ", drop names " + (live.hud.showDropNames ? "on" : "off");
    }

    /**
     * Starts a demonstration roll and works out when each phase will be on screen.
     *
     * <p>The instants are derived from the live settings rather than written down, so widening
     * or narrowing the timings above moves the capture points with them.</p>
     *
     * <p>Both acts are laid out here, and the arithmetic is deliberately independent: act one's
     * offsets never mention a jackpot duration and act two's are all measured forward from the
     * end of act one's settle phase. That is the shape of the timeline itself, so a script that
     * computed them any other way would be modelling something the mod no longer does.</p>
     *
     * @param jackpot true to offer a rare drop. It latches {@link SlotRoll#jackpot()} at once but
     *                changes nothing about act one; the celebration is a second act that begins
     *                only after the ordinary result has been settled and read.
     */
    private String startRoll(boolean jackpot) {
        return startRoll(jackpot, jackpot ? JACKPOT_DROPS : ORDINARY_DROPS);
    }

    /**
     * Starts a demonstration roll with a chosen set of drops.
     *
     * <p>Split out from {@link #startRoll(boolean)} so the pack section can put its own drop names
     * on the settled reels. The capture instants below are arithmetic on the staged durations, and
     * that arithmetic is the reason this is one method rather than two: a second copy of it would
     * be the thing that quietly drifts when somebody retunes a timing.</p>
     *
     * @param jackpot whether the roll should claim the flourish
     * @param drops   what the reels land on; one per column, in column order
     * @return the timeline, for the summary
     */
    /**
     * Starts a roll for any source, through the general entry point rather than Diana's.
     *
     * <p>The capture arithmetic is deliberately the same as {@link #startRoll(boolean, List)}'s
     * act one, and deliberately not copied from it: this method calls into the same staged config
     * so a retune of the reel timings moves both. Only the ordinary act is staged, because these
     * three frames exist to show the caption and the reels, not the celebration -- shots 08-14
     * already own that.</p>
     *
     * @param source  the activity to caption the strip with
     * @param subject what produced the loot, which is the text under the reels
     * @param drops   what the reels land on; one per column, in column order
     * @return the timeline, for the summary
     */
    private String startSourceRoll(LootSource source, String subject, List<LootDrop> drops) {
        SkyPrismConfig.DianaSettings diana = ConfigManager.get().config().diana;
        long now = System.currentTimeMillis();
        if (!LootMachine.get().simulate(new LootEvent(source, subject, now), drops)) {
            throw new IllegalStateException("LootMachine has no roll wired; cannot simulate "
                    + source);
        }
        rollStartedAt = now;

        int reels = Math.max(1, diana.reelCount);
        long firstLock = diana.spinMillis;
        long lastLock = diana.spinMillis + (long) (reels - 1) * diana.lockStaggerMillis;
        midSpinAt = Math.max(200L, firstLock / 2);
        oneLockedAt = firstLock + Math.max(150L, diana.lockStaggerMillis / 2);
        allLockedAt = lastLock + Math.min(2_000L, Math.max(400L, diana.settleMillis / 3));

        goldWashEarlyAt = 0L;
        goldWashMidAt = 0L;
        goldWashLateAt = 0L;
        jackpotSpinAt = 0L;
        firstMatchAt = 0L;
        thirdMatchAt = 0L;
        jackpotHoldAt = 0L;

        return source + " roll captioned \"" + subject + "\"; reels lock at " + firstLock + ".."
                + lastLock + " ms, capturing all-locked at " + allLockedAt + " ms";
    }

    private String startRoll(boolean jackpot, List<LootDrop> drops) {
        SkyPrismConfig.DianaSettings diana = ConfigManager.get().config().diana;
        MythologicalCreature creature = jackpot
                ? MythologicalCreature.MINOS_INQUISITOR
                : MythologicalCreature.MINOS_CHAMPION;

        DianaController.get().simulate(creature, drops);
        rollStartedAt = System.currentTimeMillis();

        int reels = Math.max(1, diana.reelCount);

        // ---- act one, which a jackpot no longer changes in any way ----
        long firstLock = diana.spinMillis;
        long lastLock = diana.spinMillis + (long) (reels - 1) * diana.lockStaggerMillis;
        long settledAt = lastLock + diana.settleMillis;

        midSpinAt = Math.max(200L, firstLock / 2);
        oneLockedAt = firstLock + Math.max(150L, diana.lockStaggerMillis / 2);
        // Deliberately inside the settle phase and well before its end. On a jackpot roll this is
        // the frame that has to prove the ordinary result is drawn with no gold on it at all, so
        // it must not creep into JACKPOT_INTRO; a third of the settle leaves seconds of margin.
        allLockedAt = lastLock + Math.min(2_000L, Math.max(400L, diana.settleMillis / 3));

        if (!jackpot) {
            goldWashEarlyAt = 0L;
            goldWashMidAt = 0L;
            goldWashLateAt = 0L;
            jackpotSpinAt = 0L;
            firstMatchAt = 0L;
            thirdMatchAt = 0L;
            jackpotHoldAt = 0L;
            return "ordinary roll for " + creature.displayName() + "; reels lock at " + firstLock
                    + ".." + lastLock + " ms, settled from " + lastLock + " ms, capturing at "
                    + midSpinAt + " / " + oneLockedAt + " / " + allLockedAt + " ms";
        }

        // ---- act two, every offset measured from the end of the settle phase ----
        //
        // The reels break loose at settledAt, not at introEnd: the wash and the spin that
        // follows it are one continuous stretch of moving reels, and introEnd is only where the
        // gold finishes arriving. That is why three of the capture points below sit inside the
        // wash rather than waiting for it to be over.
        long introEnd = settledAt + diana.jackpotIntroMillis;
        long spinEnd = introEnd + diana.jackpotSpinMillis;
        long lastMatch = spinEnd + (long) (reels - 1) * diana.jackpotLockStaggerMillis;

        // A quarter, a half and three quarters of the way through the wash. The quarters rather
        // than the edges because a frame at either edge is indistinguishable from "the gold
        // snapped on" and "the gold is simply in"; three points inside the ramp give a reader
        // the same machine at three depths of gold, with the strip somewhere different in each.
        goldWashEarlyAt = settledAt + diana.jackpotIntroMillis / 4;
        goldWashMidAt = settledAt + diana.jackpotIntroMillis / 2;
        goldWashLateAt = settledAt + (diana.jackpotIntroMillis * 3L) / 4;
        jackpotSpinAt = introEnd + Math.max(200L, diana.jackpotSpinMillis / 2);
        // Just after the first column lands and comfortably before the second one does.
        firstMatchAt = spinEnd + Math.max(150L, diana.jackpotLockStaggerMillis / 3);
        // Just after the last column lands, which is the payoff frame.
        thirdMatchAt = lastMatch + Math.max(120L, diana.jackpotLockStaggerMillis / 6);
        jackpotHoldAt = lastMatch + Math.min(3_000L, Math.max(500L, diana.jackpotHoldMillis / 2));

        return "jackpot roll for " + creature.displayName() + "; act one locks at " + firstLock
                + ".." + lastLock + " ms and settles until " + settledAt
                + " ms with no gold on it, act two washes gold " + settledAt + ".." + introEnd
                + " ms, respins to " + spinEnd + " ms and lands its three of a kind by "
                + lastMatch + " ms; capturing at " + midSpinAt + " / " + allLockedAt + " / "
                + goldWashEarlyAt + " / " + goldWashMidAt + " / " + goldWashLateAt + " / "
                + jackpotSpinAt + " / " + firstMatchAt + " / " + thirdMatchAt + " / "
                + jackpotHoldAt + " ms";
    }

    /**
     * Fails unless the live roll is in one of the states this capture point was aimed at.
     *
     * <p>The capture instants above are arithmetic on the staged durations, and arithmetic can be
     * right while the timeline it models has moved on underneath it. Asserting the state at each
     * shot turns "the screenshot looks wrong" into "the script photographed the wrong phase",
     * which is a different bug and a much faster one to find.</p>
     *
     * @param expected the states that would make this frame the intended one
     * @return a description of what was actually on screen, for the summary
     */
    private String requireState(RollState... expected) {
        RollState actual = DianaController.get().roll().state();
        if (!Arrays.asList(expected).contains(actual)) {
            throw new IllegalStateException("expected " + Arrays.toString(expected)
                    + " at " + (System.currentTimeMillis() - rollStartedAt)
                    + " ms into the roll, but the roll was " + actual);
        }
        return "roll state is " + actual + " at "
                + (System.currentTimeMillis() - rollStartedAt) + " ms";
    }

    /**
     * Fails unless the gold is part way in <em>over reels that are already turning</em>.
     *
     * <p>Both halves are asserted together on purpose, because either on its own proves nothing.
     * A wash measurably between its endpoints was true of the old behaviour too, where the
     * columns stood frozen on act one's symbols for the whole of the phase; three unlocked
     * columns are true of the re-spin, which is a phase later and has no wash left to arrive.
     * Only the conjunction is the thing the rework changed, and only the conjunction is worth
     * photographing.</p>
     *
     * <p>"Turning" is asserted as unlocked-and-already-committed rather than by comparing two
     * scroll positions. {@link Reel#locked()} is what the renderer branches on -- an unlocked
     * column is drawn as a scrolling strip and a locked one as a still item -- so it is the
     * property that decides what the PNG contains, and {@link Reel#spinPhase()} moving would be
     * a weaker statement about a number the HUD does not read.</p>
     *
     * @param low  the progress the frame must be past, so it is not "the gold snapped on"
     * @param high the progress it must be short of, so it is not "the gold is simply in"
     * @return what was actually on screen, for the summary
     */
    private String requireWashOverMotion(double low, double high) {
        String where = requireState(RollState.JACKPOT_INTRO);
        SlotRoll roll = DianaController.get().roll();
        double progress = roll.jackpotIntroProgress();
        if (!(progress > low) || !(progress < high)) {
            throw new IllegalStateException("jackpotIntroProgress is " + progress + ", outside the "
                    + low + ".." + high + " band this frame was aimed at; the shot has drifted out "
                    + "of the stretch of the ramp it is meant to document");
        }
        LootDrop symbol = roll.jackpotSymbol();
        if (symbol == null) {
            throw new IllegalStateException("the wash has begun with no jackpot symbol to spin to");
        }
        List<Reel> reels = roll.reels();
        for (Reel reel : reels) {
            if (reel.locked()) {
                throw new IllegalStateException("column " + reel.index() + " is locked while the "
                        + "gold is still arriving, so the machine is standing still under the "
                        + "wash and the two beats are consecutive after all");
            }
            if (!symbol.equals(reel.symbol())) {
                throw new IllegalStateException("column " + reel.index() + " is spinning towards "
                        + reel.symbol() + " instead of the jackpot symbol " + symbol);
            }
        }
        return where + "; jackpotIntroProgress() is "
                + String.format(Locale.ROOT, "%.2f", progress) + " and all " + reels.size()
                + " columns are unlocked and already carrying " + symbol.itemName()
                + ", so the gold is arriving over a machine that is already turning";
    }

    /**
     * Fails unless every column that has landed shows the jackpot symbol, and the expected number
     * of them have landed.
     *
     * @param wantLocked how many columns should have come to rest by now
     * @return a description of the columns, for the summary
     */
    private String requireThreeOfAKind(int wantLocked) {
        SlotRoll roll = DianaController.get().roll();
        LootDrop symbol = roll.jackpotSymbol();
        if (symbol == null) {
            throw new IllegalStateException("the roll is celebrating with no jackpot symbol");
        }
        List<Reel> reels = roll.reels();
        int locked = 0;
        for (Reel reel : reels) {
            if (!reel.locked()) {
                continue;
            }
            locked++;
            if (!symbol.equals(reel.symbol())) {
                throw new IllegalStateException("column " + reel.index() + " landed on "
                        + reel.symbol() + " instead of the jackpot symbol " + symbol);
            }
        }
        if (locked != wantLocked) {
            throw new IllegalStateException("expected " + wantLocked + " of " + reels.size()
                    + " columns landed but " + locked + " had");
        }
        return locked + " of " + reels.size() + " columns landed, every one of them on "
                + symbol.itemName() + " -- three of a kind";
    }

    /** The palette colour of a chromatic level right now, quantised exactly as the screen does. */
    private int sampleChroma() {
        SkyPrismConfig config = ConfigManager.get().config();
        long now = Palettes.quantise(System.currentTimeMillis(), config.levels);
        return ConfigManager.get().palette().colorFor(CHROMA_SAMPLE_LEVEL, now);
    }

    /** True once the stage screen is up, so its shots are not silently taken of something else. */
    private boolean stageReady() {
        return stage != null;
    }

    /**
     * The demonstration drops, one per reel column.
     *
     * <p>Built from the live reel count rather than written out, so a config with two or four
     * columns still gets one demonstration name per column instead of a settled reel showing a
     * name that was never taught.</p>
     *
     * @return one drop per column, in column order
     */
    private static List<LootDrop> packDrops() {
        int reels = Math.max(1, ConfigManager.get().config().diana.reelCount);
        List<LootDrop> drops = new ArrayList<>(reels);
        for (String name : HypixelPackProof.reelNames(reels)) {
            drops.add(new LootDrop(name, "b", 1, false));
        }
        return drops;
    }

    /**
     * Drops the client to {@link #PALETTE_GUI_SCALE} so the palette frames hold most of the range.
     *
     * <p>In memory only, exactly like {@link #stageSettings()}: {@code Options.save()} is never
     * called, so {@code options.txt} on disk keeps whatever the developer had. The previous value
     * is remembered rather than assumed -- see {@link #borrowedGuiScaleFrom}.</p>
     *
     * <p>{@code resizeGui()} is what makes it take: setting the option only stores a number, and
     * the window keeps handing out the old scaled width until something recomputes it. That call
     * also re-runs {@code resize} on whatever screen is open, which is how the grid learns it has
     * more columns to lay out into.</p>
     */
    private String borrowGuiScale() throws Skipped {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            throw new Skipped("no client, so there is no window to rescale");
        }
        if (borrowedGuiScaleFrom == Integer.MIN_VALUE) {
            borrowedGuiScaleFrom = client.options.guiScale().get();
        }
        client.options.guiScale().set(PALETTE_GUI_SCALE);
        client.resizeGui();
        return "GUI scale " + describeGuiScale(borrowedGuiScaleFrom) + " -> "
                + PALETTE_GUI_SCALE + ", so the window is "
                + client.getWindow().getGuiScaledWidth() + "x"
                + client.getWindow().getGuiScaledHeight()
                + " scaled pixels and the grid lays out about four times as many cells."
                + " In memory only: Options.save() is not called, so options.txt is untouched";
    }

    /** Puts back whatever {@link #borrowGuiScale()} took, and is a no-op if it never ran. */
    private String restoreGuiScale() {
        if (borrowedGuiScaleFrom == Integer.MIN_VALUE) {
            return "nothing was borrowed";
        }
        int original = borrowedGuiScaleFrom;
        borrowedGuiScaleFrom = Integer.MIN_VALUE;
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return "no client left to restore on";
        }
        client.options.guiScale().set(original);
        client.resizeGui();
        return "GUI scale back to " + describeGuiScale(original) + ", window "
                + client.getWindow().getGuiScaledWidth() + "x"
                + client.getWindow().getGuiScaledHeight() + " scaled pixels";
    }

    /**
     * Grows the window to {@link #PALETTE_WINDOW_HEIGHT} so the whole ramp fits one frame.
     *
     * <p>Through GLFW rather than {@code Window.setWindowed}, which also decides monitors and
     * fullscreen state: the only thing wanted here is a taller client area. The resize arrives
     * as an ordinary window event, so the caller has to let a frame pass before photographing --
     * the same rule the scroll below already obeys, and for the same reason.</p>
     *
     * <p>Safe with the window parked off-screen, which is where the background launcher puts it.
     * A window larger than the monitor is only a problem for a window somebody has to look at.</p>
     *
     * <p><b>The un-maximise is not optional.</b> A maximised window is sized by the window
     * manager, not by its owner, and Windows silently drops a resize aimed at one: the first run
     * of this asked for 1440, was told the call succeeded, and photographed a 1080-tall
     * framebuffer starting at level 115 exactly as before. Nothing failed and nothing warned --
     * the only evidence was the height of the PNG. Restoring the window first is what makes the
     * new size stick, and the flag below is what puts the developer's maximised window back.</p>
     */
    private String borrowWindowHeight() throws Skipped {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            throw new Skipped("no client, so there is no window to resize");
        }
        Window window = client.getWindow();
        int before = window.getHeight();
        if (before >= PALETTE_WINDOW_HEIGHT) {
            return "window is already " + window.getWidth() + "x" + before
                    + " real pixels, which is at least the " + PALETTE_WINDOW_HEIGHT
                    + " the ramp needs; nothing borrowed";
        }
        if (borrowedWindowHeightFrom == Integer.MIN_VALUE) {
            borrowedWindowHeightFrom = before;
            borrowedFromMaximised =
                    GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
        }
        int width = window.getWidth();

        // Where the window is now, so it can be put straight back in the same call that resizes
        // it. This run is deliberately parked off-screen so it never takes over the display of
        // whoever is sitting in front of it, and every route to a resize is also a route to a
        // reposition -- so the position is carried through rather than left to the window
        // manager's idea of where a restored window belongs.
        int[] atX = new int[1];
        int[] atY = new int[1];
        GLFW.glfwGetWindowPos(window.handle(), atX, atY);

        if (borrowedFromMaximised) {
            GLFW.glfwRestoreWindow(window.handle());
        }

        // glfwSetWindowMonitor with a null monitor, not glfwSetWindowSize. The plain resize is
        // advisory -- it was tried first, reported success, and left the framebuffer at 1080 --
        // whereas this is the call that re-establishes the window's mode, and it sets position
        // and size in one shot so there is no frame where the window has moved somewhere
        // visible. GLFW_DONT_CARE for the refresh rate: it is only read in fullscreen.
        GLFW.glfwSetWindowMonitor(window.handle(), 0L, atX[0], atY[0],
                width, PALETTE_WINDOW_HEIGHT, GLFW.GLFW_DONT_CARE);

        int[] fbW = new int[1];
        int[] fbH = new int[1];
        GLFW.glfwGetFramebufferSize(window.handle(), fbW, fbH);
        return "asked for " + width + "x" + PALETTE_WINDOW_HEIGHT + " real pixels, up from "
                + window.getWidth() + "x" + before + "; GLFW now reports a framebuffer of "
                + fbW[0] + "x" + fbH[0]
                + (borrowedFromMaximised ? " (un-maximised first)" : " (was not maximised)")
                + ", position held at " + atX[0] + "," + atY[0]
                + ". Borrowed for the palette frames only and given back below";
    }

    /** Puts back whatever {@link #borrowWindowHeight()} took, and is a no-op if it never ran. */
    private String restoreWindowHeight() {
        if (borrowedWindowHeightFrom == Integer.MIN_VALUE) {
            return "nothing was borrowed";
        }
        int original = borrowedWindowHeightFrom;
        boolean remaximise = borrowedFromMaximised;
        int shortfall = borrowedHeightShortfall;
        borrowedWindowHeightFrom = Integer.MIN_VALUE;
        borrowedFromMaximised = false;
        borrowedHeightShortfall = 0;
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return "no client left to restore on";
        }
        Window window = client.getWindow();
        int[] atX = new int[1];
        int[] atY = new int[1];
        GLFW.glfwGetWindowPos(window.handle(), atX, atY);
        // Plus the shortfall the borrow measured, so the client area comes back to exactly the
        // height the run started at rather than a pixel under it. Every screenshot after this
        // one is read off that framebuffer.
        GLFW.glfwSetWindowMonitor(window.handle(), 0L, atX[0], atY[0],
                window.getWidth(), original + shortfall, GLFW.GLFW_DONT_CARE);
        // Deliberately not re-maximised even when the borrow un-maximised it. Maximising snaps
        // the window back onto a monitor, and this one is parked off-screen on purpose; the size
        // it started at is restored, which is the part that could outlive the run.
        return "asked for " + (original + shortfall) + " to land back on " + original
                + " real pixels, position held at " + atX[0] + "," + atY[0]
                + (remaximise ? " (left un-maximised: re-maximising would drag an off-screen"
                        + " window back onto the desktop)" : "");
    }

    /** Names the auto setting, because "0" on its own reads as a broken value in the summary. */
    private static String describeGuiScale(int value) {
        return value == 0 ? "0 (auto)" : String.valueOf(value);
    }

    private static void show(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            // setScreenAndShow, not setScreen: the latter does not exist on 26.2. It also renders
            // a frame on the spot, which is why the layout waits below can be short.
            client.setScreenAndShow(screen);
        }
    }

    // ---------------------------------------------------------------- the driver

    private void onEndTick() {
        if (finished) {
            return;
        }
        if (++ticks > WATCHDOG_TICKS) {
            record("watchdog", Status.FAIL,
                    "the script was still running after " + (WATCHDOG_TICKS / TPS)
                            + " seconds; the remaining " + program.size()
                            + " operations were abandoned", null);
            finish();
            return;
        }
        try {
            while (true) {
                Op next = program.peek();
                if (next == null) {
                    finish();
                    return;
                }
                if (!next.poll()) {
                    return;
                }
                program.poll();
            }
        } catch (Throwable broken) {
            // The driver itself failing is the one thing that could hang the client, so it ends
            // the run rather than letting the next tick try again.
            record("self test driver", Status.FAIL, describe(broken), null);
            LOGGER.error("SkyPrism self test driver threw; ending the run", broken);
            finish();
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        program.clear();

        // Belt and braces: the scripted restore is a step like any other, and the watchdog and the
        // driver's own catch both end the run without reaching it. Leaving a borrowed GUI scale
        // behind would be a self test that changed the developer's client.
        try {
            restoreGuiScale();
        } catch (Throwable stuck) {
            LOGGER.warn("SkyPrism self test could not give the GUI scale back", stuck);
        }
        try {
            restoreWindowHeight();
        } catch (Throwable stuck) {
            LOGGER.warn("SkyPrism self test could not give the window height back", stuck);
        }

        Path summary = null;
        try {
            summary = writeSummary();
        } catch (Throwable unwritable) {
            LOGGER.error("SkyPrism self test could not write its summary", unwritable);
        }

        int passed = count(Status.PASS);
        int failed = count(Status.FAIL);
        int skipped = count(Status.SKIP);
        LOGGER.info("SkyPrism self test finished: {} passed, {} failed, {} skipped", passed,
                failed, skipped);
        for (Step step : steps) {
            LOGGER.info("  [{}] {}{}", step.status(), step.id(),
                    step.file() == null ? "" : " -> " + step.file());
        }
        LOGGER.info("SkyPrism self test summary: {}", summary == null ? "not written" : summary);

        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                // The vanilla quit button does exactly this: stop() only clears the running flag,
                // so the game loop unwinds normally and the launcher sees exit code 0.
                client.stop();
            }
        } catch (Throwable stubborn) {
            LOGGER.error("SkyPrism self test could not ask the client to stop", stubborn);
        }
    }

    // ---------------------------------------------------------------- operations

    /** One unit of the script. */
    @FunctionalInterface
    private interface Op {
        /** @return true when this operation is finished and the script may move on */
        boolean poll();
    }

    /** A step body, which returns the detail line for the summary. */
    @FunctionalInterface
    private interface StepBody {
        String run() throws Exception;
    }

    /** Thrown by a step body that decided not to run, carrying the reason. */
    private static final class Skipped extends Exception {

        private static final long serialVersionUID = 1L;

        Skipped(String why) {
            super(why);
        }
    }

    private enum Status { PASS, FAIL, SKIP }

    /**
     * One line of the summary.
     *
     * @param id     what was attempted
     * @param status how it went
     * @param detail the evidence, in one sentence
     * @param file   the artefact it produced, or null
     */
    private record Step(String id, Status status, String detail, String file) {
    }

    private void call(String id, StepBody body) {
        call(id, body, null);
    }

    private void call(String id, StepBody body, Supplier<Path> file) {
        program.add(() -> {
            try {
                String detail = body.run();
                record(id, Status.PASS, detail, file == null ? null : file.get());
            } catch (Skipped skipped) {
                record(id, Status.SKIP, skipped.getMessage(), null);
            } catch (Throwable broken) {
                // The artefact is attached on failure too. A step that wrote a report and then
                // failed on what the report says is precisely the case where somebody needs the
                // path to it, and dropping it here would send them hunting for the file.
                record(id, Status.FAIL, describe(broken), file == null ? null : file.get());
                LOGGER.error("SkyPrism self test step \"{}\" failed", id, broken);
            }
            return true;
        });
    }

    /** An unrecorded operation, for bookkeeping that is not worth a summary line. */
    private void plain(Runnable body) {
        program.add(() -> {
            body.run();
            return true;
        });
    }

    /**
     * Labels the stage, then lets a couple of frames go by.
     *
     * <p>The pause is the point. A capture reads back the last frame the GPU finished, so a
     * caption set in the same tick as the request would be missing from the PNG it is supposed to
     * be describing -- and a screenshot captioned with the previous phase is worse than one with
     * no caption at all.</p>
     */
    private void caption(String text) {
        plain(() -> {
            if (stage != null) {
                stage.caption(text);
            }
        });
        delay(2);
    }

    private void delay(int ticksToWait) {
        int[] remaining = {ticksToWait};
        program.add(() -> remaining[0]-- <= 0);
    }

    /**
     * Waits until a condition holds, recording whether it did.
     *
     * <p>A timeout is recorded and the script carries on rather than aborting: the later steps
     * are worth attempting even if readiness was never confirmed, and their own failures will say
     * so more precisely than a missing run would.</p>
     */
    private void awaitUntil(String id, BooleanSupplier condition, int timeoutTicks) {
        int[] waited = {0};
        program.add(() -> {
            if (condition.getAsBoolean()) {
                record(id, Status.PASS, "ready after " + waited[0] + " ticks", null);
                return true;
            }
            if (++waited[0] > timeoutTicks) {
                record(id, Status.FAIL, "still not ready after " + (timeoutTicks / TPS)
                        + " seconds; the run continues, and later steps will say what broke", null);
                return true;
            }
            return false;
        });
    }

    /** Waits until the running roll has been going for a given number of milliseconds. */
    private void awaitRoll(LongSupplier millis) {
        program.add(() -> System.currentTimeMillis() - rollStartedAt >= millis.getAsLong());
    }

    /**
     * Captures the framebuffer once, then waits for the readback to land.
     *
     * <p>The capture is requested on the first poll and collected on a later one. It cannot be
     * done in a single poll: the readback completes on the render thread, which is the thread
     * this is running on, so waiting for it here would wait forever.</p>
     *
     * @param when a guard; when it answers false the shot is recorded as skipped rather than
     *             photographing whatever happened to be on screen instead. Null means always.
     */
    private void shot(String id, String fileName, BooleanSupplier when) {
        Shots.Capture[] capture = {null};
        int[] waited = {0};
        Path target = outDir.resolve(fileName);
        program.add(() -> {
            if (capture[0] == null) {
                if (when != null && !when.getAsBoolean()) {
                    record(id, Status.SKIP,
                            "the screen this shot documents was never opened", null);
                    return true;
                }
                capture[0] = Shots.request(target);
                return false;
            }
            if (capture[0].settled()) {
                record(id, capture[0].done() ? Status.PASS : Status.FAIL,
                        capture[0].done() ? "captured" : capture[0].error(), target);
                return true;
            }
            if (++waited[0] > SHOT_TIMEOUT_TICKS) {
                record(id, Status.FAIL, "the GPU readback did not complete within "
                        + (SHOT_TIMEOUT_TICKS / TPS) + " seconds", target);
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- reporting

    private void record(String id, Status status, String detail, Path file) {
        steps.add(new Step(id, status, detail == null ? "" : detail,
                file == null ? null : file.toAbsolutePath().toString()));
    }

    private int count(Status status) {
        int total = 0;
        for (Step step : steps) {
            if (step.status() == status) {
                total++;
            }
        }
        return total;
    }

    /**
     * Writes the machine-readable summary.
     *
     * <p>Hand-rolled JSON rather than Gson. The file has to be written after a failure of any
     * kind, including one inside a serialiser, and every value in it is a string, an int or a
     * bool -- so a reflective mapper buys nothing and adds a way for the report about the crash
     * to crash. Everything is escaped to ASCII so the file reads the same in any editor.</p>
     *
     * @return the file written
     */
    private Path writeSummary() throws Exception {
        Files.createDirectories(outDir);
        Path file = outDir.resolve("selftest-summary.json");

        StringBuilder json = new StringBuilder(4096);
        json.append("{\n");
        json.append("  \"schema\": \"skyprism-selftest/1\",\n");
        json.append("  \"mod\": ").append(quote(versionOf("skyprism"))).append(",\n");
        json.append("  \"minecraft\": ").append(quote(versionOf("minecraft"))).append(",\n");
        json.append("  \"startedAt\": ")
                .append(quote(Instant.ofEpochMilli(startedAtMillis).toString())).append(",\n");
        json.append("  \"durationMillis\": ")
                .append(System.currentTimeMillis() - startedAtMillis).append(",\n");
        json.append("  \"outDir\": ").append(quote(outDir.toAbsolutePath().toString()))
                .append(",\n");
        json.append("  \"passed\": ").append(count(Status.PASS)).append(",\n");
        json.append("  \"failed\": ").append(count(Status.FAIL)).append(",\n");
        json.append("  \"skipped\": ").append(count(Status.SKIP)).append(",\n");
        json.append("  \"ok\": ").append(count(Status.FAIL) == 0).append(",\n");
        json.append("  \"steps\": [\n");
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            json.append("    { \"id\": ").append(quote(step.id()))
                    .append(", \"status\": ").append(quote(step.status().name()))
                    .append(", \"detail\": ").append(quote(step.detail()))
                    .append(", \"file\": ")
                    .append(step.file() == null ? "null" : quote(step.file()))
                    .append(" }").append(i + 1 < steps.size() ? "," : "").append('\n');
        }
        json.append("  ]\n}\n");

        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** JSON string literal, escaped to pure ASCII. */
    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length() + 8).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static String describe(Throwable broken) {
        String message = broken.getMessage();
        return broken.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String versionOf(String modId) {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(modId)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Throwable noLoader) {
            return "unknown";
        }
    }
}
