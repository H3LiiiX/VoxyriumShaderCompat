package me.cortex.voxy.client.mixin.nvidium;

//? if MC_26_3
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
//? else
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
//? endif
import me.cortex.nvidium.RenderPipeline;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderPipeline.class, remap = false)
public class MixinRenderPipeline {
    @Inject(method = "renderFrame", at = @At("RETURN"))
//? if MC_26_3
    private void voxy$injectRender(TerrainRenderPass pass, Viewport viewport, FogParameters fogParameters, ChunkRenderMatrices crm, double px, double py, double pz, GpuSampler terrainSampler, CallbackInfo ci) {
//? else
    private void voxy$injectRender(TerrainRenderPass pass, Viewport frustum, FogParameters fogParameters, ChunkRenderMatrices crm, double px, double py, double pz, GpuSampler terrainSampler, CallbackInfo ci) {
//? endif
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer != null) {
            renderer.renderOpaque(renderer.setupViewport(crm.projection(), crm.modelView(), fogParameters, pass.getTarget().width, pass.getTarget().height, px, py, pz),
                    ((GlTextureView)pass.getTarget().getDepthTextureView()).glId(), ((GlTextureView)pass.getTarget().getColorTextureView()).glId());
        }
    }
}
