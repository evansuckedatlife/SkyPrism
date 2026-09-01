package com.skyprism.mc.selftest;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.core.level.Oklab;
import com.skyprism.core.level.PalettePresets;
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
 *
 * <h2>Two levels, on purpose, and which one is load bearing</h2>
 *
 * <p>The same line is built twice, once at level 451 and once at level 521, because the shipped
 * table is two different animals either side of 480 and only one of them can prove the mod is
 * running.</p>
 *
 * <ul>
 *   <li><b>451</b> sits in the half of the table sampled off Hypixel's own hexlist, so a correct
 *       recolour there lands <em>near</em> what the server sent. That makes it a good test of
 *       faithfulness -- the familiar colours are still familiar -- and a bad test of activity:
 *       "the tag changed" is close to unfalsifiable when the before and after are cousins.</li>
 *   <li><b>521</b> sits above Hypixel's last tier, where an unmodded client shows {@code #AA0000}
 *       and goes on showing it at 600 and at 900. The shipped table spends its own colours here.
 *       So this is the line the probe leans on, and it is checked with a perceptual distance
 *       rather than {@code !=}: the regression worth catching is not "the colour is byte-identical
 *       to the server's", it is "the mod stopped doing anything a player could see", and only a
 *       measured distance can tell those apart.</li>
 * </ul>
 */
final class RecolourProbe {

    private RecolourProbe() {
    }

    /**
     * The level in the first fixture tag: below 480, where the shipped table is sampled off
     * Hypixel's own hexlist.
     *
     * <p>This one proves the vanilla-derived half of the table is <em>faithful</em> - that a
     * player at 451 still reads as the colour the rest of the lobby expects. It is deliberately
     * not the check that proves the mod is doing anything, and it cannot be: below 480 the
     * shipped colours are derived from the very colours Hypixel sends, so "recoloured" and
     * "left alone" land close together there by design. {@link #HIGH_LEVEL} carries that
     * burden instead.</p>
     */
    private static final int LEVEL = 451;

    /**
     * The level in the second fixture tag: above 480, where SkyPrism's own hues live.
     *
     * <p>480 is Hypixel's last tier. Every level above it - 481, 600, 900 - is the same dark
     * red on an unmodded client, forever, so this is the range where the mod has something to
     * say that the server does not, and the only range where "the tag changed colour" is a
     * claim with real content behind it.</p>
     */
    private static final int HIGH_LEVEL = 521;

    /**
     * The instant the deterministic run pretends it is.
     *
     * <p>Any constant would do. This one is fixed so that two self-test runs on the same
     * configuration produce byte-identical expected colours, which makes the report diffable.</p>
     */
    private static final long FIXED_MILLIS = 1_700_000_000_000L;

    /**
     * The colour the first fixture claims Hypixel sent for level 451.
     *
     * <p><b>This is not what Hypixel actually sends there</b>, and the difference is load
     * bearing, so it is written down rather than quietly corrected. Level 451 falls in
     * Hypixel's 440 tier, which is {@code #FF5555}; this fixture says {@code #AA00AA}, the
     * 360 tier. That accident is the only reason the six checks on this line still have
     * teeth - they compare the recoloured tag against a source colour far enough away to
     * notice. Set this to the truthful {@code #FF5555} and every one of them would still
     * pass while measuring almost nothing, because the shipped table below 480 is sampled
     * off that same hexlist. Anyone tempted to make the fixture more honest should move the
     * teeth to {@link #HIGH_TAG_SOURCE_RGB} first, where they do not depend on a mistake.</p>
     */
    private static final int TAG_SOURCE_RGB = 0xAA00AA;

    /**
     * The colour Hypixel really does send for level 521: its last tier, dark red.
     *
     * <p>Unlike {@link #TAG_SOURCE_RGB} this is verbatim - {@code PalettePresets}' top vanilla
     * tier, the colour an unmodded client puts on every level from 480 up. A recolour away
     * from it is the mod visibly working.</p>
     */
    private static final int HIGH_TAG_SOURCE_RGB = 0xAA0000;

