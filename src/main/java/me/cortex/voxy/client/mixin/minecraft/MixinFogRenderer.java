package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.FogRangePolicy;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// RETURN injectors execute in reverse application order at the same return site. Applying this
// mixin after Sodium makes this callback run first, before Sodium snapshots the modified FogData.
@Mixin(value = FogRenderer.class, priority = 900)
public class MixinFogRenderer {
    @Unique
    private static boolean voxy$loggedBlaze3dFogReplacement;

    @Unique
    private static boolean voxy$loggedRenderFogReplacement;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void voxy$modifyFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        boolean blaze3dProbeActive = VoxyConfig.CONFIG.isBlaze3dRenderingEnabled()
                && VoxyBlaze3DProbeRenderer.canApplyTerrainFog();
        boolean nativeRendererActive = VoxyConfig.CONFIG.isRenderingEnabled()
                && IVoxyRenderSystemHolder.getNullable() != null;
        if (!blaze3dProbeActive && !nativeRendererActive) return;

        var data = cir.getReturnValue();
        if (blaze3dProbeActive) {
            // Preserve Minecraft/Sodium's original values for diagnostics and dense-fog
            // classification before replacing the short terrain ramp below.
            VoxyBlaze3DProbeRenderer.captureVanillaRenderFogRange(
                    data.renderDistanceStart, data.renderDistanceEnd);
            if (!voxy$loggedBlaze3dFogReplacement) {
                voxy$loggedBlaze3dFogReplacement = true;
                Logger.info("Blaze3D renderer extending terrain fog on Voxy LoDs only: environmental="
                        + data.environmentalStart + ".." + data.environmentalEnd
                        + ", render-distance=" + data.renderDistanceStart + ".." + data.renderDistanceEnd);
            }
        }

        if (!voxy$loggedRenderFogReplacement) {
            voxy$loggedRenderFogReplacement = true;
            Logger.info("Voxy replacing terrain distance fog; preserving Minecraft sky and cloud fog.");
        }

        var ranges = FogRangePolicy.forVoxy(new FogRangePolicy.Range(
                data.environmentalStart, data.environmentalEnd, data.renderDistanceStart,
                data.renderDistanceEnd, data.skyEnd, data.cloudEnd), true,
                VoxyConfig.CONFIG.getFogMode().removesVanillaEnvFog);
        data.environmentalStart = ranges.environmentalStart();
        data.environmentalEnd = ranges.environmentalEnd();
        data.renderDistanceStart = ranges.renderStart();
        data.renderDistanceEnd = ranges.renderEnd();
    }
}
