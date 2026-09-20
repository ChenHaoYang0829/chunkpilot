package com.chunkpilot.client;

import com.chunkpilot.config.ClientRenderConfig;
import com.chunkpilot.generation.ChunkWeightCalculator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * v0.8.0 客户端区块渲染调度器 — 统一公式骨架 + 发送器参数集
 *
 * 对称于服务端 ChunkSendScheduler, 复用同一个 ChunkWeightCalculator.
 * v0.8.0 变更:
 *   - 删除 PredictionPath 依赖 (方向直接用传入的 directionRad)
 *   - 统一公式: w = (cos α + C1)^γ / (1 + k·ρ) × (1 + β·v/v_ref)
 *   - v_ref=30.0, C1=0.1 硬编码
 */
public class ChunkRenderScheduler {

    private static final double TICKS_PER_SECOND = 20.0;
    private static final double BLOCKS_PER_CHUNK = 16.0;

    /** 权重阈值: 超过此值的 chunk 被标记为高优先级 */
    private static final double PRIORITY_THRESHOLD = 0.5;

    private final ClientRenderConfig config;
    private final FrameTimeSampler frameSampler;
    private final RenderBudgetCalculator budgetCalculator;

    public ChunkRenderScheduler(ClientRenderConfig config) {
        this.config = config;
        this.frameSampler = new FrameTimeSampler(config.frame_window);
        this.budgetCalculator = new RenderBudgetCalculator(config);
    }

    /**
     * 判断某区块是否应该被优先处理 (高优先级).
     */
    public boolean shouldPrioritize(
            int chunkX, int chunkZ,
            int playerChunkX, int playerChunkZ,
            double speedBlocksPerTick, double directionRad) {

        if (!config.enabled) return false;

        if (!ChunkWeightCalculator.shouldActivate(speedBlocksPerTick, config.v_min)) {
            return false;
        }

        double vMs = speedBlocksPerTick * TICKS_PER_SECOND;
        double lookAheadChunks = vMs * config.lookAheadSeconds / BLOCKS_PER_CHUNK;
        if (lookAheadChunks <= 0) return false;

        double dirX = Math.cos(directionRad);
        double dirZ = Math.sin(directionRad);

        double dx = chunkX - playerChunkX;
        double dz = chunkZ - playerChunkZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > lookAheadChunks) return false;

        // v0.9.0: D = lookAheadChunks (客户端不知道服务端 viewDistance, 用预测长度近似)
        double D = lookAheadChunks;
        // v0.9.0 投影版权重公式: w = [v·(c-p)/|c-p|] / (1+k·ρ), 负权重取倒数
        double w = ChunkWeightCalculator.computeWeightProjection(
            vMs, dirX, dirZ,
            chunkX + 0.5, chunkZ + 0.5,
            playerChunkX, playerChunkZ,
            D, config.k);

        return w > PRIORITY_THRESHOLD;
    }

    /**
     * 对一批待 meshing 的区块按权重排序, 返回前 N 个.
     */
    public List<int[]> sortAndLimit(
            Iterable<int[]> chunkPositions,
            int playerChunkX, int playerChunkZ,
            double speedBlocksPerTick, double directionRad) {

        if (!config.enabled) return null;

        if (!ChunkWeightCalculator.shouldActivate(speedBlocksPerTick, config.v_min)) {
            return null;
        }

        int budget = budgetCalculator.updateBudget(frameSampler);
        if (budget <= 0) return null;

        double vMs = speedBlocksPerTick * TICKS_PER_SECOND;
        double lookAheadChunks = vMs * config.lookAheadSeconds / BLOCKS_PER_CHUNK;
        if (lookAheadChunks <= 0) return null;

        double dirX = Math.cos(directionRad);
        double dirZ = Math.sin(directionRad);
        double D = lookAheadChunks;

        List<WeightedChunk> weighted = new ArrayList<>();
        for (int[] pos : chunkPositions) {
            int cx = pos[0], cz = pos[1];
            double dx = cx - playerChunkX;
            double dz = cz - playerChunkZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > lookAheadChunks) continue;

            // v0.9.0 投影版权重公式: w = [v·(c-p)/|c-p|] / (1+k·ρ), 负权重取倒数
            double w = ChunkWeightCalculator.computeWeightProjection(
                vMs, dirX, dirZ,
                cx + 0.5, cz + 0.5,
                playerChunkX, playerChunkZ,
                D, config.k);

            if (w <= 0.0) continue;

            weighted.add(new WeightedChunk(cx, cz, w));
        }

        if (weighted.isEmpty()) return null;

        weighted.sort(Comparator.comparingDouble((WeightedChunk wc) -> wc.weight).reversed());

        List<int[]> result = new ArrayList<>(Math.min(budget, weighted.size()));
        for (int i = 0; i < Math.min(budget, weighted.size()); i++) {
            WeightedChunk wc = weighted.get(i);
            result.add(new int[]{wc.chunkX, wc.chunkZ});
        }
        return result;
    }

    public void recordFrame(long frameNanos) {
        frameSampler.recordFrame(frameNanos);
    }

    public FrameTimeSampler getFrameSampler() {
        return frameSampler;
    }

    public RenderBudgetCalculator getBudgetCalculator() {
        return budgetCalculator;
    }

    public void reset() {
        frameSampler.reset();
        budgetCalculator.reset();
    }

    private record WeightedChunk(int chunkX, int chunkZ, double weight) {}
}
