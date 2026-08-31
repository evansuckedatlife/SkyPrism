package com.skyprism.mc.selftest;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Captures the framebuffer to a PNG at a path we choose, and tells the caller when it landed.
 *
 * <h2>Why not {@code Screenshot.grab}</h2>
 *
 * <p>{@code Screenshot.grab(File, String, RenderTarget, int, Consumer)} exists on both nodes and
 * would work, but it decides the directory itself ({@code <gameDirectory>/screenshots}) and
 * writes on {@code Util.ioPool()}, so the caller learns neither the final path nor the moment
 * the bytes are on disk. A self test that must report a path per step and then shut the client
 * down cannot use either of those.
 * {@link Screenshot#takeScreenshot(RenderTarget, java.util.function.Consumer)} hands over the
 * decoded {@link com.mojang.blaze3d.platform.NativeImage} instead, which is the same GPU
 * readback minus the policy.</p>
 *
 * <p>The write happens <em>synchronously</em> inside the readback callback rather than on the IO
 * pool. That is the opposite of what vanilla does and it is deliberate: the whole point is that
 * {@link Capture#done()} means "the file is closed and complete", so the client can be stopped
 * the moment the last shot reports in. A few milliseconds of stall on a frame nobody is playing
 * costs nothing.</p>
 *
 * <h2>The one place the two Minecraft versions disagree</h2>
 *
 * <p>Everything else SkyPrism touches is identical across 26.1.2 and 26.2 -- the tree has zero
 * Stonecutter conditionals and that is worth keeping. The main render target is the exception:</p>
 *
 * <pre>
 *   26.1.2   Minecraft.getMainRenderTarget()      GameRenderer has no such method
 *   26.2     Minecraft has no such method         Minecraft.gameRenderer.mainRenderTarget()
 * </pre>
 *
 * <p>Rather than introduce the first version conditional in the tree for a debug-only class, this
 * resolves the accessor reflectively at first use and caches it. The cost is one reflective call
 * per screenshot on a path that only runs under {@code -Dskyprism.selftest=true}; the benefit is
 * that {@code src/main/java} still compiles byte-identically for both nodes. If a future
 * Minecraft renames it again, this fails with a readable line in the summary instead of failing
 * to compile.</p>
 */
final class Shots {

    private Shots() {
    }

    /** Resolved once; null until the first successful lookup. */
    private static Method renderTargetAccessor;

    /** True when the lookup ran and found nothing, so it is not repeated per shot. */
    private static boolean accessorMissing;

    /** Whether the accessor is invoked on the client or on its {@code gameRenderer}. */
    private static boolean accessorOnGameRenderer;

    /**
     * A screenshot in flight.
     *
     * <p>The fields are written from the GPU readback callback and read from the client tick.
     * Both run on the render thread in practice, but they are volatile anyway: the cost is
     * nothing and the alternative is a field whose visibility rests on an assumption about
     * Mojang threading that this class cannot enforce.</p>
     */
    static final class Capture {

        private final Path target;
        private volatile boolean done;
        private volatile String error;

        private Capture(Path target) {
            this.target = target;
        }

        /** @return true once the PNG is written, or once the attempt has definitively failed */
        boolean settled() {
            return done || error != null;
        }

        /** @return true when the file is on disk and complete */
        boolean done() {
            return done;
        }

        /** @return why the capture failed, or null while pending or once it succeeded */
        String error() {
            return error;
        }

        /** @return where the PNG was, or would have been, written */
        Path target() {
            return target;
        }
    }

    /**
     * Starts a capture of the current framebuffer.
     *
     * <p>Call this from a client tick, which is after a frame has been presented, so what lands
     * in the file is the frame a player would have been looking at. The returned capture is
     * polled rather than awaited: the readback completes when the GPU says so, normally on the
     * same or the next frame, and blocking a tick to wait for it would stall the very loop that
     * drives the completion.</p>
     *
     * @param target the PNG to write; parent directories are created
     * @return a handle to poll; never null, and this method never throws
     */
    static Capture request(Path target) {
        Capture capture = new Capture(target);
        try {
            RenderTarget framebuffer = mainRenderTarget();
            if (framebuffer == null) {
                capture.error = "no main render target accessor on this Minecraft version";
                return capture;
            }
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Screenshot.takeScreenshot(framebuffer, image -> {
                try {
                    image.writeToFile(target);
                    capture.done = true;
                } catch (Throwable broken) {
                    capture.error = describe(broken);
                } finally {
                    image.close();
                }
            });
        } catch (Throwable broken) {
            capture.error = describe(broken);
        }
        return capture;
    }

    /**
     * The main framebuffer, however this Minecraft version chooses to expose it.
     *
     * @return the render target, or null when neither shape is present
     */
    private static RenderTarget mainRenderTarget() throws ReflectiveOperationException {
        Minecraft client = Minecraft.getInstance();
        if (client == null || accessorMissing) {
            return null;
        }
        if (renderTargetAccessor == null) {
            resolveAccessor(client);
            if (renderTargetAccessor == null) {
                accessorMissing = true;
                return null;
            }
        }
        Object owner = accessorOnGameRenderer ? client.gameRenderer : client;
        return owner == null ? null : (RenderTarget) renderTargetAccessor.invoke(owner);
    }

    private static void resolveAccessor(Minecraft client) {
        Method onClient = findAccessor(client.getClass());
        if (onClient != null) {
            renderTargetAccessor = onClient;
            accessorOnGameRenderer = false;
            return;
        }
        Object renderer = client.gameRenderer;
        if (renderer == null) {
            return;
        }
        Method onRenderer = findAccessor(renderer.getClass());
        if (onRenderer != null) {
            renderTargetAccessor = onRenderer;
            accessorOnGameRenderer = true;
        }
    }

    /**
     * The first public no-argument instance method on {@code type} handing back a
     * {@link RenderTarget}, preferring the two names Mojang has actually used.
     *
     * <p>Name first rather than type first, because a class can own several render targets and
     * nothing says reflective ordering would pick the one drawn to the screen.</p>
     */
    private static Method findAccessor(Class<?> type) {
        Method byType = null;
        for (Method candidate : type.getMethods()) {
            if (candidate.getParameterCount() != 0
                    || Modifier.isStatic(candidate.getModifiers())
                    || !RenderTarget.class.isAssignableFrom(candidate.getReturnType())) {
                continue;
            }
            String name = candidate.getName();
            if (name.equals("getMainRenderTarget") || name.equals("mainRenderTarget")) {
                return candidate;
            }
            if (byType == null) {
                byType = candidate;
            }
        }
        return byType;
    }

    /** A one-line description, because a stack trace in a JSON field helps nobody. */
    private static String describe(Throwable broken) {
        String message = broken.getMessage();
        return broken.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
