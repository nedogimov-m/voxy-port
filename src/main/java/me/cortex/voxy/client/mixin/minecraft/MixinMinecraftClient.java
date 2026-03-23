package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {
    // Safety net: ensure VoxyCommon instance is shut down when disconnecting.
    // Primary shutdown happens in MixinWorldRenderer.setWorld(null), but this
    // catch-all handles edge cases where setWorld might not fire.
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("TAIL"), require = 0)
    private void voxy$injectWorldClose(Screen screen, CallbackInfo ci) {
        VoxyCommon.shutdownInstance();
    }
}
