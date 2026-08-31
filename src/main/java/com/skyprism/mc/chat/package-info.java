/**
 * The chat surface of both SkyPrism features: level-tag recolouring on the way in, and the
 * Diana slot machine's supply of parsed loot.
 *
 * <p>Three classes, with one job each:</p>
 *
 * <ul>
 *   <li>{@link com.skyprism.mc.chat.ChatHooks} attaches to Fabric and does nothing else.</li>
 *   <li>{@link com.skyprism.mc.chat.ChatRouter} decides what happens to a line. It is
 *       drivable from a command or a test, which is why the Fabric plumbing is not in
 *       it.</li>
 *   <li>{@link com.skyprism.mc.chat.DianaLineFilter} is the fast reject that keeps the
 *       core's anchored regexes off the chat thread. It is a contract with the Diana
 *       module rather than a private optimisation, so it is its own class and its own
 *       test.</li>
 * </ul>
 *
 * <p>{@link com.skyprism.mc.chat.DianaChatBridge} is the seam between this package and the
 * Diana module. It carries the suppression rule as well as the feed, because deciding
 * whether to hide a line the server sent needs live roll state that chat does not own.</p>
 *
 * <p>Nothing here renders, memoises or holds configuration, and nothing here knows how to
 * rebuild a legacy section-sign string. That last one used to live in this package as a
 * second copy of the algorithm {@code com.skyprism.mc.diana} also carried; both are now
 * {@code com.skyprism.mc.text.LegacyText}, which sits in {@code mc.text} because this
 * package already depends on {@code mc.diana} and a shared helper in either would have made
 * that circular. The recolouring rules likewise live once in
 * {@code com.skyprism.mc.text.ComponentRewriter}, shared with the TAB list and the nametag
 * mixin; the settings live once in {@code com.skyprism.mc.config.ConfigManager}.</p>
 */
package com.skyprism.mc.chat;
