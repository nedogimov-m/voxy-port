package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Simple fog parameters record, replacing Sodium's FogParameters which doesn't exist in 0.5.x (1.20.1).
 * Captures the current fog state for use in the rendering pipeline.
 */
public record FogParameters(
        float environmentalStart,
        float environmentalEnd,
        float red,
        float green,
        float blue,
        float alpha
) {
    public static final FogParameters NONE = new FogParameters(0, Float.MAX_VALUE, 0, 0, 0, 0);

    /**
     * Capture current fog parameters from RenderSystem state.
     * In 1.20.1, RenderSystem stores fog start/end and color.
     */
    public static FogParameters capture() {
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        float[] color = RenderSystem.getShaderFogColor();
        if (color == null || color.length < 4 || end <= start) {
            return NONE;
        }
        return new FogParameters(start, end, color[0], color[1], color[2], color[3]);
    }
}
