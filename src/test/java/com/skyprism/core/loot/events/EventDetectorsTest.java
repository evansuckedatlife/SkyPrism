package com.skyprism.core.loot.events;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RollPolicy;
import com.skyprism.core.loot.SourceDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One positive sample per pattern, for every detector in the events package.
 *
 * <p>Deliberately paired with {@code EventDetectorCrossTalkTest}, which supplies the negatives. A
 * positive-only suite proves a detector can fire; it says nothing about whether it fires on somebody
 * else's line, and cross-source false positives are the likeliest bug in this whole feature.
 *
 * <p>Section signs are {@code \u00A7} escapes so the file's encoding cannot change what is tested.
 */
@DisplayName("events detectors: each fires on its own real captured lines")
class EventDetectorsTest {

    private static final long NOW = 1_000L;
    private static final GameContext HUB = new GameContext(true, true, "Hub", "", "", false, false);

    @Nested
    @DisplayName("Hoppity's Hunt and the Chocolate Factory")
    class Hoppity {

        @Test
        @DisplayName("a meal egg names the meal")
        void mealEgg() {
            assertSubject(new HoppityEggDetector(),
                    "\u00A7d\u00A7lHOPPITY'S HUNT \u00A7r\u00A7dYou found a \u00A7r\u00A79Chocolate Lunch Egg "
                            + "\u00A7r\u00A7don a ledge next to the stairs up\u00A7r\u00A7d!",
                    "Chocolate Lunch Egg");
        }

        @Test
        @DisplayName("a Hitman egg is the same source under a different shape")
        void hitmanEgg() {
            assertSubject(new HoppityEggDetector(),
                    "\u00A7d\u00A7lHOPPITY'S HUNT \u00A7r\u00A7dYou found a \u00A7r\u00A76Hitman Egg\u00A7r\u00A7d!",
                    "Hitman Egg");
        }

        @Test
        @DisplayName("a rabbit line matches despite Hypixel's UPPER-CASE formatting codes")
        void rabbitUpperCaseCodes() {
            String line = "\u00A7D\u00A7LHOPPITY'S HUNT \u00A77You found \u00A76Solomon \u00A77(\u00A76\u00A7LLEGENDARY\u00A77)!";
            assertSubject(new HoppityRabbitDetector(), line, "Solomon");
            assertEquals(Optional.of("LEGENDARY"), HoppityRabbitDetector.rarityOf(line));
            assertTrue(HoppityRabbitDetector.isJackpotRarity("LEGENDARY"));
            assertFalse(HoppityRabbitDetector.isJackpotRarity("COMMON"));
        }

        @Test
        @DisplayName("the common tiers still produce an event, with the rarity for the policy")
        void rabbitCommonTiers() {
            String line = "\u00A7D\u00A7LHOPPITY'S HUNT \u00A77You found \u00A7fArnie \u00A77(\u00A7F\u00A7LCOMMON\u00A77)!";
            assertSubject(new HoppityRabbitDetector(), line, "Arnie");
            assertEquals(Optional.of("COMMON"), HoppityRabbitDetector.rarityOf(line));
        }

        @Test
        @DisplayName("a stray is named in both of its shapes")
        void strays() {
            ChocolateFactoryStrayDetector detector = new ChocolateFactoryStrayDetector();
            assertSubject(detector,
                    "\u00A77You caught a stray \u00A76\u00A7lGolden Rabbit\u00A77! \u00A77You gained "
                            + "\u00A76+13,566,571 Chocolate\u00A77!",
                    "Golden Rabbit");
            assertSubject(detector, "\u00A77You caught a stray \u00A79Fish the Rabbit\u00A77!",
                    "Fish the Rabbit");
            assertSubject(detector, "\u00A76El Dorado \u00A7d\u00A7lCAUGHT!", "El Dorado");
        }

        @Test
        @DisplayName("opening the Chocolate Factory is not a payout")
        void openingTheFactoryDoesNotRoll() {
            assertTrue(new ChocolateFactoryStrayDetector()
                    .onScreenTitle("Chocolate Factory", NOW).isEmpty());
        }
    }

    @Nested
    @DisplayName("seasonal festivals")
    class Seasonal {

