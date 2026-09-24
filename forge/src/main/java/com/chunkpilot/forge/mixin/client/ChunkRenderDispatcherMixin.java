package com.chunkpilot.forge.mixin.client;

import com.chunkpilot.client.ChunkPilotClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla 渲染路径的占位 Mixin (Forge 端)。
 *
 * 用字符串 target 避免编译期需要客户端类。
 *
 * ============================ 1.20.1 的改动: `require = 0` ============================
 * 目标方法 `updateView` 是 **1.21.x 才有**的 (1.20.1 的 `ChunkRenderDispatcher` 里没有);
 * 原样 (require=1) 会在**客户端启动期**抛 InjectionError。
 * fabric/1.20.1 分支踩到过同一个坑, 处理方式一致: `require = 0` ⇒ 目标缺失时静默跳过。
 * 方法体本身是 no-op 标记点, 所以这个降级不影响任何功能。
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher", remap = true)
public class ChunkRenderDispatcherMixin {

    @Inject(
        method = "updateView",
        at = @At("TAIL"),
        remap = true,
        require = 0
    )
    private void chunkpilot$reorderRenderQueue(CallbackInfo ci) {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled()) return;
        if (client.isSodiumDetected()) return;
        // Vanilla 路径标记点 (无副作用)
    }
}
