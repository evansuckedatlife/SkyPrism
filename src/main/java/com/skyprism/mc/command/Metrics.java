package com.skyprism.mc.command;

/**
 * The mod's performance counters, and the reason {@code /skyprism profile} can answer
 * "does SkyPrism cost me FPS?" with numbers instead of a promise.
 *
 * <p><b>Why this class exists.</b> SkyPrism makes three claims that are otherwise
 * unfalsifiable: the HUD costs nothing when no roll is running, the chat hook bails before
 * it ever touches a regex, and the TAB rewrite is memoised rather than recomputed 80 times
 * a frame. Each of those is a claim about work that <em>did not happen</em>, and the only
 * honest way to show work that did not happen is to count the times it was skipped next to
 * the times it was done. So every hot path calls in here, including - especially - on its
 * early-out branch.</p>
 *
 * <p><b>An instrument that cannot report a bad number is not an instrument.</b> For a while
 * {@link #tabProbe(boolean)}, {@link #tabRewrite(long)} and {@link #nameTagRewrite(long)} had no
 * callers at all -- the surfaces module kept private counters instead -- while
 * {@link #snapshot()} still reported a TAB hit rate, which for zero probes is defined as a
 * perfect 100%. So the two per-frame render surfaces, the only paths in the mod that can
 * plausibly cost frames, were the only ones the profiler did not measure, and the headline cost
 * line was computed from the HUD alone. They are wired now, and the cost line is the sum of
 * every measured path rather than one of them.</p>
 *
 * <p><b>Why it lives in the command package.</b> It is an output device. Nothing reads
 * these counters except {@code /skyprism profile}; the level, TAB, nametag and HUD modules
 * only ever write to it. Putting it beside its single reader keeps the dependency arrow
 * pointing one way and stops the counters growing features nobody displays.</p>
 *
 * <h2>Cost of measuring</h2>
 * <p>Every recording method is a handful of {@code long} additions on static fields, with
 * no clock read, no allocation, no synchronisation and no branch beyond the enabled flag.
 * The windowing that turns totals into per-second rates happens in {@link #tick()}, which
 * {@link ClientScheduler} calls 20 times a second - so the once-per-second
 * {@code System.currentTimeMillis()} read is paid by the tick loop, never by a render
 * frame or a chat message.</p>
 *
 * <p>Callers that would have to <em>do</em> work to measure (calling
 * {@link System#nanoTime()} twice around a block) should guard that with
 * {@link #enabled()} so profiling can be switched off entirely:</p>
 *
 * <pre>{@code
 * long t0 = Metrics.enabled() ? System.nanoTime() : 0L;
 * ... the real work ...
 * if (Metrics.enabled()) Metrics.hudFrame(System.nanoTime() - t0);
 * }</pre>
 *
 * <h2>Threading</h2>
 * <p>Plain non-volatile fields, deliberately. Every writer - the chat event, the TAB
 * overlay, the nametag renderer, the HUD element and the client tick - runs on the
 * Minecraft client thread, and so does the command that reads them. Making these fields
 * {@code volatile} or atomic would buy nothing and would put a memory barrier on the
 * render path. A stray write from another thread can only skew a displayed statistic; it
 * cannot corrupt anything.</p>
 */
public final class Metrics {

    private Metrics() {
    }

    /** Length of the rate window. One second is what a human reads as "per second". */
    private static final long WINDOW_MILLIS = 1_000L;

    private static boolean enabled = true;

    // ---- lifetime totals -------------------------------------------------

    private static long chatCalls;
    private static long chatNanos;
    private static long chatRewrites;
    private static long chatTags;

    private static long tabHits;
    private static long tabMisses;
    private static long tabRewrites;
    private static long tabNanos;

    private static long nameTagRewrites;
    private static long nameTagNanos;

    private static long hudSkips;
    private static long hudFrames;
    private static long hudNanos;
    private static long hudPeakNanos;

    private static long resetAtMillis = System.currentTimeMillis();

    // ---- current (accumulating) window -----------------------------------

    private static long windowStartMillis = resetAtMillis;
    private static long wChatCalls;
    private static long wChatNanos;
    private static long wChatRewrites;
    private static long wTabHits;
    private static long wTabMisses;
    private static long wTabNanos;
    private static long wNameTagNanos;
    private static long wHudFrames;
    private static long wHudNanos;

    // ---- last completed window, the numbers actually reported ------------

    private static long windowMillis = WINDOW_MILLIS;
    private static long rChatCalls;
    private static long rChatNanos;
    private static long rChatRewrites;
    private static long rTabHits;
    private static long rTabMisses;
    private static long rTabNanos;
    private static long rNameTagNanos;
    private static long rHudFrames;
    private static long rHudNanos;

    // ======================================================================
    //  Control
    // ======================================================================

