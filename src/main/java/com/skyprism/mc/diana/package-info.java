/**
 * The Minecraft-facing half of the Diana slot machine: everything that decides <em>when</em> the
 * machine spins and <em>what</em> it locks onto.
 *
 * <p>The animation itself lives in {@link com.skyprism.core.diana.SlotRoll} and the drawing lives in
 * the HUD module. This package is only the sensor array feeding them: it watches the server address,
 * the SkyBlock sidebar, the TAB list, the chat stream and a single bound entity, folds the first
 * three into {@link com.skyprism.core.diana.DianaGate}, and turns the last two into
 * {@code SlotRoll.start} / {@code SlotRoll.offerDrop} calls.
 *
 * <p><b>Cost when Diana is not the mayor:</b> every event handler registered here reads one boolean
 * field and returns. Nothing is parsed, nothing is allocated, and no entity query runs. The one
 * exception is {@link com.skyprism.mc.diana.HypixelContext#poll}, which is what would notice Diana
 * being elected; it is throttled to once every two seconds and returns after a single {@code long}
 * comparison in between.
 *
 * <p><b>Threading:</b> everything here is client-thread only, matching the single-threaded contract
 * of {@code DianaGate} and {@code SlotRoll}. There are no threads, executors, timers or sockets in
 * this package, and the only blocking call is the small synchronous write in
 * {@link com.skyprism.mc.diana.DianaStats}, which happens at most once a minute and only when
 * something actually changed.
 */
package com.skyprism.mc.diana;
