package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkManager.class)
public class MixinClientChunkManager {
    @Shadow @Final ClientWorld world;

    // PRESERVED: 1.20.1 signature uses (int x, int z), NOT ChunkPos (commit 6a813568 LVT order fix)
    @Inject(require = 0, method = "unload", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientChunkManager$ClientChunkMap;compareAndSet(ILnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/chunk/WorldChunk;)Lnet/minecraft/world/chunk/WorldChunk;", shift = At.Shift.BEFORE))
    private void injectUnload(int x, int z, CallbackInfo ci) {
        var renderer = ((IGetVoxyRenderSystem)(world.worldRenderer)).getVoxyRenderSystem();
        if (renderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            var chunk = world.getChunk(x, z);
            if (chunk != null) {
                renderer.getEngine().ingestService.enqueueIngest(chunk);
            }
        }
    }
}
