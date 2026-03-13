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
    // Store the real MC GL capabilities (GL 4.5+) captured at startup,
    // so we can restore them before Voxy init even when Iris has swapped
    // to its own compatibility (GL 3.2) context.
    @Unique private static GLCapabilities voxy$realCapabilities = null;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V", shift = At.Shift.AFTER))
    private void injectSetup(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.pendingInit && this.core == null) {
            this.pendingInitFrames++;

            // On the first render frame ever, capture the real GL capabilities.
            // At this point MC has its full GL 4.5 context before Iris swaps it.
            if (voxy$realCapabilities == null) {
                try {
                    GLCapabilities caps = GL.getCapabilities();
                    if (caps != null && caps.OpenGL45) {
                        voxy$realCapabilities = caps;
                        System.out.println("[Voxy] Captured real GL capabilities (GL45=true)");
                    }
                } catch (IllegalStateException ignored) {}
            }

            // Check current capabilities — if GL45 is available, we're on the real context
            boolean canInit = false;
            try {
                GLCapabilities currentCaps = GL.getCapabilities();
                canInit = (currentCaps != null && currentCaps.OpenGL45);
            } catch (IllegalStateException ignored) {}

            if (!canInit && voxy$realCapabilities != null) {
                // Iris has swapped to its compatibility context (GL 3.2).
                // Restore the real capabilities so Voxy's GL45 calls use the correct
                // function pointers. The underlying GL context IS the real one (Iris
                // runs inside MC's render loop), but Iris swapped the LWJGL capabilities
                // object to its own limited set. Restoring the original caps fixes this.
                System.out.println("[Voxy] Current GL45=false, restoring real capabilities and initializing");
                GL.setCapabilities(voxy$realCapabilities);
                canInit = true;
            }

            if (canInit) {
                this.pendingInit = false;
                this.pendingInitFrames = 0;
                this.populateCore();
            } else {
                if (this.pendingInitFrames <= 5 || this.pendingInitFrames % 60 == 0) {
                    System.out.println("[Voxy] GL context not ready, deferring init (frame " + this.pendingInitFrames + ")");
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
            // Capture GL capabilities here (before Iris swaps them in render).
            // setWorld runs on Render thread with the real MC GL context.
            if (voxy$realCapabilities == null) {
                try {
                    GLCapabilities caps = GL.getCapabilities();
                    if (caps != null && caps.OpenGL45) {
                        voxy$realCapabilities = caps;
                        System.out.println("[Voxy] Captured real GL capabilities from setWorld (GL45=true)");
                    }
                } catch (IllegalStateException ignored) {}
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