        @Test
        @DisplayName("a Season of Jerry gift carries its own rarity word")
        void winterGift() {
            String sweet = "\u00A7e\u00A7lSWEET! \u00A7r\u00A75Snow Suit Helmet \u00A7r\u00A7egift with \u00A7r\u00A7aGrazma\u00A7r\u00A7e!";
            assertSubject(new WinterGiftDetector(), sweet, "SWEET Gift");
            assertEquals(Optional.of("SWEET"), WinterGiftDetector.tierOf(sweet));
            assertTrue(WinterGiftDetector.isRareTier("SWEET"));
            assertTrue(WinterGiftDetector.isRareTier("SANTA TIER"));
            assertFalse(WinterGiftDetector.isRareTier("COMMON"),
                    "COMMON is the overwhelming majority of a gifting session");
            assertFalse(WinterGiftDetector.isRareTier("RARE"),
                    "RARE is the second of five tiers and is common in practice");
        }

        @Test
        @DisplayName("every verified gift payload shape shares one skeleton")
        void winterGiftPayloads() {
            WinterGiftDetector detector = new WinterGiftDetector();
            assertSubject(detector,
                    "\u00A79\u00A7lRARE! \u00A7r\u00A76+20,000 Coins \u00A7r\u00A7egift with \u00A7r\u00A7aGrazma\u00A7r\u00A7e!", "RARE Gift");
            assertSubject(detector,
                    "\u00A7f\u00A7lCOMMON! \u00A7r\u00A73+500 Enchanting XP \u00A7r\u00A7egift with \u00A7r\u00A7aGrazma\u00A7r\u00A7e!",
                    "COMMON Gift");
            assertSubject(detector,
                    "\u00A79\u00A7lRARE! \u00A7r\u00A7f◆ Ice Rune \u00A7r\u00A7egift with \u00A7r\u00A7aGrazma\u00A7r\u00A7e!", "RARE Gift");
            assertSubject(detector,
                    "\u00A7c\u00A7lSANTA TIER! \u00A7r\u00A75Snow Suit Boots \u00A7r\u00A7egift with \u00A7r\u00A7aGrazma\u00A7r\u00A7e!",
                    "SANTA TIER Gift");
        }

        @Test
        @DisplayName("a spooky chest appearance is detected, and shipped switched off")
        void spookyChest() {
            assertSubject(new SpookyChestDetector(),
                    "\u00A76\u00A7lSPOOKY! \u00A7r\u00A77A \u00A7r\u00A76Trick or Treat Chest \u00A7r\u00A77has appeared!",
                    "Trick or Treat Chest");
            assertSubject(new SpookyChestDetector(),
                    "\u00A7c\u00A7lPARTY! \u00A7r\u00A77A \u00A7r\u00A7cParty Chest \u00A7r\u00A77has appeared!", "Party Chest");
            assertEquals(RollPolicy.NEVER,
                    LootEvent.of(LootSource.SPOOKY_CHEST, NOW).defaultPolicy(),
                    "an appearance broadcast cannot establish that the loot was yours");
        }

        @Test
        @DisplayName("a shiny orb extraction rolls; the charge line does not")
        void shinyOrb() {
            YearOfThePigOrbDetector detector = new YearOfThePigOrbDetector();
            assertSubject(detector,
                    "SHINY! You extracted Shiny Token and +1,000,000 Coins from the piglet's orb!",
                    "Shiny Orb");
            assertTrue(detector.onChat(
                    "SHINY! The orb is charged! Click on it for loot!", NOW).isEmpty(),
                    "the orb being ready is not the orb being opened");
        }
    }

    @Nested
    @DisplayName("the two summon-window bosses")
    class SummonWindows {

        @Test
        @DisplayName("a Reindrake summon arms but never rolls on its own")
        void reindrakeSummonDoesNotRoll() {
            ReindrakeDetector detector = new ReindrakeDetector();
            detector.gateOpen(HUB);
            assertTrue(detector.onChat(
                    "WOAH! [VIP] Georeek summoned a Reindrake from the depths!", NOW).isEmpty(),
                    "the broadcast fires lobby-wide, for bystanders who never touch the fight");
        }

