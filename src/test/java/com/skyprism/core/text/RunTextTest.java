package com.skyprism.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bare-JVM tests for {@link RunText}, the shared coordinate system of this package. */
class RunTextTest {

    private static StyledRun<String> run(String text, String style) {
        return new StyledRun<>(text, style);
    }

    @Test
    @DisplayName("flatten concatenates in order and ignores styles entirely")
    void flattenConcatenates() {
        List<StyledRun<String>> runs = List.of(run("[451] ", "gray"), run("Player", "white"));
        assertEquals("[451] Player", RunText.flatten(runs));
        assertEquals(12, RunText.length(runs));
    }

    @Test
    @DisplayName("an empty run list flattens to the empty string")
    void emptyListFlattensToEmpty() {
        assertEquals("", RunText.flatten(List.of()));
        assertEquals(0, RunText.length(List.<StyledRun<String>>of()));
    }

    @Test
    @DisplayName("empty runs contribute nothing but are otherwise legal")
    void emptyRunsContributeNothing() {
        List<StyledRun<String>> runs = List.of(run("", "parent"), run("a", "x"), run("", "child"));
        assertEquals("a", RunText.flatten(runs));
        assertEquals(1, RunText.length(runs));
    }

    @Test
    @DisplayName("length agrees with flatten for astral characters, counting chars not code points")
    void lengthCountsChars() {
        List<StyledRun<String>> runs = List.of(run("a😀b", "x"));
        assertEquals(4, RunText.length(runs));
        assertEquals(4, RunText.flatten(runs).length());
    }

    @Test
    @DisplayName("a null list or a null element is a programming error, not an empty string")
    void nullsRejected() {
        assertThrows(NullPointerException.class, () -> RunText.flatten(null));
        assertThrows(NullPointerException.class, () -> RunText.length(null));
        List<StyledRun<String>> withNull = Arrays.asList(run("a", "x"), null);
        assertThrows(NullPointerException.class, () -> RunText.flatten(withNull));
    }

    @Test
    @DisplayName("a run must carry text, but a null style means inherit from the parent")
    void styleMayBeNullTextMayNot() {
        assertThrows(NullPointerException.class, () -> new StyledRun<String>(null, "x"));
        StyledRun<String> inherited = run("a", null);
        assertEquals("a", RunText.flatten(List.of(inherited)));
    }

    @Test
    @DisplayName("withStyle returns the same instance when the style object is unchanged")
    void withStyleKeepsIdentity() {
        String style = "gray";
        StyledRun<String> original = run("[451]", style);
        org.junit.jupiter.api.Assertions.assertSame(original, original.withStyle(style));
        org.junit.jupiter.api.Assertions.assertNotSame(original, original.withStyle("gold"));
        assertEquals("[451]", original.withStyle("gold").text());
    }
}
