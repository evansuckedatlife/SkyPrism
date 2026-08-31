package com.skyprism.mc.hud;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.Reel;
import com.skyprism.core.diana.RollState;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.mc.command.Metrics;
import com.skyprism.mc.command.SkyPrismServices;
import com.skyprism.mc.config.ConfigManager;
import com.skyprism.mc.diana.DianaController;
import com.skyprism.mc.symbols.DropSymbols;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Draws SkyPrism's slot machine, and is the mod's only HUD element.
 *
 * <h2>What it is a machine for</h2>
 *
 * <p>It began as Diana's: a Mythological Ritual creature died, the reels locked onto what the
 * player actually received, and a rare drop earned a casino jackpot. It now spins for every
 * chance-based activity in SkyBlock -- slayer bosses, dungeon runs, Kuudra, chests, corpses,
 * excavations, tree gifts, trophy fish, Hoppity rabbits, winter gifts -- and the change to this
 * file is smaller than that sounds, because the only thing that had to generalise was the
 * caption.</p>
 *
 * <p>The geometry, the sprite sizes, the three-of-a-kind choreography and the gold timings are
 * untouched. The reels still lock onto {@link LootDrop}s, which were always source-agnostic. What
 * moved is that the strip under them names the subject of a {@link LootEvent} -- "Minos
 * Inquisitor", "Voidgloom Seraph IV", "Obsidian Chest", "Blue Shark" -- instead of a creature, and
 * is tinted by that source's {@link SourceCategory}. See {@code drawCaption} for why the hint at
 * the category is a colour rather than a second line.</p>
 *
 * <p>Where the spins come from is not this class's business and deliberately so: it draws one
 * {@link SlotRoll}, and both {@code DianaController} (from a creature dying) and
 * {@link LootMachine} (from a chat-driven {@link LootEvent}) start that same roll. Diana is
 * exactly as it was; it simply stopped being the only caller.</p>
 *
 * <p><b>Why this class holds almost no state.</b> The spin is not decided here. It is decided
 * by {@link SlotRoll}, a pure state machine in the Minecraft-free core driven entirely by a
 * {@code Clock} and asked, once per frame, what it currently looks like. That split is what
 * lets the timing be unit-tested without a game, and it means this class never re-derives when
 * a reel locks -- it reads {@link Reel#locked()} and believes it. What this file owns is
 * presentation: the fade-out alpha, the vertical scroll of the strip, and the whole look of the
 * jackpot act. All of it is read off wall-clock millis rather than off a frame counter or an
 * accumulator, so a 30 fps laptop and a 240 fps desktop see the same animation at the same
 * speed, and a dropped frame cannot drift one effect out of phase with another.
 *
 * <h2>The performance contract</h2>
 *
 * <p>A HUD element runs on the render thread for every frame the game draws -- several hundred
 * a second on a good machine -- and the machine is idle for essentially all of them. Going
 * SkyBlock-wide raised how often it spins, and changed nothing at all about that: the idle path
 * is the same two reads it always was, and the extra sources cost the render thread nothing
 * because they cost it nothing to <em>not</em> be spinning.
 * So {@link #extractRenderState} opens with a plain field read and a {@link SlotRoll#activeAt}
 * call, which is an enum comparison, and returns. No {@code Minecraft} lookup, no config read,
 * no {@code ItemStack}, no {@code Component}, no allocation of any kind happens above that
 * line, and nothing the jackpot act adds below it is reachable while the machine is idle.
 * Everything the drawing path needs that can be hoisted -- the filler strips, their resolved item
 * icons, the legacy-colour lookup table -- is hoisted into {@code static} state built once. Going
 * per source did not change that: {@link FillerStrip} builds all sixty-four at class-init and
 * hands one back with an array load, and a roll resolves which one it is exactly once.</p>
 *
 * <p><b>Why the roll is cached in a field rather than fetched per frame.</b>
 * {@code DianaController.roll()} is not free: it re-derives a {@code SlotRollConfig} record so
 * it can notice a settings change, which would be one allocation per frame forever. So the
 * reference is cached -- but it is refreshed off {@code DianaController.rollEpoch()}, an
 * {@code int} the controller bumps at the moment it installs a different roll, and not off a
 * {@link ConfigManager} change listener. The listener looks like the right signal and is not:
 * the controller <em>defers</em> a rebuild while a roll is on screen, so a Diana setting changed
 * mid-spin fires the listener at the one moment the controller is refusing to swap, this class
 * re-caches the instance that is about to be retired, and the real swap happens later with
 * nothing announcing it. The machine then never draws again for the rest of the session. An int
 * compare on the idle path buys a signal that cannot be out of step with the thing it tracks.</p>
 *
 * <p><b>One clock read, and everything derived from it.</b> {@link SlotRoll} answers every
 * question against its own injected clock, so asking it eight things in a frame used to mean
 * eight wall-clock reads that were not even consistent with each other -- a reel's lock deadline
 * falling between two of them produced a frame reporting SPINNING from one call and a locked reel
 * from the next. The roll publishes {@code nowMillis()} and a {@code ...At(long)} overload for
 * each query, so this class reads the clock once and hands that instant to all of them, which is
 * what the paragraph above about frame-rate independence has always claimed.</p>
 *
 * <h2>What a reel shows</h2>
 *
 * <p>Real item sprites, drawn with {@code GuiGraphicsExtractor.item}, with the drop's name as a
 * small caption underneath. The machine used to draw names alone, which was legible but read as
 * a list rather than as a slot machine; sprites alone are not enough on their own either,
 * because a Griffin Feather and a stack of Enchanted Ancient Claws are both a small brown thing
 * at 16 by 16, and a drop {@link DropSymbols} has no mapping for would be unidentifiable. So the
 * sprite is the primary element at double size and the name is a subtitle under it, switchable
 * off through {@code hud.showDropNames} for players who want the sprites clean -- and drawn anyway,
 * toggle or not, on any drop {@code DropSymbols} could not map, because a fallback sprite with
 * no caption identifies nothing.</p>
 *
 * <p>The sprite is scaled through the pose stack rather than by asking for a bigger draw,
 * because {@code item} takes integer coordinates and no size. Each sprite is pushed, translated
 * to the centre of its cell, scaled, drawn about the origin and popped, so the transform cannot
 * escape into the next window; and 26.x captures the pose and the scissor rectangle into the
 * item's own {@code GuiItemRenderState} at submit time, so the item's model, lighting and depth
 * are resolved in isolation and nothing it does bleeds into the flat fills around it.</p>
 *
 * <h2>Why the reels get Hypixel's art and not vanilla's</h2>
 *
 * <p>SkyBlock now ships an official server resource pack, and it dresses its items through the
 * vanilla 26.x item-model system: Hypixel sets {@code minecraft:item_model} on the stack to an
 * id like {@code hypixel_skyblock:item/.../daedalus_blade} and the pack supplies the model
 * behind that id. Nothing about that is SkyPrism's business except one thing -- the component
 * only means something if the stack is handed to the renderer that reads it.</p>
 *
 * <p>{@code GuiGraphicsExtractor.item(stack, x, y)} is that renderer, and it is the same one
 * the inventory uses. Its bytecode on both merged jars forwards to the private
 * {@code item(LivingEntity, Level, ItemStack, int, int, int)} with {@code minecraft.player} and
 * {@code minecraft.level} already filled in, which resolves the stack through
 * {@code Minecraft.getItemModelResolver().updateForTopItem(..., ItemDisplayContext.GUI, ...)}.
 * That call is where the {@code item_model} component is looked up in whichever resource pack
 * is on top -- so a stack carrying Hypixel's component draws Hypixel's art here for exactly the
 * reason it does in the inventory, and it starts doing so the moment the server pack is applied
 * without this class being told. Passing the player also means a model that switches on its
 * holder gets one, which the {@code fakeItem} overload would not have supplied.</p>
 *
 * <p>The consequence runs the other way too, and it is the whole reason {@link DropSymbols}
 * learns components off real drops: a stack synthesised from a bare vanilla item carries no
 * {@code item_model}, so no pack on earth can dress it. This class only guarantees that
 * whatever stack it is given reaches the renderer intact.</p>
 *
 * <h2>Drawing a stack the server wrote</h2>
 *
 * <p>A genuine SkyBlock stack is a far riskier thing to draw than the synthesised vanilla item
 * that used to go here. It is often enchanted, sometimes dyed, and it carries whatever
 * components a server chose to send, pointed at models from a pack this mod has never seen --
 * Hypixel's own pack currently ships at least one the client refuses to parse. Three properties
 * make that safe rather than merely likely to work:</p>
 *
 * <ul>
 *   <li><b>Nothing escapes the window.</b> {@code GuiItemRenderState} is constructed with a
 *       <em>copy</em> of the pose ({@code new Matrix3x2f(pose)}) and with
 *       {@code scissorStack.peek()}, both taken at submit time. The glint, the dye tint and the
 *       model's own depth are therefore clipped by the reel window that was live when the call
 *       was made, and cannot be widened by anything drawn later. A model too big for its
 *       16-pixel slot is not an exception: it is promoted to an {@code OversizedItemRenderState},
 *       which carries the same {@code scissorArea} forward.</li>
 *   <li><b>Nothing is left dirty for the next element.</b> This is structural rather than a
 *       matter of ordering. A GUI item is not drawn into the screen at all -- 26.x renders it
 *       into {@code GuiItemAtlas}, an off-screen texture with its own depth attachment, its own
 *       {@code PoseStack} and its own projection (an oversized one gets a picture-in-picture
 *       texture of its own instead), and what reaches the frame is a flat quad blitted out of
 *       it. Enchantment glint, leather dye, model lighting and depth are baked into that texture
 *       before the GUI is touched. Items are also filed separately from everything else:
 *       {@code addItem} keeps them off the element stream, and the renderer walks them in their
 *       own pass. Neither the captions this class draws next nor any HUD element after it can
 *       inherit a thing.</li>
 *   <li><b>A model that throws costs one sprite, not the session.</b> See {@link #drawSprite}.
 *       </li>
 * </ul>
 *
 * <h2>The two acts</h2>
 *
 * <p>An ordinary roll and a jackpot are no longer the same drawing with a different palette.
 * The ordinary roll always plays out completely plainly -- three reels spinning, locking left to
 * right on the drops the server actually printed, in their own colours, with nothing gold
 * anywhere. Only once it has settled does {@link RollState#JACKPOT_INTRO} begin, and the second
 * act opens on both things at once: the reels break loose again -- faster than act one ever
 * spun them -- and the gold washes in over the top of them while they are already turning. The
 * wash finishes, the reels keep going, and then they land one at a time all showing
 * {@link SlotRoll#jackpotSymbol()}. Three of a kind, which is what a slot machine's payoff has
 * always looked like.</p>
 *
 * <p>That overlap is the core's decision rather than this file's.
 * {@link SlotRoll#reelsAt(long)} unlocks every column on the first instant of act two, so the
 * intro draws as three scrolling strips here for the same reason any other spinning reel does:
 * this class read {@link Reel#locked()} and believed it. What the file has to get right is that
 * nothing <em>else</em> in it still assumes a still picture underneath the gold -- the strip
 * speed, the panel sweep and the rising notes all have to run across the whole spin-up rather
 * than stopping where the wash does.</p>
 *
 * <p>The panel is sized for the second act from the first frame of the first one, so the box
 * never changes size mid-animation. Nothing here is conditional on geometry.</p>
 *
 * <p>The act closes on the one fact about a rare drop the player is actually grinding for.
 * Hypixel appends the Magic Find a rare drop was rolled at to its own banner and the machine used
 * to throw it away; it is now carried through on {@link LootDrop} and drawn in the caption strip's
 * right-hand gutter as the last column lands. It is shown only when the server reported one --
 * most banners carry none, and a "+0%" invented for an absent reading is a claim the player can
 * check and catch. That costs the layout nothing: the strip has always been the full width of the
 * machine with a centred headline on it, so the figure goes in space the panel already had, and
 * the box a roll with a Magic Find is drawn into is the same box to the pixel as the one without.
 * See {@link #drawMagicFind}.</p>
 *
 * <h2>Version portability</h2>
 *
 * <p>Zero Stonecutter comments. The Fabric HUD API ({@code HudElement},
 * {@code HudElementRegistry}, {@code VanillaHudElements}) and every
 * {@code GuiGraphicsExtractor} call used here -- {@code fill}, {@code fillGradient},
 * {@code outline}, {@code text}, {@code centeredText}, {@code item}, {@code enableScissor} and
 * the {@code pose()} stack -- are identical on 26.1.2 and 26.2, verified against both merged
 * jars. The three divergent drawing calls ({@code entity}, {@code skin}, {@code sign}) are
 * deliberately untouched; colours go through {@code TextColor.fromLegacyFormat} rather than the
 * 26.2-only {@code TextColor} constants or the accessors 26.2 stripped off
 * {@link ChatFormatting}; every sound is a {@code SoundEvents} constant present unchanged on
 * both; and nothing here reads {@code Minecraft.gui}, which is where the Gui/Hud split that
 * dominates the rest of 26.x porting would have bitten.</p>
 *
 * <p>F1 (hide GUI) is deliberately not checked by hand. {@code Options.hideGui} is
 * 26.1.2-only and {@code Hud.isHidden()} is 26.2-only, so testing it here would have cost the
 * mod its only version-conditional block; instead the element is registered into the vanilla
 * HUD chain, which the game already skips wholesale when the HUD is hidden.</p>
 */
public final class SlotMachineHud implements HudElement, SkyPrismServices.Hud {

    /** The element's own id, in the mod's namespace so it cannot collide with another mod's. */
    public static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath("skyprism", "diana_slot_machine");

    // --- Geometry --------------------------------------------------------------------
    // One rhythm runs through the panel: the frame padding, the gap between the reels and
    // the caption, and the gap between columns are all four pixels, so no edge of the box is
    // further from its content than any other. The reel window is sized to hold one *cell* --
    // a double-size item sprite with its name under it -- with a sliver of its neighbours
    // showing above and below, which is what makes a spinning column read as a drum rather
    // than as a picture that keeps changing.

    /** Width of one reel column, in unscaled GUI pixels. */
    private static final int REEL_WIDTH = 58;

    /** Gap between two reel columns. */
    private static final int REEL_GAP = 4;

    /** Border between the reels and the edge of the frame. */
    private static final int PADDING = 4;

    /** Edge of the reel window a cell never occupies, so the sprite never touches the border. */
    private static final int WINDOW_INSET = 3;

    /** A vanilla item sprite's own size; {@code item} draws exactly this, unscaled. */
    private static final int SPRITE_PX = 16;

    /** Half of it, in the sprite's own coordinates: {@code item} is drawn about its centre. */
    private static final int SPRITE_HALF = SPRITE_PX / 2;

    /**
     * How much larger than life a reel's item is drawn.
     *
     * <p>Double. At 1x a 16-pixel sprite is lost in a 58-pixel window and reads as an icon
     * beside a label; at 3x it is 48 tall, which forces the window past the height of the
     * caption strip and the panel stops looking like a machine. 32 in 58 leaves the sprite
     * clearly the subject of its column with room for a name under it.</p>
     */
    private static final float SPRITE_SCALE = 2.0f;

    /**
     * The sprite's drawn size, derived rather than written down: every piece of geometry below
     * it is measured from this, so changing {@link #SPRITE_SCALE} moves the cell, the window
     * and the panel together instead of leaving the sprite overhanging a box sized for the old
     * number.
     */
    private static final int SPRITE_DRAWN = (int) (SPRITE_PX * SPRITE_SCALE);

    /** Gap between the bottom of the sprite and the top of its name. */
    private static final int NAME_GAP = 2;

    /** Height of the name caption's inked row; see {@link #NAME_MAX_SCALE}. */
    private static final int NAME_BAND = 6;

    /** One reel cell: the sprite, the gap, and the name under it. */
    private static final int CELL_HEIGHT = SPRITE_DRAWN + NAME_GAP + NAME_BAND;

    /**
     * Vertical pitch of the scrolling strip: exactly one cell.
     *
     * <p>Equal to the cell rather than larger, so that content advancing by one symbol and the
     * strip translating by one pitch are the same event. Any other pitch makes a symbol change
     * somewhere in the middle of its travel, which the eye reads as a glitch rather than as
     * motion -- see {@link #drawSpinningReel}.</p>
     */
    private static final int STRIP_PITCH = CELL_HEIGHT;

    /**
     * Height of the reel window: one whole cell plus a sliver of the cells either side of it.
     */
    private static final int REEL_HEIGHT = CELL_HEIGHT + WINDOW_INSET * 2;

    /** Gap between the bottom of the reels and the top of the caption strip. */
    private static final int CAPTION_GAP = 4;

    /**
     * Height of the caption strip: exactly the height of the jackpot headline, with an
     * ordinary creature name centred inside it.
     */
    private static final int CAPTION_BAND = 12;

    /** Extra height claimed by the creature caption when it is switched on. */
    private static final int CAPTION_HEIGHT = CAPTION_GAP + CAPTION_BAND;

    /** Horizontal breathing room between a name and its window edge, per side. */
    private static final int LABEL_INSET = 2;

    /** The width a drop name has to fit into. */
    private static final int NAME_BUDGET = REEL_WIDTH - LABEL_INSET * 2;

    /**
     * How long one symbol takes to scroll past during the ordinary act.
     *
     * <p>The scroll is presentation, so it is owned here rather than read off
     * {@link Reel#spinPhase()}. Two reasons. The strip's content index has to advance by
     * exactly one at the instant the offset wraps, and a phase alone cannot say how many wraps
     * have happened -- deriving both from the same wall-clock division makes them the same
     * number by construction. And the jackpot re-spin has to visibly run faster than the first
     * spin, which a single core-owned phase cannot express. The core still owns the thing that
     * matters, which is <em>when a reel locks</em>.</p>
     */
    private static final long STRIP_CELL_MILLIS = 150L;

    /**
     * The same, for every column still turning in the second act: more than twice the speed.
     *
     * <p>The second act has to look like the machine being wound up rather than restarted, and
     * speed is the cheapest way to say so. It applies from the first instant of
     * {@link RollState#JACKPOT_INTRO}, because that is when the reels actually break loose.
     * Keying it to {@link RollState#JACKPOT_SPIN} instead left the columns scrolling at act
     * one's leisurely rate underneath the gold and then jumping to this one when the wash
     * finished -- a change of speed with no event under it to explain the change. It holds
     * through {@link RollState#JACKPOT_LOCK} for the mirror-image reason: a column that has not
     * landed yet must not decelerate the instant its neighbour does.</p>
     */
    private static final long JACKPOT_CELL_MILLIS = 65L;

    /** Per-column offset, so the three drums are not in lockstep. */
    private static final long REEL_STRIP_OFFSET_MILLIS = 50L;

    // The symbols a reel scrolls before it locks live in FillerStrip, one strip per LootSource.
    // They were one static array here for as long as the machine was Diana's alone, which is
    // exactly how a Crystal Hollows Control Switch ended up blurring past under a Minos Champion
    // caption: the array was written when Diana was the only source and never generalised, so all
    // sixty-four sources scrolled Diana's loot. See FillerStrip for where the names come from and
    // why the drawing path still never allocates.

    /**
     * Legacy colour letter to packed {@code 0xRRGGBB}, built once.
     *
     * <p>{@link LootDrop#colorCode()} and {@link MythologicalCreature#colorCode()} both hand
     * back a bare Hypixel colour letter. Turning one into an RGB value per reel per frame
     * would be a lookup and an allocation on the hot path, and 26.2 removed most of the
     * obvious ways to do it anyway -- so it is resolved into a flat array at class-init using
     * the one pair of calls ({@code ChatFormatting.getByCode} and
     * {@code TextColor.fromLegacyFormat}) that exists unchanged on both Minecraft versions.</p>
     */
    private static final int[] LEGACY_RGB = buildLegacyTable();

    /** Frame background, behind the configured opacity. */
    private static final int FRAME_RGB = 0x101318;

    /** The same panel once the jackpot act has taken it: darker, and warm rather than blue. */
    private static final int FRAME_HOT_RGB = 0x1C1207;

    /** Sunken reel window, darker than the frame so the columns read as recessed. */
    private static final int WINDOW_RGB = 0x05070A;

    /** Border of the frame and of a reel that has come to rest. */
    private static final int BORDER_RGB = 0x4A5568;

    /** Colour of the drop names on a spinning reel, which are captions rather than results. */
    private static final int FILLER_RGB = 0xB8C2CC;

    /** Fallback for a symbol whose colour letter is missing or is a format rather than a colour. */
    private static final int DEFAULT_TEXT_RGB = 0xFFFFFF;

    // --- The jackpot palette and its timing -------------------------------------------
    // Two colour endpoints and one wall-clock wave drive every animated value in the second
    // act, so the frame, the windows, the prize names and the headline breathe together
    // rather than reading as five separate effects that happen to be yellow. Every one of
    // them is additionally multiplied by `gold`, the act's own 0..1 intensity, which is what
    // lets the whole treatment wash in over the intro instead of switching on.

    /** The cool end of the jackpot breath: a deep, saturated amber. */
    private static final int JACKPOT_AMBER = 0xFF9B10;

    /** The hot end of the jackpot breath: near-white gold. */
    private static final int JACKPOT_GOLD = 0xFFF2A8;

    /** Warm wash laid inside a window, so a prize sits in its own pool of light. */
    private static final int GLOW_RGB = 0xFF8A14;

    /** The glint that crosses a window, the embers that rise off it, and the lock flash. */
    private static final int SPARK_RGB = 0xFFF6CC;

    /** Period of the jackpot breath. Slow enough to read as a pulse rather than a strobe. */
    private static final long PULSE_MILLIS = 760L;

    /** How long one reel's landing burst lasts. */
    private static final long BURST_MILLIS = 420L;

    /**
     * How long the celebration of the third and final match lasts.
     *
     * <p>Longer than a single reel's burst, and it lights the whole panel rather than one
     * window, because the third match is the moment the act exists for.</p>
     */
    private static final long FINALE_MILLIS = 720L;

    /** Period of the glint that crosses a window. */
    private static final long SHINE_MILLIS = 1600L;

    /** Half-width of the glint, in unscaled pixels; it fades linearly to nothing at the edge. */
    private static final int SHINE_HALF_WIDTH = 3;

    /** How many embers rise off a lit window at once, evenly staggered over their life. */
    private static final int SPARKS = 5;

    /** Lifetime of one ember. */
    private static final long SPARK_MILLIS = 1150L;

    /** Size of the jackpot headline relative to the drop names it sits under. */
    private static final float JACKPOT_SCALE = 1.5f;

    /** How much the headline grows at the top of the breath. */
    private static final float JACKPOT_SCALE_PULSE = 0.12f;

    /** How much larger the bloom behind the headline is than the headline itself. */
    private static final float JACKPOT_BLOOM = 1.14f;

    /**
     * The headline's one-off overshoot on the third match.
     *
     * <p>Named rather than written into {@code drawJackpotHeadline}, because the magic-find
     * figure beside the headline has to reserve room for the headline at its very widest and
     * the widest it ever gets is this kick. A number that lives in one method and is depended
     * on by another is exactly how two things end up overlapping on the one frame a year the
     * kick and the pulse peak together.</p>
     */
    private static final float JACKPOT_FINALE_KICK = 0.26f;

    /**
     * The largest the headline is ever drawn, as a multiple of the font's own size.
     *
     * <p>{@code JACKPOT_SCALE} is the steady size, {@code JACKPOT_SCALE_PULSE} is the top of the
     * breath and {@code JACKPOT_FINALE_KICK} is the third-match overshoot; the {@code grown}
     * term in {@code drawJackpotHeadline} only ever scales that down. So this is a genuine
     * upper bound and not an estimate, which is what {@link #drawMagicFind} needs it to be.</p>
     */
    private static final float JACKPOT_SCALE_MAX =
            JACKPOT_SCALE + JACKPOT_SCALE_PULSE + JACKPOT_FINALE_KICK;

    /**
     * How much a sprite overshoots its size at the instant its reel snaps home.
     *
     * <p>The snap is the whole reason a staggered landing reads as three separate events
     * rather than as one row appearing. Small, and squared out over {@link #BURST_MILLIS}.</p>
     */
    private static final float LOCK_KICK = 0.18f;

    /**
     * How many rising notes climb under the second act's spin-up.
     *
     * <p>Spread across the gold wash <em>and</em> the spin that follows it, not across the wash
     * alone: those are one unbroken stretch of moving reels. See {@link #playSounds}.</p>
     */
    private static final int SPINUP_TONES = 8;

    /** How many light sweeps run up the panel across the spin-up, accelerating. */
    private static final int SPINUP_SWEEPS = 2;

    private static final String JACKPOT_TEXT = "JACKPOT";

    /**
     * Caption of last resort, when a roll can name neither a subject nor a source.
     *
     * <p>Unreachable in practice: {@link LootEvent} substitutes its source's display name for a
     * blank subject inside its own constructor, and every roll now carries an event. But a caption
     * strip drawn empty reads as a rendering bug rather than as missing data, so there is a word
     * for the case that should not happen.</p>
     *
     * <p>It used to say "Mythological Ritual", which was correct while the machine only ever spun
     * for Diana and would now be a lie on a fishing streak.</p>
     */
    private static final String IDLE_CAPTION = "SkyBlock";

    /** Only ever written to once per session, from the render-thread guard in the draw path. */
    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/hud");

    /**
     * How far a name may be shrunk before it is ellipsised instead.
     *
     * <p>Half size is the floor because the vanilla font is a 7-pixel-tall bitmap: below
     * about half, glyph stems start landing on the same physical pixel and the word stops
     * being a word. At this floor the {@link #NAME_BUDGET} holds a hundred and eight pixels of
     * text, which is wider than the longest Diana drop name -- see {@link #nameScale}.</p>
     */
    private static final float MIN_LABEL_SCALE = 0.5f;

    /**
     * The largest a drop name is ever drawn.
     *
     * <p>The name is a subtitle now, not the content of the reel: full-size type under a
     * 32-pixel sprite competes with it for the eye and makes the window look crowded. Three
     * quarters puts the inked row at exactly {@link #NAME_BAND} pixels, which is what the cell
     * was measured for.</p>
     */
    private static final float NAME_MAX_SCALE = 0.75f;

    // --- The magic-find figure ----------------------------------------------------------
    //
    // Hypixel appends the magic find that earned a rare drop to its own banner -- "(+240%
    // <star> Magic Find!)" -- and the machine used to throw it away. It is the one number a
    // player who is grinding magic find actually wants off a rare drop, so it belongs on the
    // frame the act exists for.
    //
    // WHICH DROP'S FIGURE. The reveal converges every column on SlotRoll.jackpotSymbol(), so
    // the figure shown is that drop's and no other. A roll can capture several drops and more
    // than one of them can carry a magic find, but only one of them is on the reels: showing
    // any other number would caption a prize with a stranger's statistic. jackpotSymbol() is
    // also the only choice that cannot change once the reels have landed, which is what lets
    // the string be built once (see magicFindLabel) rather than per frame.
    //
    // WHERE IT GOES, AND WHY THE PANEL DOES NOT MOVE. In the right-hand gutter of the caption
    // strip, on the same band as the headline and right-aligned to the frame's inner edge.
    // That is deliberately a place that already exists: JACKPOT is centred and the strip is
    // the full width of the machine, so there is dead space either side of it on every roll
    // the widget has ever drawn. Nothing about the panel's geometry is conditional on the
    // figure -- not height(), not width(), not bandTop -- so a roll that reports magic find
    // and a roll that does not are drawn into pixel-identical boxes, and the ordinary act-one
    // frames are untouched to the byte.

    /**
     * The star drawn beside the figure, and why it is not Hypixel's.
     *
     * <p>Hypixel's current magic-find icon is U+E01A, a private-use codepoint only its own server
     * resource pack has a glyph for; the legacy one is U+272F. Neither is in Minecraft's font.
     * That was checked rather than assumed: {@code assets/minecraft/font/include/default.json}
     * enumerates every codepoint the three bitmap providers cover, and neither U+272F nor U+E01A
     * is among them. So drawing the captured codepoint would put an empty box on the reveal for
     * every player without the pack applied -- and a HUD, unlike chat, has no room to fall back
     * to words. It is the same call {@code ContainerText.itemCaption} and
     * {@code TrophyFishDetector} already make about private-use glyphs.</p>
     *
     * <p>So the machine draws its own star: U+2605 BLACK STAR, which
     * {@code include/default.json} maps into {@code minecraft:font/nonlatin_european.png} at row
     * 56, where the 8-by-8 cell holds a real inked five-pointed star seven pixels wide. That
     * texture is byte-identical in both merged jars, so the glyph is guaranteed on 26.1.2 and
     * 26.2 alike with no resource pack at all. It reads as Hypixel's idiom -- a star and a
     * percentage -- without depending on a codepoint Hypixel has already moved once.</p>
     *
     * <p>It is spelled into the translation string rather than concatenated here, so a translator
     * whose font does not carry it can replace it with a word. The string's one argument is
     * {@link LootDrop.MagicFind#format()}, which is the reading with its sign exactly as Hypixel
     * sent it -- {@code "+240%"} or {@code "+240"}, because the server emits both and inventing
     * the missing {@code %} would be inventing a unit. Deciding that is the parser's job and the
     * widget does not second-guess it.</p>
     *
     * <p>The words "Magic Find" are deliberately not on the strip. They do not fit: the figure
     * lives in the gutter beside a headline that is half again the size of everything else, and
     * spelling the stat out needs the band twice over at any size the vanilla bitmap font is
     * still readable at. The star and the percentage are Hypixel's own shorthand for it, which is
     * what the players who care about this number already read it as.</p>
     */
    private static final String MAGIC_FIND_KEY = "skyprism.hud.jackpot.magic_find";

    /**
     * Size of the figure, relative to the font.
     *
     * <p>The same size as a prize name, because that is what it is: a caption on the item the
     * reels landed on. It is secondary by the two means that do not cost a pixel of layout --
     * it is less than half the height of the headline it sits beside, and it is the only aqua
     * thing on a gold panel, so it reads as a stat rather than as part of the announcement.</p>
     */
    private static final float MAGIC_FIND_SCALE = NAME_MAX_SCALE;

    /** Clear air between the headline at its widest and the figure. */
    private static final int MAGIC_FIND_GAP = 4;

    /**
     * Hypixel's own aqua, which is the colour it prints every magic-find suffix in.
     *
     * <p>Not read out of {@link #LEGACY_RGB}: this is a fixed piece of the design rather than a
     * colour some line happened to carry, and resolving it through the drop's colour code would
     * make it change with the rarity of the prize.</p>
     */
    private static final int MAGIC_FIND_RGB = 0x55FFFF;

    /**
     * How long the figure takes to fade in behind the third match.
     *
     * <p>It arrives on the finale rather than on {@link RollState#JACKPOT_HOLD}, so that it is
     * revealed by an event the player can see -- the last column landing -- instead of
     * appearing at a phase boundary that has no picture attached to it. Short, because it is
     * arriving into a frame that is already flashing.</p>
     */
    private static final long MAGIC_FIND_REVEAL_MILLIS = 320L;

    /**
     * Slack allowed when asking whether a name overflows its budget.
     *
     * <p>{@link #nameScale} picks the scale as {@code budget / width(worst)} in {@code float}
     * arithmetic, so the very name that set the scale measures back at {@code budget} plus a
     * rounding epsilon. A bare {@code >} therefore sent precisely that name -- the longest one
     * on the machine -- down {@link #drawFitted}'s shortening branch, which is why every spin
     * showed one filler symbol ellipsised while the shorter names beside it were untouched.
     * Half a pixel is far below anything the eye can see and far above the error.</p>
     */
    private static final float FIT_EPSILON = 0.5f;

    /**
     * Height of the inked part of a line of vanilla text, as distinct from {@code lineHeight}.
     *
     * <p>{@code Font.lineHeight} is 9 and includes the row of leading below the glyphs; the
     * glyphs themselves fill 8. Vanilla centres a label in a widget with this number rather
     * than with {@code lineHeight}, and so does every caption here.</p>
     */
    private static final int GLYPH_HEIGHT = 8;

    /** Alpha below which a sprite is no longer worth submitting; the panel is all but gone. */
    private static final int SPRITE_CUTOFF_ALPHA = 8;

    /**
     * How many distinct stacks may fail to render before sprites are given up on entirely.
     *
     * <p>Small on purpose. One broken item model is a bad row in somebody's resource pack; nine
     * of them in one machine is the renderer itself being unusable, and at that point drawing
     * captions is the honest answer.</p>
     */
    private static final int MAX_POISONED_SPRITES = 8;

    /**
     * Stacks whose model threw when it was submitted, compared by identity and never
     * dereferenced.
     *
     * <p>Render-thread only and never read while empty, exactly like a {@link FillerStrip}'s own
     * sprite array, so it deliberately carries no synchronisation. {@link DropSymbols} hands out
     * one shared stack
     * per symbol, so identity is the right test and this array is the right size: it is keyed on
     * the object that failed, not on a name that might be spelled two ways.</p>
     */
    private static final Object[] POISONED = new Object[MAX_POISONED_SPRITES];

    /** How much of {@link #POISONED} is in use; zero for the whole of a healthy session. */
    private static int poisonedCount;

    /** Set when {@link #POISONED} overflowed: every sprite is a caption from then on. */
    private static boolean spritesGivenUp;

    /** The one instance; a HUD element is registered once and lives for the session. */
    private static final SlotMachineHud INSTANCE = new SlotMachineHud();

    private static boolean registered;

    /**
     * Width of {@link #JACKPOT_TEXT} in the vanilla font, resolved on first use.
     *
     * <p>Same reasoning as {@link FillerStrip#labelScale()}: the string is a compile-time
     * constant, so
     * re-measuring it -- which decomposes it glyph by glyph -- on every frame of every jackpot
     * was paying for an answer that cannot change.
     */
    private static int jackpotTextWidth;

    /**
     * The live roll.
     *
     * <p>Re-read when {@link DianaController#rollEpoch()} moves, which is the only signal that
     * actually tracks the swap -- see {@link #extractRenderState}.
     */
    private volatile SlotRoll roll;

    /** The controller's roll generation when {@link #roll} was last read. */
    private int rollEpoch = -1;

    /**
     * {@link SlotRoll#rollId()} of the roll the per-roll fields below belong to.
     *
     * <p>Not a boolean "was active last frame": {@code SlotRoll.start} deliberately restarts over a
     * running roll, so a second kill inside the settle phase -- or a second
     * {@code /skyprism simulate} -- leaves the machine active on both sides of the transition and
     * an observer watching activity alone sees nothing happen. That is exactly when the flags below
     * must be cleared, and when they were not: the jackpot sting had already been announced so it
     * never played again, and the reveal timestamps were already spent so the flashes, the burst
     * rings and the headline's kick were all skipped and the act appeared pre-settled.
     */
    private long lastRollId = -1L;

    /** How many reels had locked last frame in the first act, so a lock becomes exactly one click. */
    private int lockedLastFrame;

    /** The same counter for the second act, which locks its own reels all over again. */
    private int jackpotLockedLastFrame;

    /** How many of the spin-up's rising notes have played, so each fires exactly once. */
    private int spinUpTonesPlayed;

    /** Whether the celebration sting has already played for the current roll. */
    private boolean finaleAnnounced;

    /**
     * Whether this roll has entered the jackpot act at all.
     *
     * <p>Latched, because {@link RollState#FADING} is shared by both acts and has to fade out a
     * gold machine after a jackpot and a plain one after an ordinary roll. Nothing else reads
     * it, and in particular the first act never does: it is set the first time a
     * {@code JACKPOT_*} state is observed, which is strictly after the first act has settled.</p>
     */
    private boolean jackpotSeen;

    /** Set once a draw has thrown, so the guard below reports it exactly one time. */
    private boolean drawFailureReported;

    /** Wall-clock millis at which the roll was first observed fading, or 0 when it is not. */
    private long fadeStartedAt;

    /** Wall-clock millis of the third and final match, or 0 before it. */
    private long finaleAt;

    /**
     * Wall-clock millis at which the second act was first observed, or 0 before it.
     *
     * <p>The zero point of {@link #spinUpProgress}, which is the one progress value in the file
     * that has to span two phases. It is stamped rather than derived because the instant act two
     * begins is not something {@link SlotRoll} publishes, and it is stamped on the same frame as
     * {@link #jackpotSeen} -- which is why {@link #hasPerRollState} can get away with not reading
     * it, exactly as it gets away with not scanning {@link #jackpotLockAt}.</p>
     */
    private long actTwoStartedAt;

    /**
     * Wall-clock millis at which each reel was first seen locked <em>in the jackpot act</em>,
     * or 0.
     *
     * <p>One stamp per reel, written once, never an accumulator: a landing burst is derived as
     * {@code now - this}, so it runs for exactly {@link #BURST_MILLIS} of real time however many
     * frames happen to land inside that window. Per reel rather than one shared stamp because
     * the whole point of the act is that the three landings are separate events.</p>
     */
    private long[] jackpotLockAt = new long[0];

    /**
     * This frame's locked-reel names, one per column, reused across frames.
     *
     * <p>{@code label(LootDrop)} concatenates a string whenever a drop stacked -- and Diana drops
     * coins on nearly every kill, so that is the common case -- and it used to be called twice per
     * reel per frame, once to choose the shared type size and again to draw, with {@code Font.width}
     * re-decomposing the result each time. A locked reel's name cannot change, so it is built once
     * per frame here and passed to both.
     */
    private String[] labels = new String[0];

    /**
     * The jackpot prize's magic-find figure, already formatted, or null when it reported none.
     *
     * <p>Built exactly once per roll -- see {@link #magicFindLabel} -- because
     * {@link SlotRoll#jackpotSymbolAt} cannot change once the act has begun and because
     * {@code Component.translatable(...).getString()} allocates a component, a decomposed
     * format and a string. Doing that per frame for the length of a hold is three allocations
     * a frame for several seconds, to arrive at the same answer every time.</p>
     */
    private String magicFindText;

    /**
     * The symbols this roll's spinning reels scroll, resolved once from the roll's own source.
     *
     * <p>Per roll rather than per frame because {@code SlotRoll.sourceAt} is a sweep and a field
     * read and {@link FillerStrip#of} is an array load, and doing both on every frame of every
     * spin to arrive at the same answer is the sort of cost the class javadoc's performance
     * contract exists to refuse. A roll cannot change source once it is running -- a second event
     * restarts it, which bumps {@code rollId} and clears this along with everything else.</p>
     *
     * <p>Deliberately absent from {@link #hasPerRollState}, which is a six-field read on the idle
     * path and worth keeping that way. Nothing goes wrong if a finished roll leaves its strip
     * behind: the reference is to an immutable session-lived singleton, nothing reads it while the
     * machine is idle, and the {@code rollId} check at the top of {@link #extractRenderState}
     * clears it before the next roll's first frame is ever drawn.</p>
     */
    private FillerStrip strip;

    /**
     * Whether {@link #magicFindText} has been worked out for this roll.
     *
     * <p>Separate from the field itself because null is a real answer -- most rare drops carry
     * no magic find -- and without this the no-magic-find case would re-ask the question on
     * every frame, which is the one case the caching exists for.</p>
     *
     * <p>Deliberately absent from {@link #hasPerRollState}, on the same argument that leaves
     * {@code actTwoStartedAt} out of it: it is only ever written from inside the second act, on
     * a frame where {@link #jackpotSeen} has already been latched, so a resolved figure cannot
     * outlive a cleared flag.</p>
     */
    private boolean magicFindResolved;

    private SlotMachineHud() {
    }

    /** @return the single HUD element instance */
    public static SlotMachineHud get() {
        return INSTANCE;
    }

    /**
     * Registers the element with Fabric and binds it to the command tree.
     *
     * <p>Attached <em>before</em> {@code VanillaHudElements.CHAT}, which in this API means
     * "drawn underneath chat". That is the right way round: the machine is a flourish and
     * chat is information, so a spin must never obscure a line the player is reading.</p>
     *
     * <p>Idempotent, because the mod initialiser is exactly the sort of place a second call
     * gets added by accident, and a doubly-registered element would draw twice at double
     * opacity for no visible reason.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ELEMENT_ID, INSTANCE);

        // Prime the cached reference. Keeping it exact afterwards is the epoch check on the idle
        // path, not this call and not a config listener -- see the class javadoc.
        INSTANCE.refreshRoll();

        SkyPrismServices.setHud(INSTANCE);
    }

    /** Re-reads the controller's current roll. Cheap, and only ever called off the hot path. */
    private void refreshRoll() {
        DianaController controller = DianaController.get();
        try {
            // roll() can install a new roll on the spot -- the first call of the session always
            // does -- so the epoch is read after it, never before, or the very first read would
            // record a generation the reference has already moved past.
            SlotRoll current = controller.roll();
            int epoch = controller.rollEpoch();
            if (current != this.roll) {
                // A different object cannot share the previous one's presentation state, and its
                // own rollId counts from zero, so a fresh roll could otherwise land on the same
                // id the retired one last had and slip past the restart check below.
                lastRollId = -1L;
                resetPerRollState();
            }
            this.roll = current;
            this.rollEpoch = epoch;
        } catch (RuntimeException failed) {
            // A settings change must never be the thing that takes the HUD down; the previous
            // reference stays valid and keeps drawing the roll it already knows about.
        }
    }

    // --- HudElement ------------------------------------------------------------------

    /**
     * Draws one frame of the machine, or -- overwhelmingly the common case -- nothing at all.
     *
     * <p>The opening lines are the performance contract described on the class: a field read
     * and an enum comparison. Everything else in this method is unreachable while Diana is
     * not actively paying out, the jackpot act included.</p>
     *
     * @param graphics the frame's draw-command recorder
     * @param delta    unused: the animation is driven by the core's clock rather than by the
     *                 render delta, so that a slow frame cannot desynchronise the reels from
     *                 the chat lines they are locking onto
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        SlotRoll current = this.roll;
        long now = current == null ? 0L : current.nowMillis();
        if (current == null || !current.activeAt(now)) {
            if (Metrics.enabled()) {
                Metrics.hudSkip();
            }
            if (hasPerRollState()) {
                resetPerRollState();
            }
            // The idle path, and the only place the cached reference is re-checked: an int
            // compare, on the branch that runs for essentially every frame the game ever draws.
            // It has to be here rather than on a config listener because the controller defers a
            // roll rebuild past the settings change that asked for it -- see the class javadoc.
            if (current == null || rollEpoch != DianaController.get().rollEpoch()) {
                refreshRoll();
            }
            return;
        }

        // A restart over a running roll is invisible to activeAt, so the per-roll flags are keyed
        // on the roll's own counter instead. Checked before anything reads them.
        if (lastRollId != current.rollId()) {
            lastRollId = current.rollId();
            resetPerRollState();
        }

        long startedNanos = Metrics.enabled() ? System.nanoTime() : 0L;
        try {
            draw(graphics, current, now);
        } catch (RuntimeException | LinkageError broken) {
            // A cosmetic overlay must never be able to take the render thread down. Drop the
            // roll rather than throwing again on every frame for the rest of the session.
            //
            // Say so, exactly once. Swallowing this silently is what a render-thread guard is
            // for; swallowing it *quietly* is not, and it cost a debugging session -- a throw in
            // here looks from the outside exactly like a roll that never started, because the
            // reset below makes it one. Once per session, because the alternative on a genuinely
            // broken frame is a log line per frame forever.
            if (!drawFailureReported) {
                drawFailureReported = true;
                LOGGER.error("SkyPrism slot machine failed to draw; the roll has been dropped and "
                        + "the widget will stay hidden until the next one starts", broken);
            }
            current.reset();
            resetPerRollState();
        }
        if (startedNanos != 0L) {
            Metrics.hudFrame(System.nanoTime() - startedNanos);
        }
    }

    /**
     * Whether anything is left over from a roll that has finished.
     *
     * <p>Six scalar field reads on the idle path, and deliberately not a scan of
     * {@link #jackpotLockAt}: the stamps in it are only ever written while
     * {@link #jackpotLockedLastFrame} is being written too, so the counter standing at zero is
     * proof the array is clear. {@link #actTwoStartedAt} is left out on the same argument -- it
     * is written on the one frame {@link #jackpotSeen} latches, so a stamp there cannot outlive
     * a cleared flag here.</p>
     */
    private boolean hasPerRollState() {
        return fadeStartedAt != 0L || lockedLastFrame != 0 || jackpotLockedLastFrame != 0
                || spinUpTonesPlayed != 0 || finaleAt != 0L || jackpotSeen;
    }

    private void resetPerRollState() {
        lockedLastFrame = 0;
        jackpotLockedLastFrame = 0;
        spinUpTonesPlayed = 0;
        finaleAnnounced = false;
        jackpotSeen = false;
        fadeStartedAt = 0L;
        finaleAt = 0L;
        actTwoStartedAt = 0L;
        magicFindText = null;
        magicFindResolved = false;
        strip = null;
        Arrays.fill(jackpotLockAt, 0L);
    }

    /**
     * The strip this roll's spinning reels scroll.
     *
     * <p>Resolved on the first frame that needs it rather than when the roll is installed, because
     * the roll is installed from the chat thread and this is the render thread's own answer to a
     * render-thread question. Null source -- a roll that has just stopped running, which a frame
     * can land on either side of -- takes {@link FillerStrip#unknown()} rather than Diana's.</p>
     */
    private FillerStrip strip(SlotRoll current, long now) {
        FillerStrip cached = this.strip;
        if (cached != null) {
            return cached;
        }
        FillerStrip resolved = FillerStrip.of(current.sourceAt(now));
        this.strip = resolved;
        return resolved;
    }

    private void draw(GuiGraphicsExtractor graphics, SlotRoll current, long now) {
        SkyPrismConfig config = ConfigManager.get().config();
        SkyPrismConfig.HudSettings hud = config.hud;
        // Only the HUD's own switch, now that the machine is not Diana's alone.
        //
        // This used to also require config.diana.enabled, which was right when a Diana kill was
        // the only thing that could start a roll and is wrong now that a dungeon chest can: it
        // would have made the Mythological Ritual toggle silently switch off every other source
        // in the game. Diana is still governed by that flag where it belongs -- DianaController
        // reads it before a Diana roll can ever start -- so nothing has been loosened, only moved
        // to the one place that owns it.
        //
        // The settings under config.diana that this method goes on to read are the roll's
        // *timings* rather than its trigger. They keep their historical name because renaming a
        // config key silently resets everyone's saved settings.
        if (!hud.enabled) {
            return;
        }

        // `now` is the roll's own clock, read once in extractRenderState and threaded through
        // everything below. Every animated value here is a pure function of it, which is what
        // keeps both acts framerate-independent and stops two effects that are meant to breathe
        // together from sampling different instants -- including the roll's own reel states,
        // which are asked for at the same instant rather than at a later one.
        RollState state = current.stateAt(now);
        int alpha = fadeAlpha(state, config, now);
        if (alpha <= 0) {
            return;
        }

        if (isJackpotAct(state)) {
            jackpotSeen = true;
            if (actTwoStartedAt == 0L) {
                actTwoStartedAt = now;
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        List<Reel> reels = current.reelsAt(now);
        boolean caption = hud.showCreatureName;
        boolean names = hud.showDropNames;

        // How far into the second act the look has come: 0 for the whole of the ordinary roll,
        // ramping over the intro, and pinned at 1 for the rest. Every gold value below is
        // multiplied by it, which is what makes "nothing gold before JACKPOT_INTRO" a property
        // of one number rather than of a branch in twenty places.
        double gold = goldLevel(current, state, now);

        // How far the reels have come through act two's free spin: 0 as they break loose, 1 as
        // the first column lands. Deliberately not the same number as `gold`, which is full a
        // third of the way through that motion -- anything riding the movement rather than the
        // colour reads this instead, and there are two such things left: the panel sweep and
        // the rising notes.
        double spinUp = spinUpProgress(config, state, now);

        trackJackpotLocks(reels, state, now);
        playSounds(minecraft, reels, state, config, spinUp);

        int boxWidth = width(reels.size());
        int boxHeight = height(caption);
        double scale = clamp(hud.scale, SkyPrismConfig.HudSettings.MIN_SCALE,
                SkyPrismConfig.HudSettings.MAX_SCALE);

        float left = (float) hud.anchor.topLeftX(graphics.guiWidth(), boxWidth * scale, hud.x);
        float top = (float) hud.anchor.topLeftY(graphics.guiHeight(), boxHeight * scale, hud.y);

        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(left, top);
            graphics.pose().scale((float) scale, (float) scale);
            drawFrame(graphics, font, current, reels, hud, boxWidth, boxHeight, caption, names,
                    alpha, now, state, gold, spinUp);
        } finally {
            // popMatrix in a finally: a throw inside the drawing must not leave every later
            // HUD element -- hotbar, chat, everything after us -- drawn translated and scaled.
            graphics.pose().popMatrix();
        }
    }

    /**
     * The jackpot act's intensity, 0 to 1, and the single switch behind every gold pixel.
     *
     * <p>Zero through the whole ordinary roll, which is the change the player asked for: the
     * first act now looks exactly as it would have if the drop had been common. It ramps across
     * {@link RollState#JACKPOT_INTRO} on the roll's own
     * {@link SlotRoll#jackpotIntroProgressAt(long)}, asked at the frame's own instant like every
     * other question here, and smoothed so the wash eases in and out rather than arriving at a
     * constant rate. It stays pinned at one for the rest of the act.</p>
     *
     * <p>{@link RollState#FADING} is the one state shared by both acts, so it answers off
     * {@link #jackpotSeen} -- a jackpot must not lose its gold on the way out, and an ordinary
     * roll must not gain any.</p>
     */
    private double goldLevel(SlotRoll current, RollState state, long now) {
        switch (state) {
            case JACKPOT_INTRO:
                return smoothstep(clamp(current.jackpotIntroProgressAt(now), 0.0, 1.0));
            case JACKPOT_SPIN:
            case JACKPOT_LOCK:
            case JACKPOT_HOLD:
                return 1.0;
            case FADING:
                return jackpotSeen ? 1.0 : 0.0;
            default:
                return 0.0;
        }
    }

    /**
     * How far act two's free spin has come, 0 to 1, across the wash and the spin together.
     *
     * <p>The only progress value in the file that spans two phases, and it spans them because
     * the thing it measures does: from the first instant of {@link RollState#JACKPOT_INTRO}
     * until the first column lands the reels turn continuously, and an effect that rides that
     * motion has to run for all of it rather than stopping where the colour stops.
     * {@link SlotRoll#jackpotIntroProgressAt(long)} cannot serve -- it pins at 1 as soon as the
     * gold is fully in, which on the shipped timings is a third of the way through the
     * movement.</p>
     *
     * <p>Measured from {@link #actTwoStartedAt} against the two configured durations, clamped
     * here exactly as {@code DianaSettings.toRollConfig} clamps them so the two cannot disagree
     * about an absurd file. Reading the settings rather than the roll is a compromise the roll
     * forces: it publishes no durations, and the controller defers a rebuild while a roll is on
     * screen. So a player who retunes the jackpot mid-celebration paces this one frame's notes
     * off the settings the <em>next</em> roll will use -- which costs note spacing on a single
     * roll and nothing else. No gold value, no landing and no piece of geometry reads it.</p>
     *
     * @return 0..1 inside the free spin; 0 before act two and 1 once the landings have begun
     */
    private double spinUpProgress(SkyPrismConfig config, RollState state, long now) {
        if (state != RollState.JACKPOT_INTRO && state != RollState.JACKPOT_SPIN) {
            return isJackpotAct(state) ? 1.0 : 0.0;
        }
        long window = clampMillis(config.diana.jackpotIntroMillis,
                        SkyPrismConfig.DianaSettings.MAX_JACKPOT_INTRO_MILLIS)
                + clampMillis(config.diana.jackpotSpinMillis,
                        SkyPrismConfig.DianaSettings.MAX_JACKPOT_SPIN_MILLIS);
        if (window <= 0L || actTwoStartedAt == 0L) {
            // Only reachable when the settings moved out from under a running roll, since a
            // roll cannot be in either of those two states with both durations at zero.
            return 1.0;
        }
        return clamp((now - actTwoStartedAt) / (double) window, 0.0, 1.0);
    }

    /** A duration from the settings bag, clamped the way {@code toRollConfig} clamps it. */
    private static long clampMillis(long value, long max) {
        return Math.max(0L, Math.min(value, max));
    }

    /** @return whether {@code state} is one of the four states of the second act */
    private static boolean isJackpotAct(RollState state) {
        return state == RollState.JACKPOT_INTRO || state == RollState.JACKPOT_SPIN
                || state == RollState.JACKPOT_LOCK || state == RollState.JACKPOT_HOLD;
    }

    /**
     * Stamps the instant each reel lands in the second act, and the instant the last one does.
     *
     * <p>Runs for the whole of act two, the wash included, and needs no phase test to stay
     * honest: the core unlocks every column on the first instant of the act and locks none of
     * them again until the spin is over, so there is simply nothing to stamp before the first
     * landing. The test below is an early-out for act one and the fade rather than a
     * correctness fence. It <em>was</em> a fence -- act one's locked reels used to survive into
     * the wash, and stamping those flashed all three windows the moment the gold arrived -- and
     * leaving it phrased as one would now be describing a machine this file no longer draws.</p>
     */
    private void trackJackpotLocks(List<Reel> reels, RollState state, long now) {
        if (!isJackpotAct(state)) {
            return;
        }
        int count = reels.size();
        if (jackpotLockAt.length < count) {
            jackpotLockAt = new long[count];
        }
        int locked = 0;
        for (int i = 0; i < count; i++) {
            if (!reels.get(i).locked()) {
                continue;
            }
            locked++;
            if (jackpotLockAt[i] == 0L) {
                jackpotLockAt[i] = now;
            }
        }
        if (locked >= count && count > 0 && finaleAt == 0L) {
            finaleAt = now;
        }
    }

    private void drawFrame(GuiGraphicsExtractor graphics, Font font, SlotRoll current,
                           List<Reel> reels, SkyPrismConfig.HudSettings hud,
                           int boxWidth, int boxHeight, boolean caption, boolean names,
                           int alpha, long now, RollState state, double gold, double spinUp) {
        double wave = gold > 0.0 ? pulseWave(now) : 0.0;
        int hot = gold > 0.0
                ? lerpRgb(BORDER_RGB, lerpRgb(JACKPOT_AMBER, JACKPOT_GOLD, wave), gold)
                : BORDER_RGB;
        float finale = finaleProgress(now);

        if (hud.drawBackground) {
            // The panel deepens as the act takes hold: warmer, and a little more solid, so the
            // gold on top of it has something dark to be gold against.
            int backdrop = (int) Math.round(
                    clamp(hud.backgroundOpacity * (1.0 + 0.35 * gold), 0.0, 1.0) * alpha);
            graphics.fill(0, 0, boxWidth, boxHeight,
                    (backdrop << 24) | lerpRgb(FRAME_RGB, FRAME_HOT_RGB, gold));
        }

        // The rising shimmer, and only ever while the reels are free: light running up the
        // machine as it winds itself back up. It accelerates with the spin-up, so the last
        // sweep travels several times faster than the first, and it runs out exactly as the
        // first column lands -- the loudest event in the act, and so the one place the sweep
        // can stop without the stop itself being the visible thing. Keyed to JACKPOT_INTRO
        // alone it ended where the wash did, which is now a boundary nothing else crosses: the
        // reels are turning on both sides of it, so the bands just vanished.
        if (state == RollState.JACKPOT_INTRO || state == RollState.JACKPOT_SPIN) {
            drawSpinUpSweep(graphics, boxWidth, boxHeight, alpha, gold, spinUp);
        }

        // What this roll's unlocked reels scroll: the rolling source's own loot, resolved once for
        // the roll. A dungeon chest scrolls dungeon chest drops and a trophy fish scrolls fish.
        FillerStrip strip = strip(current, now);

        // The item a jackpot converges on. Null outside the second act, which is what keeps the
        // ordinary roll's strip generic and its windows plain.
        LootDrop jackpotDrop = isJackpotAct(state) ? current.jackpotSymbolAt(now) : null;
        String jackpotLabel = jackpotDrop == null ? null : label(jackpotDrop);
        String jackpotKey = jackpotDrop == null ? null : jackpotDrop.itemName();
        ItemStack jackpotIcon = jackpotDrop == null ? null : DropSymbols.iconFor(jackpotDrop);

        // Each locked reel's name, built exactly once for this frame; the type size and the
        // draw both read it from here.
        buildLabels(reels);

        // One type size for every name on the machine this frame. Fitting each to its own column
        // stopped names being shortened, but bought that with three reels at three sizes and
        // with a spinning reel whose captions changed size as they scrolled past; uniformity is
        // what makes the sizing read as designed rather than as a bug. The jackpot's own name is
        // folded in even while its reels are still spinning, so nothing resizes when they land.
        float nameSize = nameScale(font, reels, jackpotLabel, strip);

        for (int i = 0; i < reels.size(); i++) {
            int left = PADDING + i * (REEL_WIDTH + REEL_GAP);
            drawReel(graphics, font, reels.get(i), labels[i], left, PADDING, alpha, nameSize,
                    names, now, state, gold, wave, hot, jackpotIcon, jackpotLabel, jackpotKey,
                    strip);
        }

        // The third match, lighting the whole machine rather than one window. It is the only
        // effect in the file that covers the panel edge to edge, because it is the only moment
        // that is about the machine rather than about a reel.
        if (finale < 1.0f) {
            float remaining = 1.0f - finale;
            int flash = (int) Math.round(alpha * 0.45 * remaining * remaining);
            if (flash > 3) {
                graphics.fill(0, 0, boxWidth, boxHeight, (flash << 24) | SPARK_RGB);
            }
        }

        // The frame last, so gold sits over the reel windows where they meet the padding. A
        // jackpot gets two rings rather than one: a single-pixel outline carries the same
        // weight as the idle frame, and weight is most of what makes a border read as loud.
        graphics.outline(0, 0, boxWidth, boxHeight, (alpha << 24) | hot);
        if (gold > 0.0) {
            int inner = (int) Math.round(alpha * gold * (0.45 + 0.35 * wave));
            if (inner > 3) {
                graphics.outline(1, 1, boxWidth - 2, boxHeight - 2,
                        (inner << 24) | lerpRgb(JACKPOT_GOLD, JACKPOT_AMBER, wave));
            }
        }

        if (caption) {
            drawCaption(graphics, font, current, boxWidth, alpha, now, gold, wave, finale,
                    magicFindLabel(jackpotDrop), magicFindReveal(now));
        }
    }

    /**
     * The caption strip: the subject of whatever paid out, the JACKPOT headline, or the hand-over
     * between them.
     *
     * <p>The two do not swap on a frame. Across the intro the subject fades out over the first
     * stretch and the headline fades and grows in over the rest, overlapping slightly in the
     * middle, so the strip reads as one act giving way to another rather than as a label being
     * replaced. None of that timing moved when the machine went SkyBlock-wide; only what the
     * strip says did.</p>
     *
     * <h2>What generalised, and what deliberately did not</h2>
     *
     * <p><b>The text</b> is now {@code LootEvent.subject()} -- "Minos Inquisitor", "Voidgloom
     * Seraph IV", "Obsidian Chest", "Blue Shark", "Tree Gift" -- rather than a creature's name.
     * {@link LootEvent} guarantees that is never blank: a detector that produces no subject gets
     * its source's display name substituted in the event's own constructor, so a Kuudra whose tier
     * could not be read still captions "Kuudra" rather than nothing.</p>
     *
     * <p><b>The colour</b> hints at where the roll came from, because the strip is one line and the
     * brief's constraint is that its geometry does not move. A second row naming the category would
     * have been clearer and would also have been a different widget. So {@link SourceCategory}
     * tints it instead: gold for something opened, aqua for something mined, blue for something
     * caught. That is learnable in two rolls and costs no pixels.</p>
     *
     * <p><b>Diana keeps its own colour</b>, and its branch is checked first. A Diana roll carries a
     * live {@code MythologicalCreature} whose colour code is what the shipped, live-verified
     * caption drew, and reading the category instead would have repainted the one path that was
     * verified against the real server. The creature lookup is also still exactly one call rather
     * than two, for the reason the original comment gives: the {@code Optional}-returning form
     * allocates, and this is inside a draw.</p>
     */
    private static void drawCaption(GuiGraphicsExtractor graphics, Font font, SlotRoll current,
                                    int boxWidth, int alpha, long now, double gold, double wave,
                                    float finale, String magicFind, float magicFindReveal) {
        int bandTop = PADDING + REEL_HEIGHT + CAPTION_GAP;

        // The subject holds the strip alone until the wash is nearly half in, then leaves.
        double subjectFade = 1.0 - clamp(gold / 0.45, 0.0, 1.0);
        if (subjectFade > 0.02) {
            int a = (int) Math.round(alpha * subjectFade);
            if (a > 3) {
                // Two lookups, not four: the text and the colour both come off these, and the
                // Optional-returning forms of each allocate. This runs every frame of act one.
                LootEvent event = current.eventAt(now);
                MythologicalCreature creature = current.creatureAt(now);

                String text = event == null || event.subject().isEmpty()
                        ? IDLE_CAPTION
                        : event.subject();

                // Diana first and unchanged: its creature's own colour is what the shipped,
                // live-verified caption drew. Everything else is tinted by its category. Both
                // routes end at rgbOf, so there is one colour table in this file and no second
                // path that could drift from it.
                int rgb;
                if (creature != null) {
                    rgb = rgbOf(creature.colorCode());
                } else if (event != null) {
                    rgb = rgbOf(SourceCategory.of(event.source()).colorCode());
                } else {
                    rgb = DEFAULT_TEXT_RGB;
                }

                graphics.centeredText(font, text, boxWidth / 2,
                        bandTop + (CAPTION_BAND - GLYPH_HEIGHT) / 2, (a << 24) | rgb);
            }
        }

        double headline = clamp((gold - 0.35) / 0.65, 0.0, 1.0);
        if (headline > 0.02) {
            drawJackpotHeadline(graphics, font, boxWidth, bandTop, alpha, wave, finale, headline);
            drawMagicFind(graphics, font, magicFind, boxWidth, bandTop, alpha, magicFindReveal);
        }
    }

    /**
     * Light running up the panel for as long as act two's reels are free.
     *
     * <p>Two bands, half a cycle apart, on a rate that climbs with the spin-up -- so the machine
     * visibly winds up rather than pulsing at a constant speed. Each is a short gradient that is
     * transparent at its trailing edge, which is what makes it read as a sweep rather than as a
     * bar sliding about.</p>
     *
     * <p>Two different numbers drive it, and the split is the whole point. The <em>travel</em>
     * comes off {@code spinUp}, so the bands keep accelerating for the entire stretch the reels
     * are turning and are still climbing when the first column lands. The <em>brightness</em>
     * comes off {@code gold}, so the sweep fades in with the wash and, like every other gold
     * value in the file, is multiplied by zero for the whole of act one.</p>
     */
    private static void drawSpinUpSweep(GuiGraphicsExtractor graphics, int boxWidth, int boxHeight,
                                        int alpha, double gold, double spinUp) {
        // spinUp * (2 + 3 * spinUp): no sweeps as the reels break loose, five by the landing.
        double travel = spinUp * (2.0 + 3.0 * spinUp);
        for (int k = 0; k < SPINUP_SWEEPS; k++) {
            double phase = travel + k / (double) SPINUP_SWEEPS;
            phase -= Math.floor(phase);
            int y = boxHeight - (int) Math.round(phase * boxHeight);
            int a = (int) Math.round(alpha * 0.26 * gold * (1.0 - phase));
            if (a <= 3) {
                continue;
            }
            int band = Math.max(2, boxHeight / 12);
            int topY = Math.max(0, y - band);
            if (topY < y) {
                // Bright at the top edge, fading downward: the band is travelling upward, so
                // the lit edge has to be its leading one or the sweep reads as sliding
                // backwards against its own direction.
                graphics.fillGradient(0, topY, boxWidth, y, (a << 24) | SPARK_RGB, GLOW_RGB);
            }
        }
    }

    // --- One column ---------------------------------------------------------------------

    /**
     * Draws one column.
     *
     * <p>A locked reel is one item sprite with its name under it. A spinning one is a strip of
     * cells scrolled past the window and clipped to it, so the eye reads continuous motion.
     * Everything gold in here is multiplied by {@code gold}, so an ordinary roll takes the plain
     * branch of every decision without a single test for which act is running.</p>
     */
    private void drawReel(GuiGraphicsExtractor graphics, Font font, Reel reel, String label,
                          int left, int top, int alpha, float nameSize, boolean names,
                          long now, RollState state, double gold, double wave, int hot,
                          ItemStack jackpotIcon, String jackpotLabel, String jackpotKey,
                          FillerStrip strip) {
        int right = left + REEL_WIDTH;
        int bottom = top + REEL_HEIGHT;
        // When the names are switched off the cell keeps its full height -- the panel must not
        // resize on a settings toggle any more than it may mid-animation -- so it is nudged down
        // by half the empty caption band instead, which puts the sprite back in the optical
        // centre of its window.
        int cellTop = top + WINDOW_INSET + (names ? 0 : NAME_BAND / 2);

        graphics.fill(left, top, right, bottom, (alpha << 24) | WINDOW_RGB);

        // This reel's own landing, if it has had one in the second act. 1 means "not landing":
        // no burst, no kick, no flash.
        float burst = burstProgress(reel.index(), now);

        if (gold > 0.0) {
            drawWindowGlow(graphics, left, top, right, bottom, alpha, now, wave, burst, gold);
        }

        if (reel.locked()) {
            LootDrop symbol = reel.symbol();
            int rgb = symbol == null ? DEFAULT_TEXT_RGB : rgbOf(symbol.colorCode());
            if (gold > 0.0) {
                // A prize name in its drop's own colour disappears into an amber window; the
                // whole panel is one colour now, and the names belong to it.
                rgb = lerpRgb(rgb, lerpRgb(JACKPOT_GOLD, 0xFFFFFF, wave), gold);
            }
            // The snap: the sprite overshoots by a fraction and eases back, so a reel landing is
            // an event rather than a picture appearing.
            float kick = 1.0f + LOCK_KICK * (1.0f - burst) * (1.0f - burst);
            drawCell(graphics, font, iconOf(symbol), label,
                    symbol == null ? null : symbol.itemName(), left, cellTop, alpha,
                    alpha / 255.0f, nameSize, names, rgb, kick);

            if (gold > 0.0) {
                // Two rings again, the outer one a pixel outside the window: a prize window has
                // to out-weigh both the panel frame and a window that has not landed yet.
                graphics.outline(left - 1, top - 1, REEL_WIDTH + 2, REEL_HEIGHT + 2,
                        (alpha << 24) | hot);
                int ringAlpha = (int) Math.round(alpha * 0.7 * gold);
                if (ringAlpha > 3) {
                    graphics.outline(left, top, REEL_WIDTH, REEL_HEIGHT,
                            (ringAlpha << 24) | JACKPOT_GOLD);
                }
                drawBurstRing(graphics, left, top, alpha, burst);
            } else {
                graphics.outline(left, top, REEL_WIDTH, REEL_HEIGHT, (alpha << 24) | BORDER_RGB);
            }
            return;
        }

        // A spinning reel's captions warm with the act, so the strip belongs to the gold panel
        // rather than sitting on it in the ordinary roll's cool grey.
        int stripRgb = gold > 0.0 ? lerpRgb(FILLER_RGB, JACKPOT_GOLD, gold) : FILLER_RGB;
        drawSpinningReel(graphics, font, reel, left, top, right, bottom, cellTop, alpha,
                nameSize, names, now, state, jackpotIcon, jackpotLabel, jackpotKey, stripRgb,
                strip);

        // A dimmed frame, so a spinning column is still recognisably one of three windows.
        // Without it the machine has no windows at all until the first reel stops, and the
        // gold frame a landed reel gets has nothing to be brighter than.
        int idle = gold > 0.0 ? lerpRgb(BORDER_RGB, hot, gold * 0.6) : BORDER_RGB;
        graphics.outline(left, top, REEL_WIDTH, REEL_HEIGHT, ((alpha / 2) << 24) | idle);
    }

    /**
     * The scrolling strip.
     *
     * <p>Both the sub-cell offset and the index of the symbol in each row come out of the same
     * division of wall-clock millis by the cell period, so the content advances by exactly one
     * at the instant the offset wraps and the strip is seamless. Deriving the offset from
     * {@link Reel#spinPhase()} and the index from anything else cannot have that property, and
     * the symptom -- a sprite changing halfway through its travel -- is far more obvious with a
     * 32-pixel item than it ever was with a line of text.</p>
     *
     * <p>From the first instant of the second act the period is less than half as long, and
     * every other cell is the jackpot's own item, so the symbol the reels are about to land on
     * keeps flashing past. That is what a slot machine looks like when it is about to pay. It
     * begins at the top of {@link RollState#JACKPOT_INTRO} rather than at
     * {@link RollState#JACKPOT_SPIN}, because the columns are already turning under the gold
     * wash and drawing them at act one's speed there made the wash look like a pause.</p>
     */
    private static void drawSpinningReel(GuiGraphicsExtractor graphics, Font font, Reel reel,
                                         int left, int top, int right, int bottom, int cellTop,
                                         int alpha, float nameSize, boolean names, long now,
                                         RollState state, ItemStack jackpotIcon,
                                         String jackpotLabel, String jackpotKey, int stripRgb,
                                         FillerStrip strip) {
        long period = isJackpotAct(state) ? JACKPOT_CELL_MILLIS : STRIP_CELL_MILLIS;
        long t = now + (long) reel.index() * REEL_STRIP_OFFSET_MILLIS;
        long cell = Math.floorDiv(t, period);
        double phase = Math.floorMod(t, period) / (double) period;

        int offset = (int) Math.round(phase * STRIP_PITCH);
        int base = cellTop - offset;
        // The extra per-column term de-correlates *which* symbols the three drums are showing;
        // the millisecond offset above only de-correlates where they are in their travel.
        int index = (int) cell + reel.index() * 3;

        String[] fillerNames = strip.names();
        ItemStack[] fillers = strip.icons(now);
        int windowCentre = top + REEL_HEIGHT / 2;
        float fade = alpha / 255.0f;

        // Scissor so the strip is genuinely clipped by the window rather than merely drawn
        // inside it; without this the neighbouring cells spill across the frame. 26.x records
        // the live scissor rectangle into each item's own render state, so it clips the
        // sprites exactly as it clips the flat fills.
        graphics.enableScissor(left, top, right, bottom);
        try {
            for (int row = -1; row <= 1; row++) {
                int slot = index + row;
                boolean prize = jackpotIcon != null && (Math.floorMod(slot, 2) == 0);
                int pick = Math.floorMod(slot, fillerNames.length);
                ItemStack icon = prize ? jackpotIcon : fillers[pick];
                String name = prize ? jackpotLabel : fillerNames[pick];
                String key = prize ? jackpotKey : fillerNames[pick];
                int rowTop = base + row * STRIP_PITCH;
                // Dimmed by how far the cell is from the middle of the window, not by which of
                // the three rows it happens to be. Row order is an artefact of the loop and
                // changes at every wrap, so keying the brightness to it made a caption jump in
                // weight the instant the strip advanced; distance is continuous, so a caption
                // now brightens as it arrives and dims as it leaves.
                int centre = rowTop + SPRITE_DRAWN / 2;
                float near = 1.0f - Math.min(1.0f,
                        Math.abs(centre - windowCentre) / (float) STRIP_PITCH);
                int rowAlpha = Math.round(alpha * (0.25f + 0.75f * near * near));
                drawCell(graphics, font, icon, name, key, left, rowTop, rowAlpha, fade,
                        nameSize, names, stripRgb, 1.0f);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * One cell of a reel: the item sprite, and its name under it.
     *
     * <p>The name is drawn whenever the toggle asks for it, and additionally whenever
     * {@link DropSymbols} has no mapping for the drop -- an unmapped item falls back to a
     * generic sprite, and a generic sprite with no caption is a window that tells the player
     * nothing at all. That is the one case where the toggle is overridden, and it costs a
     * lookup only on a drop the mod has never seen.</p>
     */
    private static void drawCell(GuiGraphicsExtractor graphics, Font font, ItemStack icon,
                                 String name, String mappingKey, int left, int cellTop,
                                 int nameAlpha, float fade, float nameSize, boolean names,
                                 int nameRgb, float sizeKick) {
        boolean sprite = drawSprite(graphics, icon, left + REEL_WIDTH / 2,
                cellTop + SPRITE_DRAWN / 2, SPRITE_SCALE * sizeKick, fade);

        if (name == null || nameAlpha <= 3) {
            return;
        }
        // The mapping is asked about with the drop's bare item name, never with the caption --
        // "Coins x24500" is not an item and would report unmapped on every single roll.
        //
        // The sprite test is the second half of the same rule: a window with its caption
        // suppressed and no picture in it is blank, which is the one outcome neither branch is
        // allowed to produce. That used to be asked as `!icon.isEmpty()`, which answered only
        // the original way a cell could end up pictureless -- an ItemStack that cannot be built
        // before Minecraft has bound the item's data components. Drawing real server-sent stacks
        // added a second way, a model that will not render, so the question is now put to
        // drawSprite, which is the only code that actually knows whether a picture went in.
        if (!names && mappingKey != null && sprite && DropSymbols.hasMapping(mappingKey)) {
            return;
        }
        // drawFitted centres the shrunken ink inside a GLYPH_HEIGHT box starting at y, so the
        // band's own offset is folded in here rather than inside it.
        int nameY = cellTop + SPRITE_DRAWN + NAME_GAP + (NAME_BAND - GLYPH_HEIGHT) / 2;
        drawFitted(graphics, font, name, left + LABEL_INSET, nameY, NAME_BUDGET, nameSize,
                (nameAlpha << 24) | nameRgb);
    }

    /**
     * Draws an item sprite, scaled about its own centre.
     *
     * <p>{@code GuiGraphicsExtractor.item} takes integer coordinates and no size, so the size
     * has to come from the pose stack; drawing at {@code -8, -8} after translating to the
     * centre means the scale grows the sprite in place instead of pushing it down and right.
     * The push/pop is unconditional so a scaled sprite cannot leak into the next window, and
     * 26.x snapshots the pose and the scissor into the item's own render state at this call, so
     * the item's model, its lighting and its depth are resolved on their own and cannot reach
     * the flat fills drawn around it. It is also the inventory's own call, which is the whole
     * reason a stack carrying Hypixel's {@code item_model} arrives here wearing Hypixel's art;
     * see the class javadoc.</p>
     *
     * <p><b>Why a submit can fail, and why it costs one sprite.</b> The stacks reaching this
     * method are no longer all mod-authored: a learned one points at a model out of a server's
     * resource pack, and a pack can be wrong -- Hypixel's own currently ships at least one item
     * model the client logs as unparseable. Resolution happens inside this call, on the
     * {@code updateForTopItem} line that vanilla leaves outside its own crash handler, and what
     * comes out of a bad model is a {@code ReportedException}: a {@code RuntimeException}, which
     * the render-thread guard in {@link #extractRenderState} would answer by dropping the whole
     * roll and hiding the machine for the rest of the session. One bad row in somebody else's
     * pack is not worth the slot machine, so the submit is caught here instead, where the blast
     * radius is one window. Nothing is half-submitted when it throws: the render state is added
     * to the frame on the last instruction of the call, after every resolution that can fail, so
     * a throw means nothing was queued.</p>
     *
     * <p>The offender is remembered by identity and skipped from then on -- a model that failed
     * once fails every frame, and a log line per sprite per frame is an outage of its own -- and
     * the cell falls back to its caption, which {@link #drawCell} reads off this answer.</p>
     *
     * <p>The fade is the one thing this cannot do properly. Neither Minecraft version offers a
     * tint or an alpha on GUI item rendering, so a fading sprite is shrunk on the panel's own
     * alpha ramp instead of being made transparent: it recedes into the window as the machine
     * closes down, and is dropped entirely once the panel is nearly gone. Below
     * {@link #SPRITE_CUTOFF_ALPHA} it would be a couple of pixels across, so nothing pops. That
     * case still answers {@code true}: the sprite exists and is being withheld for a frame or
     * two, and answering {@code false} would flash every suppressed caption back on over the
     * last few frames of every fade-out.</p>
     *
     * <p>{@code fade} is the <em>panel's</em> alpha, never a row's. A scrolling strip dims its
     * outer rows, and feeding that dimming in here would have shrunk the neighbouring sprites
     * instead -- three items at three sizes sliding past a window, which reads as a broken
     * perspective rather than as a drum. Sprites cannot be dimmed at all, for the same reason
     * they cannot be faded, so the strip dims its captions and leaves its items alone.</p>
     *
     * @return whether this cell has a picture in it -- false only when there is no stack, or
     *         none that can be drawn, which is the caller's cue that the caption has to stay
     */
    private static boolean drawSprite(GuiGraphicsExtractor graphics, ItemStack stack,
                                      int centreX, int centreY, float scale, float fade) {
        if (stack == null || stack.isEmpty() || !renderable(stack)) {
            return false;
        }
        if (fade * 255.0f <= SPRITE_CUTOFF_ALPHA) {
            return true;
        }
        float drawn = scale * fade;
        boolean drew = true;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(centreX, centreY);
            graphics.pose().scale(drawn, drawn);
            graphics.item(stack, -SPRITE_HALF, -SPRITE_HALF);
        } catch (RuntimeException | LinkageError broken) {
            poison(stack, broken);
            drew = false;
        } finally {
            // Unconditional, and on the throwing path too: a pose left pushed would translate
            // and scale every later HUD element, and a throw is exactly the case where that
            // would get blamed on the wrong widget.
            graphics.pose().popMatrix();
        }
        return drew;
    }

    /**
     * Whether a stack is still worth submitting.
     *
     * <p>One int compare on the branch a healthy session takes for every sprite of every frame;
     * the scan below it is unreachable until something has actually failed.</p>
     */
    private static boolean renderable(ItemStack stack) {
        if (poisonedCount == 0) {
            return true;
        }
        if (spritesGivenUp) {
            return false;
        }
        for (int i = 0; i < poisonedCount; i++) {
            if (POISONED[i] == stack) {
                return false;
            }
        }
        return true;
    }

    /** Records a stack whose model would not render, and says so once. */
    private static void poison(ItemStack stack, Throwable broken) {
        if (poisonedCount < POISONED.length) {
            POISONED[poisonedCount++] = stack;
            LOGGER.error("SkyPrism could not draw the item sprite for {}; that window will show "
                    + "its caption instead for the rest of the session. This is usually a broken "
                    + "item model in the active resource pack rather than a fault in the stack.",
                    stack.getItem(), broken);
        } else if (!spritesGivenUp) {
            spritesGivenUp = true;
            LOGGER.error("SkyPrism gave up on item sprites after {} of them failed to render; the "
                    + "slot machine will draw captions only for the rest of the session.",
                    POISONED.length);
        }
    }

    /** The sprite for a drop, with {@link SlotRoll#NO_DROP}'s standing in for a missing one. */
    private static ItemStack iconOf(LootDrop symbol) {
        return DropSymbols.iconFor(symbol == null ? SlotRoll.NO_DROP : symbol);
    }

    // --- The jackpot act's effects --------------------------------------------------------

    /**
     * Lights a window from the inside: a warm band across it, a glint that crosses it, embers
     * rising off it, and -- on the frame its reel lands -- a flash.
     *
     * <p>All of it is drawn under the sprite and the name, so the item stays the most legible
     * thing in the panel while everything around it moves, and all of it is derived from the
     * frame's single {@code now} and scaled by {@code gold} -- nothing accumulates, the effect
     * looks the same at 30 fps as at 240, and every part of it is at zero for the whole of an
     * ordinary roll.</p>
     */
    private static void drawWindowGlow(GuiGraphicsExtractor graphics, int left, int top,
                                       int right, int bottom, int alpha, long now,
                                       double wave, float burst, double gold) {
        int midY = top + REEL_HEIGHT / 2;

        // A pool of light across the window. fillGradient only runs top to bottom, so the band
        // is two gradients meeting in the middle: transparent at both window edges, brightest
        // exactly where the item sits.
        int washAlpha = (int) Math.round(alpha * gold * (0.20 + 0.16 * wave));
        int peak = (washAlpha << 24) | GLOW_RGB;
        graphics.fillGradient(left, top, right, midY, GLOW_RGB, peak);
        graphics.fillGradient(left, midY, right, bottom, peak, GLOW_RGB);

        // The landing flash: a hot wash over the window for a fifth of a second, squared so it
        // dies away fast rather than lingering as a yellow haze over the item.
        if (burst < 1.0f) {
            float remaining = 1.0f - burst;
            int flash = (int) Math.round(alpha * 0.55 * remaining * remaining);
            if (flash > 3) {
                graphics.fill(left, top, right, bottom, (flash << 24) | SPARK_RGB);
            }
        }

        // A glint crossing the window, drawn as a run of one-pixel columns with a triangular
        // falloff -- seven fills, and only while the act is actually on screen.
        int span = REEL_WIDTH + SHINE_HALF_WIDTH * 2;
        int centre = left - SHINE_HALF_WIDTH + (int) ((now % SHINE_MILLIS) * span / SHINE_MILLIS);
        for (int k = -SHINE_HALF_WIDTH; k <= SHINE_HALF_WIDTH; k++) {
            int x = centre + k;
            if (x < left || x >= right) {
                continue;
            }
            double falloff = 1.0 - Math.abs(k) / (double) (SHINE_HALF_WIDTH + 1);
            int a = (int) Math.round(alpha * gold * 0.20 * falloff);
            if (a > 3) {
                graphics.fill(x, top + 1, x + 1, bottom - 1, (a << 24) | SPARK_RGB);
            }
        }

        // Embers. Each has a fixed phase offset within one lifetime, so the five are evenly
        // staggered; the column is re-hashed from the cycle number, so a given ember rises in
        // a different place each time round rather than tracing one fixed line forever.
        for (int i = 0; i < SPARKS; i++) {
            long t = now + i * (SPARK_MILLIS / SPARKS);
            float life = (t % SPARK_MILLIS) / (float) SPARK_MILLIS;
            int noise = mix((int) (t / SPARK_MILLIS) * 31 + i);
            int x = left + 1 + Math.floorMod(noise, REEL_WIDTH - 3);
            int y = bottom - 2 - Math.round(life * (REEL_HEIGHT - 5));
            // Fade in over the first sixth of the life, so an ember appears rather than pops.
            float fade = (1.0f - life) * Math.min(1.0f, life * 6.0f);
            int a = (int) Math.round(alpha * gold * fade);
            if (a <= 6) {
                continue;
            }
            int size = ((noise >>> 8) & 1) + 1;
            graphics.fill(x, y, x + size, y + size, (a << 24) | SPARK_RGB);
        }
    }

    /**
     * The shockwave that leaves a window the moment its reel lands.
     *
     * <p>It grows by {@link #PADDING} pixels and stops, which puts its last frame exactly on
     * the panel border -- so the ring expands to kiss the frame and dies there, and the burst
     * never escapes the widget or covers a pixel of the world.</p>
     */
    private static void drawBurstRing(GuiGraphicsExtractor graphics, int left, int top,
                                      int alpha, float burst) {
        if (burst >= 1.0f) {
            return;
        }
        int grow = 1 + Math.round(burst * (PADDING - 1));
        int a = (int) Math.round(alpha * (1.0f - burst));
        if (a <= 4) {
            return;
        }
        graphics.outline(left - grow, top - grow, REEL_WIDTH + grow * 2, REEL_HEIGHT + grow * 2,
                (a << 24) | JACKPOT_GOLD);
    }

    /**
     * Draws the headline half again as large as the drop names, with a bloom behind it.
     *
     * <p>It is the announcement, not a label for the row above it, so it is the largest type
     * in the widget rather than the same size as everything else. The bloom is the same word
     * drawn a little larger and much dimmer underneath, which is the cheapest honest glow a
     * bitmap font can have; the scale breathes on the same wave as the frame, so the word and
     * the border are visibly one effect and not two.</p>
     *
     * <p>{@code arrival} carries it in over the intro -- it starts small and dim under the
     * departing creature name and grows into place -- and {@code finale} gives it one extra
     * kick on the third match, which is the only time in the roll the word means something new.</p>
     */
    private static void drawJackpotHeadline(GuiGraphicsExtractor graphics, Font font,
                                            int boxWidth, int bandTop, int alpha,
                                            double wave, float finale, double arrival) {
        // A short overshoot on the third match, easing back into the steady breath.
        float kick = finale < 1.0f
                ? JACKPOT_FINALE_KICK * (1.0f - finale) * (1.0f - finale)
                : 0.0f;
        float grown = (float) (0.55 + 0.45 * arrival);
        float scale = (JACKPOT_SCALE + (float) (JACKPOT_SCALE_PULSE * wave) + kick) * grown;
        int width = jackpotTextWidth(font);
        int visible = (int) Math.round(alpha * arrival);
        if (visible <= 3) {
            return;
        }

        int bloom = (int) Math.round(visible * (0.18 + 0.22 * wave));
        if (bloom > 6) {
            // No shadow on the bloom pass: a drop shadow under a halo is a dark fringe
            // around a glow, which is the one thing a glow must not have.
            drawScaledCentred(graphics, font, JACKPOT_TEXT, width, boxWidth, bandTop,
                    scale * JACKPOT_BLOOM, (bloom << 24) | JACKPOT_AMBER, false);
        }
        drawScaledCentred(graphics, font, JACKPOT_TEXT, width, boxWidth, bandTop, scale,
                (visible << 24) | lerpRgb(JACKPOT_GOLD, 0xFFFFFF, wave), true);
    }

    /** Draws {@code text} scaled about the centre of the caption strip. */
    private static void drawScaledCentred(GuiGraphicsExtractor graphics, Font font, String text,
                                          int width, int boxWidth, int bandTop, float scale,
                                          int argb, boolean shadow) {
        float drawnWidth = width * scale;
        float drawnHeight = GLYPH_HEIGHT * scale;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate((boxWidth - drawnWidth) / 2.0f,
                    bandTop + (CAPTION_BAND - drawnHeight) / 2.0f);
            graphics.pose().scale(scale, scale);
            graphics.text(font, text, 0, 0, argb, shadow);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /**
     * The Magic Find the prize was rolled at, in the caption strip's right-hand gutter.
     *
     * <p><b>Nothing at all when the server reported nothing.</b> {@code text} is null on the
     * overwhelming majority of rare drops -- pet drops with no roll, Diana treasure digs, every
     * banner Hypixel simply did not append the stat to -- and the answer to that is an untouched
     * strip, not a placeholder and not a zero. Zero Magic Find and unreported Magic Find are
     * different facts, and a player who knows their own stat can tell which one a widget is
     * claiming.</p>
     *
     * <p><b>And no movement either way.</b> This method draws into space the panel already had:
     * the strip has always been the full width of the machine with a centred headline on it, so
     * the gutter it uses is dead space on every roll the widget has ever drawn. It reads
     * {@code boxWidth} and {@code bandTop} and writes neither, so the height, the width, the
     * reels, the headline and the frame are identical between a roll that reports the stat and
     * one that does not -- and identical to what act one drew before any of this existed.</p>
     *
     * <p><b>The gutter is measured against the headline at its widest.</b> Not its current width:
     * the headline breathes on {@link #PULSE_MILLIS} and takes a one-off kick on the third match,
     * so a figure placed against this frame's headline would be clear of it on this frame and
     * under it two frames later. {@link #JACKPOT_SCALE_MAX} is a genuine upper bound over both,
     * so the two can never touch. The figure is shrunk to fit what is left and, if the machine is
     * so narrow that even {@link #MIN_LABEL_SCALE} will not fit, it is dropped rather than drawn
     * illegibly or over the top of the word it is beside.</p>
     *
     * <p>The numbers, measured off the vanilla bitmaps rather than guessed. On the shipped
     * three-column machine the panel is 190 wide, {@code JACKPOT} is 42 and reaches 40 either
     * side of centre at its very largest, so the gutter is 47; {@code "★ +240%"} is 42, or
     * 32 at {@link #MAGIC_FIND_SCALE}, which clears the headline's worst case by fifteen pixels
     * and its resting width by twice that. Two columns leaves 16 and the figure is dropped; one
     * column has no gutter at all, because there the headline alone is wider than the panel.</p>
     *
     * <p>Nothing is drawn on any machine whose caption strip is switched off either, since there
     * is then no strip and no headline to sit beside -- {@code drawCaption} is not called at all.
     * That is the same answer the JACKPOT headline itself has always given.</p>
     */
    private static void drawMagicFind(GuiGraphicsExtractor graphics, Font font, String text,
                                      int boxWidth, int bandTop, int alpha, float reveal) {
        if (text == null || reveal <= 0.0f) {
            return;
        }
        int shown = (int) Math.round(alpha * reveal);
        if (shown <= 3) {
            return;
        }

        // The half-width the headline claims at the very top of its breath, plus the kick.
        int headlineHalf = (int) Math.ceil(jackpotTextWidth(font) * JACKPOT_SCALE_MAX / 2.0f);
        int gutterLeft = boxWidth / 2 + headlineHalf + MAGIC_FIND_GAP;
        int budget = boxWidth - PADDING - gutterLeft;
        if (budget <= 0) {
            return;
        }

        int measured = font.width(text);
        if (measured <= 0) {
            return;
        }
        float scale = Math.min(MAGIC_FIND_SCALE, budget / (float) measured);
        if (scale < MIN_LABEL_SCALE) {
            return;
        }

        float drawnWidth = measured * scale;
        float drawnHeight = GLYPH_HEIGHT * scale;
        graphics.pose().pushMatrix();
        try {
            // Right-aligned to the frame's inner edge, so the figure keeps the panel's own
            // four-pixel rhythm and sits on the same margin the reels do.
            graphics.pose().translate(boxWidth - PADDING - drawnWidth,
                    bandTop + (CAPTION_BAND - drawnHeight) / 2.0f);
            graphics.pose().scale(scale, scale);
            // With a shadow, unlike the headline's bloom pass: this is small aqua type over a
            // window that is glowing amber underneath it, and the shadow is what keeps it off
            // the background rather than in it.
            graphics.text(font, text, 0, 0, (shown << 24) | MAGIC_FIND_RGB, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** @return 0 at the instant reel {@code i} landed in the second act, rising to 1 */
    private float burstProgress(int index, long now) {
        if (index < 0 || index >= jackpotLockAt.length) {
            return 1.0f;
        }
        long at = jackpotLockAt[index];
        if (at == 0L) {
            return 1.0f;
        }
        long elapsed = now - at;
        if (elapsed <= 0L) {
            return 0.0f;
        }
        return elapsed >= BURST_MILLIS ? 1.0f : elapsed / (float) BURST_MILLIS;
    }

    /** @return 0 at the instant the third reel matched, rising to 1 once the celebration is spent */
    private float finaleProgress(long now) {
        if (finaleAt == 0L) {
            return 1.0f;
        }
        long elapsed = now - finaleAt;
        if (elapsed <= 0L) {
            return 0.0f;
        }
        return elapsed >= FINALE_MILLIS ? 1.0f : elapsed / (float) FINALE_MILLIS;
    }

    /**
     * How far the magic-find figure has faded in: 0 before the three of a kind, 1 once it is up.
     *
     * <p>The mirror image of {@link #finaleProgress}, and the reason it is a second method rather
     * than a reuse of the first: {@code finaleProgress} answers 1 for "no landing to celebrate",
     * because everything that reads it wants no kick and no flash in that case. Read as an
     * arrival curve, that same 1 would mean "fully revealed", and the figure would be painted on
     * the strip from the first frame of the gold wash -- before the reels have landed, and
     * therefore before the machine has said what the prize is. So the absent case answers 0 here,
     * and the figure arrives with the prize rather than ahead of it.</p>
     *
     * <p>It stays at 1 for the rest of the roll, {@link RollState#FADING} included, because
     * {@code finaleAt} is per-roll state: the panel's own alpha takes the figure out with
     * everything else rather than it having a second exit of its own.</p>
     */
    private float magicFindReveal(long now) {
        if (finaleAt == 0L) {
            return 0.0f;
        }
        long elapsed = now - finaleAt;
        if (elapsed <= 0L) {
            return 0.0f;
        }
        return elapsed >= MAGIC_FIND_REVEAL_MILLIS
                ? 1.0f
                : elapsed / (float) MAGIC_FIND_REVEAL_MILLIS;
    }

    // --- Names ---------------------------------------------------------------------------

    /**
     * The one type size every drop name on the machine is drawn at this frame.
     *
     * <p>The size is chosen once for the whole machine: the smallest fit any name currently on
     * screen needs, capped at {@link #NAME_MAX_SCALE} because the caption is a subtitle under a
     * sprite rather than the content of the reel. While anything is still spinning that is the
     * widest name on this roll's own {@link FillerStrip}, which is fixed for the length of the
     * roll, so a spin never changes size mid-scroll; and the jackpot's own name is folded in from
     * the first frame of the second act, so the three captions do not resize as the reels land on
     * it.</p>
     *
     * <p>Floored at {@link #MIN_LABEL_SCALE}. At the {@link #NAME_BUDGET} a name gets, that
     * floor holds 108 pixels of text, so {@link #drawFitted}'s shortening branch is a last resort
     * rather than the normal path it once was -- reached now only by the handful of genuinely long
     * drop names the wider game has, such as "Void Conqueror Enderman Skin", and reached the same
     * way whether such a name is on a spinning strip or under a reel that has landed on it.</p>
     */
    private float nameScale(Font font, List<Reel> reels, String jackpotLabel, FillerStrip strip) {
        float scale = NAME_MAX_SCALE;
        boolean spinning = false;
        for (int i = 0; i < reels.size(); i++) {
            if (reels.get(i).locked()) {
                scale = Math.min(scale, fitScale(font, labels[i], NAME_BUDGET));
            } else {
                spinning = true;
            }
        }
        if (spinning) {
            scale = Math.min(scale, spinLabelScale(font, strip));
        }
        if (jackpotLabel != null) {
            scale = Math.min(scale, fitScale(font, jackpotLabel, NAME_BUDGET));
        }
        return Math.max(MIN_LABEL_SCALE, scale);
    }

    /**
     * Fills {@link #labels} with this frame's locked-reel captions.
     *
     * <p>The array is grown, never shrunk, and only when the reel count actually changes -- which
     * is a settings edit, not something that happens between frames.
     */
    private void buildLabels(List<Reel> reels) {
        int count = reels.size();
        if (labels.length < count) {
            labels = new String[count];
        }
        for (int i = 0; i < count; i++) {
            Reel reel = reels.get(i);
            labels[i] = reel.locked() ? label(reel.symbol()) : null;
        }
    }

    /**
     * The scale at which every name on one strip fits its column. Measured once per source.
     *
     * <p>Because it is the minimum over an array that is fixed for the life of the session it is
     * itself a constant for that source, which is what keeps a caption the same size for the whole
     * of its scroll past the window. It is cached on the strip rather than here because the strips
     * are different lengths of word and one shared number would change under the player the first
     * time a different source rolled.</p>
     *
     * <p>Measured here rather than inside {@link FillerStrip} because the budget and the two
     * bounds belong to the widget's geometry, and {@link Font} does not exist at class-init.
     * Idempotent -- two threads racing here would compute the same float from the same immutable
     * array -- so it deliberately carries no synchronisation, and it is not invalidated on a
     * resource-pack swap because a font reload moves the answer by a pixel or two at most, which
     * is not worth a check on every frame of every spin.</p>
     */
    private static float spinLabelScale(Font font, FillerStrip strip) {
        float cached = strip.labelScale();
        if (cached > 0.0f) {
            return cached;
        }
        float worst = NAME_MAX_SCALE;
        for (String name : strip.names()) {
            worst = Math.min(worst, fitScale(font, name, NAME_BUDGET));
        }
        worst = Math.max(MIN_LABEL_SCALE, worst);
        strip.labelScale(worst);
        return worst;
    }

    /**
     * {@link #JACKPOT_TEXT}'s width, measured once; same reasoning as
     * {@link #spinLabelScale(Font, FillerStrip)}.
     */
    private static int jackpotTextWidth(Font font) {
        int cached = jackpotTextWidth;
        if (cached > 0) {
            return cached;
        }
        int measured = font.width(JACKPOT_TEXT);
        jackpotTextWidth = measured;
        return measured;
    }

    private static float fitScale(Font font, String text, int maxWidth) {
        if (text == null) {
            return NAME_MAX_SCALE;
        }
        int full = font.width(text);
        return full <= maxWidth ? NAME_MAX_SCALE : maxWidth / (float) full;
    }

    /**
     * Draws a drop name at the machine's shared scale, centred in its column.
     *
     * <p>Diana drop names are long -- "Dwarf Turtle Shelmet" is twenty characters -- and the
     * reel is deliberately narrow, so overflow is the normal path here, not an edge case.
     * This method used to answer it by ellipsising at full size, and at that column width it
     * truncated almost everything: "Griffin Feather" became "Griffin F...", and, far worse, a
     * payout of 24,500 coins locked onto a reel reading <em>"Coins x2..."</em>. A widget whose
     * entire job is to report the drops received cannot report a different number from the one
     * the player got, so shortening is the last resort: it happens only when a name is still
     * too wide at {@link #MIN_LABEL_SCALE}, and the budget is measured in the shrunken font so
     * the shortened string fills the column rather than stopping short of it.</p>
     *
     * <p>The shrink is applied about the line's own vertical centre, so a scaled name sits on
     * the same optical row as a full-size one and the three columns keep one baseline.</p>
     */
    private static void drawFitted(GuiGraphicsExtractor graphics, Font font, String text,
                                   int x, int y, int maxWidth, float scale, int argb) {
        String shown = text;
        float drawn = font.width(shown) * scale;
        if (drawn > maxWidth + FIT_EPSILON && maxWidth > 0) {
            int budget = Math.round(maxWidth / scale) - font.width("...");
            shown = font.plainSubstrByWidth(shown, Math.max(0, budget)) + "...";
            drawn = font.width(shown) * scale;
        }

        if (scale >= 0.999f) {
            // Centred, not flush left. A short name -- "Coins" in a wide column -- used to sit
            // hard against the left edge with a third of the window empty beside it, which
            // reads as text that overflowed rather than as a caption under a sprite.
            graphics.text(font, shown, x + Math.round((maxWidth - drawn) / 2.0f), y, argb, true);
            return;
        }

        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x + (maxWidth - drawn) / 2.0f,
                    y + (GLYPH_HEIGHT * (1.0f - scale)) / 2.0f);
            graphics.pose().scale(scale, scale);
            graphics.text(font, shown, 0, 0, argb, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** The name a reel shows, with its count when the drop stacked. */
    private static String label(LootDrop symbol) {
        if (symbol == null) {
            return SlotRoll.NO_DROP.itemName();
        }
        return symbol.count() > 1 ? symbol.itemName() + " x" + symbol.count() : symbol.itemName();
    }

    /**
     * The jackpot prize's Magic Find figure, formatted once for the whole roll.
     *
     * <p><b>Whose figure.</b> {@code jackpotDrop} is {@link SlotRoll#jackpotSymbolAt}, the one
     * drop all three columns converge on. A kill can print several banners and more than one of
     * them can carry a Magic Find; only one of them is the prize, and captioning the prize with a
     * number that belongs to a different item would be worse than showing nothing. The choice is
     * also what makes the answer cacheable: the jackpot symbol is fixed for the life of the act,
     * so there is exactly one figure to work out and it cannot go stale under the cache.</p>
     *
     * <p><b>Why it is worked out at all here rather than on every frame.</b>
     * {@code Component.translatable(...).getString()} builds a component, decomposes the format
     * and materialises a string. The hold runs for seconds; doing that per frame would be three
     * allocations a frame to arrive at a value that is a compile-time function of a fixed drop.
     * So the first frame of the act that has a jackpot symbol resolves it and every frame after
     * reads the field -- including, and this is the case the {@code resolved} flag exists for,
     * the common one where the answer is null.</p>
     *
     * @param jackpotDrop the drop the reveal converges on; null for the whole of act one
     * @return the text to draw, or null when this roll's prize reported no Magic Find
     */
    private String magicFindLabel(LootDrop jackpotDrop) {
        if (magicFindResolved) {
            return magicFindText;
        }
        if (jackpotDrop == null) {
            // Act one. Not an answer yet, so nothing is cached and nothing is drawn -- the
            // headline this figure sits beside has not arrived either.
            return null;
        }
        magicFindResolved = true;
        magicFindText = formatMagicFind(jackpotDrop);
        return magicFindText;
    }

    /**
     * Formats one drop's reading, or answers null when it has none.
     *
     * <p>The number and its percent sign come straight from {@link LootDrop.MagicFind#format()},
     * so the widget echoes exactly what Hypixel sent -- {@code "+240%"} on the lines that carried
     * a sign and {@code "+240"} on the ones that did not, because the server emits both and
     * adding the sign back would be asserting a unit nobody sent. The star and the spacing are
     * the widget's own and live in the language file; see {@link #MAGIC_FIND_KEY} for why the
     * captured icon codepoint is not used.</p>
     */
    private static String formatMagicFind(LootDrop drop) {
        LootDrop.MagicFind reading = drop.magicFind();
        if (reading == null) {
            return null;
        }
        return Component.translatable(MAGIC_FIND_KEY, reading.format()).getString();
    }

    // --- Fade and colour ----------------------------------------------------------------

    /**
     * Alpha for the current frame, 0-255.
     *
     * <p>{@link SlotRoll} reports that it is fading but not how far through it is, and it
     * should not have to: the fade is a look rather than a mechanism, and baking it into the
     * tested state machine would push a rendering concern into the core. So the moment
     * {@link RollState#FADING} is first seen is remembered here and the ramp is read off the
     * configured fade length. Any state other than FADING clears the timestamp, so a roll
     * that is restarted mid-fade always begins fully opaque.</p>
     */
    private int fadeAlpha(RollState state, SkyPrismConfig config, long now) {
        if (state != RollState.FADING) {
            fadeStartedAt = 0L;
            return 255;
        }
        if (fadeStartedAt == 0L) {
            fadeStartedAt = now;
        }
        long span = Math.max(1L, config.diana.fadeMillis);
        double remaining = 1.0 - (now - fadeStartedAt) / (double) span;
        return (int) Math.round(clamp(remaining, 0.0, 1.0) * 255.0);
    }

    /**
     * The jackpot's breath: 0 at the amber end, 1 at the gold end.
     *
     * <p>Read off wall time rather than off a frame counter, so it runs at the same speed on
     * a 30 fps laptop and a 240 fps desktop, and off a cosine rather than a triangle so the
     * word and the frame ease in and out of the peak instead of snapping through it.</p>
     */
    private static double pulseWave(long now) {
        double phase = (now % PULSE_MILLIS) / (double) PULSE_MILLIS;
        return 0.5 - 0.5 * Math.cos(phase * 2.0 * Math.PI);
    }

    /** Hermite ease, so the gold wash arrives and settles rather than sliding in linearly. */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** Blends two packed {@code 0xRRGGBB} values. */
    private static int lerpRgb(int from, int to, double t) {
        int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    /**
     * A cheap integer hash, so the embers can be scattered without a {@code Random} and
     * without any state that outlives the frame that drew them.
     */
    private static int mix(int value) {
        int h = value * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    // --- Sound -------------------------------------------------------------------------

    /**
     * Every sound the machine makes, re-timed to the two acts.
     *
     * <p>The first act is unchanged: one click per reel that comes to rest, pitch climbing
     * across the three, so the ordinary roll sounds exactly as plain as it now looks. Nothing
     * announces a jackpot while it is playing -- the old sting fired the instant the rare line
     * was parsed, which gave the surprise away before the reels had even stopped.</p>
     *
     * <p>The second act is one continuous rise. A ladder of notes climbs for as long as the
     * reels are free -- across the gold wash <em>and</em> the spin that follows it, because on
     * screen those are one unbroken stretch of motion and not two -- and it deliberately tops
     * out below the first landing, so the three landing clicks carry the line on upwards
     * instead of stepping down off the end of it. Then the celebration, and only on the third
     * match.</p>
     *
     * <p>The ladder used to be spread across the wash alone, on the reasoning that the wash was
     * the still beat before the machine moved. It is not any more: the reels break loose on the
     * first instant of act two, so a ladder that ended with the wash crammed eight notes into
     * the opening third of the spin-up and then left the rest of it silent, with nothing
     * visible happening at the moment the sound stopped to account for the sound stopping. Only
     * the pacing moved. Every landing is still driven off an observed transition rather than
     * off a timer, so a note falls on exactly the frame the thing it announces happens and
     * cannot double-fire at a high frame rate.</p>
     */
    private void playSounds(Minecraft minecraft, List<Reel> reels, RollState state,
                            SkyPrismConfig config, double spinUp) {
        SkyPrismConfig.SoundSettings sounds = config.sounds;
        float volume = (float) clamp(sounds.volume, 0.0, 1.0);
        boolean jackpotAct = isJackpotAct(state);
        int locked = countLocked(reels);

        if (!sounds.enabled || volume <= 0.0f) {
            // Still track every counter, so switching sound back on mid-roll does not fire a
            // burst of notes for events that happened while it was off.
            if (!jackpotAct) {
                lockedLastFrame = locked;
            }
            jackpotLockedLastFrame = jackpotAct ? locked : 0;
            spinUpTonesPlayed = SPINUP_TONES;
            finaleAnnounced = finaleAt != 0L;
            return;
        }

        if (!jackpotAct) {
            if (sounds.reelTicks && locked > lockedLastFrame) {
                // Pitch climbs with each column, so the sequence sounds like a machine coming to
                // rest rather than three identical beeps.
                float pitch = 0.9f + 0.15f * Math.min(locked, 5);
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
            }
            lockedLastFrame = locked;
            return;
        }

        // The spin-up's ladder, climbing for exactly as long as the reels are free. The step is
        // derived from the act's own progress rather than from a local timer, so a longer
        // configured act spreads the same ladder over more time instead of playing it early and
        // going quiet -- which is the property the old wash-only version had and the reason it
        // is kept here rather than replaced with a fixed interval.
        if ((state == RollState.JACKPOT_INTRO || state == RollState.JACKPOT_SPIN)
                && sounds.jackpotSound) {
            int step = Math.min(SPINUP_TONES, (int) (spinUp * SPINUP_TONES) + 1);
            while (spinUpTonesPlayed < step) {
                // 0.70 to 1.40 across the eight. The step was nearly twice this while the ladder
                // had only the wash to fit into; spread over the whole spin-up it has room to
                // climb gently, and it has to stop under the 1.60 the first landing rings at or
                // the approach would peak above the arrival.
                float pitch = pitchOf(0.70f + 0.10f * spinUpTonesPlayed);
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_BELL.value(), pitch, volume));
                spinUpTonesPlayed++;
            }
        }

        // Each landing of the second act: the same click as the first act, but pitched above
        // where that one ever reaches, with a bell over it so a match is audibly a match. The
        // pitch climbs across the three, so the third is the highest thing the roll ever plays.
        //
        // No phase test picks the landings out of the act, because the core already has: it
        // locks no jackpot column until the spin is over, so this count is zero for the whole
        // of the free spin by construction. It used to need one, because act one's locked reels
        // survived into the wash and would have fired all three clicks the moment the gold
        // arrived; every column is unlocked from the first instant of the act now.
        int landed = locked;
        if (landed > jackpotLockedLastFrame) {
            if (sounds.reelTicks) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.UI_BUTTON_CLICK.value(),
                        pitchOf(1.25f + 0.25f * landed), volume));
            }
            if (sounds.jackpotSound) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_BELL.value(),
                        pitchOf(1.30f + 0.30f * landed), volume));
            }
        }
        jackpotLockedLastFrame = landed;

        // The third match, and nothing before it.
        if (sounds.jackpotSound && finaleAt != 0L && !finaleAnnounced) {
            finaleAnnounced = true;
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4f, volume));
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.NOTE_BLOCK_CHIME.value(), 2.0f, volume));
        }
    }

    /** Minecraft clamps pitch to 0.5..2.0 anyway; doing it here keeps the ladders predictable. */
    private static float pitchOf(float pitch) {
        return (float) clamp(pitch, 0.5, 2.0);
    }

    private static int countLocked(List<Reel> reels) {
        int locked = 0;
        for (int i = 0; i < reels.size(); i++) {
            if (reels.get(i).locked()) {
                locked++;
            }
        }
        return locked;
    }

    // --- SkyPrismServices.Hud -----------------------------------------------------------

    /**
     * Runs a demonstration spin so {@code /skyprism hud} has something to position against.
     *
     * <p>Routed through {@link DianaController#simulate} rather than driving a private roll,
     * so what the player positions is the real widget fed by the real state machine. A
     * preview that took a different code path would be exactly the preview that lies. The
     * simulated loot carries one rare drop, so the preview plays the jackpot act too.</p>
     */
    @Override
    public void previewRoll() {
        DianaController.get().simulate(MythologicalCreature.MINOS_INQUISITOR,
                List.of(new LootDrop("Griffin Feather", "9", 1, false),
                        new LootDrop("Daedalus Stick", "5", 1, true),
                        new LootDrop("Coins", "6", 25000, false)));
        refreshRoll();
        lastRollId = -1L;
        resetPerRollState();
    }

    /**
     * The drop names a spinning reel can put on screen, across every source.
     *
     * <p>The union of every {@link FillerStrip} rather than one of them, because the strips are
     * per source now and the player runs {@code /skyprism status} while nothing is rolling. The
     * question the line answers -- how much of what the machine can draw is resolving through
     * Hypixel's own art rather than through the fallback -- is about the whole machine, and a
     * single source's strip would have answered it for whichever source happened to be hard-coded
     * here.</p>
     *
     * @return the names, distinct and in source order; {@code /skyprism status} reports where
     *         each one's sprite is being resolved from
     */
    @Override
    public List<String> symbolNames() {
        return FillerStrip.allNames();
    }

    /** @return the widget's unscaled footprint as {@code [width, height]} */
    @Override
    public int[] previewSize() {
        SkyPrismConfig config = ConfigManager.get().config();
        return new int[] {width(config.diana.reelCount), height(config.hud.showCreatureName)};
    }

    // --- Geometry helpers ---------------------------------------------------------------

    private static int width(int reelCount) {
        int n = Math.max(1, reelCount);
        return PADDING * 2 + n * REEL_WIDTH + (n - 1) * REEL_GAP;
    }

    /**
     * The panel's height.
     *
     * <p>Deliberately not a function of the roll state or of the drop-name toggle. A box that
     * changes size when the jackpot act begins looks broken however good the animation inside
     * it is, so the window is sized for the taller case -- a sprite with a caption under it --
     * from the very first frame, and the caption band simply goes unused when it is switched
     * off.</p>
     */
    private static int height(boolean caption) {
        return PADDING * 2 + REEL_HEIGHT + (caption ? CAPTION_HEIGHT : 0);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return value < min ? min : Math.min(value, max);
    }

    // --- Colour ---------------------------------------------------------------------

    /**
     * Resolves a Hypixel colour letter to a packed {@code 0xRRGGBB}.
     *
     * @param colorCode a one-character legacy code; null, empty or a non-colour code (a format
     *                  letter such as {@code l}) falls back to white rather than drawing an
     *                  invisible symbol
     * @return the packed colour, never with an alpha byte set
     */
    private static int rgbOf(String colorCode) {
        if (colorCode == null || colorCode.isEmpty()) {
            return DEFAULT_TEXT_RGB;
        }
        char code = Character.toLowerCase(colorCode.charAt(0));
        if (code >= LEGACY_RGB.length) {
            return DEFAULT_TEXT_RGB;
        }
        int rgb = LEGACY_RGB[code];
        return rgb < 0 ? DEFAULT_TEXT_RGB : rgb;
    }

    private static int[] buildLegacyTable() {
        int[] table = new int[128];
        Arrays.fill(table, -1);
        for (char code : "0123456789abcdef".toCharArray()) {
            ChatFormatting formatting = ChatFormatting.getByCode(code);
            if (formatting == null) {
                continue;
            }
            TextColor color = TextColor.fromLegacyFormat(formatting);
            if (color != null) {
                table[code] = color.getValue() & 0xFFFFFF;
            }
        }
        return table;
    }
}
