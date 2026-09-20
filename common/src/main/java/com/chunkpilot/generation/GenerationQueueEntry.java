package com.chunkpilot.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v0.3.0 生成器队列条目
 * 优先级队列的一个元素，按 weight 降序排序
 *
 * 字段:
 *  - chunkPos: 目标 chunk 的 ChunkPos.asLong() 编码
 *  - weight: 跨玩家权重合并后的最终权重
 *  - expiresAtTick: server tick 截止时间 (TTL 过期)
 *  - contributingPlayers: 贡献过权重的玩家 UUID (用于审计/调试)
 */
public class GenerationQueueEntry implements Comparable<GenerationQueueEntry> {

    /** ChunkPos.asLong() 编码: ((long)x & 0x3FFFFFF) << 38 | ((long)z & 0x3FFFFFF) */
    public long chunkPos;

    /** 权重: 跨玩家合并后, 越大越优先 */
    public double weight;

    /** 过期 tick: 超过此 tick 视为失效 */
    public long expiresAtTick;

    /** 贡献过权重的玩家列表 (用于审计/调试) */
    public List<UUID> contributingPlayers;

    public GenerationQueueEntry(long chunkPos, double weight, long expiresAtTick) {
        this.chunkPos = chunkPos;
        this.weight = weight;
        this.expiresAtTick = expiresAtTick;
        this.contributingPlayers = new ArrayList<>();
    }

    public GenerationQueueEntry(long chunkPos, double weight, long expiresAtTick, List<UUID> contributingPlayers) {
        this.chunkPos = chunkPos;
        this.weight = weight;
        this.expiresAtTick = expiresAtTick;
        this.contributingPlayers = contributingPlayers;
    }

    /**
     * 降序排序: 权重大的在前
     * PriorityQueue 默认小顶堆, 直接插入即可
     */
    @Override
    public int compareTo(GenerationQueueEntry other) {
        return Double.compare(other.weight, this.weight);
    }
}
