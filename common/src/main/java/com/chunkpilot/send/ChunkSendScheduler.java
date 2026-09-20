package com.chunkpilot.send;

import com.chunkpilot.config.ChunkSendConfig;
import com.chunkpilot.core.SpeedTracker;
import com.chunkpilot.generation.ChunkWeightCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * v0.8.0 区块发送顺序调度器 — 统一公式骨架 + 发送器参数集
 *
 * 2026-08-10 定稿 (deepseek-v4-pro 评审 + 用户拍板):
 *   - 只对 vanilla pendingChunks 做权重排序, 不改变发送总量
 *   - 统一公式: w = (cos α + C1)^γ / (1 + k·ρ) × (1 + β·v/v_ref)
 *   - v_ref=30.0, C1=0.1 硬编码; γ=4.0, k=0.15, β=1.0 可调
 *   - 删除 PredictionPath 依赖 (方向直接用 SpeedTracker.getDirection)
 *
 * 设计原则:
 *   - batchQuota 不变 (和 vanilla 一样)
 *   - pendingChunks 内容不变 (还是 viewDistance 内已加载的)
 *   - 只改变 "先发哪个" — 前方权重高先发, 侧方/后方延后
 *
 * 退化行为:
 *   - enabled=false → 返回 null, Mixin 回退到 vanilla 排序
 *   - 速度 < v_min → 返回 null, 回退到 vanilla 距离排序
 */
public class ChunkSendScheduler {
    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotSend");

    private static final double TICKS_PER_SECOND = 20.0;
    private static final double BLOCKS_PER_CHUNK = 16.0;

    public ChunkSendScheduler() {}

    /**
     * 对待发送的 chunk 集合按权重排序.
     *
     * @param pendingChunkLongs  原版 pendingChunks 的 long 集合 (ChunkPos.toLong)
     * @param playerChunkX       玩家当前 chunk X
     * @param playerChunkZ       玩家当前 chunk Z
     * @param speedTracker       速度追踪器
     * @param playerId           玩家 UUID
     * @param config             发送配置
     * @param viewDistance       服务器视距 (chunks), 用于算 D
     * @return 按权重降序排列的 chunk long 列表, 或 null 表示不干预 (回退 vanilla)
     */
    public List<Long> sortPendingChunks(
            Iterable<Long> pendingChunkLongs,
            int playerChunkX, int playerChunkZ,
            SpeedTracker speedTracker, UUID playerId,
            ChunkSendConfig config, int viewDistance) {

        if (!config.enabled) return null;

        double vBlocksPerTick = speedTracker.getSpeed(playerId);
        if (!ChunkWeightCalculator.shouldActivate(vBlocksPerTick, config.v_min)) {
            return null;
        }

        // 调试日志: 确认发送器被调用 (仅 debug, 避免每玩家每 tick 刷屏)
        if (LOG.isDebugEnabled()) {
            LOG.debug("[Send] sortPendingChunks called: player={} v={} b/t pending={} enabled={}",
                playerId.toString().substring(0, 8), String.format("%.2f", vBlocksPerTick),
                countPending(pendingChunkLongs), config.enabled);
        }

        // v0.8.0: 速度 (m/s) + 方向 (弧度), 不再依赖 PredictionPath
        double vMs = vBlocksPerTick * TICKS_PER_SECOND;  // blocks/tick × 20 = m/s
        double directionRad = speedTracker.getDirection(playerId);
        double dirX = Math.cos(directionRad);
        double dirZ = Math.sin(directionRad);

        // v0.8.0: D = min(viewDistance, lookAhead_chunks)
        double lookAheadChunks = vMs * config.lookAheadSeconds / BLOCKS_PER_CHUNK;
        double D = ChunkWeightCalculator.computeEffectiveRadius(viewDistance, lookAheadChunks);
        if (D <= 0.0) return null;

        double playerX = playerChunkX;
        double playerZ = playerChunkZ;

        // v0.10.6 治本: 玩家周围核心区 (coreRadius 内, 无论方向) 始终最高优先发送.
        //   根因: 原实现只发前方 (w<=0 丢弃侧/后方), 导致玩家周围(含后方/侧方)区块不被接收,
        //   小地图中心圆出现黑像素. 核心区保证玩家周围区块被接收.
        double coreRadius = 3.0; // chunks, 玩家周围 (v0.10.9: 实测最佳平衡)
        // v0.10.8: 侧/后方区块也在视距内按低优先发送 (不再丢弃), 保证玩家周围区域被接收 (vanilla 行为)
        double sendSideRadius = viewDistance;
        List<WeightedChunk> weighted = new ArrayList<>();
        for (Long chunkLong : pendingChunkLongs) {
            int cx = unpackX(chunkLong);
            int cz = unpackZ(chunkLong);

            double d = Math.sqrt(
                (double)(cx - playerChunkX) * (cx - playerChunkX) +
                (double)(cz - playerChunkZ) * (cz - playerChunkZ));

            if (d <= coreRadius) {
                // 玩家周围核心区: 最高优先级 (权重极大, 按距离微调, 近者先发)
                weighted.add(new WeightedChunk(chunkLong, 1e9 - d));
                continue;
            }

            // v0.9.0 投影版权重公式: w = [v·(c-p)/|c-p|] / (1+k·ρ)
            // 负权重取倒数（方案A），替换原 cosα 公式
            double w = ChunkWeightCalculator.computeWeightProjection(
                vMs, dirX, dirZ,
                cx + 0.5, cz + 0.5,
                playerX, playerZ,
                D, config.k);

            if (w <= 0.0) {
                // v0.10.8 治本: 不再丢弃侧/后方 (原实现 w<=0 全丢, 玩家周围/后方区块从不接收
                // → 小地图中心圆黑像素). 改为按距离给一个小正权重, 让侧/后方区块也进入发送队列
                // (低优先, 前方发完后才发), 保证玩家周围区域被接收. 权重随距离衰减, 近处侧/后优先.
                if (d <= sendSideRadius) {
                    weighted.add(new WeightedChunk(chunkLong, 0.000001 / (1.0 + d)));
                }
                continue;
            }

            weighted.add(new WeightedChunk(chunkLong, w));
        }

        if (weighted.isEmpty()) return null;

        // 按权重降序排序
        weighted.sort(Comparator.comparingDouble((WeightedChunk wc) -> wc.weight).reversed());

        List<Long> result = new ArrayList<>(weighted.size());
        for (WeightedChunk wc : weighted) {
            result.add(wc.chunkLong);
        }
        return result;
    }

    private static int unpackX(long packed) {
        return (int) packed;
    }

    private static int unpackZ(long packed) {
        return (int) (packed >> 32);
    }

    private static int countPending(Iterable<Long> longs) {
        int n = 0;
        for (Long ignored : longs) n++;
        return n;
    }

    private record WeightedChunk(long chunkLong, double weight) {}
}
