package me.cortex.voxy.client.core.model;

public class IdNotYetComputedException extends RuntimeException {
    public final boolean isIdBlockId;
    public final int id;

    /** Auxiliary data for neighbor section processing. Set by RenderDataFactory when catching. */
    public long[] auxData;
    /** Bit mask of which neighbor faces have data. Set by RenderDataFactory when catching. */
    public int auxBitMsk;

    public IdNotYetComputedException(int id) {
        super("Id not yet computed: " + id);
        this.id = id;
        this.isIdBlockId = false;
    }

    public IdNotYetComputedException(int id, boolean isBlockId) {
        super((isBlockId ? "Block" : "Client state") + " id not yet computed: " + id);
        this.id = id;
        this.isIdBlockId = isBlockId;
    }
}
