package com.chunkpilot.neoforge.client.mixin;

import com.chunkpilot.client.ChunkPilotClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium 兼容 Mixin (NeoForge 端)
 *
 * 仿 C2ME 兼容思路: 检测 Sodium → Mixin RenderSectionManager.shouldPrioritizeTask
 * 高速飞行时前方区块强制返回 true, 进入 important 队列
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class SodiumRenderSectionManagerMixin {

    @Inject(
        method = "shouldPrioritizeTask",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void chunkpilot$directionAwarePriority(Object section, float distance, CallbackInfoReturnable<Boolean> cir) {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled() || !client.isSodiumDetected()) return;

        try {
            int chunkX = (int) section.getClass().getMethod("getChunkX").invoke(section);
            int chunkZ = (int) section.getClass().getMethod("getChunkZ").invoke(section);

            if (client.shouldPrioritizeChunk(chunkX, chunkZ)) {
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            // Sodium API 变了, 静默降级
        }
    }
}