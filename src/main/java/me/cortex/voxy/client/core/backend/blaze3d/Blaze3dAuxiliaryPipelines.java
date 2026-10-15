package me.cortex.voxy.client.core.backend.blaze3d;

//? if MC_26_3
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
//? else
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
//? endif
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** Explicit attachment formats are required even when a pipeline disables colour writes. */
final class Blaze3dAuxiliaryPipelines {
    static final Blaze3dDiscardPipelines SODIUM_DEPTH_VARIANTS = Blaze3dDiscardPipelines.build(
            fullscreen("blaze3d_lod_depth_seed", GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true)));
    static final RenderPipeline SODIUM_DEPTH = SODIUM_DEPTH_VARIANTS.normal();
    static final RenderPipeline HIZ_COPY = fullscreen("blaze3d_hiz_copy", GpuFormat.R32_FLOAT,
            ColorTargetState.WRITE_RED).build();
    static final RenderPipeline HIZ_REDUCE = fullscreen("blaze3d_hiz_reduce", GpuFormat.R32_FLOAT,
            ColorTargetState.WRITE_RED).build();
    static final RenderPipeline HIZ_VISIBILITY = fullscreen("blaze3d_hiz_visibility", GpuFormat.R8_UNORM,
            ColorTargetState.WRITE_RED).withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("VoxyOcclusionOrigins", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform("VoxyOcclusionBounds", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT).build()).build();
    static final RenderPipeline HIZ_COMMANDS = fullscreen("blaze3d_hiz_commands", GpuFormat.RGBA32_UINT,
            ColorTargetState.WRITE_ALL).withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("VoxyOcclusionCounts", UniformType.TEXEL_BUFFER, GpuFormat.RG32_UINT).build()).build();

    private static RenderPipeline.Builder fullscreen(String shader, GpuFormat format, int writeMask) {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxy", shader))
//? if MC_26_3
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
//? else
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
//? endif
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_composite"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxy", "core/" + shader))
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, writeMask)).withCull(false);
    }
    private Blaze3dAuxiliaryPipelines() {}
}
