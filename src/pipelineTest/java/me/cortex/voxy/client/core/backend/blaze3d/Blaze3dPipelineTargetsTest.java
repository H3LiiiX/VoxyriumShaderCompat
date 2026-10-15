package me.cortex.voxy.client.core.backend.blaze3d;

//? if MC_26_3
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//? else
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
//? endif
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.Identifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Exercises Minecraft's actual attachment validation without opening a device or the game. */
public final class Blaze3dPipelineTargetsTest {
    private static int accepted;
    public static void main(String[] args) throws Exception {
        Blaze3dUniformLayoutTest.verify();
        verify(Blaze3dAuxiliaryPipelines.SODIUM_DEPTH, GpuFormat.RGBA8_UNORM);
        var variants = Blaze3dAuxiliaryPipelines.SODIUM_DEPTH_VARIANTS;
        verify(variants.sampleMask(), GpuFormat.RGBA8_UNORM);
        if (variants.normal().getShaderDefines().flags().contains("VOXY_SAMPLE_MASK_DISCARD")
                || !variants.sampleMask().getShaderDefines().flags().contains("VOXY_SAMPLE_MASK_DISCARD")
                || variants.normal().getLocation().equals(variants.sampleMask().getLocation())
                || !variants.normal().getDepthStencilState().equals(variants.sampleMask().getDepthStencilState())
//? if MC_26_3
                || !variants.normal().getColorTargetStates().equals(variants.sampleMask().getColorTargetStates())) {
//? else
                || !variants.normal().getColorTargetState().equals(variants.sampleMask().getColorTargetState())) {
//? endif
            throw new AssertionError("Discard variants must have independent shader defines/IDs and identical attachment state");
        }
        verify(Blaze3dAuxiliaryPipelines.HIZ_COPY, GpuFormat.R32_FLOAT);
        verify(Blaze3dAuxiliaryPipelines.HIZ_REDUCE, GpuFormat.R32_FLOAT);
        verify(Blaze3dAuxiliaryPipelines.HIZ_VISIBILITY, GpuFormat.R8_UNORM);
        verify(Blaze3dAuxiliaryPipelines.HIZ_COMMANDS, GpuFormat.RGBA32_UINT);
//? if MC_26_3
        // 26.3 no longer creates a default colour attachment. Check every production pass.
        for (String name : List.of("MARKER_PIPELINE", "LOD_PIPELINE", "LOD_WATER_PIPELINE",
                "LOD_OVERLAY_PIPELINE", "LOD_WATER_OVERLAY_PIPELINE", "LOD_COMPOSITE_PIPELINE",
                "LOD_TRANSLUCENT_COMPOSITE_PIPELINE")) {
            var field = VoxyBlaze3DProbeRenderer.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Blaze3dDiscardPipelines pair) {
                verify(pair.normal(), GpuFormat.RGBA8_UNORM);
                verify(pair.sampleMask(), GpuFormat.RGBA8_UNORM);
            } else {
                verify((RenderPipeline) value, GpuFormat.RGBA8_UNORM);
            }
        }
        if (Blaze3dAuxiliaryPipelines.SODIUM_DEPTH.getColorTargetStates().getFirst().writeMask() != ColorTargetState.WRITE_NONE
//? else
        if (Blaze3dAuxiliaryPipelines.SODIUM_DEPTH.getColorTargetState().writeMask() != ColorTargetState.WRITE_NONE
//? endif
                || !Blaze3dAuxiliaryPipelines.SODIUM_DEPTH.wantsDepthTexture()) {
            throw new AssertionError("Depth prefill must write depth without writing colour");
        }
        for (RenderPipeline pipeline : List.of(Blaze3dAuxiliaryPipelines.HIZ_COPY,
                Blaze3dAuxiliaryPipelines.HIZ_REDUCE, Blaze3dAuxiliaryPipelines.HIZ_VISIBILITY,
                Blaze3dAuxiliaryPipelines.HIZ_COMMANDS)) {
            if (pipeline.wantsDepthTexture()) throw new AssertionError("HiZ colour passes have no depth attachment");
        }

        // Negative control reproduces the exact runtime failure from the supplied log.
        RenderPipeline broken = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxy", "invalid_depth_target"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_composite"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_depth_seed"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withColorTargetState(new ColorTargetState(Optional.empty(), null, ColorTargetState.WRITE_NONE)).build();
        try {
            verify(broken, GpuFormat.RGBA8_UNORM);
            throw new AssertionError("Null format was incorrectly accepted as a wildcard");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("format doesn't match")) throw expected;
        }
        // Shader compilation alone cannot detect attachment-format mismatches.
        try {
            verify(Blaze3dAuxiliaryPipelines.HIZ_COMMANDS, GpuFormat.RGBA8_UNORM);
            throw new AssertionError("Integer commands were accepted with a normalized colour target");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("format doesn't match")) throw expected;
        }
