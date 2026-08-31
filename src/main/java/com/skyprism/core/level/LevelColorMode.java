package com.skyprism.core.level;

/**
 * How a SkyBlock level number is turned into a colour.
 *
 * <p>The three modes are deliberately different kinds of answer rather than three
 * flavours of the same one, because players want opposite things from this
 * feature: some want every level to be its own shade, some want readable tiers
 * they can recognise at a glance, and some just want the mod to stop touching a
 * prefix they are used to.</p>
 */
public enum LevelColorMode {
    /**
     * A smooth multi-stop ramp: each level gets its own interpolated shade, so
     * 250 and 251 are visibly a hair apart.
     */
    GRADIENT,

    /**
     * A step table: every level inside a bracket shares one colour, and the colour
     * changes only at the bracket boundaries the user configured.
     */
    BRACKETS,

    /**
     * Hypixel's own 13 forty-level tiers, byte-for-byte. Chosen when a player
     * wants the recolour feature installed but not applied, and used as the
     * reference the other modes are compared against.
     */
    VANILLA
}
