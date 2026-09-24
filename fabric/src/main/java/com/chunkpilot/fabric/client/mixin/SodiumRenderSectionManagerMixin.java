package com.chunkpilot.fabric.client.mixin;

import com.chunkpilot.client.ChunkPilotClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium 兼容 Mixin (DESIGN.md §12.4)
 *
 * 拦截 RenderSectionManager.shouldPrioritizeTask,
 * 用 CP 方向权重决定哪些区块进入 important 队列.
 *
 * 仿 C2ME 兼容思路: 检测 Sodium → Mixin 其内部方法
 * shouldPrioritizeTask 原始逻辑: 仅判断距离 < threshold
 * CP 注入: 高速时前方区块强制返回 true
 *
 * 注意: Sodium 类名可能在版本间变化, 用 @Pseudo + try-catch 降级
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class SodiumRenderSectionManagerMixin {

    /**
     * 拦截 shouldPrioritizeTask, 在高速飞行时让前方区块进入 important 队列.
     *
     * 原始签名: private boolean shouldPrioritizeTask(RenderSection section, float distance)
     * RenderSection 有 getChunkX()/getChunkZ() 方法
     */
    @Inject(
        method = "shouldPrioritizeTask",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        // 1.20.1 移植加固: Sodium 内部方法名跨版本会变; require=0 → 找不到就跳过,
        // 不让"兼容性的可选项"把客户端拖崩.
        require = 0
    )
    private void chunkpilot$directionAwarePriority(Object section, float distance, CallbackInfoReturnable<Boolean> cir) {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled() || !client.isSodiumDetected()) return;

        try {
            // 反射获取 chunk 坐标 (RenderSection 有 getChunkX/getChunkZ)
            int chunkX = (int) section.getClass().getMethod("getChunkX").invoke(section);
            int chunkZ = (int) section.getClass().getMethod("getChunkZ").invoke(section);

            if (client.shouldPrioritizeChunk(chunkX, chunkZ)) {
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            // 反射失败: Sodium API 变了, 静默降级
            // 后续可以加日志
        }
    }
}