package me.cortex.voxy.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Legacy entry point kept for fabric.mod.json compatibility.
 * Actual initialization happens in VoxyClient.
 */
public class Voxy implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Initialization moved to VoxyClient
    }
}
