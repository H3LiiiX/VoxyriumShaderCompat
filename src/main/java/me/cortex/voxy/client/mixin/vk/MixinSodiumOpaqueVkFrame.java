//? if MC_26_3
package me.cortex.voxy.client.mixin.vk;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.vk.MinecraftVkHost;
import me.cortex.voxy.client.core.vk.MinecraftVkHostAdapter;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//The pure-Vulkan render capture entry point.
//
//Captures frame matrices and camera coordinates at the TAIL of Sodium's OPAQUE
// drawChunkLayer. Sodium's opaque terrain has just been recorded into MC's RenderPass.
// Execution of the pure-Vulkan LOD frame is deferred until the RenderPass closes,
// so Voxy's dynamic rendering and barriers are never recorded inside an active pass.
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumOpaqueVkFrame {

    @Inject(method = "drawChunkLayer", at = @At("TAIL"), remap = false)
    private void voxy$captureVkFrame(RenderPass pass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                     double x, double y, double z, GpuSampler sampler, OitStage stage, CallbackInfo ci) {
        if (group != ChunkSectionLayerGroup.OPAQUE) return;
        if (!(MinecraftVkHost.get() instanceof MinecraftVkHostAdapter)) return;

        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null || renderer.vkCore == null) return;

        try {
            var target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
            me.cortex.voxy.client.core.vk.VkFrameCapture.capture(target, matrices, x, y, z);
        } catch (Throwable t) {
            Logger.error("Voxy VK frame capture failed", t);
        }
    }
}
//? else
package me.cortex.voxy.client.mixin.vk;

import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.vk.MinecraftVkHost;
import me.cortex.voxy.client.core.vk.MinecraftVkHostAdapter;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//The pure-Vulkan render entry point.
//
//NOTE: the earlier hook on the TAIL of vanilla ChunkSectionsToRender.renderGroup
// NEVER fired, because Sodium 0.9.1 — which renders terrain on MC's Vulkan
// device too — injects at the HEAD of renderGroup and calls
// CallbackInfo.cancel(), so the vanilla RETURN (and every @At("TAIL") injector)
// is bypassed. Sodium instead draws through SodiumWorldRenderer#drawChunkLayer.
// We therefore trigger Voxy's frame at the TAIL of drawChunkLayer for the
// OPAQUE group: Sodium's opaque terrain has just been drawn, its render pass
// is closed, the frame command buffer is recording, and MC's depth buffer
// holds vanilla terrain — exactly the state Voxy's VK frame needs.
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumOpaqueVkFrame {

    @Inject(method = "drawChunkLayer", at = @At("TAIL"), remap = false)
    private void voxy$renderVkFrame(ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                    double x, double y, double z, GpuSampler sampler, CallbackInfo ci) {
        if (group != ChunkSectionLayerGroup.OPAQUE) return;
        if (!(MinecraftVkHost.get() instanceof MinecraftVkHostAdapter adapter)) return;

        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null || renderer.vkCore == null) return;

        try {
            renderer.vkCore.renderFrame(group.outputTarget(), adapter, matrices, x, y, z);
        } catch (Throwable t) {
            //Never take down MC's frame; log loudly instead
            Logger.error("Voxy VK frame failed", t);
        }
    }
}
//? endif
