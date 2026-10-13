package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.IRenderList;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;

import static org.lwjgl.vulkan.VK10.*;

//Pure-VK viewport: the MDICViewport buffer set as VkBuffers, plus the
// offscreen render targets (colour + D32S8 depth-stencil), the depth-bound
// image (vanilla-coverage optimisation), and the HiZ pyramid.
public class VkViewport extends Viewport<VkViewport> {
    public static final int OPAQUE_DRAW_COUNT = 400_000;
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;
    public static final int TEMPORAL_DRAW_COUNT = 100_000;

    private final VkFrameCtx ctx;

    public final VkBuffer drawCountCallBuffer;
    public final VkBuffer drawCallBuffer;
    public final VkBuffer positionScratchBuffer;
    public final VkBuffer indirectLookupBuffer;
    public final VkBuffer visibilityBuffer;

    //Offscreen targets, lazily (re)created on resize
    public VkImage2D colour;
    public VkImage2D depthStencil;
    public long depthSampleView;//DEPTH-aspect view of depthStencil for sampling
    public VkImage2D depthBound;
    public long depthBoundSampleView;
    //SSAO output colour: the compute pass reads `colour` and writes the AO-modulated
    // result (alpha sanitized to 1/0) here; translucents then draw onto it and the
    // compositor samples it — mirroring the GL colourTex/colourSSAOTex pair.
    public VkImage2D colourSSAO;
    //Shaderpack LOD programs: colour is then the pack-lit (HDR) colortex0 the pack writes, and these
    // hold its other opaque draw buffers (colortex6 for Complementary), composited into the pack's
    // own targets. Empty outside that mode.
    public VkImage2D[] packData = new VkImage2D[0];
    //...and the opaque depth in the window depth the pack reads (its vxDepthTexOpaque), null outside that mode
    public VkImage2D packDepth;
    //...and the face / AO flag of every opaque LOD pixel, for Voxy's SSAO (see ssao.comp PACK_AO)
    public VkImage2D packMeta;
    //...and the light (light map uv) and face plane of every opaque LOD pixel, written by the light
    // prepass and read back to blend the light between neighbouring blocks (quads.frag VOXY_SMOOTH_LIGHT)
    public VkImage2D packLight;
    public static final int PACK_LIGHT_FORMAT = VK_FORMAT_R32G32B32A32_SFLOAT;
    public static final int PACK_META_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private boolean packMode = false;
    public final VkHiZ hiZ;

    public VkViewport(VkFrameCtx ctx, RenderProperties properties, int maxSectionCount) {
        super(properties);
        this.ctx = ctx;
        this.drawCountCallBuffer = new VkBuffer(ctx, 1024).zero();
        this.drawCallBuffer = new VkBuffer(ctx, 5L * 4 * (OPAQUE_DRAW_COUNT + TRANSLUCENT_DRAW_COUNT + TEMPORAL_DRAW_COUNT)).zero();
        this.positionScratchBuffer = new VkBuffer(ctx, 8L * 400000).zero();
        this.indirectLookupBuffer = new VkBuffer(ctx, HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE * 4L + 4).zero();
        this.visibilityBuffer = new VkBuffer(ctx, maxSectionCount * 4L).zero();
        this.hiZ = new VkHiZ(ctx, properties);
        ctx.flushImmediate();
    }

    @Override
    protected boolean useGlViewportHelpers() {
        return false;
    }

    public static final int PACK_COLOUR_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    public static final int PACK_DATA_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    public boolean isPackMode() {
        return this.packMode;
    }

    /**
     * (Re)creates the offscreen targets on size or mode change; true if recreated.
     * @param packDataTargets -1 outside shaderpack LOD mode, else how many extra opaque draw buffers it writes
     */
    public boolean ensureTargets(int packDataTargets) {
        if (this.width <= 0 || this.height <= 0) return false;
        boolean packMode = packDataTargets >= 0;
        int dataCount = Math.max(0, packDataTargets);
        if (this.colour != null && this.colour.width == this.width && this.colour.height == this.height
                && this.packMode == packMode && this.packData.length == dataCount) return false;
        if (this.colour != null) {
            this.freeTargets();//destruction is deferred until the frames using them retire
        }
        this.packMode = packMode;
        this.colour = new VkImage2D(this.ctx, this.width, this.height, 1,
                packMode ? PACK_COLOUR_FORMAT : VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false);
        this.packDepth = packMode ? new VkImage2D(this.ctx, this.width, this.height, 1, VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false) : null;
        this.packMeta = packMode ? new VkImage2D(this.ctx, this.width, this.height, 1, PACK_META_FORMAT,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false) : null;
        this.packLight = packMode ? new VkImage2D(this.ctx, this.width, this.height, 1, PACK_LIGHT_FORMAT,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false) : null;
        this.packData = new VkImage2D[dataCount];
        for (int i = 0; i < dataCount; i++) {
            this.packData[i] = new VkImage2D(this.ctx, this.width, this.height, 1, PACK_DATA_FORMAT,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT, false);
        }
        this.colourSSAO = new VkImage2D(this.ctx, this.width, this.height, 1,
                packMode ? PACK_COLOUR_FORMAT : VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false);
        this.depthStencil = new VkImage2D(this.ctx, this.width, this.height, 1,
                VK_FORMAT_D32_SFLOAT_S8_UINT,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, false,
                //VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT + view-format list lets the
                // depth-only aspect view alias the image directly. Without this,
                // MoltenVK may have to synthesise a separate staging texture for
                // depth-only sampling of a packed D32S8 image. The list must
                // contain the original format plus the depth-only format.
                new int[]{VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D32_SFLOAT});
        this.depthSampleView = this.depthStencil.createAspectView(VK_IMAGE_ASPECT_DEPTH_BIT);
        this.depthBound = new VkImage2D(this.ctx, this.width, this.height, 1,
                VK_FORMAT_D32_SFLOAT,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT, false);
        this.depthBoundSampleView = this.depthBound.view;
        return true;
    }

    private void freeTargets() {
        this.colour.free();
        this.colourSSAO.free();
        this.depthStencil.free();
        this.depthBound.free();
        for (var data : this.packData) {
            data.free();
        }
        this.packData = new VkImage2D[0];
        if (this.packDepth != null) {
            this.packDepth.free();
            this.packDepth = null;
        }
        if (this.packMeta != null) {
            this.packMeta.free();
            this.packMeta = null;
        }
        if (this.packLight != null) {
            this.packLight.free();
            this.packLight = null;
        }
    }

    @Override
    protected void delete0() {
        super.delete0();
        if (this.colour != null) {
            this.freeTargets();
        }
        this.hiZ.free();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public IRenderList getRenderList() {
        return this.indirectLookupBuffer;
    }
}
