package com.chunkpilot.forge.mixin;

import com.chunkpilot.ChunkPilot;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 ChunkMap.updatePlayerPos 和 move（玩家移动触发区块更新）
 * 传递 player 的 blockPos 给 optimizer，让 speed tracker 拿到方块级精度。
 *
 * ============================ 1.20.1 的真实签名 (javap 实证) ============================
 *   private net.minecraft.core.SectionPos ChunkMap.updatePlayerPos(ServerPlayer)
 *   public  void                        ChunkMap.move(ServerPlayer)
 *   final   ServerLevel                 ChunkMap.level      (包级 final, @Shadow 可用)
 *
 * neoforge (1.21.x) 版本给 updatePlayerPos 用的是 `CallbackInfo` —— 在 1.20.1 上**编译期不报错、
 * 运行期才炸** (第一次实测的原始日志):
 *   Mixin apply failed chunkpilot.mixins.json:ChunkMapMixin -> net.minecraft.server.level.ChunkMap:
 *     InvalidInjectionException: Invalid descriptor on ...->@Inject::chunkpilot$onUpdatePlayerPos
 *     (...CallbackInfo;)V! **CallbackInfoReturnable is required**!
 * 根因: 1.20.1 的 updatePlayerPos **有返回值** (SectionPos), 注入 HEAD 必须用
 * `CallbackInfoReturnable<SectionPos>`。已改为 CallbackInfoReturnable, 且**不 setReturnValue**
 * (只读观测, 不改原版返回值)。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private ServerLevel level;

    @Inject(
        method = "updatePlayerPos",
        at = @At("HEAD")
    )
    private void chunkpilot$onUpdatePlayerPos(ServerPlayer player, CallbackInfoReturnable<SectionPos> cir) {
        try {
            var optimizer = ChunkPilot.getInstance().getOptimizer();
            if (optimizer == null) return;
            int worldId = level.hashCode();
            double bx = player.getX();
            double bz = player.getZ();
            optimizer.onPlayerChunkUpdate(player.getUUID(), worldId, bx, bz);
        } catch (Throwable t) {
            // 安全：Mixin 异常不应破坏游戏
        }
    }

    @Inject(
        method = "move",
        at = @At("HEAD")
    )
    private void chunkpilot$onMove(ServerPlayer player, CallbackInfo ci) {
        try {
            var optimizer = ChunkPilot.getInstance().getOptimizer();
            if (optimizer == null) return;
            int worldId = level.hashCode();
            double bx = player.getX();
            double bz = player.getZ();
            optimizer.onPlayerChunkUpdate(player.getUUID(), worldId, bx, bz);
        } catch (Throwable t) {
            // 安全：Mixin 异常不应破坏游戏
        }
    }
}
