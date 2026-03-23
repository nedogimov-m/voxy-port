package me.cortex.voxy.client.core;

import net.minecraft.client.MinecraftClient;

public interface IGetVoxyRenderSystem {
    VoxyRenderSystem getVoxyRenderSystem();
    void shutdownRenderer();
    void createRenderer();

    static VoxyRenderSystem getNullable() {
        var lr = (IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer;
        if (lr == null) return null;
        return lr.getVoxyRenderSystem();
    }
}
