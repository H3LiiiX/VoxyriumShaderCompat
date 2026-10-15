package me.cortex.voxy.client.core.backend.blaze3d;

//? if MC_26_3
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
//? else
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
//? endif
import com.mojang.blaze3d.systems.RenderSystem;
//? if MC_26_3
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//? else
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
//? endif
import me.cortex.voxy.client.core.model.IModelStore;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * CPU mirror of Cortex's model store plus a Blaze3D-owned copy of its baked model atlas.
 * RenderDataFactory still owns model selection and quad generation; this class only makes its
 * already-computed model metadata, biome colours and texture tiles available to the GPU.
 */
final class Blaze3dModelStore implements IModelStore {
    private static final int TEXTURE_UPLOADS_PER_FRAME = 64;
    private static final long TEXTURE_UPLOAD_BUDGET_NANOS = 2_000_000L;
    private static final int MODEL_CAPACITY = 1 << 16;
    private static final int MODEL_INTS = IModelStore.MODEL_SIZE / Integer.BYTES;
    private static final int COLOUR_CAPACITY = 1 << 16;
    private static final int ATLAS_WIDTH = ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256;
    private static final int ATLAS_HEIGHT = ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256;

    private final int[] modelData = new int[MODEL_CAPACITY * MODEL_INTS];
    private final AtomicIntegerArray readyModels = new AtomicIntegerArray(MODEL_CAPACITY);
    private final AtomicIntegerArray colourIndices = new AtomicIntegerArray(MODEL_CAPACITY);
    private final AtomicLongArray modelTextureVersions = new AtomicLongArray(MODEL_CAPACITY);
    private final ConcurrentLinkedQueue<ModelUpload> pendingTextures = new ConcurrentLinkedQueue<>();
    private final AtomicLong stagedTextureVersion = new AtomicLong();
    private volatile long uploadedTextureVersion;
    private volatile long stagedBiomeVersion;
    private final GpuBuffer facesBuffer;
    private final GpuBuffer infoBuffer;
    private final GpuBuffer coloursBuffer;
    private long uploadedTableBytes;
    private final GpuTexture atlas;
    private final GpuTextureView atlasView;
    private volatile boolean freed;

    Blaze3dModelStore() {
        RenderSystem.assertOnRenderThread();
        GpuTexture atlas = null;
        GpuTextureView view = null;
        GpuBuffer faces = null, info = null, colours = null;
        try {
            atlas = RenderSystem.getDevice().createTexture(
                    "Voxy Blaze3D baked model atlas",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, ATLAS_WIDTH, ATLAS_HEIGHT, 1, ModelFactory.LAYERS);
            view = RenderSystem.getDevice().createTextureView(atlas);
            int usage = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;
            faces = RenderSystem.getDevice().createBuffer(() -> "Voxy model faces 0-3", usage,
                    (long) MODEL_CAPACITY * Blaze3dModelEncoder.TABLE_STRIDE);
            info = RenderSystem.getDevice().createBuffer(() -> "Voxy model faces 4-5, flags and tint", usage,
                    (long) MODEL_CAPACITY * Blaze3dModelEncoder.TABLE_STRIDE);
            ByteBuffer zeroColours = MemoryUtil.memCalloc(COLOUR_CAPACITY * Integer.BYTES);
            try {
                colours = RenderSystem.getDevice().createBuffer(() -> "Voxy biome colours", usage, zeroColours);
            } finally { MemoryUtil.memFree(zeroColours); }
            this.atlas = atlas;
            this.atlasView = view;
            this.facesBuffer = faces;
            this.infoBuffer = info;
            this.coloursBuffer = colours;
        } catch (RuntimeException | Error exception) {
            if (colours != null) colours.close();
            if (info != null) info.close();
            if (faces != null) faces.close();
            if (view != null) view.close();
            if (atlas != null) atlas.close();
            throw exception;
        }
    }

    @Override
    public synchronized void stageModelData(int modelId, MemoryBuffer model, int biomeUploadIndex,
                               @Nullable MemoryBuffer biomeUpload, MemoryBuffer texture) {
        if (this.freed) {
            return;
        }
        ByteBuffer modelBytes = model.asByteBuffer().duplicate().order(ByteOrder.nativeOrder());
        modelBytes.asIntBuffer().get(this.modelData, modelId * MODEL_INTS, MODEL_INTS);
        if (biomeUploadIndex == -1) biomeUpload = null;
        if (biomeUpload != null) validateColours(biomeUpload, biomeUploadIndex);
        this.colourIndices.set(modelId, this.modelData[modelId * MODEL_INTS + 7]);

        queueUpload(modelId, modelId, biomeUploadIndex, biomeUpload, modelId, texture);
        // Publish only after all immutable upload data and its version have been queued.
        this.readyModels.set(modelId, 1);
    }