    /**
     * @return whether counters are being recorded. Callers whose measurement itself costs
     *         something (a {@code nanoTime} pair) should skip it when this is false.
     */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Turns recording on or off. Off is genuinely free: the recording methods still run but
     * return on their first line, and time-stamping callers skip their clock reads.
     *
     * @param value whether to record
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Clears every counter and restarts the rate window. */
    public static void reset() {
        chatCalls = chatNanos = chatRewrites = chatTags = 0L;
        tabHits = tabMisses = tabRewrites = tabNanos = 0L;
        nameTagRewrites = nameTagNanos = 0L;
        hudSkips = hudFrames = hudNanos = hudPeakNanos = 0L;
        wChatCalls = wChatNanos = wChatRewrites = wTabHits = wTabMisses = wHudFrames = wHudNanos = 0L;
        rChatCalls = rChatNanos = rChatRewrites = rTabHits = rTabMisses = rHudFrames = rHudNanos = 0L;
        wTabNanos = wNameTagNanos = rTabNanos = rNameTagNanos = 0L;
        resetAtMillis = System.currentTimeMillis();
        windowStartMillis = resetAtMillis;
        windowMillis = WINDOW_MILLIS;
    }

    /**
     * Rolls the rate window if a second has passed. Called once per client tick by
     * {@link ClientScheduler}; it is the only place in this class that reads a clock.
     *
     * <p>The window is closed with its <em>measured</em> duration rather than an assumed
     * 1000 ms, because a stalled client can leave a tick gap far wider than that and a rate
     * computed against the assumption would read as a spike that never happened.</p>
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStartMillis;
        if (elapsed < WINDOW_MILLIS) {
            return;
        }
        windowMillis = elapsed;
        rChatCalls = wChatCalls;
        rChatNanos = wChatNanos;
        rChatRewrites = wChatRewrites;
        rTabHits = wTabHits;
        rTabMisses = wTabMisses;
        rTabNanos = wTabNanos;
        rNameTagNanos = wNameTagNanos;
        rHudFrames = wHudFrames;
        rHudNanos = wHudNanos;

        wChatCalls = wChatNanos = wChatRewrites = wTabHits = wTabMisses = wHudFrames = wHudNanos = 0L;
        wTabNanos = wNameTagNanos = 0L;
        windowStartMillis = now;
    }

    // ======================================================================
    //  Recording - called from the hot paths of the other modules
    // ======================================================================

    /**
     * One invocation of the chat hook, however early it bailed.
     *
     * <p>Record this on <em>every</em> call, including the "no bracket in the string, no
     * regex run" branch. The ratio of {@link #chatMessages()} to {@link #chatRewrites()} is
     * the evidence that the cheap gate is doing its job.</p>
     *
     * @param nanos wall time spent inside the hook
     */
    public static void chatMessage(long nanos) {
        if (!enabled) {
            return;
        }
        chatCalls++;
        chatNanos += nanos;
        wChatCalls++;
        wChatNanos += nanos;
    }

    /**
     * A chat component that actually had level tags recoloured.
     *
     * @param tagCount how many level tags were restyled in it
     */
    public static void chatRewrite(int tagCount) {
        if (!enabled) {
            return;
        }
        chatRewrites++;
        chatTags += tagCount;
        wChatRewrites++;
    }

    /**
     * One probe of the TAB-list memoisation cache.
     *
     * <p>This is the load-bearing counter for the TAB performance rule: the overlay asks for
     * up to 80 display names every frame, so at 60 FPS a hit rate below about 99% means the
     * cache key is wrong and the mod is recomputing for no reason.</p>
     *
     * @param hit whether the cached component could be reused
     */
    public static void tabProbe(boolean hit) {
        if (!enabled) {
            return;
        }
        if (hit) {
            tabHits++;
            wTabHits++;
        } else {
            tabMisses++;
            wTabMisses++;
        }
    }

    /**
     * A TAB entry whose display name was genuinely rebuilt (a cache miss did work).
     *
     * @param nanos wall time spent rebuilding it
     */
    public static void tabRewrite(long nanos) {
        if (!enabled) {
            return;
        }
        tabRewrites++;
        tabNanos += nanos;
        wTabNanos += nanos;
    }

    /**
     * An above-head nametag that was recoloured.
     *
     * @param nanos wall time spent on it
     */
    public static void nameTagRewrite(long nanos) {
        if (!enabled) {
            return;
        }
        nameTagRewrites++;
        nameTagNanos += nanos;
        wNameTagNanos += nanos;
    }

    /**
     * The HUD element's first-line early-out: no roll is active, so nothing was drawn.
     *
     * <p>Counting the skips is the whole point. A profile showing millions of skips and a
     * handful of frames is the proof that the slot machine is free when idle.</p>
     */
    public static void hudSkip() {
        if (!enabled) {
            return;
        }
        hudSkips++;
    }

    /**
     * A HUD frame that actually drew a spinning slot machine.
     *
     * @param nanos wall time spent inside {@code extractRenderState}
     */
    public static void hudFrame(long nanos) {
        if (!enabled) {
            return;
        }
        hudFrames++;
        hudNanos += nanos;
        wHudFrames++;
        wHudNanos += nanos;
        if (nanos > hudPeakNanos) {
            hudPeakNanos = nanos;
        }
    }

