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

    // Force-create fresh GL capabilities from the actual native GL context.
    // Iris swaps the LWJGL GLCapabilities ThreadLocal to its own GL 3.2 set,
    // but the real native context is still GL 4.5. By calling GL.createCapabilities()
    // after clearing the cache, we query the real driver and get GL 4.5 caps back.
    // The key insight: we need to do this ONLY when Iris hasn't yet made
    // its context "current" — i.e., the native context is MC's real one.
    @Unique
    private static GLCapabilities voxy$forceCreateCapabilities() {
        try {
            // Save whatever Iris set
            GLCapabilities irisCaps = null;
            try {
                irisCaps = GL.getCapabilities();
            } catch (IllegalStateException ignored) {}

            // Force LWJGL to re-query the native driver
            GL.setCapabilities(null);
            GLCapabilities realCaps = GL.createCapabilities();

            System.out.println("[Voxy/GL-Debug] Force-created capabilities: GL45=" + realCaps.OpenGL45 +
                " GL_ARB_direct_state_access=" + realCaps.GL_ARB_direct_state_access +
                " GL_ARB_buffer_storage=" + realCaps.GL_ARB_buffer_storage);

            if (realCaps.OpenGL45) {
                // Real context supports GL 4.5 — use these caps
                return realCaps;
            } else {
                // Still not GL 4.5 — restore Iris caps and return null
                if (irisCaps != null) {
                    GL.setCapabilities(irisCaps);
                }
                return null;
            }
        } catch (Exception e) {
            System.out.println("[Voxy/GL-Debug] forceCreateCapabilities failed: " + e.getClass().getName() + ": " + e.getMessage());
            return null;
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V", shift = At.Shift.AFTER))
    private void injectSetup(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.pendingInit && this.core == null) {
            this.pendingInitFrames++;

            // Check if current caps already have GL45
            boolean canInit = false;
            try {
                GLCapabilities currentCaps = GL.getCapabilities();
                canInit = (currentCaps != null && currentCaps.OpenGL45);
                if (canInit) {
                    System.out.println("[Voxy] Current capabilities have GL45=true, initializing directly");
                }
            } catch (IllegalStateException ignored) {}

            if (!canInit) {
                // Iris swapped caps — force-recreate from native context
                GLCapabilities realCaps = voxy$forceCreateCapabilities();
                if (realCaps != null) {
                    canInit = true;
                    System.out.println("[Voxy] Restored GL45 capabilities, initializing");
                }
            }

            if (canInit) {
                this.pendingInit = false;
                this.pendingInitFrames = 0;
                this.populateCore();
            } else {
                if (this.pendingInitFrames <= 5 || this.pendingInitFrames % 60 == 0) {
                    System.out.println("[Voxy] GL context not ready (GL45 unavailable), deferring init (frame " + this.pendingInitFrames + ")");
                }
                if (this.pendingInitFrames > 600) {
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
            // Debug: check GL state at setWorld time
            try {
                GLCapabilities caps = GL.getCapabilities();
                System.out.println("[Voxy/GL-Debug] setWorld: current caps GL45=" +
                    (caps != null ? caps.OpenGL45 : "null") +
                    " ARB_dsa=" + (caps != null ? caps.GL_ARB_direct_state_access : "null"));

                // Try force-recreating to check the real native driver
                GL.setCapabilities(null);
                GLCapabilities freshCaps = GL.createCapabilities();
                System.out.println("[Voxy/GL-Debug] setWorld: fresh caps GL45=" + freshCaps.OpenGL45 +
                    " ARB_dsa=" + freshCaps.GL_ARB_direct_state_access);

                if (freshCaps.OpenGL45) {
                    // We have GL 4.5 right now — init immediately!
                    System.out.println("[Voxy] setWorld: GL45 available, initializing immediately");
                    GL.setCapabilities(freshCaps);
                    this.populateCore();
                    return;
                } else {
                    // Restore caps and defer
                    if (caps != null) GL.setCapabilities(caps);
                    else GL.setCapabilities(freshCaps);
                }
            } catch (Exception e) {
                System.out.println("[Voxy/GL-Debug] setWorld GL check failed: " + e);
            }
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
