package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.loot.RollPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The universal drop banners: both shapes, the double space, and what they decline")
class BannerDetectorsTest {

    @Nested
    @DisplayName("MobRareDropDetector")
    class MobRareDrop {

        @Test
        @DisplayName("ON_RARE_BANNER is the only sane default: the banner is the trigger")
        void defaultPolicy() {
            assertEquals(RollPolicy.ON_RARE_BANNER,
                    LootSourceRegistry.defaultPolicy(LootSource.MOB_RARE_DROP));
        }

        @Test
        @DisplayName("the plain shape fires, whatever colour the tier painted the banner")
        void plainShape() {
            MobRareDropDetector detector = new MobRareDropDetector();
            String[] lines = {
                    "§6§lRARE DROP! §r§9Judgement Core §r§b(+§r§b168% §r§b✯ Magic Find§r§b)",
                    "§6§lRARE DROP! §r§5Golden Powder §r§b(+123% ✯ Magic Find)",
                    "§9§lRARE DROP! §r§9Arachne's Keeper Fragment §r§b(+123% ✯ Magic Find)",
                    "§5§lRARE DROP! §r§9Hunk of Blue Ice §r§b(+123% ✯ Magic Find)",
                    "§d§lRARE DROP! §r§9Beating Heart §r§b(+123% ✯ Magic Find)",
                    "§6§lRARE DROP! §r§fEnchanted Book §r§b(+123% ✯ Magic Find)",
            };
            for (String line : lines) {
                LootEvent event = detector.onChat(line, 3L).orElseThrow(() -> new AssertionError(line));
                assertEquals(LootSource.MOB_RARE_DROP, event.source());
                assertEquals("Rare Mob Drop", event.subject(),
                        "an ordinary mob has no kill line, so the source name is the honest caption");
            }
        }

        @Test
        @DisplayName("the bracketed sack shape fires -- the one a naive parser reads as \"(\"")
        void bracketedShape() {
            MobRareDropDetector detector = new MobRareDropDetector();
            String[] lines = {
                    "§b§lRARE DROP! §r§7(§r§f§r§9Revenant Viscera§r§7) (+123% ✯ Magic Find)",
                    "§b§lRARE DROP! §r§7(§r§f§r§72x §r§f§r§9Foul Flesh§r§7) (+123% ✯ Magic Find)",
            };
            for (String line : lines) {
                assertTrue(detector.onChat(line, 0L).isPresent(), line);
            }
        }

        @Test
        @DisplayName("VERY RARE and CRAZY RARE carry TWO spaces, and both still fire")
        void doubleSpaceVariants() {
            MobRareDropDetector detector = new MobRareDropDetector();
            String[] lines = {
                    "§5§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) (+123% ✯ Magic Find)",
                    "§9§lVERY RARE DROP!  §r§7(§r§fMana Steal I§r§7) (+123% ✯ Magic Find)",
                    "§d§lCRAZY RARE DROP!  §r§7(§r§f§r§fPocket Espresso Machine§r§7) (+123%)",
                    "§6§lINSANE DROP!  §r§7(§r§f§r§6Some Thing§r§7) (+123%)",
            };
            for (String line : lines) {
                assertTrue(detector.onChat(line, 0L).isPresent(),
                        "the double space is verbatim in eleven SkyHanni patterns: " + line);
            }
        }

        @Test
        @DisplayName("declines the banners other sources own")
        void declinesOtherSources() {
            MobRareDropDetector detector = new MobRareDropDetector();
            String[] foreign = {
                    // Diana's treasure dig, which is a RARE DROP! by wording only.
                    "§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!",
                    "§6§lWow! §r§eYou dug out §r§62,500 coins§r§e!",
                    // The Garden's Crop Fever.
                    "RARE DROP! You dropped 48x Enchanted Melon Slice!",
                    // The Garden's pest drop, told apart by its own trailing bracket.
                    "§6§lRARE DROP! §r§aNot Just a Pest Vinyl §r§6(Cocoaleech)",
                    // Different banner words entirely.
                    "§6§lRARE CROP! §r§9Burrowing Spores",
                    "§6§lVERY RARE CROP! §r§9Burrowing Spores",
                    "§6§lPET DROP! §r§5Baby Yeti §r§b(+123% ✯ Magic Find)",
                    "§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§9Lavahorse §r§6§lGOLD§r§f!",
                    "§9§lRARE! §r§9Scavenger IV §r§egift with §r§aSteve§r§f§r§e!",
            };
            for (String line : foreign) {
                assertTrue(detector.onChat(line, 0L).isEmpty(), line);
            }
        }

        @Test
        @DisplayName("declines somebody else's loot, however it is phrased")
        void declinesOtherPlayersLoot() {
            MobRareDropDetector detector = new MobRareDropDetector();
            String[] foreign = {
                    "§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Recombobulator 3000 "
                            + "§r§ein their Obsidian Chest§r§e!",
                    "Steve §r§ehas obtained §r§a§r§9Judgement Core§r§e!",
                    "§c§lBONUS LOOT! §r§eThey also received §r§817x §r§5Wise Dragon Fragment "
                            + "§r§efrom their sacrifice!",
                    "LOOT SHARE You received 2 Mossybit Shards for assisting FallenYeti!",
            };
            for (String line : foreign) {
                assertTrue(detector.onChat(line, 0L).isEmpty(), line);
            }
        }

