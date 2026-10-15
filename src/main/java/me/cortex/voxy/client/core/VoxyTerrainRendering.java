//? if MC_26_3
package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.backend.VoxyGraphicsBackend;
import me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.Minecraft;

/** Render-thread context retained until Minecraft closes its terrain pass in 26.3. */
public final class VoxyTerrainRendering {
    private static ChunkRenderMatrices matrices;
    private static CameraTransform camera;
    private static boolean opaqueRendered;

    public static void capture(ChunkRenderMatrices matrices, double x, double y, double z) {
        VoxyTerrainRendering.matrices = matrices;
        camera = new CameraTransform(x, y, z);
        opaqueRendered = false;
    }

    // The native Vulkan renderer records its frame through MixinSodiumOpaqueVkFrame/VkFrameCapture
    // instead, which also holds it for a Vitrail shaderpack's own stages; only Blaze3D renders here
    public static boolean needsSeparateTerrainPass() {
        return matrices != null && !IrisUtil.irisShadowActive() && VoxyGraphicsBackend.usesBlaze3dRenderer();
    }

    public static void renderOpaque() {
        if (matrices == null || opaqueRendered || IrisUtil.irisShadowActive()) return;
        opaqueRendered = true;
        var gameRenderer = Minecraft.getInstance().gameRenderer;
        var target = gameRenderer.mainRenderTarget();
        if (VoxyGraphicsBackend.usesBlaze3dRenderer()) {
            VoxyBlaze3DProbeRenderer.renderOpaque(matrices, target.getColorTextureView(),
                    target.getDepthTextureView(), camera, ((GameRendererStorage) gameRenderer).sodium$getFogParameters());
        }
    }

    public static void renderTranslucent() {
        try {
            if (matrices == null || !VoxyGraphicsBackend.usesBlaze3dRenderer() || IrisUtil.irisShadowActive()) return;
            var gameRenderer = Minecraft.getInstance().gameRenderer;
            var target = gameRenderer.mainRenderTarget();
            var fog = ((GameRendererStorage) gameRenderer).sodium$getFogParameters();
            VoxyBlaze3DProbeRenderer.renderWater(matrices, target.getColorTextureView(), target.getDepthTextureView(), camera, fog);
        } finally {
            matrices = null;
            camera = null;
            opaqueRendered = false;
        }
    }

    private VoxyTerrainRendering() {}
}
//? endif
