package me.cortex.voxy.client.core.vk.render;

//? if MC_26_3
import com.mojang.renderpearl.api.textures.GpuTextureView;
//? else
import com.mojang.blaze3d.textures.GpuTextureView;
//? endif
import me.cortex.voxy.client.compat.vitrail.VitrailVkCompat;
import me.cortex.voxy.client.compat.vitrail.VitrailVoxyPack;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Minecraft;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

//The two fullscreen passes bracketing Voxy's VK frame, mirroring the GL
// NormalRenderPipeline:
//
//  SETUP  — clear Voxy's offscreen depth-stencil (depth=clear, stencil=1) and
//           colour, then copy MC's depth in (transformed into Voxy's projection
//           space by the fragment shader) writing stencil=0 where vanilla
//           terrain exists. LOD terrain then renders with stencil==1 only.
//
//  COMPOSITE — alpha-blend Voxy's offscreen colour into MC's frame, emitting
//           depth transformed back into vanilla's projection space, with the
//           environmental fog ramp applied.
public class VkCompositor {
    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final RenderProperties properties;
    private final boolean useEnvFog;

    private final VkBuffer compositeParams;
    private final long depthSampler;
    private final long colourSampler;

    private VkShaderPipeline depthSetup;
    private VkShaderPipeline composite;
    private int setupDepthFormat = -1;
    private int compositeColorFormat = -1, compositeDepthFormat = -1;
    private boolean compositeColorWrite = true;
    private boolean compositeCoverage = false;

    //Reused each frame for the composite-param matrices (no per-frame allocation)
    private final Matrix4f scratchA = new Matrix4f();

