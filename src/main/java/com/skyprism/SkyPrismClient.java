package com.skyprism;

import com.skyprism.mc.chat.ChatHooks;
import com.skyprism.mc.chat.ChatRouter;
import com.skyprism.mc.command.SkyPrismCommands;
import com.skyprism.mc.config.ConfigManager;
import com.skyprism.mc.diana.DianaController;
import com.skyprism.mc.gui.ConfigGui;
import com.skyprism.mc.hud.LootMachine;
import com.skyprism.mc.hud.SlotMachineHud;
import com.skyprism.mc.selftest.SelfTest;
import com.skyprism.mc.symbols.IconCapture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SkyPrism's entrypoint: the one place the independently written adapter modules are joined up.
 *
 * <p>Every module below is self-contained and knows nothing about the others, which is what
 * let them be built in parallel. The cost of that independence is that none of them starts
 * itself, so this class is the whole of the mod's startup order and the whole of its
 * failure policy.</p>
 *
 * <h2>Why the order matters</h2>
 *
 * <ol>
 *   <li><b>Config first, before anything else.</b> Every other module reads
 *       {@code ConfigManager.get().config()} and several register change listeners against
 *       it. Loading after they start would mean they each spent their first moments acting
 *       on defaults and then had to be told the truth; loading first means there is only ever
 *       one answer.</li>
 *   <li><b>The Diana controller is pointed at that config before it is initialised.</b>
 *       Without the supplier it silently falls back to loading a <em>second</em> copy of the
 *       file, which works but never sees a settings change -- the worst kind of bug, because
 *       everything appears to function until the player edits something.</li>
 *   <li><b>The HUD registers after the controller.</b> Registration caches the controller's
 *       {@code SlotRoll} so the render path never has to ask for it, which means the
 *       controller has to exist first. Going the other way round would cache a roll built
 *       from default settings a moment before the controller rebuilt it from the real
 *       ones.</li>
 *   <li><b>The loot bus comes after both and before the chat hooks.</b> {@code LootMachine}
 *       spins the controller's {@code SlotRoll}, so the controller has to exist; and it is fed
 *       by the controller's chat listener, so it has to be populated before a line can arrive.
 *       Registered late it would not fail, which is the dangerous shape -- it would simply do
 *       nothing until the next chat message and look like a broken feature.</li>
 *   <li><b>Commands last.</b> {@code SkyPrismCommands.register()} also installs the service
 *       bindings the command tree reads, so running it after the modules exist means every
 *       subcommand finds a live implementation rather than a null.</li>
 * </ol>
 *
 * <h2>Where the machine's two feeds meet</h2>
 *
 * <p>The slot machine has exactly one {@code SlotRoll} and two things that can start it. The
 * Mythological Ritual starts it from a creature dying, inside {@code DianaController}, exactly
 * as it always has; every other chance-based activity in SkyBlock starts it from a
 * {@code LootEvent} off {@code LootMachine}'s bus. They share the roll, they share the loot
 * window, and they share the controller's single chat listener -- which is the whole reason
 * the mod still pays for one chat hook and one flatten per line rather than two of each.</p>
 *
 * <h2>Why every step is wrapped</h2>
 *
 * <p>A client mod initialiser that throws takes the whole game down with a Fabric crash
 * report. SkyPrism is entirely cosmetic: there is no state it can corrupt and nothing it can
 * lose, so a module that fails to start is worth an error in the log and nothing more. Each
 * step is therefore isolated, and a failure in one leaves the others running -- a broken
 * Diana HUD must not cost the player their level colours. {@link Throwable} rather than
 * {@link RuntimeException} is caught on purpose: the realistic failure for an adapter built
 * against two Minecraft versions is a {@link LinkageError} from a missing method, which is
 * not an exception.</p>
 *
 * <h2>The one wiring decision recorded here</h2>
 *
 * <p>{@code ChatRouter} and {@code DianaController} were independently given the same job --
 * an {@code ALLOW_GAME} listener that feeds Diana from chat and suppresses drop lines -- and
 * both do it correctly. Running both is merely redundant rather than wrong, but redundant
 * work on every chat line is still work, so the chat module's copy is switched off and the
 * controller's is kept: the controller must be initialised for burrow and entity tracking
 * regardless, so keeping its listener is one moving part fewer. Level recolouring is
 * unaffected; it lives in the chat module's other callback.</p>
 */
