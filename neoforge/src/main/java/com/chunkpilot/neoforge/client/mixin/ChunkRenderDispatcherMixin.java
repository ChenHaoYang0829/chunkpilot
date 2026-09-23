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
 *
 * ============================ port/1.21.5: 已停用 (不注册进 mixins.json) ============================
 * 1.21.5 把 `net.minecraft.client.renderer.chunk.ChunkRenderDispatcher` 改名成了
 * `net.minecraft.client.renderer.chunk.SectionRenderDispatcher` (1.21.5 的 minecraft-merged 里
 * 已无 ChunkRenderDispatcher, 只有 SectionRenderDispatcher)。
 * 本 mixin 方法体是**空实现**(仅"Vanilla 路径标记点"), 停用不损失任何功能, 所以按 AGENT_BRIEF §3:
 * 从 chunkpilot.client.mixins.json 取消注册, 而不是硬凑目标 —— 保证客户端/服务端都能正常启动。
 * (neoforge 的 client mixins.json 是 "required": true, 继续注册会让客户端直接崩。)
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher", remap = true)
public class ChunkRenderDispatcherMixin {

    @Inject(
        method = "updateView",
        at = @At("TAIL"),
        remap = true
    )
    private void chunkpilot$reorderRenderQueue(CallbackInfo ci) {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled()) return;
        if (client.isSodiumDetected()) return;
        // Vanilla 路径标记点
    }
}