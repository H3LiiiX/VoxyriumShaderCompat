package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IVoxyRenderSystemHolder {
    @Unique @Nullable private WorldIdentifier identifier;
    @Unique private @Nullable VoxyRenderSystem renderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
    }

    @Override
    public void voxy$shutdownRenderer() {
        VoxyBlaze3DProbeRenderer.shutdown();
        me.cortex.voxy.client.core.vk.VkFrameCapture.reset();
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

//? if MC_26_3
    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void voxy$afterExecuteSolid(net.minecraft.client.renderer.chunk.ChunkSectionsToRender chunks,
                                        net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame features,
                                        com.mojang.renderpearl.api.commands.RenderPass renderPass,
                                        CallbackInfo ci) {
        me.cortex.voxy.client.core.vk.VkFrameCapture.tryRenderPendingVkFrame();
    }

    @Inject(
        method = "lambda$addMainPass$0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;close()V",
            shift = At.Shift.AFTER,
            ordinal = 0
        ),
        require = 0
    )
    private void voxy$afterMainPassClose(com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,
                                         boolean oit,
                                         net.minecraft.client.renderer.chunk.ChunkSectionsToRender chunks,
                                         net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame features,
                                         boolean gizmos,
                                         boolean outline,
                                         CallbackInfo ci) {
        me.cortex.voxy.client.core.vk.VkFrameCapture.tryRenderPendingVkFrame();
    }

    @Inject(
        method = "lambda$addMainPass$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"
        ),
        require = 0
    )
    private void voxy$beforeExecuteOutline(com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,
                                           boolean oit,
                                           net.minecraft.client.renderer.chunk.ChunkSectionsToRender chunks,
                                           net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame features,
                                           boolean gizmos,
                                           boolean outline,
                                           CallbackInfo ci) {
        me.cortex.voxy.client.core.vk.VkFrameCapture.tryRenderPendingVkFrame();
    }
//? endif

    /*
    @Override
    public void voxy$reloadRenderer() {
        this.voxy$shutdownRenderer();
        this.voxy$createRenderer();
    }*/

    @Override
    public void voxy$setWorld(Level level) {
        WorldIdentifier identifier = level==null?null:WorldIdentifier.of(level);
        if (Objects.equals(this.identifier, identifier)) return;
        this.voxy$shutdownRenderer();
        this.identifier = identifier;
    }

    @Override
    public void voxy$createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.identifier == null) {
            Logger.info("Not creating renderer due to null identifier");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            //This is now legal (e.g. when the instance is disabled)
            Logger.info("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = this.identifier.getOrCreateEngine(true);
        if (world == null) {
            Logger.warn("Not creating renderer due to null engine");
            return;
        }
        this.voxy$createEngineDirect(world);
    }

    @Unique
    private void voxy$createEngineDirect(WorldEngine world) {
        var instance = world.instanceIn;
        if (instance == null) throw new IllegalStateException();//in theory this could be null if is like in a test suit or something
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                IrisUtil.disableIrisShaders();
            } else {
                throw e;
            }
        }
        instance.updateDedicatedThreads();
    }
}