    /**
     * How far apart two colours must sit in Oklab before a player can tell them apart.
     *
     * <p>Roughly the just-noticeable difference, and the same order the palette's own design
     * notes use: adjacent stops on the shipped ramp are about 0.045 apart, described there as
     * "twice over the threshold where two colours stop being tellable apart". Asserting a
     * distance rather than {@code !=} is the whole point of the high-level checks: a mod that
     * recoloured 521 to {@code #AA0001} would satisfy inequality and be indistinguishable from
     * a mod that had stopped running.</p>
     */
    private static final double VISIBLE_DISTANCE = 0.02;

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
     * Builds both fixtures, rewrites them, checks nine claims and writes the report.
     *
     * @param reportFile where to write the plain-text write-up
     * @return the outcome; this method does not throw
     */
    static Result run(Path reportFile) {
        List<Check> checks = new ArrayList<>(12);
        // Two fixtures, three dumps each, so the write-up is about twice what it was.
        StringBuilder out = new StringBuilder(8192);
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

        Component before = fixture(LEVEL, TAG_SOURCE_RGB);
        Component fixed = ComponentRewriter.recolourLevels(before, palette, locator, brackets,
                FIXED_MILLIS);
        Component live = liveRecolour(before, config);

        Component highBefore = fixture(HIGH_LEVEL, HIGH_TAG_SOURCE_RGB);
        Component highFixed = ComponentRewriter.recolourLevels(highBefore, palette, locator,
                brackets, FIXED_MILLIS);
        Component highLive = liveRecolour(highBefore, config);

        int expected = palette.colorFor(LEVEL, FIXED_MILLIS);
        int highExpected = palette.colorFor(HIGH_LEVEL, FIXED_MILLIS);

        // Which run the recoloured tag ends up in, which is not a constant: the rewriter emits
        // one run per contiguous span of one style, so tinting the brackets merges "[", the
        // digits and "]" into a single "[451]" run, while leaving them alone keeps the digits as
        // a run of their own. Both are correct; a probe that only knew the second shape reported
        // three failures the moment the shipped default flipped, which is a probe reporting on
        // itself rather than on the mod.
        String tagRun = tagRunText(LEVEL, brackets);
        String highTagRun = tagRunText(HIGH_LEVEL, brackets);

        header(out, config, palette, locator, brackets, expected, highExpected);
        dump(out, "BEFORE (level " + LEVEL + ")", before);
        dump(out, "AFTER (level " + LEVEL + ", deterministic, ComponentRewriter.recolourLevels at t="
                + FIXED_MILLIS + ")", fixed);
        dump(out, "AFTER (level " + LEVEL + ", live, ChatRouter.modifyGameMessage)", live);
        dump(out, "BEFORE (level " + HIGH_LEVEL + ")", highBefore);
        dump(out, "AFTER (level " + HIGH_LEVEL + ", deterministic, ComponentRewriter.recolourLevels "
                + "at t=" + FIXED_MILLIS + ")", highFixed);
        dump(out, "AFTER (level " + HIGH_LEVEL + ", live, ChatRouter.modifyGameMessage)", highLive);

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

        // ---------------------------------------------------------- above Hypixel's last tier
        //
        // Everything above this line can be satisfied by a table that merely echoes Hypixel back,
        // because below 480 the shipped colours are drawn from Hypixel's own. These three cannot.
        // 480 is the last tier the server has; from there up it sends one flat dark red forever,
        // so this is the only stretch of the ladder where the mod's output is its own work and the
        // only place a "the recolour silently stopped" regression has nowhere to hide.

        // 7. Same exactness as check 3, one tier past where the server ran out of colours.
        Integer highDigits = colourOf(highFixed, highTagRun);
        checks.add(new Check("the level " + HIGH_LEVEL + " tag was recoloured to the palette colour",
                highDigits != null && highDigits == highExpected,
                "run " + quote(highTagRun) + " is " + hex(highDigits)
                        + ", palette.colorFor(" + HIGH_LEVEL + ", " + FIXED_MILLIS + ") is "
                        + hex(highExpected) + ", server sent " + hex(HIGH_TAG_SOURCE_RGB)));

        // 8. The one that gives this whole probe teeth. Equality against the palette proves the
        //    rewriter and the palette agree; it does not prove the palette is saying anything.
        //    A distance does. If the level module were gutted tomorrow and every tag came back
        //    wearing the server's own colour, checks 1, 3 and 7 could all still be arranged to
        //    pass -- this one could not.
        double highDrift = highDigits == null
                ? 0.0
                : distance(highDigits, HIGH_TAG_SOURCE_RGB);
        checks.add(new Check("the level " + HIGH_LEVEL + " recolour is VISIBLE, not just different",
                highDigits != null && highDrift >= VISIBLE_DISTANCE,
                "Oklab distance from the unmodded colour " + hex(HIGH_TAG_SOURCE_RGB) + " to "
                        + hex(highDigits) + " is " + fixed3(highDrift) + ", threshold "
                        + fixed3(VISIBLE_DISTANCE) + " (an unmodded client shows " + HIGH_LEVEL
                        + ", 600 and 900 all as " + hex(HIGH_TAG_SOURCE_RGB) + "; if this fails "
                        + "with mode=" + palette.mode() + " the palette is echoing the server "
                        + "back and no player would see the mod working up here)"));

        // 9. And the same claim through the callback Fabric actually calls.
        Integer highLiveDigits = colourOf(highLive, highTagRun);
        double highLiveDrift = highLiveDigits == null
                ? 0.0
                : distance(highLiveDigits, HIGH_TAG_SOURCE_RGB);
        checks.add(new Check("the live chat callback recolours " + HIGH_LEVEL + " visibly too",
                highLiveDigits != null && highLiveDrift >= VISIBLE_DISTANCE,
                "ChatRouter.modifyGameMessage left run " + quote(highTagRun) + " at "
                        + hex(highLiveDigits) + ", Oklab distance " + fixed3(highLiveDrift)
                        + " from " + hex(HIGH_TAG_SOURCE_RGB) + " (the exact value moves with the "
                        + "clock when chroma reaches this level, but the distance does not: the "
                        + "shimmer runs far lighter and more saturated than a dark red)"));

        // Not a check, on purpose. The faithfulness half of the story - that 451 still reads as
        // the colour the rest of the lobby expects - is a claim about which points the palette
        // module chose to sample off the vanilla hexlist, and pinning a threshold on it here
        // would make this probe fail every time that module legitimately retunes a bracket.
        // The number is printed instead, for a human reading the report to eyeball.
        out.append("== faithfulness below 480 (reported, not asserted) ==\n")
                .append("  Hypixel's own tier colour at level ").append(LEVEL).append(" is ")
                .append(hex(vanillaTierColour(LEVEL))).append("; the shipped palette says ")
                .append(hex(expected)).append(", Oklab distance ")
                .append(fixed3(distance(expected, vanillaTierColour(LEVEL)))).append(".\n")
                .append("  For scale, the same figure at level ").append(HIGH_LEVEL)
                .append(" - where the palette is deliberately NOT following Hypixel - is ")
                .append(fixed3(highDrift)).append(".\n")
                .append("  Below 480 the small number is the point; above it, the large one is.\n\n");
    }

