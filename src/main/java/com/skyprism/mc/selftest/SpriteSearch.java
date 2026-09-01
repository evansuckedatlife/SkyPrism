package com.skyprism.mc.selftest;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.Optional;

/**
 * Finds a 16x16 item texture inside a screenshot, or proves it is not in there.
 *
 * <h2>Why a pixel search and not an assertion</h2>
 *
 * <p>Every check this project had before was upstream of the picture: the pack is mounted, the
 * namespace resolves, the stack carries an {@code item_model}. All three were true of the run
 * that produced screenshots drawn entirely in vanilla art, because none of them is a statement
 * about pixels. This one is. It takes the PNG that was written and Hypixel's own PNG for the item
 * that PNG is supposed to be showing, and answers whether the second is inside the first.</p>
 *
 * <h2>How the match works</h2>
 *
 * <p>An item sprite reaches the GUI as a nearest-neighbour magnification of its texture by a whole
 * number, and a flat item model in the GUI is drawn unshaded, so a texel's colour survives to the
 * framebuffer unchanged. That makes the search exact rather than statistical: every fully opaque
 * texel must be found, byte for byte, at the right offset.</p>
 *
 * <p>The offset is what makes it cheap. Pick one texel as the anchor; if that texel is drawn at
 * frame pixel {@code (fx, fy)} at scale {@code s}, then <em>every</em> other texel {@code u} is
 * drawn at {@code (fx + (ux-ax)*s, fy + (uy-ay)*s)} -- the anchor's position inside its own
 * magnified block cancels out of the difference, so the sprite's true origin never has to be
 * guessed. So the whole search is: find the pixels that could be the anchor, and for each one try
 * each scale.</p>
 *
 * <p>Choosing the anchor is the other half. One pass over the frame counts how often each of the
 * template's colours occurs anywhere in it; the anchor is the texel whose colour is rarest in the
 * frame, which typically leaves a few hundred candidates out of two million pixels. If that colour
 * does not occur at all, the sprite cannot be present and the search stops on the first pass.</p>
 *
 * <h2>What it will not do</h2>
 *
 * <p>It will not match a sprite drawn at a fractional scale, tinted, glinted or washed with
 * colour, and it does not try: a fuzzy match that answered "close enough" is precisely the kind
 * of check that passed for two releases while the pictures were wrong. A caller that photographs
 * a glinting or gold-washed cell gets {@link Outcome#NOT_FOUND} and must say so, not soften the
 * threshold.</p>
 */
final class SpriteSearch {

    private SpriteSearch() {
    }

    /** The largest magnification looked for. The HUD draws at 16x on a doubled widget already. */
    static final int MAX_SCALE = 24;

    /** Below this many opaque texels a match would not mean anything, so it is refused. */
    private static final int MIN_TEXELS = 12;

    /** How many anchor candidates are worth trying before giving up on this template. */
    private static final int MAX_CANDIDATES = 400_000;

    /** What a search concluded. */
    enum Outcome {
        /** The template was located, byte for byte. */
        FOUND,
        /** The template is definitely not in the frame at any whole magnification. */
        NOT_FOUND,
        /** The template is too small, too plain or too transparent for a match to prove anything. */
        UNUSABLE
    }

    /**
     * Where a template was found.
     *
     * @param x       the left edge of the sprite in frame pixels
     * @param y       the top edge of the sprite in frame pixels
     * @param scale   the whole-number magnification it was drawn at
     * @param texels  how many opaque texels were matched, which is all of them
     */
    record Hit(int x, int y, int scale, int texels) {

        @Override
        public String toString() {
            return texels + " texels at " + x + "," + y + " scale " + scale + "x";
        }
    }

    /** A search result: an outcome, and a hit when there is one. */
    record Result(Outcome outcome, Hit hit, String note) {

        static Result found(Hit hit) {
            return new Result(Outcome.FOUND, hit, hit.toString());
        }

        static Result absent(String note) {
            return new Result(Outcome.NOT_FOUND, null, note);
        }

        static Result unusable(String note) {
            return new Result(Outcome.UNUSABLE, null, note);
        }

