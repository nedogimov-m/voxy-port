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

    // Check if an OpenGL context is actually current on this thread.
    // GL.getCapabilities() is cached in ThreadLocal and doesn't reflect Iris unbinding the context.
    // GLFW.glfwGetCurrentContext() returns 0L when no context is bound.
    @Unique
    private static boolean voxy$hasGLContext() {
        return GLFW.glfwGetCurrentContext() != 0L;
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V", shift = At.Shift.AFTER))
    private void injectSetup(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.pendingInit && this.core == null) {
            if (voxy$hasGLContext()) {
                this.pendingInit = false;
                this.populateCore();
            }
            // else: no GL context yet (Iris pipeline rebuild), keep pendingInit for next frame
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
