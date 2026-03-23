package me.cortex.voxy.common.config.section;

// TODO: upstream uses me.cortex.voxy.common.config.ConfigBuildCtx, port has it at common.storage.config
import me.cortex.voxy.common.storage.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;

public abstract class SectionStorageConfig {
    static {
        Serialization.CONFIG_TYPES.add(SectionStorageConfig.class);
    }

    public abstract SectionStorage build(ConfigBuildCtx ctx);
}