//? if MC_26_3
        var missingTarget = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxy", "missing_colour_target"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_composite"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_composite"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP).build();
        try {
            verify(missingTarget, GpuFormat.RGBA8_UNORM);
            throw new AssertionError("Missing colour target was accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("attachment count")) throw expected;
        }
        if (accepted != 19) throw new AssertionError("Invalid pipelines must fail before backend submission");
        System.out.println("Blaze3D pipeline targets: 19 actual variants accepted; missing colour attachment, wrong format and discard variants checked without a GPU.");
//? else
        if (accepted != 6) throw new AssertionError("Invalid pipelines must fail before backend submission");
        System.out.println("Blaze3D pipeline targets: six actual variants accepted; independent discard shaders, logged failure and wrong integer target checked without a GPU.");
//? endif
    }

    private static void verify(RenderPipeline pipeline, GpuFormat format) {
        DeviceInfo info = new DeviceInfo("Headless test", "", "", true, "Test", 1.0f,
                null, null, Set.of(), null, null);
        GpuDeviceBackend device = (GpuDeviceBackend) Proxy.newProxyInstance(GpuDeviceBackend.class.getClassLoader(),
                new Class<?>[]{GpuDeviceBackend.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDeviceInfo")) return info;
                    throw new AssertionError("Unexpected device call: " + method);
                });
        RenderPassBackend backend = (RenderPassBackend) Proxy.newProxyInstance(RenderPassBackend.class.getClassLoader(),
                new Class<?>[]{RenderPassBackend.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setPipeline")) { accepted++; return null; }
                    if (method.getName().equals("close")) return null;
                    throw new AssertionError("Unexpected render backend call: " + method);
                });
//? if MC_26_3
        GpuTexture texture = (GpuTexture) Proxy.newProxyInstance(GpuTexture.class.getClassLoader(),
                new Class<?>[]{GpuTexture.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getFormat")) return format;
                    throw new AssertionError("Unexpected texture call: " + method);
                });
        GpuTextureView view = (GpuTextureView) Proxy.newProxyInstance(GpuTextureView.class.getClassLoader(),
                new Class<?>[]{GpuTextureView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("texture")) return texture;
                    throw new AssertionError("Unexpected texture view call: " + method);
                });
        BackendRenderPipeline compiledBackend = (BackendRenderPipeline) Proxy.newProxyInstance(
                BackendRenderPipeline.class.getClassLoader(), new Class<?>[]{BackendRenderPipeline.class},
                (proxy, method, args) -> { throw new AssertionError("Unexpected pipeline call: " + method); });
        var compiled = new FrontendRenderPipeline(pipeline.getLocation().toString(), compiledBackend,
                pipeline.getVertexFormatBindings(), new Object2IntOpenHashMap<>(), List.of(),
                pipeline.getColorTargetStates(), pipeline.wantsDepthTexture(), pipeline.pushConstantSize());
        try (RenderPass pass = new FrontendRenderPass(backend, device,
                List.of(new RenderPassDescriptor.Attachment<>(view, Optional.empty())), pipeline.wantsDepthTexture(),
                () -> {}, new RenderPass.RenderArea(0, 0, 4, 4))) {
            pass.setPipeline(compiled);
//? else
        GpuTexture texture = new GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT, "Headless target", format, 4, 4, 1, 1) {
            public void close() {}
            public boolean isClosed() { return false; }
        };
        GpuTextureView view = new GpuTextureView(texture, 0, 1) {
            public void close() {}
            public boolean isClosed() { return false; }
        };
        try (RenderPass pass = new RenderPass(backend, device,
                List.of(new RenderPassDescriptor.Attachment<>(view, Optional.empty())), () -> {},
                new RenderPass.RenderArea(0, 0, 4, 4))) {
            pass.setPipeline(pipeline);
//? endif
        }
    }
}
