package com.chunkpilot.network;

import java.util.List;

/**
 * 区块优先级提示包: 服务端 → 客户端, Play 阶段, 每 10 tick (0.5s)
 *
 * 服务端为该玩家计算的原始权重 top-N 列表.
 * 不含结构偏移、不含多玩家合并 — 纯个人权重.
 *
 * 客户端收到后直接用于渲染排序, 跳过本地 ChunkWeightCalculator 计算.
 */
public final class ChunkPriorityHintPacket {
    /** 玩家当前区块 X (服务端视角) */
    public final int playerChunkX;
    /** 玩家当前区块 Z (服务端视角) */
    public final int playerChunkZ;
    /** 玩家速度 (blocks/tick, 服务端视角) */
    public final float speedBlocksPerTick;
    /** 玩家移动方向 (弧度, atan2(dz,dx)) */
    public final float directionRad;
    /** 优先级列表 (按权重降序), 每条 = (chunkX, chunkZ, weight) */
    public final List<ChunkPriority> priorities;

    public ChunkPriorityHintPacket(int playerChunkX, int playerChunkZ,
                                    float speed, float direction,
                                    List<ChunkPriority> priorities) {
        this.playerChunkX = playerChunkX;
        this.playerChunkZ = playerChunkZ;
        this.speedBlocksPerTick = speed;
        this.directionRad = direction;
        this.priorities = priorities;
    }

    /** 单条优先级记录 */
    public record ChunkPriority(int chunkX, int chunkZ, float weight) {}

    /** 最大发送条数 (避免包过大) */
    public static final int MAX_PRIORITIES = 32;
}