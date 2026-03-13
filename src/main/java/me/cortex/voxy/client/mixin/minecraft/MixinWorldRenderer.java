package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.Voxy;
import me.cortex.voxy.client.core.IGetVoxelCore;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.VoxelCore;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer implements IGetVoxelCore {
    @Shadow private Frustum frustum;

    @Shadow private @Nullable ClientWorld world;
    @Unique private VoxelCore core;
    @Unique private boolean pendingInit = false;
    @Unique private int pendingInitFrames = 0;

    // Try to verify GL context is actually usable by recreating capabilities.
    // LWJGL caches GLCapabilities in ThreadLocal, so cached function pointers
    // remain even after Iris unbinds the context. GLFW.glfwGetCurrentContext()
    // also doesn't detect it. The only safe way: clear capabilities, try to
    // recreate them (which queries the actual driver), and see if it succeeds.
    @Unique
    private static boolean voxy$isGLContextUsable() {
        long glfwCtx = GLFW.glfwGetCurrentContext();
        System.out.println("[Voxy/GL-Debug] glfwGetCurrentContext() = " + glfwCtx);

        GLCapabilities oldCaps = null;
        try {
            oldCaps = GL.getCapabilities();
            System.out.println("[Voxy/GL-Debug] GL.getCapabilities() returned non-null, GL_ARB_direct_state_access=" +
                (oldCaps != null ? oldCaps.GL_ARB_direct_state_access : "null"));
        } catch (IllegalStateException e) {
            System.out.println("[Voxy/GL-Debug] GL.getCapabilities() threw ISE: " + e.getMessage());
            return false;
        }

        // Clear cached capabilities and try to recreate them from the actual context
        try {
            GL.setCapabilities(null);
            GLCapabilities newCaps = GL.createCapabilities();
            System.out.println("[Voxy/GL-Debug] GL.createCapabilities() succeeded, GL45=" + newCaps.OpenGL45);
            return newCaps.OpenGL45; // Voxy requires GL 4.5
        } catch (IllegalStateException e) {
            System.out.println("[Voxy/GL-Debug] GL.createCapabilities() threw ISE: " + e.getMessage());
            // Restore old caps so other mods don't break
            if (oldCaps != null) {
                GL.setCapabilities(oldCaps);
            }
            return false;
        } catch (Exception | Error e) {
            System.out.println("[Voxy/GL-Debug] GL.createCapabilities() threw: " + e.getClass().getName() + ": " + e.getMessage());
            if (oldCaps != null) {
                GL.setCapabilities(oldCaps);
            }
            return false;
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V", shift = At.Shift.AFTER))
    private void injectSetup(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.pendingInit && this.core == null) {
            this.pendingInitFrames++;
            System.out.println("[Voxy] Pending init frame #" + this.pendingInitFrames);

            if (voxy$isGLContextUsable()) {
                System.out.println("[Voxy] GL context is usable, initializing core");
                this.pendingInit = false;
                this.pendingInitFrames = 0;
                this.populateCore();
            } else {
                System.out.println("[Voxy] GL context NOT usable, deferring (frame " + this.pendingInitFrames + ")");
                if (this.pendingInitFrames > 300) {
                    // Give up after ~5 seconds at 60fps
                    System.out.println("[Voxy] Giving up on GL context after " + this.pendingInitFrames + " frames");
                    this.pendingInit = false;
                    this.pendingInitFrames = 0;
                }
            }
        }
        if (this.core != null) {
            this.core.renderSetup(this.frustum, camera);
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;renderLayer(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/util/math/MatrixStack;DDDLorg/joml/Matrix4f;)V", ordinal = 2, shift = At.Shift.AFTER))
    private void injectOpaqueRender(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.core != null) {
            var cam = camera.getPos();
            this.core.renderOpaque(matrices, cam.x, cam.y, cam.z);
        }
    }

    @Unique
    public void populateCore() {
        if (this.core != null) {
            throw new IllegalStateException("Trying to create new core while a core already exists");
        }
        try {
            this.core = Voxy.createVoxelCore(this.world);
        } catch (Exception e) {
            Logger.error("Voxy failed to initialize (likely unsupported GPU). Disabling Voxy.");
            e.printStackTrace();
            this.core = null;
            VoxyConfig.CONFIG.enabled = false;
        }
    }

    public VoxelCore getVoxelCore() {
        return this.core;
    }

    @Inject(method = "reload()V", at = @At("TAIL"))
    private void resetVoxelCore(CallbackInfo ci) {
        // No-op: don't restart Voxy on renderer reload (e.g. Iris shader toggle).
    }

    @Inject(method = "setWorld", at = @At("TAIL"))
    private void initVoxelCore(ClientWorld world, CallbackInfo ci) {
        this.pendingInit = false;
        this.pendingInitFrames = 0;
        if (world == null) {
            if (this.core != null) {
                this.core.shutdown();
                this.core = null;
            }
            return;
        }

        if (this.core != null) {
            this.core.shutdown();
            this.core = null;
        }
        if (VoxyConfig.CONFIG.enabled) {
            System.out.println("[Voxy] setWorld called, deferring init to render frame");
            this.pendingInit = true;
        }
    }

    @Override
    public void reloadVoxelCore() {
        if (this.core != null) {
            this.core.shutdown();
            this.core = null;
        }
        if (this.world != null && VoxyConfig.CONFIG.enabled) {
            System.out.println("[Voxy] reloadVoxelCore called, deferring init to render frame");
            this.pendingInit = true;
            this.pendingInitFrames = 0;
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void injectClose(CallbackInfo ci) {
        if (this.core != null) {
            this.core.shutdown();
            this.core = null;
        }
    }
}
