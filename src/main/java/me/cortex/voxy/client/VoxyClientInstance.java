package me.cortex.voxy.client;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;

public class VoxyClientInstance extends VoxyInstance {
    @Override
    protected WorldEngine createWorld(WorldIdentifier identifier) {
        // TODO: implement proper world creation with storage backend
        return null;
    }
}
