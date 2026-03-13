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
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer implements IGetVoxelCore {
    @Shadow private Frustum frustum;

    @Shadow private @Nullable ClientWorld world;
    @Unique private VoxelCore core;
    @Unique private boolean pendingInit = false;
    @Unique private int pendingInitFrames = 0;

    // Query the real GL capabilities from the native driver, bypassing any
    // cached/swapped capabilities (e.g. from Iris shader pipelines).
    @Unique
    private static GLCapabilities voxy$queryRealCapabilities() {
        try {
            GLCapabilities saved = null;
            try {
                saved = GL.getCapabilities();
            } catch (IllegalStateException ignored) {}

            GL.setCapabilities(null);
            GLCapabilities real = GL.createCapabilities();

            if (real.OpenGL45) {
                return real;
            } else {
                if (saved != null) {
                    GL.setCapabilities(saved);
                }
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V", shift = At.Shift.AFTER))
    private void injectSetup(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.pendingInit && this.core == null) {
            this.pendingInitFrames++;

            boolean canInit = false;
            try {
                GLCapabilities caps = GL.getCapabilities();
                canInit = (caps != null && caps.OpenGL45);
            } catch (IllegalStateException ignored) {}

            if (!canInit) {
                GLCapabilities real = voxy$queryRealCapabilities();
                if (real != null) {
                    canInit = true;
                }
            }

            if (canInit) {
                this.pendingInit = false;
                this.pendingInitFrames = 0;
                this.populateCore();
            } else if (this.pendingInitFrames > 120) {
                // GPU does not support OpenGL 4.5 — log once and disable
                String glVersion = "unknown";
                String glRenderer = "unknown";
                try {
                    glVersion = GL11.glGetString(GL11.GL_VERSION);
                    glRenderer = GL11.glGetString(GL11.GL_RENDERER);
                } catch (Exception ignored) {}
                Logger.error("Voxy requires OpenGL 4.5 but your GPU only supports OpenGL " + glVersion
                    + " (" + glRenderer + "). Voxy has been disabled.");
                this.pendingInit = false;
                this.pendingInitFrames = 0;
                VoxyConfig.CONFIG.enabled = false;
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
            // Try immediate init if GL 4.5 is available right now
            try {
                GLCapabilities caps = GL.getCapabilities();
                if (caps != null && caps.OpenGL45) {
                    this.populateCore();
                    return;
                }
                // Try querying real driver capabilities
                GLCapabilities real = voxy$queryRealCapabilities();
                if (real != null) {
                    this.populateCore();
                    return;
                }
            } catch (Exception ignored) {}
            // Defer to render loop
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
