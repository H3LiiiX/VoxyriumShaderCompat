//? if MC_26_3
package me.cortex.voxy.client.mixin.sodium;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import me.cortex.voxy.client.core.VoxyTerrainRendering;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinTerrainRenderContext {
    @Inject(method = "drawChunkLayer", at = @At("HEAD"))
    private void voxy$captureTerrain(RenderPass pass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                    double x, double y, double z, GpuSampler sampler, OitStage stage, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            VoxyTerrainRendering.capture(matrices, x, y, z);
        }
    }
}
//? endif
