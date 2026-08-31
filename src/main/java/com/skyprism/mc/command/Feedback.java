package com.skyprism.mc.command;

import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.level.GradientRamp;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Locale;

/**
 * The house style for everything {@code /skyprism} prints.
 *
 * <p><b>Why a class rather than inline literals.</b> Nine subcommands printing ad-hoc
 * {@code Component} calls drift within a week: two spellings of the prefix, three shades of
 * "value", labels that line up in one command and not the next. Centralising it means a
 * reply reads as the same program wherever it came from, and means the one place that
 * decides what a "good" or "bad" value looks like can be changed once.</p>
 *
 * <p>The prefix is built through the core's own {@link GradientRamp} - the same Oklab
 * interpolation that colours level tags - so the mod's own name is a small live
 * demonstration of the feature. It is assembled once into a static field: chat feedback is
 * not a hot path, but rebuilding eight styled components per line for no reason is still
 * the wrong habit to set in a codebase whose headline claim is that it costs nothing.</p>
 *
 * <p><b>Everything user-visible is a translation key.</b> Every method here takes a
 * {@link Component}, never a {@code String}, so a caller cannot accidentally introduce an
 * untranslatable literal. The composition rule that goes with it: a line is one
 * {@code Component.translatable} with its variable parts passed as arguments, not a chain of
 * translated fragments glued together with {@code append}. Argument components keep their
 * own colour, so a whole sentence can be one key and still have its numbers picked out in
 * the value colour. Keys live under {@code skyprism.command.*}, with names shared with the
 * settings screen under {@code skyprism.common.*}.</p>
 */
public final class Feedback {

    private Feedback() {
    }

    /** Label colour: dimmed, so values stand out against it. */
    public static final int LABEL = 0x9AA4B2;

    /** Value colour: near-white, the thing the eye should land on. */
    public static final int VALUE = 0xE6EDF3;

    /** An enabled / healthy / true value. */
    public static final int GOOD = 0x6EE7A8;

    /** A disabled / inert value. Not an error - just off. */
    public static final int OFF = 0x8B95A5;

    /** A warning: something works but not the way the user probably expects. */
    public static final int WARN = 0xF5C451;

    /** A failure. */
    public static final int BAD = 0xF87171;

    /** A heading or an interactive affordance. */
    public static final int ACCENT = 0x7DD3FC;

    /** Every key this class and the command tree print sits under here. */
    static final String K = "skyprism.command.";

    /**
     * The ramp the "SkyPrism" wordmark is drawn with: sky blue, through prism violet, into
     * warm rose. Three stops rather than two so the Oklab interpolation has something to
     * show off - a two-stop ramp over eight glyphs is barely distinguishable from a solid.
     */
    private static final GradientRamp WORDMARK = GradientRamp.of(
            0, 0x7DD3FC,
            4, 0xC084FC,
            7, 0xFB7185);

    /**
     * The wordmark is the mod's name, not prose: it is spelled the same in every language,
     * so it stays a literal here rather than becoming a key nobody should ever change.
     */
    private static final String NAME = "SkyPrism";

    /** Built once; chat feedback re-uses the same immutable component every time. */
    private static final Component PREFIX = buildPrefix();

    private static Component buildPrefix() {
        MutableComponent out = Component.literal("[").withColor(0x4C566A);
        for (int i = 0; i < NAME.length(); i++) {
            out.append(Component.literal(String.valueOf(NAME.charAt(i)))
                    .withColor(WORDMARK.colorAt(i)));
        }
        return out.append(Component.literal("] ").withColor(0x4C566A));
    }

    /**
     * @return the "[SkyPrism] " prefix, gradient-coloured, shared and immutable
     */
    public static Component prefix() {
        return PREFIX;
    }

    /**
     * Prints one prefixed line.
     *
     * @param source the command source
     * @param body   the line's content
     */
    public static void send(FabricClientCommandSource source, Component body) {
        source.sendFeedback(Component.empty().append(PREFIX).append(body));
    }

    /**
     * Prints one continuation line, indented and unprefixed, for list output.
     *
     * @param source the command source
     * @param body   the line's content
     */
    public static void detail(FabricClientCommandSource source, Component body) {
        source.sendFeedback(Component.literal("  ").append(body));
    }

    /**
     * Prints a section heading.
     *
     * @param source the command source
     * @param text   the heading
     */
    public static void heading(FabricClientCommandSource source, Component text) {
        send(source, text.copy().withColor(ACCENT).withStyle(ChatFormatting.BOLD));
    }

    /**
     * Reports a failure. Uses {@code sendError}, which the client renders in red and which
     * distinguishes "you typed something I could not use" from ordinary output.
     *
     * @param source the command source
     * @param text   a sentence saying what went wrong and, where possible, what to do
     */
    public static void error(FabricClientCommandSource source, Component text) {
        source.sendError(Component.empty().append(PREFIX).append(text.copy().withColor(BAD)));
    }

