package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Per-session Voxy instance. Owns thread pool, world engines, services.
 * TODO: Wire SectionSavingService, VoxelIngestService, ImportManager when ported.
 */
public abstract class VoxyInstance {
    private volatile boolean isRunning = true;
    private final Thread worldCleaner;
    public final BooleanSupplier savingServiceRateLimiter;
    protected final UnifiedServiceThreadPool threadPool;

    private final StampedLock activeWorldLock = new StampedLock();
    private final HashMap<WorldIdentifier, WorldEngine> activeWorlds = new HashMap<>();

    public VoxyInstance() {
        Logger.info("Initializing voxy instance");
        this.threadPool = new UnifiedServiceThreadPool();
        this.savingServiceRateLimiter = () -> true;
        this.worldCleaner = new Thread(() -> {
            try {
                while (this.isRunning) {
                    //noinspection BusyWait
                    Thread.sleep(1000);
                    this.cleanIdle();
                }
            } catch (InterruptedException e) {
                // Exiting
            } catch (Exception e) {
                Logger.error("Exception in world cleaner", e);
            }
        });
        this.worldCleaner.setPriority(Thread.MIN_PRIORITY);
        this.worldCleaner.setName("Active world cleaner");
        this.worldCleaner.setDaemon(true);
        this.worldCleaner.start();
    }

    protected void setNumThreads(int threads) {
        if (threads < 0) throw new IllegalArgumentException("Num threads <0");
        if (this.threadPool.setNumThreads(threads)) {
            Logger.info("Dedicated voxy thread pool size: " + threads);
        }
    }

    public void updateDedicatedThreads() {
        this.setNumThreads(3);
    }

    public ServiceManager getServiceManager() {
        return this.threadPool.serviceManager;
    }

    public UnifiedServiceThreadPool getThreadPool() {
        return this.threadPool;
    }

    public WorldEngine getNullable(WorldIdentifier identifier) {
        if (!this.isRunning) return null;
        var cache = identifier.cachedEngineObject;
        WorldEngine world;
        if (cache == null) {
            world = null;
        } else {
            world = cache.get();
            if (world == null) {
                identifier.cachedEngineObject = null;
            } else {
                if (world.isLive()) {
                    // Successful cache hit
                } else {
                    identifier.cachedEngineObject = null;
                    world = null;
                }
            }
        }
        if (world == null) {
            long stamp = this.activeWorldLock.readLock();
            world = this.activeWorlds.get(identifier);
            this.activeWorldLock.unlockRead(stamp);
            if (world != null) {
                identifier.cachedEngineObject = new WeakReference<>(world);
            }
        }
        if (world != null) {
            world.markActive();
        }
        return world;
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier) {
        return this.getOrCreate(identifier, false);
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier, boolean incrementRef) {
        if (!this.isRunning) {
            Logger.error("Tried getting world object on voxy instance but its not running");
            return null;
        }
        var world = this.getNullable(identifier);
        if (world != null) {
            world.markActive();
            if (incrementRef) world.acquireRef();
            return world;
        }
        long stamp = this.activeWorldLock.writeLock();

        if (!this.isRunning) {
            this.activeWorldLock.unlockWrite(stamp);
            Logger.error("Tried getting world object on voxy instance but its not running");
            return null;
        }

        world = this.activeWorlds.get(identifier);
        if (world == null) {
            world = this.createWorld(identifier);
        }
        world.markActive();
        if (incrementRef) world.acquireRef();

        this.activeWorldLock.unlockWrite(stamp);
        identifier.cachedEngineObject = new WeakReference<>(world);
        return world;
    }

    protected abstract WorldEngine createWorld(WorldIdentifier identifier);

    public void cleanIdle() {
        List<WorldIdentifier> idleWorlds = null;
        {
            long stamp = this.activeWorldLock.readLock();
            for (var pair : this.activeWorlds.entrySet()) {
                if (pair.getValue().isWorldIdle()) {
                    if (idleWorlds == null) idleWorlds = new ArrayList<>();
                    idleWorlds.add(pair.getKey());
                }
            }
            this.activeWorldLock.unlockRead(stamp);
        }

        if (idleWorlds != null) {
            long stamp = this.activeWorldLock.writeLock();
            for (var id : idleWorlds) {
                var world = this.activeWorlds.remove(id);
                if (world == null) continue;
                if (!world.isWorldIdle()) {
                    this.activeWorlds.put(id, world);
                    continue;
                }
                Logger.info("Shutting down idle world: " + id.getLongHash());
                world.free();
            }
            this.activeWorldLock.unlockWrite(stamp);
        }
    }

    public void addDebug(List<String> debug) {
        debug.add("MemoryBuffer, Count/Size (mb): " + MemoryBuffer.getCount() + "/" + (MemoryBuffer.getTotalSize() / 1_000_000));
        debug.add("AWSC: [" + this.activeWorlds.values().stream().map(a -> "" + a.getActiveSectionCount()).collect(Collectors.joining(", ")) + "]");
    }

    public void shutdown() {
        Logger.info("Shutting down voxy instance");
        this.isRunning = false;
        try {
            this.worldCleaner.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.cleanIdle();

        long stamp = this.activeWorldLock.writeLock();
        if (!this.activeWorlds.isEmpty()) {
            for (var world : this.activeWorlds.values()) {
                world.free();
            }
            this.activeWorlds.clear();
        }
        try {
            this.threadPool.shutdown();
        } catch (Exception e) {
            Logger.error(e);
        }
        Logger.info("Instance shutdown");
        this.activeWorldLock.unlockWrite(stamp);
    }

    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return true;
    }

    public boolean isRunning() {
        return this.isRunning;
    }
}
