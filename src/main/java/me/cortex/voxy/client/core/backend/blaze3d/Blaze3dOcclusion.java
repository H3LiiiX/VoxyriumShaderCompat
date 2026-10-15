package me.cortex.voxy.client.core.backend.blaze3d;

//? if MC_26_3
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
//? else
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
//? endif
import com.mojang.blaze3d.systems.RenderSystem;
//? if MC_26_3
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//? else
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
//? endif
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** GPU-only conservative section visibility, derived from occluders rendered in the current frame. */
final class Blaze3dOcclusion implements AutoCloseable {
    private static final RenderPipeline COPY = Blaze3dAuxiliaryPipelines.HIZ_COPY;
    private static final RenderPipeline REDUCE = Blaze3dAuxiliaryPipelines.HIZ_REDUCE;
    private static final RenderPipeline VISIBILITY = Blaze3dAuxiliaryPipelines.HIZ_VISIBILITY;
    private static final RenderPipeline COMMANDS = Blaze3dAuxiliaryPipelines.HIZ_COMMANDS;
    private GpuTexture hierarchy, visibility;
    private GpuTextureView hierarchyView, visibilityView;
    private GpuTextureView[] levels;
    private GpuBuffer origins, bounds, counts, commands;
    private GpuTexture commandTexture;
    private GpuTextureView commandView;
    private ByteBuffer originData, boundData, countData;
    private boolean active;
    private int entries;