    @Override
    public synchronized void stageBiomeData(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
        if (this.freed) return;
        validateColours(biomeColourBuffer, 0);
        int first = MODEL_CAPACITY, last = -1;
        long pointer = modelBiomeIndexPairs.address;
        for (long offset = 0; offset < modelBiomeIndexPairs.size; offset += Long.BYTES) {
            long pair = MemoryUtil.memGetLong(pointer + offset);
            int modelId = (int) pair;
            this.colourIndices.set(modelId, (int) (pair >>> 32));
            first = Math.min(first, modelId);
            last = Math.max(last, modelId);
        }
        if (last >= first) this.stagedBiomeVersion = queueUpload(first, last, 0, biomeColourBuffer, -1, null);
    }

    private long queueUpload(int first, int last, int firstColour, @Nullable MemoryBuffer colours,
                             int textureModel, @Nullable MemoryBuffer texture) {
        ByteBuffer faces = null, info = null, colourBytes = null, textureBytes = null;
        boolean published = false;
        try {
            int bytes = (last - first + 1) * Blaze3dModelEncoder.TABLE_STRIDE;
            // Biome repacks change only tint bases; do not resend the unchanged faces table.
            if (texture != null) faces = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
            info = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
            for (int model = first; model <= last; model++) {
                int colour = this.colourIndices.get(model);
                if (faces != null) Blaze3dModelEncoder.put(faces, info, this.modelData, model * MODEL_INTS, colour);
                else Blaze3dModelEncoder.putInfo(info, this.modelData, model * MODEL_INTS, colour);
            }
            if (faces != null) faces.flip();
            info.flip();
            if (colours != null && colours.size != 0) colourBytes = copyUpload(colours);
            if (texture != null) textureBytes = copyUpload(texture);
            long version = this.stagedTextureVersion.incrementAndGet();
            this.pendingTextures.add(new ModelUpload(version, first, faces, info,
                    firstColour, colourBytes, textureModel, textureBytes));
            published = true;
            // A biome repack changes both palette entries and per-model base indices.
            // Coalesce the affected range into one info write; never upload one pointer at a time.
            for (int model = first; model <= last; model++) this.modelTextureVersions.set(model, version);
            return version;
        } finally {
            if (!published) {
                freeUpload(faces); freeUpload(info); freeUpload(colourBytes); freeUpload(textureBytes);
            }
        }
    }

    private static ByteBuffer copyUpload(MemoryBuffer source) {
        ByteBuffer copy = MemoryUtil.memAlloc(Math.toIntExact(source.size)).order(ByteOrder.nativeOrder());
        try { return copy.put(source.asByteBuffer().duplicate()).flip(); }
        catch (RuntimeException | Error exception) { MemoryUtil.memFree(copy); throw exception; }
    }

    private static void freeUpload(@Nullable ByteBuffer bytes) { if (bytes != null) MemoryUtil.memFree(bytes); }

    private static void validateColours(MemoryBuffer source, int destinationIndex) {
        if (destinationIndex < 0 || (source.size & 3L) != 0
                || source.size / Integer.BYTES > COLOUR_CAPACITY - (long) destinationIndex) {
            throw new IllegalArgumentException("Blaze3D biome palette exceeds the shared colour table");
        }
    }

    long biomeVersion() { return this.stagedBiomeVersion; }

    private void assertReady(int modelId) {
        if (modelId < 0 || modelId >= MODEL_CAPACITY || this.readyModels.get(modelId) == 0) {
            throw new IllegalStateException("Cortex model data was not staged for model " + modelId);
        }
    }

