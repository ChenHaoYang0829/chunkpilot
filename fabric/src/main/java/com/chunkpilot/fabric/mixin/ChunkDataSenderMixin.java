package com.chunkpilot.fabric.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkSendConfig;
import com.chunkpilot.core.SpeedTracker;
import com.chunkpilot.send.ChunkSendScheduler;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * fabric 1.21.3 发送器 Mixin — 拦截 PlayerChunkSender.collectChunksToSend, 用投影公式排序.
 *
 * 1.21.3 official 映射下类名/方法名/字段名都是可读的 (Mojang official 映射保留可读名):
 *   class net.minecraft.server.network.PlayerChunkSender:
 *     field pendingChunks: LongSet            (待发送区块)
 *     field batchQuota: float                 (本批配额)
 *     method collectChunksToSend(ChunkMap, ChunkPos) -> List<LevelChunk>
 *   class net.minecraft.server.level.ChunkMap:
 *     method getChunkToSend(long) -> LevelChunk
 *
 * 关键: @Inject 参数类型必须精确匹配 (ChunkMap, ChunkPos), 不能用 Object 代替,
 *       否则 Mixin 注入失败 (Invalid descriptor).
 */
@Mixin(PlayerChunkSender.class)
public abstract class ChunkDataSenderMixin {

    @Shadow
    private LongSet pendingChunks;

    @Shadow
    private float batchQuota;

    @Inject(method = "collectChunksToSend", at = @At("HEAD"), cancellable = true)
    private void chunkpilot$onMakeBatch(ChunkMap chunkMap, ChunkPos playerChunkPos,
                                        CallbackInfoReturnable<List<LevelChunk>> cir) {
        try {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null) return;
            ChunkSendConfig sendConfig = cp.getConfig().chunkSend;
            if (!sendConfig.enabled) return;

            ChunkSendScheduler scheduler = cp.getChunkSendScheduler();
            if (scheduler == null) return;

            if (pendingChunks == null || pendingChunks.isEmpty()) return;

            // 收集 pendingChunks 的 long 值
            List<Long> pendingList = new ArrayList<>();
            for (long chunkLong : pendingChunks) {
                pendingList.add(chunkLong);
            }
            if (pendingList.isEmpty()) return;

            // 玩家 chunk 坐标
            int playerChunkX = playerChunkPos.x;
            int playerChunkZ = playerChunkPos.z;

            // 用 ChunkSendScheduler 排序 (投影公式)
            SpeedTracker speedTracker = cp.getOptimizer().getSpeedTracker();
            // 当前玩家由 PlayerChunkSenderSendMixin 写入 ThreadLocal (collectChunksToSend
            // 由 sendNextChunks(player) 调用, 期间 ThreadLocal 有效). 拿不到则回退 vanilla,
            // 不再用反射偷 speedTracker.buffers 的第一个玩家 (多人时排序会用到错误玩家).
            ServerPlayer player = com.chunkpilot.fabric.util.PlayerChunkSendHolder.currentPlayer.get();
            if (player == null) return;
            int viewDistance = cp.getPlatform().getServerRenderDistance();
            List<Long> sorted = scheduler.sortPendingChunks(
                pendingList, playerChunkX, playerChunkZ,
                speedTracker, player.getUUID(), sendConfig, viewDistance);
            if (sorted == null || sorted.isEmpty()) return;

            // 按排序后的顺序从 chunkMap 取 LevelChunk
            List<LevelChunk> result = new ArrayList<>();
            // v0.11.0: 尊重 vanilla batchQuota (客户端 ACK 流控), 只重排不改批量大小.
            //   之前 max(floor(batchQuota),4) 会无视客户端 ACK 降速请求, 且让 batchQuota 变负
            //   制造"发4停3"脉冲. 现在只按排序顺序取 floor(batchQuota) 块.
            int limit = Math.min(sorted.size(), (int) Math.floor(batchQuota));
            for (int i = 0; i < limit && i < sorted.size(); i++) {
                long chunkLong = sorted.get(i);
                LevelChunk chunk = chunkMap.getChunkToSend(chunkLong);
                if (chunk != null) {
                    result.add(chunk);
                    pendingChunks.remove(chunkLong);
                }
            }
            if (!result.isEmpty()) {
                // 记录发送顺序 (投影公式排序结果) — 仅调试时输出, 避免每批发送刷屏
                if (org.slf4j.LoggerFactory.getLogger("ChunkPilot").isDebugEnabled()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[SendOrder] player_chunk=").append(playerChunkX).append(",").append(playerChunkZ);
                    sb.append(" dir=").append(speedTracker.getDirection(player.getUUID()));
                    sb.append(" v=").append(String.format("%.2f", speedTracker.getSpeed(player.getUUID())));
                    sb.append(" sent=");
                    for (int i = 0; i < result.size(); i++) {
                        long cl = result.get(i).getPos().toLong();
                        int cx = net.minecraft.world.level.ChunkPos.getX(cl);
                        int cz = net.minecraft.world.level.ChunkPos.getZ(cl);
                        if (i > 0) sb.append(",");
                        sb.append(cx).append("/").append(cz);
                    }
                    org.slf4j.LoggerFactory.getLogger("ChunkPilot").debug(sb.toString());
                }
                cir.setReturnValue(result);
            }
        } catch (Throwable t) {
            // 任何异常不干扰 vanilla
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").warn(
                "[ChunkPilot] ChunkDataSenderMixin error: {}", t.toString());
        }
    }
}
