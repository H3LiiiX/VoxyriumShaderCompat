package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FogRenderer.class, priority = 900)//We must execute before sodium
public class MixinFogRenderer {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void voxy$modifyFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        boolean blaze3dProbeActive = VoxyConfig.CONFIG.isBlaze3dRenderingEnabled()
                && VoxyBlaze3DProbeRenderer.canApplyGlobalFog();
        if (!VoxyConfig.CONFIG.isRenderingEnabled() && !blaze3dProbeActive) return;

        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null && !blaze3dProbeActive) return;

        var data = cir.getReturnValue();
        if (blaze3dProbeActive) {
            // Preserve Minecraft/Sodium's original values for diagnostics and dense-fog
            // classification before replacing the short terrain ramp below.
            VoxyBlaze3DProbeRenderer.captureVanillaRenderFogRange(
                    data.renderDistanceStart, data.renderDistanceEnd);
        }

        boolean fogIsDamnClose = data.environmentalEnd < 10;
        var mode = VoxyConfig.CONFIG.getFogMode();
        if (mode.removesVanillaEnvFog && !fogIsDamnClose) {
            data.environmentalStart = 99999999;
            data.environmentalEnd = 99999999;
        }

        // Suppress only Minecraft/Sodium's short terrain render-distance fog so Voxy LODs
        // extend seamlessly beyond vanilla chunk render distance.
        // Sky and cloud fog ranges (data.skyEnd, data.cloudEnd) MUST be preserved so the sky dome
        // fades softly into the horizon fog color rather than creating a hard cutoff line.
        data.renderDistanceStart = 999999999;
        data.renderDistanceEnd = 999999999;
    }
}
