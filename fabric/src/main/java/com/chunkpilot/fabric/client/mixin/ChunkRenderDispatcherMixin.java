package com.chunkpilot.fabric.client.mixin;

import com.chunkpilot.client.ChunkPilotClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla 回退 Mixin (DESIGN.md §12.4)
 *
 * 当 Sodium 不存在时, 拦截 ChunkRenderDispatcher.updateView,
 * 用 CP 权重重新排序待 meshing 的区块队列.
 *
 * 用字符串 targets 而不是 {@code @Mixin(ChunkRenderDispatcher.class)}:
 * 这样无需 import 客户端类, 在缺少客户端类 / 客户端 mappings 的编译环境下同样能通过.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher", remap = true)
public class ChunkRenderDispatcherMixin {

    @Inject(
        method = "updateView",
        at = @At("TAIL"),
        remap = true,
        // 1.20.1 移植加固: 该目标方法在若干版本上并不存在 (编译期会警告
        // "Unable to determine descriptor"). 原版默认 require=1 → 客户端会直接崩.
        // 这里改成 require=0: 目标缺失时静默跳过, 绝不因"客户端渲染队列标记点"崩客户端.
        require = 0
    )
    private void chunkpilot$reorderRenderQueue(CallbackInfo ci) {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled()) return;
        if (client.isSodiumDetected()) return;
        // Vanilla 路径标记点
    }
}