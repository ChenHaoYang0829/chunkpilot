package com.chunkpilot.neoforge.mixin;
import com.chunkpilot.neoforge.util.PlayerChunkSendHolder;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkSendConfig;
import com.chunkpilot.core.SpeedTracker;
import com.chunkpilot.send.ChunkSendScheduler;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.3.0 拦截 PlayerChunkSender.collectChunksToSend
 *
 * vanilla 逻辑: pendingChunks → 按距离平方排序 → 返回 List<LevelChunk>
 * ChunkPilot 逻辑: pendingChunks → 按权重排序 (复用 GenerationScheduler 权重) → 返回 List<LevelChunk>
 *
 * 实现方式:
 *   - @Inject 到 collectChunksToSend 的 HEAD, @Cancel
 *   - 取 pendingChunks, 用 ChunkSendScheduler 排序
 *   - 按排序后的顺序从 ChunkMap 取 LevelChunk, 构造返回 List
 *   - 从 pendingChunks 中移除已收集的
 *   - 如果 ChunkSendScheduler 返回 null (未启用/低速), 不 cancel → 走 vanilla
 *
 * 注意: collectChunksToSend 是 private 方法, Mixin 可以拦截.
 *       pendingChunks 也是 private, 用 @Shadow 暴露.
 */
// 2026-09-25 (二阶段整改项 1, 按主代理硬要求): 本模块的 mixin 注入一律 `require = 0, expect = 0`。
//   理由: 这些类用的是**硬编码 descriptor / 可读方法名**, 版本一漂移就会在**启动期**报
//   InvalidInjectionException → MixinApplyError → ModLoadingException (1.21.11 的
//   ConnectionNetTrackerMixin 就是这么崩的)。软失败只会丢一项增强, 不会让服务端起不来。
//   功能是否真的生效由**服务端探针 + 飞行跑分**验收, 不靠"启动成功"。
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {

    @Shadow
    private it.unimi.dsi.fastutil.longs.LongSet pendingChunks;

    @Shadow
    private float batchQuota;

    /**
     * 拦截 collectChunksToSend, 替换排序逻辑.
     *
     * 签名: private List<LevelChunk> collectChunksToSend(ChunkMap chunkMap, ChunkPos playerChunkPos)
     */
    @Inject(
        method = "collectChunksToSend",
        at = @At("HEAD"),
        cancellable = true,
        require = 0, expect = 0
    )
    private void chunkpilot$onCollectChunksToSend(ChunkMap chunkMap, ChunkPos playerChunkPos,
                                                   CallbackInfoReturnable<List<LevelChunk>> cir) {
        try {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null) return; // 未初始化 → 走 vanilla

            ChunkSendConfig sendConfig = cp.getConfig().chunkSend;
            if (!sendConfig.enabled) return; // 未启用 → 走 vanilla

            // C2ME 兼容: 如果 C2ME 存在, 不拦截发送排序 (让 C2ME 的发送逻辑走)
            // CP 只负责生成交给 C2ME, 发送交给 C2ME
            if (cp.getPlatform().isModLoaded("c2me")) return;

            ChunkSendScheduler scheduler = cp.getChunkSendScheduler();
            if (scheduler == null) return;

            // 取当前玩家
            // collectChunksToSend 是由 sendNextChunks(player) 调用的,
            // 但此处无法直接拿到 player 对象. 通过 ChunkMap 找在线玩家中最接近的.
            // 更好的方案: 拦截 sendNextChunks 传入 player, 存到 ThreadLocal.
            // 但为了简化, 这里从 ChunkMap 的 tracked players 中找.
            //
            // 实际上 collectChunksToSend 的调用者是 sendNextChunks(ServerPlayer),
            // 我们可以拦截 sendNextChunks 来保存当前 player 到 ThreadLocal.

            // 使用 ThreadLocal 获取当前 player
            ServerPlayer player = PlayerChunkSendHolder.currentPlayer.get();
            if (player == null) return; // 无法确定玩家 → 走 vanilla

            SpeedTracker speedTracker = cp.getOptimizer().getSpeedTracker();
            int playerChunkX = playerChunkPos.x();
            int playerChunkZ = playerChunkPos.z();

            // 收集 pendingChunks 的 long 值
            if (pendingChunks.isEmpty()) return;

            List<Long> pendingList = new ArrayList<>();
            for (long chunkLong : pendingChunks) {
                pendingList.add(chunkLong);
            }

            // 用 ChunkSendScheduler 排序 (v0.5.0: 需要 viewDistance 算 D)
            int viewDistance = cp.getPlatform().getServerRenderDistance();
            List<Long> sorted = scheduler.sortPendingChunks(
                pendingList, playerChunkX, playerChunkZ,
                speedTracker, player.getUUID(), sendConfig, viewDistance);

            if (sorted == null || sorted.isEmpty()) return; // 排序器不干预 → 走 vanilla

            // 按排序后的顺序从 ChunkMap 取 LevelChunk
            // vanilla 用 chunkMap.getChunkToSend(long) 取 LevelChunk
            List<LevelChunk> result = new ArrayList<>();
            int limit = Math.min(sorted.size(), (int) Math.floor(batchQuota));

            for (int i = 0; i < limit && i < sorted.size(); i++) {
                long chunkLong = sorted.get(i);
                // 取 LevelChunk: 用 ChunkMap 的 getChunkToSend 方法
                LevelChunk chunk = getChunkToSend(chunkMap, chunkLong);
                if (chunk != null) {
                    result.add(chunk);
                    pendingChunks.remove(chunkLong);
                }
            }

            if (!result.isEmpty()) {
                cir.setReturnValue(result);
            }
            // 如果 result 为空 (chunk 还没生成好), 不 cancel → 走 vanilla
        } catch (Throwable t) {
            // 任何异常不干扰 vanilla
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").warn(
                "[ChunkPilot] PlayerChunkSenderMixin error: {}", t.toString());
        }
    }

    /**
     * 反射调用 ChunkMap.getChunkToSend(long) 获取 LevelChunk.
     * 该方法在 vanilla 中是 package-private, 需要反射或 @Shadow.
     */
    private LevelChunk getChunkToSend(ChunkMap chunkMap, long chunkLong) {
        try {
            // 尝试反射调用 getChunkToSend
            java.lang.reflect.Method m = chunkMap.getClass().getDeclaredMethod("getChunkToSend", long.class);
            m.setAccessible(true);
            Object result = m.invoke(chunkMap, chunkLong);
            return result instanceof LevelChunk lc ? lc : null;
        } catch (Throwable t) {
            return null;
        }
    }
}