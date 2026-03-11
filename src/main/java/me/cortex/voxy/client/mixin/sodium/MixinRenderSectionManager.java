package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxelCore;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Shadow @Final private ClientWorld world;

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void injectIngestOnLoad(int x, int z, CallbackInfo ci) {
        var core = ((IGetVoxelCore)(world.worldRenderer)).getVoxelCore();
        if (core != null && VoxyConfig.CONFIG.ingestEnabled) {
            WorldChunk chunk = world.getChunk(x, z);
            if (chunk != null) {
                core.enqueueIngest(chunk);
            }
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngestOnUnload(int x, int z, CallbackInfo ci) {
        var core = ((IGetVoxelCore)(world.worldRenderer)).getVoxelCore();
        if (core != null && VoxyConfig.CONFIG.ingestEnabled) {
            WorldChunk chunk = world.getChunk(x, z);
            if (chunk != null) {
                core.enqueueIngest(chunk);
            }
        }
    }
}