    /**
     * A {@code label: value} row for status output.
     *
     * <p>The separator is a translation key of its own rather than a hardcoded {@code ": "},
     * because the punctuation that joins a label to its value is not the same in every
     * script.</p>
     *
     * @param label      the field name
     * @param value      the field value
     * @param valueColor packed 0xRRGGBB for the value
     * @return the row
     */
    public static MutableComponent row(Component label, Component value, int valueColor) {
        return Component.translatable(K + "row", label, value.copy().withColor(valueColor))
                .withColor(LABEL);
    }

    /**
     * A {@code label: value} row in the neutral value colour.
     *
     * @param label the field name
     * @param value the field value
     * @return the row
     */
    public static MutableComponent row(Component label, Component value) {
        return row(label, value, VALUE);
    }

    /**
     * A {@code label: on/off} row, coloured by the state.
     *
     * @param label the field name
     * @param on    the state
     * @return the row
     */
    public static MutableComponent toggle(Component label, boolean on) {
        return Component.translatable(K + "row", label, onOff(on)).withColor(LABEL);
    }

    /**
     * The word "on" or "off", already coloured by what it means.
     *
     * <p>Returned as a component rather than a String so it can be dropped straight into a
     * {@code translatable} argument and keep its colour inside a sentence the translator
     * controls the shape of.</p>
     *
     * @param on the state
     * @return the coloured word
     */
    public static MutableComponent onOff(boolean on) {
        return Component.translatable(on ? "skyprism.common.on" : "skyprism.common.off")
                .withColor(on ? GOOD : OFF);
    }

    /**
     * A creature's user-facing name.
     *
     * <p>The core's {@code displayName()} is the fallback, so a creature added to the core
     * reads correctly in English before anybody writes its key.</p>
     *
     * @param creature the creature
     * @return its name
     */
    public static MutableComponent creature(MythologicalCreature creature) {
        return Component.translatableWithFallback(
                "skyprism.common.creature." + creature.name().toLowerCase(Locale.ROOT),
                creature.displayName());
    }

    /**
     * Text the player can click to copy, with a hover hint. Used for the config path, which
     * is long, platform-specific and exactly the sort of thing people want to paste into a
     * file manager rather than retype.
     *
     * @param text  the visible and copied text
     * @param color packed 0xRRGGBB
     * @return the clickable component
     */
    public static MutableComponent copyable(String text, int color) {
        Style style = Style.EMPTY
                .withColor(color)
                .withUnderlined(Boolean.TRUE)
                .withClickEvent(new ClickEvent.CopyToClipboard(text))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.translatable(K + "copy_hint").withColor(ACCENT)));
        return Component.literal(text).withStyle(style);
    }

    /**
     * Text that <em>types</em> another SkyPrism subcommand into the chat box when clicked,
     * so the status summary can offer its own next steps.
     *
     * <p>Deliberately {@link ClickEvent.SuggestCommand} and not {@code RunCommand}. A
     * run-command click is dispatched down the vanilla path, which hands the line straight
     * to the server - it would send {@code /skyprism preview} to Hypixel. Suggesting fills
     * the chat box instead, and pressing Enter routes it through the normal chat path where
     * Fabric's client dispatcher intercepts it. This command tree never speaks to the
     * server and this is the one place that rule is easy to break by accident.</p>
     *
     * <p>The label is a literal because it <em>is</em> the command being suggested: a
     * translated "preview" that no longer matches what the click types would be a lie.</p>
     *
     * @param text    the visible label, which is the command's own spelling
     * @param command the full command including the leading slash
     * @param color   packed 0xRRGGBB
     * @return the clickable component
     */
    public static MutableComponent suggestion(String text, String command, int color) {
        Style style = Style.EMPTY
                .withColor(color)
                .withUnderlined(Boolean.TRUE)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal(command).withColor(ACCENT)));
        return Component.literal(text).withStyle(style);
    }

    /**
     * Formats a fraction as a percentage with one decimal, which is the resolution a cache
     * hit rate needs: "99.8%" and "100.0%" are different claims, "100%" and "100%" are not.
     *
     * @param fraction a value in 0..1
     * @return the formatted percentage
     */
    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    /**
     * Formats a duration in microseconds with the precision a reader can act on.
     *
     * @param micros microseconds
     * @return the formatted value with its unit
     */
    public static String micros(double micros) {
        if (micros >= 1000.0) {
            return String.format(Locale.ROOT, "%.2f ms", micros / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1f us", micros);
    }

    /**
     * Formats a rate.
     *
     * @param perSecond events per second
     * @return the formatted value with its unit
     */
    public static String rate(double perSecond) {
        return String.format(Locale.ROOT, "%.1f/s", perSecond);
    }
}
