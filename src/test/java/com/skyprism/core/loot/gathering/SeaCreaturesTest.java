package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Sea creatures: the whole corpus, and the two detectors that split it")
class SeaCreaturesTest {

    private static final long NOW = 1_000L;

    private final SeaCreatureDetector rare = SeaCreatureDetector.rare();
    private final SeaCreatureDetector ordinary = SeaCreatureDetector.ordinary();

    @Nested
    @DisplayName("the table")
    class Table {

        @Test
        @DisplayName("carries the whole corpus: 89 creatures that speak, plus the second Titanoboa spelling")
        void size() {
            // 90 entries in SeaCreatures.json, one of which (Baby Magma Slug) has an empty
            // chat_message and therefore cannot be detected at all, plus one alias.
            assertEquals(90, SeaCreatures.size());
        }

        @Test
        @DisplayName("Baby Magma Slug is absent, because Hypixel announces nothing for it")
        void babyMagmaSlugIsAbsent() {
            assertTrue(SeaCreatures.byMessage().values().stream()
                    .noneMatch(c -> c.name().equals("Baby Magma Slug")));
        }

        @Test
        @DisplayName("both Titanoboa spellings resolve, because Hypixel corrected the line")
        void bothTitanoboaSpellings() {
            String its = "A massive Titanoboa surfaces. Its body stretches as far as the eye can see.";
            String itIs = "A massive Titanoboa surfaces. It's body stretches as far as the eye can see.";
            assertEquals("Titanoboa", SeaCreatures.byMessage(its).orElseThrow().name());
            assertEquals("Titanoboa", SeaCreatures.byMessage(itIs).orElseThrow().name());
            assertTrue(SeaCreatures.byMessage(itIs).orElseThrow().rare());
        }

        @Test
        @DisplayName("the cheap guard rejects ordinary chat without reaching the table")
        void guardRejectsChat() {
            assertNull(SeaCreatures.matchRaw("§bBob§f: anyone selling a rod"));
            assertNull(SeaCreatures.matchRaw(""));
            assertNull(SeaCreatures.matchRaw(null));
        }

        @Test
        @DisplayName("every announcement in the table survives its own guard")
        void guardNeverSwallowsARealAnnouncement() {
            for (String message : SeaCreatures.byMessage().keySet()) {
                assertNotNull(SeaCreatures.matchRaw("§9" + message),
                        "guard rejected a real announcement: " + message);
            }
        }
    }

    @Nested
    @DisplayName("the rare detector")
    class Rare {

        @Test
        @DisplayName("fires on the mythics, captioned with the creature")
        void mythics() {
            assertEquals("Lord Jawbus", subject(rare,
                    "§9You have angered a legendary creature... §r§bLord Jawbus §r§9has arrived."));
            assertEquals("Thunder", subject(rare, "§9You hear a massive rumble as Thunder emerges."));
            assertEquals("Ragnarok", subject(rare,
                    "§9The sky darkens and the air thickens. The end times are upon us: Ragnarok is here."));
            assertEquals("Nessie", subject(rare,
                    "§9You've caused a disturbance in the loch. Could it be... Nessie?"));
            assertEquals("Yeti", subject(rare, "§9What is this creature!?"));
        }

        @Test
        @DisplayName("ignores an ordinary catch, so the two detectors cannot both claim one line")
        void ignoresOrdinary() {
            assertTrue(rare.onChat("§9A Squid appeared.", NOW).isEmpty());
            assertTrue(rare.onChat("§9You caught a Sea Walker.", NOW).isEmpty());
        }

        @Test
        @DisplayName("cannot be spoofed by a player typing the sentence")
        void cannotBeSpoofed() {
            assertTrue(rare.onChat("§bBob§f: What is this creature!?", NOW).isEmpty());
            assertTrue(rare.onChat("§7Party §8> §bBob§f: WOAH! A Plhlegblast appeared.", NOW).isEmpty());
        }
    }

    @Nested
    @DisplayName("the ordinary detector")
    class Ordinary {

        @Test
        @DisplayName("fires on the everyday catches")
        void everyday() {
            assertEquals("Squid", subject(ordinary, "§9A Squid appeared."));
            assertEquals("Rider of the Deep", subject(ordinary, "§9The Rider of the Deep has emerged."));
            assertEquals("Bogged", subject(ordinary, "§9You've hooked a Bogged!"));
        }

        @Test
        @DisplayName("ignores every rare catch")
        void ignoresRare() {
            assertTrue(ordinary.onChat("§9What is this creature!?", NOW).isEmpty());
            assertTrue(ordinary.onChat("§9A Reindrake forms from the depths.", NOW).isEmpty());
        }

        @Test
        @DisplayName("the two detectors partition the corpus: every message goes to exactly one")
        void partition() {
            for (String message : SeaCreatures.byMessage().keySet()) {
                String line = "§9" + message;
                boolean byRare = rare.onChat(line, NOW).isPresent();
                boolean byOrdinary = ordinary.onChat(line, NOW).isPresent();
                assertTrue(byRare ^ byOrdinary, "not claimed exactly once: " + message);
            }
        }
    }

    @Nested
    @DisplayName("other sources' lines")
    class NotMine {

        @Test
        @DisplayName("neither detector claims a line belonging to another gathering source")
        void noCrossClaims() {
            for (String line : GatheringSamples.ALL_LINES) {
                if (line.startsWith("§9") && SeaCreatures.matchRaw(line) != null) {
                    continue;
                }
                assertFalse(rare.onChat(line, NOW).isPresent(), "rare claimed: " + line);
                assertFalse(ordinary.onChat(line, NOW).isPresent(), "ordinary claimed: " + line);
            }
        }
    }

    private static String subject(SeaCreatureDetector detector, String line) {
        Optional<LootEvent> event = detector.onChat(line, NOW);
        assertTrue(event.isPresent(), "no event for: " + line);
        assertEquals(detector.source() == LootSource.FISHING_RARE_SEA_CREATURE
                        ? LootSource.FISHING_RARE_SEA_CREATURE
                        : LootSource.FISHING_SEA_CREATURE,
                event.get().source());
        return event.get().subject();
    }
}
