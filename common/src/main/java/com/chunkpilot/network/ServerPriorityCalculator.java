package com.chunkpilot.network;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.SpeedTracker;
import com.chunkpilot.generation.ChunkWeightCalculator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * v0.8.0 服务端优先级计算器 — 统一公式骨架 + 发送器参数集
 *
 * 为单个玩家计算原始权重 top-N, 发给客户端.
 * v0.8.0 变更:
 *   - 删除 PredictionPath 依赖 (方向直接用 SpeedTracker.getDirection)
 *   - 统一公式: w = (cos α + C1)^γ / (1 + k·ρ) × (1 + β·v/v_ref)
 *   - v_ref=30.0, C1=0.1 硬编码
 */
public class ServerPriorityCalculator {

    private static final double TICKS_PER_SECOND = 20.0;
    private static final double BLOCKS_PER_CHUNK = 16.0;

    private final ChunkPilotConfig config;

    public ServerPriorityCalculator(ChunkPilotConfig config) {
        this.config = config;
    }

    public ChunkPriorityHintPacket computeHint(
            UUID playerId,
            int playerChunkX, int playerChunkZ,
            SpeedTracker speedTracker,
            Iterable<int[]> nearbyChunks,
            int viewDistance) {

        var renderConfig = config.chunkRender;
        if (!renderConfig.enabled) return null;

        double vBlocksPerTick = speedTracker.getSpeed(playerId);
        if (!ChunkWeightCalculator.shouldActivate(vBlocksPerTick, renderConfig.v_min)) {
            return null;
        }

        // v0.8.0: 速度 (m/s) + 方向 (弧度)
        double vMs = vBlocksPerTick * TICKS_PER_SECOND;
        double directionRad = speedTracker.getDirection(playerId);
        double dirX = Math.cos(directionRad);
        double dirZ = Math.sin(directionRad);

        double lookAheadChunks = vMs * renderConfig.lookAheadSeconds / BLOCKS_PER_CHUNK;
        double D = ChunkWeightCalculator.computeEffectiveRadius(viewDistance, lookAheadChunks);
        if (D <= 0.0) return null;

        List<ChunkPriorityHintPacket.ChunkPriority> priorities = new ArrayList<>();
        for (int[] pos : nearbyChunks) {
            int cx = pos[0], cz = pos[1];

            double d = Math.sqrt(
                (double)(cx - playerChunkX) * (cx - playerChunkX) +
                (double)(cz - playerChunkZ) * (cz - playerChunkZ));

            // v0.9.0 投影版权重公式: w = [v·(c-p)/|c-p|] / (1+k·ρ), 负权重取倒数
            double w = ChunkWeightCalculator.computeWeightProjection(
                vMs, dirX, dirZ,
                cx + 0.5, cz + 0.5,
                playerChunkX, playerChunkZ,
                D, renderConfig.k);

            if (w > 0.01) {
                priorities.add(new ChunkPriorityHintPacket.ChunkPriority(cx, cz, (float) w));
            }
        }

        if (priorities.isEmpty()) return null;

        priorities.sort(Comparator.comparingDouble(
            (ChunkPriorityHintPacket.ChunkPriority p) -> p.weight()).reversed());

        int maxN = Math.min(ChunkPriorityHintPacket.MAX_PRIORITIES, priorities.size());
        List<ChunkPriorityHintPacket.ChunkPriority> topN = priorities.subList(0, maxN);

        return new ChunkPriorityHintPacket(
            playerChunkX, playerChunkZ,
            (float) vBlocksPerTick, (float) directionRad,
            new ArrayList<>(topN));
    }
}