    /**
     * The run text the recoloured tag ends up in.
     *
     * <p>Not a constant: the rewriter emits one run per contiguous span of one style, so tinting
     * the brackets merges them into the digits and leaving them alone does not.</p>
     */
    private static String tagRunText(int level, boolean recolourBrackets) {
        return recolourBrackets ? "[" + level + "]" : String.valueOf(level);
    }

    /**
     * Perceptual distance between two packed sRGB colours, in Oklab.
     *
     * <p>Plain Euclidean distance over {@code L, a, b}, which is what Oklab is built for: equal
     * numeric steps are meant to look like equal perceptual steps, so a single threshold means
     * the same thing at every hue. Comparing sRGB channels instead would call a dark red and a
     * dark blue further apart than a pale yellow and a white, which is backwards.</p>
     */
    private static double distance(int rgbA, int rgbB) {
        double[] p = Oklab.srgbToOklab(rgbA);
        double[] q = Oklab.srgbToOklab(rgbB);
        double dL = p[0] - q[0];
        double da = p[1] - q[1];
        double db = p[2] - q[2];
        return Math.sqrt(dL * dL + da * da + db * db);
    }

    /** What an unmodded client shows at this level, straight off Hypixel's tier table. */
    private static int vanillaTierColour(int level) {
        return PalettePresets.vanillaBrackets().colorAt(level);
    }

    private static String fixed3(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
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
     *
     * <p>Only the tag varies between the two fixtures. Everything to the right of it - the split
     * rank prefix, the interactive name, the emblem, the out-of-range number in the body - is
     * identical, because those runs are testing the rewriter's handling of neighbours and that
     * has nothing to do with which level is in the tag. Building both lines from one method also
     * means the second fixture cannot drift into being an easier case than the first.</p>
     *
     * @param level  the level in the tag
     * @param tagRgb the colour the server is pretending to have sent for it
     */
    private static Component fixture(int level, int tagRgb) {
        MutableComponent tree = Component.literal("").setStyle(Style.EMPTY.withBold(true));
        tree.append(Component.literal("[" + level + "] ")
                .setStyle(Style.EMPTY.withColor(tagRgb)));
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
                               LevelTagLocator locator, boolean brackets, int expected,
                               int highExpected) {
        out.append("SkyPrism level-recolour probe\n")
                .append("=============================\n\n")
                .append("No server, no Hypixel: two synthetic Component trees, one tagged below "
                        + "Hypixel's\nlast tier and one above it, are pushed through the same two "
                        + "entry points a real\nchat line takes.\n\n")
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
                .append(FIXED_MILLIS).append(" is ").append(hex(expected))
                .append(" (server sent ").append(hex(TAG_SOURCE_RGB)).append(")\n")
                .append("                 : level ").append(HIGH_LEVEL).append(" at t=")
                .append(FIXED_MILLIS).append(" is ").append(hex(highExpected))
                .append(" (server sent ").append(hex(HIGH_TAG_SOURCE_RGB))
                .append(", its last tier)\n\n");
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
