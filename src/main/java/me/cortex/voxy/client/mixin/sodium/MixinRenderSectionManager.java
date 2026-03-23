package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Shadow @Final private ClientWorld world;

    // Debounce map: chunkPos (long) -> last ingest timestamp (ms).
    // Prevents re-ingesting the same chunk more than once per 2 seconds
    // when many blocks change rapidly (leaf decay, explosions, water flow).
    @Unique
    private final ConcurrentHashMap<Long, Long> voxy$lastIngestTime = new ConcurrentHashMap<>();

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void injectIngestOnLoad(int x, int z, CallbackInfo ci) {
        var renderer = ((IGetVoxyRenderSystem)(world.worldRenderer)).getVoxyRenderSystem();
        if (renderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            WorldChunk chunk = world.getChunk(x, z);
            if (chunk != null) {
                renderer.getEngine().ingestService.enqueueIngest(chunk);
            }
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngestOnUnload(int x, int z, CallbackInfo ci) {
        var renderer = ((IGetVoxyRenderSystem)(world.worldRenderer)).getVoxyRenderSystem();
        if (renderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            WorldChunk chunk = world.getChunk(x, z);
            if (chunk != null) {
                renderer.getEngine().ingestService.enqueueIngest(chunk);
            }
        }
    }

    // Hook into block changes in loaded chunks (tree cutting, leaf decay, explosions, etc.)
    // scheduleRebuild is called by Sodium when a block changes in a loaded section.
    // Parameters are section coordinates (x, y, z). Section x/z == chunk x/z.
    // Debounced to max once per 2 seconds per chunk to avoid flooding the ingest queue.
    @Inject(method = "scheduleRebuild", at = @At("HEAD"))
    private void injectIngestOnBlockChange(int x, int y, int z, boolean important, CallbackInfo ci) {
        var renderer = ((IGetVoxyRenderSystem)(world.worldRenderer)).getVoxyRenderSystem();
        if (renderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            long chunkKey = (long) x << 32 | (z & 0xFFFFFFFFL);
            long now = System.currentTimeMillis();
            Long lastTime = voxy$lastIngestTime.get(chunkKey);
            if (lastTime != null && (now - lastTime) < 2000) {
                return; // Skip -- already ingested this chunk recently
            }
            voxy$lastIngestTime.put(chunkKey, now);

            // Clean up old entries periodically (every 64 ingests)
            if (voxy$lastIngestTime.size() > 256) {
                voxy$lastIngestTime.entrySet().removeIf(e -> (now - e.getValue()) > 10000);
            }

            WorldChunk chunk = world.getChunk(x, z);
            if (chunk != null) {
                renderer.getEngine().ingestService.enqueueIngest(chunk);
            }
        }
    }
}
