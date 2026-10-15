//? if MC_26_3
package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.VoxyTerrainRendering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(LevelRenderer.class)
public class MixinTerrainRenderPasses {
    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void voxy$insertOpaqueBeforeWater(LevelRenderer renderer, ChunkSectionsToRender sections,
                                             FeatureRenderDispatcher.PreparedFrame features, RenderPass solidPass,
                                             Operation<Void> original) {
        if (!VoxyTerrainRendering.needsSeparateTerrainPass()) {
            original.call(renderer, sections, features, solidPass);
            return;
        }
        // Classic transparency shares the solid pass in 26.3. End it before Voxy submits
        // its seabed, then let Sodium blend water over the completed opaque scene.
        // FrontendRenderPass.close is idempotent when the caller closes solidPass again.
        solidPass.close();
        VoxyTerrainRendering.renderOpaque();
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (RenderPass waterPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Terrain classic transparency after Voxy", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(waterPass);
            original.call(renderer, sections, features, waterPass);
        }
    }

    // 26.3's caller owns the terrain pass. Opening Voxy's passes inside Sodium's draw would nest them.
    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;close()V", ordinal = 0, shift = At.Shift.AFTER))
    private void voxy$afterTerrainPass(GpuBufferSlice fog, boolean useOit, ChunkSectionsToRender sections,
                                     FeatureRenderDispatcher.PreparedFrame features, boolean seeThrough,
                                     boolean alwaysOnTop, CallbackInfo ci) {
        VoxyTerrainRendering.renderOpaque();
        if (!useOit) VoxyTerrainRendering.renderTranslucent();
    }

    @Inject(method = "executeOit", at = @At("TAIL"))
    private void voxy$afterOitComposite(CallbackInfo ci) {
        VoxyTerrainRendering.renderTranslucent();
    }
}
//? endif
