package com.chunkpilot.neoforge.client.mixin;

import com.chunkpilot.client.ChunkPilotClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla 回退 Mixin (NeoForge 端)
 *
 * 使用字符串目标避免编译时需要客户端类
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher", remap = true)
public class ChunkRenderDispatcherMixin {

    @Inject(
        method = "updateView",
        at = @At("TAIL"),
        remap = true,
        // 1.20.1 加固: 该目标方法在 1.20.1 上不存在 (编译期就警告 "Unable to determine
        // descriptor") —— 原版默认 require=1 会让**客户端**直接崩。require=0 表示找不到就跳过。
        require = 0
    )
    private void chunkpilot$reorderRenderQueue(CallbackInfo ci) {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled()) return;
        if (client.isSodiumDetected()) return;
        // Vanilla 路径标记点
    }
}