        @Test
        @DisplayName("the next rare banner inside the window is credited to the Reindrake, once")
        void reindrakeClaimsOneBanner() {
            ReindrakeDetector detector = new ReindrakeDetector();
            detector.gateOpen(HUB);
            detector.onChat("WOAH! [MVP+] DulceLyncis summoned TWO Reindrakes from the depths!", NOW);

            Optional<LootEvent> first = detector.onChat(
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Reindrake Fragment \u00A7r\u00A7b(+123% ✯ Magic Find)", NOW + 1_000L);
            assertTrue(first.isPresent());
            assertEquals("Reindrake", first.get().subject());

            assertTrue(detector.onChat(
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Frozen Blaze Helmet \u00A7r\u00A7b(+123% ✯ Magic Find)",
                    NOW + 2_000L).isEmpty(),
                    "single-shot: one summon is one roll, not one per drop");
        }

        @Test
        @DisplayName("a banner outside the window belongs to the generic catch-all")
        void reindrakeWindowExpires() {
            ReindrakeDetector detector = new ReindrakeDetector();
            detector.gateOpen(HUB);
            detector.onChat("WOAH! [VIP] Georeek summoned a Reindrake from the depths!", NOW);
            assertTrue(detector.onChat(
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Judgement Core \u00A7r\u00A7b(+123% ✯ Magic Find)",
                    NOW + ReindrakeDetector.WINDOW_MILLIS + 1L).isEmpty());
        }

        @Test
        @DisplayName("a Primal Fear summon arms, and the next banner is credited to it")
        void primalFear() {
            PrimalFearDetector detector = new PrimalFearDetector();
            detector.gateOpen(HUB);
            assertTrue(detector.onChat(
                    "\u00A75\u00A7lFEAR. \u00A7r\u00A7eA \u00A7r\u00A7dPrimal Fear \u00A7r\u00A7ehas been summoned!", NOW).isEmpty());

            Optional<LootEvent> event = detector.onChat(
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Ephemeral Gratitude \u00A7r\u00A7b(+123% ✯ Magic Find)", NOW + 500L);
            assertTrue(event.isPresent());
            assertEquals("Primal Fear", event.get().subject());
        }

        @Test
        @DisplayName("a context change closes the window, so a summon cannot follow the player")
        void contextChangeDisarms() {
            PrimalFearDetector detector = new PrimalFearDetector();
            detector.gateOpen(HUB);
            detector.onChat("\u00A75\u00A7lFEAR. \u00A7r\u00A7eA \u00A7r\u00A7dPrimal Fear \u00A7r\u00A7ehas been summoned!", NOW);
            detector.gateOpen(GameContext.onIsland("The End"));
            assertTrue(detector.onChat(
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Judgement Core \u00A7r\u00A7b(+123% ✯ Magic Find)",
                    NOW + 500L).isEmpty());
        }

        @Test
        @DisplayName("a pet dropped mid-fight stays a pet drop rather than being relabelled")
        void windowsRefusePets() {
            ReindrakeDetector detector = new ReindrakeDetector();
            detector.gateOpen(HUB);
            detector.onChat("WOAH! [VIP] Georeek summoned a Reindrake from the depths!", NOW);
            assertTrue(detector.onChat("\u00A76\u00A7lPET DROP! \u00A7r\u00A75Baby Yeti", NOW + 500L).isEmpty());
        }
    }

    @Nested
    @DisplayName("The Rift and the Carnival")
    class RiftAndCarnival {

        @Test
        @DisplayName("Split or Steal rolls on the GUI, and the cooldown line suppresses it")
        void splitOrSteal() {
            SplitOrStealDetector detector = new SplitOrStealDetector();
            Optional<LootEvent> first = detector.onScreenTitle("Split or Steal", NOW);
            assertTrue(first.isPresent());
            assertEquals("Split or Steal", first.get().subject());

            assertTrue(detector.onScreenTitle("Split or Steal", NOW + 1_000L).isEmpty(),
                    "reopening the same GUI is the same gamble");

            long later = NOW + SplitOrStealDetector.MIN_GAP_MILLIS + 1L;
            assertTrue(detector.onChat(
                    "SPLIT! You need to wait 4h 12m before you can play again.", later).isEmpty(),
                    "being turned away is the opposite of a payout");
            assertTrue(detector.onScreenTitle("Split or Steal", later + 1L).isEmpty(),
                    "a refusal proves the game was not playable, so the GUI must not roll");
        }

        @Test
        @DisplayName("a Motes orb is detected, and is shipped off because it is routine currency")
        void motesOrb() {
            assertSubject(new MotesOrbDetector(),
                    "\u00A75\u00A7lORB! \u00A7r\u00A7dPicked up \u00A7r\u00A75+12 Motes\u00A7r\u00A7d.", "Motes Orb");
            assertEquals(RollPolicy.NEVER,
                    LootEvent.of(LootSource.RIFT_MOTES_ORB, NOW).defaultPolicy());
        }

        @Test
        @DisplayName("all three vermin are read, and nothing else is")
        void vermin() {
            RiftVerminDetector detector = new RiftVerminDetector();
            assertSubject(detector, "\u00A7eYou vacuumed a \u00A7r\u00A7aSilverfish\u00A7r\u00A7e!", "Silverfish");
            assertSubject(detector, "\u00A7eYou vacuumed a \u00A7r\u00A7aSpider\u00A7r\u00A7e!", "Spider");
            assertSubject(detector, "\u00A7eYou vacuumed a \u00A7r\u00A7aFly\u00A7r\u00A7e!", "Fly");
            assertTrue(detector.onChat("\u00A7eYou vacuumed a \u00A7r\u00A7aDragon\u00A7r\u00A7e!", NOW).isEmpty(),
                    "the vermin set is closed on purpose");
        }

        @Test
        @DisplayName("the Carnival reveals a fruit; the bomb and empty lines are not triggers")
        void carnival() {
            CarnivalFruitDiggingDetector detector = new CarnivalFruitDiggingDetector();
            assertSubject(detector, "TREASURE! There is a Dragonfruit nearby.", "Dragonfruit");
            assertSubject(detector, "TREASURE! There is an Orange nearby.", "Orange");
            assertTrue(detector.onChat("MINES! There are 3 bombs hidden nearby.", NOW).isEmpty());
            assertTrue(detector.onChat("TREASURE! There are no fruits nearby!", NOW).isEmpty());
        }

        @Test
        @DisplayName("the Rift detectors are shut everywhere outside the Rift")
        void riftGatesAreShutElsewhere() {
            assertFalse(new MotesOrbDetector().gateOpen(HUB));
            assertFalse(new RiftVerminDetector().gateOpen(HUB));
            GameContext rift = new GameContext(true, true, "The Rift", "West Village", "",
                    false, true);
            assertTrue(new MotesOrbDetector().gateOpen(rift));
            assertTrue(new RiftVerminDetector().gateOpen(rift));
        }

        @Test
        @DisplayName("the Carnival gate needs the area, not just the island")
        void carnivalGateNeedsTheArea() {
            CarnivalFruitDiggingDetector detector = new CarnivalFruitDiggingDetector();
            assertFalse(detector.gateOpen(HUB));
            assertTrue(detector.gateOpen(GameContext.onIsland("Hub", "Carnival")));
        }
    }

    @Nested
    @DisplayName("the universal banner sources")
    class Universal {

        @Test
        @DisplayName("a pet drop is captioned as a pet, with its rarity available for the jackpot")
        void petDrop() {
            String line = "\u00A76\u00A7lPET DROP! \u00A7r\u00A76Golden Dragon";
            assertSubject(new PetDropDetector(), line, "Pet Drop");
            assertEquals(Optional.of("Golden Dragon"),
                    PetDropDetector.petOf(line).map(RareDropBanner.Banner::item));
            assertTrue(PetDropDetector.isJackpotRarityColor("6"), "LEGENDARY");
            assertTrue(PetDropDetector.isJackpotRarityColor("d"), "MYTHIC");
            assertFalse(PetDropDetector.isJackpotRarityColor("a"), "UNCOMMON");
        }

        @Test
        @DisplayName("the catch-all claims any banner, captioned honestly as an unnamed source")
        void genericCatchAll() {
            GenericRareDropDetector detector = new GenericRareDropDetector();
            assertSubject(detector,
                    "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Judgement Core \u00A7r\u00A7b(+123% ✯ Magic Find)", "Rare Mob Drop");
            assertSubject(detector,
                    "\u00A7b\u00A7lRARE DROP! \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A79Revenant Viscera\u00A7r\u00A77) (+123% ✯ Magic Find)",
                    "Rare Mob Drop");
            assertEquals(Optional.of("Revenant Viscera"),
                    GenericRareDropDetector.dropOf(
                            "\u00A7b\u00A7lRARE DROP! \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A79Revenant Viscera\u00A7r\u00A77) (+1%)")
                            .map(RareDropBanner.Banner::item));
        }

        @Test
        @DisplayName("the catch-all refuses pet drops even if it is asked first")
        void catchAllRefusesPets() {
            assertTrue(new GenericRareDropDetector()
                    .onChat("\u00A76\u00A7lPET DROP! \u00A7r\u00A75Baby Yeti", NOW).isEmpty());
        }
    }

    private static void assertSubject(SourceDetector detector, String line, String expected) {
        Optional<LootEvent> event = detector.onChat(line, NOW);
        assertTrue(event.isPresent(), detector.source() + " did not match: " + line);
        assertEquals(expected, event.get().subject());
        assertEquals(detector.source(), event.get().source());
        assertEquals(NOW, event.get().atMillis());
    }
}
