package me.cortex.voxy.client.mixin.vitrail;

import me.cortex.voxy.client.compat.vitrail.VitrailVkCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Mixin(targets = "dev.vitrail.pack.source.ShaderPackSource", remap = false)
public abstract class MixinVitrailShaderPackSource {
    @Shadow
    public abstract String rel(Path file);

    @Shadow
    private Map<String, List<String>> linesByFile;

    @Inject(method = "readLines", at = @At("RETURN"), cancellable = true, require = 0)
    private void voxy$patchShaderLines(Path file, CallbackInfoReturnable<List<String>> cir) {
        List<String> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;
        try {
            String rel = this.rel(file);
            List<String> patched = VitrailVkCompat.patchShaderLines(rel, original);
            if (patched != original) {
                if (this.linesByFile != null) {
                    this.linesByFile.put(rel, patched);
                }
                cir.setReturnValue(patched);
            }
        } catch (Throwable ignored) {}
    }
}

