package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.storage.StorageBackend;
import me.cortex.voxy.common.storage.compressors.ZSTDCompressor;
import me.cortex.voxy.common.storage.config.ConfigBuildCtx;
import me.cortex.voxy.common.storage.config.StorageConfig;
import me.cortex.voxy.common.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private final Path basePath;

    public VoxyClientInstance() {
        super();
        this.basePath = getBasePath();
        this.updateDedicatedThreads();
    }

    @Override
    protected WorldEngine createWorld(WorldIdentifier identifier) {
        var path = this.basePath;
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create voxy storage directory", e);
        }

        String worldId = identifier.getWorldId();
        Logger.info("Creating WorldEngine for world: " + worldId + " at " + path);

        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, path.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, worldId);
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);

        // Load or create storage config
        StorageConfig storageConfig = loadOrCreateStorageConfig(path);
        StorageBackend storage = storageConfig.build(ctx);

        var engine = new WorldEngine(storage, 2, 4, 5);
        engine.instanceIn = this;
        return engine;
    }

    private static StorageConfig loadOrCreateStorageConfig(Path basePath) {
        var configFile = basePath.resolve("config.json");
        if (Files.exists(configFile)) {
            try {
                var config = Serialization.GSON.fromJson(Files.readString(configFile), StorageConfigWrapper.class);
                if (config != null && config.storageConfig != null) {
                    return config.storageConfig;
                }
            } catch (Exception e) {
                Logger.error("Failed to load voxy storage config, using default");
                e.printStackTrace();
            }
        }

        // Create default config
        var baseDB = new RocksDBStorageBackend.Config();
        var compressor = new ZSTDCompressor.Config();
        compressor.compressionLevel = 7;
        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        // Save the default config
        var wrapper = new StorageConfigWrapper();
        wrapper.storageConfig = compression;
        try {
            Files.createDirectories(basePath);
            Files.writeString(configFile, Serialization.GSON.toJson(wrapper));
        } catch (IOException e) {
            Logger.error("Failed to save default voxy storage config");
        }

        return compression;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        RenderResourceReuse.clearResources();
    }

    private static class StorageConfigWrapper {
        public StorageConfig storageConfig;
    }

    private static Path getBasePath() {
        Path basePath = MinecraftClient.getInstance().runDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = MinecraftClient.getInstance().getServer();
        if (iserver != null) {
            basePath = iserver.getSavePath(WorldSavePath.ROOT).resolve("voxy");
        } else {
            var netHandle = MinecraftClient.getInstance().interactionManager;
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = netHandle.networkHandler.getServerInfo();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    basePath = basePath.resolve(info.address.replace(":", "_"));
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
