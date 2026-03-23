package me.cortex.voxy.client.core.model;

public class IdNotYetComputedException extends RuntimeException {
    public IdNotYetComputedException(int id) {
        super("Id not yet computed: " + id);
    }

    public IdNotYetComputedException(int id, boolean isBlockId) {
        super((isBlockId ? "Block" : "Client state") + " id not yet computed: " + id);
    }
}
