package com.skyprism.mc.command;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.DianaGate;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.util.TimeFormat;
import com.skyprism.mc.config.ConfigManager;
import com.skyprism.mc.diana.DianaController;
import com.skyprism.mc.diana.DianaStats;
import com.skyprism.mc.surfaces.LevelSurfaces;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts the sibling modules onto {@link SkyPrismServices}.
 *
 * <p><b>This is the only file in the command module that names another module's classes.</b>
 * That is the whole design: every other class here speaks in core types, so if
 * {@code ConfigManager} or {@code DianaController} is renamed or reshaped, exactly one file
 * fails to compile and exactly one file needs editing. The command tree itself does not
 * move.</p>
 *
 * <p><b>Why bind here rather than in the mod initialiser.</b> The initialiser is not owned
 * by this module, and a command tree that silently does nothing because someone forgot four
 * lines of wiring is a bad default. {@link SkyPrismCommands#register()} calls
 * {@link #install()} itself, so registering the commands is sufficient to make all nine
 * subcommands work. An integrator who wants different wiring can still call
 * {@code SkyPrismServices.setX(...)} afterwards and win, because the setters simply
 * overwrite.</p>
 */
public final class DefaultBindings {

    private DefaultBindings() {
    }

    private static boolean installed;

    /**
     * Points the command tree at the live config manager, level palette and Diana
     * controller. Idempotent.
     *
     * <p>The slot-machine HUD is deliberately not bound: no HUD module exists at the time of
     * writing, and {@code /skyprism hud} already draws its own stand-in footprint rather than
     * depending on one. When the HUD module arrives it need only implement
     * {@link SkyPrismServices.Hud} and add one line here.</p>
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;

        SkyPrismServices.setConfig(new ConfigManagerAdapter());
        SkyPrismServices.setLevel(new LevelAdapter());
        SkyPrismServices.setDiana(new DianaAdapter());
    }

    /** The config manager is already exactly the shape {@link SkyPrismServices.Config} wants. */
    private static final class ConfigManagerAdapter implements SkyPrismServices.Config {

        @Override
        public SkyPrismConfig get() {
            return ConfigManager.get().config();
        }

        @Override
        public Path path() {
            return ConfigManager.get().file();
        }

        @Override
        public void reload() {
            ConfigManager.get().load();
        }

        @Override
        public void save() {
            ConfigManager.get().save();
        }
    }

    /**
     * The palette read per call, never cached.
     *
     * <p>{@code ConfigManager.palette()} is documented as a volatile field load returning an
     * immutable object that is replaced whenever config changes. Holding the reference
     * across frames would freeze the preview screen on a stale ramp the moment somebody
     * changed a setting from the config GUI while the preview was open - which is exactly
     * the workflow the preview exists to support.</p>
     */
    private static final class LevelAdapter implements SkyPrismServices.Level {

        @Override
        public LevelPalette palette() {
            return ConfigManager.get().palette();
        }

        @Override
        public void invalidate() {
            // refresh() re-derives the palette and locator and bumps the generation counter,
            // which is the cache key every memoised surface in the mod is keyed on. Bumping
            // it is what makes a reload visible in TAB without restarting.
            ConfigManager.get().refresh();
        }

        @Override
        public long[] recomputeCounts() {
            return LevelSurfaces.recomputeCounts();
        }
    }

    /**
     * The Diana controller, plus the one piece of translation this seam actually needs:
     * turning {@link DianaStats} into display lines.
     */
    private static final class DianaAdapter implements SkyPrismServices.Diana {

        @Override
        public void simulate(MythologicalCreature creature, List<LootDrop> drops) {
            DianaController.get().simulate(creature, drops);
        }

        @Override
        public DianaGate gate() {
            return DianaController.get().gate();
        }

        @Override
        public SlotRoll roll() {
            return DianaController.get().roll();
        }

        /**
         * Formats the statistics for chat.
         *
         * <p>Kept here rather than on {@code DianaStats} because it is a presentation
         * decision belonging to the command that prints it - what to show, in what order,
         * and what to leave out when a player has thirty distinct drops and eight lines of
         * chat to show them in.</p>
         *
         * <p>Each line is resolved from a {@code skyprism.command.stats.*} key and flattened
         * to a String here, because the interface this implements deals in plain lines. The
         * drop and creature names inside them are Hypixel's own text, so they pass through
         * untranslated by design.</p>
         */
        @Override
        public List<String> stats() {
            DianaStats stats = DianaController.get().stats();
            if (stats == null) {
                return List.of();
            }

            List<String> lines = new ArrayList<>(8);

            long since = stats.firstRecordedAt();
            String age = since <= 0L
                    ? line("no_history")
                    : line("tracking_for",
                            TimeFormat.shortDuration(System.currentTimeMillis() - since));
            lines.add(line("totals", String.valueOf(stats.totalKills()),
                    String.valueOf(stats.rolls()), String.valueOf(stats.jackpots()), age));

            if (stats.rolls() > 0) {
                lines.add(line("jackpot_rate", String.format(Locale.ROOT, "%.1f",
                        100.0 * stats.jackpots() / stats.rolls())));
            }

            String kills = topEntries(stats.killsByName(), 5);
            if (!kills.isEmpty()) {
                lines.add(line("kills", kills));
            }

            String drops = topEntries(stats.drops(), 6);
            if (!drops.isEmpty()) {
                lines.add(line("drops", drops));
            }

            for (String note : stats.notes()) {
                lines.add(line("note", note));
            }

            lines.add(line("file", String.valueOf(stats.file())));
            return List.copyOf(lines);
        }

        /** One {@code skyprism.command.stats.*} line, resolved to plain text. */
        private static String line(String suffix, Object... args) {
            return Component.translatable("skyprism.command.stats." + suffix, args).getString();
        }

        /**
         * The busiest few entries of a count map, largest first.
         *
         * <p>Truncated on purpose. A long Diana session accumulates dozens of distinct drop
         * names, and dumping all of them into chat pushes everything else off the screen -
         * the tail is what the JSON file is for.</p>
         */
        private static String topEntries(Map<String, Integer> counts, int limit) {
            if (counts == null || counts.isEmpty()) {
                return "";
            }
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
            entries.sort((a, b) -> {
                int byCount = Integer.compare(b.getValue(), a.getValue());
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            });

            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Integer> entry : entries) {
                if (shown == limit) {
                    out.append(line("more", String.valueOf(entries.size() - shown)));
                    break;
                }
                if (shown > 0) {
                    out.append(", ");
                }
                out.append(line("entry", entry.getKey(), String.valueOf(entry.getValue())));
                shown++;
            }
            return out.toString();
        }
    }
}
