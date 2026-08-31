package com.skyprism.core.level;

/**
 * The animated shimmer applied to high-level tags: a hue that sweeps the wheel at a
 * fixed rate, sampled at whatever timestamp the caller supplies.
 *
 * <p><b>Why the time is a parameter and not a field read:</b> this class never calls
 * {@code System.currentTimeMillis()}. The render layer samples the clock once per
 * frame and passes the same timestamp to every tag it draws, which is the only way a
 * TAB list of forty shimmering names stays in step instead of tearing across the
 * frame. It also makes the whole animation a pure function, so its cyclicity can be
 * asserted in a unit test rather than eyeballed in game.</p>
 *
 * <p>Hue is deliberately swept in HSL rather than Oklab. The shimmer wants maximum
 * apparent colour travel and a constant, obviously-synthetic vividness -- the exact
 * opposite of the even, natural progression {@link GradientRamp} needs -- and
 * constant-lightness HSL delivers that in a few multiplies per tag per frame.</p>
 *
 * <p>Immutable and thread-safe.</p>
 */
public final class ChromaClock {

    private final double cyclesPerSecond;
    private final double saturation;
    private final double lightness;
    private final double periodMillis;

    /**
     * @param cyclesPerSecond how many full trips around the hue wheel per second; must
     *                        be finite and greater than zero. Around 0.2 to 1.0 reads
     *                        as a shimmer; much faster reads as a strobe.
     * @param saturation      0..1; 0 collapses the shimmer to a moving grey, 1 is fully
     *                        vivid
     * @param lightness       0..1; 0 is black and 1 is white regardless of hue, so
     *                        useful values sit near 0.5 to 0.7 for chat legibility
     * @throws IllegalArgumentException if any argument is outside its stated range, or
     *                                  if {@code cyclesPerSecond} is so small that the
     *                                  resulting period is not a finite number of millis
     */
    public ChromaClock(double cyclesPerSecond, double saturation, double lightness) {
        if (!(cyclesPerSecond > 0.0) || !Double.isFinite(cyclesPerSecond)) {
            throw new IllegalArgumentException("cyclesPerSecond must be finite and > 0 but was " + cyclesPerSecond);
        }
        requireUnit(saturation, "saturation");
        requireUnit(lightness, "lightness");
        double period = 1000.0 / cyclesPerSecond;
        // A rate can be finite and positive and still have a period that overflows a
        // double. Letting that through gave an infinite period, which froze the shimmer
        // solid and made periodMillis() report a value no caller could step by.
        if (!Double.isFinite(period)) {
            throw new IllegalArgumentException(
                "cyclesPerSecond is too small to have a usable period: " + cyclesPerSecond);
        }
        this.cyclesPerSecond = cyclesPerSecond;
        this.saturation = saturation;
        this.lightness = lightness;
        this.periodMillis = period;
    }

    /**
     * The shimmer colour at a moment in time.
     *
     * <p>The timestamp is reduced modulo the period before it is scaled, not after.
     * Doing it in that order keeps {@code colorAt(t)} and {@code colorAt(t + period)}
     * bit-identical for whole-millisecond periods instead of drifting apart as the
     * game clock climbs into the billions, which is what a naive
     * {@code millis / 1000.0 * rate} would do.</p>
     *
     * @param millis      any monotonic millisecond timestamp; negative values are fine
     * @param phaseOffset a per-tag hue offset in degrees, so that two names on screen
     *                    are not locked to the identical colour. The level number
     *                    itself makes a good offset. Wraps exactly at 360, for every
     *                    timestamp and for negative and extreme offsets alike.
     * @return packed {@code 0xRRGGBB}
     */
    public int colorAt(long millis, int phaseOffset) {
        double pos = millis % periodMillis;
        if (pos < 0.0) {
            pos += periodMillis;
        }
        // The offset is reduced to 0..359 in integer arithmetic before it becomes a
        // fraction. Adding a whole turn as a double and floor-subtracting it afterwards
        // loses the low bit -- 0.4 comes back as 1.4 - 1.0 -- so colorAt(t, 0) and
        // colorAt(t, 360) disagreed by a unit of red on a few timestamps per cycle.
        double hue = pos / periodMillis + Math.floorMod(phaseOffset, 360) / 360.0;
        hue -= Math.floor(hue);
        return hslToRgb(hue, saturation, lightness);
    }

    /** Full trips around the hue wheel per second. */
    public double cyclesPerSecond() {
        return cyclesPerSecond;
    }

    /** Configured saturation, 0..1. */
    public double saturation() {
        return saturation;
    }

    /** Configured lightness, 0..1. */
    public double lightness() {
        return lightness;
    }

    /** Milliseconds for one full hue cycle; exposed so tests and tooling can step by a period. */
    public double periodMillis() {
        return periodMillis;
    }

    @Override
    public String toString() {
        return "ChromaClock[" + cyclesPerSecond + " Hz, s=" + saturation + ", l=" + lightness + "]";
    }

    private static void requireUnit(double v, String name) {
        if (!(v >= 0.0 && v <= 1.0)) {
            throw new IllegalArgumentException(name + " must be within 0..1 but was " + v);
        }
    }

    private static int hslToRgb(double h, double s, double l) {
        if (s == 0.0) {
            int v = byteOf(l);
            return (v << 16) | (v << 8) | v;
        }
        double q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
        double p = 2.0 * l - q;
        return (byteOf(hueToChannel(p, q, h + 1.0 / 3.0)) << 16)
            | (byteOf(hueToChannel(p, q, h)) << 8)
            | byteOf(hueToChannel(p, q, h - 1.0 / 3.0));
    }

    private static double hueToChannel(double p, double q, double t) {
        t -= Math.floor(t);
        if (t < 1.0 / 6.0) {
            return p + (q - p) * 6.0 * t;
        }
        if (t < 0.5) {
            return q;
        }
        if (t < 2.0 / 3.0) {
            return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
        }
        return p;
    }

    private static int byteOf(double unit) {
        int v = (int) Math.round(unit * 255.0);
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
