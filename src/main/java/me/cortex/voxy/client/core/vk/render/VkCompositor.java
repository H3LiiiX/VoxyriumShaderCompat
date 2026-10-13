package me.cortex.voxy.client.core.vk.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
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
    private VkShaderPipeline compositeColortex0;
    private int setupDepthFormat = -1;
    private int compositeColorFormat = -1, compositeDepthFormat = -1;
    private int colortex0ColorFormat = -1, colortex0DepthFormat = -1;
    private int[] colortex0PackFormats = null;
    private VkShaderPipeline compositeWater;
    private int waterColorFormat = -1, waterDepthFormat = -1;//colour formats of the pack's data targets the pipeline writes, null = none

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

    private void ensureCompositePipeline(int mcColorFormat, int mcDepthFormat) {
        if (this.composite != null && this.compositeColorFormat == mcColorFormat && this.compositeDepthFormat == mcDepthFormat) return;
        if (this.composite != null) this.composite.free();
        var d = new VkShaderPipeline.GfxDesc();
        d.name = "composite";
        d.vertGlsl = VkShaderSource.load("voxy:post/fullscreen2.vert", VkShaderSource.defs().props(this.properties).build());
        d.fragGlsl = VkShaderSource.load("voxy:post/blit_texture_depth_cutout.frag", VkShaderSource.defs().props(this.properties)
                .def("EMIT_COLOUR")
                .def("HAS_FOG")
                .def("HAS_FADE")
                .build());
        d.colorFormat = mcColorFormat;
        d.depthFormat = mcDepthFormat;
        d.stencilFormat = VK_FORMAT_UNDEFINED;
        d.depthTest = true;
        d.depthWrite = true;
        d.depthCompare = VkCmd.closerEqual(this.properties);
        d.blend = true;
        d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        d.bindings = List.of(VkShaderPipeline.sampler(0), VkShaderPipeline.ubo(1), VkShaderPipeline.sampler(3));
        this.composite = new VkShaderPipeline(this.ctx, d);
        this.compositeColorFormat = mcColorFormat;
        this.compositeDepthFormat = mcDepthFormat;
    }

    /**
     * @param packFormats null outside shaderpack LOD mode, else the formats of the pack's data
     *                    targets written beside colortex0 (possibly none)
     */
    private void ensureCompositeColortex0Pipeline(int colorFormat, int depthFormat, int[] packFormats) {
        if (this.compositeColortex0 != null && this.colortex0ColorFormat == colorFormat && this.colortex0DepthFormat == depthFormat
                && java.util.Arrays.equals(this.colortex0PackFormats, packFormats)) return;
        if (this.compositeColortex0 != null) this.compositeColortex0.free();
        int dataCount = packFormats == null ? 0 : packFormats.length;
        var d = new VkShaderPipeline.GfxDesc();
        d.name = "composite-colortex0";
        d.vertGlsl = VkShaderSource.load("voxy:post/fullscreen2.vert", VkShaderSource.defs().props(this.properties).build());
        d.fragGlsl = VkShaderSource.load("voxy:post/blit_texture_depth_cutout.frag", VkShaderSource.defs().props(this.properties)
                .def("EMIT_COLOUR")
                .def("HAS_FOG")
                .def("HAS_FADE")
                .def("VOXY_SHADER_FOG")
                .defIf("PACK_LOD", packFormats != null)
                .def("PACK_DATA_COUNT", dataCount)
                .build());
        int[] formats = new int[1 + dataCount];
        formats[0] = colorFormat;
        for (int i = 0; i < dataCount; i++) {
            formats[1 + i] = packFormats[i];
        }
        d.colorFormats = formats;
        d.depthFormat = depthFormat;
        d.stencilFormat = VK_FORMAT_UNDEFINED;
        d.depthTest = true;
        d.depthWrite = false;
        d.depthCompare = VkCmd.closerEqual(this.properties);
        d.blend = true;
        d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        var bindings = new java.util.ArrayList<>(List.of(VkShaderPipeline.sampler(0), VkShaderPipeline.ubo(1), VkShaderPipeline.sampler(3)));
        for (int i = 0; i < dataCount; i++) {
            bindings.add(VkShaderPipeline.sampler(4 + i));
        }
        d.bindings = bindings;
        this.compositeColortex0 = new VkShaderPipeline(this.ctx, d);
        this.colortex0ColorFormat = colorFormat;
        this.colortex0DepthFormat = depthFormat;
        this.colortex0PackFormats = packFormats == null ? null : packFormats.clone();
    }



    private void ensureCompositeWaterPipeline(int colorFormat, int depthFormat) {
        if (this.compositeWater != null && this.waterColorFormat == colorFormat && this.waterDepthFormat == depthFormat) return;
        if (this.compositeWater != null) this.compositeWater.free();
        var d = new VkShaderPipeline.GfxDesc();
        d.name = "composite-water";
        d.vertGlsl = VkShaderSource.load("voxy:post/fullscreen2.vert", VkShaderSource.defs().props(this.properties).build());
        d.fragGlsl = VkShaderSource.load("voxy:post/blit_texture_depth_cutout.frag", VkShaderSource.defs().props(this.properties)
                .def("EMIT_COLOUR")
                .def("HAS_FOG")
                .def("HAS_FADE")
                .def("VOXY_SHADER_FOG")
                .def("PACK_LOD")
                .build());
        d.colorFormat = colorFormat;
        d.depthFormat = depthFormat;
        d.stencilFormat = VK_FORMAT_UNDEFINED;
        d.depthTest = true;
        //Into the game's depth like the real water: the pack takes depthtex0 from it after the
        // translucents, while its depthtex1 was taken before them and keeps the floor under the water
        d.depthWrite = true;
        d.depthCompare = VkCmd.closerEqual(this.properties);
        d.blend = true;
        d.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        d.bindings = List.of(VkShaderPipeline.sampler(0), VkShaderPipeline.ubo(1), VkShaderPipeline.sampler(3));
        this.compositeWater = new VkShaderPipeline(this.ctx, d);
        this.waterColorFormat = colorFormat;
        this.waterDepthFormat = depthFormat;
    }

    /**
     * Shaderpack LOD mode: composites the LOD translucents (drawn after the pack's deferred stage into
     * colourSSAO) into the colortex0 the pack's water writes, and their depth into the game's depth.
     * Uses the composite params uploaded by this frame's composite().
     */
    public void compositeTranslucents(VkViewportRT rt) {
        var viewport = rt.viewport();
        GpuTextureView colortex0 = VitrailVoxyPack.writeTarget(0);
        if (colortex0 == null) {
            colortex0 = VitrailVkCompat.getColortex0();
        }
        if (colortex0 == null) return;
        var cmd = this.ctx.cmd();
        viewport.colourSSAO.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        int colorFormat = VkFrameHost.vkFormat(colortex0);
        int depthFormat = VkFrameHost.vkFormat(rt.mcDepth());
        this.ensureCompositeWaterPipeline(colorFormat, depthFormat);

        VkFrameHost.barrierDepthAttachment(cmd, rt.mcDepth());
        VkFrameHost.barrierColorAttachment(cmd, colortex0);
        int width = colortex0.getWidth(0);
        int height = colortex0.getHeight(0);
        try (MemoryStack stack = stackPush()) {
            var colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                    .imageView(VkFrameHost.vkView(colortex0))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
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
        this.compositeWater.bind(cmd);
        VkCmd.setViewportScissor(cmd, width, height);
        try (var b = this.compositeWater.binder()) {
            b.sampler(0, viewport.depthSampleView, this.depthSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .ubo(1, this.compositeParams)
                    .sampler(3, viewport.colourSSAO.view, this.colourSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .push(cmd);
        }
        vkCmdDraw(cmd, 4, 1, 0, 0);
        vkCmdEndRenderingKHR(cmd);
        VkFrameHost.barrierColorAttachment(cmd, colortex0);
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
     * COMPOSITE pass into colortex0 (when a Vitrail pack is drawing) and MC's frame attachments.
     * @param packDataIndices in shaderpack LOD mode, the colortex indices of viewport.packData
     */
    public void composite(VkViewportRT rt, int[] packDataIndices) {
        var viewport = rt.viewport;
        boolean fogCoversAllRendering = viewport.fogParameters != null
                && viewport.fogParameters.environmentalEnd() > 0.0f
                && viewport.fogParameters.environmentalEnd() < 16.0f;
        if (fogCoversAllRendering) {
            return;//mirrors the GL "fog covers everything -> skip blit" branch
        }

        int mcColorFormat = VkFrameHost.vkFormat(rt.mcColour);
        int mcDepthFormat = VkFrameHost.vkFormat(rt.mcDepth);
        this.ensureCompositePipeline(mcColorFormat, mcDepthFormat);
        var cmd = this.ctx.cmd();

        this.uploadCompositeParams(viewport);

        //SSAO output colour (with translucents composited on top) -> sampled, along with the
        // shaderpack's other draw buffers in shaderpack LOD mode
        VkImage2D source = viewport.colourSSAO;
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
        GpuTextureView[] dataTargets = viewport.isPackMode() ? packDataTargets(viewport, packDataIndices) : null;
        GpuTextureView colortex0 = null;
        if (viewport.isPackMode()) {
            colortex0 = VitrailVoxyPack.writeTarget(0);
        }
        if (colortex0 == null) {
            colortex0 = VitrailVkCompat.getColortex0();
        }
        if (colortex0 != null) {
            this.compositeIntoColortex0(rt, colortex0, mcDepthFormat, source, dataTargets);
        }

        VkFrameHost.barrierColorAttachment(cmd, rt.mcColour);
        VkFrameHost.barrierDepthAttachment(cmd, rt.mcDepth);

        try (MemoryStack stack = stackPush()) {
            var colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                    .imageView(VkFrameHost.vkView(rt.mcColour))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
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
    }

    /** The pack's own targets its other opaque draw buffers go to, or null if any of them is missing. */
    private static GpuTextureView[] packDataTargets(VkViewport viewport, int[] indices) {
        if (indices == null || indices.length != viewport.packData.length) return null;
        var out = new GpuTextureView[indices.length];
        for (int i = 0; i < indices.length; i++) {
            out[i] = VitrailVoxyPack.writeTarget(indices[i]);
            if (out[i] == null) return null;
        }
        return out;
    }

    private void compositeIntoColortex0(VkViewportRT rt, GpuTextureView colortex0, int mcDepthFormat,
                                        VkImage2D source, GpuTextureView[] dataTargets) {
        var viewport = rt.viewport;
        var cmd = this.ctx.cmd();

        int colortex0Format = VkFrameHost.vkFormat(colortex0);
        int[] packFormats = null;
        if (viewport.isPackMode()) {
            packFormats = new int[dataTargets == null ? 0 : dataTargets.length];
            for (int i = 0; i < packFormats.length; i++) {
                packFormats[i] = VkFrameHost.vkFormat(dataTargets[i]);
            }
        }
        this.ensureCompositeColortex0Pipeline(colortex0Format, mcDepthFormat, packFormats);
        int dataCount = packFormats == null ? 0 : packFormats.length;

        VkFrameHost.barrierDepthAttachment(cmd, rt.mcDepth);
        VkFrameHost.barrierColorAttachment(cmd, colortex0);
        for (int i = 0; i < dataCount; i++) {
            VkFrameHost.barrierColorAttachment(cmd, dataTargets[i]);
        }

        int width = colortex0.getWidth(0);
        int height = colortex0.getHeight(0);

        try (MemoryStack stack = stackPush()) {
            var colorAttach = VkRenderingAttachmentInfoKHR.calloc(1 + dataCount, stack);
            for (int i = 0; i <= dataCount; i++) {
                colorAttach.get(i).sType$Default()
                        .imageView(VkFrameHost.vkView(i == 0 ? colortex0 : dataTargets[i - 1]))
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
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(width).height(height)))
                    .layerCount(1)
                    .pColorAttachments(colorAttach)
                    .pDepthAttachment(depthAttach);
            vkCmdBeginRenderingKHR(cmd, info);
        }
        this.compositeColortex0.bind(cmd);
        VkCmd.setViewportScissor(cmd, width, height);
        try (var b = this.compositeColortex0.binder()) {
            b.sampler(0, viewport.depthSampleView, this.depthSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .ubo(1, this.compositeParams)
                    .sampler(3, source.view, this.colourSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            for (int i = 0; i < dataCount; i++) {
                b.sampler(4 + i, viewport.packData[i].view, this.colourSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            b.push(cmd);
        }
        vkCmdDraw(cmd, 4, 1, 0, 0);
        vkCmdEndRenderingKHR(cmd);

        VkFrameHost.barrierColorAttachment(cmd, colortex0);
        for (int i = 0; i < dataCount; i++) {
            VkFrameHost.barrierColorAttachment(cmd, dataTargets[i]);
        }
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
                e3 = 1.0f;

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
        if (this.compositeColortex0 != null) this.compositeColortex0.free();
        if (this.compositeWater != null) this.compositeWater.free();
        //depthSampler/colourSampler come from VkImage2D.createSampler's
        // device-lifetime cache (shared across compositor/SSAO/terrain); never
        // destroy them per-object (double vkDestroySampler -> SIGSEGV on unload)
        this.compositeParams.free();
    }
}