public final class SkyPrismClient implements ClientModInitializer {
    public static final String MOD_ID = "skyprism";

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism");

    /**
     * The Diana controller's step name.
     *
     * <p>A constant rather than two string literals because the chat step below tests for it: two
     * copies of a human-readable label, one of them load-bearing, is exactly the pair that drifts.
     */
    private static final String DIANA_CONTROLLER = "Diana controller";

    @Override
    public void onInitializeClient() {
        LOGGER.info("SkyPrism {} ready on Minecraft {}", modVersion(), minecraftVersion());

        List<String> started = new ArrayList<>(8);
        List<String> failed = new ArrayList<>(0);

        step("configuration", started, failed, () -> ConfigManager.get().load());

        step(DIANA_CONTROLLER, started, failed, () -> {
            DianaController controller = DianaController.get();
            controller.setConfigSupplier(() -> ConfigManager.get().config());
            controller.init();
        });

        step("slot machine HUD", started, failed, SlotMachineHud::register);

        // The whole-game loot bus: every chance-based source outside the Mythological Ritual.
        //
        // After the controller, because it spins the controller's roll and reads the controller's
        // loot window. Before the chat hooks, because the controller's chat listener is what feeds
        // it -- a machine registered after that listener would sit inert until the next line. And
        // before the commands, so /skyprism sources finds a populated bus rather than an empty one.
        //
        // Only sources whose policy is armed get a detector at all: a source switched off is not a
        // branch that returns early here, it is an object that does not exist and contributes
        // nothing to the bus's chat pre-filter. Diana is deliberately not among them; its detector
        // is the controller above, and registering a second owner for that source would spin the
        // machine twice for one burrow.
        step("loot sources", started, failed, () -> {
            LootMachine machine = LootMachine.get();
            DianaController controller = DianaController.get();
            machine.wire(controller::roll,
                    () -> ConfigManager.get().config().diana.lootWindowMillis);
            machine.registerDetectors(SkyPrismClient::localPlayerName);
        });

        // Watching the player's own inventory for the real item art, so a reel can draw what
        // Hypixel's server resource pack draws instead of a vanilla lookalike.
        //
        // IconCapture already self-arms the first time the symbol table is read, and that is a
        // safety net rather than the intended wiring: the table is first read while a reel is
        // being drawn, which is *during* a roll, so a mod that leaves it to the net arms one roll
        // late and misses the first drop it was supposed to learn from. Arming it here instead
        // moves the memory read onto the tick the Diana gate opens -- the player arriving on the
        // Hub with Diana in office, minutes before anything dies -- which is both the cheapest
        // moment to touch a file and the last one that is still early enough.
        //
        // init() is idempotent and documented never to throw however early it runs, so the net
        // stays harmless and this call is safe wherever it sits in this list.
        step("Diana item art capture", started, failed, IconCapture::init);

        step("chat hooks", started, failed, () -> {
            ChatHooks.register();
            // See the class documentation: the controller owns the Diana chat feed, so the
            // chat module's duplicate is retired. Recolouring and /skyprism replay are
            // deliberately unaffected -- replay ignores this flag by design.
            //
            // Conditional, and that is the whole point of the redundancy. step() is built so a
            // failure in one subsystem leaves the others running, so the controller's listener
            // may never have been registered -- a LinkageError from an adapter built against two
            // Minecraft versions is the realistic case, and init() resolves three Fabric event
            // classes. Retiring the surviving copy on the assumption that the other one is live
            // would leave Diana chat parsing completely dead while the startup summary reported
            // "chat hooks" as started, pointing every bug report at the wrong subsystem. The
            // redundant-but-working path is the correct fallback, which is exactly the reasoning
            // ChatRouter's own field documentation gives for defaulting the flag to true.
            if (started.contains(DIANA_CONTROLLER)) {
                ChatRouter.setDianaFeedEnabled(false);
            } else {
                LOGGER.warn("SkyPrism is keeping the chat module's Diana feed: the {} did not "
                        + "start, so it is the only one left.", DIANA_CONTROLLER);
            }
        });

        step("commands", started, failed, SkyPrismCommands::register);

        // The in-client self test, and the only conditional in this method.
        //
        // Everything above is a feature; this is a camera. It drives the client through every
        // screen the mod owns and photographs them, which is how the visual half of SkyPrism
        // stopped being an inference from source code. It ships in the jar because a test you
        // have to rebuild the jar to run is a test nobody runs.
        //
        // It must therefore cost a normal player exactly nothing, and the shape of this branch
        // is how that is guaranteed rather than merely intended:
        //
        //   * one property read, once, at startup -- not per tick, per frame or per event;
        //   * the call sits INSIDE the if, so with the flag absent com.skyprism.mc.selftest is
        //     never loaded. Verifying this class does not resolve the constant-pool entry for a
        //     method that is never invoked, which is the same guarantee ConfigGui relies on to
        //     keep YACL out of a client that does not have it;
        //   * SelfTest.arm() is the package's only public entry point, so there is no second
        //     door into it;
        //   * and it goes through step(), so a self test that throws is logged and skipped like
        //     any other subsystem rather than crashing a player's game.
        //
        // Last on purpose: the script opens screens that read SkyPrismServices, which the
        // commands step above is what wires up.
        if (Boolean.getBoolean(SelfTest.ENABLE_PROPERTY)) {
            step("self test", started, failed, () -> SelfTest.arm());
        }

        // One line naming exactly what came up. This is the line to ask a user to paste when
        // they report that a feature "does nothing": it distinguishes a subsystem that failed
        // to start from one that started and is simply switched off in the settings, which
        // are indistinguishable from the outside and have completely different fixes.
        LOGGER.info("SkyPrism started: {} | settings screen: {}",
                started.isEmpty() ? "nothing" : String.join(", ", started),
                ConfigGui.available() ? "available (YACL present)" : "unavailable (YACL absent)");
        if (!failed.isEmpty()) {
            LOGGER.warn("SkyPrism is running degraded; these subsystems did not start: {}",
                    String.join(", ", failed));
        }
    }

