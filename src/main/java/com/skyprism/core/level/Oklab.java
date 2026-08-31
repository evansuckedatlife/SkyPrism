package com.skyprism.core.level;

/**
 * Conversions between packed 24-bit sRGB and the Oklab perceptual colour space.
 *
 * <p><b>Why this exists:</b> a level ramp interpolated straight in sRGB channels
 * goes muddy through the middle -- blending blue into yellow in sRGB passes
 * through a dead grey-green, and the ramp's apparent brightness lurches around
 * because sRGB is gamma-encoded, not perceptually uniform. Oklab is engineered so
 * that equal numeric steps look like equal perceptual steps, which is exactly what
 * a per-level gradient needs: a player at level 251 should look one notch away
 * from 250, not a third of the way to somewhere else.</p>
 *
 * <p>Colours are packed as {@code 0xRRGGBB}. There is no alpha channel anywhere in
 * this package; the level tag is always drawn opaque, and leaving alpha out keeps
 * every value in this module directly comparable.</p>
 *
 * <p>All methods are pure and allocation-light. {@link #mix(int, int, double)} in
 * particular is called once per rendered tag per frame when chroma is on, so it
 * avoids anything that would show up in a profile.</p>
 */
public final class Oklab {

    private Oklab() {
    }

    /**
     * Decomposes a packed sRGB colour into Oklab coordinates.
     *
     * @param rgb packed {@code 0xRRGGBB}; any bits above bit 23 are ignored
     * @return a fresh three-element array {@code {L, a, b}}, with L roughly in
     *         0..1 and a/b roughly in -0.4..0.4
     */
    public static double[] srgbToOklab(int rgb) {
        double r = toLinear(((rgb >> 16) & 0xFF) / 255.0);
        double g = toLinear(((rgb >> 8) & 0xFF) / 255.0);
        double b = toLinear((rgb & 0xFF) / 255.0);

        double l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
        double m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
        double s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

        double lp = Math.cbrt(l);
        double mp = Math.cbrt(m);
        double sp = Math.cbrt(s);

        return new double[] {
            0.2104542553 * lp + 0.7936177850 * mp - 0.0040720468 * sp,
            1.9779984951 * lp - 2.4285922050 * mp + 0.4505937099 * sp,
            0.0259040371 * lp + 0.7827717662 * mp - 0.8086757660 * sp
        };
    }

    /**
     * Recomposes Oklab coordinates into a packed sRGB colour.
     *
     * <p>Oklab is a larger space than sRGB, so an interpolated midpoint can land
     * outside the display gamut. Rather than fail, each channel is clamped
     * independently to 0..255 after the transfer function. That is a slight hue
     * shift in the extreme corners and completely invisible for the ranges a
     * level ramp actually uses.</p>
     *
     * @param L lightness
     * @param a green/red axis
     * @param b blue/yellow axis
     * @return packed {@code 0xRRGGBB}, always a valid sRGB colour
     */
    public static int oklabToSrgb(double L, double a, double b) {
        double lp = L + 0.3963377774 * a + 0.2158037573 * b;
        double mp = L - 0.1055613458 * a - 0.0638541728 * b;
        double sp = L - 0.0894841775 * a - 1.2914855480 * b;

        double l = lp * lp * lp;
        double m = mp * mp * mp;
        double s = sp * sp * sp;

        double r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

        return (channel(r) << 16) | (channel(g) << 8) | channel(bl);
    }

    /**
     * Perceptually even blend of two packed sRGB colours.
     *
     * <p>{@code t} is clamped, and the endpoints are short-circuited: {@code t <= 0}
     * returns {@code rgbA} and {@code t >= 1} returns {@code rgbB} bit-for-bit
     * rather than round-tripping them through Oklab. Callers depend on that --
     * {@link GradientRamp} promises that a stop's own level renders as exactly the
     * hex the user configured, and a 1/255 rounding drift there would be a visible
     * bug report.</p>
     *
     * @param rgbA the colour at {@code t == 0}, packed {@code 0xRRGGBB}
     * @param rgbB the colour at {@code t == 1}, packed {@code 0xRRGGBB}
     * @param t    blend position; NaN is treated as 0
     * @return the blended colour, packed {@code 0xRRGGBB}
     */
    public static int mix(int rgbA, int rgbB, double t) {
        if (!(t > 0.0)) { // also catches NaN
            return rgbA & 0xFFFFFF;
        }
        if (t >= 1.0) {
            return rgbB & 0xFFFFFF;
        }
        double[] p = srgbToOklab(rgbA);
        double[] q = srgbToOklab(rgbB);
        return oklabToSrgb(
            p[0] + (q[0] - p[0]) * t,
            p[1] + (q[1] - p[1]) * t,
            p[2] + (q[2] - p[2]) * t);
    }

    /** sRGB transfer function, encoded 0..1 to linear-light 0..1. */
    private static double toLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Inverse transfer function plus rounding and gamut clamping, linear-light to a 0..255 byte. */
    private static int channel(double linear) {
        double encoded = linear <= 0.0031308
            ? 12.92 * linear
            : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        int v = (int) Math.round(encoded * 255.0);
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
