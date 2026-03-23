package me.cortex.voxy.client.core;

import me.cortex.voxy.common.util.TrackedObject;

// Phase 4 stub - will be replaced with full implementation later
public abstract class AbstractRenderPipeline extends TrackedObject {
    // Used by HierarchicalOcclusionTraverser
    public String taaFunction(String functionName) {
        return null; // No TAA by default
    }

    // Used by HierarchicalOcclusionTraverser
    public void bindUniforms() {
        // No-op in stub
    }
}
