package me.cortex.voxy.client.compat.vitrail;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class VitrailVkCompat {
    private static final boolean IS_LOADED = FabricLoader.getInstance().isModLoaded("vitrail");
    private static boolean initialized = false;
    private static Method drawingPackMethod;
    private static Field activeField;
    private static Field targetsField;
    private static Method viewMethod;
    private static Object sideMain;

    private VitrailVkCompat() {}

    private static synchronized void init() {
        if (!IS_LOADED || initialized) return;
        initialized = true;
        try {
            Class<?> packChainClass = Class.forName("dev.vitrail.render.PackChain");
            Class<?> colorTargetsClass = Class.forName("dev.vitrail.render.ColorTargets");
            Class<?> sideClass = Class.forName("dev.vitrail.pack.target.TargetSchedule$Side");

            drawingPackMethod = packChainClass.getMethod("drawingPack");

            activeField = packChainClass.getDeclaredField("active");
            activeField.setAccessible(true);

            targetsField = packChainClass.getDeclaredField("targets");
            targetsField.setAccessible(true);

            viewMethod = colorTargetsClass.getDeclaredMethod("view", int.class, sideClass);
            viewMethod.setAccessible(true);

            for (Object constant : sideClass.getEnumConstants()) {
                if ("MAIN".equals(constant.toString())) {
                    sideMain = constant;
                    break;
                }
            }
            Logger.info("Vitrail Vulkan compatibility initialized");
        } catch (Throwable t) {
            Logger.error("Failed to initialize Vitrail Vulkan compatibility", t);
            drawingPackMethod = null;
            activeField = null;
            targetsField = null;
            viewMethod = null;
            sideMain = null;
        }
    }

    public static boolean isDrawingPack() {
        if (!IS_LOADED) return false;
        if (!initialized) init();
        if (drawingPackMethod == null) return false;
        try {
            return (boolean) drawingPackMethod.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    public static GpuTextureView getColortex0() {
        if (!IS_LOADED) return null;
        if (!initialized) init();
        if (drawingPackMethod == null || activeField == null || targetsField == null || viewMethod == null || sideMain == null) {
            return null;
        }
        try {
            if (!(boolean) drawingPackMethod.invoke(null)) {
                return null;
            }
            Object activeChain = activeField.get(null);
            if (activeChain == null) {
                return null;
            }
            Object targets = targetsField.get(activeChain);
            if (targets == null) {
                return null;
            }
            return (GpuTextureView) viewMethod.invoke(targets, 0, sideMain);
        } catch (Throwable t) {
            return null;
        }
    }
}
