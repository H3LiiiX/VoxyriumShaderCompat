package me.cortex.voxy.client.mixin.vitrail;

import me.cortex.voxy.client.compat.vitrail.VitrailVkCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.vitrail.sodium.ShadowTerrain", remap = false)
public class MixinVitrailShadowTerrain {
    @Inject(method = "draw", at = @At("HEAD"), require = 0, remap = false)
    private static void voxy$enterShadowStage(CallbackInfo ci) {
        VitrailVkCompat.enterShadowStage();
    }

    @Inject(method = "draw", at = @At("RETURN"), require = 0, remap = false)
    private static void voxy$exitShadowStage(CallbackInfo ci) {
        VitrailVkCompat.exitShadowStage();
    }
}
