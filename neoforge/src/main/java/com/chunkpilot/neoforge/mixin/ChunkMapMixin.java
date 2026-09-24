package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 ChunkMap.updatePlayerPos 和 move（玩家移动触发区块更新）
 * 传递 player 的 blockPos 给 optimizer，让 speed tracker 拿到方块级精度
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private ServerLevel level;

    @Inject(
        method = "updatePlayerPos",
        at = @At("HEAD"), require = 0, expect = 0)
    private void chunkpilot$onUpdatePlayerPos(ServerPlayer player, CallbackInfo ci) {
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
        at = @At("HEAD"), require = 0, expect = 0)
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
