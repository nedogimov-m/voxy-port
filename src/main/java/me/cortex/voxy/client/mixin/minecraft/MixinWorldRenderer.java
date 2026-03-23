package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientWorld world;
    @Unique private VoxyRenderSystem renderer;
    @Unique private boolean pendingInit = false;

    @Override
    public VoxyRenderSystem getVoxyRenderSystem() {
        return this.renderer;
    }

    // Deferred init hook: if GL context wasn't ready during setWorld (e.g. Iris pipeline rebuild),
    // initialize on the first render frame when the context is guaranteed to be valid.
    // PRESERVED from port commit 50b867d5 (GL context safety check)
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V", shift = At.Shift.AFTER))
    private void injectDeferredInit(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.pendingInit && this.renderer == null) {
            this.pendingInit = false;
            this.createRenderer();
        }
    }

    @Inject(method = "reload()V", at = @At("TAIL"))
    private void onReload(CallbackInfo ci) {
        // Don't restart Voxy on renderer reload (e.g. Iris shader toggle).
        // Voxy's GL resources are independent of Minecraft's resource system.
        // Full restart causes RocksDB close/reopen, loss of all render sections,
        // and 1 FPS while everything rebuilds. Use setWorld or manual restart.
    }

    @Inject(method = "setWorld", at = @At("TAIL"))
    private void onSetWorld(ClientWorld world, CallbackInfo ci) {
        this.pendingInit = false;

        // Always shut down old renderer first
        this.shutdownRenderer();

        if (world == null) {
            // Leaving world: shut down the VoxyCommon instance
            VoxyCommon.shutdownInstance();
            return;
        }

        // Lazy init: register instance factory, check GPU caps, etc.
        // Must happen before isAvailable() check since it sets the factory.
        VoxyClient.ensureInitialized();

        // Joining new world: ensure VoxyCommon instance exists
        if (VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled) {
            if (VoxyCommon.getInstance() == null) {
                VoxyCommon.createInstance();
            }

            // Check if GL context is available. Iris may destroy/recreate its pipeline
            // during setWorld, leaving no valid GL context. In that case, defer init
            // to the first render frame where the context is guaranteed to exist.
            // PRESERVED from port commit 50b867d5 (GL context safety / Iris compatibility)
            try {
                GL.getCapabilities();
                this.createRenderer();
            } catch (IllegalStateException e) {
                Logger.warn("GL context not available during setWorld, deferring Voxy init to first render frame");
                this.pendingInit = true;
            }
        }
    }

    @Override
    public void createRenderer() {
        // Ensure GL-dependent initialization (Capabilities, SharedIndexBuffer, etc.)
        // This is deferred from onInitializeClient because GL context doesn't exist there
        VoxyClient.ensureInitialized();

        if (this.renderer != null) {
            throw new IllegalStateException("Cannot have multiple renderers");
        }
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (this.world == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.world);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            Logger.error("Voxy failed to initialize (likely unsupported GPU). Disabling Voxy.");
            e.printStackTrace();
            this.renderer = null;
            VoxyConfig.CONFIG.enabled = false;
            return;
        }
        instance.updateDedicatedThreads();
    }

    @Override
    public void shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void injectClose(CallbackInfo ci) {
        this.shutdownRenderer();
    }
}
