package com.skyprism.mc.selftest;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.core.text.StyledRun;
import com.skyprism.core.diana.DianaGate;
import com.skyprism.mc.chat.ChatRouter;
import com.skyprism.mc.config.ConfigManager;
import com.skyprism.mc.diana.DianaController;
import com.skyprism.mc.text.ComponentRewriter;
import com.skyprism.mc.text.LegacyText;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a Hypixel-shaped chat line through the shipped recolour path and writes down what happened.
 *
 * <h2>What this proves that the test suite does not</h2>
 *
 * <p>{@code ComponentRewriterMcTest} already asserts most of this on a bare JVM, and it should keep
 * doing so. What it cannot assert is that the same thing happens <em>in a running client</em>, with
 * the palette and locator the config module actually published, through the callback Fabric
 * actually invokes. Those are different objects reached by a different road, and the interesting
 * failures -- a palette built from unsanitised settings, a locator whose range excludes the tag,
 * a config listener that never fired -- live only on that road.</p>
 *
 * <h2>Two runs, on purpose</h2>
 *
 * <ol>
 *   <li><b>Deterministic.</b> {@link ComponentRewriter#recolourLevels} called with a fixed
 *       millisecond, so the expected colour can be computed exactly and compared exactly. Chroma is
 *       switched on for the self test, and a live clock would make the tag colour unpredictable by
 *       design -- which is the correct behaviour and a useless assertion.</li>
 *   <li><b>Live.</b> {@link ChatRouter#modifyGameMessage}, the body Fabric calls on every game
 *       message. This one cannot check an exact colour, so it checks the two things that hold
 *       whatever the clock says: the text is untouched and the tag no longer wears the colour the
 *       server sent.</li>
 * </ol>
 *
 * <h2>The fixture</h2>
 *
 * <p>Shaped like a real Hypixel chat line rather than like a convenient one:</p>
 *
 * <pre>
 *   [451] [MVP+] Notch &#10248; : hello [4200] world
 *   |     |      |     |         |
 *   |     |      |     |         a number the locator must reject (outside 0..1000)
 *   |     |      |     the emblem, to the RIGHT of the name
 *   |     |      the name, carrying hover + click + insertion + italic
 *   |     the rank prefix, split across runs the way Hypixel colours the plus sign
 *   the level tag
 * </pre>
 *
 * <p>The emblem matters because it is the thing most likely to be recoloured by accident: it sits
 * adjacent to the name and, in the fixture as in the game, it is a separate coloured run. The
 * rewriter has no emblem-specific code and must not need any -- an emblem is not bracketed digits,
 * so the locator simply never sees it. This probe exists to demonstrate that claim rather than
 * trust it.</p>
 */
final class RecolourProbe {

    private RecolourProbe() {
    }

    /** The level in the fixture tag. */
    private static final int LEVEL = 451;

    /**
     * The instant the deterministic run pretends it is.
     *
     * <p>Any constant would do. This one is fixed so that two self-test runs on the same
     * configuration produce byte-identical expected colours, which makes the report diffable.</p>
     */
    private static final long FIXED_MILLIS = 1_700_000_000_000L;

    /** Hypixel colour for the level tag in the fixture: the purple it sends for a mid-tier level. */
    private static final int TAG_SOURCE_RGB = 0xAA00AA;

    /** The rank prefix colour. */
    private static final int RANK_RGB = 0x55FFFF;

    /** The plus sign inside the rank, coloured separately exactly as Hypixel does it. */
    private static final int RANK_PLUS_RGB = 0xFF5555;

    /** The emblem colour; deliberately unlike every other colour in the line. */
    private static final int EMBLEM_RGB = 0x00AA00;

    /** The emblem glyph, U+2748 SPARKLE, one of the shapes Hypixel awards every ten levels. */
    private static final String EMBLEM = "❈";

    /** A bracketed number in the message body, outside the locator range, that must survive. */
    private static final String BODY_NUMBER = "4200";

    /**
     * The outcome of one probe run.
     *
     * @param passed  true when every check held
     * @param summary a single line naming how many checks ran and which failed
     * @param report  where the full before/after write-up was written, or null when it could not be
     */
    record Result(boolean passed, String summary, Path report) {
    }

    /**
     * Builds the fixture, rewrites it both ways, checks six claims and writes the report.
     *
     * @param reportFile where to write the plain-text write-up
     * @return the outcome; this method does not throw
     */
    static Result run(Path reportFile) {
        List<Check> checks = new ArrayList<>(8);
        StringBuilder out = new StringBuilder(4096);
        try {
            probe(checks, out);
        } catch (Throwable broken) {
            checks.add(new Check("probe completed", false,
                    broken.getClass().getSimpleName() + ": " + broken.getMessage()));
        }

        int failed = 0;
        for (Check check : checks) {
            if (!check.passed()) {
                failed++;
            }
        }

        out.append("\n== verdict ==\n");
        for (Check check : checks) {
            out.append(check.passed() ? "  PASS  " : "  FAIL  ")
                    .append(check.what()).append('\n')
                    .append("        ").append(check.detail()).append('\n');
        }
        out.append('\n')
                .append(failed == 0 ? "ALL CHECKS PASSED" : failed + " CHECK(S) FAILED")
                .append(" (").append(checks.size()).append(" total)\n");

        Path written = null;
        try {
            Path parent = reportFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportFile, out.toString(), StandardCharsets.UTF_8);
            written = reportFile;
        } catch (Throwable unwritable) {
            checks.add(new Check("report written", false, unwritable.toString()));
            failed++;
        }

        String summary = (checks.size() - failed) + "/" + checks.size() + " checks passed";
        if (failed > 0) {
            StringBuilder names = new StringBuilder();
            for (Check check : checks) {
                if (!check.passed()) {
                    names.append(names.isEmpty() ? "" : "; ").append(check.what());
                }
            }
            summary = summary + " - failed: " + names;
        }
        return new Result(failed == 0, summary, written);
    }

    // ------------------------------------------------------------------ the probe itself

    private static void probe(List<Check> checks, StringBuilder out) {
        SkyPrismConfig config = ConfigManager.get().config();
        LevelPalette palette = ConfigManager.get().palette();
        LevelTagLocator locator = ConfigManager.get().locator();
        boolean brackets = config.levels.recolourBrackets;

        Component before = fixture();
        Component fixed = ComponentRewriter.recolourLevels(before, palette, locator, brackets,
                FIXED_MILLIS);
        Component live = liveRecolour(before, config);

        int expected = palette.colorFor(LEVEL, FIXED_MILLIS);

        // Which run the recoloured tag ends up in, which is not a constant: the rewriter emits
        // one run per contiguous span of one style, so tinting the brackets merges "[", the
        // digits and "]" into a single "[451]" run, while leaving them alone keeps the digits as
        // a run of their own. Both are correct; a probe that only knew the second shape reported
        // three failures the moment the shipped default flipped, which is a probe reporting on
        // itself rather than on the mod.
        String tagRun = brackets ? "[" + LEVEL + "]" : String.valueOf(LEVEL);

        header(out, config, palette, locator, brackets, expected);
        dump(out, "BEFORE", before);
        dump(out, "AFTER (deterministic, ComponentRewriter.recolourLevels at t=" + FIXED_MILLIS + ")",
                fixed);
        dump(out, "AFTER (live, ChatRouter.modifyGameMessage)", live);

        // 1. The rewriter must not be a no-op, or every check below would pass vacuously.
        checks.add(new Check("the rewriter rewrote something",
                fixed != before,
                fixed != before
                        ? "recolourLevels returned a new tree; it returns the argument by identity "
                                + "when it has nothing to do, so this is the real signal"
                        : "recolourLevels handed back the same reference: no tag was matched"));

        // 2. Flattened text unchanged. The whole feature is a restyle; a single character added or
        //    dropped here would corrupt every chat line on the server.
        String beforeText = before.getString();
        checks.add(new Check("flattened text is unchanged",
                beforeText.equals(fixed.getString()) && beforeText.equals(live.getString()),
                "before=" + quote(beforeText)
                        + " deterministic=" + quote(fixed.getString())
                        + " live=" + quote(live.getString())));

        // 3. The tag carries exactly the palette colour for level 451.
        Integer digitsAfter = colourOf(fixed, tagRun);
        checks.add(new Check("the level tag was recoloured to the palette colour",
                digitsAfter != null && digitsAfter == expected,
                "run " + quote(tagRun) + " is " + hex(digitsAfter)
                        + ", palette.colorFor(" + LEVEL + ", " + FIXED_MILLIS + ") is "
                        + hex(expected) + ", server sent " + hex(TAG_SOURCE_RGB)
                        + " (recolourBrackets=" + brackets + ", so the tag is one run"
                        + (brackets ? " including its brackets" : " of bare digits") + ")"));

        // 4. The emblem, which sits immediately to the right of the name, is untouched.
        Integer emblemAfter = colourOf(fixed, " " + EMBLEM);
        checks.add(new Check("the emblem was NOT recoloured",
                emblemAfter != null && emblemAfter == EMBLEM_RGB,
                "run " + quote(" " + EMBLEM) + " is " + hex(emblemAfter) + ", was "
                        + hex(EMBLEM_RGB) + " - the locator matches bracketed digits only, so an "
                        + "emblem cannot produce a span"));

        // 5. Hover, click and insertion survive the round trip. Losing these is invisible to
        //    whoever wrote the mod, because the colours still look right.
        Style name = styleOf(fixed, "Notch");
        boolean interactive = name != null
                && name.getHoverEvent() != null
                && name.getClickEvent() != null
                && "Notch".equals(name.getInsertion())
                && name.isItalic()
                && name.isBold();
        checks.add(new Check("hover, click, insertion and formatting survived",
                interactive,
                name == null
                        ? "the run " + quote("Notch") + " is gone from the rewritten tree"
                        : "hover=" + name.getHoverEvent() + " click=" + name.getClickEvent()
                                + " insertion=" + quote(name.getInsertion())
                                + " italic=" + name.isItalic()
                                + " bold(inherited from the root)=" + name.isBold()));

        // 6. Two things next to the tag that must not move: the brackets, which are only tinted
        //    when the player asks, and a bracketed number outside the locator range.
        // With the brackets tinted they are inside the tag run, so the question is asked of
        // that run; with them left alone they are a run of their own still carrying the server's
        // colour, and the question is asked of that.
        String bracketRunText = brackets ? tagRun : "[";
        Integer bracketRun = colourOf(fixed, bracketRunText);
        String body = ": hello [" + BODY_NUMBER + "] world";
        Style bodyStyle = styleOf(fixed, body);
        boolean bodyIntact = bodyStyle != null && bodyStyle.getColor() == null;
        boolean bracketsHeld = brackets
                ? bracketRun != null && bracketRun == expected
                : bracketRun != null && bracketRun == TAG_SOURCE_RGB;
        checks.add(new Check("brackets and an out-of-range number behaved",
                bracketsHeld && bodyIntact,
                "recolourBrackets=" + brackets + ", run " + quote(bracketRunText) + " is "
                        + hex(bracketRun) + " and should be "
                        + hex(brackets ? expected : TAG_SOURCE_RGB)
                        + "; the body is still one uncoloured run: "
                        + (bodyIntact ? "yes" : "no")
                        + " (level " + BODY_NUMBER + " is outside " + locator.minLevel() + ".."
                        + locator.maxLevel() + ", so the locator must not match it)"));

        // The live path cannot be pinned to a colour, so it is checked for movement instead.
        Integer digitsLive = colourOf(live, tagRun);
        checks.add(new Check("the live chat callback also recoloured the tag",
                digitsLive != null && digitsLive != TAG_SOURCE_RGB,
                "ChatRouter.modifyGameMessage left run " + quote(tagRun) + " at " + hex(digitsLive)
                        + "; the server sent " + hex(TAG_SOURCE_RGB)
                        + " (chroma is on, so the exact value moves with the clock; the SkyBlock "
                        + "condition is forced for this call, see liveRecolour)"));
    }

    /**
     * Runs the live chat callback with its server precondition satisfied.
     *
     * <p>{@code levels.onlyOnSkyBlock} defaults to on, and a dev client is not on SkyBlock, so the
     * callback would correctly decline to recolour anything and the check below would read as a
     * regression in the rewriter rather than as the gate doing its job. The probe therefore forces
     * the one condition it needs and puts it back afterwards.
     *
     * <p>This is a diagnostic that only runs under {@code -Dskyprism.selftest=true}, and forcing a
     * precondition is exactly what such a diagnostic is for -- but it must not <em>leave</em> the
     * gate forced, because the same flag feeds the Diana feature. The restore is in a finally, and
     * the next sidebar poll would correct it within two seconds regardless.
     */
    private static Component liveRecolour(Component before, SkyPrismConfig config) {
        if (!config.levels.onlyOnSkyBlock) {
            return ChatRouter.modifyGameMessage(before, false);
        }
        DianaGate gate = DianaController.get().gate();
        boolean wasInSkyBlock = gate.inSkyBlock();
        gate.setInSkyBlock(true);
        try {
            return ChatRouter.modifyGameMessage(before, false);
        } finally {
            gate.setInSkyBlock(wasInSkyBlock);
        }
    }

    /**
     * A chat line shaped the way Hypixel sends one.
     *
     * <p>The root carries bold so the probe also proves that an inherited style reaches the runs:
     * a child style says "inherit" with a null, and a rewriter that resolved the merge in the wrong
     * order would silently drop the parent formatting along with its hover event.</p>
     */
    private static Component fixture() {
        MutableComponent tree = Component.literal("").setStyle(Style.EMPTY.withBold(true));
        tree.append(Component.literal("[" + LEVEL + "] ")
                .setStyle(Style.EMPTY.withColor(TAG_SOURCE_RGB)));
        tree.append(Component.literal("[MVP").setStyle(Style.EMPTY.withColor(RANK_RGB)));
        tree.append(Component.literal("+").setStyle(Style.EMPTY.withColor(RANK_PLUS_RGB)));
        tree.append(Component.literal("] ").setStyle(Style.EMPTY.withColor(RANK_RGB)));
        tree.append(Component.literal("Notch").setStyle(Style.EMPTY
                .withColor(RANK_RGB)
                .withClickEvent(new ClickEvent.RunCommand("/profile Notch"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to view profile")))
                .withInsertion("Notch")
                .withItalic(true)));
        tree.append(Component.literal(" " + EMBLEM).setStyle(Style.EMPTY.withColor(EMBLEM_RGB)));
        tree.append(Component.literal(": hello [" + BODY_NUMBER + "] world"));
        return tree;
    }

    // ------------------------------------------------------------------ reporting

    private static void header(StringBuilder out, SkyPrismConfig config, LevelPalette palette,
                               LevelTagLocator locator, boolean brackets, int expected) {
        out.append("SkyPrism level-recolour probe\n")
                .append("=============================\n\n")
                .append("No server, no Hypixel: a synthetic Component tree is pushed through the "
                        + "same\ntwo entry points a real chat line takes.\n\n")
                .append("palette          : ").append(palette).append('\n')
                .append("locator          : ").append(locator).append('\n')
                .append("mode             : ").append(palette.mode()).append('\n')
                .append("chroma           : ").append(palette.chromaEnabled()
                        ? "on from level " + palette.chromaMinLevel() + ", "
                                + config.levels.chromaCyclesPerSecond + " cycles/s at "
                                + config.levels.chromaUpdateHz + " Hz"
                        : "off").append('\n')
                .append("recolourBrackets : ").append(brackets).append('\n')
                .append("applyToChat      : ").append(config.levels.applyToChat).append('\n')
                .append("expected colour  : level ").append(LEVEL).append(" at t=")
                .append(FIXED_MILLIS).append(" is ").append(hex(expected)).append("\n\n");
    }

    /**
     * Writes a component three ways: its plain text, its legacy form, and one line per styled run.
     *
     * <p>The legacy string is what a human recognises from a Hypixel log, and it is what the brief
     * asked for. It is not enough on its own: legacy codes cannot express a 24-bit colour, so a
     * gradient tag and the nearest {@code &sect;d} look identical there. The per-run dump is the
     * one that can actually be checked, so both are printed.</p>
     */
    private static void dump(StringBuilder out, String label, Component component) {
        out.append("== ").append(label).append(" ==\n");
        out.append("  plain  : ").append(quote(component.getString())).append('\n');
        out.append("  legacy : ").append(quote(visible(LegacyText.toLegacy(component)))).append('\n');
        out.append("  runs   :\n");
        for (StyledRun<Style> run : ComponentRewriter.toRuns(component)) {
            Style style = run.style();
            TextColor colour = style == null ? null : style.getColor();
            out.append("    ").append(pad(quote(run.text()), 34))
                    .append(" colour=").append(pad(colour == null ? "-" : hex(colour.getValue()), 10))
                    .append(" bold=").append(style != null && style.isBold())
                    .append(" italic=").append(style != null && style.isItalic())
                    .append(" hover=").append(style == null || style.getHoverEvent() == null
                            ? "-" : "yes")
                    .append(" click=").append(style == null || style.getClickEvent() == null
                            ? "-" : "yes")
                    .append(" insertion=").append(style == null || style.getInsertion() == null
                            ? "-" : quote(style.getInsertion()))
                    .append('\n');
        }
        out.append('\n');
    }

    /** Replaces the section sign with a literal {@code &} so the report survives any editor. */
    private static String visible(String legacy) {
        return legacy == null ? "null" : legacy.replace(LegacyText.SECTION, '&');
    }

    private static String quote(String text) {
        return text == null ? "null" : "\"" + text + "\"";
    }

    private static String hex(Integer rgb) {
        return rgb == null ? "none" : String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /** The colour of the first run whose text is exactly {@code text}, or null. */
    private static Integer colourOf(Component component, String text) {
        Style style = styleOf(component, text);
        if (style == null || style.getColor() == null) {
            return null;
        }
        return style.getColor().getValue();
    }

    /** The resolved style of the first run whose text is exactly {@code text}, or null. */
    private static Style styleOf(Component component, String text) {
        for (StyledRun<Style> run : ComponentRewriter.toRuns(component)) {
            if (run.text().equals(text)) {
                return run.style();
            }
        }
        return null;
    }

    /** One claim and whether it held. */
    private record Check(String what, boolean passed, String detail) {
    }
}
