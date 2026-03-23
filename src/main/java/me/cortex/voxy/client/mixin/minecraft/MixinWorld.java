package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Injects WorldIdentifier into World instances so that WorldIdentifier.of(world) works.
 * Required for the new VoxyRenderSystem lifecycle to resolve world engines.
 */
@Mixin(World.class)
public class MixinWorld implements IWorldGetIdentifier {
    @Unique
    private WorldIdentifier voxy$identifier;

    // MC 1.20.1 Yarn World constructor:
    // protected World(MutableWorldProperties properties, RegistryKey<World> registryRef,
    //     DynamicRegistryManager registryManager, RegistryEntry<DimensionType> dimensionEntry,
    //     Supplier<Profiler> profiler, boolean isClient, boolean debugWorld,
    //     long biomeAccess, int maxChainedNeighborUpdates)
    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$injectIdentifier(MutableWorldProperties properties,
                                       RegistryKey<World> registryRef,
                                       DynamicRegistryManager registryManager,
                                       RegistryEntry<DimensionType> dimensionEntry,
                                       Supplier<Profiler> profiler,
                                       boolean isClient,
                                       boolean debugWorld,
                                       long biomeAccess,
                                       int maxChainedNeighborUpdates,
                                       CallbackInfo ci) {
        if (registryRef != null) {
            this.voxy$identifier = new WorldIdentifier(
                    registryRef,
                    biomeAccess,
                    dimensionEntry == null ? null : dimensionEntry.getKey().orElse(null)
            );
        } else {
            this.voxy$identifier = null;
        }
    }

    @Override
    public WorldIdentifier voxy$getIdentifier() {
        return this.voxy$identifier;
    }
}
