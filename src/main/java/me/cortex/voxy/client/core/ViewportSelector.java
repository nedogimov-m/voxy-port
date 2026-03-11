package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.AbstractFarWorldRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport> {
    public static final boolean VIVECRAFT_INSTALLED = FabricLoader.getInstance().isModLoaded("vivecraft");

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getVivecraftViewport() {
        try {
            Class<?> cdhClass = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            Method getInstance = cdhClass.getMethod("getInstance");
            Object cdh = getInstance.invoke(null);
            var passField = cdhClass.getField("currentPass");
            var pass = passField.get(cdh);
            if (pass == null) {
                return this.defaultViewport;
            }
            return this.extraViewports.computeIfAbsent(pass, a->this.creator.get());
        } catch (Exception e) {
            return this.defaultViewport;
        }
    }

    public T getViewport() {
        if (VIVECRAFT_INSTALLED) {
            return getVivecraftViewport();
        }
        return this.defaultViewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