    // ======================================================================
    //  Reading
    // ======================================================================

    /** @return lifetime chat-hook invocations since the last reset */
    public static long chatMessages() {
        return chatCalls;
    }

    /** @return lifetime chat components whose level tags were recoloured */
    public static long chatRewrites() {
        return chatRewrites;
    }

    /**
     * Everything {@code /skyprism profile} prints, captured in one consistent read so the
     * report cannot show a hit rate computed from two different instants.
     *
     * @param enabled          whether recording is currently on
     * @param uptimeMillis     time since the last {@link #reset()}
     * @param chatMessages     chat-hook invocations, including early bail-outs
     * @param chatRewrites     chat components actually recoloured
     * @param chatTags         level tags restyled across those components
     * @param chatAvgMicros    mean microseconds per chat-hook invocation
     * @param chatPerSecond    chat-hook invocations per second, last window
     * @param chatMillisPerSec milliseconds of every second spent in the chat hook, last window
     * @param chatRewritesPerSec chat components recoloured per second, last window
     * @param tabHits          TAB cache probes served from the memo
     * @param tabMisses        TAB cache probes that had to rebuild
     * @param tabHitRate       hits divided by probes, 0..1; 1 when there were no probes
     * @param tabRewrites      TAB display names rebuilt
     * @param tabAvgMicros     mean microseconds per rebuild
     * @param tabProbesPerSec  TAB cache probes per second, last window
     * @param nameTagRewrites  above-head nametags recoloured
     * @param nameTagAvgMicros mean microseconds per nametag
     * @param hudSkips         HUD frames that early-outed with no roll active
     * @param hudFrames        HUD frames that drew
     * @param hudAvgMicros     mean microseconds per drawing frame
     * @param hudPeakMicros    worst single drawing frame
     * @param hudFramesPerSec  drawing frames per second, last window
     * @param hudMillisPerSec  milliseconds of every second spent in the HUD, last window
     * @param surfaceMillisPerSec milliseconds of every second spent rebuilding TAB entries and
     *                         nametags, last window -- the two per-frame render surfaces
     * @param costMillisPerSec the headline: every millisecond of each second the mod spends in
     *                         any of its measured paths
     */
    public record Snapshot(
            boolean enabled,
            long uptimeMillis,
            long chatMessages,
            long chatRewrites,
            long chatTags,
            double chatAvgMicros,
            double chatPerSecond,
            double chatMillisPerSec,
            double chatRewritesPerSec,
            long tabHits,
            long tabMisses,
            double tabHitRate,
            long tabRewrites,
            double tabAvgMicros,
            double tabProbesPerSec,
            long nameTagRewrites,
            double nameTagAvgMicros,
            long hudSkips,
            long hudFrames,
            double hudAvgMicros,
            double hudPeakMicros,
            double hudFramesPerSec,
            double hudMillisPerSec,
            double surfaceMillisPerSec,
            double costMillisPerSec) {
    }

    /**
     * @return a consistent view of every counter, with the derived averages and rates
     *         already computed
     */
    public static Snapshot snapshot() {
        long probes = tabHits + tabMisses;
        double perWindow = windowMillis <= 0L ? 0.0 : 1000.0 / windowMillis;
        double chatPerSec = rChatNanos / 1_000_000.0 * perWindow;
        double hudPerSec = rHudNanos / 1_000_000.0 * perWindow;
        // The two per-frame render surfaces belong in the headline. They are the only paths in
        // the mod that can cost frames on a full lobby, and the cost line -- documented as what
        // the mod takes out of every 16.7 ms frame -- used to be computed from the HUD alone,
        // structurally excluding them.
        double surfacePerSec = (rTabNanos + rNameTagNanos) / 1_000_000.0 * perWindow;
        return new Snapshot(
                enabled,
                System.currentTimeMillis() - resetAtMillis,
                chatCalls,
                chatRewrites,
                chatTags,
                micros(chatNanos, chatCalls),
                rChatCalls * perWindow,
                chatPerSec,
                rChatRewrites * perWindow,
                tabHits,
                tabMisses,
                probes == 0L ? 1.0 : (double) tabHits / probes,
                tabRewrites,
                micros(tabNanos, tabRewrites),
                (rTabHits + rTabMisses) * perWindow,
                nameTagRewrites,
                micros(nameTagNanos, nameTagRewrites),
                hudSkips,
                hudFrames,
                micros(hudNanos, hudFrames),
                hudPeakNanos / 1_000.0,
                rHudFrames * perWindow,
                hudPerSec,
                surfacePerSec,
                chatPerSec + hudPerSec + surfacePerSec);
    }

    /** Mean microseconds, defined as zero rather than NaN when nothing was sampled. */
    private static double micros(long totalNanos, long count) {
        return count <= 0L ? 0.0 : totalNanos / (double) count / 1_000.0;
    }
}
