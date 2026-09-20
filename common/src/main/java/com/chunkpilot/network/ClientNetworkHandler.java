package com.chunkpilot.network;

import com.chunkpilot.client.ChunkPilotClient;

import java.util.List;

/**
 * 客户端网络接收处理器 (DESIGN.md §12.3)
 *
 * 接收服务端发来的优先级提示, 直接用于渲染排序.
 * 客户端收到后跳过本地 ChunkWeightCalculator 计算, 零计算直接用.
 */
public class ClientNetworkHandler {

    private final ChunkPilotClient client;
    private volatile boolean serverHasCP = false;
    private volatile String serverVersion = "";
    private volatile int serverProtocol = 0;

    /** 服务端发来的最新优先级 (缓存, 供 Mixin 查询) */
    private volatile ChunkPriorityHintPacket latestHint = null;

    public ClientNetworkHandler(ChunkPilotClient client) {
        this.client = client;
    }

    /**
     * 收到服务端 CapabilityPacket (Configuration 阶段)
     */
    public void onServerCapability(boolean hasCP, String version, int protocol) {
        this.serverHasCP = hasCP;
        this.serverVersion = version;
        this.serverProtocol = protocol;
        System.out.println("[ChunkPilot] Server capability: hasCP=" + hasCP +
            ", version=" + version + ", protocol=" + protocol);
    }

    /**
     * 收到服务端 ChunkPriorityHintPacket (Play 阶段, 每 0.5s)
     *
     * 直接缓存, 供 shouldPrioritizeChunk 查询.
     * 如果服务端给了优先级, 客户端就不用自己算了.
     */
    public void onPriorityHint(ChunkPriorityHintPacket packet) {
        this.latestHint = packet;
    }

    /**
     * 查询某区块是否应该被优先 meshing.
     * 优先用服务端给的优先级, 没有服务端数据时回退到本地计算.
     *
     * 由 Sodium/Vanilla Mixin 调用.
     */
    public boolean shouldPrioritizeChunk(int chunkX, int chunkZ) {
        // 优先用服务端数据; latestHint 在网络线程更新, 这里一次性读到局部变量,
        // 避免多次独立读 volatile 字段在不同包之间撕裂.
        ChunkPriorityHintPacket hint = latestHint;
        if (hint != null && serverHasCP) {
            // 先检查速度阈值 (服务端已经在发包前检查过了, 但客户端再兜底)
            if (hint.speedBlocksPerTick < client.getConfig().v_min) {
                return false;
            }

            // 在优先级列表中查找
            for (var p : hint.priorities) {
                if (p.chunkX() == chunkX && p.chunkZ() == chunkZ) {
                    return p.weight() > 0.5f; // v0.5.0: 新公式权重量级 ~0-2.5, 阈值 0.5
                }
            }
            return false; // 不在 top-N 中, 不优先
        }

        // 回退到本地计算
        return client.shouldPrioritizeChunk(chunkX, chunkZ);
    }

    /**
     * 获取服务端最新的玩家状态 (位置/速度/方向).
     * 用于客户端同步显示, 如果服务端数据比本地新就用服务端的.
     */
    public ChunkPriorityHintPacket getLatestHint() {
        return latestHint;
    }

    /** 服务端是否有 CP */
    public boolean serverHasCP() {
        return serverHasCP;
    }

    /** 构建客户端配置覆写包 */
    public static ClientConfigOverridePacket buildOverridePacket(ChunkPilotClient client) {
        var sampler = client.getScheduler().getFrameSampler();
        float avgFrameTime = sampler.hasEnoughData() ? (float) sampler.getEmaFrameTimeMs() : 0f;
        int targetFps = client.getConfig().target_fps;
        boolean enabled = client.isEnabled();

        return new ClientConfigOverridePacket(
            targetFps,
            0, // meshingQueueSize: 客户端暂时无法获取, 传 0
            avgFrameTime,
            enabled
        );
    }
}