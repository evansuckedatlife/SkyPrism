package com.skyprism.core.loot;

/**
 * How a {@link LootSource} announces itself to the client.
 *
 * <p>Recorded per source because it decides which mechanism has to exist before the source can work
 * at all, and because it is the honest way to say "we found no signal for this". A source declared
 * {@link #CHAT} owes the registry markers and samples; one declared {@link #SCREEN_TITLE} owes a
 * title; one declared {@link #ENTITY} cannot be served by the chat bus at all and is waiting on the
 * client-side adapter that watches the world.
 */
public enum TriggerKind {

    /** A chat line. The overwhelming majority, and the only kind the bus routes by itself. */
    CHAT,

    /** A container opening, identified by its inventory title from the open-screen packet. */
    SCREEN_TITLE,

    /**
     * Something in the world: an entity dying, an armour stand appearing, a tab widget changing.
     *
     * <p>Diana is the shipped example -- the roll fires when the bound creature is defeated, which
     * no chat line announces. The Ender Dragon and the Endstone Protector are the awkward ones: they
     * have a chat line for the kill but drop their loot as floating armour stands with nothing in
     * chat at all, so a reel has nothing to land on unless something reads the world.
     */
    ENTITY
}
