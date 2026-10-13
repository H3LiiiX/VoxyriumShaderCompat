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
    // hold its other opaque draw buffers (colortex6 for Complementary, all of them for a pack that
    // writes no colortex0), in output order with the colour left out, composited into the pack's own
    // targets. Empty outside that mode.
    public VkImage2D[] packData = new VkImage2D[0];
    //...and one per output of the pack's translucent program, drawn after its deferred stage
    public VkImage2D[] packTrans = new VkImage2D[0];
    //...and where the translucent program drew at all (alpha 1), which is what is composited
    public VkImage2D packTransMeta;
    private PackLayout packLayout;
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

    public static final int PACK_COLOUR_FORMAT = PackLayout.COLOUR_FORMAT;

    public boolean isPackMode() {
        return this.packMode;
    }

    /** The layout the pack-mode targets were made for, null outside that mode. */
    public PackLayout packLayout() {
        return this.packLayout;
    }

    /**
     * (Re)creates the offscreen targets on size or mode change; true if recreated.
     * @param layout null outside shaderpack LOD mode, else the outputs of the pack's LOD programs
     */
    public boolean ensureTargets(PackLayout layout) {
        if (this.width <= 0 || this.height <= 0) return false;
        boolean packMode = layout != null;
        if (this.colour != null && this.colour.width == this.width && this.colour.height == this.height
                && this.packMode == packMode && java.util.Objects.equals(this.packLayout, layout)) return false;
        if (this.colour != null) {
            this.freeTargets();//destruction is deferred until the frames using them retire
        }
        this.packMode = packMode;
        this.packLayout = layout;
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
        var data = new java.util.ArrayList<VkImage2D>();
        var trans = new java.util.ArrayList<VkImage2D>();
        if (packMode) {
            for (int i = 0; i < layout.opaqueCount(); i++) {
                if (i == layout.colourOutput()) continue;//that one is `colour`
                data.add(this.packImage(layout.opaqueFormats()[i]));
            }
            for (int i = 0; i < layout.translucentCount(); i++) {
                trans.add(this.packImage(layout.translucentFormats()[i]));
            }
        }
        this.packData = data.toArray(new VkImage2D[0]);
        this.packTrans = trans.toArray(new VkImage2D[0]);
        this.packTransMeta = packMode ? this.packImage(PACK_META_FORMAT) : null;
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

    private VkImage2D packImage(int format) {
        return new VkImage2D(this.ctx, this.width, this.height, 1, format,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false);
    }

    /** The image each output of the pack's opaque program is rasterised into, in output order. */
    public VkImage2D[] packOpaqueOutputs() {
        var layout = this.packLayout;
        var out = new VkImage2D[layout.opaqueCount()];
        int data = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = i == layout.colourOutput() ? this.colour : this.packData[data++];
        }
        return out;
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
        for (var trans : this.packTrans) {
            trans.free();
        }
        this.packTrans = new VkImage2D[0];
        if (this.packTransMeta != null) {
            this.packTransMeta.free();
            this.packTransMeta = null;
        }
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
