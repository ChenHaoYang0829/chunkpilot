package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.neoforge.ChunkPilotNeoForge;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 ServerPlayer.teleportTo(double, double, double)
 *
 * RCON tp 命令走 teleportTo → moveTo → setPos，
 * 不经过 ChunkMap.move，所以 ChunkMapMixin 拦截不到。
 * 在这里拦截 teleportTo，手动更新 speedTracker。
 */
// 2026-09-25 (二阶段整改项 1, 按主代理硬要求): 本模块的 mixin 注入一律 `require = 0, expect = 0`。
//   理由: 这些类用的是**硬编码 descriptor / 可读方法名**, 版本一漂移就会在**启动期**报
//   InvalidInjectionException → MixinApplyError → ModLoadingException (1.21.11 的
//   ConnectionNetTrackerMixin 就是这么崩的)。软失败只会丢一项增强, 不会让服务端起不来。
//   功能是否真的生效由**服务端探针 + 飞行跑分**验收, 不靠"启动成功"。
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(
        method = "teleportTo(DDD)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void chunkpilot$onTeleportTo(double x, double y, double z, CallbackInfo ci) {
        try {
            var optimizer = ChunkPilot.getInstance().getOptimizer();
            if (optimizer == null) return;

            ServerPlayer self = (ServerPlayer) (Object) this;
            // 1.21.11: ServerPlayer.serverLevel() 已删除, level() 直接返回 ServerLevel
            int worldId = self.level().hashCode();
            optimizer.onPlayerChunkUpdate(self.getUUID(), worldId, x, z);

            ChunkPilotNeoForge.LOGGER.debug("[ChunkPilot] teleportTo: {} → ({}, {}, {}) tick={}",
                self.getName().getString(), x, y, z, optimizer.getCurrentTick());
        } catch (Throwable t) {
            // 安全：Mixin 异常不应破坏游戏
        }
    }
}