    void uploadPendingTextures(CommandEncoder encoder) {
        RenderSystem.assertOnRenderThread();
        ModelUpload upload;
        long started = System.nanoTime();
        int uploaded = 0;
        while (uploaded < TEXTURE_UPLOADS_PER_FRAME
                && (uploaded == 0 || System.nanoTime() - started < TEXTURE_UPLOAD_BUDGET_NANOS)
                && (upload = this.pendingTextures.poll()) != null) {
            try {
                long offset = (long) upload.firstModel() * Blaze3dModelEncoder.TABLE_STRIDE;
                int infoBytes = upload.info().remaining();
                if (upload.faces() != null) {
                    int faceBytes = upload.faces().remaining();
                    encoder.writeToBuffer(this.facesBuffer.slice(offset, faceBytes), upload.faces());
                    this.uploadedTableBytes += faceBytes;
                }
                encoder.writeToBuffer(this.infoBuffer.slice(offset, infoBytes), upload.info());
                this.uploadedTableBytes += infoBytes;
                if (upload.colours() != null) {
                    int colourBytes = upload.colours().remaining();
                    encoder.writeToBuffer(this.coloursBuffer.slice((long) upload.firstColour() * Integer.BYTES,
                            colourBytes), upload.colours());
                    this.uploadedTableBytes += colourBytes;
                }
                if (upload.texture() != null) {
                    int x = (upload.textureModel() & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
                    int y = ((upload.textureModel() >> 8) & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
                    int sourceOffset = 0;
                    for (int level = 0; level < ModelFactory.LAYERS; level++) {
                        int width = (ModelFactory.MODEL_TEXTURE_SIZE * 3) >> level;
                        int height = (ModelFactory.MODEL_TEXTURE_SIZE * 2) >> level;
                        int bytes = width * height * Integer.BYTES;
                        ByteBuffer mip = upload.texture().duplicate();
                        mip.position(sourceOffset).limit(sourceOffset + bytes);
                        encoder.writeToTexture(this.atlas, mip.slice(), level, 0,
                                x >> level, y >> level, width, height);
                        sourceOffset += bytes;
                    }
                }
                // Advance only when faces, palette, pointers and atlas are all submitted.
                this.uploadedTextureVersion = upload.version();
                uploaded++;
            } finally { upload.free(); }
        }
    }

    void bindModelTables(RenderPass pass) {
        pass.setUniform("VoxyModelFaces", this.facesBuffer);
        pass.setUniform("VoxyModelInfo", this.infoBuffer);
        pass.setUniform("VoxyColours", this.coloursBuffer);
    }

    GpuTextureView atlasView() {
        return this.atlasView;
    }

    long textureVersion(int modelId) {
        assertReady(modelId);
        return this.modelTextureVersions.get(modelId);
    }

    boolean isTextureVersionUploaded(long version) {
        return this.uploadedTextureVersion >= version;
    }

    String benchmarkSummary() {
        return "stagedTextureVersion=" + this.stagedTextureVersion.get()
                + " uploadedTextureVersion=" + this.uploadedTextureVersion
                + " pendingTextures=" + this.pendingTextures.size()
                + " modelTableBytes=" + ((long) MODEL_CAPACITY * Blaze3dModelEncoder.TABLE_STRIDE * 2 + COLOUR_CAPACITY * 4L)
                + " uploadedTableBytes=" + this.uploadedTableBytes;
    }

    GpuSampler atlasSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
    }

    @Override
    public void uploadModelData(int modelId, MemoryBuffer model, int biomeUploadIndex,
                                @Nullable MemoryBuffer biomeUpload, MemoryBuffer texture) {
        // stageModelData already queued immutable metadata, palette and atlas copies together.
    }

    @Override
    public void uploadBiomeData(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
        // stageBiomeData already queued the palette and all affected model tint bases together.
    }

    @Override
    public void finishUploads() {
    }

    @Override
    public IDeviceBuffer modelBufferHandle() {
        throw new UnsupportedOperationException("Blaze3D tables use the public device buffer API");
    }

    @Override
    public IDeviceBuffer colourBufferHandle() {
        throw new UnsupportedOperationException("Blaze3D tables use the public device buffer API");
    }

    @Override
    public void beginTextureUploads() {
    }

    @Override
    public void uploadModelTexture(int modelId, MemoryBuffer texture) {
    }

    @Override
    public void endTextureUploads() {
    }

    @Override
    public synchronized void free() {
        RenderSystem.assertOnRenderThread();
        this.freed = true;
        ModelUpload upload;
        while ((upload = this.pendingTextures.poll()) != null) upload.free();
        this.coloursBuffer.close();
        this.infoBuffer.close();
        this.facesBuffer.close();
        this.atlasView.close();
        this.atlas.close();
    }

    private record ModelUpload(long version, int firstModel, @Nullable ByteBuffer faces, ByteBuffer info,
                               int firstColour, @Nullable ByteBuffer colours,
                               int textureModel, @Nullable ByteBuffer texture) {
        void free() { freeUpload(this.faces); freeUpload(this.info); freeUpload(this.colours); freeUpload(this.texture); }
    }
}
