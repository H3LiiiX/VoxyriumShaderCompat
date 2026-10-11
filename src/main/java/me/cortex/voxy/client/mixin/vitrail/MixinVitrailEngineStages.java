package me.cortex.voxy.client.mixin.vitrail;

import me.cortex.voxy.client.core.vk.VkFrameCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.vitrail.platform.EngineStages", remap = false)
public class MixinVitrailEngineStages {
    @Inject(
            method = "afterOpaqueFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/vitrail/render/GraphicsApi;suspendLevelPass()V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private static void voxy$afterOpaqueFeatures(CallbackInfo ci) {
        VkFrameCapture.tryRenderPendingVkFrame();
    }
}
