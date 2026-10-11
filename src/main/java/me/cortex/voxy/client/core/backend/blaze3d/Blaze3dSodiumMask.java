package me.cortex.voxy.client.core.backend.blaze3d;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Public Blaze3D upload/binding for the small near-terrain column mask. */
final class Blaze3dSodiumMask implements AutoCloseable {
    private GpuTexture texture;
    private GpuTextureView view;
    private GpuBufferSlice uniform;
    private Blaze3dSodiumCoverage uploaded;

    void update(CommandEncoder encoder, Blaze3dSodiumCoverage coverage, float overlap) {
        if (this.uploaded != coverage) {
            if (this.texture == null || this.texture.getWidth(0) != coverage.width() || this.texture.getHeight(0) != coverage.height()) {
                if (this.view != null) this.view.close();
                if (this.texture != null) this.texture.close();
                this.view = null; this.texture = null; this.uploaded = null;
                this.texture = RenderSystem.getDevice().createTexture("Voxy Sodium visible columns",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R8_UINT,
                        coverage.width(), coverage.height(), 1, 1);
                this.view = RenderSystem.getDevice().createTextureView(this.texture);
            }
            ByteBuffer data = encoder.transientMemory().allocateCpu(coverage.pixels().length, 1);
            data.put(coverage.pixels()).flip();
            encoder.writeToTexture(this.texture, data, 0, 0, 0, 0, coverage.width(), coverage.height());
            this.uploaded = coverage;
        }
        ByteBuffer data = encoder.transientMemory().allocateCpu(32, 4).order(ByteOrder.nativeOrder());
        data.putInt(coverage.x()).putInt(coverage.z()).putInt(coverage.width()).putInt(coverage.height());
        data.putFloat(Math.clamp(overlap, 0, 2)).putFloat(0).putFloat(0).putFloat(0).flip();
        this.uniform = encoder.transientMemory().uploadGpu(data,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
    }
    void bind(RenderPass pass) {
        pass.setUniform("VoxyCoverage", this.uniform);
        pass.setUniform("VoxySodiumMask", this.view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    @Override public void close() {
        if (this.view != null) this.view.close();
        if (this.texture != null) this.texture.close();
        this.view = null; this.texture = null; this.uploaded = null; this.uniform = null;
    }
}
