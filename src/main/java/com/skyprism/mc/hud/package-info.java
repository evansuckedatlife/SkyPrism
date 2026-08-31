/**
 * The Diana slot machine's on-screen half.
 *
 * <p>Everything that decides <em>what</em> the machine shows lives in
 * {@code com.skyprism.core.diana}: {@code SlotRoll} is a clock-driven state machine that
 * answers "which reels have locked, onto what, and is this a jackpot". This package answers
 * only "where on the screen, in which colours, and how big", so the interesting logic stays
 * unit-tested on a bare JVM and the untestable part stays as small as it can be.</p>
 *
 * <p>The whole package is one class plus this file, and its render method is written to cost
 * nothing at all when no roll is running -- see
 * {@link com.skyprism.mc.hud.SlotMachineHud#extractRenderState}.</p>
 */
package com.skyprism.mc.hud;
