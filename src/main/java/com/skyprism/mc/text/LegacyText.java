package com.skyprism.mc.text;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Converts between Minecraft's styled {@link Component} tree and the flat legacy
 * section-sign string that {@code com.skyprism.core.diana} is written against.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>{@code DianaPatterns} and {@code LootParser} match against strings such as
 * <code>&#167;6&#167;lRARE&nbsp;DROP!&nbsp;&#167;r&#167;eYou&nbsp;dug&nbsp;out&nbsp;a&nbsp;&#167;r&#167;9Griffin&nbsp;Feather&#167;r&#167;e!</code>
 * -- exactly the shape SkyHanni's and Skytils' patterns were copied from, because those read
 * Minecraft 1.8's {@code IChatComponent.getFormattedText()}. Modern Minecraft hands a mod
 * nothing like that: {@link Component#getString()} returns the <em>plain</em> text with every
 * colour thrown away, and the formatting lives in a {@link Style} on each node of a tree.
 * Feeding {@code getString()} to those anchored patterns would fail every single line, so the
 * raw string has to be reconstructed, and this is the one place that knows how.</p>
 *
 * <h2>Where this class lives, and why</h2>
 *
 * <p>Two independent copies of this algorithm used to exist, one in {@code mc.chat} and one in
 * {@code mc.diana}, and they had quietly diverged -- see the notes below on format-flag order
 * and on doubled resets. They are now this single class. It sits in {@code mc.text} rather
 * than in either of the two packages that call it because {@code mc.chat} already depends on
 * {@code mc.diana} ({@code ChatRouter} drives {@code DianaController}); homing the shared
 * helper in either one would have made that dependency circular. {@code mc.text} is a leaf --
 * it imports the core and Minecraft and nothing else of ours -- so both callers can reach it
 * without any new coupling.</p>
 *
 * <h2>The reconstruction rule, and why it is this one</h2>
 *
 * <p>The rule is: <em>walk the component in visual order; for every non-empty run emit a
 * reset, then the run's colour, then its format flags, then the text -- except that the very
 * first run emits no leading reset -- then collapse any run of resets down to one.</em></p>
 *
 * <p>That is not an arbitrary choice, and it is not the obvious one. 1.8's
 * {@code getFormattedText()} appended a reset <em>after</em> each part instead. The two agree
 * everywhere in the middle of a line and differ only at the ends: the trailing form leaves a
 * dangling <code>&#167;r</code>, and the core matches with
 * {@link java.util.regex.Matcher#matches()} rather than {@code find()}, which is anchored at
 * both ends. Three of the four Diana patterns end on a literal <code>&#167;r&#167;e!</code>,
 * so a dangling reset makes them fail outright. Emitting the reset in front instead
 * reproduces all four documented example lines in {@code DianaPatterns} and
 * {@code LootParser} character for character:</p>
 *
 * <pre>
 *   run("RARE DROP! ", gold+bold)  first      -&gt; "&#167;6&#167;lRARE DROP! "
 *   run("Dwarf Turtle Shelmet ", blue)        -&gt; "&#167;r&#167;9Dwarf Turtle Shelmet "
 *   run("(+", aqua)                           -&gt; "&#167;r&#167;b(+"
 * </pre>
 *
 * <p>Note the third line: the reset is emitted even though the style did not change from the
 * run before it. An "only emit codes when the style differs" optimisation looks obviously
 * correct and would break the magic-find tail of {@code LootParser.BANNER_DROP}, which really
 * does contain <code>&#167;r&#167;b</code> four times in a row for four identically-styled
 * runs. Do not add it. It also does not interact with the reset collapsing below: a
 * <code>&#167;r&#167;b&#167;r&#167;b</code> run contains no two <em>adjacent</em> resets.</p>
 *
 * <h2>Why runs of resets are collapsed</h2>
 *
 * <p>Hypixel has sent SkyBlock chat in more than one shape over the years, and the two that
 * matter here are: <em>one</em> text node with the section signs already inside its literal
 * text, and <em>several</em> nodes each of which opens with its own literal
 * <code>&#167;r</code>. In the second shape the reset this class injects at a run boundary
 * lands right next to the server's own, giving <code>&#167;r&#167;r</code> where every pattern
 * wants a single reset -- and because the patterns are anchored, that is a total miss rather
 * than a cosmetic wobble. Collapsing makes that shape, the single-node shape and the pure
 * style-carrying shape (which contains no literal codes at all) all converge on the same
 * string, which is the entire point of this class.</p>
 *
 * <p>The price is small and worth naming: a line that genuinely arrived with
 * <code>&#167;r&#167;r</code> inside one node is normalised to a single reset, so the output
 * is not byte-for-byte identical to the wire for that one input shape. Nothing depends on it
 * -- a doubled reset renders exactly as a single one, and no core pattern asks for two --
 * whereas the anchored-match failure it prevents is silent and total. The single-node shape
 * without a doubled reset, which is what Hypixel has used for most of SkyBlock's life, still
 * survives byte for byte.</p>
 *
 * <h2>Format-flag order</h2>
 *
 * <p>Colour is written first, then the flags in the order <em>bold, italic, underline,
 * obfuscated, strikethrough</em>. That is not alphabetical and it is not arbitrary: it is the
 * exact order of 1.8's {@code ChatStyle.getFormattingCode()}, the method whose output these
 * patterns were written against. The two former copies disagreed here -- one emitted the
 * flags in code order {@code k l m n o} -- and this is the one that reproduces the source
 * material. The difference is only observable on a run carrying two or more flags at once,
 * which Hypixel's Diana lines never do, but "never does today" is not a reason to keep the
 * wrong one. Colour precedes the flags because a legacy colour code clears active flags on
 * the receiving side, so the other order would erase the very flags just emitted.</p>
 *
 * <h2>Cross-version colour lookup</h2>
 *
 * <p>Going from a {@link TextColor} back to its legacy letter is the one part of this that is
 * version-sensitive. Minecraft 26.2 gutted {@link ChatFormatting}: {@code getChar()},
 * {@code getColor()}, {@code isColor()}, {@code getId()} and {@code getByName} are all gone,
 * and only 26.1.2 still has them. What survives on <em>both</em> nodes is
 * {@link ChatFormatting#getByCode(char)} and {@link TextColor#fromLegacyFormat}, so the table
 * below is built by walking the sixteen legacy colour letters forwards and inverting the
 * result. That needs no Stonecutter conditional, which is the whole point.</p>
 *
 * <p>Format letters ({@code k} obfuscated, {@code l} bold, {@code m} strikethrough,
 * {@code n} underline, {@code o} italic, {@code r} reset) are written as literals rather than
 * looked up. They are fixed by the wire protocol, not by any particular Minecraft release,
 * and a lookup would only add a way for them to be wrong.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>{@link #toLegacy} allocates a {@link StringBuilder} and walks the tree once, so it is
 * emphatically not free and callers must gate on a cheap plain-text test before reaching for
 * it. Both callers do: {@code ChatRouter} and {@code DianaController} each reject on
 * {@link Component#getString()} first, and only a line carrying a Diana marker word is ever
 * reconstructed.</p>
 */
public final class LegacyText {

    /**
     * The section sign. Written as an escape, and cross-checked against
     * {@link ChatFormatting#PREFIX_CODE} at class-init, so that a change of file encoding can
     * never silently alter what this class emits -- the same discipline
     * {@code com.skyprism.core.util.TextClean} follows.
     */
    public static final char SECTION = '§';

    /** The sixteen legacy colour letters, in wire order. */
    private static final String COLOR_CODES = "0123456789abcdef";

    /**
     * Packed {@code 0xRRGGBB} of each legacy colour, parallel to {@link #COLOR_CODES}, or
     * {@code -1} where the running Minecraft has no colour for that letter. Filled from
     * Minecraft's own tables rather than hard-coded, because Mojang has quietly retuned these
     * values before.
     *
     * <p>{@code -1} is safe as the "nothing maps here" sentinel because every lookup masks to
     * 24 bits before comparing, so a real colour can never present as {@code -1}.</p>
     */
    private static final int[] COLOR_RGB = new int[COLOR_CODES.length()];

    static {
        if (SECTION != ChatFormatting.PREFIX_CODE) {
            throw new AssertionError("Minecraft's section sign is no longer U+00A7");
        }
        for (int i = 0; i < COLOR_CODES.length(); i++) {
            ChatFormatting format = ChatFormatting.getByCode(COLOR_CODES.charAt(i));
            TextColor color = format == null ? null : TextColor.fromLegacyFormat(format);
            COLOR_RGB[i] = color == null ? -1 : color.getValue() & 0xFFFFFF;
        }
    }

    private LegacyText() {
    }

    /**
     * Flattens a component into the legacy section-sign string the Diana patterns expect.
     *
     * <p>A component that already carries literal section signs in its text -- which is how
     * Hypixel sends a good deal of its chat -- passes through essentially unchanged, because
     * such runs have an empty {@link Style} and so contribute no codes of their own beyond the
     * injected reset, which the collapsing pass then folds into the server's own. Components
     * that carry real styles are re-serialised as described in the class javadoc. Both shapes
     * reach the patterns looking the same, which is the reason this is a reconstruction rather
     * than a simple {@code getString()}.
     *
     * <p>Empty runs are skipped entirely. Emitting their codes would inject stray
     * <code>&#167;r</code> sequences into the middle of a line, and an empty run cannot carry
     * any visible formatting anyway.
     *
     * <p><b>It does not throw.</b> A component assembled by a buggy mod or a hostile sender
     * must not be able to take down the chat handler, so a visitor that blows up yields
     * whatever was accumulated so far. That partial line simply will not match a pattern,
     * which is the correct outcome for a line we could not read.
     *
     * @param component the component to flatten, may be null
     * @return the reconstructed raw line, never null; empty for a null or empty component
     */
    public static String toLegacy(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(64);
        try {
            component.visit((style, content) -> {
                if (!content.isEmpty()) {
                    if (!out.isEmpty()) {
                        out.append(SECTION).append('r');
                    }
                    appendCodes(out, style);
                    out.append(content);
                }
                return Optional.empty();
            }, Style.EMPTY);
        } catch (RuntimeException malformed) {
            return collapseResets(out);
        }
        return collapseResets(out);
    }

    /**
     * Parses a legacy section-sign string back into a component.
     *
     * <p>This is the inverse used by {@code /skyprism replay} and {@code /skyprism simulate}: a
     * fixture line captured from Hypixel is a raw string, and to push it through the same
     * recolouring path a real message takes it has to become a {@link Component} again.
     *
     * <p>Legacy semantics are applied through {@link Style#applyLegacyFormat}, which is present
     * and identical on both Minecraft nodes and already encodes the two rules that are easy to
     * get wrong: a colour code clears any active format flags, and <code>&#167;r</code> resets
     * everything. A trailing lone section sign, or one followed by a character that is not a
     * format code, is kept as literal text rather than swallowed -- dropping it would make this
     * function lossy for exactly the malformed input a replay fixture is most likely to
     * contain.
     *
     * @param legacy the raw line, may be null
     * @return a component whose {@link #toLegacy} round-trips to an equivalent line
     */
    public static MutableComponent fromLegacy(String legacy) {
        MutableComponent root = Component.empty();
        if (legacy == null || legacy.isEmpty()) {
            return root;
        }
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder(legacy.length());
        for (int i = 0; i < legacy.length(); i++) {
            char ch = legacy.charAt(i);
            ChatFormatting format = null;
            if (ch == SECTION && i + 1 < legacy.length()) {
                format = ChatFormatting.getByCode(legacy.charAt(i + 1));
            }
            if (format == null) {
                run.append(ch);
                continue;
            }
            if (!run.isEmpty()) {
                root.append(Component.literal(run.toString()).setStyle(style));
                run.setLength(0);
            }
            style = style.applyLegacyFormat(format);
            i++;
        }
        if (!run.isEmpty()) {
            root.append(Component.literal(run.toString()).setStyle(style));
        }
        return root;
    }

    /**
     * Emits one run's colour and format codes, in 1.8's {@code getFormattingCode} order.
     *
     * <p>See the class javadoc for why that order rather than {@code k l m n o}. A null or
     * empty style leaves early: it can contribute nothing, and skipping it keeps the
     * literal-codes-in-the-text shape -- the common Hypixel one -- off the branchy path
     * entirely.
     */
    private static void appendCodes(StringBuilder out, Style style) {
        if (style == null || style.isEmpty()) {
            return;
        }
        TextColor color = style.getColor();
        if (color != null) {
            char letter = legacyLetterFor(color.getValue() & 0xFFFFFF);
            if (letter != 0) {
                out.append(SECTION).append(letter);
            }
        }
        if (style.isBold()) {
            out.append(SECTION).append('l');
        }
        if (style.isItalic()) {
            out.append(SECTION).append('o');
        }
        if (style.isUnderlined()) {
            out.append(SECTION).append('n');
        }
        if (style.isObfuscated()) {
            out.append(SECTION).append('k');
        }
        if (style.isStrikethrough()) {
            out.append(SECTION).append('m');
        }
    }

    /**
     * The legacy letter for a packed colour, or {@code 0} when the colour is not one of the
     * sixteen.
     *
     * <p>An arbitrary RGB colour deliberately emits <em>nothing</em> rather than being snapped
     * to the nearest legacy tier. The patterns this feeds are anchored and spell out the exact
     * codes they expect; a nearest-match would sometimes produce a plausible but wrong letter
     * and turn a clean miss into a wrong match, which is far worse than a missed reel. Hypixel
     * writes its Diana lines in legacy colours, so the sixteen are all that is ever needed
     * here.
     *
     * <p>A sixteen-entry linear scan beats a {@code HashMap} lookup at this size and allocates
     * nothing, which matters because this runs per styled run per chat line.
     */
    private static char legacyLetterFor(int rgb) {
        for (int i = 0; i < COLOR_RGB.length; i++) {
            if (COLOR_RGB[i] == rgb) {
                return COLOR_CODES.charAt(i);
            }
        }
        return 0;
    }

    /**
     * Rewrites every run of consecutive resets down to a single one; see the class javadoc for
     * why that is worth doing.
     *
     * <p>Written as a hand-rolled scan rather than {@code String.replaceAll} because this runs
     * on every Diana-relevant chat line and a regex here would allocate a matcher per line for
     * a job a single pass does. The common case -- no doubled reset anywhere -- finds nothing
     * in the first scan and returns the builder's own string with no second buffer at all.
     */
    private static String collapseResets(StringBuilder built) {
        int length = built.length();
        int firstDouble = -1;
        for (int i = 0; i + 3 < length; i++) {
            if (built.charAt(i) == SECTION && built.charAt(i + 1) == 'r'
                    && built.charAt(i + 2) == SECTION && built.charAt(i + 3) == 'r') {
                firstDouble = i;
                break;
            }
        }
        if (firstDouble < 0) {
            return built.toString();
        }
        StringBuilder out = new StringBuilder(length);
        out.append(built, 0, firstDouble);
        int i = firstDouble;
        while (i < length) {
            if (i + 1 < length && built.charAt(i) == SECTION && built.charAt(i + 1) == 'r') {
                out.append(SECTION).append('r');
                i += 2;
                while (i + 1 < length && built.charAt(i) == SECTION && built.charAt(i + 1) == 'r') {
                    i += 2;
                }
            } else {
                out.append(built.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
}
