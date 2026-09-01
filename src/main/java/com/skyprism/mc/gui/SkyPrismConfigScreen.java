package com.skyprism.mc.gui;

import com.skyprism.core.config.HudAnchor;
import com.skyprism.core.config.LootSourceCategory;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.level.BracketTable;
import com.skyprism.core.level.GradientRamp;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.PalettePresets;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.mc.config.ConfigManager;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.LongSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The YACL settings screen. <b>The only class in SkyPrism permitted to import
 * {@code dev.isxander}.</b>
 *
 * <p>It is package-private on purpose: the quarantine that lets the mod run without YACL
 * only holds if nothing outside {@link ConfigGui} can name this type. Everything else
 * reaches the screen through {@link ConfigGui#open(Screen)}, whose signature mentions only
 * {@link Screen}.</p>
 *
 * <p><b>YACL does not own the settings.</b> The core already ships atomic saves, corrupt
 * file recovery, versioned migrations and {@code sanitized()} clamping, none of which
 * YACL's own {@code ConfigClassHandler} does, and the core's config class is off-limits to
 * annotate anyway. So this screen is a pure GUI: it binds every control to a detached
 * {@link ConfigManager#draft()} and, on Save, hands the whole draft to
 * {@link ConfigManager#apply(SkyPrismConfig)} in one call. That single hand-off is what
 * guarantees a frame can never render a half-edited configuration, and it is also what
 * bumps {@link ConfigManager#generation()} so the TAB-list cache drops its stale colours.</p>
 *
 * <p><b>Why the parallel lists.</b> A YACL {@code ListOption<T>} has one controller for
 * every row, so a two-field record like {@code GradientRamp.Stop(level, rgb)} cannot be a
 * single list. The screen therefore carries a scratch {@link Draft} holding levels and
 * colours as two lists edited side by side, and zips them back into stops at Save. Rows
 * beyond the shorter list are dropped, which is the only sane reading of "the user added a
 * colour but no level for it".</p>
 *
 * <p><b>Every string here is a translation key.</b> The English copy lives in
 * {@code assets/skyprism/lang/en_us.json} and nowhere else, which is both what makes the
 * mod translatable and what makes the wording reviewable in one file rather than scattered
 * over seven hundred lines of builder calls. Keys are namespaced {@code skyprism.config.*},
 * with the handful of atoms shared with the command tree and the HUD screens under
 * {@code skyprism.common.*}. Values that come out of a core enum -- colouring modes, HUD
 * anchors, creature names -- are looked up with
 * {@link Component#translatableWithFallback(String, String)} so that a constant added to
 * the core keeps rendering readable English until somebody adds its key.</p>
 */
final class SkyPrismConfigScreen {

    /** Reference values for YACL's per-option "reset to default" arrow. */
    private static final SkyPrismConfig DEFAULTS = SkyPrismConfig.defaults();

    /** Every key in this screen sits under here; spelled once so a typo is a compile error. */
    private static final String K = "skyprism.config.";

    private SkyPrismConfigScreen() {
    }

    /**
     * Builds the screen.
     *
     * @param parent the screen to return to on close; may be null
     * @return a fully built YACL screen
     */
    static Screen create(Screen parent) {
        ConfigManager manager = ConfigManager.get();
        Draft draft = new Draft(manager.draft());

        // The loot tabs are built before the overview because the overview's "start again"
        // button has to be able to reach every control on every one of them. Display order is
        // decided below by the order the categories are added, not by the order they were built.
        var controls = new LootControls();
        List<ConfigCategory> lootTabs = new ArrayList<>();
        for (LootSourceCategory category : LootSourceCategory.values()) {
            if (category.configurable() && !LootSourceCategory.sources(category).isEmpty()) {
                lootTabs.add(lootCategory(draft, category, controls));
            }
        }

        var builder = YetAnotherConfigLib.createBuilder()
                .title(Component.translatable(K + "title"))
                .category(levelsCategory(draft))
                .category(dianaCategory(draft))
                .category(lootOverviewCategory(draft, controls));
        for (ConfigCategory tab : lootTabs) {
            builder.category(tab);
        }
        return builder
                .category(hudCategory(draft))
                .category(soundsCategory(draft))
                .save(() -> {
                    draft.commit();
                    manager.apply(draft.config);
                })
                .build()
                .generateScreen(parent);
    }

    // ================================================================ Levels

    private static ConfigCategory levelsCategory(Draft draft) {
        SkyPrismConfig.LevelSettings levels = draft.config.levels;

        ListOption<Integer> stopLevels = ListOption.<Integer>createBuilder()
                .name(Component.translatable(K + "levels.stop_levels"))
                .description(describe(K + "levels.stop_levels"))
                .binding(defaultStopLevels(), () -> draft.stopLevels, list -> replace(draft.stopLevels, list))
                .controller(IntegerFieldControllerBuilder::create)
                .initial(0)
                .minimumNumberOfEntries(2)
                .maximumNumberOfEntries(SkyPrismConfig.LevelSettings.MAX_TABLE_ENTRIES)
                .collapsed(true)
                .build();

        ListOption<Color> stopColours = ListOption.<Color>createBuilder()
                .name(Component.translatable(K + "levels.stop_colours"))
                .description(describe(K + "levels.stop_colours"))
                .binding(defaultStopColours(), () -> draft.stopColours, list -> replace(draft.stopColours, list))
                .controller(colour -> ColorControllerBuilder.create(colour).allowAlpha(false))
                .initial(Color.WHITE)
                .minimumNumberOfEntries(2)
                .maximumNumberOfEntries(SkyPrismConfig.LevelSettings.MAX_TABLE_ENTRIES)
                .collapsed(true)
                .build();

        ListOption<Integer> bracketLevels = ListOption.<Integer>createBuilder()
                .name(Component.translatable(K + "levels.bracket_levels"))
                .description(describe(K + "levels.bracket_levels"))
                .binding(defaultBracketLevels(), () -> draft.bracketLevels, list -> replace(draft.bracketLevels, list))
                .controller(IntegerFieldControllerBuilder::create)
                .initial(0)
                .minimumNumberOfEntries(1)
                .maximumNumberOfEntries(SkyPrismConfig.LevelSettings.MAX_TABLE_ENTRIES)
                .collapsed(true)
                .build();

        ListOption<Color> bracketColours = ListOption.<Color>createBuilder()
                .name(Component.translatable(K + "levels.bracket_colours"))
                .description(describe(K + "levels.bracket_colours"))
                .binding(defaultBracketColours(), () -> draft.bracketColours, list -> replace(draft.bracketColours, list))
                .controller(colour -> ColorControllerBuilder.create(colour).allowAlpha(false))
                .initial(Color.WHITE)
                .minimumNumberOfEntries(1)
                .maximumNumberOfEntries(SkyPrismConfig.LevelSettings.MAX_TABLE_ENTRIES)
                .collapsed(true)
                .build();

        List<String> presetNames = new ArrayList<>(PalettePresets.gradients().keySet());
        presetNames.add(SkyPrismConfig.LevelSettings.CUSTOM_PRESET);

        // Two guards, because "the preset changed" is narrower than "the option fired".
        // YACL raises INITIAL when the listener is registered and AVAILABILITY_CHANGE when
        // an option is merely greyed out; acting on either would overwrite the user's own
        // stops the instant the screen opened. The remembered value then covers any further
        // event that arrives without the selection having actually moved.
        String[] lastPreset = {levels.gradientPreset};

        Option<String> preset = Option.<String>createBuilder()
                .name(Component.translatable(K + "levels.preset"))
                .description(describe(K + "levels.preset"))
                .binding(DEFAULTS.levels.gradientPreset,
                        () -> draft.config.levels.gradientPreset,
                        value -> draft.config.levels.gradientPreset = value)
                .controller(option -> DropdownStringControllerBuilder.create(option)
                        .values(presetNames)
                        .allowAnyValue(false)
                        .allowEmptyValue(false))
                .addListener((option, event) -> {
                    if (event != OptionEventListener.Event.STATE_CHANGE) {
                        return;
                    }
                    String value = option.pendingValue();
                    if (Objects.equals(value, lastPreset[0])) {
                        return;
                    }
                    lastPreset[0] = value;
                    GradientRamp ramp = PalettePresets.gradients().get(value);
                    if (ramp == null) {
                        return;
                    }
                    List<Integer> newLevels = new ArrayList<>();
                    List<Color> newColours = new ArrayList<>();
                    for (GradientRamp.Stop stop : ramp.stops()) {
                        newLevels.add(stop.level());
                        newColours.add(new Color(stop.rgb()));
                    }
                    // Fresh lists, never the draft's own: the bindings above clear before
                    // they copy, so handing back the same list instance would empty it.
                    stopLevels.requestSet(newLevels);
                    stopColours.requestSet(newColours);
                })
                .build();

        OptionGroup general = OptionGroup.createBuilder()
                .name(Component.translatable(K + "levels.group.general"))
                .option(toggle(K + "levels.enabled",
                        DEFAULTS.levels.enabled,
                        () -> levels.enabled, value -> levels.enabled = value))
                .option(Option.<LevelColorMode>createBuilder()
                        .name(Component.translatable(K + "levels.mode"))
                        .description(describe(K + "levels.mode"))
                        .binding(DEFAULTS.levels.mode, () -> levels.mode, value -> levels.mode = value)
                        .controller(option -> EnumControllerBuilder.create(option)
                                .enumClass(LevelColorMode.class)
                                .formatValue(SkyPrismConfigScreen::modeName))
                        .build())
                .option(toggle(K + "levels.recolour_brackets",
                        DEFAULTS.levels.recolourBrackets,
                        () -> levels.recolourBrackets, value -> levels.recolourBrackets = value))
                .build();

        OptionGroup surfaces = OptionGroup.createBuilder()
                .name(Component.translatable(K + "levels.group.surfaces"))
                .description(describe(K + "levels.group.surfaces"))
                .option(toggle(K + "levels.apply_chat",
                        DEFAULTS.levels.applyToChat,
                        () -> levels.applyToChat, value -> levels.applyToChat = value))
                .option(toggle(K + "levels.apply_tab",
                        DEFAULTS.levels.applyToTabList,
                        () -> levels.applyToTabList, value -> levels.applyToTabList = value))
                .option(toggle(K + "levels.apply_nametags",
                        DEFAULTS.levels.applyToNameTags,
                        () -> levels.applyToNameTags, value -> levels.applyToNameTags = value))
                .option(toggle(K + "levels.only_skyblock",
                        DEFAULTS.levels.onlyOnSkyBlock,
                        () -> levels.onlyOnSkyBlock, value -> levels.onlyOnSkyBlock = value))
                .build();

        OptionGroup chroma = OptionGroup.createBuilder()
                .name(Component.translatable(K + "levels.group.chroma"))
                .description(describe(K + "levels.group.chroma"))
                .option(toggle(K + "levels.chroma_enabled",
                        DEFAULTS.levels.chromaEnabled,
                        () -> levels.chromaEnabled, value -> levels.chromaEnabled = value))
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable(K + "levels.chroma_min_level"))
                        .description(describe(K + "levels.chroma_min_level"))
                        .binding(DEFAULTS.levels.chromaMinLevel,
                                () -> levels.chromaMinLevel, value -> levels.chromaMinLevel = value)
                        .controller(IntegerFieldControllerBuilder::create)
                        .build())
                .option(Option.<Double>createBuilder()
                        .name(Component.translatable(K + "levels.chroma_speed"))
                        .description(describe(K + "levels.chroma_speed"))
                        .binding(DEFAULTS.levels.chromaCyclesPerSecond,
                                () -> levels.chromaCyclesPerSecond,
                                value -> levels.chromaCyclesPerSecond = value)
                        .controller(option -> DoubleSliderControllerBuilder.create(option)
                                .range(SkyPrismConfig.LevelSettings.MIN_CHROMA_CPS,
                                        SkyPrismConfig.LevelSettings.MAX_CHROMA_CPS)
                                .step(0.05)
                                .formatValue(value -> Component.translatable(K + "value.cycles",
                                        String.format(Locale.ROOT, "%.2f", value))))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable(K + "levels.chroma_hz"))
                        .description(describe(K + "levels.chroma_hz"))
                        .binding(DEFAULTS.levels.chromaUpdateHz,
                                () -> levels.chromaUpdateHz, value -> levels.chromaUpdateHz = value)
                        .controller(option -> IntegerSliderControllerBuilder.create(option)
                                .range(SkyPrismConfig.LevelSettings.MIN_CHROMA_HZ,
                                        SkyPrismConfig.LevelSettings.MAX_CHROMA_HZ)
                                .step(1)
                                .formatValue(value -> Component.translatable(K + "value.hertz",
                                        String.valueOf(value))))
                        .build())
                // Saturation and lightness became real config fields in v3 of the file but had
                // no controls, so the only way to reach them was to close the game and edit
                // JSON. Bounds come from the same MIN/MAX constants the sanitiser clamps
                // against, per the class rule that the screen and the sanitiser cannot
                // disagree about what is legal.
                .option(Option.<Double>createBuilder()
                        .name(Component.translatable(K + "levels.chroma_saturation"))
                        .description(describe(K + "levels.chroma_saturation"))
                        .binding(DEFAULTS.levels.chromaSaturation,
                                () -> levels.chromaSaturation,
                                value -> levels.chromaSaturation = value)
                        .controller(option -> DoubleSliderControllerBuilder.create(option)
                                .range(SkyPrismConfig.LevelSettings.MIN_CHROMA_SATURATION,
                                        SkyPrismConfig.LevelSettings.MAX_CHROMA_SATURATION)
                                .step(0.01)
                                .formatValue(value -> Component.translatable(K + "value.fraction",
                                        String.format(Locale.ROOT, "%.2f", value))))
                        .build())
                .option(Option.<Double>createBuilder()
                        .name(Component.translatable(K + "levels.chroma_lightness"))
                        .description(describe(K + "levels.chroma_lightness"))
                        .binding(DEFAULTS.levels.chromaLightness,
                                () -> levels.chromaLightness,
                                value -> levels.chromaLightness = value)
                        .controller(option -> DoubleSliderControllerBuilder.create(option)
                                .range(SkyPrismConfig.LevelSettings.MIN_CHROMA_LIGHTNESS,
                                        SkyPrismConfig.LevelSettings.MAX_CHROMA_LIGHTNESS)
                                .step(0.01)
                                .formatValue(value -> Component.translatable(K + "value.fraction",
                                        String.format(Locale.ROOT, "%.2f", value))))
                        .build())
                .build();

        OptionGroup detection = OptionGroup.createBuilder()
                .name(Component.translatable(K + "levels.group.detection"))
                .description(describe(K + "levels.group.detection"))
                .collapsed(true)
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable(K + "levels.min_level"))
                        .description(describe(K + "levels.min_level"))
                        .binding(DEFAULTS.levels.minLevel, () -> levels.minLevel, value -> levels.minLevel = value)
                        .controller(IntegerFieldControllerBuilder::create)
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable(K + "levels.max_level"))
                        .description(describe(K + "levels.max_level"))
                        .binding(DEFAULTS.levels.maxLevel, () -> levels.maxLevel, value -> levels.maxLevel = value)
                        .controller(IntegerFieldControllerBuilder::create)
                        .build())
                .build();

        return ConfigCategory.createBuilder()
                .name(Component.translatable(K + "category.levels"))
                .tooltip(Component.translatable(K + "category.levels.tooltip"))
                .group(general)
                .group(surfaces)
                .option(preset)
                .group(stopLevels)
                .group(stopColours)
                .group(bracketLevels)
                .group(bracketColours)
                .group(chroma)
                .group(detection)
                .build();
    }

    // ================================================================ Diana

    private static ConfigCategory dianaCategory(Draft draft) {
        SkyPrismConfig.DianaSettings diana = draft.config.diana;

        OptionGroup.Builder triggers = OptionGroup.createBuilder()
                .name(Component.translatable(K + "diana.group.triggers"))
                .description(describe(K + "diana.group.triggers"));
        for (MythologicalCreature creature : MythologicalCreature.values()) {
            triggers.option(toggle(creatureName(creature),
                    describe(K + (creature.rare() ? "diana.trigger.rare" : "diana.trigger.common")),
                    MythologicalCreature.defaultTriggers().contains(creature),
                    () -> draft.triggers.contains(creature),
                    value -> {
                        if (value) {
                            draft.triggers.add(creature);
                        } else {
                            draft.triggers.remove(creature);
                        }
                    }));
        }

        ListOption<String> areas = ListOption.<String>createBuilder()
                .name(Component.translatable(K + "diana.allowed_areas"))
                .description(describe(K + "diana.allowed_areas"))
                .binding(new ArrayList<>(DEFAULTS.diana.allowedAreas),
                        () -> draft.allowedAreas, list -> replace(draft.allowedAreas, list))
                .controller(StringControllerBuilder::create)
                .initial("")
                .maximumNumberOfEntries(SkyPrismConfig.DianaSettings.MAX_JACKPOT_ITEMS)
                .collapsed(true)
                .build();

        ListOption<String> jackpot = ListOption.<String>createBuilder()
                .name(Component.translatable(K + "diana.jackpot_items"))
                .description(describe(K + "diana.jackpot_items"))
                .binding(new ArrayList<>(DEFAULTS.diana.jackpotItems),
                        () -> draft.jackpotItems, list -> replace(draft.jackpotItems, list))
                .controller(StringControllerBuilder::create)
                .initial("")
                .maximumNumberOfEntries(SkyPrismConfig.DianaSettings.MAX_JACKPOT_ITEMS)
                .collapsed(true)
                .build();

        OptionGroup general = OptionGroup.createBuilder()
                .name(Component.translatable(K + "diana.group.general"))
                .option(toggle(K + "diana.enabled",
                        DEFAULTS.diana.enabled,
                        () -> diana.enabled, value -> diana.enabled = value))
                .option(toggle(K + "diana.only_my_burrows",
                        DEFAULTS.diana.onlyMyBurrows,
                        () -> diana.onlyMyBurrows, value -> diana.onlyMyBurrows = value))
                .option(toggle(K + "diana.suppress_drop_lines",
                        DEFAULTS.diana.suppressDropChatLines,
                        () -> diana.suppressDropChatLines, value -> diana.suppressDropChatLines = value))
                .build();

        OptionGroup timing = OptionGroup.createBuilder()
                .name(Component.translatable(K + "diana.group.timing"))
                .description(describe(K + "diana.group.timing"))
                .collapsed(true)
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable(K + "diana.reels"))
                        .description(describe(K + "diana.reels"))
                        .binding(DEFAULTS.diana.reelCount, () -> diana.reelCount, value -> diana.reelCount = value)
                        .controller(option -> IntegerSliderControllerBuilder.create(option)
                                .range(1, 5).step(1))
                        .build())
                .option(millis(K + "diana.loot_window",
                        DEFAULTS.diana.lootWindowMillis,
                        SkyPrismConfig.DianaSettings.MIN_LOOT_WINDOW_MILLIS,
                        SkyPrismConfig.DianaSettings.MAX_LOOT_WINDOW_MILLIS, 250L,
                        () -> diana.lootWindowMillis, value -> diana.lootWindowMillis = value))
                .option(millis(K + "diana.spin_length",
                        DEFAULTS.diana.spinMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_SPIN_MILLIS, 100L,
                        () -> diana.spinMillis, value -> diana.spinMillis = value))
                .option(millis(K + "diana.lock_stagger",
                        DEFAULTS.diana.lockStaggerMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_STAGGER_MILLIS, 25L,
                        () -> diana.lockStaggerMillis, value -> diana.lockStaggerMillis = value))
                .option(millis(K + "diana.settle",
                        DEFAULTS.diana.settleMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_SETTLE_MILLIS, 250L,
                        () -> diana.settleMillis, value -> diana.settleMillis = value))
                .option(millis(K + "diana.fade",
                        DEFAULTS.diana.fadeMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_FADE_MILLIS, 50L,
                        () -> diana.fadeMillis, value -> diana.fadeMillis = value))
                .build();

        // The second act's four durations, in the order they play. They are a group of their own
        // rather than four more rows under the ordinary timings because they are answerable to a
        // different question: the timings above decide how long every kill takes, these decide how
        // long the rare one celebrates, and a player who wants a shorter celebration should not
        // have to read past the settings that govern every roll to find them.
        OptionGroup jackpotTiming = OptionGroup.createBuilder()
                .name(Component.translatable(K + "diana.group.jackpot_timing"))
                .description(describe(K + "diana.group.jackpot_timing"))
                .collapsed(true)
                .option(millis(K + "diana.jackpot_intro",
                        DEFAULTS.diana.jackpotIntroMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_JACKPOT_INTRO_MILLIS, 50L,
                        () -> diana.jackpotIntroMillis, value -> diana.jackpotIntroMillis = value))
                .option(millis(K + "diana.jackpot_spin",
                        DEFAULTS.diana.jackpotSpinMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_JACKPOT_SPIN_MILLIS, 100L,
                        () -> diana.jackpotSpinMillis, value -> diana.jackpotSpinMillis = value))
                .option(millis(K + "diana.jackpot_stagger",
                        DEFAULTS.diana.jackpotLockStaggerMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_JACKPOT_STAGGER_MILLIS, 20L,
                        () -> diana.jackpotLockStaggerMillis,
                        value -> diana.jackpotLockStaggerMillis = value))
                .option(millis(K + "diana.jackpot_hold",
                        DEFAULTS.diana.jackpotHoldMillis, 0L,
                        SkyPrismConfig.DianaSettings.MAX_JACKPOT_HOLD_MILLIS, 100L,
                        () -> diana.jackpotHoldMillis, value -> diana.jackpotHoldMillis = value))
                .build();

        return ConfigCategory.createBuilder()
                .name(Component.translatable(K + "category.diana"))
                .tooltip(Component.translatable(K + "category.diana.tooltip"))
                .group(general)
                .group(areas)
                .group(triggers.build())
                .group(timing)
                .group(jackpotTiming)
                .group(jackpot)
                .build();
    }

    // ================================================================ Loot

    /**
     * The short landing tab: the one switch that covers everything, and the way back to a clean
     * slate.
     *
     * <p>It carries no per-category and no per-source control at all, which is deliberate. YACL
     * applies every option's binding on Save, so the same setting appearing on two tabs would be
     * two controls writing one field, disagreeing the moment either is touched. Every switch
     * therefore lives on exactly one tab, and this one holds only the settings that have nowhere
     * else to be.
     */
    private static ConfigCategory lootOverviewCategory(Draft draft, LootControls controls) {
        SkyPrismConfig.LootSettings loot = draft.config.loot;

        OptionGroup master = OptionGroup.createBuilder()
                .name(Component.translatable(K + "loot.group.master"))
                .description(describe(K + "loot.group.master"))
                .option(toggle(K + "loot.enabled",
                        DEFAULTS.loot.enabled, () -> loot.enabled, value -> loot.enabled = value))
                .option(toggle(K + "loot.suppress_drop_lines",
                        DEFAULTS.loot.suppressDropChatLines,
                        () -> loot.suppressDropChatLines,
                        value -> loot.suppressDropChatLines = value))
                .build();

        OptionGroup housekeeping = OptionGroup.createBuilder()
                .name(Component.translatable(K + "loot.group.housekeeping"))
                .description(describe(K + "loot.group.housekeeping"))
                .option(button(K + "loot.disable_everything", () -> {
                    for (LootSourceCategory category : controls.categories()) {
                        controls.setAllEnabled(category, false);
                    }
                }))
                .option(button(K + "loot.reset_everything", () -> {
                    for (LootSourceCategory category : controls.categories()) {
                        controls.reset(loot, category);
                    }
                }))
                .build();

        return ConfigCategory.createBuilder()
                .name(Component.translatable(K + "category.loot"))
                .tooltip(Component.translatable(K + "category.loot.tooltip"))
                .group(master)
                .group(housekeeping)
                .build();
    }

    /**
     * One tab per drawer: its own switch, three bulk actions, then one collapsed group per source.
     *
     * <p><b>Why a collapsed group per source rather than a flat list of rows.</b> A drawer holds up
     * to twenty-two sources and each has three settings, which flat would be sixty-odd rows of
     * near-identical controls with nothing to navigate by. Collapsed, the tab reads as a list of
     * the things in that part of the game, and a player opens the one they came for. The caption
     * is the only thing on screen until they do.
     */
    private static ConfigCategory lootCategory(Draft draft, LootSourceCategory category,
                                               LootControls controls) {
        SkyPrismConfig.LootSettings loot = draft.config.loot;
        String categoryKey = K + "loot.category." + category.id();

        Option<Boolean> categoryToggle = toggle(
                Component.translatable(categoryKey),
                describe(categoryKey),
                true,
                () -> loot.categoryEnabled(category),
                value -> loot.setCategoryEnabled(category, value));
        controls.addCategoryToggle(category, categoryToggle);

        OptionGroup bulk = OptionGroup.createBuilder()
                .name(Component.translatable(K + "loot.group.bulk"))
                .description(describe(K + "loot.group.bulk"))
                .option(categoryToggle)
                .option(button(K + "loot.enable_all", () -> controls.setAllEnabled(category, true)))
                .option(button(K + "loot.disable_all",
                        () -> controls.setAllEnabled(category, false)))
                .option(button(K + "loot.reset_category", () -> controls.reset(loot, category)))
                .build();

        var builder = ConfigCategory.createBuilder()
                .name(Component.translatable(categoryKey))
                .tooltip(Component.translatable(categoryKey + ".tooltip"))
                .group(bulk);

        for (LootSource source : LootSourceCategory.sources(category)) {
            builder.group(sourceGroup(loot, category, source, controls));
        }
        return builder.build();
    }

    /**
     * The three controls one source gets.
     *
     * <p>Every binding looks the entry up through {@link SkyPrismConfig.LootSettings#settingsFor}
     * rather than capturing it once. That is what makes the reset button honest: it deletes the
     * stored entries and then re-syncs these controls, and a captured reference would have kept
     * writing into an object the config no longer holds.
     */
    private static OptionGroup sourceGroup(SkyPrismConfig.LootSettings loot,
                                           LootSourceCategory category, LootSource source,
                                           LootControls controls) {
        RollPolicy shipped = shippedPolicy(source);

        Option<Boolean> enabled = toggle(
                Component.translatable(K + "loot.source.enabled"),
                describe(K + "loot.source.enabled"),
                true,
                () -> loot.settingsFor(source).enabled,
                value -> loot.settingsFor(source).enabled = value);

        Option<RollPolicy> policy = Option.<RollPolicy>createBuilder()
                .name(Component.translatable(K + "loot.source.policy"))
                .description(policyDescription(source))
                .binding(shipped,
                        () -> loot.settingsFor(source).effectivePolicy(source),
                        value -> loot.settingsFor(source).policy = value)
                .controller(option -> EnumControllerBuilder.create(option)
                        .enumClass(RollPolicy.class)
                        .formatValue(SkyPrismConfigScreen::policyName))
                .build();

        Option<String> jackpot = Option.<String>createBuilder()
                .name(Component.translatable(K + "loot.source.jackpot"))
                .description(describe(K + "loot.source.jackpot"))
                .binding("",
                        () -> loot.settingsFor(source).jackpotText(),
                        value -> loot.settingsFor(source).applyJackpotText(value))
                .controller(StringControllerBuilder::create)
                .build();

        controls.add(category, enabled, policy, jackpot);

        return OptionGroup.createBuilder()
                .name(sourceName(source))
                .description(sourceDescription(source))
                .collapsed(true)
                .option(enabled)
                .option(policy)
                .option(jackpot)
                .build();
    }

    // ================================================================ HUD

    private static ConfigCategory hudCategory(Draft draft) {
        SkyPrismConfig.HudSettings hud = draft.config.hud;

        OptionGroup placement = OptionGroup.createBuilder()
                .name(Component.translatable(K + "hud.group.placement"))
                .description(describe(K + "hud.group.placement"))
                .option(Option.<HudAnchor>createBuilder()
                        .name(Component.translatable(K + "hud.anchor"))
                        .description(describe(K + "hud.anchor"))
                        .binding(DEFAULTS.hud.anchor, () -> hud.anchor, value -> hud.anchor = value)
                        .controller(option -> EnumControllerBuilder.create(option)
                                .enumClass(HudAnchor.class)
                                .formatValue(SkyPrismConfigScreen::anchorName))
                        .build())
                .option(fraction(K + "hud.x",
                        DEFAULTS.hud.x, () -> hud.x, value -> hud.x = value))
                .option(fraction(K + "hud.y",
                        DEFAULTS.hud.y, () -> hud.y, value -> hud.y = value))
                .option(Option.<Double>createBuilder()
                        .name(Component.translatable(K + "hud.scale"))
                        .description(describe(K + "hud.scale"))
                        .binding(DEFAULTS.hud.scale, () -> hud.scale, value -> hud.scale = value)
                        .controller(option -> DoubleSliderControllerBuilder.create(option)
                                .range(SkyPrismConfig.HudSettings.MIN_SCALE,
                                        SkyPrismConfig.HudSettings.MAX_SCALE)
                                .step(0.05)
                                .formatValue(value -> Component.translatable(K + "value.scale",
                                        String.format(Locale.ROOT, "%.2f", value))))
                        .build())
                .build();

        OptionGroup appearance = OptionGroup.createBuilder()
                .name(Component.translatable(K + "hud.group.appearance"))
                .option(toggle(K + "hud.enabled",
                        DEFAULTS.hud.enabled, () -> hud.enabled, value -> hud.enabled = value))
                .option(toggle(K + "hud.background",
                        DEFAULTS.hud.drawBackground,
                        () -> hud.drawBackground, value -> hud.drawBackground = value))
                .option(fraction(K + "hud.background_opacity",
                        DEFAULTS.hud.backgroundOpacity,
                        () -> hud.backgroundOpacity, value -> hud.backgroundOpacity = value))
                .option(toggle(K + "hud.show_creature_name",
                        DEFAULTS.hud.showCreatureName,
                        () -> hud.showCreatureName, value -> hud.showCreatureName = value))
                .option(toggle(K + "hud.show_drop_names",
                        DEFAULTS.hud.showDropNames,
                        () -> hud.showDropNames, value -> hud.showDropNames = value))
                .build();

        return ConfigCategory.createBuilder()
                .name(Component.translatable(K + "category.hud"))
                .tooltip(Component.translatable(K + "category.hud.tooltip"))
                .group(appearance)
                .group(placement)
                .build();
    }

    // ================================================================ Sounds

    private static ConfigCategory soundsCategory(Draft draft) {
        SkyPrismConfig.SoundSettings sounds = draft.config.sounds;

        return ConfigCategory.createBuilder()
                .name(Component.translatable(K + "category.sounds"))
                .tooltip(Component.translatable(K + "category.sounds.tooltip"))
                .option(toggle(K + "sounds.enabled",
                        DEFAULTS.sounds.enabled, () -> sounds.enabled, value -> sounds.enabled = value))
                .option(fraction(K + "sounds.volume",
                        DEFAULTS.sounds.volume, () -> sounds.volume, value -> sounds.volume = value))
                .option(toggle(K + "sounds.reel_ticks",
                        DEFAULTS.sounds.reelTicks, () -> sounds.reelTicks, value -> sounds.reelTicks = value))
                .option(toggle(K + "sounds.jackpot",
                        DEFAULTS.sounds.jackpotSound,
                        () -> sounds.jackpotSound, value -> sounds.jackpotSound = value))
                .build();
    }

    // ================================================================ i18n helpers

    /**
     * The description for an option whose key follows the house convention: the option's own
     * key with {@code .desc} on the end.
     */
    private static OptionDescription describe(String key) {
        return OptionDescription.of(Component.translatable(key + ".desc"));
    }

    /**
     * A colouring mode's user-facing name.
     *
     * <p>Falls back to the prettified constant so that a mode added to the core shows as
     * "Some new mode" rather than as a raw key until its translation is written.</p>
     */
    private static Component modeName(LevelColorMode mode) {
        return Component.translatableWithFallback(
                "skyprism.common.mode." + mode.name().toLowerCase(Locale.ROOT), pretty(mode.name()));
    }

    /** A HUD anchor's user-facing name, with the same fallback rule as {@link #modeName}. */
    private static Component anchorName(HudAnchor anchor) {
        return Component.translatableWithFallback(
                "skyprism.common.anchor." + anchor.name().toLowerCase(Locale.ROOT), pretty(anchor.name()));
    }

    /**
     * A roll policy's user-facing name, with the same fallback rule as {@link #modeName}.
     *
     * <p>The four constants are not self-explanatory to somebody who has not read the design notes,
     * so the wording in {@code en_us.json} says what each one costs rather than repeating what it
     * is called in the code.</p>
     */
    private static Component policyName(RollPolicy policy) {
        return Component.translatableWithFallback(
                "skyprism.common.policy." + policy.name().toLowerCase(Locale.ROOT),
                pretty(policy.name()));
    }

    /**
     * A loot source's caption.
     *
     * <p>The registry's own display name is the fallback, so a source added on the detection side
     * gets a readable English caption here with no change to this file and no language edit.</p>
     */
    private static Component sourceName(LootSource source) {
        return Component.translatableWithFallback(
                "skyprism.common.loot_source." + source.id(), registryDisplayName(source));
    }

    /**
     * A source's description: the researched note on why its default is what it is, plus a
     * one-line summary of the gate it sits behind.
     *
     * <p>Both are read from {@code LootSourceRegistry}, which is where they are kept accurate,
     * rather than copied into the language file where they would drift the first time the research
     * was revised. A translator can still override the whole thing by adding the key.</p>
     */
    private static OptionDescription sourceDescription(LootSource source) {
        String note;
        String gate;
        try {
            note = LootSourceRegistry.info(source).note();
            gate = LootSourceRegistry.gate(source).describe();
        } catch (RuntimeException noEntry) {
            note = "";
            gate = "";
        }
        var lines = new ArrayList<Component>();
        lines.add(Component.translatableWithFallback(
                K + "loot.source." + source.id() + ".desc", note.isBlank() ? " " : note));
        if (gate != null && !gate.isBlank()) {
            lines.add(Component.empty());
            lines.add(Component.translatable(K + "loot.source.gate", gate));
        }
        return OptionDescription.of(lines.toArray(Component[]::new));
    }

    /**
     * The policy control's description, with this source's shipped default named in it.
     *
     * <p>Naming the shipped value matters more here than anywhere else in the screen. The dropdown
     * shows the value in force, which for an untouched source <em>is</em> the default, so without
     * this line there is nothing to tell a player which of the four the mod chose for them and
     * which they chose for themselves.</p>
     */
    private static OptionDescription policyDescription(LootSource source) {
        return OptionDescription.of(
                Component.translatable(K + "loot.source.policy.desc"),
                Component.empty(),
                Component.translatable(K + "loot.source.policy.shipped",
                        policyName(shippedPolicy(source))));
    }

    /** The shipped default policy, falling back to NEVER if the registry cannot answer. */
    private static RollPolicy shippedPolicy(LootSource source) {
        try {
            return LootSourceRegistry.defaultPolicy(source);
        } catch (RuntimeException noEntry) {
            return RollPolicy.NEVER;
        }
    }

    /** The registry's caption, falling back to the prettified constant name. */
    private static String registryDisplayName(LootSource source) {
        try {
            return LootSourceRegistry.displayName(source);
        } catch (RuntimeException noEntry) {
            return pretty(source.name());
        }
    }

    /**
     * A creature's user-facing name.
     *
     * <p>The core's {@code displayName()} is the fallback, so adding a creature to the core
     * needs no change here and no language file edit before it reads correctly in English.</p>
     */
    private static Component creatureName(MythologicalCreature creature) {
        return Component.translatableWithFallback(
                "skyprism.common.creature." + creature.name().toLowerCase(Locale.ROOT),
                creature.displayName());
    }

    // ================================================================ control helpers

    /** A tick box whose name is {@code key} and whose description is {@code key + ".desc"}. */
    private static Option<Boolean> toggle(String key, boolean fallback,
                                          Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return toggle(Component.translatable(key), describe(key), fallback, getter, setter);
    }

    /** A tick box whose name and description do not share a key -- the creature triggers. */
    private static Option<Boolean> toggle(Component name, OptionDescription description, boolean fallback,
                                          Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(name)
                .description(description)
                .binding(fallback, getter, setter)
                .controller(TickBoxControllerBuilder::create)
                .build();
    }

    /**
     * A push button whose label, button text and description all hang off one key.
     *
     * <p>The action runs against the controls, not against the config: a button that wrote straight
     * into the draft would leave every control on screen showing the value it had a moment ago, and
     * YACL would then write those stale values back over the button's work on Save.
     */
    private static ButtonOption button(String key, Runnable action) {
        return ButtonOption.createBuilder()
                .name(Component.translatable(key))
                .text(Component.translatable(key + ".text"))
                .description(describe(key))
                // The two-argument form deliberately: the one-argument Consumer overload is
                // deprecated, and neither the screen nor the button itself is needed here.
                .action((screen, self) -> action.run())
                .build();
    }

    /** A duration in milliseconds, shown as a slider that reads in seconds where that is clearer. */
    private static Option<Long> millis(String key, long fallback,
                                       long min, long max, long step,
                                       Supplier<Long> getter, Consumer<Long> setter) {
        return Option.<Long>createBuilder()
                .name(Component.translatable(key))
                .description(describe(key))
                .binding(fallback, getter, setter)
                .controller(option -> LongSliderControllerBuilder.create(option)
                        .range(min, max)
                        .step(step)
                        .formatValue(value -> Component.translatable(K + "value.seconds",
                                String.format(Locale.ROOT, "%.2f", value / 1000.0))))
                .build();
    }

    /** A 0..1 quantity, shown as a percentage because nobody thinks in thousandths of a screen. */
    private static Option<Double> fraction(String key, double fallback,
                                           Supplier<Double> getter, Consumer<Double> setter) {
        return Option.<Double>createBuilder()
                .name(Component.translatable(key))
                .description(describe(key))
                .binding(fallback, getter, setter)
                .controller(option -> DoubleSliderControllerBuilder.create(option)
                        .range(0.0, 1.0)
                        .step(0.01)
                        .formatValue(value -> Component.translatable(K + "value.percent",
                                String.format(Locale.ROOT, "%.0f", value * 100.0))))
                .build();
    }

    /** {@code TOP_CENTER} to {@code Top center}: enum constants are not user-facing prose. */
    private static String pretty(String constant) {
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Copies a YACL-supplied list into the draft's own list rather than swapping the
     * reference. The bindings hand back a list YACL still owns, and holding onto it would
     * let a later edit mutate the draft behind the screen's back.
     */
    private static <T> void replace(List<T> target, List<T> replacement) {
        target.clear();
        if (replacement != null) {
            target.addAll(replacement);
        }
    }

    private static List<Integer> defaultStopLevels() {
        List<Integer> out = new ArrayList<>();
        for (GradientRamp.Stop stop : PalettePresets.defaultRamp().stops()) {
            out.add(stop.level());
        }
        return out;
    }

    private static List<Color> defaultStopColours() {
        List<Color> out = new ArrayList<>();
        for (GradientRamp.Stop stop : PalettePresets.defaultRamp().stops()) {
            out.add(new Color(stop.rgb()));
        }
        return out;
    }

    /**
     * The bracket table the reset arrow restores, which must be the one the mod ships.
     *
     * <p>These two feed {@code .binding(default, getter, setter)}, so YACL treats what they
     * return as "unchanged" and hands it back when the reset arrow is clicked. That makes them
     * the third place the shipped table is named, after the field initialiser and the
     * sanitiser, and the only one with no test watching it. When the default moved off
     * {@code fineBrackets()} and these stayed behind, the reset arrow would have written a
     * table the mod no longer ships anywhere else - a config the user cannot get back to by
     * any other route, produced by the one control whose whole job is "put it back".
     * {@link PalettePresets#defaultBrackets()} is the single name for the shipped table; read
     * it here rather than naming a preset.</p>
     */
    private static List<Integer> defaultBracketLevels() {
        List<Integer> out = new ArrayList<>();
        for (BracketTable.Bracket bracket : PalettePresets.defaultBrackets().brackets()) {
            out.add(bracket.minLevel());
        }
        return out;
    }

    /** @see #defaultBracketLevels() */
    private static List<Color> defaultBracketColours() {
        List<Color> out = new ArrayList<>();
        for (BracketTable.Bracket bracket : PalettePresets.defaultBrackets().brackets()) {
            out.add(new Color(bracket.rgb()));
        }
        return out;
    }

    /**
     * Every loot control on the screen, indexed by the tab it sits on.
     *
     * <p>It exists so the bulk buttons can be real. YACL keeps a pending value per control and only
     * writes the ones that changed, so "disable every source in this category" cannot be done by
     * editing the config: the seventeen tick boxes on screen would still be showing, and on Save
     * still writing, the values they were built with. Driving the controls instead means the button
     * does exactly what clicking each box would have done, including leaving the change cancellable.
     *
     * <p>The reset button is the one that needs both halves. It deletes the stored entries, which
     * is the only way back to "no opinion" once a control has been touched, and then calls
     * {@code forgetPendingValue()} so each control re-reads what the config now says. That leaves
     * the controls showing the shipped defaults <em>and</em> marked unchanged, so Save writes
     * nothing back and the entries stay deleted.
     */
    private static final class LootControls {

        private final Map<LootSourceCategory, List<Option<?>>> all =
                new EnumMap<>(LootSourceCategory.class);
        private final Map<LootSourceCategory, List<Option<Boolean>>> enables =
                new EnumMap<>(LootSourceCategory.class);

        /** The categories that ended up with a tab, in the order they were built. */
        List<LootSourceCategory> categories() {
            return new ArrayList<>(all.keySet());
        }

        void addCategoryToggle(LootSourceCategory category, Option<Boolean> toggle) {
            all.computeIfAbsent(category, key -> new ArrayList<>()).add(toggle);
        }

        void add(LootSourceCategory category, Option<Boolean> enabled, Option<?>... rest) {
            all.computeIfAbsent(category, key -> new ArrayList<>()).add(enabled);
            enables.computeIfAbsent(category, key -> new ArrayList<>()).add(enabled);
            for (Option<?> option : rest) {
                all.get(category).add(option);
            }
        }

        void setAllEnabled(LootSourceCategory category, boolean on) {
            for (Option<Boolean> option : enables.getOrDefault(category, List.of())) {
                option.requestSet(on);
            }
        }

        void reset(SkyPrismConfig.LootSettings loot, LootSourceCategory category) {
            loot.resetCategory(category);
            for (Option<?> option : all.getOrDefault(category, List.of())) {
                option.forgetPendingValue();
            }
        }
    }

    /**
     * The screen's scratch state: the config being edited, plus the flattened lists the
     * multi-field settings are edited through.
     *
     * <p>It exists because a {@code ListOption} can only edit one value per row, while a
     * gradient stop and a bracket row each carry two, and because a trigger set is nicer as
     * a column of tick boxes than as a list of typed names. Nothing here is visible to the
     * rest of the mod until {@link #commit()} folds it back into the config and
     * {@link ConfigManager#apply(SkyPrismConfig)} adopts the result.</p>
     */
    private static final class Draft {
        private final SkyPrismConfig config;
        private final List<Integer> stopLevels = new ArrayList<>();
        private final List<Color> stopColours = new ArrayList<>();
        private final List<Integer> bracketLevels = new ArrayList<>();
        private final List<Color> bracketColours = new ArrayList<>();
        private final Set<MythologicalCreature> triggers = EnumSet.noneOf(MythologicalCreature.class);
        private final List<String> jackpotItems = new ArrayList<>();
        private final List<String> allowedAreas = new ArrayList<>();

        Draft(SkyPrismConfig config) {
            this.config = config;
            for (GradientRamp.Stop stop : config.levels.customStops) {
                if (stop != null) {
                    stopLevels.add(stop.level());
                    stopColours.add(new Color(stop.rgb()));
                }
            }
            for (BracketTable.Bracket bracket : config.levels.brackets) {
                if (bracket != null) {
                    bracketLevels.add(bracket.minLevel());
                    bracketColours.add(new Color(bracket.rgb()));
                }
            }
            triggers.addAll(config.diana.triggers);
            jackpotItems.addAll(config.diana.jackpotItems);
            allowedAreas.addAll(config.diana.allowedAreas);
        }

        /**
         * Folds the flattened lists back into the config.
         *
         * <p>Zipping stops at the shorter of each pair: a colour with no level, or a level
         * with no colour, is not a stop. Everything else -- duplicate levels, an unsorted
         * list, more rows than the core allows -- is left to {@code sanitized()}, which
         * already has tested rules for all of it.</p>
         */
        void commit() {
            List<GradientRamp.Stop> stops = new ArrayList<>();
            int stopCount = Math.min(stopLevels.size(), stopColours.size());
            for (int i = 0; i < stopCount; i++) {
                Integer level = stopLevels.get(i);
                Color colour = stopColours.get(i);
                if (level != null && colour != null) {
                    stops.add(new GradientRamp.Stop(level, rgb(colour)));
                }
            }
            config.levels.customStops = stops;

            List<BracketTable.Bracket> brackets = new ArrayList<>();
            int bracketCount = Math.min(bracketLevels.size(), bracketColours.size());
            for (int i = 0; i < bracketCount; i++) {
                Integer level = bracketLevels.get(i);
                Color colour = bracketColours.get(i);
                if (level != null && colour != null) {
                    brackets.add(new BracketTable.Bracket(level, rgb(colour)));
                }
            }
            config.levels.brackets = brackets;

            config.diana.triggers = new LinkedHashSet<>(triggers);

            Set<String> items = new LinkedHashSet<>();
            for (String item : jackpotItems) {
                if (item != null && !item.isBlank()) {
                    items.add(item.trim());
                }
            }
            config.diana.jackpotItems = items;

            Set<String> islands = new LinkedHashSet<>();
            for (String area : allowedAreas) {
                if (area != null && !area.isBlank()) {
                    islands.add(area.trim());
                }
            }
            config.diana.allowedAreas = islands;
        }

        /** Drops the alpha byte: the core speaks 24-bit packed RGB and would misread a set alpha as red. */
        private static int rgb(Color colour) {
            return colour.getRGB() & 0xFFFFFF;
        }
    }
}
