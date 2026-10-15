package me.cortex.voxy.client.core.vk.render;

//? if MC_26_3
import com.mojang.renderpearl.api.textures.GpuTextureView;
//? else
import com.mojang.blaze3d.textures.GpuTextureView;
//? endif
import me.cortex.voxy.client.compat.vitrail.VitrailVoxyPack;
import me.cortex.voxy.client.compat.vitrail.VoxyPackPatch;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.ShadercCompiler;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkDownloadStream;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

//Pure-VK mirror of MDICSectionRenderer: the same six GPU passes (prep,
// raster-cull, command generation, translucency prefix sort + build, then
// opaque / temporal / translucent indexed-indirect-count draws) against the
// same buffer layouts, recorded into MC's frame command buffer with dynamic
// rendering over Voxy's offscreen colour + depth-stencil targets.
public class VkTerrainRenderer {
    //Draw-command offsets shared with the cmdgen/translucent shader defines.
    private static final int TRANSLUCENT_OFFSET = VkViewport.OPAQUE_DRAW_COUNT;
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + VkViewport.TRANSLUCENT_DRAW_COUNT;

    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final RenderProperties properties;
    private final VkSectionGeometryData geometry;
    private final VkModelStore modelStore;

    //MoltenVK fixed-count fallback state: the last-known REAL per-pass draw counts,
    // read back from drawCountCallBuffer each frame. On desktop the GPU sources
    // these counts itself via vkCmdDrawIndexedIndirectCount. Initialised to 0 and
    // gated by hasAnyReadback: before the first async readback lands, fixedCountBudget
    // returns 0 — no draws at all — so renderOpaque (which runs BEFORE buildDrawCalls
    // and uses last frame's commands) skips entirely on the first frame with geometry.
    // Without this, the headroom floor (1024/256/256) would issue 1024+ no-op Metal
    // draws/frame from the zeroed drawCallBuffer, causing the "2D floating blocks"
    // loading glitch on macOS. Once the first readback lands, hasAnyReadback flips
    // true and fixedCountBudget operates normally (lastKnown*1.5 + headroom).
    private int fbOpaqueDraws = 0;
    private int fbTranslucentDraws = 0;
    private int fbTemporalDraws = 0;
    private boolean hasAnyReadback = false;

    private final VkBuffer uniform;
    private final VkBuffer distanceCountBuffer;
    private final VkBuffer indexBuffer;
    private final long depthBoundSampler;
    private final long lightmapSampler;

    private final VkShaderPipeline prep;
    private final VkShaderPipeline cmdGen;
    private final VkShaderPipeline prefixSum;
    private final VkShaderPipeline translucentGen;
    private final VkShaderPipeline cullRaster;
    private VkShaderPipeline terrainOpaque;
    private VkShaderPipeline terrainTranslucent;
    private int pipelineColorFormat = -1, pipelineDepthFormat = -1;

    //Shaderpack LOD programs (see VitrailVoxyPack): the pack's voxy_opaque / voxy_translucent compiled
    // into quads.frag, drawn into the pack-mode targets of the viewport. Null outside that mode.
    private VoxyPackPatch pack;
    private VkShaderPipeline packOpaque;
    private VkShaderPipeline packTranslucent;//null when the pack has no translucent program
    private List<VoxyPackPatch.Sampler> packOpaqueSamplers = List.of();
    private List<VoxyPackPatch.Sampler> packTranslucentSamplers = List.of();
    private int packDepthFormat = -1;
    private VkShaderPipeline packDepthPipeline;
    private VkShaderPipeline packLightPrepass;
    private final long packWaterSampler;
    private final VkBuffer packFrameUbo;
    private final VkBuffer packUniformUbo;
    private final VkBuffer packDepthParams;
    private final long packNearestSampler;
    private final long[] packSamplers = new long[4];//[linear ? 1 : 0 | repeat ? 2 : 0], what the pack asked for a texture
    private final Matrix4f packModelViewPrev = new Matrix4f();
    private final Matrix4f packProjPrev = new Matrix4f();
    private boolean packHasPrev = false;

    public VkTerrainRenderer(VkFrameCtx ctx, VkUploadStream uploadStream, VkDownloadStream downloadStream,
                             RenderProperties properties, VkSectionGeometryData geometry, VkModelStore modelStore) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.properties = properties;
        this.geometry = geometry;
        this.modelStore = modelStore;

        this.uniform = new VkBuffer(ctx, 1024).zero();
        this.distanceCountBuffer = new VkBuffer(ctx, 1024L * 4 + VkViewport.TRANSLUCENT_DRAW_COUNT * 4L).zero();

        //Shared index buffer: u16 quad pattern + u16 cube indices at CUBE_INDEX_OFFSET
        this.indexBuffer = new VkBuffer(ctx, SharedIndexBuffer.CUBE_INDEX_OFFSET + 6 * 2 * 3 * 2L);
        {
            var quads = SharedIndexBuffer.generateQuadIndicesShort(16380);
            long ptr = uploadStream.upload(this.indexBuffer, 0, this.indexBuffer.size());
            quads.cpyTo(ptr);
            VkCmd.writeCubeIndicesU16(ptr + SharedIndexBuffer.CUBE_INDEX_OFFSET);
            quads.free();
            uploadStream.commit();
            ctx.flushImmediate();
        }
        this.depthBoundSampler = VkImage2D.createSampler(ctx.vk(), false, false);
        this.lightmapSampler = VkImage2D.createSampler(ctx.vk(), false, true);
        this.packFrameUbo = new VkBuffer(ctx, VoxyPackPatch.FRAME_BLOCK_BYTES).zero();
        this.packUniformUbo = new VkBuffer(ctx, PACK_UNIFORM_BYTES).zero();
        this.packDepthParams = new VkBuffer(ctx, 128).zero();
        this.packNearestSampler = VkImage2D.createSampler(ctx.vk(), false, false);
        for (int i = 0; i < 4; i++) {
            this.packSamplers[i] = createPackSampler(ctx, (i & 1) != 0, (i & 2) != 0);
        }
        this.packWaterSampler = VkImage2D.createSampler(ctx.vk(), true, false);//nearest, every mip like the block atlas

