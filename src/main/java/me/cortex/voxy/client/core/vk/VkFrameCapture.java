package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.cortex.voxy.client.compat.vitrail.VitrailVkCompat;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;

public final class VkFrameCapture {
    private static ChunkRenderMatrices pendingMatrices;
    private static double pendingX;
    private static double pendingY;
    private static double pendingZ;
    private static RenderTarget pendingTarget;
    private static boolean hasPendingFrame;

    private VkFrameCapture() {}

    public static synchronized void capture(RenderTarget target, ChunkRenderMatrices matrices, double x, double y, double z) {
        //Vitrail draws its shadow map through the same Sodium OPAQUE chunk draw; those matrices/camera
        // belong to the light, never to a Voxy frame
        if (VitrailVkCompat.isShadowStageActive()) return;
        pendingTarget = target;
        pendingMatrices = matrices;
        pendingX = x;
        pendingY = y;
        pendingZ = z;
        hasPendingFrame = true;
    }

    public static synchronized boolean hasPendingFrame() {
        return hasPendingFrame;
    }

    public static synchronized void tryRenderPendingVkFrame() {
        if (!hasPendingFrame) return;
        renderPendingVkFrameInternal();
    }

    private static void renderPendingVkFrameInternal() {
        if (!(MinecraftVkHost.get() instanceof MinecraftVkHostAdapter adapter)) return;
        if (!adapter.isRecordingOutsideRenderPass()) {
            return;
        }
        hasPendingFrame = false;
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null || renderer.vkCore == null) return;
        try {
            renderer.vkCore.renderFrame(pendingTarget, adapter, pendingMatrices, pendingX, pendingY, pendingZ);
        } catch (Throwable t) {
            Logger.error("Voxy VK frame failed", t);
        }
    }

    /** Records the LOD translucents a shaderpack frame left for after the pack's deferred stage. */
    public static synchronized void renderDeferredTranslucents() {
        if (!(MinecraftVkHost.get() instanceof MinecraftVkHostAdapter adapter)) return;
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null || renderer.vkCore == null) return;
        if (!adapter.isRecordingOutsideRenderPass()) {
            Logger.warn("Voxy VK: a render pass is open after the shaderpack's deferred stage, LOD water skipped this frame");
            return;
        }
        try {
            renderer.vkCore.renderDeferredTranslucents(adapter);
        } catch (Throwable t) {
            Logger.error("Voxy VK translucent frame failed", t);
        }
    }

        public static synchronized void reset() {
        hasPendingFrame = false;
        pendingTarget = null;
        pendingMatrices = null;
    }
}