    void prepare(int width, int height, boolean enabled) {
        ensureResources(width, height);
        this.active = enabled;
        if (enabled) {
            MemoryUtil.memSet(MemoryUtil.memAddress(this.originData), 0, this.originData.capacity());
            MemoryUtil.memSet(MemoryUtil.memAddress(this.boundData), 0, this.boundData.capacity());
            if (this.countData != null) MemoryUtil.memSet(MemoryUtil.memAddress(this.countData), 0, this.countData.capacity());
            this.entries = 0;
        }
    }
    void candidate(float x, float y, float z, float scale, int aabb, int opaque, int water) {
        if (!this.active) return;
        int index = Blaze3dOcclusionHash.index(x, y, z, scale), offset = index * 16;
        // Only one section owns a slot. Colliding sections fail the origin comparison and remain visible.
        if (this.originData.getFloat(offset + 12) != 0.0f) return;
        this.originData.putFloat(offset, x).putFloat(offset + 4, y).putFloat(offset + 8, z).putFloat(offset + 12, scale);
        this.boundData.putInt(index * 4, aabb);
        if (this.countData != null) this.countData.putInt(index * 8, opaque).putInt(index * 8 + 4, water);
        this.entries++;
    }
    GpuBufferSlice command(float x, float y, float z, float scale, boolean water) {
        if (!this.active || this.commands == null) return null;
        int index = Blaze3dOcclusionHash.index(x, y, z, scale);
        // Hash collisions retain direct draws: never consume another section's instance count.
        if (!Blaze3dOcclusionHash.owns(this.originData, index, x, y, z, scale)) return null;
        return this.commands.slice((long) index * 48 + (water ? 20 : 0), 20);
    }
    boolean hasIndirect() { return this.commands != null; }
    void bind(RenderPass pass) {
        pass.setUniform("VoxyOcclusionOrigins", this.origins);
//? if MC_26_3
        pass.setUniform("VoxyVisibility", this.visibilityView,
//? else
        pass.bindTexture("VoxyVisibility", this.visibilityView,
//? endif
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    void capture(CommandEncoder encoder, GpuTextureView depth, GpuBuffer fullscreen, Matrix4fc view) {
        if (!this.active) return;
        encoder.writeToBuffer(this.origins.slice(), this.originData.duplicate());
        encoder.writeToBuffer(this.bounds.slice(), this.boundData.duplicate());
        for (int level = 0; level < this.levels.length; level++) {
            GpuTextureView source = level == 0 ? depth : this.levels[level - 1];
            try (RenderPass pass = encoder.createRenderPass(() -> "Voxy conservative depth reduction",
                    this.levels[level], Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
//? if MC_26_3
                pass.setPipeline(RenderSystem.getCompiledPipeline(level == 0 ? COPY : REDUCE));
                pass.setUniform("Sampler0", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
//? else
                pass.setPipeline(level == 0 ? COPY : REDUCE);
                pass.bindTexture("Sampler0", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
//? endif
                pass.setVertexBuffer(0, fullscreen.slice());
                pass.draw(4, 1, 0, 0);
            }
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Voxy section visibility",
                this.visibilityView, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
//? if MC_26_3
            pass.setPipeline(RenderSystem.getCompiledPipeline(VISIBILITY));
//? else
            pass.setPipeline(VISIBILITY);
//? endif
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view),
                    new Vector4f(1), new Vector3f(depth.getWidth(0), depth.getHeight(0),
                            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0), new Matrix4f()));
            pass.setUniform("VoxyOcclusionOrigins", this.origins);
            pass.setUniform("VoxyOcclusionBounds", this.bounds);
//? if MC_26_3
            pass.setUniform("Sampler0", this.hierarchyView,
//? else
            pass.bindTexture("Sampler0", this.hierarchyView,
//? endif
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setVertexBuffer(0, fullscreen.slice());
            pass.draw(4, 1, 0, 0);
        }
        if (this.commands != null) {
            encoder.writeToBuffer(this.counts.slice(), this.countData.duplicate());
            try (RenderPass pass = encoder.createRenderPass(() -> "Voxy GPU indirect command generation",
                    this.commandView, Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
//? if MC_26_3
                pass.setPipeline(RenderSystem.getCompiledPipeline(COMMANDS));
//? else
                pass.setPipeline(COMMANDS);
//? endif
                pass.setUniform("VoxyOcclusionCounts", this.counts);
//? if MC_26_3
                pass.setUniform("Sampler0", this.visibilityView,
//? else
                pass.bindTexture("Sampler0", this.visibilityView,
//? endif
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setVertexBuffer(0, fullscreen.slice());
                pass.draw(4, 1, 0, 0);
            }
            // GPU-to-GPU transfer, with no mapping, readback or wait on the render thread.
            encoder.copyTextureToBuffer(this.commandTexture, this.commands, 0, () -> {}, 0,
                    0, 0, 768, 256);
        }
    }
    String summary() { return "gpuOcclusion=" + this.active + " occlusionEntries=" + this.entries
            + " gpuIndirect=" + (this.active && this.commands != null); }

    private void ensureResources(int width, int height) {
        if (this.origins == null) {
            try {
                this.originData = MemoryUtil.memCalloc(Blaze3dOcclusionHash.CAPACITY * 16).order(ByteOrder.nativeOrder());
                this.boundData = MemoryUtil.memCalloc(Blaze3dOcclusionHash.CAPACITY * 4).order(ByteOrder.nativeOrder());
                int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER;
                this.origins = RenderSystem.getDevice().createBuffer(() -> "Voxy occlusion section origins", usage, this.originData.duplicate());
                this.bounds = RenderSystem.getDevice().createBuffer(() -> "Voxy occlusion section bounds", usage, this.boundData.duplicate());
                this.visibility = RenderSystem.getDevice().createTexture("Voxy section visibility",
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.R8_UNORM, 256, 256, 1, 1);
                this.visibilityView = RenderSystem.getDevice().createTextureView(this.visibility);
                if (RenderSystem.getDevice().getDeviceInfo().features().drawIndirect()) {
                    this.countData = MemoryUtil.memCalloc(Blaze3dOcclusionHash.CAPACITY * 8).order(ByteOrder.nativeOrder());
                    this.counts = RenderSystem.getDevice().createBuffer(() -> "Voxy occlusion quad counts", usage, this.countData.duplicate());
                    this.commands = RenderSystem.getDevice().createBuffer(() -> "Voxy GPU indirect commands",
                            GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDIRECT_PARAMETERS, 48L * Blaze3dOcclusionHash.CAPACITY);
                    this.commandTexture = RenderSystem.getDevice().createTexture("Voxy integer draw commands",
                            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                            GpuFormat.RGBA32_UINT, 768, 256, 1, 1);
                    this.commandView = RenderSystem.getDevice().createTextureView(this.commandTexture);
                }
            } catch (RuntimeException | Error exception) { close(); throw exception; }
        }
        int w = nextPowerOfTwo((width + 3) / 4), h = nextPowerOfTwo((height + 3) / 4);
        if (this.hierarchy != null && this.hierarchy.getWidth(0) == w && this.hierarchy.getHeight(0) == h) return;
        releaseHierarchy();
        try {
            int count = 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
            this.hierarchy = RenderSystem.getDevice().createTexture("Voxy minimum reverse-Z hierarchy",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.R32_FLOAT, w, h, 1, count);
            this.hierarchyView = RenderSystem.getDevice().createTextureView(this.hierarchy);
            this.levels = new GpuTextureView[count];
            for (int i = 0; i < count; i++) this.levels[i] = RenderSystem.getDevice().createTextureView(this.hierarchy, i, 1);
        } catch (RuntimeException | Error exception) { close(); throw exception; }
    }
    private static int nextPowerOfTwo(int value) { return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1; }
    private void releaseHierarchy() {
        if (this.levels != null) for (var level : this.levels) if (level != null) level.close();
        if (this.hierarchyView != null) this.hierarchyView.close();
        if (this.hierarchy != null) this.hierarchy.close();
        this.levels = null; this.hierarchyView = null; this.hierarchy = null;
    }
    @Override public void close() {
        releaseHierarchy();
        if (this.visibilityView != null) this.visibilityView.close();
        if (this.visibility != null) this.visibility.close();
        if (this.origins != null) this.origins.close();
        if (this.bounds != null) this.bounds.close();
        if (this.counts != null) this.counts.close();
        if (this.commands != null) this.commands.close();
        if (this.commandView != null) this.commandView.close();
        if (this.commandTexture != null) this.commandTexture.close();
        if (this.originData != null) MemoryUtil.memFree(this.originData);
        if (this.boundData != null) MemoryUtil.memFree(this.boundData);
        if (this.countData != null) MemoryUtil.memFree(this.countData);
        this.visibilityView = null; this.visibility = null; this.origins = null; this.bounds = null;
        this.originData = null; this.boundData = null; this.active = false; this.entries = 0;
        this.counts = null; this.commands = null; this.commandView = null; this.commandTexture = null; this.countData = null;
    }
}