        @Test
        @DisplayName("a party message quoting a drop banner cannot spin the machine")
        void partyInjectionDeclined() {
            MobRareDropDetector detector = new MobRareDropDetector();
            String[] injections = {
                    "§9Party §8> Steve§f: §rRARE DROP! §r§9Judgement Core",
                    "§7Steve§f: §rVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7)",
                    "§e[NPC] §6Keeper of Lapis§f: §rRARE DROP! §r§9Thing",
            };
            for (String line : injections) {
                assertTrue(detector.onChat(line, 0L).isEmpty(), line);
            }
        }

        @Test
        @DisplayName("open anywhere in SkyBlock, shut outside it")
        void gate() {
            MobRareDropDetector detector = new MobRareDropDetector();
            assertTrue(detector.gateOpen(GameContext.onIsland("Hub")));
            assertTrue(detector.gateOpen(GameContext.onIsland("The End")));
            assertTrue(!detector.gateOpen(GameContext.UNKNOWN));
        }
    }

    @Nested
    @DisplayName("PetDropDetector")
    class PetDrop {

        @Test
        @DisplayName("ALWAYS: the banner is already the rarity gate")
        void defaultPolicy() {
            assertEquals(RollPolicy.ALWAYS, LootSourceRegistry.defaultPolicy(LootSource.PET_DROP));
        }

        @Test
        @DisplayName("captions the pet by name, with and without a magic-find tail")
        void capturesThePetName() {
            PetDropDetector detector = new PetDropDetector();
            assertEquals("Baby Yeti", detector
                    .onChat("§6§lPET DROP! §r§5Baby Yeti §r§b(+§r§b168% §r§b✯ Magic Find§r§b)", 0L)
                    .orElseThrow().subject());
            assertEquals("Rat", detector
                    .onChat("§6§lPET DROP! §r§6Rat", 0L)
                    .orElseThrow().subject());
            assertEquals(LootSource.PET_DROP, detector
                    .onChat("§6§lPET DROP! §r§6Rat", 0L)
                    .orElseThrow().source());
        }

        @Test
        @DisplayName("exposes the rarity colour, so the flourish keys on it and not on a name list")
        void exposesRarityColour() {
            assertEquals('5', PetDropDetector.rarityColour(
                    "§6§lPET DROP! §r§5Baby Yeti §r§b(+123% ✯ Magic Find)"));
            assertEquals('6', PetDropDetector.rarityColour("§6§lPET DROP! §r§6Rat"));
            assertEquals(0, PetDropDetector.rarityColour("§6§lRARE DROP! §r§9Judgement Core"));
            assertEquals(0, PetDropDetector.rarityColour(null));
        }

        @Test
        @DisplayName("declines the two pet shapes that are not a pet drop of yours")
        void declinesForeignPetShapes() {
            PetDropDetector detector = new PetDropDetector();
            String[] foreign = {
                    // Fished, which belongs to the fishing treasure source.
                    "§6? §r§6§lGREAT CATCH! §r§fYou caught a §r§7[Lvl 1] §r§aSquid§r§f!",
                    // Somebody else's.
                    "Steve §r§ehas obtained §r§7[Lvl 1] §r§6Golden Dragon§r§e!",
                    // A plain rare drop.
                    "§6§lRARE DROP! §r§9Judgement Core §r§b(+123% ✯ Magic Find)",
            };
            for (String line : foreign) {
                assertTrue(detector.onChat(line, 0L).isEmpty(), line);
            }
        }

        @Test
        @DisplayName("a party message quoting a pet drop is declined")
        void partyInjectionDeclined() {
            assertTrue(new PetDropDetector()
                    .onChat("§9Party §8> Steve§f: §rPET DROP! §r§6Golden Dragon", 0L)
                    .isEmpty());
        }

        @Test
        @DisplayName("the Garden pest pet drop overlaps by shape; registration order resolves it")
        void gardenPestOverlapIsReal() {
            // Documented rather than defended in code: "PET DROP! Slug (+78)" is a genuine pet drop
            // by shape and a Garden drop only by context. GARDEN_PEST_DROP's own pattern, which
            // requires the fortune bracket, is the stricter match -- so it must be registered
            // first. If this ever starts returning empty, someone has narrowed the pet pattern and
            // the plain "PET DROP! Rat" case is at risk; check that one before "fixing" this.
            assertTrue(new PetDropDetector()
                    .onChat("§6§lPET DROP! §r§6Slug §e(§e+78)", 0L)
                    .isPresent());
        }
    }

    @Nested
    @DisplayName("CombatChatGuards")
    class Guards {

        @Test
        @DisplayName("every ownership tell is caught")
        void ownershipTells() {
            List<String> foreign = List.of(
                    "Steve has obtained a thing",
                    "They also received something",
                    "Leebys §r§efound a §r§6Recombobulator 3000",
                    "You received 2 Shards for assisting FallenYeti!");
            for (String line : foreign) {
                assertTrue(CombatChatGuards.announcesAnotherPlayer(line), line);
            }
            assertTrue(!CombatChatGuards.announcesAnotherPlayer(
                    "§6§lRARE DROP! §r§9Judgement Core"));
        }

        @Test
        @DisplayName("the colon guard rejects every player-authored shape and no server banner")
        void colonGuard() {
            assertTrue(CombatChatGuards.looksPlayerAuthored("§9Party §8> Steve§f: §rhi"));
            assertTrue(CombatChatGuards.looksPlayerAuthored("§e[NPC] §6Trevor§f: §rhi"));
            assertTrue(!CombatChatGuards.looksPlayerAuthored(
                    "§6§lRARE DROP! §r§9Judgement Core §r§b(+123% ✯ Magic Find)"));
            assertTrue(!CombatChatGuards.looksPlayerAuthored("§f  §r§6§lARACHNE DOWN!"));
        }
    }
}
