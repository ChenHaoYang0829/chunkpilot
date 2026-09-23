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
 *
 * ===================== 1.21.8 移植: **已停用 (目标类消失)** =====================
 * javap/zip 实证 (1.21.8 minecraft-merged):
 *   net/minecraft/client/renderer/chunk/ChunkRenderDispatcher.class   → **不存在**
 *   net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class → 存在 (1.21.6 起改名)
 *   SectionRenderDispatcher 上也没有 updateView 方法
 *   (只剩 setLevel/setCameraPosition/schedule/rebuildSectionSync/compileQueue 等)。
 * 因此该 mixin 的注入点在 1.21.8 **不存在**, 已从
 * fabric / neoforge 的 chunkpilot.client.mixins.json 里移除注册。
 * 影响评估: 本 mixin 体内原本**没有任何实际逻辑** (只有 "Vanilla 路径标记点" 一行注释,
 *   真正的渲染重排从未实装 —— 见 DESIGN.md §12.4), 所以停用对功能零影响;
 *   保留 .java 文件只为记录与将来重新接线。
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