    /**
     * Runs one startup step, logging rather than propagating anything it throws.
     *
     * @param what    a human-readable name; this is what a user reporting a broken feature
     *                will quote back, so it names the feature rather than the class
     * @param started collects the steps that succeeded
     * @param failed  collects the steps that did not
     * @param body    the step
     */
    private static void step(String what, List<String> started, List<String> failed,
                             Runnable body) {
        try {
            body.run();
            started.add(what);
        } catch (Throwable broken) {
            failed.add(what);
            LOGGER.error("SkyPrism could not start its {}; the rest of the mod is unaffected.",
                    what, broken);
        }
    }

    /**
     * The client's own username, for the chest detectors that have to tell your loot from a
     * party member's.
     *
     * <p>Hypixel broadcasts {@code RARE REWARD! <player> found a <item> in their <tier> Chest} to
     * everyone in the run, so a detector that did not check the name would spin the machine five
     * times for one chest, four of them for loot the player never received. Returning null while
     * the client is still connecting is the safe direction: the detectors read it as "cannot
     * confirm this is mine" and stay quiet, rather than as "it is mine".</p>
     */
    private static String localPlayerName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.getUser() == null ? null : mc.getUser().getName();
        } catch (RuntimeException | LinkageError notReady) {
            return null;
        }
    }

    private static String modVersion() {
        return versionOf(MOD_ID);
    }

    private static String minecraftVersion() {
        return versionOf("minecraft");
    }

    private static String versionOf(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
