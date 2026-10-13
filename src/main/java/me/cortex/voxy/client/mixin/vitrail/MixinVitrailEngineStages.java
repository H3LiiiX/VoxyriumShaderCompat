package me.cortex.voxy.client.mixin.vitrail;

import me.cortex.voxy.client.compat.vitrail.VitrailVkCompat;
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
        if (!VkFrameCapture.hasPendingFrame()) return;
        //Vitrail has just suspended its level pass, but a geometry hold (chunk/entity/particles) can
        // still be recording; end it so the frame lands here, before the pack's deferred stage
        VitrailVkCompat.endGeometryHold();
        VkFrameCapture.tryRenderPendingVkFrame();
    }

    //Right after the pack's deferred stage, where the real water is drawn: the pack's LOD water goes in
    // here, so the deferred stage only ever saw the floor under it and the water reaches the pack's
    // depth after its depthtex1 (taken in drawBeforeTranslucents) as real water does
    @Inject(
            method = "afterOpaqueFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/vitrail/render/PackChain;drawBeforeTranslucents()V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private static void voxy$afterDeferred(CallbackInfo ci) {
        VitrailVkCompat.endGeometryHold();
        VkFrameCapture.renderDeferredTranslucents();
    }
}
