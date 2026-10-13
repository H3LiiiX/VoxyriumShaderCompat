package me.cortex.voxy.client.mixin.vitrail;

import me.cortex.voxy.client.compat.vitrail.VitrailVkCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.Map;

@Mixin(targets = "dev.vitrail.pack.option.EngineDefines", remap = false)
public class MixinVitrailEngineDefines {
    @Inject(
        method = "table(Ldev/vitrail/pack/option/EngineDefines$Environment;)Ljava/util/Map;",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private static void voxy$addEngineDefines(CallbackInfoReturnable<Map<String, String>> cir) {
        Map<String, String> defines = cir.getReturnValue();
        if (defines != null) {
            defines.put("VOXY_LOD", "");
            defines.put("VOXY_RENDER_DISTANCE", String.format(Locale.ROOT, "%.1f", VitrailVkCompat.getVoxyRenderDistanceBlocks()));
        }
    }
}
