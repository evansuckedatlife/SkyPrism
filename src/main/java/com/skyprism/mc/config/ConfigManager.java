package com.skyprism.mc.config;

import com.skyprism.core.config.ConfigCodec;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.level.ChromaClock;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single owner of SkyPrism's live settings, and the only thing the chat, TAB,
 * nametag, HUD and command adapters are allowed to ask about configuration.
 *
 * <p><b>Why a manager at all, when the core already has {@code SkyPrismConfig}?</b>
 * Because the core deliberately stops at "a bag of validated data". Three things still
 * have to happen on the Minecraft side, and none of them belong in a tested,
 * Minecraft-free module: deciding <em>where</em> the file lives (a Fabric concept),
 * turning the settings into the derived objects the renderers actually call
 * ({@link LevelPalette}, {@link LevelTagLocator}), and telling every cache in the mod
 * that its contents just went stale. This class does exactly those three.</p>
 *
 * <p><b>The generation counter is the load-bearing part.</b> The TAB list is re-rendered
 * for up to eighty players every single frame, so the level-colour adapters memoise their
 * work keyed on {@code (source component identity, generation)}. If {@link #generation()}
 * failed to move after a settings change, those caches would happily serve last minute's
 * colours forever. So every path that can alter the configuration -- {@link #load()},
 * {@link #save()}, {@link #apply(SkyPrismConfig)} and {@link #refresh()} -- bumps it, and
 * rebuilds the derived palette and locator in the same step. A cache only ever has to
 * compare one {@code int}.</p>
 *
 * <p><b>Immutability where it matters.</b> {@link #config()}, {@link #palette()} and
 * {@link #locator()} are read on the render thread while the config screen's Save button
 * is being pressed on that same thread between frames. Each is a {@code volatile}
 * reference to an already-finished object swapped in a single assignment, so a frame can
 * never observe a half-applied settings change: it sees either the whole old
 * configuration or the whole new one.</p>
 *
 * <p><b>Sanitisation is not optional here.</b> {@link #config()} is documented as always
 * sanitized, so everything entering this class goes through
 * {@link SkyPrismConfig#sanitized()} first -- including the object the YACL screen hands
 * back, which is a live draft the user has been typing into and may carry an inverted
 * level range or a chroma rate of zero. Downstream adapters therefore never have to
 * defend against nonsense values.</p>
 *
 * <p>Client-thread class. The listener list is copy-on-write and the published references
 * are volatile purely so that a stray read from another thread cannot see a torn state;
 * that is belt and braces, not a licence to mutate from off-thread.</p>
 */
public final class ConfigManager {

    /** Subfolder of the Fabric config directory, so SkyPrism owns a folder rather than a loose file. */
    public static final String CONFIG_FOLDER = "skyprism";

    /** File name inside {@link #CONFIG_FOLDER}. */
    public static final String CONFIG_FILE = "config.json";

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Config");

    /**
     * Lazy holder. Resolving the config path touches {@link FabricLoader}, so deferring
     * construction to first use keeps merely mentioning this class harmless in a context
     * where the loader is not initialised.
     */
    private static final class Holder {
        static final ConfigManager INSTANCE = new ConfigManager(resolveFile());
    }

    private final Path file;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile SkyPrismConfig config;
    private volatile LevelPalette palette;
    private volatile LevelTagLocator locator;
    private volatile int generation;

    private ConfigManager(Path file) {
        this.file = file;
        // Start from defaults rather than null so an adapter reading config() before load()
        // has run gets working colours instead of a NullPointerException.
        this.config = SkyPrismConfig.defaults().sanitized();
        this.palette = buildPalette(this.config);
        this.locator = this.config.levels.resolveLocator();
        this.generation = 1;
    }

    /**
     * The process-wide instance.
     *
     * @return the manager, constructed on first call; never null
     */
    public static ConfigManager get() {
        return Holder.INSTANCE;
    }

    /**
     * The live settings.
     *
     * <p>Never null, and always the output of {@link SkyPrismConfig#sanitized()}, so every
     * numeric field is already inside its documented bounds and every enum is non-null.
     * Read the fields freely; if you <em>write</em> them, call {@link #save()} afterwards
     * or the derived palette, the locator and every cache keyed on {@link #generation()}
     * will disagree with what you just set.</p>
     *
     * @return the current configuration
     */
    public SkyPrismConfig config() {
        return config;
    }

    /**
     * The palette derived from the current level settings.
     *
     * <p>Rebuilt whenever the configuration changes, so read it per frame rather than
     * caching the reference across frames. Reading it is one volatile field load; the
     * object behind it is immutable.</p>
     *
     * @return the palette a renderer calls {@code colorFor} on; never null
     */
    public LevelPalette palette() {
        return palette;
    }

    /**
     * The tag detector for the configured sanity range.
     *
     * @return the locator matching {@code config().levels.minLevel..maxLevel}; never null
     */
    public LevelTagLocator locator() {
        return locator;
    }

    /**
     * A counter that changes every time anything about the configuration changes.
     *
     * <p>This is the cache key for every memoised render in the mod. It is monotonic within
     * a session and is deliberately not derived from the config's contents: two different
     * configurations that happened to hash alike would still have to invalidate, and
     * re-deriving a hash over the whole settings tree every frame would defeat the point of
     * caching in the first place.</p>
     *
     * @return the current generation; compare for inequality, never for ordering
     */
    public int generation() {
        return generation;
    }

    /**
     * The file this manager reads and writes.
     *
     * @return the absolute or working-directory-relative config path; never null
     */
    public Path file() {
        return file;
    }

    /**
     * The minimum gap between chroma repaints, derived from {@code chromaUpdateHz}.
     *
     * <p>Offered here so the TAB and nametag adapters cap their re-render rate off one
     * agreed number instead of each inventing a constant. At the default 30 Hz a shimmering
     * TAB entry is rebuilt at most once every 33 ms no matter how fast the client renders.</p>
     *
     * @return milliseconds between permitted chroma repaints; at least 4
     */
    public long chromaFrameIntervalMillis() {
        int hz = config.levels.chromaUpdateHz;
        return hz <= 0 ? 1000L : Math.max(4L, 1000L / hz);
    }

    /**
     * Reads the file from disk and publishes what it finds.
     *
     * <p>Delegates every hard case -- missing file, unparseable file, older schema, a file
     * written by a newer SkyPrism -- to {@link ConfigCodec#load(Path)}, which recovers
     * rather than throws and preserves a corrupt file aside instead of overwriting it. When
     * the load was anything other than a clean read, the normalised result is written
     * straight back, so a crash before the next save cannot silently lose a migration.</p>
     */
    public void load() {
        ConfigCodec.LoadResult result = ConfigCodec.load(file);
        for (String note : result.notes()) {
            LOGGER.info("config: {}", note);
        }
        result.preservedAs().ifPresent(kept ->
                LOGGER.warn("config was unreadable; the original was preserved at {}", kept));
        adopt(result.config());

        if (result.status() != ConfigCodec.Status.LOADED) {
            write();
        }
    }

    /**
     * Re-sanitises the current settings, republishes everything derived from them, and
     * writes the file.
     *
     * <p>Call this after mutating {@link #config()} in place -- from a keybind toggle or a
     * command, say. It is the same path the config screen takes, so a setting changed from
     * chat and a setting changed from the GUI invalidate caches identically.</p>
     */
    public void save() {
        apply(config);
    }

    /**
     * Adopts an edited copy of the settings, then saves.
     *
     * <p>This is what the YACL screen calls. The screen binds its controls to a detached
     * {@link #draft()} so a half-finished edit is never visible to the renderers, and hands
     * the finished draft back here in one go when the user presses Save.</p>
     *
     * @param edited the edited settings; null is ignored rather than treated as a request
     *               to wipe the configuration
     */
    public void apply(SkyPrismConfig edited) {
        if (edited == null) {
            return;
        }
        adopt(edited);
        write();
    }

    /**
     * Republishes the derived objects and bumps {@link #generation()} without touching disk.
     *
     * <p>For changes that are deliberately session-only, and for forcing every cache in the
     * mod to drop its contents.</p>
     */
    public void refresh() {
        adopt(config);
    }

    /**
     * A detached, mutable copy of the current settings for a config screen to bind to.
     *
     * @return a copy no renderer can see, safe to mutate field by field
     */
    public SkyPrismConfig draft() {
        return config.copy();
    }

    /**
     * Registers a callback fired after every change, once the new configuration, palette,
     * locator and generation are all already visible.
     *
     * <p>Ordering matters: a listener that re-reads {@link #palette()} must not be able to
     * observe the old one, so the swap happens first and the notification second. A
     * listener that throws is logged and skipped -- one broken cache must not stop the
     * others from being invalidated.</p>
     *
     * @param listener the callback; null is ignored
     */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ---------------------------------------------------------------- internals

    /** Sanitise, rebuild the derived objects, publish them, bump the generation, then notify. */
    private void adopt(SkyPrismConfig raw) {
        SkyPrismConfig clean = (raw == null ? SkyPrismConfig.defaults() : raw).sanitized();
        LevelPalette newPalette = buildPalette(clean);
        LevelTagLocator newLocator = clean.levels.resolveLocator();

        this.config = clean;
        this.palette = newPalette;
        this.locator = newLocator;
        this.generation++;

        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException broken) {
                LOGGER.error("a config change listener threw; the others still ran", broken);
            }
        }
    }

    /**
     * Assembles the palette the renderers call.
     *
     * <p>The core has no {@code resolvePalette()} on purpose: {@link LevelPalette}'s
     * constructor is strict about which component each mode requires, and the mapping from
     * "settings the user edits" to "objects the renderer needs" is adapter policy. Both
     * {@code resolveRamp()} and {@code resolveTable()} are documented never to throw, so the
     * only realistic failure is {@link ChromaClock} rejecting a rate; that is caught here
     * because a bad shimmer setting must degrade to a static palette, never to a crash on
     * the render thread.</p>
     */
    private static LevelPalette buildPalette(SkyPrismConfig cfg) {
        SkyPrismConfig.LevelSettings levels = cfg.levels;
        try {
            // Saturation and lightness come from the settings rather than from constants
            // here: the sanitiser has already clamped both into the 0..1 band ChromaClock
            // insists on, so this adapter has no policy of its own left to apply.
            ChromaClock chroma = levels.chromaEnabled
                    ? new ChromaClock(levels.chromaCyclesPerSecond,
                            levels.chromaSaturation, levels.chromaLightness)
                    : null;
            return new LevelPalette(levels.mode, levels.resolveRamp(), levels.resolveTable(),
                    levels.chromaEnabled, levels.chromaMinLevel, chroma);
        } catch (RuntimeException unusable) {
            LOGGER.warn("level settings could not be turned into a palette; using defaults", unusable);
            return LevelPalette.defaults();
        }
    }

    /** Writes the current config. An IO failure is logged, never propagated: it must not eat a keypress. */
    private void write() {
        try {
            ConfigCodec.save(file, config);
        } catch (IOException failed) {
            LOGGER.error("could not write {}", file, failed);
        } catch (RuntimeException failed) {
            LOGGER.error("unexpected failure writing {}", file, failed);
        }
    }

    /**
     * The config path, from Fabric when it is available.
     *
     * <p>The fallback is not defensive theatre: this class is reachable from very early
     * init and from harnesses with no loader, and a hard failure here would take the whole
     * mod down over a settings file.</p>
     */
    private static Path resolveFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FOLDER).resolve(CONFIG_FILE);
        } catch (RuntimeException | LinkageError noLoader) {
            LOGGER.warn("Fabric config directory unavailable; falling back to ./config/{}", CONFIG_FOLDER);
            return Paths.get("config", CONFIG_FOLDER, CONFIG_FILE);
        }
    }
}
