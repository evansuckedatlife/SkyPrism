package com.skyprism.mc.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.DianaGate;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.RollState;
import com.skyprism.core.config.HudAnchor;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceInfo;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.mc.hud.LootMachine;
import com.skyprism.mc.hud.SourceCategory;
import com.skyprism.core.util.TimeFormat;
import com.skyprism.mc.symbols.DropSymbols;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /skyprism} client command tree.
 *
 * <p><b>Client-only, and structurally so.</b> Registration goes through
 * {@code ClientCommandRegistrationCallback}, feedback goes through
 * {@code FabricClientCommandSource.sendFeedback}, and nothing here ever obtains a packet
 * sender or a server command source. The one place this rule was easy to break by accident
 * - a clickable "run this command" link, which the vanilla click handler dispatches
 * <em>to the server</em> - is handled in {@link Feedback#suggestion}, which suggests rather
 * than runs.</p>
 *
 * <p><b>What this tree is for.</b> Two of the mod's features are conditional on the world:
 * Diana is only mayor for a few days at a time, and level colours only show up when other
 * players with level tags are around. Without {@code simulate} and {@code replay} neither
 * feature could be developed, demonstrated or bug-reported except by waiting for Hypixel.
 * {@code profile} exists for the same reason in the other direction: "no FPS impact" is a
 * claim, and a claim with no counter behind it is marketing.</p>
 *
 * <p><b>Failure handling.</b> Every executor returns a value rather than throwing, and every
 * foreseeable bad input - an unknown creature, a missing file, an unreadable file, an
 * unregistered module - produces one sentence saying what went wrong and what would work
 * instead. A Brigadier exception would surface to the player as a red wall of parser
 * internals, which is not a usable error message for a cosmetic mod.</p>
 *
 * <p><b>Every printed string is a translation key.</b> The English copy is in
 * {@code assets/skyprism/lang/en_us.json} under {@code skyprism.command.*}. A line is one
 * {@code Component.translatable} whose variable parts are arguments, never a chain of
 * translated fragments joined with {@code append}: word order differs between languages and
 * a sentence assembled from pieces cannot be reordered. See {@link Feedback} for the
 * colouring rules that let a single-key sentence still read as label-and-value.</p>
 */
public final class SkyPrismCommands {

    private SkyPrismCommands() {
    }

    /** Cancellation token for scheduled replay lines; a second replay supersedes the first. */
    private static final String REPLAY_GROUP = "skyprism:replay";

    /** Ticks between replayed lines. Four ticks is about the pace Hypixel sends a burst. */
    private static final int REPLAY_TICK_SPACING = 4;

    /** Guard against a pasted log with a million lines eating the tick queue. */
    private static final int REPLAY_MAX_LINES = 2_000;

    /**
     * The widest level range {@code /skyprism preview} will draw. Named rather than inlined
     * because the number appears in the refusal message and the two must not drift.
     */
    private static final int MAX_PREVIEW_SPAN = 20_000;

    private static final List<String> CREATURE_TOKENS = creatureTokens();

    /** Every {@link LootSource} as a typeable id, for tab completion. */
    private static final List<String> SOURCE_TOKENS = sourceTokens();

    /**
     * What {@code /skyprism simulate} completes: creatures first, then every source.
     *
     * <p>Creatures lead because {@code simulate inq} was the shipped spelling and is the one in
     * everybody's muscle memory; it still resolves, and still means what it always did.</p>
     */
    private static final List<String> SIMULATE_TOKENS = simulateTokens();

    /** The four {@link RollPolicy} values, lower-cased. */
    private static final List<String> POLICY_TOKENS = policyTokens();

    /**
     * Separates a simulated event's subject from its drops.
     *
     * <p>A pipe rather than a second Brigadier argument because both halves are free text with
     * spaces in them, and Brigadier's greedy string can only ever be last. It is also visible: a
     * player who mistypes gets a caption that reads like their whole tail, which is a legible
     * mistake, rather than drops that silently vanished.</p>
     */
    private static final char SUBJECT_DROP_SEPARATOR = '|';

    /** Shorthand for the command tree's key namespace; shared with {@link Feedback}. */
    private static final String K = Feedback.K;

    /**
     * Registers the tree. Call once from the client initialiser.
     *
     * <p>Also installs {@link DefaultBindings}, which points the tree at the live config
     * manager and Diana controller, and {@link ClientScheduler}, which drives replay pacing
     * and the profiler's per-second windows. Both live here rather than in the initialiser so
     * that this module stays self-contained: registering the commands is all that is needed
     * to make every subcommand work, and nothing outside it has to know it wants a tick.</p>
     */
    public static void register() {
        DefaultBindings.install();
        ClientScheduler.install();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(ClientCommands.<FabricClientCommandSource>literal("skyprism")
                        .executes(SkyPrismCommands::status)

                        .then(ClientCommands.literal("preview")
                                // Not a literal 600. That was the second copy of the range rule,
                                // and it drifted the moment the chroma default moved to 600: the
                                // grid stopped at the threshold, so the one screen built to show
                                // the shimmer opened with no shimmer in it. The screen derives its
                                // own top from the live threshold; this asks it for the same one.
                                .executes(ctx -> preview(ctx, 0, LevelPreviewScreen.defaultMaxLevel()))
                                .then(ClientCommands.argument("min", IntegerArgumentType.integer(0, 999_999))
                                        .then(ClientCommands.argument("max", IntegerArgumentType.integer(0, 999_999))
                                                .executes(ctx -> preview(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "min"),
                                                        IntegerArgumentType.getInteger(ctx, "max"))))))

                        .then(ClientCommands.literal("hud")
                                .executes(SkyPrismCommands::hud))

                        .then(ClientCommands.literal("simulate")
                                .executes(SkyPrismCommands::simulateHelp)
                                .then(ClientCommands.argument("source", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(SIMULATE_TOKENS, builder))
                                        .executes(ctx -> simulate(ctx,
                                                StringArgumentType.getString(ctx, "source"), null))
                                        .then(ClientCommands.argument("tail", StringArgumentType.greedyString())
                                                .executes(ctx -> simulate(ctx,
                                                        StringArgumentType.getString(ctx, "source"),
                                                        StringArgumentType.getString(ctx, "tail"))))))

                        .then(ClientCommands.literal("sources")
                                .executes(ctx -> sources(ctx, null))
                                .then(ClientCommands.argument("filter", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(SOURCE_TOKENS, builder))
                                        .executes(ctx -> sources(ctx,
                                                StringArgumentType.getString(ctx, "filter")))))

                        .then(ClientCommands.literal("loot")
                                .executes(SkyPrismCommands::lootHelp)
                                .then(ClientCommands.literal("interval")
                                        .executes(ctx -> lootInterval(ctx, -1))
                                        .then(ClientCommands.argument("millis",
                                                        IntegerArgumentType.integer(0, 30_000))
                                                .executes(ctx -> lootInterval(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "millis")))))
                                .then(ClientCommands.literal("policy")
                                        .then(ClientCommands.argument("source", StringArgumentType.word())
                                                .suggests((ctx, builder) ->
                                                        SharedSuggestionProvider.suggest(SOURCE_TOKENS, builder))
                                                .then(ClientCommands.argument("policy", StringArgumentType.word())
                                                        .suggests((ctx, builder) ->
                                                                SharedSuggestionProvider.suggest(POLICY_TOKENS, builder))
                                                        .executes(ctx -> lootPolicy(ctx,
                                                                StringArgumentType.getString(ctx, "source"),
                                                                StringArgumentType.getString(ctx, "policy")))))))

                        .then(ClientCommands.literal("replay")
                                .executes(SkyPrismCommands::replayHelp)
                                .then(ClientCommands.literal("stop")
                                        .executes(SkyPrismCommands::replayStop))
                                .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                        .executes(ctx -> replay(ctx,
                                                StringArgumentType.getString(ctx, "file")))))

                        .then(ClientCommands.literal("stats")
                                .executes(SkyPrismCommands::stats))

                        .then(ClientCommands.literal("profile")
                                .executes(SkyPrismCommands::profile)
                                .then(ClientCommands.literal("reset")
                                        .executes(SkyPrismCommands::profileReset))
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> profileEnable(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> profileEnable(ctx, false))))

                        .then(ClientCommands.literal("reload")
                                .executes(SkyPrismCommands::reload))));
    }

    // ======================================================================
    //  /skyprism
    // ======================================================================

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        SkyPrismConfig config = SkyPrismServices.config().get();

        Feedback.heading(source, Component.translatable(K + "status.heading",
                modVersion(), minecraftVersion()));

        LevelPalette palette = SkyPrismServices.level().palette();
        Feedback.detail(source, Component.translatable(K + "status.levels",
                        Feedback.onOff(config.levels.enabled),
                        modeName(palette.mode()).withColor(Feedback.VALUE),
                        value(String.valueOf(config.levels.gradientPreset)))
                .withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "status.surfaces",
                        Feedback.onOff(config.levels.applyToChat),
                        Feedback.onOff(config.levels.applyToTabList),
                        Feedback.onOff(config.levels.applyToNameTags))
                .withColor(Feedback.LABEL));
        Feedback.detail(source, (palette.chromaEnabled()
                ? Component.translatable(K + "status.chroma.on",
                        value(String.valueOf(palette.chromaMinLevel())).withColor(Feedback.GOOD),
                        value(fixed(config.levels.chromaCyclesPerSecond)).withColor(Feedback.GOOD))
                : Component.translatable(K + "status.chroma.off"))
                .withColor(Feedback.LABEL));

        Feedback.detail(source, Component.translatable(K + "status.diana",
                        Feedback.onOff(config.diana.enabled),
                        value(String.valueOf(config.diana.reelCount)),
                        triggerSummary(config))
                .withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "status.gate", gateSummary())
                .withColor(Feedback.LABEL));
        lootSourceLines(source);
        symbolLines(source);

        int hudColor = config.hud.enabled ? Feedback.VALUE : Feedback.OFF;
        Feedback.detail(source, Component.translatable(K + "status.hud",
                        Feedback.onOff(config.hud.enabled),
                        value(String.format(Locale.ROOT, "%.3f", config.hud.x)).withColor(hudColor),
                        value(String.format(Locale.ROOT, "%.3f", config.hud.y)).withColor(hudColor),
                        value(String.format(Locale.ROOT, "%.2f", config.hud.scale)).withColor(hudColor),
                        anchorName(config.hud.anchor).withColor(hudColor))
                .withColor(Feedback.LABEL));

        Feedback.detail(source, Component.translatable(K + "status.config",
                        Feedback.copyable(SkyPrismServices.config().path().toString(), Feedback.VALUE))
                .withColor(Feedback.LABEL));

        if (!SkyPrismServices.configWired() || !SkyPrismServices.levelWired()
                || SkyPrismServices.diana() == null || SkyPrismServices.hud() == null) {
            Feedback.detail(source, Component.translatable(K + "status.unwired", unwiredSummary())
                    .withColor(Feedback.WARN));
        }

        Feedback.detail(source, Component.translatable(K + "status.try",
                        Feedback.suggestion("preview", "/skyprism preview", Feedback.ACCENT),
                        Feedback.suggestion("hud", "/skyprism hud", Feedback.ACCENT),
                        Feedback.suggestion("simulate inq", "/skyprism simulate inq", Feedback.ACCENT),
                        Feedback.suggestion("sources", "/skyprism sources", Feedback.ACCENT),
                        Feedback.suggestion("profile", "/skyprism profile", Feedback.ACCENT))
                .withColor(Feedback.LABEL));
        return 1;
    }

    /**
     * Where the slot machine's item sprites are coming from.
     *
     * <p><b>The question this line exists to answer.</b> SkyBlock dresses its items through
     * Hypixel's own server resource pack, and the pack only ever finds a stack that carries the
     * {@code minecraft:item_model} component Hypixel set on it. {@code DropSymbols} cannot
     * invent that component -- there is no mapping anywhere from "Daedalus Stick" to
     * {@code hypixel_skyblock:item/.../daedalus_blade}, the pack's paths are semantic rather
     * than named after anything a player sees -- so it learns the component off the real stack
     * the first time the drop actually lands and remembers it from then on.</p>
     *
     * <p>That makes "my reel is drawing a plain vanilla stick" ambiguous from the outside, and
     * the two causes have opposite fixes. If the symbol is FALLBACK the mod has simply never
     * seen the drop yet and the answer is to go and get one; if it is REAL or LEARNED then the
     * component is there, the sprite really is being resolved through the active pack, and
     * whatever is missing is missing from Hypixel's pack rather than from here. Nothing else in
     * {@code /skyprism status} can separate those, which is why the counts are worth a line.</p>
     *
     * <p>The hint under the counts is printed only when something is actually unresolved, on
     * the same rule as {@link #unwiredSummary()}: a status command that repeats an explanation
     * of a state you are not in teaches the reader to stop reading it.</p>
     */
    private static void symbolLines(FabricClientCommandSource source) {
        SkyPrismServices.Hud hud = SkyPrismServices.hud();
        if (hud != null) {
            List<String> names = hud.symbolNames();
            int real = 0;
            int learned = 0;
            int fallback = 0;
            int none = 0;
            for (String name : names) {
                switch (DropSymbols.sourceFor(name)) {
                    case REAL -> real++;
                    case LEARNED -> learned++;
                    case FALLBACK -> fallback++;
                    case NONE -> none++;
                }
            }

            // Green only for the two tiers that actually carry Hypixel's component. A fallback is
            // not an error -- every symbol is a fallback until the player has met the drop once --
            // so it takes the muted colour rather than the warning one, and only a symbol with no
            // sprite at all is worth alarming anybody about.
            Feedback.detail(source, Component.translatable(K + "status.symbols",
                            count(real, Feedback.GOOD),
                            count(learned, Feedback.GOOD),
                            count(fallback, Feedback.OFF),
                            count(none, Feedback.WARN),
                            count(names.size(), Feedback.VALUE))
                    .withColor(Feedback.LABEL));

            if (fallback + none > 0) {
                Feedback.detail(source, Component.translatable(K + "status.symbols.hint")
                        .withColor(Feedback.OFF));
            }
        }

        // The line above counts every name the reel strips can put on screen -- the union across
        // all sixty-four sources, not one strip's ten, since the strips went per source. What the
        // mod has learned is still not bounded by even that union. Without
        // this second line a player who has just captured a drop that is not on the strip sees
        // no evidence of it anywhere, and "the capture never fired" and "the capture fired for
        // something the first line does not cover" look identical.
        Feedback.detail(source, Component.translatable(K + "status.symbols.memory",
                        count(DropSymbols.capturedCount(), Feedback.GOOD),
                        count(DropSymbols.learnedCount(), Feedback.GOOD))
                .withColor(Feedback.LABEL));
    }

    /** A count in a colour chosen by the caller; zero is never worth highlighting. */
    private static MutableComponent count(int n, int color) {
        return Component.literal(Integer.toString(n)).withColor(n == 0 ? Feedback.OFF : color);
    }

    /** A value in the value colour, ready to drop into a {@code translatable} argument. */
    private static MutableComponent value(String text) {
        return Component.literal(text).withColor(Feedback.VALUE);
    }

    /**
     * A colouring mode's user-facing name, falling back to the raw constant so a mode added
     * to the core stays readable before its key is written.
     */
    private static MutableComponent modeName(LevelColorMode mode) {
        return Component.translatableWithFallback(
                "skyprism.common.mode." + mode.name().toLowerCase(Locale.ROOT), mode.name());
    }

    /** A HUD anchor's user-facing name, with the same fallback rule as {@link #modeName}. */
    private static MutableComponent anchorName(HudAnchor anchor) {
        return anchor == null
                ? Component.literal("-")
                : Component.translatableWithFallback(
                        "skyprism.common.anchor." + anchor.name().toLowerCase(Locale.ROOT), anchor.name());
    }

    /**
     * The trigger set as one comma-joined component.
     *
     * <p>Built by appending rather than from a key per entry because this is a list, not a
     * sentence: the separator is punctuation and the entries are proper nouns. Sorted on the
     * rendered names so the order matches what the reader sees, not the enum's order.</p>
     */
    private static Component triggerSummary(SkyPrismConfig config) {
        if (config.diana.triggers == null || config.diana.triggers.isEmpty()) {
            return Component.translatable("skyprism.common.none").withColor(Feedback.VALUE);
        }
        List<MutableComponent> names = new ArrayList<>(config.diana.triggers.size());
        for (MythologicalCreature creature : config.diana.triggers) {
            names.add(Feedback.creature(creature));
        }
        names.sort((a, b) -> a.getString().compareTo(b.getString()));
        return join(names).withColor(Feedback.VALUE);
    }

    /**
     * The whole-game half of the machine, in two lines.
     *
     * <p>The brief for {@code status} is that a source which never fires can be diagnosed from it,
     * and the two facts that separate the four reasons a source is silent are how many are armed
     * and which gates are open right now. Those go here. The per-source table does not: sixty-four
     * rows would bury every other line in this command, so the second line names
     * {@code /skyprism sources} and the drill-down lives there.</p>
     *
     * <p>When a gate is open the sources are listed by name, because that short list is the
     * actionable one -- it is exactly what could spin the machine where the player is standing.</p>
     */
    private static void lootSourceLines(FabricClientCommandSource source) {
        LootMachine machine = LootMachine.get();
        int armed = machine.armedSourceCount();
        List<LootSource> open = machine.openSources();

        Feedback.detail(source, Component.translatable(K + "status.loot",
                        value(String.valueOf(armed)),
                        value(String.valueOf(LootSource.values().length - 1)),
                        value(String.valueOf(open.size())))
                .withColor(Feedback.LABEL));

        if (!open.isEmpty()) {
            List<MutableComponent> names = new ArrayList<>(open.size());
            for (LootSource candidate : open) {
                names.add(Component.literal(candidate.id()).withColor(Feedback.GOOD));
            }
            Feedback.detail(source, Component.translatable(K + "status.loot.open", join(names))
                    .withColor(Feedback.LABEL));
        } else {
            Feedback.detail(source, Component.translatable(K + "status.loot.shut",
                            contextSummary(machine),
                            Feedback.suggestion("/skyprism sources all",
                                    "/skyprism sources all", Feedback.ACCENT))
                    .withColor(Feedback.LABEL));
        }
    }

    /** Joins components with ", ". The comma is punctuation, so no key is involved. */
    private static MutableComponent join(List<? extends Component> parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Component.literal(", "));
            }
            out.append(parts.get(i));
        }
        return out;
    }

    private static Component gateSummary() {
        SkyPrismServices.Diana diana = SkyPrismServices.diana();
        if (diana == null) {
            return Component.translatable(K + "status.gate.unregistered").withColor(Feedback.WARN);
        }
        DianaGate gate = diana.gate();
        if (gate == null) {
            return Component.translatable(K + "status.gate.missing").withColor(Feedback.WARN);
        }
        if (gate.isOpen()) {
            SlotRoll roll = diana.roll();
            RollState state = roll == null ? RollState.IDLE : roll.state();
            return Component.translatable(K + "status.gate.open", state.name()).withColor(Feedback.GOOD);
        }
        // Name the failing condition, not just "closed". "Diana is not the mayor" and "SkyPrism
        // cannot read the mayor row out of TAB" are the same symptom from the outside -- nothing
        // ever happens -- and have completely different fixes.
        return Component.translatable(K + "status.gate.closed", gate.describe())
                .withColor(Feedback.OFF);
    }

    private static Component unwiredSummary() {
        List<MutableComponent> missing = new ArrayList<>(4);
        if (!SkyPrismServices.configWired()) {
            missing.add(Component.translatable(K + "status.unwired.config"));
        }
        if (!SkyPrismServices.levelWired()) {
            missing.add(Component.translatable(K + "status.unwired.levels"));
        }
        if (SkyPrismServices.diana() == null) {
            missing.add(Component.translatable(K + "status.unwired.diana"));
        }
        if (SkyPrismServices.hud() == null) {
            missing.add(Component.translatable(K + "status.unwired.hud"));
        }
        return join(missing);
    }

    // ======================================================================
    //  /skyprism preview
    // ======================================================================

    private static int preview(CommandContext<FabricClientCommandSource> ctx, int min, int max) {
        FabricClientCommandSource source = ctx.getSource();
        if (max < min) {
            Feedback.error(source, Component.translatable(K + "preview.range_inverted",
                    String.valueOf(max), String.valueOf(min)));
            return 0;
        }
        if (max - min > MAX_PREVIEW_SPAN) {
            Feedback.error(source, Component.translatable(K + "preview.range_too_large",
                    String.valueOf(max - min + 1), String.valueOf(MAX_PREVIEW_SPAN)));
            return 0;
        }
        openScreen(source, new LevelPreviewScreen(null, min, max));
        Feedback.send(source, Component.translatable(K + "preview.opened",
                String.valueOf(min), String.valueOf(max)).withColor(Feedback.VALUE));
        return 1;
    }

    // ======================================================================
    //  /skyprism hud
    // ======================================================================

    private static int hud(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        openScreen(source, new HudPlacementScreen(null));
        Feedback.send(source, Component.translatable(K + "hud.opened").withColor(Feedback.VALUE));
        return 1;
    }

    /**
     * Opens a screen next tick.
     *
     * <p>Deferred deliberately. A command executes while the chat screen is still the active
     * screen and is about to be closed by the very keypress that ran it, so setting a screen
     * inside the executor gets it immediately replaced by null. One tick later the chat
     * screen is gone and the new one sticks.</p>
     */
    private static void openScreen(FabricClientCommandSource source, net.minecraft.client.gui.screens.Screen screen) {
        ClientScheduler.schedule(1, "skyprism:screen", () -> source.getClient().setScreenAndShow(screen));
    }

    // ======================================================================
    //  /skyprism simulate
    // ======================================================================

    private static int simulateHelp(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        Feedback.send(source, Component.translatable(K + "simulate.usage").withColor(Feedback.VALUE));
        Feedback.detail(source, Component.translatable(K + "simulate.creatures",
                String.join(", ", CREATURE_TOKENS)).withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "simulate.sources",
                        String.valueOf(SOURCE_TOKENS.size()),
                        Feedback.suggestion("/skyprism sources", "/skyprism sources", Feedback.ACCENT))
                .withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "simulate.drops_hint")
                .withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "simulate.example",
                        Feedback.suggestion("/skyprism simulate glacite_corpse Vanguard Corpse",
                                "/skyprism simulate glacite_corpse Vanguard Corpse", Feedback.ACCENT))
                .withColor(Feedback.LABEL));
        return 1;
    }

    /**
     * Demonstrates any chance-based source in the game, offline.
     *
     * <h2>What the two arguments mean</h2>
     *
     * <p>{@code <source>} is a {@link LootSource} id -- {@code slayer_boss}, {@code glacite_corpse}
     * -- or a Mythological Ritual creature token, which is how the shipped {@code simulate inq}
     * keeps working and keeps meaning what it did. A creature token resolves to
     * {@link LootSource#DIANA_MYTHOLOGICAL} with that creature as its subject, so it also keeps
     * going through the Diana controller rather than the general machine.</p>
     *
     * <p>The greedy tail is {@code subject}, or {@code subject | drops}, or {@code | drops}. The
     * subject is what the caption says -- "Voidgloom Seraph IV", "Obsidian Chest" -- and defaults
     * to the source's own display name; the drops are the same comma-separated free text the
     * command has always taken, and default to a plausible set rolled from the source's real
     * jackpot list.</p>
     *
     * <p>A pipe is needed because both halves contain spaces and Brigadier's greedy string has to
     * be last. That is a small break from {@code simulate inq Chimera I, 40000 coins}, so a tail
     * with no pipe that looks like a drop list gets told about the pipe rather than silently
     * becoming a very long caption.</p>
     */
    private static int simulate(CommandContext<FabricClientCommandSource> ctx, String sourceToken,
                                String tail) {
        FabricClientCommandSource source = ctx.getSource();

        String subjectText = null;
        String dropText = null;
        if (tail != null && !tail.isBlank()) {
            int bar = tail.indexOf(SUBJECT_DROP_SEPARATOR);
            if (bar >= 0) {
                subjectText = tail.substring(0, bar).trim();
                dropText = tail.substring(bar + 1).trim();
            } else {
                subjectText = tail.trim();
            }
        }

        // Brigadier's word() argument cannot carry a space, so "minos_inquisitor" is the only
        // way to type a full display name. Underscores are folded back to spaces before the
        // core is asked, and the raw token is tried too so an alias containing an underscore
        // would still work if one is ever added.
        MythologicalCreature creature = MythologicalCreature
                .byNameOrAlias(sourceToken.replace('_', ' '))
                .or(() -> MythologicalCreature.byNameOrAlias(sourceToken))
                .orElse(null);
        LootSource lootSource = creature != null
                ? LootSource.DIANA_MYTHOLOGICAL
                : LootSource.byId(sourceToken).orElse(null);

        if (creature == null && lootSource == null) {
            Feedback.error(source, Component.translatable(K + "simulate.unknown_source",
                    sourceToken, String.join(", ", CREATURE_TOKENS)));
            Feedback.detail(source, Component.translatable(K + "simulate.unknown_source.hint",
                            Feedback.suggestion("/skyprism sources", "/skyprism sources", Feedback.ACCENT))
                    .withColor(Feedback.LABEL));
            return 0;
        }

        // A tail with no pipe is a subject. That is right for "simulate slayer_boss Voidgloom
        // Seraph IV" and wrong for anyone still typing the old drop list, so say so once rather
        // than captioning the machine with their loot table.
        if (dropText == null && subjectText != null && looksLikeDrops(subjectText)) {
            Feedback.detail(source, Component.translatable(K + "simulate.subject_or_drops",
                    subjectText).withColor(Feedback.WARN));
        }

        List<LootDrop> drops = dropText == null || dropText.isBlank()
                ? (creature != null ? SimulatedLoot.rollFor(creature) : SimulatedLoot.rollFor(lootSource))
                : parseDrops(dropText);

        if (drops.isEmpty()) {
            Feedback.error(source, Component.translatable(K + "simulate.no_drops",
                    String.valueOf(dropText)));
            return 0;
        }

        // Diana keeps its own route, so the command exercises the path the player actually runs.
        if (creature != null) {
            return simulateDiana(source, creature, drops);
        }

        String subject = subjectText == null || subjectText.isEmpty()
                ? LootSourceRegistry.displayName(lootSource)
                : subjectText;
        LootEvent event = new LootEvent(lootSource, subject, System.currentTimeMillis());
        if (!LootMachine.get().simulate(event, drops)) {
            Feedback.error(source, Component.translatable(K + "simulate.no_machine"));
            return 0;
        }
        reportEvent(source, event, drops);
        return 1;
    }

    /** The Mythological Ritual half, unchanged from what shipped. */
    private static int simulateDiana(FabricClientCommandSource source,
                                     MythologicalCreature creature, List<LootDrop> drops) {
        SkyPrismServices.Diana diana = SkyPrismServices.diana();
        if (diana != null) {
            diana.simulate(creature, drops);
            report(source, creature, drops, Component.translatable(K + "simulate.how.direct"));
            return 1;
        }

        // Fallback: no controller registered, so stage the kill as the chat lines Hypixel
        // would have sent and let whatever chat handlers exist react. Less direct, but it is
        // the difference between a command that works today and one that waits on a sibling
        // module - and it exercises the parsers as well, which the direct call does not.
        long tick = 0;
        ClientScheduler.schedule(1, "skyprism:simulate",
                () -> ChatPipeline.push(Component.literal(SimulatedLoot.spawnLine(creature))));
        for (LootDrop drop : drops) {
            tick += REPLAY_TICK_SPACING;
            final long delay = tick;
            ClientScheduler.schedule(delay, "skyprism:simulate",
                    () -> ChatPipeline.push(Component.literal(SimulatedLoot.dropLine(drop))));
        }
        report(source, creature, drops, Component.translatable(K + "simulate.how.staged"));
        return 1;
    }

    /**
     * Whether a tail that arrived without a pipe was probably meant as drops.
     *
     * <p>Only ever produces a hint, never a refusal: guessing wrong and refusing would break a
     * legitimate subject like "Enchanted Book, Ice Cold I".</p>
     */
    private static boolean looksLikeDrops(String text) {
        return text.indexOf(',') >= 0 || text.toLowerCase(Locale.ROOT).endsWith(" coins");
    }

    /** Prints what a non-Diana simulation just put on the machine. */
    private static void reportEvent(FabricClientCommandSource source, LootEvent event,
                                    List<LootDrop> drops) {
        SourceCategory category = SourceCategory.of(event.source());
        Feedback.send(source, Component.translatable(K + "simulate.heading.source",
                        Component.literal(event.subject()).withColor(Feedback.VALUE),
                        Component.literal(category.displayName()).withColor(Feedback.ACCENT))
                .withColor(Feedback.LABEL));
        for (LootDrop drop : drops) {
            Component name = drop.count() > 1
                    ? Component.translatable(K + "simulate.drop.stack",
                            String.valueOf(drop.count()), drop.itemName())
                    : Component.literal(drop.itemName());
            Feedback.detail(source, Component.translatable(
                            K + (drop.rare() ? "simulate.drop.jackpot" : "simulate.drop"), name)
                    .withColor(drop.rare() ? Feedback.WARN : Feedback.VALUE));
        }
        Feedback.detail(source, Component.translatable(K + "simulate.how.event",
                        Component.literal(event.source().id()).withColor(Feedback.VALUE),
                        policyName(LootMachine.get().policyFor(event.source())))
                .withColor(Feedback.LABEL));
    }

    private static void report(FabricClientCommandSource source, MythologicalCreature creature,
                               List<LootDrop> drops, Component how) {
        Feedback.send(source, Component.translatable(K + "simulate.heading",
                        Feedback.creature(creature).withColor(creature.rare() ? Feedback.BAD : Feedback.VALUE))
                .withColor(Feedback.LABEL));
        for (LootDrop drop : drops) {
            boolean jackpot = SkyPrismServices.config().get().diana.isJackpot(drop.itemName());
            // The item name is server text, not ours, so it stays a literal; only the row's
            // shape and its "3x <item>" stacking are translated.
            Component name = drop.count() > 1
                    ? Component.translatable(K + "simulate.drop.stack",
                            String.valueOf(drop.count()), drop.itemName())
                    : Component.literal(drop.itemName());
            Feedback.detail(source, Component.translatable(
                            K + (jackpot ? "simulate.drop.jackpot" : "simulate.drop"), name)
                    .withColor(jackpot ? Feedback.WARN : Feedback.VALUE));
        }
        Feedback.detail(source, how.copy().withColor(Feedback.LABEL));
    }

    // ======================================================================
    //  /skyprism sources
    // ======================================================================

    /**
     * The diagnostic for "this source never fires".
     *
     * <p>That complaint has four completely different causes and they are indistinguishable from
     * the outside, because all four look like nothing happening. This command separates them by
     * printing, per source, all four answers at once:</p>
     *
     * <ol>
     *   <li><b>Policy.</b> {@code never} means the detector was not even registered -- the source
     *       is off, on purpose, and usually because its shipped default said its cadence would
     *       have made the machine unusable.</li>
     *   <li><b>Gate.</b> Printed verbatim from the registry, so a source that is shut because you
     *       are on the wrong island says which island it wants. This is also where the honest gap
     *       shows: sources gated on a fine graph area ("in The Mist", "in Dragon's Nest") are shut
     *       because SkyPrism does not read that area yet, and the line says so by naming a
     *       condition that plainly is not being tested.</li>
     *   <li><b>Deferral.</b> A source on {@code on_rare_banner} or {@code on_jackpot_item_only} is
     *       working exactly as asked when it stays quiet through a hundred common drops.</li>
     *   <li><b>Trigger.</b> The registry's note carries what was and was not verified about the
     *       source's chat lines, including the several where no kill line exists at all.</li>
     * </ol>
     *
     * <p>With no filter the output is a summary and one row per open gate, which is short. With a
     * filter -- a source id, a category name, or {@code open}, {@code armed} or {@code off} -- it
     * prints the matching rows in full. Sixty-four ungrouped rows would be a wall nobody reads, so
     * the unfiltered form deliberately does not print one.</p>
     */
    private static int sources(CommandContext<FabricClientCommandSource> ctx, String filter) {
        FabricClientCommandSource source = ctx.getSource();
        LootMachine machine = LootMachine.get();

        Feedback.heading(source, Component.translatable(K + "sources.heading",
                String.valueOf(machine.armedSourceCount()),
                String.valueOf(LootSource.values().length - 1),
                String.valueOf(machine.openGateCount())));

        Feedback.detail(source, Component.translatable(K + "sources.context",
                        contextSummary(machine))
                .withColor(Feedback.LABEL));

        // Whether the bus is scanning for literals or looking at every line. This is the one
        // performance fact a player can act on.
        //
        // The number that matters here is the count of MARKERLESS detectors, not whether the
        // filter is off entirely. A markerless detector no longer disables filtering for its
        // neighbours -- it used to, and that one behaviour cost roughly fourteen times the
        // per-line price across the whole of SkyBlock -- but each one still pays its own full
        // cost on every line of chat, so the count is worth surfacing and worth colouring.
        int unmarked = machine.unmarkedDetectorCount();
        Feedback.detail(source, (machine.unfiltered()
                ? Component.translatable(K + "sources.filter.off")
                : Component.translatable(K + "sources.filter.on",
                        String.valueOf(machine.activeMarkers().size()),
                        String.valueOf(unmarked)))
                .withColor(machine.unfiltered() || unmarked > 0 ? Feedback.WARN : Feedback.LABEL));

        Feedback.detail(source, Component.translatable(K + "sources.counters",
                        String.valueOf(machine.admittedCount()),
                        String.valueOf(machine.deferredCount()),
                        String.valueOf(machine.suppressedCount()),
                        String.valueOf(machine.minIntervalMillis()))
                .withColor(Feedback.LABEL));

        // Two detector implementations for one source is not a failure -- one was chosen and the
        // source works -- but the loser is dead code, and nothing else in the mod would ever say so.
        List<LootSource> contested = machine.contestedSources();
        if (!contested.isEmpty()) {
            List<MutableComponent> names = new ArrayList<>(contested.size());
            for (LootSource candidate : contested) {
                names.add(Component.literal(candidate.id()).withColor(Feedback.WARN));
            }
            Feedback.detail(source, Component.translatable(K + "sources.contested", join(names))
                    .withColor(Feedback.WARN));
        }

        List<LootSource> rows = matchingSources(machine, filter);
        if (rows.isEmpty()) {
            Feedback.detail(source, Component.translatable(K + "sources.none",
                    String.valueOf(filter)).withColor(Feedback.WARN));
            return 0;
        }

        SourceCategory heading = null;
        for (LootSource candidate : rows) {
            SourceCategory category = SourceCategory.of(candidate);
            if (category != heading) {
                heading = category;
                Feedback.detail(source, Component.translatable(K + "sources.category",
                        category.displayName()).withColor(Feedback.ACCENT));
            }
            sourceRow(source, machine, candidate);
        }

        if (filter == null) {
            Feedback.detail(source, Component.translatable(K + "sources.more",
                            Feedback.suggestion("/skyprism sources armed",
                                    "/skyprism sources armed", Feedback.ACCENT),
                            Feedback.suggestion("/skyprism sources combat",
                                    "/skyprism sources combat", Feedback.ACCENT))
                    .withColor(Feedback.LABEL));
        }
        return 1;
    }

    /** One source's four answers: policy, gate, whether the gate is open, and the note. */
    private static void sourceRow(FabricClientCommandSource source, LootMachine machine,
                                  LootSource candidate) {
        LootSourceInfo info = LootSourceRegistry.info(candidate);
        RollPolicy policy = machine.policyFor(candidate);
        boolean open = policy.armed() && machine.gateOpen(candidate);

        Feedback.detail(source, Component.translatable(K + "sources.row",
                        Component.literal(candidate.id())
                                .withColor(open ? Feedback.GOOD : Feedback.OFF),
                        Component.literal(info.displayName()).withColor(Feedback.VALUE),
                        policyName(policy),
                        machine.overridden(candidate)
                                ? Component.translatable(K + "sources.overridden")
                                        .withColor(Feedback.WARN)
                                : Component.empty())
                .withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "sources.row.gate",
                        Component.literal(info.gate().describe())
                                .withColor(open ? Feedback.GOOD : Feedback.OFF))
                .withColor(Feedback.LABEL));
    }

    /**
     * The rows a filter selects, grouped so each category heading is printed once.
     *
     * <p>With no filter: the open gates only. The unfiltered form of this command is meant to fit
     * on screen and answer "what could fire right now", and the full table is one keystroke away.
     *
     * <p>Sorted by category rather than left in enum order. The enum is grouped by content area
     * and the categories are not quite the same grouping -- the Experimentation Table sits among
     * the mining constants, for one -- so printing in enum order would repeat a heading, which
     * reads as a rendering bug rather than as a list.
     */
    private static List<LootSource> matchingSources(LootMachine machine, String filter) {
        List<LootSource> out = new ArrayList<>(16);
        String key = filter == null ? null : filter.trim().toLowerCase(Locale.ROOT);

        for (LootSource candidate : LootSource.values()) {
            if (candidate == LootSource.DIANA_MYTHOLOGICAL) {
                continue;
            }
            RollPolicy policy = machine.policyFor(candidate);
            boolean match;
            if (key == null) {
                match = policy.armed() && machine.gateOpen(candidate);
            } else {
                match = switch (key) {
                    case "all" -> true;
                    case "armed", "on" -> policy.armed();
                    case "off", "never" -> !policy.armed();
                    case "open" -> policy.armed() && machine.gateOpen(candidate);
                    default -> candidate.id().contains(key)
                            || SourceCategory.of(candidate).displayName()
                                    .toLowerCase(Locale.ROOT).equals(key)
                            || LootSourceRegistry.displayName(candidate)
                                    .toLowerCase(Locale.ROOT).contains(key);
                };
            }
            if (match) {
                out.add(candidate);
            }
        }

        out.sort((a, b) -> {
            int byCategory = Integer.compare(SourceCategory.of(a).ordinal(),
                    SourceCategory.of(b).ordinal());
            return byCategory != 0 ? byCategory : Integer.compare(a.ordinal(), b.ordinal());
        });
        return List.copyOf(out);
    }

    /** The world facts the bus is gating against, as one readable clause. */
    private static Component contextSummary(LootMachine machine) {
        GameContext ctx = machine.context();
        if (!ctx.onHypixel()) {
            return Component.translatable(K + "sources.context.offserver").withColor(Feedback.OFF);
        }
        if (!ctx.inSkyBlock()) {
            return Component.translatable(K + "sources.context.lobby").withColor(Feedback.OFF);
        }
        String island = ctx.island().isEmpty() ? "?" : ctx.island();
        return Component.literal(island).withColor(Feedback.VALUE);
    }

    // ======================================================================
    //  /skyprism loot
    // ======================================================================

    private static int lootHelp(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        Feedback.send(source, Component.translatable(K + "loot.usage").withColor(Feedback.VALUE));
        Feedback.detail(source, Component.translatable(K + "loot.policies",
                String.join(", ", POLICY_TOKENS)).withColor(Feedback.LABEL));
        return lootInterval(ctx, -1);
    }

    /**
     * Reads or sets the floor between two rolls started by the loot bus.
     *
     * <p>Session-scoped, and it says so: SkyPrism's settings file has no loot section yet, and a
     * command that claimed to have saved something it had not would be worse than one that is
     * honest about lasting until the next restart. {@code LootMachine.setMinIntervalSupplier} is
     * the hook a settings section takes this over with, at which point this command reports the
     * saved value instead.</p>
     *
     * @param millis the new floor, or a negative number to report the current one
     */
    private static int lootInterval(CommandContext<FabricClientCommandSource> ctx, int millis) {
        FabricClientCommandSource source = ctx.getSource();
        LootMachine machine = LootMachine.get();
        if (millis >= 0) {
            machine.setMinIntervalMillis(millis);
        }
        Feedback.send(source, Component.translatable(K + "loot.interval",
                        value(String.valueOf(machine.minIntervalMillis())),
                        value(String.valueOf(LootMachine.DEFAULT_MIN_INTERVAL_MILLIS)))
                .withColor(Feedback.LABEL));
        Feedback.detail(source, (machine.intervalSupplied()
                ? Component.translatable(K + "loot.interval.saved")
                : Component.translatable(K + "loot.interval.session"))
                .withColor(Feedback.LABEL));
        return 1;
    }

    /** Switches one source's roll policy for the rest of the session. */
    private static int lootPolicy(CommandContext<FabricClientCommandSource> ctx, String sourceToken,
                                  String policyToken) {
        FabricClientCommandSource source = ctx.getSource();

        LootSource target = LootSource.byId(sourceToken).orElse(null);
        if (target == null || target == LootSource.DIANA_MYTHOLOGICAL) {
            Feedback.error(source, Component.translatable(K + "loot.unknown_source", sourceToken));
            Feedback.detail(source, Component.translatable(K + "simulate.unknown_source.hint",
                            Feedback.suggestion("/skyprism sources", "/skyprism sources", Feedback.ACCENT))
                    .withColor(Feedback.LABEL));
            return 0;
        }

        String key = policyToken.trim().toUpperCase(Locale.ROOT);
        RollPolicy wanted = null;
        if (!"DEFAULT".equals(key)) {
            for (RollPolicy candidate : RollPolicy.values()) {
                if (candidate.name().equals(key)) {
                    wanted = candidate;
                    break;
                }
            }
            if (wanted == null) {
                Feedback.error(source, Component.translatable(K + "loot.unknown_policy",
                        policyToken, String.join(", ", POLICY_TOKENS)));
                return 0;
            }
        }

        LootMachine machine = LootMachine.get();
        RollPolicy now = machine.setPolicy(target, wanted);
        Feedback.send(source, Component.translatable(K + "loot.policy.set",
                        Component.literal(target.id()).withColor(Feedback.VALUE),
                        policyName(now))
                .withColor(Feedback.LABEL));

        // A policy the source cannot honour is the failure the whole feature was warned about:
        // ON_RARE_BANNER on a source that emits no banner is a detector that silently never fires
        // and is indistinguishable from a working one.
        LootSourceInfo info = LootSourceRegistry.info(target);
        if (now == RollPolicy.ON_RARE_BANNER && !info.emitsRareBanner()) {
            Feedback.detail(source, Component.translatable(K + "loot.policy.no_banner")
                    .withColor(Feedback.WARN));
        }
        if (now == RollPolicy.ON_JACKPOT_ITEM_ONLY && info.jackpotItems().isEmpty()) {
            Feedback.detail(source, Component.translatable(K + "loot.policy.no_jackpot")
                    .withColor(Feedback.WARN));
        }
        return 1;
    }

    /**
     * Reads free-text drops.
     *
     * <p>Comma-separated, with an optional leading count ("3x Ancient Claw") and a special
     * case for coins ("40000 coins"), because those are the two shapes people type without
     * being told to. Anything else becomes an item of that name with a count of one - a
     * simulator that rejected input would be a worse tool than one that took the player at
     * their word.</p>
     */
    private static List<LootDrop> parseDrops(String text) {
        List<LootDrop> drops = new ArrayList<>(4);
        for (String piece : text.split(",")) {
            String token = piece.trim();
            if (token.isEmpty()) {
                continue;
            }

            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.endsWith(" coins") || lower.endsWith(" coin")) {
                String amount = token.substring(0, token.lastIndexOf(' ')).trim().replace(",", "");
                try {
                    int value = Integer.parseInt(amount);
                    drops.add(new LootDrop("Coins", "6", Math.max(1, value), false));
                    continue;
                } catch (NumberFormatException notANumber) {
                    // Falls through to the plain-item path; "many coins" is a legal item name.
                }
            }

            int count = 1;
            int x = token.indexOf('x');
            if (x > 0 && x + 1 < token.length() && token.charAt(x + 1) == ' ') {
                try {
                    count = Math.max(1, Integer.parseInt(token.substring(0, x).trim()));
                    token = token.substring(x + 2).trim();
                } catch (NumberFormatException notACount) {
                    count = 1;
                }
            }
            if (token.isEmpty()) {
                continue;
            }
            boolean jackpot = SkyPrismServices.config().get().diana.isJackpot(token);
            drops.add(new LootDrop(token, jackpot ? "5" : "a", count, jackpot));
        }
        return List.copyOf(drops);
    }

    // ======================================================================
    //  /skyprism replay
    // ======================================================================

    private static int replayHelp(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        Feedback.send(source, Component.translatable(K + "replay.usage").withColor(Feedback.VALUE));
        Feedback.detail(source, Component.translatable(K + "replay.format").withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "replay.paths",
                String.valueOf(FabricLoader.getInstance().getGameDir())).withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "replay.stop_hint").withColor(Feedback.LABEL));
        return 1;
    }

    private static int replayStop(CommandContext<FabricClientCommandSource> ctx) {
        int dropped = ClientScheduler.cancel(REPLAY_GROUP);
        Feedback.send(ctx.getSource(), (dropped == 0
                ? Component.translatable(K + "replay.none_running")
                : Component.translatable(K + "replay.stopped", String.valueOf(dropped)))
                .withColor(dropped == 0 ? Feedback.OFF : Feedback.VALUE));
        return 1;
    }

    private static int replay(CommandContext<FabricClientCommandSource> ctx, String rawPath) {
        FabricClientCommandSource source = ctx.getSource();

        Path file;
        try {
            Path candidate = Path.of(rawPath.trim().replace("\"", ""));
            file = candidate.isAbsolute()
                    ? candidate
                    : FabricLoader.getInstance().getGameDir().resolve(candidate);
        } catch (InvalidPathException badPath) {
            Feedback.error(source, Component.translatable(K + "replay.bad_path", rawPath));
            return 0;
        }

        if (!Files.isRegularFile(file)) {
            Feedback.error(source, Component.translatable(K + "replay.missing_file",
                    String.valueOf(file), String.valueOf(FabricLoader.getInstance().getGameDir())));
            return 0;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            Feedback.error(source, Component.translatable(K + "replay.unreadable",
                    String.valueOf(file.getFileName()), String.valueOf(unreadable.getMessage())));
            return 0;
        }

        int cancelled = ClientScheduler.cancel(REPLAY_GROUP);
        if (cancelled > 0) {
            Feedback.send(source, Component.translatable(K + "replay.superseded",
                    String.valueOf(cancelled)).withColor(Feedback.WARN));
        }

        long delayTicks = 1;
        int queued = 0;
        int skipped = 0;

        for (String line : lines) {
            if (queued >= REPLAY_MAX_LINES) {
                Feedback.send(source, Component.translatable(K + "replay.truncated",
                        String.valueOf(REPLAY_MAX_LINES)).withColor(Feedback.WARN));
                break;
            }

            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                long pause = readWaitDirective(trimmed);
                if (pause > 0) {
                    // Millis to ticks, rounded up so "#wait 50" is still a visible beat.
                    delayTicks += Math.max(1, (pause + 49) / 50);
                } else {
                    skipped++;
                }
                continue;
            }

            String raw = ChatPipeline.unescape(line);
            final long at = delayTicks;
            ClientScheduler.schedule(at, REPLAY_GROUP, () -> {
                ChatPipeline.Outcome outcome = ChatPipeline.push(Component.literal(raw));
                if (outcome.delivered()) {
                    source.sendFeedback(outcome.result());
                }
            });
            delayTicks += REPLAY_TICK_SPACING;
            queued++;
        }

        MutableComponent lineCount = value(String.valueOf(queued));
        String over = TimeFormat.shortDuration(delayTicks * 50L);
        Feedback.send(source, (skipped > 0
                ? Component.translatable(K + "replay.queued.skipped", lineCount,
                        String.valueOf(file.getFileName()), over, String.valueOf(skipped))
                : Component.translatable(K + "replay.queued", lineCount,
                        String.valueOf(file.getFileName()), over))
                .withColor(Feedback.LABEL));
        return 1;
    }

    /**
     * @return the millisecond pause a {@code #wait <millis>} directive asks for, or 0 when
     *         the line is an ordinary comment
     */
    private static long readWaitDirective(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("#wait")) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(trimmed.substring(5).trim()));
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    // ======================================================================
    //  /skyprism stats
    // ======================================================================

    private static int stats(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        SkyPrismServices.Diana diana = SkyPrismServices.diana();

        if (diana == null) {
            Feedback.error(source, Component.translatable(K + "stats.unavailable"));
            return 0;
        }

        // The lines themselves are composed by the Diana module, which owns their wording;
        // this command only frames them.
        List<String> lines = diana.stats();
        Feedback.heading(source, Component.translatable(K + "stats.heading"));
        if (lines == null || lines.isEmpty()) {
            Feedback.detail(source, Component.translatable(K + "stats.empty").withColor(Feedback.OFF));
            return 1;
        }
        for (String line : lines) {
            Feedback.detail(source, Component.literal(line).withColor(Feedback.VALUE));
        }
        return 1;
    }

    // ======================================================================
    //  /skyprism profile
    // ======================================================================

    private static int profile(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        Metrics.Snapshot m = Metrics.snapshot();

        Feedback.heading(source, Component.translatable(K + "profile.heading",
                TimeFormat.shortDuration(m.uptimeMillis())));
        if (!m.enabled()) {
            Feedback.detail(source, Component.translatable(K + "profile.disabled")
                    .withColor(Feedback.WARN));
        }

        Feedback.detail(source, Feedback.row(
                Component.translatable(K + "profile.chat"),
                Component.translatable(K + "profile.chat.detail",
                        String.valueOf(m.chatMessages()), Feedback.rate(m.chatPerSecond()),
                        String.valueOf(m.chatRewrites()), Feedback.rate(m.chatRewritesPerSec()),
                        String.valueOf(m.chatTags()), Feedback.micros(m.chatAvgMicros()),
                        String.format(Locale.ROOT, "%.3f", m.chatMillisPerSec()))));

        int hitColor = m.tabHits() + m.tabMisses() == 0 ? Feedback.OFF
                : m.tabHitRate() >= 0.98 ? Feedback.GOOD
                : m.tabHitRate() >= 0.9 ? Feedback.WARN : Feedback.BAD;
        Feedback.detail(source, Feedback.row(
                Component.translatable(K + "profile.tab"),
                Component.translatable(K + "profile.tab.detail",
                        Feedback.percent(m.tabHitRate()),
                        String.valueOf(m.tabHits()), String.valueOf(m.tabMisses()),
                        Feedback.rate(m.tabProbesPerSec()), String.valueOf(m.tabRewrites()),
                        Feedback.micros(m.tabAvgMicros())),
                hitColor));

        // Guarded the same way the TAB row is. Without it a nametag surface that has never run --
        // it is off by default -- printed "0 rewrites at 0.0 us" in the ordinary colour, which
        // reads as "nametags are free" rather than as "nothing was measured".
        Feedback.detail(source, Feedback.row(
                Component.translatable(K + "profile.tags"),
                Component.translatable(K + "profile.tags.detail",
                        String.valueOf(m.nameTagRewrites()), Feedback.micros(m.nameTagAvgMicros())),
                m.nameTagRewrites() == 0L ? Feedback.OFF : Feedback.GOOD));

        // The surfaces module keeps its own recompute counters, and they are the
        // authoritative ones: they are incremented inside the memo itself rather than by a
        // caller who might forget. Shown alongside so a disagreement between the two is
        // visible rather than averaged away.
        long[] recomputes = SkyPrismServices.level().recomputeCounts();
        if (recomputes.length == 2 && recomputes[0] >= 0L) {
            Feedback.detail(source, Feedback.row(
                    Component.translatable(K + "profile.memo"),
                    Component.translatable(K + "profile.memo.detail",
                            String.valueOf(recomputes[0]), String.valueOf(recomputes[1]))));
        }

        long hudTotal = m.hudSkips() + m.hudFrames();
        double idleShare = hudTotal == 0 ? 1.0 : m.hudSkips() / (double) hudTotal;
        Feedback.detail(source, Feedback.row(
                Component.translatable(K + "profile.hud"),
                Component.translatable(K + "profile.hud.detail",
                        String.valueOf(m.hudFrames()), String.valueOf(m.hudSkips()),
                        Feedback.percent(idleShare),
                        Feedback.micros(m.hudAvgMicros()), Feedback.micros(m.hudPeakMicros()))));

        // The headline number: what the mod costs out of every 16.7 ms frame at 60 FPS. It sums
        // every measured path -- the two per-frame render surfaces included -- because a headline
        // computed from the HUD alone could not have contradicted the no-FPS-cost claim whatever
        // the TAB list was actually doing.
        Feedback.detail(source, Feedback.row(
                Component.translatable(K + "profile.cost"),
                Component.translatable(K + "profile.cost.detail",
                        String.format(Locale.ROOT, "%.3f", m.costMillisPerSec()),
                        Feedback.percent(m.costMillisPerSec() / 1000.0),
                        String.format(Locale.ROOT, "%.3f", m.hudMillisPerSec()),
                        String.format(Locale.ROOT, "%.3f", m.chatMillisPerSec()),
                        String.format(Locale.ROOT, "%.3f", m.surfaceMillisPerSec())),
                m.costMillisPerSec() < 5.0 ? Feedback.GOOD : Feedback.WARN));

        Feedback.detail(source, Component.translatable(K + "profile.hint").withColor(Feedback.LABEL));
        return 1;
    }

    private static int profileReset(CommandContext<FabricClientCommandSource> ctx) {
        Metrics.reset();
        Feedback.send(ctx.getSource(), Component.translatable(K + "profile.reset")
                .withColor(Feedback.VALUE));
        return 1;
    }

    private static int profileEnable(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        Metrics.setEnabled(on);
        Feedback.send(ctx.getSource(), Component.translatable(K + (on ? "profile.on" : "profile.off"))
                .withColor(on ? Feedback.GOOD : Feedback.OFF));
        return 1;
    }

    // ======================================================================
    //  /skyprism reload
    // ======================================================================

    private static int reload(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        Path path = SkyPrismServices.config().path();
        try {
            SkyPrismServices.config().reload();
        } catch (RuntimeException failed) {
            Feedback.error(source, Component.translatable(K + "reload.failed",
                    String.valueOf(path.getFileName()), String.valueOf(failed)));
            return 0;
        }
        SkyPrismServices.level().invalidate();

        SkyPrismConfig config = SkyPrismServices.config().get();
        Feedback.send(source, Component.translatable(K + "reload.done",
                Feedback.copyable(path.toString(), Feedback.VALUE)).withColor(Feedback.LABEL));
        Feedback.detail(source, Component.translatable(K + "reload.summary",
                        modeName(config.levels.mode).withColor(Feedback.VALUE),
                        Feedback.onOff(config.levels.enabled),
                        value(String.valueOf(config.diana.reelCount)),
                        Feedback.onOff(config.diana.enabled))
                .withColor(Feedback.LABEL));
        return 1;
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    /**
     * Every token {@code MythologicalCreature.byNameOrAlias} accepts, for suggestions.
     *
     * <p>Built from the enum rather than typed out, so a creature added to the core turns up
     * in tab-completion without anybody remembering to update a list here.</p>
     */
    private static List<String> creatureTokens() {
        List<String> tokens = new ArrayList<>();
        for (MythologicalCreature creature : MythologicalCreature.values()) {
            tokens.add(creature.displayName().toLowerCase(Locale.ROOT).replace(' ', '_'));
            tokens.addAll(creature.aliases());
        }
        List<String> unique = new ArrayList<>(tokens.stream().distinct().toList());
        unique.sort(String::compareTo);
        return List.copyOf(unique);
    }

    /** Every source id except Diana's, which is driven by creature token instead. */
    private static List<String> sourceTokens() {
        List<String> tokens = new ArrayList<>(LootSource.values().length);
        for (LootSource source : LootSource.values()) {
            if (source != LootSource.DIANA_MYTHOLOGICAL) {
                tokens.add(source.id());
            }
        }
        return List.copyOf(tokens);
    }

    private static List<String> simulateTokens() {
        List<String> tokens = new ArrayList<>(CREATURE_TOKENS);
        tokens.addAll(SOURCE_TOKENS);
        return List.copyOf(tokens);
    }

    private static List<String> policyTokens() {
        List<String> tokens = new ArrayList<>(RollPolicy.values().length + 1);
        for (RollPolicy policy : RollPolicy.values()) {
            tokens.add(policy.name().toLowerCase(Locale.ROOT));
        }
        tokens.add("default");
        return List.copyOf(tokens);
    }

    /**
     * A policy as a coloured word.
     *
     * <p>The name itself is not translated. {@code ON_RARE_BANNER} is an identifier the player
     * types back into {@code /skyprism loot policy}, and a translated label they cannot type would
     * make the report and the command disagree.</p>
     */
    private static MutableComponent policyName(RollPolicy policy) {
        int color = switch (policy) {
            case ALWAYS -> Feedback.GOOD;
            case ON_RARE_BANNER, ON_JACKPOT_ITEM_ONLY -> Feedback.ACCENT;
            case NEVER -> Feedback.OFF;
        };
        return Component.literal(policy.name().toLowerCase(Locale.ROOT)).withColor(color);
    }

    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String modVersion() {
        return versionOf("skyprism");
    }

    private static String minecraftVersion() {
        return versionOf("minecraft");
    }

    private static String versionOf(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
