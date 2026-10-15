package me.cortex.voxy.client.core.backend.blaze3d;

//? if MC_26_3
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//? else
import com.mojang.blaze3d.pipeline.RenderPipeline;
//? endif
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.Identifier;

/** Descriptors are built before device initialization; the active device selects the shader variant at draw time. */
record Blaze3dDiscardPipelines(RenderPipeline normal, RenderPipeline sampleMask) {
    static Blaze3dDiscardPipelines build(RenderPipeline.Builder builder) {
        RenderPipeline normal = builder.build();
        Identifier location = normal.getLocation();
        RenderPipeline masked = builder.withLocation(Identifier.fromNamespaceAndPath(
                location.getNamespace(), location.getPath() + "_sample_mask"))
                .withShaderDefine("VOXY_SAMPLE_MASK_DISCARD").build();
        return new Blaze3dDiscardPipelines(normal, masked);
    }

    static boolean needsSampleMask() {
        var device = RenderSystem.getDevice().getDeviceInfo();
        return Blaze3dDiscardPolicy.needsSampleMask(System.getProperty("os.name", ""),
                device.backendName(), device.vendorName());
    }

    RenderPipeline get() {
        return needsSampleMask() ? this.sampleMask : this.normal;
    }
}
