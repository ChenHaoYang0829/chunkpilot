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
 * 这样无需 import 客户端类, 在缺少客户端类 / 客户端 mappings 的编译环境下同样能通过
 * (Architectury Loom main sourceSet 不含客户端 mappings).
 *
 * ============================ port/1.21.5: 已停用 (不注册进 mixins.json) ============================
 * 1.21.5 把 `net.minecraft.client.renderer.chunk.ChunkRenderDispatcher` 改名成了
 * `net.minecraft.client.renderer.chunk.SectionRenderDispatcher` (javap/zip 实证:
 * 1.21.5 的 minecraft-merged 里已经没有 ChunkRenderDispatcher, 只有 SectionRenderDispatcher)。
 * 本 mixin 的方法体是**空实现**(仅"Vanilla 路径标记点"), 停用不损失任何功能,
 * 因此按 AGENT_BRIEF §3 的做法: 从 chunkpilot.client.mixins.json 取消注册 (而不是硬凑目标),
 * 保证客户端/服务端都能正常启动。若将来要恢复, 需按 SectionRenderDispatcher.updateView 的新签名重写。
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