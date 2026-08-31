package com.skyprism.core.loot.containers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ContainerText: the two tidies that decide whether a caption reads like a name")
class ContainerTextTest {

    @Nested
    @DisplayName("playerName")
    class PlayerName {

        @Test
        @DisplayName("a bare coloured name loses only its formatting")
        void bare() {
            assertEquals("Leebys", ContainerText.playerName("§r§bLeebys"));
        }

        @Test
        @DisplayName("a rank prefix is dropped, whatever rank it is")
        void rankPrefix() {
            assertEquals("Leebys", ContainerText.playerName("§b[MVP§c+§b] §bLeebys"));
            assertEquals("Grazma", ContainerText.playerName("§a[VIP] Grazma"));
            assertEquals("Grazma", ContainerText.playerName("§6[MVP§c++§6] Grazma"));
        }

        @Test
        @DisplayName("a guild tag after the name does not become the name")
        void guildTag() {
            assertEquals("Leebys", ContainerText.playerName("[MVP+] Leebys [SKY]"));
        }

        @Test
        @DisplayName("nothing usable yields empty rather than a fragment")
        void empty() {
            assertEquals("", ContainerText.playerName(null));
            assertEquals("", ContainerText.playerName("   "));
            assertEquals("", ContainerText.playerName("§r"));
        }
    }

    @Nested
    @DisplayName("itemCaption")
    class ItemCaption {

        @Test
        @DisplayName("a plain item keeps every word of its name")
        void plain() {
            assertEquals("Scavenged Diamond Axe",
                    ContainerText.itemCaption("§cScavenged Diamond Axe"));
        }

        @Test
        @DisplayName("the stack count Hypixel hides inside the name group is removed")
        void trailingCount() {
            assertEquals("Flawed Jade Gemstone",
                    ContainerText.itemCaption("§a☘ Flawed Jade Gemstone §r§8x2"));
            assertEquals("Gemstone Powder",
                    ContainerText.itemCaption("§dGemstone Powder §r§8x537"));
            assertEquals("Mithril Powder",
                    ContainerText.itemCaption("§2Mithril Powder §r§8x1,204"));
        }

        @Test
        @DisplayName("a gemstone tier glyph is dropped, never matched")
        void leadingGlyph() {
            assertEquals("Rough Amethyst Gemstone",
                    ContainerText.itemCaption("§f❈ Rough Amethyst Gemstone"));
            assertEquals("Fine Onyx Gemstone",
                    ContainerText.itemCaption("§9☠ Fine Onyx Gemstone §r§8x2"));
        }

        @Test
        @DisplayName("a name that merely ends in digits keeps them")
        void digitsAreNotACount() {
            assertEquals("FTX 3070", ContainerText.itemCaption("§9FTX 3070"));
            assertEquals("Pickonimbus 2000", ContainerText.itemCaption("§5Pickonimbus 2000"));
            assertEquals("Recombobulator 3000", ContainerText.itemCaption("Recombobulator 3000"));
        }

        @Test
        @DisplayName("a one-letter first token is not mistaken for a glyph")
        void singleLetterToken() {
            assertEquals("A Test Item", ContainerText.itemCaption("A Test Item"));
        }
    }
}
