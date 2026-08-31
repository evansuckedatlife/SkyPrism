package com.skyprism.core.config;

/**
 * Which corner or edge of the HUD widget the stored {@code (x, y)} fraction pins.
 *
 * <p><b>Why an enum and not the {@code int} the brief allowed.</b> An int anchor has to
 * be validated on every read, can be written out of range by a hand-edited file, and
 * tells the config screen nothing it can put on a button. An enum makes an invalid
 * anchor unrepresentable in Java, gives the screen its labels for free, and -- the part
 * that matters for a resilient config -- Gson turns an unrecognised name into
 * {@code null} rather than a plausible-looking wrong number, so
 * {@link SkyPrismConfig#sanitized()} can spot the damage and repair it instead of
 * silently drawing the machine off-screen.</p>
 *
 * <p>The anchor exists at all because {@code (x, y)} alone is ambiguous at the edges: a
 * widget pinned by its left edge at {@code x = 0.98} runs off a narrow window, while the
 * same widget pinned by its right edge sits neatly against the border at every
 * resolution. Storing which point of the widget the fraction refers to is what lets one
 * saved position survive a window resize, a GUI-scale change and an ultrawide monitor.</p>
 */
public enum HudAnchor {
    TOP_LEFT(0.0, 0.0),
    TOP_CENTER(0.5, 0.0),
    TOP_RIGHT(1.0, 0.0),
    MIDDLE_LEFT(0.0, 0.5),
    MIDDLE_CENTER(0.5, 0.5),
    MIDDLE_RIGHT(1.0, 0.5),
    BOTTOM_LEFT(0.0, 1.0),
    BOTTOM_CENTER(0.5, 1.0),
    BOTTOM_RIGHT(1.0, 1.0);

    private final double xFraction;
    private final double yFraction;

    HudAnchor(double xFraction, double yFraction) {
        this.xFraction = xFraction;
        this.yFraction = yFraction;
    }

    /**
     * How far across the widget's own width the anchor point sits.
     *
     * @return 0 at the widget's left edge, 0.5 at its centre, 1 at its right edge
     */
    public double xFraction() {
        return xFraction;
    }

    /**
     * How far down the widget's own height the anchor point sits.
     *
     * @return 0 at the widget's top edge, 0.5 at its centre, 1 at its bottom edge
     */
    public double yFraction() {
        return yFraction;
    }

    /**
     * Top-left pixel of a widget of this size placed at this anchor.
     *
     * <p>Kept here rather than in the render layer so the one piece of arithmetic that
     * turns a stored position into a screen position is unit-testable on a bare JVM;
     * the Minecraft-facing widget only has to supply four numbers it already knows.</p>
     *
     * @param screenWidth  window width in scaled pixels
     * @param screenHeight window height in scaled pixels
     * @param widgetWidth  the widget's own width in scaled pixels
     * @param widgetHeight the widget's own height in scaled pixels
     * @param x            stored horizontal position, a 0..1 fraction of the screen
     * @param y            stored vertical position, a 0..1 fraction of the screen
     * @return the widget's top-left corner as {@code [left, top]}, in scaled pixels
     */
    public double[] topLeft(double screenWidth, double screenHeight,
                            double widgetWidth, double widgetHeight,
                            double x, double y) {
        return new double[] {
            topLeftX(screenWidth, widgetWidth, x),
            topLeftY(screenHeight, widgetHeight, y)
        };
    }

    /**
     * The x half of {@link #topLeft}, without the array.
     *
     * <p>Offered because the HUD element calls this once per frame and the two-element array it
     * would otherwise allocate is pure garbage: the caller reads both slots immediately and drops
     * it. The array form stays for the placement screen, which genuinely wants a point.
     *
     * @param screenWidth the GUI-scaled screen width
     * @param widgetWidth the widget's drawn width
     * @param x           the horizontal position as a 0..1 fraction of the screen
     * @return the widget's left edge, in GUI pixels
     */
    public double topLeftX(double screenWidth, double widgetWidth, double x) {
        return screenWidth * x - widgetWidth * xFraction;
    }

    /**
     * The y half of {@link #topLeft}, without the array.
     *
     * @param screenHeight the GUI-scaled screen height
     * @param widgetHeight the widget's drawn height
     * @param y            the vertical position as a 0..1 fraction of the screen
     * @return the widget's top edge, in GUI pixels
     */
    public double topLeftY(double screenHeight, double widgetHeight, double y) {
        return screenHeight * y - widgetHeight * yFraction;
    }
}
