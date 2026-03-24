package me.cortex.voxy.client.core.rendering;

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
     * Capture current fog parameters from GL state.
     * Returns NONE to ensure LODs are always composited (fogCoversAllRendering=false).
     * Real fog capture caused fogEnd < renderDistance → LODs not blitted → invisible.
     */
    public static FogParameters capture() {
        return NONE;
    }
}
