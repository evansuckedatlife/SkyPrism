package com.skyprism.core.text;

import java.util.List;
import java.util.Objects;

/**
 * Turns a list of {@link StyledRun}s into the plain string that matchers run against.
 *
 * <p>Everything in this package agrees on one coordinate system: {@code char} indices
 * into the string {@link #flatten(List)} produces. Keeping the flattening in one tiny
 * place is what makes {@link RunRewriter}'s central promise checkable -- restyling must
 * never change this string.</p>
 */
public final class RunText {

    private RunText() {
    }

    /**
     * Concatenates every run's text in order.
     *
     * @param runs the runs, not null and containing no nulls
     * @param <S>  the style type, never inspected
     * @return the flattened text; empty for an empty list
     * @throws NullPointerException if {@code runs} or any element is null
     */
    public static <S> String flatten(List<StyledRun<S>> runs) {
        Objects.requireNonNull(runs, "runs");
        StringBuilder out = new StringBuilder(length(runs));
        for (StyledRun<S> run : runs) {
            out.append(run.text());
        }
        return out.toString();
    }

    /**
     * The length of {@link #flatten(List)} without building it.
     *
     * <p>Useful for bounds-checking spans on the hot path, where allocating the whole
     * string just to ask for its length would be wasteful.</p>
     *
     * @param runs the runs, not null and containing no nulls
     * @param <S>  the style type, never inspected
     * @return the total number of {@code char}s across all runs
     * @throws NullPointerException if {@code runs} or any element is null
     */
    public static <S> int length(List<StyledRun<S>> runs) {
        Objects.requireNonNull(runs, "runs");
        int total = 0;
        for (StyledRun<S> run : runs) {
            total += Objects.requireNonNull(run, "run").length();
        }
        return total;
    }
}
