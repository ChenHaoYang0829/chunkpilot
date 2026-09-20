package com.chunkpilot.network;

/**
 * 客户端配置覆写包: 客户端 → 服务端, Play 阶段
 *
 * 客户端告诉服务端自己的渲染偏好,
 * 服务端据此调整该玩家的参数 (如降低发送速率).
 *
 * 只覆写客户端独占配置, 不影响权重计算参数.
 */
public final class ClientConfigOverridePacket {
    /** 客户端目标 FPS (0 = 未设置, 用服务端默认) */
    public final int targetFps;
    /** 客户端 meshing 队列大小 (0 = 未报告) */
    public final int meshingQueueSize;
    /** 客户端平均帧耗时 (毫秒, 0 = 未报告) */
    public final float avgFrameTimeMs;
    /** 客户端是否启用了 CP 渲染优化 */
    public final boolean clientRenderEnabled;

    public ClientConfigOverridePacket(int targetFps, int meshingQueueSize,
                                       float avgFrameTimeMs, boolean clientRenderEnabled) {
        this.targetFps = targetFps;
        this.meshingQueueSize = meshingQueueSize;
        this.avgFrameTimeMs = avgFrameTimeMs;
        this.clientRenderEnabled = clientRenderEnabled;
    }
}