        boolean isFound() {
            return outcome == Outcome.FOUND;
        }
    }

    /** A decoded image as a flat ARGB array, so the search never touches native memory twice. */
    record Pixels(int[] argb, int width, int height) {

        int at(int x, int y) {
            return argb[y * width + x];
        }
    }

    /**
     * Copies a {@link NativeImage} out to a plain array.
     *
     * <p>Both the frame and the template go through this, so whichever channel order the platform
     * hands back cancels out of every comparison below. Only the alpha byte is read positionally,
     * and it is the top byte in both of the orders Mojang uses.</p>
     */
    static Pixels pixels(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = image.getPixel(x, y);
            }
        }
        return new Pixels(out, width, height);
    }

    /**
     * A template's opaque texels, with the colour histogram the anchor choice needs.
     */
    static final class Template {

        private final int[] tx;
        private final int[] ty;
        private final int[] rgb;
        private final int count;
        final int width;
        final int height;

        private Template(int[] tx, int[] ty, int[] rgb, int count, int width, int height) {
            this.tx = tx;
            this.ty = ty;
            this.rgb = rgb;
            this.count = count;
            this.width = width;
            this.height = height;
        }

        /** @return how many fully opaque texels this template will insist on matching */
        int texels() {
            return count;
        }
    }

    /**
     * Reduces an image to the opaque texels a match has to reproduce.
     *
     * <p>Partly transparent texels are dropped rather than blended: what they composite against
     * is whatever the widget drew behind them, which this class has no way to know and no reason
     * to model.</p>
     */
    static Template template(Pixels image) {
        int size = image.width() * image.height();
        int[] tx = new int[size];
        int[] ty = new int[size];
        int[] rgb = new int[size];
        int count = 0;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int argb = image.at(x, y);
                if ((argb >>> 24) != 0xFF) {
                    continue;
                }
                tx[count] = x;
                ty[count] = y;
                rgb[count] = argb & 0x00FFFFFF;
                count++;
            }
        }
        return new Template(tx, ty, rgb, count, image.width(), image.height());
    }

    /**
     * Looks for a template anywhere in a frame, at any whole magnification.
     *
     * @param frame    the screenshot
     * @param template the item texture that frame is supposed to be showing
     * @return where it is, or a stated reason it is not
     */
    static Result find(Pixels frame, Template template) {
        if (template.count < MIN_TEXELS) {
            return Result.unusable("the texture has only " + template.count
                    + " fully opaque texels, under the " + MIN_TEXELS
                    + " it would take for a match to mean anything");
        }

        // One pass over the frame, counting only the colours this template actually uses. That
        // is both the "is it here at all" shortcut and the anchor choice, off the same scan.
        // Open-addressed rather than a HashMap because this is two million lookups per search
        // and a boxed Integer per pixel turns a fifth of a second into several.
        ColourCounts wanted = new ColourCounts(template);
        int[] framePixels = frame.argb();
        for (int argb : framePixels) {
            wanted.count(argb & 0x00FFFFFF);
        }

        int anchor = -1;
        int anchorOccurrences = Integer.MAX_VALUE;
        for (int i = 0; i < template.count; i++) {
            int occurrences = wanted.occurrences(template.rgb[i]);
            if (occurrences < anchorOccurrences) {
                anchorOccurrences = occurrences;
                anchor = i;
            }
        }
        if (anchorOccurrences == 0) {
            return Result.absent("the texture's rarest colour "
                    + hex(template.rgb[anchor]) + " does not occur anywhere in the frame, so no"
                    + " magnification of this texture can be in it");
        }
        if (anchorOccurrences > MAX_CANDIDATES) {
            return Result.unusable("every colour in this texture is common in the frame (the"
                    + " rarest still occurs " + anchorOccurrences + " times), so the search would"
                    + " cost more than the answer is worth");
        }

        int ax = template.tx[anchor];
        int ay = template.ty[anchor];
        int anchorRgb = template.rgb[anchor];

        for (int fy = 0; fy < frame.height(); fy++) {
            int row = fy * frame.width();
            for (int fx = 0; fx < frame.width(); fx++) {
                if ((framePixels[row + fx] & 0x00FFFFFF) != anchorRgb) {
                    continue;
                }
                // Smallest first, and that ordering is load-bearing rather than arbitrary. A
                // sprite drawn at 16x also matches at 17x: the check samples one pixel per texel
                // and a one-pixel-per-texel drift of nine still lands inside a sixteen-pixel
                // block, so several magnifications above the real one satisfy it and taking the
                // largest reports a scale and an origin that are both a few pixels wrong.
                //
                // The smallest cannot be. The anchor is the raster-first pixel of its colour, so
                // it sits at the top-left corner of its own magnified block and every other texel
                // is at or below it. At any scale under the real one, those lower texels are
                // sampled above where they were drawn -- into the previous row of blocks, which is
                // a different colour -- so the match fails. The first scale that matches going up
                // is therefore the scale it was drawn at.
                for (int scale = 1; scale <= MAX_SCALE; scale++) {
                    if (matches(frame, template, ax, ay, fx, fy, scale)) {
                        return Result.found(new Hit(fx - ax * scale, fy - ay * scale, scale,
                                template.count));
                    }
                }
            }
        }
        return Result.absent("no whole magnification from 1x to " + MAX_SCALE + "x of this "
                + template.count + "-texel texture appears anywhere in the frame");
    }

    private static boolean matches(Pixels frame, Template template, int ax, int ay,
                                   int fx, int fy, int scale) {
        for (int i = 0; i < template.count; i++) {
            int px = fx + (template.tx[i] - ax) * scale;
            int py = fy + (template.ty[i] - ay) * scale;
            if (px < 0 || py < 0 || px >= frame.width() || py >= frame.height()) {
                return false;
            }
            if ((frame.argb()[py * frame.width() + px] & 0x00FFFFFF) != template.rgb[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * How often each of a template's colours occurs, in an open-addressed int table.
     *
     * <p>A template has at most a few dozen distinct colours and the frame has two million
     * pixels, so the table is sized to the former and probed by the latter. Everything is a
     * primitive: the same job through {@code HashMap<Integer, Integer>} allocates a box per hit
     * and dominates the whole audit.</p>
     */
    private static final class ColourCounts {

        private final int[] colours;
        private final int[] counts;
        private final boolean[] used;
        private final int mask;

        ColourCounts(Template template) {
            int capacity = Integer.highestOneBit(Math.max(16, template.count * 4) - 1) * 2;
            this.colours = new int[capacity];
            this.counts = new int[capacity];
            this.used = new boolean[capacity];
            this.mask = capacity - 1;
            for (int i = 0; i < template.count; i++) {
                insert(template.rgb[i]);
            }
        }

        private void insert(int colour) {
            int slot = slotOf(colour);
            if (!used[slot]) {
                used[slot] = true;
                colours[slot] = colour;
            }
        }

        private int slotOf(int colour) {
            int slot = (colour * 0x9E3779B1) >>> 1 & mask;
            while (used[slot] && colours[slot] != colour) {
                slot = slot + 1 & mask;
            }
            return slot;
        }

        /** Adds one to this colour's tally, if the template uses it at all. */
        void count(int colour) {
            int slot = slotOf(colour);
            if (used[slot]) {
                counts[slot]++;
            }
        }

        /** @return how many frame pixels carried this colour */
        int occurrences(int colour) {
            int slot = slotOf(colour);
            return used[slot] ? counts[slot] : 0;
        }
    }

    /** @return {@code #rrggbb}, for a message somebody has to read */
    static String hex(int rgb) {
        return String.format("#%06X", rgb & 0x00FFFFFF);
    }

    /**
     * Reads a PNG off disk through the same decoder the textures go through.
     *
     * @param bytes the file contents
     * @return the pixels
     * @throws Exception when the file is not a readable image
     */
    static Pixels decode(byte[] bytes) throws Exception {
        try (NativeImage image = NativeImage.read(bytes)) {
            return pixels(image);
        }
    }

    /** Convenience for a texture that has already been located in the resource manager. */
    static Optional<Template> templateOf(NativeImage image) {
        try {
            return Optional.of(template(pixels(image)));
        } finally {
            image.close();
        }
    }
}
