package com.skyprism.mc.command;

import com.skyprism.core.config.ConfigCodec;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.DianaGate;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.level.LevelPalette;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The seam between {@code /skyprism} and the rest of the mod.
 *
 * <p><b>Why a registry instead of direct calls.</b> The command tree has to reach into the
 * config store, the level palette, the Diana state machine and the slot-machine HUD - four
 * modules that are being written in parallel with this one and whose class names are not
 * settled. Naming them directly would couple the command module to signatures nobody has
 * committed to yet, and would make the whole feature uncompilable whenever a sibling is
 * mid-edit. So each of them is reduced to a tiny interface expressed <em>entirely in core
 * types</em> - {@link SkyPrismConfig}, {@link LevelPalette}, {@link DianaGate},
 * {@link SlotRoll} - which are finished, tested and frozen.</p>
 *
 * <p><b>Wiring, for the integrator.</b> Four one-liners in the mod initialiser, after the
 * owning modules have constructed themselves:</p>
 *
 * <pre>{@code
 * SkyPrismServices.setConfig(configManager);   // implements SkyPrismServices.Config
 * SkyPrismServices.setLevel(levelRenderer);    // implements SkyPrismServices.Level
 * SkyPrismServices.setDiana(dianaController);  // implements SkyPrismServices.Diana
 * SkyPrismServices.setHud(slotMachineHud);     // implements SkyPrismServices.Hud
 * }</pre>
 *
 * <p>Each interface is small enough that the owning class can simply {@code implements} it,
 * or be adapted with a four-line anonymous class. If {@code DianaController} really does
 * end up with {@code get()}, {@code simulate(...)} and {@code stats()} as briefed, the
 * adapter is:</p>
 *
 * <pre>{@code
 * SkyPrismServices.setDiana(new SkyPrismServices.Diana() {
 *     public void simulate(MythologicalCreature c, List<LootDrop> d) { DianaController.get().simulate(c, d); }
 *     public List<String> stats()  { return DianaController.get().stats(); }
 *     public DianaGate gate()      { return DianaController.get().gate(); }
 *     public SlotRoll roll()       { return DianaController.get().roll(); }
 * });
 * }</pre>
 *
 * <p><b>Degrading when nothing is wired.</b> {@link #config()} always answers, falling back
 * to a private instance loaded from the real config file, so {@code /skyprism},
 * {@code /skyprism preview}, {@code /skyprism hud} and {@code /skyprism reload} work on
 * their own. {@link #level()} synthesises a palette from that config through
 * {@link Palettes}. {@link #diana()} and {@link #hud()} return {@code null}, and the
 * commands that need them say so in one clear sentence instead of throwing. Every
 * subcommand also reports which providers are live, so an unwired module is visible rather
 * than silently mimicked.</p>
 */
public final class SkyPrismServices {

    private SkyPrismServices() {
    }

    /**
     * Access to the one live {@link SkyPrismConfig} instance every module shares, plus the
     * file it came from.
     *
     * <p>The command module never constructs a config of its own when this is wired: a
     * second instance would let {@code /skyprism hud} move a slot machine that the HUD
     * renderer is not reading.</p>
     */
    public interface Config {
        /** @return the live, mutable config every module reads */
        SkyPrismConfig get();

        /** @return the file it is persisted to, for display and for reload */
        Path path();

        /** Re-reads the file, replacing the live instance's contents. */
        void reload();

        /** Writes the live instance back, sanitised. */
        void save();
    }

    /**
     * The level-colour module's current palette, and its cache-invalidation hook.
     *
     * <p>{@code /skyprism preview} renders through exactly this palette so that what the
     * user tunes by eye is what chat and TAB will show - a preview built from a
     * separately-constructed palette would be a plausible lie.</p>
     */
    public interface Level {
        /** @return the palette chat, TAB and nametags are colouring with right now */
        LevelPalette palette();

        /**
         * Signals that config changed and every memoised component must be discarded.
         * Called by {@code /skyprism reload} and when the placement screen writes config.
         */
        void invalidate();

        /**
         * How many times the TAB and nametag memos have actually rebuilt a component.
         *
         * <p>These are the authoritative numbers for the memoisation claim, because they are
         * incremented inside the cache itself rather than by a caller who could forget. A
         * default is provided so an implementation that does not memoise anything - the
         * config-derived fallback, for one - does not have to invent a figure.</p>
         *
         * @return {@code {tabRebuilds, nameTagRebuilds}}, or {@code {-1, -1}} when the
         *         implementation does not track them
         */
        default long[] recomputeCounts() {
            return new long[] {-1L, -1L};
        }
    }

    /**
     * The Diana feature's controller, reduced to what the command tree needs.
     *
     * <p>{@link #simulate} is the reason this interface exists: Diana is only mayor for a
     * few days at a time, so without an offline driver the slot machine cannot be developed,
     * demonstrated or debugged. An implementation must bypass {@link DianaGate} - the whole
     * point is to run when the gate is shut.</p>
     */
    public interface Diana {
        /**
         * Runs the full pipeline offline: start a roll for {@code creature}, then feed it
         * {@code drops} as though they had been parsed from chat.
         *
         * @param creature the creature that "died"
         * @param drops    the rewards to lock the reels onto, never null, may be empty
         */
        void simulate(MythologicalCreature creature, List<LootDrop> drops);

        /** @return session and lifetime statistics, one display line per element */
        List<String> stats();

        /** @return the live gate, so {@code /skyprism} can report why Diana is idle */
        DianaGate gate();

        /** @return the live roll, so the status line can report what it is doing */
        SlotRoll roll();
    }

    /** The slot-machine HUD, reduced to the one thing the command tree asks of it. */
    public interface Hud {
        /**
         * Starts a self-contained demonstration spin, with no server involvement, so
         * {@code /skyprism hud} has something to position against.
         */
        void previewRoll();

        /** @return the widget's on-screen size at scale 1.0, as {@code [width, height]} */
        int[] previewSize();

        /**
         * The drop names the reels put on screen on every single spin.
         *
         * <p>Here rather than on {@code DropSymbols} because the question the status line asks
         * is not "what does the symbol table contain" but "what is the machine actually going
         * to draw" -- and the widget is the only thing that knows that. It is also the list
         * whose sprites a player looks at most, so it is the list worth reporting on.</p>
         *
         * @return the names, in reel order, never null and never empty
         */
        List<String> symbolNames();
    }

    private static Config config;
    private static Level level;
    private static Diana diana;
    private static Hud hud;

    private static Config fallbackConfig;

    /**
     * @param value the shared config manager
     */
    public static void setConfig(Config value) {
        config = value;
    }

    /**
     * @param value the level-colour renderer
     */
    public static void setLevel(Level value) {
        level = value;
    }

    /**
     * @param value the Diana controller
     */
    public static void setDiana(Diana value) {
        diana = value;
    }

    /**
     * @param value the slot-machine HUD element
     */
    public static void setHud(Hud value) {
        hud = value;
    }

    /**
     * @return the config access, never null; a private fallback when nothing registered
     */
    public static Config config() {
        if (config != null) {
            return config;
        }
        if (fallbackConfig == null) {
            fallbackConfig = new StandaloneConfig();
        }
        return fallbackConfig;
    }

    /**
     * @return the level palette source, never null; derived from config when the level
     *         module has not registered
     */
    public static Level level() {
        if (level != null) {
            return level;
        }
        return FALLBACK_LEVEL;
    }

    /** @return the Diana controller, or null when the Diana module has not registered */
    public static Diana diana() {
        return diana;
    }

    /** @return the HUD element, or null when the HUD module has not registered */
    public static Hud hud() {
        return hud;
    }

    /** @return whether the shared config manager is wired (rather than the fallback) */
    public static boolean configWired() {
        return config != null;
    }

    /** @return whether the level-colour module is wired */
    public static boolean levelWired() {
        return level != null;
    }

    /**
     * A palette built straight from the config file. Correct, but it is not the instance
     * the renderers hold, so it cannot see a runtime-only change; the status command says
     * as much when this is in use.
     */
    private static final Level FALLBACK_LEVEL = new Level() {
        @Override
        public LevelPalette palette() {
            return Palettes.fromConfig(config().get().levels);
        }

        @Override
        public void invalidate() {
            // Nothing memoises anything when the level module is absent.
        }
    };

    /**
     * The last-resort config store: the real file, the core's real codec, just not shared
     * with modules that never announced themselves.
     */
    private static final class StandaloneConfig implements Config {

        private final Path file = FabricLoader.getInstance().getConfigDir().resolve("skyprism.json");
        private SkyPrismConfig live = ConfigCodec.loadOrDefaults(file);

        @Override
        public SkyPrismConfig get() {
            return live;
        }

        @Override
        public Path path() {
            return file;
        }

        @Override
        public void reload() {
            live = ConfigCodec.loadOrDefaults(file);
        }

        @Override
        public void save() {
            try {
                ConfigCodec.save(file, live.sanitized());
            } catch (IOException failed) {
                // Reported to the player by the caller; swallowing here keeps a full disk
                // from turning a cosmetic command into a crash.
                throw new UncheckedConfigWriteException(failed);
            }
        }
    }

    /**
     * Signals that the config file could not be written. Unchecked so the {@link Config}
     * interface stays free of a checked exception that only one implementation can throw.
     */
    public static final class UncheckedConfigWriteException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param cause the underlying I/O failure
         */
        public UncheckedConfigWriteException(IOException cause) {
            super(cause);
        }
    }
}
