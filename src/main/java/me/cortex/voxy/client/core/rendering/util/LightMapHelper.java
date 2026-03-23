package me.cortex.voxy.client.core.rendering.util;

import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

import net.minecraft.client.MinecraftClient;

public class LightMapHelper {
    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        // In 1.20.1 (Yarn): LightmapTextureManager has a NativeImageBackedTexture
        // accessible via .texture field, and NativeImageBackedTexture extends AbstractTexture
        // which has getGlId()
        return MinecraftClient.getInstance().gameRenderer.getLightmapTextureManager().texture.getGlId();
    }
}
