package com.skyprism.mc.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.skyprism.core.diana.DianaPatterns;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Guards the one part of the chat pipeline that can break without saying anything.
 *
 * <h2>The failure this exists to make impossible</h2>
 *
 * <p>{@link DianaLineFilter} rejects a chat line unless it contains one of four literal
 * substrings, and it does so <em>before</em> the line is ever shown to
 * {@link DianaPatterns}. The optimisation is real and worth keeping -- it is the difference
 * between four anchored regexes plus a component walk on every system message Hypixel sends
 * and four {@code indexOf} calls -- but it inverts the usual safety of a filter. A pattern
 * added to the core whose text shares none of those substrings is not slow, or wrong, or
 * loud. It simply never runs. No exception is thrown, nothing is logged, no other test
 * fails, and the only symptom is a feature that quietly does not fire on a server nobody
 * can run in CI.</p>
 *
 * <p>So this test asserts the contract from the core's side rather than the filter's:
 * <b>every pattern {@code DianaPatterns} exposes must have a real matching line, and every
 * one of those lines must survive the filter.</b></p>
 *
 * <h2>Why reflection rather than a written-out list of patterns</h2>
 *
 * <p>{@link #everyPatternHasSampleLines()} discovers the patterns by walking
 * {@code DianaPatterns}' public static {@link Pattern} fields. A hand-maintained list here
 * would have exactly the same defect as the marker list it is policing: adding
 * {@code DianaPatterns.SOMETHING_NEW} and forgetting to mention it would leave the suite
 * green. Discovering them instead means a new pattern fails this test on the day it is
 * added, with a message naming the field and saying what to do, and the author has to
 * either add a marker to {@link DianaLineFilter#MARKERS} or record a sample line proving
 * an existing marker already covers it.</p>
 *
 * <h2>Why the sample lines are checked against their own pattern first</h2>
 *
 * <p>A sample line that does not actually match its pattern would still sail through the
 * filter and turn this whole file into a test of nothing -- the easiest way to "fix" a
 * failure here would be to invent a line containing "dug out" and move on. So
 * {@link #sampleLinesMatchTheirPattern()} asserts every sample really is a line the core
 * accepts. Only then does {@link #everySampleSurvivesTheFilter()} put the same strings
 * through the same predicate the chat hook calls.</p>
 *
 * <p>The samples are written in the raw section-coded form, because that is what the filter
 * sees on the {@code /skyprism replay} path and what the patterns match against. The plain
 * form is checked too, since the live {@code ALLOW_GAME} path filters on
 * {@code Component.getString()}: a marker that happened to straddle a colour code would
 * pass one and fail the other.</p>
 */
@DisplayName("DianaLineFilter covers every core pattern")
final class DianaMarkerContractMcTest {

    /**
     * A known-matching line for every pattern {@code DianaPatterns} exposes, keyed by field
     * name.
     *
     * <p>More than one line per pattern where the pattern has alternations that reach
     * different markers -- {@code BURROW_DUG} is the case that matters, since its
     * chain-finished branch is the only Diana line in the game that never says "dug out",
     * and it is the reason {@code "burrow chain"} is in the marker list at all.
     */
    private static final Map<String, List<String>> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("SPAWN", List.of(
                "§c§lOh! §r§eYou dug out a §r§2Gaia Construct§r§e!",
                "§c§lWoah! §r§eYou dug out §r§cMinos Inquisitor§r§e!"));
        SAMPLES.put("BURROW_DUG", List.of(
                "§eYou dug out a Griffin Burrow! §r§7(3/4)",
                // The branch that carries no "dug out" at all.
                "§eYou finished the Griffin burrow chain! §r§7(4/4)"));
        SAMPLES.put("TREASURE_DUG", List.of(
                "§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!",
                "§6§lWow! §r§eYou dug out §r§62,500 coins§r§e!"));
        SAMPLES.put("INQUISITOR_SHARE", List.of(
                "§9Party §8> §bSteve§f: §rA MINOS INQUISITOR has spawned near "
                        + "[Howling Cave] at Coords -12 75 130",
                "§bSteve§f: §rA MINOS INQUISITOR has spawned near "
                        + "[Howling Cave] at Coords -12 75 130"));
    }

    /** Every public static Pattern on {@code DianaPatterns}, discovered rather than listed. */
    private static Map<String, Pattern> corePatterns() {
        Map<String, Pattern> found = new LinkedHashMap<>();
        for (Field field : DianaPatterns.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Pattern.class) {
                try {
                    found.put(field.getName(), (Pattern) field.get(null));
                } catch (IllegalAccessException unreachable) {
                    throw new AssertionError("public static field was not readable: " + field, unreachable);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("every pattern the core exposes has at least one sample line here")
    void everyPatternHasSampleLines() {
        List<String> unknown = new ArrayList<>();
        for (String name : corePatterns().keySet()) {
            List<String> samples = SAMPLES.get(name);
            if (samples == null || samples.isEmpty()) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            fail("DianaPatterns exposes " + unknown + ", which this test has never seen.\n"
                    + "That is not a test-maintenance chore, it is the check working: a new "
                    + "pattern only reaches the Diana pipeline if its text contains one of "
                    + DianaLineFilter.MARKERS + " (see DianaLineFilter), and nothing at "
                    + "runtime will tell you when it does not.\n"
                    + "Add a real matching line for each field above to SAMPLES. If none of "
                    + "the existing markers appears in it, add the new marker to "
                    + "DianaLineFilter.MARKERS as well.");
        }

        // The reverse direction: a sample for a pattern that no longer exists is dead weight
        // and, worse, hides the fact that its marker may now have no reason to be in the list.
        List<String> stale = new ArrayList<>(SAMPLES.keySet());
        stale.removeAll(corePatterns().keySet());
        assertTrue(stale.isEmpty(),
                () -> "SAMPLES still names " + stale + ", which DianaPatterns no longer exposes. "
                        + "Delete those entries, and check whether any marker in "
                        + DianaLineFilter.MARKERS + " is now unused.");
    }

    @TestFactory
    @DisplayName("each sample line really is matched by the pattern it claims")
    List<DynamicTest> sampleLinesMatchTheirPattern() {
        Map<String, Pattern> patterns = corePatterns();
        List<DynamicTest> cases = new ArrayList<>();
        SAMPLES.forEach((name, samples) -> {
            Pattern pattern = patterns.get(name);
            if (pattern == null) {
                return; // reported by everyPatternHasSampleLines
            }
            for (String line : samples) {
                cases.add(DynamicTest.dynamicTest(name + " <- " + readable(line), () ->
                        assertTrue(pattern.matcher(line).matches(),
                                () -> "This sample no longer matches " + name + ", so it proves "
                                        + "nothing about the filter. Either Hypixel's line "
                                        + "changed and the sample needs updating, or the pattern "
                                        + "regressed.\n  pattern: " + pattern.pattern()
                                        + "\n  line:    " + readable(line))));
            }
        });
        return cases;
    }

    @TestFactory
    @DisplayName("the chat hook's fast reject lets every one of them through")
    List<DynamicTest> everySampleSurvivesTheFilter() {
        List<DynamicTest> cases = new ArrayList<>();
        SAMPLES.forEach((name, samples) -> {
            for (String line : samples) {
                cases.add(DynamicTest.dynamicTest(name + " <- " + readable(line), () -> {
                    assertTrue(DianaLineFilter.mightMatterToDiana(line), () -> rejected(name, line));
                    // The live ALLOW_GAME path filters on the plain text, not the raw line.
                    String plain = stripCodes(line);
                    assertTrue(DianaLineFilter.mightMatterToDiana(plain), () -> rejected(name, plain));
                }));
            }
        });
        return cases;
    }

    private static String rejected(String patternName, String line) {
        return "DianaLineFilter rejected a line that " + patternName + " matches, so this "
                + "pattern can never fire in game.\n"
                + "  line:    " + readable(line) + "\n"
                + "  markers: " + DianaLineFilter.MARKERS + "\n"
                + "Add a substring of this line to DianaLineFilter.MARKERS. It must appear in "
                + "the line without a formatting code through the middle of it, because the "
                + "filter runs against both the raw and the plain form.";
    }

    /**
     * The subset of {@code TextClean.stripFormatting} these fixtures need. Deliberately
     * hand-rolled rather than delegating: this test exists to catch the filter drifting from
     * the patterns, and borrowing the production stripper would let a bug in it disguise a
     * bug here.
     */
    private static String stripCodes(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Section signs render as mojibake in most CI logs; ampersands do not. */
    private static String readable(String line) {
        return line.replace('§', '&');
    }
}