    public VkCompositor(VkFrameCtx ctx, VkUploadStream uploadStream, RenderProperties properties, boolean useEnvFog) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.properties = properties;
        this.useEnvFog = useEnvFog;
        this.compositeParams = new VkBuffer(ctx, 256).zero();
        ctx.flushImmediate();
        this.depthSampler = VkImage2D.createSampler(ctx.vk(), false, false);
        this.colourSampler = VkImage2D.createSampler(ctx.vk(), false, false);
    }

    private void ensureSetupPipeline(VkViewportRT viewport) {
        if (this.depthSetup != null && this.setupDepthFormat == viewport.viewport.depthStencil.format) return;
        if (this.depthSetup != null) this.depthSetup.free();
        var d = new VkShaderPipeline.GfxDesc();
        d.name = "depth-setup";
        d.vertGlsl = VkShaderSource.load("voxy:post/fullscreen2.vert", VkShaderSource.defs().props(this.properties).build());
        d.fragGlsl = VkShaderSource.load("voxy:post/setup_stencil_depth.frag", VkShaderSource.defs().props(this.properties).build());
        d.pushConstantBytes = 8;
        d.colorFormat = viewport.viewport.colour.format;
        d.depthFormat = viewport.viewport.depthStencil.format;
        d.stencilFormat = viewport.viewport.depthStencil.format;
        d.depthTest = true;
        d.depthWrite = true;
        d.depthCompare = VK_COMPARE_OP_ALWAYS;
        d.colorWrite = false;
        d.blend = false;
        d.stencilWriteAlways1 = true;
        d.stencilWriteRef = 0;//mark vanilla-covered pixels with 0; LOD renders where stencil==1
        d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        d.bindings = List.of(VkShaderPipeline.sampler(0));
        this.depthSetup = new VkShaderPipeline(this.ctx, d);
        this.setupDepthFormat = viewport.viewport.depthStencil.format;
    }

    /**
     * @param colorWrite false: only the depth goes in
     * @param coverage   also writes the depth into Vitrail's coverage mask (a second, R32F attachment)
     *                   and leaves MC's colour as it is
     */
    private void ensureCompositePipeline(int mcColorFormat, int mcDepthFormat, boolean colorWrite, boolean coverage) {
        if (this.composite != null && this.compositeColorFormat == mcColorFormat && this.compositeDepthFormat == mcDepthFormat
                && this.compositeColorWrite == colorWrite && this.compositeCoverage == coverage) return;
        if (this.composite != null) this.composite.free();
        var d = new VkShaderPipeline.GfxDesc();
        d.name = "composite";
        d.vertGlsl = VkShaderSource.load("voxy:post/fullscreen2.vert", VkShaderSource.defs().props(this.properties).build());
        d.fragGlsl = VkShaderSource.load("voxy:post/blit_texture_depth_cutout.frag", VkShaderSource.defs().props(this.properties)
                .def("EMIT_COLOUR")
                .def("HAS_FOG")
                .def("HAS_FADE")
                .defIf("COVERAGE_OUT", coverage)
                .defIf("KEEP_TARGET_COLOUR", coverage)
                .build());
        if (coverage) {
            d.colorFormats = new int[]{mcColorFormat, VK_FORMAT_R32_SFLOAT};
            d.attachmentBlend = new int[][]{OVER_BLEND, null};
        } else {
            d.colorFormat = mcColorFormat;
            d.blend = true;
            d.colorWrite = colorWrite;
        }
        d.depthFormat = mcDepthFormat;
        d.stencilFormat = VK_FORMAT_UNDEFINED;
        d.depthTest = true;
        d.depthWrite = true;
        d.depthCompare = VkCmd.closerEqual(this.properties);
        d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        d.bindings = List.of(VkShaderPipeline.sampler(0), VkShaderPipeline.ubo(1), VkShaderPipeline.sampler(3));
        this.composite = new VkShaderPipeline(this.ctx, d);
        this.compositeColorFormat = mcColorFormat;
        this.compositeDepthFormat = mcDepthFormat;
        this.compositeColorWrite = colorWrite;
        this.compositeCoverage = coverage;
    }

    /**
     * One composite of Voxy's offscreen images into the pack's targets (and, for the translucents, the
     * game's depth). {@code dataFormats}/{@code dataModes}/{@code dataBlends} describe the outputs after
     * the colour, see blit_texture_depth_cutout.frag.
     */
    private record PackComposite(boolean packLod, boolean hasColour, int colourFormat, List<Integer> dataFormats,
                                 int dataModes, List<List<Integer>> dataBlends, int depthFormat, boolean depthWrite) {}

    private static final int[] OVER_BLEND = {VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA};

    private final java.util.Map<PackComposite, VkShaderPipeline> packComposites = new java.util.HashMap<>();

    private VkShaderPipeline packComposite(PackComposite spec) {
        var pipeline = this.packComposites.get(spec);
        if (pipeline != null) return pipeline;
        int dataCount = spec.dataFormats.size();
        var d = new VkShaderPipeline.GfxDesc();
        d.name = spec.depthWrite ? "composite-water" : "composite-colortex0";
        d.vertGlsl = VkShaderSource.load("voxy:post/fullscreen2.vert", VkShaderSource.defs().props(this.properties).build());
        d.fragGlsl = VkShaderSource.load("voxy:post/blit_texture_depth_cutout.frag", VkShaderSource.defs().props(this.properties)
                .def("EMIT_COLOUR")
                .def("HAS_FOG")
                .def("HAS_FADE")
                .def("VOXY_SHADER_FOG")
                .defIf("PACK_LOD", spec.packLod)
                .def("PACK_HAS_COLOUR", spec.hasColour ? 1 : 0)
                .def("PACK_DATA_COUNT", dataCount)
                .def("PACK_DATA_MODES", spec.dataModes)
                .build());
        int base = spec.hasColour ? 1 : 0;
        int[] formats = new int[base + dataCount];
        int[][] blends = new int[base + dataCount][];
        if (spec.hasColour) {
            formats[0] = spec.colourFormat;
            blends[0] = OVER_BLEND;
        }
        for (int i = 0; i < dataCount; i++) {
            formats[base + i] = spec.dataFormats.get(i);
            var blend = spec.dataBlends.get(i);
            blends[base + i] = blend == null ? null : blend.stream().mapToInt(Integer::intValue).toArray();
        }
        d.colorFormats = formats;
        d.attachmentBlend = blends;
        d.depthFormat = spec.depthFormat;
        d.stencilFormat = VK_FORMAT_UNDEFINED;
        d.depthTest = true;
        //The translucents go into the game's depth like the real water: the pack takes depthtex0 from it
        // after the translucents, while its depthtex1 was taken before them and keeps the floor under it
        d.depthWrite = spec.depthWrite;
        d.depthCompare = VkCmd.closerEqual(this.properties);
        d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        var bindings = new java.util.ArrayList<>(List.of(VkShaderPipeline.sampler(0), VkShaderPipeline.ubo(1), VkShaderPipeline.sampler(3)));
        for (int i = 0; i < dataCount; i++) {
            bindings.add(VkShaderPipeline.sampler(4 + i));
        }
        d.bindings = bindings;
        pipeline = new VkShaderPipeline(this.ctx, d);
        this.packComposites.put(spec, pipeline);
        return pipeline;
    }

    private static List<List<Integer>> blendList(int[][] blends) {
        var out = new java.util.ArrayList<List<Integer>>();
        for (int[] b : blends) {
            out.add(b == null ? null : java.util.Arrays.stream(b).boxed().toList());
        }
        return out;
    }

    /**
     * Shaderpack LOD mode: composites the LOD translucents (drawn after the pack's deferred stage into
     * viewport.packTrans) into the targets the pack's water writes, and their depth into the game's
     * depth. Uses the composite params uploaded by this frame's composite().
     */
    public void compositeTranslucents(VkViewportRT rt) {
        var viewport = rt.viewport();
        var layout = viewport.packLayout();
        if (layout == null) return;
        var cmd = this.ctx.cmd();

        //The output that goes to colortex0 is the colour (fogged, faded, and where nothing was drawn
        // its alpha is zero); every other output that goes somewhere is data after it
        int colourOutput = -1;
        for (int i = 0; i < layout.translucentCount(); i++) {
            int[] blend = layout.translucentBlends()[i];
            if (layout.translucentBuffers()[i] == 0 && blend != null && blend[1] == VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA) {
                colourOutput = i;
                break;
            }
        }
        GpuTextureView colourTarget = null;
        if (colourOutput >= 0) {
            colourTarget = VitrailVoxyPack.writeTarget(0);
            if (colourTarget == null) {
                colourTarget = VitrailVkCompat.getColortex0();
            }
            if (colourTarget == null) return;
        }
        var dataTargets = new java.util.ArrayList<GpuTextureView>();
        var dataImages = new java.util.ArrayList<VkImage2D>();
        var dataFormats = new java.util.ArrayList<Integer>();
        var dataBlends = new java.util.ArrayList<int[]>();
        int dataModes = 0;
        for (int i = 0; i < layout.translucentCount(); i++) {
            int dest = layout.translucentBuffers()[i];
            if (i == colourOutput || dest < 0) continue;
            var target = VitrailVoxyPack.writeTarget(dest);
            if (target == null) continue;
            int[] blend = layout.translucentBlends()[i];
            int mode = blend == null ? 0 : (blend[1] == VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA ? 1 : 2);
            dataModes |= mode << (2 * dataTargets.size());
            dataBlends.add(mode == 0 ? null : mode == 1 ? OVER_BLEND : blend);
            dataTargets.add(target);
            dataImages.add(viewport.packTrans[i]);
            dataFormats.add(VkFrameHost.vkFormat(target));
        }
        if (colourTarget == null && dataTargets.isEmpty()) return;
        //Without a colour, where the translucent program drew at all
        VkImage2D source = colourOutput >= 0 ? viewport.packTrans[colourOutput] : viewport.packTransMeta;

        var sampled = new java.util.ArrayList<>(dataImages);
        sampled.add(source);
        for (var image : sampled) {
            image.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        int depthFormat = VkFrameHost.vkFormat(rt.mcDepth());
        var pipeline = this.packComposite(new PackComposite(true, colourTarget != null,
                colourTarget == null ? VK_FORMAT_UNDEFINED : VkFrameHost.vkFormat(colourTarget), List.copyOf(dataFormats),
                dataModes, blendList(dataBlends.toArray(new int[0][])), depthFormat, true));

        var attachments = new java.util.ArrayList<GpuTextureView>();
        if (colourTarget != null) attachments.add(colourTarget);
        attachments.addAll(dataTargets);
        this.drawPackComposite(rt, pipeline, attachments, source, dataImages);
    }

    //Draws a pack composite over {@code attachments} (all of one size) and the game's depth
    private void drawPackComposite(VkViewportRT rt, VkShaderPipeline pipeline, List<GpuTextureView> attachments,
                                   VkImage2D source, List<VkImage2D> dataImages) {
        var viewport = rt.viewport();
        var cmd = this.ctx.cmd();
        VkFrameHost.barrierDepthAttachment(cmd, rt.mcDepth());
        for (var target : attachments) {
            VkFrameHost.barrierColorAttachment(cmd, target);
        }
        int width = attachments.get(0).getWidth(0);
        int height = attachments.get(0).getHeight(0);
        try (MemoryStack stack = stackPush()) {
            var colorAttach = VkRenderingAttachmentInfoKHR.calloc(attachments.size(), stack);
            for (int i = 0; i < attachments.size(); i++) {
                colorAttach.get(i).sType$Default()
                        .imageView(VkFrameHost.vkView(attachments.get(i)))
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            }
            var depthAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(VkFrameHost.vkView(rt.mcDepth()))
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(width).height(height)))
                    .layerCount(1)
                    .pColorAttachments(colorAttach)
                    .pDepthAttachment(depthAttach);
            vkCmdBeginRenderingKHR(cmd, info);
        }
        pipeline.bind(cmd);
        VkCmd.setViewportScissor(cmd, width, height);
        try (var b = pipeline.binder()) {
            b.sampler(0, viewport.depthSampleView, this.depthSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .ubo(1, this.compositeParams)
                    .sampler(3, source.view, this.colourSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            for (int i = 0; i < dataImages.size(); i++) {
                b.sampler(4 + i, dataImages.get(i).view, this.colourSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            b.push(cmd);
        }
        vkCmdDraw(cmd, 4, 1, 0, 0);
        vkCmdEndRenderingKHR(cmd);
        for (var target : attachments) {
            VkFrameHost.barrierColorAttachment(cmd, target);
        }
        VkFrameHost.barrierDepthAttachment(cmd, rt.mcDepth());
    }

        /** Wrapper carrying the per-frame MC attachment info alongside Voxy's viewport. */
    public record VkViewportRT(VkViewport viewport,
                               GpuTextureView mcColour, GpuTextureView mcDepth,
                               int mcWidth, int mcHeight) {}

    //SETUP pass. MC's depth image is transitioned to shader-read for sampling
    // and back to attachment afterwards.
    public void setupDepthStencil(VkViewportRT rt) {
        this.ensureSetupPipeline(rt);
        var cmd = this.ctx.cmd();
        var viewport = rt.viewport;

        //Voxy offscreen images -> attachment layouts (first use each frame).
        //Batched into a single vkCmdPipelineBarrier (colour + depthStencil together)
        // instead of two separate transitions
        VkImage2D.transitionBatch(java.util.List.of(
                new VkImage2D.BatchEntry(viewport.colour, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT),
                new VkImage2D.BatchEntry(viewport.depthStencil, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)),
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT);
        //depth-bound image: the clear happens inline as LOAD_OP_CLEAR inside
        // VkBoundRenderer's render pass (or a no-section clear-only pass when
        // there are no visible sections). Leave depthBound in its current layout;
        // VkBoundRenderer transitions it to DEPTH_STENCIL_ATTACHMENT directly.

        //Shaderpack LOD mode: the pack's other draw buffers (and the SSAO metadata) start each frame
        // empty, like the colour
        if (viewport.isPackMode()) {
            var cleared = new java.util.ArrayList<VkImage2D>(java.util.List.of(viewport.packData));
            cleared.add(viewport.packMeta);
            var entries = new java.util.ArrayList<VkImage2D.BatchEntry>();
            for (var data : cleared) {
                entries.add(new VkImage2D.BatchEntry(data, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT));
            }
            VkImage2D.transitionBatch(entries, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            try (MemoryStack stack = stackPush()) {
                var attach = VkRenderingAttachmentInfoKHR.calloc(cleared.size(), stack);
                for (int i = 0; i < cleared.size(); i++) {
                    attach.get(i).sType$Default()
                            .imageView(cleared.get(i).view)
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
        }

        //MC depth -> sample-able
        VkFrameHost.transitionMcImage(cmd, rt.mcDepth, true,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        try (MemoryStack stack = stackPush()) {
            var colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                    .imageView(viewport.colour.view)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            colorAttach.clearValue().color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            var depthAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depthAttach.clearValue().depthStencil().depth(this.properties.clearDepth()).stencil(1);
            var stencilAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            stencilAttach.clearValue().depthStencil().depth(this.properties.clearDepth()).stencil(1);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pColorAttachments(colorAttach)
                    .pDepthAttachment(depthAttach)
                    .pStencilAttachment(stencilAttach);
            vkCmdBeginRenderingKHR(cmd, info);
        }
        this.depthSetup.bind(cmd);
        VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
        try (var b = this.depthSetup.binder()) {
            b.sampler(0, VkFrameHost.vkView(rt.mcDepth), this.depthSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .push(cmd);
        }
        try (MemoryStack stack = stackPush()) {
            var pc = stack.malloc(8);
            pc.putFloat(0, ((float) viewport.width) / rt.mcWidth);
            pc.putFloat(4, ((float) viewport.height) / rt.mcHeight);
            this.depthSetup.pushConstants(cmd, pc);
        }
        vkCmdDraw(cmd, 4, 1, 0, 0);
        vkCmdEndRenderingKHR(cmd);

        //MC depth back to attachment
        VkFrameHost.transitionMcImage(cmd, rt.mcDepth, true,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    /** Transition Voxy's offscreen targets for sampling (HiZ build / composite). */
    public void offscreenToSampled(VkViewport viewport) {
        viewport.depthStencil.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    public void offscreenToAttachment(VkViewport viewport) {
        viewport.depthStencil.transition(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
    }

    /**
     * COMPOSITE pass into the pack's targets (when a Vitrail pack is drawing) and MC's frame attachments.
     */
    public void composite(VkViewportRT rt) {
        var viewport = rt.viewport;
        boolean fogCoversAllRendering = viewport.fogParameters != null
                && viewport.fogParameters.environmentalEnd() > 0.0f
                && viewport.fogParameters.environmentalEnd() < 16.0f;
        if (fogCoversAllRendering) {
            return;//mirrors the GL "fog covers everything -> skip blit" branch
        }
        var layout = viewport.packLayout();
        //A pack whose LOD programs write no colour leaves MC's colour alone: only where the LODs are
        // (packMeta, alpha 1 under every opaque LOD) and their depth go in
        boolean emitsColour = layout == null || layout.hasColour();

        int mcColorFormat = VkFrameHost.vkFormat(rt.mcColour);
        int mcDepthFormat = VkFrameHost.vkFormat(rt.mcDepth);
        //Vitrail paints the game's own picture into the terrain's first draw buffer wherever its coverage
        // mask does not claim the pixel for the pack. Without a colour LOD that buffer is pack data
        // (Photon's colortex1), and the picture painted over the LODs was the sky. With one it is colortex0,
        // and the picture painted back was the LODs' own (composited into the game's image below), but
        // through the game's 8 bit image: everything over 1 was cut off, which is most of an emissive
        // block's light in the packs' float colortex0 (LOD lava a duller, darker orange than the real lava
        // beside it). So the LODs of every pack with LOD programs claim their pixels in the mask, with the
        // very depth they leave in the game's depth, which is what the mask holds for the pack's own
        // terrain, and keep the colour composited into the pack's own targets
        GpuTextureView coverage = layout != null ? VitrailVoxyPack.coverage() : null;
        if (coverage != null && (coverage.getWidth(0) != rt.mcWidth || coverage.getHeight(0) != rt.mcHeight
                || VkFrameHost.vkFormat(coverage) != VK_FORMAT_R32_SFLOAT
                //the mask is compared for equality with the depth as stored, which a float depth keeps exactly
                || (mcDepthFormat != VK_FORMAT_D32_SFLOAT && mcDepthFormat != VK_FORMAT_D32_SFLOAT_S8_UINT))) {
            coverage = null;
        }
        this.ensureCompositePipeline(mcColorFormat, mcDepthFormat, emitsColour, coverage != null);
        var cmd = this.ctx.cmd();

        this.uploadCompositeParams(viewport);

        //SSAO output colour (with translucents composited on top) -> sampled, along with the
        // shaderpack's other draw buffers in shaderpack LOD mode
        VkImage2D source = emitsColour ? viewport.colourSSAO : viewport.packMeta;
        source.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        for (var data : viewport.packData) {
            data.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        //(depth already SHADER_READ from the HiZ stage — offscreenToSampled)

        //The pack's colortex0 goes FIRST, while MC's depth still only holds the vanilla terrain.
        // Both composites depth-test against the vanilla terrain only; doing it after the main-target
        // pass instead made the colortex0 pass compare each LOD fragment against the depth that very
        // fragment had just written (depth func closerEqual, so it relied on two separately compiled
        // fragment shaders producing a bit-identical gl_FragDepth). Any 1-ulp difference dropped
        // that pixel, and since the value changes with the camera this showed up as LOD speckle that
        // flickered while moving.
        if (layout != null) {
            this.compositeIntoPack(rt, layout, mcDepthFormat, source);
        } else {
            GpuTextureView colortex0 = VitrailVkCompat.getColortex0();
            if (colortex0 != null) {
                this.compositeIntoColortex0(rt, colortex0, mcDepthFormat, source);
            }
        }

        VkFrameHost.barrierColorAttachment(cmd, rt.mcColour);
        VkFrameHost.barrierDepthAttachment(cmd, rt.mcDepth);
        if (coverage != null) {
            VkFrameHost.barrierColorAttachment(cmd, coverage);
        }

        try (MemoryStack stack = stackPush()) {
            var colorAttach = VkRenderingAttachmentInfoKHR.calloc(coverage != null ? 2 : 1, stack);
            colorAttach.get(0).sType$Default()
                    .imageView(VkFrameHost.vkView(rt.mcColour))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (coverage != null) {
                colorAttach.get(1).sType$Default()
                        .imageView(VkFrameHost.vkView(coverage))
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            }
            var depthAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(VkFrameHost.vkView(rt.mcDepth))
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(rt.mcWidth).height(rt.mcHeight)))
                    .layerCount(1)
                    .pColorAttachments(colorAttach)
                    .pDepthAttachment(depthAttach);
            vkCmdBeginRenderingKHR(cmd, info);
        }
        this.composite.bind(cmd);
        VkCmd.setViewportScissor(cmd, rt.mcWidth, rt.mcHeight);
        try (var b = this.composite.binder()) {
            b.sampler(0, viewport.depthSampleView, this.depthSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .ubo(1, this.compositeParams)
                    .sampler(3, source.view, this.colourSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .push(cmd);
        }
        vkCmdDraw(cmd, 4, 1, 0, 0);
        vkCmdEndRenderingKHR(cmd);

        VkFrameHost.barrierColorAttachment(cmd, rt.mcColour);
        if (coverage != null) {
            VkFrameHost.barrierColorAttachment(cmd, coverage);
        }
    }

    //Voxy's own (vanilla-lit) LODs into colortex0 of a pack that has no LOD programs
    private void compositeIntoColortex0(VkViewportRT rt, GpuTextureView colortex0, int mcDepthFormat, VkImage2D source) {
        var pipeline = this.packComposite(new PackComposite(false, true, VkFrameHost.vkFormat(colortex0), List.of(),
                0, List.of(), mcDepthFormat, false));
        this.drawPackComposite(rt, pipeline, List.of(colortex0), source, List.of());
    }

    //The opaque outputs of a pack's LOD programs into the targets its voxy.json names: the colour (with
    // the fog and fade) into colortex0, everything else copied as the pack wrote it
    private void compositeIntoPack(VkViewportRT rt, PackLayout layout, int mcDepthFormat, VkImage2D source) {
        var viewport = rt.viewport;
        GpuTextureView colourTarget = null;
        if (layout.hasColour()) {
            colourTarget = VitrailVoxyPack.writeTarget(0);
            if (colourTarget == null) {
                colourTarget = VitrailVkCompat.getColortex0();
            }
            if (colourTarget == null) return;
        }
        var dataTargets = new java.util.ArrayList<GpuTextureView>();
        var dataFormats = new java.util.ArrayList<Integer>();
        for (int i = 0; i < layout.opaqueCount(); i++) {
            if (i == layout.colourOutput()) continue;
            var target = VitrailVoxyPack.writeTarget(layout.opaqueBuffers()[i]);
            if (target == null) {
                //All of the data or none of it, the pack's stages read it together: the colour alone
                dataTargets.clear();
                dataFormats.clear();
                if (colourTarget == null) return;
                break;
            }
            dataTargets.add(target);
            dataFormats.add(VkFrameHost.vkFormat(target));
        }
        int data = dataTargets.size();
        var blends = new java.util.ArrayList<List<Integer>>();
        for (int i = 0; i < data; i++) blends.add(null);
        var pipeline = this.packComposite(new PackComposite(true, colourTarget != null,
                colourTarget == null ? VK_FORMAT_UNDEFINED : VkFrameHost.vkFormat(colourTarget), List.copyOf(dataFormats),
                0, blends, mcDepthFormat, false));
        var attachments = new java.util.ArrayList<GpuTextureView>();
        if (colourTarget != null) attachments.add(colourTarget);
        attachments.addAll(dataTargets);
        this.drawPackComposite(rt, pipeline, attachments, source, List.of(viewport.packData).subList(0, data));
    }

    private void uploadCompositeParams(VkViewport viewport) {
        long ptr = this.uploadStream.upload(this.compositeParams, 0, 256);
        this.scratchA.set(viewport.MVP).invert().getToAddress(ptr); ptr += 64;
        this.scratchA.set(viewport.vanillaProjection).mul(viewport.modelView).getToAddress(ptr); ptr += 64;

        boolean isShaderPack = VitrailVkCompat.isDrawingPack();
        float e0 = 0, e1 = 0, e2 = 0, e3 = 0.75f;
        float f0 = 0, f1 = 0, f2 = 0, f3 = 0;
        float fadeMode = 0f, fadeY = 0f, fadeZ = 0f, fadeW = 0f;

        if (isShaderPack) {
            boolean borderFog = VitrailVkCompat.isBorderFogEnabled();
            if (borderFog) {
                float rd = VitrailVkCompat.getVoxyRenderDistanceBlocks();
                e0 = 1.0f / rd;
                e1 = 3.0f; // Exponent factor matching Complementary's 1.0 - exp(-3.0 * (lPos / rd)^4)
                e2 = 1.0f;
                e3 = 2.0f; // that formula in every composite, see blit_texture_depth_cutout.frag

                if (viewport.fogParameters != null) {
                    f0 = viewport.fogParameters.red();
                    f1 = viewport.fogParameters.green();
                    f2 = viewport.fogParameters.blue();
                    f3 = 1.0f;
                } else {
                    f0 = 0.7f;
                    f1 = 0.8f;
                    f2 = 1.0f;
                    f3 = 1.0f;
                }

                // Smooth distance fade at the outer edge of Voxy's render distance
                fadeMode = 2.0f; // length(point.xyz)
                float fadeStart = rd * 0.90f;
                float fadeEnd = rd;
                float scale = 1.0f / Math.max(1.0f, fadeEnd - fadeStart);
                fadeY = -fadeStart * scale;
                fadeZ = scale;
                fadeW = 0.0f;
            } else if (Boolean.FALSE.equals(VitrailVoxyPack.loadedHasComplementaryFog())) {
                // A pack with LOD programs of its own and no border fog setting fogs the LODs itself:
                // only the fade at the outer edge of Voxy's render distance, into the pack's sky
                float rd = VitrailVkCompat.getVoxyRenderDistanceBlocks();
                f3 = 0.0f;
                fadeMode = 2.0f;
                float fadeStart = rd * 0.90f;
                float scale = 1.0f / Math.max(1.0f, rd - fadeStart);
                fadeY = -fadeStart * scale;
                fadeZ = scale;
                fadeW = 0.0f;
            } else {
                // When border fog is disabled in shader settings: NO fog and NO fade on LODs
                f3 = 0.0f;
                fadeMode = 0.0f;
            }
        } else {
            var fogMode = VoxyConfig.CONFIG.getFogMode();
            boolean hasFog = fogMode.hasFog;
            boolean hasFade = fogMode.hasFade;

            float vanillaRd = VoxyRenderSystem.getVanillaRenderDistance();
            float rd = Math.max(vanillaRd, VoxyConfig.CONFIG.sectionRenderDistance * 512.0f);

            float rain = 0.0f;
            var mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                rain = mc.level.getRainLevel(1.0f);
            }
            e3 = Math.clamp(0.75f + 0.20f * rain, 0.0f, 1.0f);

            if (hasFog && viewport.fogParameters != null) {
                float envStart = viewport.fogParameters.environmentalStart();
                float envEnd = viewport.fogParameters.environmentalEnd();
                if (envEnd > envStart && envEnd < rd && Math.abs(envEnd - envStart) > 1.0f) {
                    float invDelta = 1.0f / (envEnd - envStart);
                    e0 = invDelta;
                    e1 = -envStart * invDelta;
                    e2 = 1.0f;
                } else {
                    float start = Math.max(0.0f, vanillaRd * 0.75f);
                    float end = rd;
                    if (end > start) {
                        float invDelta = 1.0f / (end - start);
                        e0 = invDelta;
                        e1 = -start * invDelta;
                        e2 = 1.0f;
                    }
                }
                f0 = viewport.fogParameters.red();
                f1 = viewport.fogParameters.green();
                f2 = viewport.fogParameters.blue();
                f3 = viewport.fogParameters.alpha();
            }

            if (hasFade) {
                fadeMode = 2.0f; // length(point.xyz)
                float fadeStart = Math.max(vanillaRd, rd * 0.85f);
                float fadeEnd = rd;
                float scale = 1.0f / Math.max(1.0f, fadeEnd - fadeStart);
                fadeY = -fadeStart * scale;
                fadeZ = scale;

                if (hasFog) {
                    float density = 0.0018f;
                    if (rain > 0.0f) {
                        density *= (1.0f + 1.2f * rain);
                    }
                    if (viewport.fogParameters != null) {
                        float envEnd = viewport.fogParameters.environmentalEnd();
                        float envStart = viewport.fogParameters.environmentalStart();
                        if (envEnd > envStart && envEnd < rd) {
                            float envDelta = envEnd - envStart;
                            if (envDelta > 1.0f) {
                                density = Math.max(density, 2.5f / envDelta);
                            }
                        }
                    }
                    fadeW = density;
                }
            }
        }

        MemoryUtil.memPutFloat(ptr, e0);
        MemoryUtil.memPutFloat(ptr + 4, e1);
        MemoryUtil.memPutFloat(ptr + 8, e2);
        MemoryUtil.memPutFloat(ptr + 12, e3);
        ptr += 16;
        MemoryUtil.memPutFloat(ptr, f0);
        MemoryUtil.memPutFloat(ptr + 4, f1);
        MemoryUtil.memPutFloat(ptr + 8, f2);
        MemoryUtil.memPutFloat(ptr + 12, f3);
        ptr += 16;
        MemoryUtil.memPutFloat(ptr, fadeMode);
        MemoryUtil.memPutFloat(ptr + 4, fadeY);
        MemoryUtil.memPutFloat(ptr + 8, fadeZ);
        MemoryUtil.memPutFloat(ptr + 12, fadeW);
        this.uploadStream.commit();
    }

    public void free() {
        if (this.depthSetup != null) this.depthSetup.free();
        if (this.composite != null) this.composite.free();
        for (var pipeline : this.packComposites.values()) {
            pipeline.free();
        }
        this.packComposites.clear();
        //depthSampler/colourSampler come from VkImage2D.createSampler's
        // device-lifetime cache (shared across compositor/SSAO/terrain); never
        // destroy them per-object (double vkDestroySampler -> SIGSEGV on unload)
        this.compositeParams.free();
    }
}
