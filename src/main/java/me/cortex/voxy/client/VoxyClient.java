package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;

    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
            Logger.error("Voxy is unsupported on your system.");
            return;
        }

        //Try acquire an exclusive lock
        try {
            var dir = FabricLoader.getInstance().getGameDir().resolve(".voxy");
            dir.toFile().mkdirs();
            var lockFile = new FileOutputStream(dir.resolve("voxy.lock").toFile());
            EXCLUSIVE_LOCK = lockFile.getChannel().tryLock();
            if (EXCLUSIVE_LOCK == null) {
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }
        } catch (FileNotFoundException | NonWritableChannelException e) {
            Logger.error("Failed to create exclusive voxy lock file, mod will be disabled");
            systemSupported = false;
        } catch (IOException e) {
            Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
            systemSupported = false;
        }

        if (systemSupported) {
            SharedIndexBuffer.INSTANCE.id();
            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, voxy prefix sum performance will be degraded");
            }
        }
    }

    @Override
    public void onInitializeClient() {
        initVoxyClient();

        //FREX integration
        FabricLoader.getInstance()
                .getEntrypoints("frex_flawless_frames", Consumer.class)
                .forEach(api -> ((Consumer<Function<String, Consumer<Boolean>>>) api).accept(name -> active -> {
                    if (active) {
                        FREX.add(name);
                    } else {
                        FREX.remove(name);
                    }
                }));
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;
    }
}
