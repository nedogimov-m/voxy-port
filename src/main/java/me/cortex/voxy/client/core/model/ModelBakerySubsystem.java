package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;

    // NOTE: Model baking (processAllThings) must run on the render thread because
    // bakeBlockModel/bakeFluidState access MinecraftClient.getBlockRenderManager()
    // which is not thread-safe. Running it on a background thread causes silent failures
    // resulting in red (unbaked) chunks.

    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        this.factory = new ModelFactory(mapper, this.storage);
    }

    private int tickCount = 0;
    public void tick(long totalBudget) {
        // Process model baking on the render thread — MC block render APIs are not thread-safe
        this.factory.processAllThings();
        this.factory.processUploads();
        if (tickCount++ % 300 == 0) { // Every ~5 seconds at 60fps
            System.out.println("[Voxy ModelBakery] tick #" + tickCount +
                " inflight=" + this.factory.getInflightCount() +
                " baked=" + this.factory.getBakedCount() +
                " requested=" + this.seenCount +
                " queueSize=" + this.factory.getBakeQueueSize());
        }
    }

    public void shutdown() {
        this.factory.free();
        this.storage.free();
    }

    //Simplified: addEntry already handles deduplication via idMappings and blockStatesInFlight
    private final ReentrantLock enqueueLock = new ReentrantLock();
    private int requestCount = 0;
    private int seenCount = 0;
    public void requestBlockBake(int blockId) {
        if (requestCount++ < 10) {
            System.out.println("[Voxy] requestBlockBake: blockId=" + blockId + " stateCount=" + this.mapper.getBlockStateCount());
        }
        if (this.mapper.getBlockStateCount() < blockId) {
            Logger.error("Error, got baking request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        this.enqueueLock.lock();
        if (this.factory.addEntry(blockId)) {
            seenCount++;
        }
        this.enqueueLock.unlock();
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("IF/MC: %03d, %04d", this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.factory.getInflightCount();
    }
}