        //================= compute pipelines =================
        this.prep = new VkShaderPipeline(ctx, "prep.comp",
                VkShaderSource.load("voxy:lod/gl46/prep.comp", VkShaderSource.defs().build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2)));

        this.cmdGen = new VkShaderPipeline(ctx, "cmdgen.comp",
                VkShaderSource.load("voxy:lod/gl46/cmdgen.comp", VkShaderSource.defs()
                        .def("TRANSLUCENT_WRITE_BASE", 1024)
                        .def("TEMPORAL_OFFSET", TEMPORAL_OFFSET)
                        .def("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)
                        .build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2),
                        VkShaderPipeline.ssbo(3), VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                        VkShaderPipeline.ssbo(6), VkShaderPipeline.ssbo(7)));

        //Subgroup prefix sum on VK when the device advertises subgroup arithmetic
        // (MoltenVK/Metal simdgroups, NVIDIA, AMD, Intel — virtually every VK 1.1+
        // device). Falls back to the shared-memory Hillis-Steele scan otherwise.
        boolean useSubgroup = ctx.vk().subgroupArithmetic;
        this.prefixSum = new VkShaderPipeline(ctx, "prefixsum.comp",
                VkShaderSource.load(useSubgroup ? "voxy:util/prefixsum/inital3_vk.comp" : "voxy:util/prefixsum/simple.comp",
                        VkShaderSource.defs().def("IO_BUFFER", 0).build()),
                0, List.of(VkShaderPipeline.ssbo(0)));

        this.translucentGen = new VkShaderPipeline(ctx, "buildtranslucents.comp",
                VkShaderSource.load("voxy:lod/gl46/buildtranslucents.comp", VkShaderSource.defs()
                        .def("TRANSLUCENT_WRITE_BASE", 1024)
                        .def("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
                        .def("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)
                        .build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2),
                        VkShaderPipeline.ssbo(3), VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5)));

        //================= raster cull pipeline (depth-only, no writes) =================
        var cullDesc = new VkShaderPipeline.GfxDesc();
        cullDesc.name = "cullraster";
        cullDesc.vertGlsl = VkShaderSource.load("voxy:lod/gl46/cull/raster.vert", VkShaderSource.defs().props(properties).build());
        cullDesc.fragGlsl = VkShaderSource.load("voxy:lod/gl46/cull/raster.frag", VkShaderSource.defs().props(properties).build());
        cullDesc.colorFormat = VK_FORMAT_UNDEFINED;
        cullDesc.depthFormat = VK_FORMAT_D32_SFLOAT_S8_UINT;
        cullDesc.stencilFormat = VK_FORMAT_D32_SFLOAT_S8_UINT;
        cullDesc.depthTest = true;
        cullDesc.depthWrite = false;
        cullDesc.colorWrite = false;
        cullDesc.depthCompare = VkCmd.closerEqual(this.properties);
        cullDesc.bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1),
                VkShaderPipeline.ssbo(2), VkShaderPipeline.ssbo(3));
        this.cullRaster = new VkShaderPipeline(ctx, cullDesc);
    }

    private void ensureTerrainPipelines(VkViewport viewport) {
        int cf = viewport.colour.format;
        int df = viewport.depthStencil.format;
        if (this.terrainOpaque != null && cf == this.pipelineColorFormat && df == this.pipelineDepthFormat) return;
        if (this.terrainOpaque != null) {
            this.terrainOpaque.free();
            this.terrainTranslucent.free();
        }
        var cardinalLight = net.minecraft.client.Minecraft.getInstance().level.cardinalLighting();
        String vert = VkShaderSource.load("voxy:lod/gl46/quads3.vert", VkShaderSource.defs().props(this.properties)
                .def("NO_SHADE_FACE_TINT", cardinalLight.up())
                .def("UP_FACE_TINT", cardinalLight.up())
                .def("DOWN_FACE_TINT", cardinalLight.down())
                .def("Z_AXIS_FACE_TINT", cardinalLight.north())
                .def("X_AXIS_FACE_TINT", cardinalLight.east())
                .build());

        var bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(3),
                VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                VkShaderPipeline.sampler(8), VkShaderPipeline.sampler(9), VkShaderPipeline.sampler(10));

        var opaque = new VkShaderPipeline.GfxDesc();
        opaque.name = "terrain-opaque";
        opaque.vertGlsl = vert;
        opaque.fragGlsl = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties)
                .defIf("VOXY_VULKAN_SAMPLE_MASK_DISCARD", this.ctx.vk().needsSampleMaskDiscard)
                .def("ANIMATED_LAVA")
                .build());
        opaque.colorFormat = cf;
        opaque.depthFormat = df;
        opaque.stencilFormat = df;
        opaque.depthTest = true;
        opaque.depthWrite = true;
        opaque.depthCompare = VkCmd.closerEqual(this.properties);
        opaque.blend = false;
        opaque.stencilTestEqual1 = true;//only render where vanilla terrain is absent
        var opaqueBindings = new ArrayList<>(bindings);
        opaqueBindings.add(VkShaderPipeline.sampler(7));//Minecraft's block atlas, for the lava sprite
        opaque.bindings = opaqueBindings;
        opaque.pushConstantBytes = 16;
        this.terrainOpaque = new VkShaderPipeline(this.ctx, opaque);

        var translucent = new VkShaderPipeline.GfxDesc();
        translucent.name = "terrain-translucent";
        translucent.vertGlsl = vert;
        translucent.fragGlsl = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties)
                .defIf("VOXY_VULKAN_SAMPLE_MASK_DISCARD", this.ctx.vk().needsSampleMaskDiscard)
                .def("TRANSLUCENT").build());
        translucent.colorFormat = cf;
        translucent.depthFormat = df;
        translucent.stencilFormat = df;
        translucent.depthTest = true;
        translucent.depthWrite = true;
        translucent.depthCompare = VkCmd.closerEqual(this.properties);
        translucent.blend = true;
        translucent.stencilTestEqual1 = true;
        translucent.bindings = bindings;
        this.terrainTranslucent = new VkShaderPipeline(this.ctx, translucent);

        this.pipelineColorFormat = cf;
        this.pipelineDepthFormat = df;
    }

    //=============================== shaderpack LOD programs ===============================

    private static final int PACK_UNIFORM_BYTES = VoxyPackPatch.MAX_PACK_UNIFORM_BYTES;

    private static long createPackSampler(VkFrameCtx ctx, boolean linear, boolean repeat) {
        try (MemoryStack stack = stackPush()) {
            int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            int address = repeat ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            var sci = org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(linear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(address)
                    .addressModeV(address)
                    .addressModeW(address)
                    .minLod(0).maxLod(VK_LOD_CLAMP_NONE);
            var pSampler = stack.mallocLong(1);
            if (vkCreateSampler(ctx.vk().device, sci, null, pSampler) != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler(pack)");
            }
            return pSampler.get(0);
        }
    }

    /**
     * Makes {@code patch} the program the LODs are drawn with, building its pipelines the first time.
     * Returns false (and leaves pack mode) when there is no patch or it cannot be built, in which case
     * the LODs are drawn with Voxy's own vanilla-lit shaders.
     */
    public boolean preparePack(VoxyPackPatch patch, PackLayout layout, int depthFormat) {
        if (patch == null || layout == null) {
            this.freePack();
            return false;
        }
        if (patch == this.pack && this.packOpaque != null && depthFormat == this.packDepthFormat && layout.equals(this.packLayout)) {
            return true;
        }
        this.freePack();
        try {
            this.buildPack(patch, layout, depthFormat);
            this.pack = patch;
            this.packLayout = layout;
            this.packDepthFormat = depthFormat;
            this.packHasPrev = false;
            return true;
        } catch (Throwable t) {
            this.freePack();
            VitrailVoxyPack.markFailed(patch, t);
            return false;
        }
    }

    private PackLayout packLayout;

    private void buildPack(VoxyPackPatch patch, PackLayout layout, int depthFormat) {
        {
            var d = new VkShaderPipeline.GfxDesc();
            d.name = "pack-depth";
            d.vertGlsl = VkShaderSource.load("voxy:post/fullscreen2.vert", VkShaderSource.defs().props(this.properties).build());
            d.fragGlsl = VkShaderSource.load("voxy:post/pack_depth.frag", VkShaderSource.defs().props(this.properties).build());
            d.pushConstantBytes = 8;
            d.colorFormat = VK_FORMAT_R32_SFLOAT;
            d.depthFormat = VK_FORMAT_UNDEFINED;
            d.stencilFormat = VK_FORMAT_UNDEFINED;
            d.depthTest = false;
            d.depthWrite = false;
            d.blend = false;
            d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            d.bindings = List.of(VkShaderPipeline.sampler(0), VkShaderPipeline.sampler(1), VkShaderPipeline.ubo(2));
            this.packDepthPipeline = new VkShaderPipeline(this.ctx, d);
        }
        var vert = VkShaderSource.load("voxy:lod/gl46/quads3.vert", VkShaderSource.defs().props(this.properties)
                .def("PATCHED_SHADER")
                .def("VOXY_SMOOTH_LIGHT")
                .build());

        {//light prepass: the same geometry and the same vertex stage, so the depth it leaves is exactly the
            // depth the lit pass then draws at
            var d = new VkShaderPipeline.GfxDesc();
            d.name = "terrain-pack-light";
            d.vertGlsl = vert;
            d.fragGlsl = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties)
                    .defIf("VOXY_VULKAN_SAMPLE_MASK_DISCARD", this.packSampleMaskDiscard())
                    .def("PATCHED_SHADER")
                    .def("VOXY_SMOOTH_LIGHT")
                    .def("VOXY_LIGHT_PREPASS")
                    .build());
            d.colorFormat = VkViewport.PACK_LIGHT_FORMAT;
            d.depthFormat = depthFormat;
            d.stencilFormat = depthFormat;
            d.depthTest = true;
            d.depthWrite = true;
            d.depthCompare = VkCmd.closerEqual(this.properties);
            d.blend = false;
            d.stencilTestEqual1 = true;
            d.bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(3),
                    VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                    VkShaderPipeline.sampler(8), VkShaderPipeline.sampler(9), VkShaderPipeline.sampler(10));
            this.packLightPrepass = new VkShaderPipeline(this.ctx, d);
        }

        int opaqueCount = layout.opaqueCount();
        int[] opaqueFormats = new int[opaqueCount + 1];
        System.arraycopy(layout.opaqueFormats(), 0, opaqueFormats, 0, opaqueCount);
        //After the pack's draw buffers: the face / AO flag for Voxy's SSAO (also where an LOD was drawn)
        opaqueFormats[opaqueCount] = VkViewport.PACK_META_FORMAT;

        {
            String frag = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties)
                    .defIf("VOXY_VULKAN_SAMPLE_MASK_DISCARD", this.packSampleMaskDiscard())
                    .def("PATCHED_SHADER")
                    .def("VOXY_SMOOTH_LIGHT")
                    .def("ANIMATED_LAVA")
                    .def("VOXY_PACK_AO_LOCATION", opaqueCount)
                    .build()) + this.packTail(patch.opaqueFragmentTail());
            var spirv = ShadercCompiler.compile(frag, ShaderType.FRAGMENT, "terrain-pack-opaque.frag");
            this.packOpaqueSamplers = usedSamplers(patch, spirv);

            var d = new VkShaderPipeline.GfxDesc();
            d.name = "terrain-pack-opaque";
            d.vertGlsl = vert;
            d.fragSpirv = spirv;
            d.colorFormats = opaqueFormats;
            d.depthFormat = depthFormat;
            d.stencilFormat = depthFormat;
            d.depthTest = true;
            d.depthWrite = true;
            d.depthCompare = VkCmd.closerEqual(this.properties);
            d.blend = false;
            d.stencilTestEqual1 = true;
            var bindings = packBindings(this.packOpaqueSamplers);
            bindings.add(VkShaderPipeline.sampler(6));//the light prepass
            bindings.add(VkShaderPipeline.sampler(7));//Minecraft's block atlas, for the lava sprite
            d.bindings = bindings;
            d.pushConstantBytes = 16;
            this.packOpaque = new VkShaderPipeline(this.ctx, d);
        }

        if (patch.hasTranslucent()) {
            int translucentCount = layout.translucentCount();
            String frag = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties)
                    .defIf("VOXY_VULKAN_SAMPLE_MASK_DISCARD", this.packSampleMaskDiscard())
                    .def("PATCHED_SHADER")
                    .def("TRANSLUCENT")
                    .def("PACK_ANIMATED_WATER")
                    .def("VOXY_TRANSLUCENT_AFTER_DEFERRED")//drawn by renderDeferredTranslucent
                    //After the pack's draw buffers: where the translucent program drew, for the composite
                    .def("VOXY_PACK_AO_LOCATION", translucentCount)
                    .build()) + this.packTail(patch.translucentFragmentTail());
            var spirv = ShadercCompiler.compile(frag, ShaderType.FRAGMENT, "terrain-pack-translucent.frag");
            this.packTranslucentSamplers = usedSamplers(patch, spirv);

            int[] formats = new int[translucentCount + 1];
            System.arraycopy(layout.translucentFormats(), 0, formats, 0, translucentCount);
            formats[translucentCount] = VkViewport.PACK_META_FORMAT;
            int[][] blends = new int[translucentCount + 1][];
            System.arraycopy(layout.translucentBlends(), 0, blends, 0, translucentCount);
            var d = new VkShaderPipeline.GfxDesc();
            d.name = "terrain-pack-translucent";
            d.vertGlsl = vert;
            d.fragSpirv = spirv;
            d.colorFormats = formats;
            d.attachmentBlend = blends;
            d.depthFormat = depthFormat;
            d.stencilFormat = depthFormat;
            d.depthTest = true;
            d.depthWrite = true;
            d.depthCompare = VkCmd.closerEqual(this.properties);
            d.stencilTestEqual1 = true;
            var bindings = packBindings(this.packTranslucentSamplers);
            bindings.add(VkShaderPipeline.sampler(7));//Minecraft's block atlas, for the water sprite
            d.bindings = bindings;
            d.pushConstantBytes = 16;
            try {
                this.packTranslucent = new VkShaderPipeline(this.ctx, d);
            } catch (Throwable t) {
                //The opaque programs stand on their own: without the translucent one the LOD water is
                // Voxy's own, as for a pack with no translucent program, rather than every LOD losing
                // the pack's lighting (MoltenVK turns some of these pipelines down)
                this.packTranslucent = null;
                Logger.error("The shaderpack's translucent Voxy LOD program failed to build, LOD water keeps Voxy's own shading", t);
            }
        }
    }

    //The shaderpack LOD programs on macOS drop their cut-out pixels through a zero sample mask, not a discard:
    // with a pack's code behind it (Complementary, Solas) Apple GPUs too kept the depth of a discarded pixel
    // without its colour, so the pack shaded leaves and bars from whatever its targets held there. That path
    // also never reads gl_HelperInvocation, which SPIRV-Cross then sets before every discard, out of scope in
    // a pack's own function that discards (its LOD water), so Metal refused the whole program
    private boolean packSampleMaskDiscard() {
        return this.ctx.vk().needsSampleMaskDiscard || this.ctx.vk().macOS;
    }

    //A shaderpack's own functions named like Metal's built-ins: MoltenVK keeps the name, and a definition with
    // the very signature of the built-in made every call to it ambiguous (Photon's length_squared), which failed
    // the whole program. Renamed throughout the pack's code on macOS
    private static final String[] METAL_BUILTIN_NAMES = {"length_squared", "distance_squared"};

    private String packTail(String tail) {
        if (!this.ctx.vk().macOS) return tail;
        StringBuilder renames = new StringBuilder("\n");
        for (String name : METAL_BUILTIN_NAMES) {
            renames.append("#define ").append(name).append(" voxy_").append(name).append('\n');
        }
        return renames + tail;
    }

    //Only what the compiled program actually reads gets a descriptor: a pack lists names that only
    // exist under Iris (shadow maps behind options, buffers only its Voxy deferred writes)
    private static List<VoxyPackPatch.Sampler> usedSamplers(VoxyPackPatch patch, java.nio.ByteBuffer spirv) {
        Set<String> loaded = VoxyPackPatch.loadedVariableNames(spirv);
        List<VoxyPackPatch.Sampler> used = new ArrayList<>();
        for (var sampler : patch.samplers()) {
            if (!loaded.contains(sampler.name())) continue;
            if (!sampler.type().equals("sampler2D")) {
                throw new IllegalStateException("the LOD programs read " + sampler.name() + " as a " + sampler.type()
                        + ", which is not served on Vulkan");
            }
            used.add(sampler);
        }
        return List.copyOf(used);
    }

    private static ArrayList<VkShaderPipeline.Binding> packBindings(List<VoxyPackPatch.Sampler> samplers) {
        var bindings = new ArrayList<>(List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(3),
                VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                VkShaderPipeline.sampler(8), VkShaderPipeline.sampler(9), VkShaderPipeline.sampler(10),
                VkShaderPipeline.ubo(VoxyPackPatch.FRAME_UBO_BINDING), VkShaderPipeline.ubo(VoxyPackPatch.PACK_UBO_BINDING)));
        for (var sampler : samplers) {
            bindings.add(VkShaderPipeline.sampler(sampler.binding()));
        }
        return bindings;
    }

    private void freePack() {
        if (this.packOpaque != null || this.packTranslucent != null || this.packDepthPipeline != null
                || this.packLightPrepass != null) {
            //Frames still in flight may be drawing with them (this only happens on a pack/setting change)
            vkDeviceWaitIdle(this.ctx.vk().device);
        }
        if (this.packOpaque != null) this.packOpaque.free();
        if (this.packTranslucent != null) this.packTranslucent.free();
        if (this.packDepthPipeline != null) this.packDepthPipeline.free();
        if (this.packLightPrepass != null) this.packLightPrepass.free();
        this.packLightPrepass = null;
        this.packOpaque = null;
        this.packTranslucent = null;
        this.packDepthPipeline = null;
        this.pack = null;
        this.packLayout = null;
        this.packOpaqueSamplers = List.of();
        this.packTranslucentSamplers = List.of();
    }

    private final Matrix4f packScratch = new Matrix4f();

    //The two blocks the pack's programs read: Voxy's own volume (the vx* names, the pack's view size,
    // and how to turn this backend's depth into the OpenGL one the pack expects), and every other
    // uniform the pack listed, answered by Vitrail exactly as its own programs are answered
    private void uploadPackUniforms(VkViewport viewport) {
        long ptr = this.uploadStream.upload(this.packFrameUbo, 0, VoxyPackPatch.FRAME_BLOCK_BYTES);
        var proj = toPackDepth(viewport.projection, this.packScratch);
        if (!this.packHasPrev) {
            this.packModelViewPrev.set(viewport.modelView);
            this.packProjPrev.set(proj);
            this.packHasPrev = true;
        }
        viewport.modelView.getToAddress(ptr);
        new Matrix4f(viewport.modelView).invert().getToAddress(ptr + 64);
        this.packModelViewPrev.getToAddress(ptr + 128);
        proj.getToAddress(ptr + 192);
        new Matrix4f(proj).invert().getToAddress(ptr + 256);
        this.packProjPrev.getToAddress(ptr + 320);
        //voxy_depthConv: the window depth the pack expects is a * gl_FragCoord.z + b
        boolean reverse = this.properties.isReverseZ();
        MemoryUtil.memPutFloat(ptr + 384, reverse ? -1.0f : 1.0f);
        MemoryUtil.memPutFloat(ptr + 388, reverse ? 1.0f : 0.0f);
        MemoryUtil.memPutFloat(ptr + 392, 0);
        MemoryUtil.memPutFloat(ptr + 396, 0);
        MemoryUtil.memPutFloat(ptr + 400, viewport.width);
        MemoryUtil.memPutFloat(ptr + 404, viewport.height);
        MemoryUtil.memPutInt(ptr + 408, Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32));
        MemoryUtil.memPutInt(ptr + 412, 0);
        //vxViewProj, its inverse and last frame's (Solas reads these)
        var viewProj = new Matrix4f(proj).mul(viewport.modelView);
        viewProj.getToAddress(ptr + 416);
        new Matrix4f(viewProj).invert().getToAddress(ptr + 480);
        new Matrix4f(this.packProjPrev).mul(this.packModelViewPrev).getToAddress(ptr + 544);

        //Next frame's "previous" pair
        this.packModelViewPrev.set(viewport.modelView);
        this.packProjPrev.set(proj);

        long packPtr = this.uploadStream.upload(this.packUniformUbo, 0, PACK_UNIFORM_BYTES);
        VitrailVoxyPack.writeUniforms(packPtr);//sized against PACK_UNIFORM_BYTES when the pack was read
        this.uploadStream.commit();
    }

    //The projection Voxy rasterises with, reversed Z over zero to one on this backend, in the OpenGL
    // form (z from -1 to 1, near at -1) a pack is written against: the z row becomes w - 2z (or 2z - w
    // for a forward depth), which is the same conversion Vitrail hands the pack's own programs
    private Matrix4f toPackDepth(Matrix4f projection, Matrix4f dest) {
        dest.set(projection);
        float sign = this.properties.isReverseZ() ? -2.0f : 2.0f;
        float wSign = this.properties.isReverseZ() ? 1.0f : -1.0f;
        if (this.properties.isZero2One()) {
            dest.m02(wSign * projection.m03() + sign * projection.m02());
            dest.m12(wSign * projection.m13() + sign * projection.m12());
            dest.m22(wSign * projection.m23() + sign * projection.m22());
            dest.m32(wSign * projection.m33() + sign * projection.m32());
        } else if (this.properties.isReverseZ()) {
            dest.m02(-projection.m02());
            dest.m12(-projection.m12());
            dest.m22(-projection.m22());
            dest.m32(-projection.m32());
        }
        return dest;
    }

    private void bindPackSamplers(VkShaderPipeline.Binder b, List<VoxyPackPatch.Sampler> samplers) {
        b.ubo(VoxyPackPatch.FRAME_UBO_BINDING, this.packFrameUbo)
                .ubo(VoxyPackPatch.PACK_UBO_BINDING, this.packUniformUbo);
        GpuTextureView white = VitrailVoxyPack.white();
        for (var sampler : samplers) {
            String name = sampler.name();
            if (name.equals("tex")) {
                //Nothing of an LOD's texture is the pack's: Voxy's own atlas is the closest stand-in
                b.sampler(sampler.binding(), this.modelStore.atlas.view, this.modelStore.atlasSampler,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                continue;
            }
            if (name.startsWith("vxDepthTex") && this.packDepthView != 0) {
                //What the opaque LODs (and the vanilla terrain under them) left, converted by buildPackDepth.
                // The translucent depth is never read by the LOD programs themselves.
                b.sampler(sampler.binding(), this.packDepthView, this.packNearestSampler,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                continue;
            }
            var sampled = VitrailVoxyPack.sampler(name);
            GpuTextureView view = sampled == null ? white : sampled.view();
            long vkSampler = sampled == null ? this.packNearestSampler
                    : this.packSamplers[(sampled.linear() ? 1 : 0) | (sampled.repeat() ? 2 : 0)];
            //Minecraft's Vulkan backend keeps every image it owns in GENERAL
            b.sampler(sampler.binding(), VkFrameHost.vkView(view), vkSampler, VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    private long packDepthView = 0;

    /**
     * Shaderpack LOD mode: converts the opaque depth (offscreen depth must be in shader-read layout)
     * into viewport.packDepth, which the translucent LOD programs read as vxDepthTexOpaque for the
     * water's depth fog and its reflections. Recorded between the opaque and translucent passes.
     */
    public void buildPackDepth(VkViewport viewport, VkCompositor.VkViewportRT rt) {
        this.packDepthView = 0;
        if (this.pack == null || viewport.packDepth == null) return;
        var cmd = this.ctx.cmd();
        {
            long ptr = this.uploadStream.upload(this.packDepthParams, 0, 128);
            new Matrix4f(viewport.vanillaProjection).invert().getToAddress(ptr);
            toPackDepth(viewport.projection, new Matrix4f()).getToAddress(ptr + 64);
            this.uploadStream.commit();
        }
        //Minecraft's depth, read where the vanilla terrain covers the pixel
        VkFrameHost.transitionMcImage(cmd, rt.mcDepth(), true,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        viewport.packDepth.transition(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            var attach = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                    .imageView(viewport.packDepth.view)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pColorAttachments(attach);
            vkCmdBeginRenderingKHR(cmd, info);
            this.packDepthPipeline.bind(cmd);
            VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
            try (var b = this.packDepthPipeline.binder()) {
                b.sampler(0, viewport.depthSampleView, this.packNearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .sampler(1, VkFrameHost.vkView(rt.mcDepth()), this.packNearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .ubo(2, this.packDepthParams)
                        .push(cmd);
            }
            var pc = stack.malloc(8);
            boolean reverse = this.properties.isReverseZ();
            pc.putFloat(0, reverse ? -1.0f : 1.0f);
            pc.putFloat(4, reverse ? 1.0f : 0.0f);
            this.packDepthPipeline.pushConstants(cmd, pc);
            vkCmdDraw(cmd, 4, 1, 0, 0);
            vkCmdEndRenderingKHR(cmd);
        }
        VkFrameHost.transitionMcImage(cmd, rt.mcDepth(), true,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        viewport.packDepth.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.packDepthView = viewport.packDepth.view;
    }

    private static final net.minecraft.resources.Identifier WATER_STILL =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "block/water_still");
    private static final net.minecraft.resources.Identifier LAVA_STILL =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");

    //Minecraft's block atlas and a fluid sprite's corners in it: water_still for PACK_ANIMATED_WATER,
    // lava_still for ANIMATED_LAVA
    private void bindFluidSprite(VkShaderPipeline pipeline, VkShaderPipeline.Binder b, VkCommandBuffer cmd,
                                 net.minecraft.resources.Identifier spriteId, long sampler) {
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) net.minecraft.client.Minecraft.getInstance()
                .getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var sprite = atlas.getSprite(spriteId);
        b.sampler(7, VkFrameHost.vkView(atlas.getTextureView()), sampler, VK_IMAGE_LAYOUT_GENERAL);
        try (MemoryStack stack = stackPush()) {
            var pc = stack.malloc(16);
            pc.putFloat(0, sprite.getU0()).putFloat(4, sprite.getV0()).putFloat(8, sprite.getU1()).putFloat(12, sprite.getV1());
            pipeline.pushConstants(cmd, pc);
        }
    }

    //The light (and face plane) of every visible opaque LOD pixel, into viewport.packLight, which the lit
    // pass right after reads to blend the light between neighbouring blocks. It also lays down the depth,
    // so the lit pass only ever shades the visible surface
    private void renderLightPrepass(VkViewport viewport, int maxDraw) {
        var cmd = this.ctx.cmd();
        viewport.packLight.transition(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            var attach = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                    .imageView(viewport.packLight.view)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            //face -1: no LOD here, which no fragment's face matches
            attach.clearValue().color().float32(0, 0).float32(1, 0).float32(2, -1).float32(3, 0);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pColorAttachments(attach);
            vkCmdBeginRenderingKHR(cmd, info);
            vkCmdEndRenderingKHR(cmd);
        }
        this.renderTerrain(viewport, new long[]{viewport.packLight.view}, this.packLightPrepass, null, 0, 4 * 3, maxDraw, false);
        viewport.packLight.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /** Whether the LOD translucents are the pack's, drawn after its deferred stage (renderDeferredTranslucent). */
    public boolean hasPackTranslucent() {
        return this.pack != null && this.packTranslucent != null;
    }

    /**
     * Shaderpack LOD mode: the LOD translucents, drawn where the real water is, after the pack's
     * deferred stage, into the cleared SSAO target (the opaque LODs were composited from it already).
     * The offscreen depth must be in attachment layout.
     */
    public void renderDeferredTranslucent(VkViewport viewport) {
        var cmd = this.ctx.cmd();
        //Every output starts each frame as transparent black: blended ones accumulate over it, and the
        // coverage says where anything was drawn at all
        var targets = new ArrayList<>(List.of(viewport.packTrans));
        targets.add(viewport.packTransMeta);
        var entries = new ArrayList<VkImage2D.BatchEntry>();
        for (var image : targets) {
            entries.add(new VkImage2D.BatchEntry(image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT));
        }
        VkImage2D.transitionBatch(entries, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
        long[] views = new long[targets.size()];
        try (MemoryStack stack = stackPush()) {
            var attach = VkRenderingAttachmentInfoKHR.calloc(targets.size(), stack);
            for (int i = 0; i < targets.size(); i++) {
                views[i] = targets.get(i).view;
                attach.get(i).sType$Default()
                        .imageView(views[i])
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                attach.get(i).clearValue().color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            }
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pColorAttachments(attach);
            vkCmdBeginRenderingKHR(cmd, info);
            vkCmdEndRenderingKHR(cmd);
        }
        if (!this.hasPackTranslucent() || this.geometry.getSectionCount() == 0) return;
        int cap = Math.min(this.geometry.getSectionCount(), VkViewport.TRANSLUCENT_DRAW_COUNT);
        int maxDraw = this.fixedCountBudget(this.fbTranslucentDraws, 256, cap);
        //The pack's deferred stage has just written its targets as attachments, among them the scene the
        // LOD water and ice reflect (Complementary's colortex5): wait for those writes, as renderOpaque
        // does, or the reflections read parts of last frame's scene and trail behind when moving
        this.ctx.barrier(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                        | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.renderTerrain(viewport, views, this.packTranslucent, this.packTranslucentSamplers,
                TRANSLUCENT_OFFSET * 5L * 4, 4 * 4, maxDraw, false);
    }

    public boolean isPackActive() {
        return this.pack != null;
    }

    //==================================================================================

    private final Matrix4f uniformScratch = new Matrix4f();
    public void uploadUniform(VkViewport viewport) {
        long ptr = this.uploadStream.upload(this.uniform, 0, 1024);
        var mat = this.uniformScratch.set(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4 * 4 * 4;
        viewport.section.getToAddress(ptr); ptr += 4 * 3;
        if (viewport.frameId < 0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId & 0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4 * 3;
        MemoryUtil.memPutFloat(ptr, me.cortex.voxy.client.core.rendering.FluidSurface.relativeSurfaceY(viewport.section.y));
        this.uploadStream.commit();
    }

    /** Mirrors MDIC buildDrawCalls: prep -> raster cull -> cmdgen -> translucency sort. */
    public void buildDrawCalls(VkViewport viewport) {
        if (this.geometry.getSectionCount() == 0) return;
        var cmd = this.ctx.cmd();
        this.uploadUniform(viewport);

        if (!this.ctx.vk().hasDrawIndirectCount) {
            //Fixed-count fallback (MoltenVK): renderTerrain issues
            // vkCmdDrawIndexedIndirect with maxDrawCount rather than a GPU-sourced
            // count, so trailing slots past the actual command count would be read
            // as stale draw commands from the previous frame. Zero the three
            // drawCallBuffer slices (opaque / temporal / translucent) here so any
            // un-overwritten slot reads as instanceCount=0 (a no-op draw). The
            // cmdgen compute below then writes the real commands on top; the fill
            // completes (with a barrier) before the cmdgen dispatch reads the buffer.
            // Gated to the fallback path only — the tight-count path (desktop Vulkan)
            // never reads past the actual count, so it pays nothing here.
            long stride = 5L * 4;
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, 0L, VkViewport.OPAQUE_DRAW_COUNT * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TEMPORAL_OFFSET * stride, VkViewport.TEMPORAL_DRAW_COUNT * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TRANSLUCENT_OFFSET * stride, VkViewport.TRANSLUCENT_DRAW_COUNT * stride, 0);
            this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
        }

        {//prep
            this.prep.bind(cmd);
            try (var b = this.prep.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCountCallBuffer)
                        .ssbo(2, viewport.indirectLookupBuffer)
                        .push(cmd);
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //prep (compute) wrote drawCountCallBuffer, which has TWO consumers:
            // the raster-cull draw below reads cullDrawIndirectCommand (@24) from
            // DRAW_INDIRECT, and cmdgen atomicAdds opaque/translucent/temporal
            // DrawCount (@12/@16/@20) from COMPUTE. computeToDrawBarrier() scopes
            // the destination to DRAW_INDIRECT|VERTEX only, so prep -> cmdgen had
            // no memory dependency at all: cmdgen's atomics could observe the
            // PREVIOUS frame's counters instead of prep's zeroes, placing draw
            // commands past their pass region with a baseInstance that no longer
            // matches the positionBuffer slot written for that section. Desktop
            // drivers happened to make the writes visible anyway; MoltenVK does
            // not. Include COMPUTE (read+write, the atomics are RMW) in the dst.
            this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        }

        {//raster occlusion test into the visibility buffer (depth-tested box draw, no writes)
            this.beginRendering(cmd, viewport, new long[0], true);//depth-only
            this.cullRaster.bind(cmd);
            VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
            try (var b = this.cullRaster.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, this.geometry.metadataBuffer())
                        .ssbo(2, viewport.visibilityBuffer)
                        .ssbo(3, viewport.indirectLookupBuffer)
                        .push(cmd);
            }
            vkCmdBindIndexBuffer(cmd, this.indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCountCallBuffer.buffer, 6 * 4, 1, 20);
            vkCmdEndRenderingKHR(cmd);
            //The raster-cull draw wrote visibilityData (SSBO) from the fragment
            // shader; the consumer is the cmdgen compute. Scope to those stages
            // instead of the previous fullBarrier (ALL_COMMANDS -> ALL_COMMANDS)
            // which forced a full pipeline stall on every frame.
            this.ctx.barrier(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }

        {//command generation (indirect dispatch sized by prep)
            vkCmdFillBuffer(cmd, this.distanceCountBuffer.buffer, 0, 1024L * 4, 0);
            this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            this.cmdGen.bind(cmd);
            try (var b = this.cmdGen.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCallBuffer)
                        .ssbo(2, viewport.drawCountCallBuffer)
                        .ssbo(3, this.geometry.metadataBuffer())
                        .ssbo(4, viewport.visibilityBuffer)
                        .ssbo(5, viewport.indirectLookupBuffer)
                        .ssbo(6, viewport.positionScratchBuffer)
                        .ssbo(7, this.distanceCountBuffer)
                        .push(cmd);
            }
            vkCmdDispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            //cmdgen -> prefixsum: both compute. The draw that consumes these
            // indirect commands is guarded by renderTerrain's own barrier.
            this.ctx.computeToComputeBarrier();
        }

        {//translucency sorting
            this.prefixSum.bind(cmd);
            try (var b = this.prefixSum.binder()) {
                b.ssbo(0, this.distanceCountBuffer).push(cmd);
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //prefixsum -> translucentGen: both compute.
            this.ctx.computeToComputeBarrier();

            this.translucentGen.bind(cmd);
            try (var b = this.translucentGen.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCallBuffer)
                        .ssbo(2, viewport.drawCountCallBuffer)
                        .ssbo(3, this.geometry.metadataBuffer())
                        .ssbo(4, viewport.indirectLookupBuffer)
                        .ssbo(5, this.distanceCountBuffer)
                        .push(cmd);
            }
            vkCmdDispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            //No trailing barrier here: the translucent commands written into
            // drawCallBuffer are read by renderTranslucent's renderTerrain, which
            // issues its own COMPUTE|TRANSFER -> DRAW_INDIRECT|VERTEX|FRAGMENT
            // barrier (line ~355) before drawing. The previous computeToAllBarrier
            // here was redundant with that draw barrier and serialised the GPU.
        }

        if (!this.ctx.vk().hasDrawIndirectCount) {
            //Read the three real per-pass draw counts back to the CPU so next frame's
            // fixed-count multi-draws track them instead of the worst-case section
            // cap (see fixedCountBudget). The counts live at opaque@12 / translucent@16
            // / temporal@20 in drawCountCallBuffer; download those 12 bytes on the same
            // async, event-retired path the traversal request readback already uses
            // (commit() scopes its own COMPUTE->TRANSFER->HOST barriers). The callback
            // runs on the render thread from pollRetired, so the plain field writes are
            // race-free. Desktop (drawIndirectCount) neither needs nor issues this.
            this.downloadStream.download(viewport.drawCountCallBuffer, 12, 12, (ptr, size) -> {
                this.fbOpaqueDraws = clampCount(MemoryUtil.memGetInt(ptr), VkViewport.OPAQUE_DRAW_COUNT);
                this.fbTranslucentDraws = clampCount(MemoryUtil.memGetInt(ptr + 4), VkViewport.TRANSLUCENT_DRAW_COUNT);
                this.fbTemporalDraws = clampCount(MemoryUtil.memGetInt(ptr + 8), VkViewport.TEMPORAL_DRAW_COUNT);
                this.hasAnyReadback = true;
            });
        }
    }

    private static int clampCount(int value, int cap) {
        return value < 0 ? 0 : Math.min(value, cap);
    }

    /**
     * Draw-count upper bound for one terrain pass. On desktop Vulkan the GPU sources
     * the real count from drawCountCallBuffer (vkCmdDrawIndexedIndirectCount), so the
     * section-derived {@code cap} is only a ceiling and is returned unchanged. On
     * MoltenVK — which lacks drawIndirectCount — the fixed-count multi-draw instead
     * iterates whatever count it is handed, encoding one Metal draw per slot; handing
     * it the section-count ceiling means tens of thousands of no-op draws every frame,
     * invariant to where the camera looks (this is why sky/occlusion culling produced
     * no Mac speed-up). Bounding it to the last-known real count plus headroom lets the
     * culling actually reduce Mac draw cost. The full drawCallBuffer is still zeroed per
     * frame, so every slot within the ceiling reads either a real command or a no-op —
     * a transient under-estimate during fast camera motion only drops a few LOD draws
     * for a frame or two, never reads stale geometry.
     */
    private int fixedCountBudget(int lastKnownCount, int headroom, int cap) {
        if (this.ctx.vk().hasDrawIndirectCount) return cap;
        if (!this.hasAnyReadback) return 0;//first frame: no draw calls generated yet
        int budget = (int) (lastKnownCount * 1.5f) + headroom;
        return Math.min(cap, Math.max(0, budget));
    }

    public void renderOpaque(VkViewport viewport, boolean clearTargets) {
        //Build pipelines BEFORE the section-count guard: within a frame geometry is
        // uploaded after this call (nodeManager.tick/buildDrawCalls), so renderTemporal/
        // renderTranslucent can see sectionCount>0 later this same frame. If we skipped
        // ensure here when the count is momentarily 0, those calls would bind a null
        // pipeline. ensureTerrainPipelines is idempotent (no-op once built for the format).
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        this.uploadUniform(viewport);
        if (this.pack != null) {
            this.uploadPackUniforms(viewport);
            //The pack's targets were last written as attachments by Vitrail's passes (this frame's
            // or the last one's); the LOD programs sample them
            this.ctx.barrier(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        int cap = Math.min((int) (this.geometry.getSectionCount() * 4.4 + 128), VkViewport.OPAQUE_DRAW_COUNT);
        int maxDraw = this.fixedCountBudget(this.fbOpaqueDraws, 1024, cap);
        if (this.pack != null) {
            this.renderLightPrepass(viewport, maxDraw);
        }
        this.renderOpaqueTerrain(viewport, 0, 4 * 3, maxDraw, clearTargets);
    }

    public void renderTemporal(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int cap = Math.min(this.geometry.getSectionCount(), VkViewport.TEMPORAL_DRAW_COUNT);
        int maxDraw = this.fixedCountBudget(this.fbTemporalDraws, 256, cap);
        this.renderOpaqueTerrain(viewport, TEMPORAL_OFFSET * 5L * 4, 4 * 5, maxDraw, false);
    }

    private void renderOpaqueTerrain(VkViewport viewport, long indirectOffset, long drawCountOffset, int maxDraw, boolean clear) {
        if (this.pack != null) {
            var outputs = viewport.packOpaqueOutputs();
            long[] views = new long[outputs.length + 1];
            for (int i = 0; i < outputs.length; i++) {
                views[i] = outputs[i].view;
            }
            views[outputs.length] = viewport.packMeta.view;
            this.renderTerrain(viewport, views, this.packOpaque, this.packOpaqueSamplers, indirectOffset, drawCountOffset, maxDraw, clear);
        } else {
            this.renderTerrain(viewport, new long[]{viewport.colour.view}, this.terrainOpaque, null, indirectOffset, drawCountOffset, maxDraw, clear);
        }
    }

    /** Translucents draw onto the SSAO output (mirrors the GL fbSSAO target). */
    public void renderTranslucent(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int cap = Math.min(this.geometry.getSectionCount(), VkViewport.TRANSLUCENT_DRAW_COUNT);
        int maxDraw = this.fixedCountBudget(this.fbTranslucentDraws, 256, cap);
        //(a pack's own translucent program is drawn by renderDeferredTranslucent instead)
        this.renderTerrain(viewport, new long[]{viewport.colourSSAO.view}, this.terrainTranslucent, null, TRANSLUCENT_OFFSET * 5L * 4, 4 * 4, maxDraw, false);
    }

    private void renderTerrain(VkViewport viewport, long[] colorViews, VkShaderPipeline pipeline, List<VoxyPackPatch.Sampler> packSamplers,
                               long indirectOffset, long drawCountOffset, int maxDrawCount, boolean clear) {
        var cmd = this.ctx.cmd();
        this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDEX_READ_BIT);

        this.beginRendering(cmd, viewport, colorViews, !clear);//LOAD unless first pass (which cleared via compositor setup)
        pipeline.bind(cmd);
        VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
        long lightmapView = VkFrameHost.lightmapView();
        try (var b = pipeline.binder()) {
            b.ubo(0, this.uniform)
                    .ssbo(1, this.geometry.geometryBuffer())
                    .ssbo(3, this.modelStore.modelBuffer)
                    .ssbo(4, this.modelStore.modelColourBuffer)
                    .ssbo(5, viewport.positionScratchBuffer)
                    .sampler(8, this.modelStore.atlas.view, this.modelStore.atlasSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .sampler(9, lightmapView, this.lightmapSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .sampler(10, viewport.depthBoundSampleView, this.depthBoundSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (packSamplers != null) {
                this.bindPackSamplers(b, packSamplers);
            }
            if (pipeline == this.packTranslucent) {
                this.bindFluidSprite(pipeline, b, cmd, WATER_STILL, this.packWaterSampler);
            }
            if (pipeline == this.packOpaque) {
                //Read as the pack's real lava is: with a mip picked whole (where that sampler blends two)
                // the LOD lava came out more averaged than the real lava beside it, and through the pack's
                // gamma and emission an averaged lava texture is darker and redder than the real one
//? if MC_26_3
                var terrainSampler = VitrailVoxyPack.terrainSampler();
                boolean useTerrainSampler = terrainSampler instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler vk
                        && !vk.isClosed();
                long lavaSampler = useTerrainSampler
                        ? ((com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler) terrainSampler).vkSampler() : this.packWaterSampler;
//? else
                long lavaSampler = this.packWaterSampler;
//? endif
                this.bindFluidSprite(pipeline, b, cmd, LAVA_STILL, lavaSampler);
            } else if (pipeline == this.terrainOpaque) {
                this.bindFluidSprite(pipeline, b, cmd, LAVA_STILL, this.packWaterSampler);
            }
            if (pipeline == this.packOpaque) {
                b.sampler(6, viewport.packLight.view, this.packNearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            b.push(cmd);
        }
        vkCmdBindIndexBuffer(cmd, this.indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT16);
        if (this.ctx.vk().hasDrawIndirectCount) {
            //Tight-count path: the GPU reads the actual draw count from the count
            // buffer each frame. Requires the drawIndirectCount Vulkan 1.2 feature
            // to be enabled on the device — desktop Vulkan (NVIDIA/AMD/Intel) enables
            // it; MoltenVK does not (see the else branch).
            vkCmdDrawIndexedIndirectCount(cmd,
                    viewport.drawCallBuffer.buffer, indirectOffset,
                    viewport.drawCountCallBuffer.buffer, drawCountOffset,
                    maxDrawCount, 5 * 4);
        } else {
            //MoltenVK/macOS fixed-count fallback: drawIndirectCount is not enabled
            // on MC's adopted Vulkan device (MC's DeviceFeatures record does not
            // even track it), and calling the function without the feature enabled
            // is invalid usage that MoltenVK degenerates. The drawCallBuffer slice
            // for this pass is zeroed per frame in buildDrawCalls (gated to this
            // fallback path) so trailing slots past the actual command count read
            // instanceCount=0 (no-op draws); a fixed-count multi-draw over the
            // clamped maxDrawCount is therefore correct, just less tight.
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCallBuffer.buffer, indirectOffset, maxDrawCount, 5 * 4);
        }
        vkCmdEndRenderingKHR(cmd);
    }

    /**
     * Begin dynamic rendering over the offscreen targets (always LOAD; clears
     * happen in the depth-setup pass). {@code colorViews} are the colour
     * attachments in output order (main colour vs SSAO output, plus the
     * shaderpack's extra draw buffers); empty = depth-only.
     */
    private void beginRendering(VkCommandBuffer cmd, VkViewport viewport,
                                long[] colorViews, boolean load) {
        try (MemoryStack stack = stackPush()) {
            var depthAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var stencilAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pDepthAttachment(depthAttach)
                    .pStencilAttachment(stencilAttach);
            if (colorViews.length != 0) {
                var colorAttach = VkRenderingAttachmentInfoKHR.calloc(colorViews.length, stack);
                for (int i = 0; i < colorViews.length; i++) {
                    colorAttach.get(i).sType$Default()
                            .imageView(colorViews[i])
                            .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                }
                info.pColorAttachments(colorAttach);
            }
            vkCmdBeginRenderingKHR(cmd, info);
        }
    }

    public void free() {
        this.prep.free();
        this.cmdGen.free();
        this.prefixSum.free();
        this.translucentGen.free();
        this.cullRaster.free();
        if (this.terrainOpaque != null) {
            this.terrainOpaque.free();
            this.terrainTranslucent.free();
        }
        this.freePack();
        for (long sampler : this.packSamplers) {
            vkDestroySampler(this.ctx.vk().device, sampler, null);
        }
        this.packFrameUbo.free();
        this.packUniformUbo.free();
        this.packDepthParams.free();
        //depthBoundSampler/lightmapSampler come from VkImage2D.createSampler's
        // device-lifetime cache (shared handles); never destroy them per-object
        // (multi-free vkDestroySampler -> SIGSEGV on world unload).
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.indexBuffer.free();
    }
}
