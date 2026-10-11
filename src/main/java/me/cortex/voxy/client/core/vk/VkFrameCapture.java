package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.pipeline.RenderTarget;
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
        pendingTarget = target;
        pendingMatrices = matrices;
        pendingX = x;
        pendingY = y;
        pendingZ = z;
        hasPendingFrame = true;
    }

    public static synchronized void tryRenderPendingVkFrame() {
        if (!hasPendingFrame) return;
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

    public static synchronized void reset() {
        hasPendingFrame = false;
        pendingTarget = null;
        pendingMatrices = null;
    }
}

