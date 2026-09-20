package com.chunkpilot.client;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.config.ClientRenderConfig;
import com.chunkpilot.network.ClientNetworkHandler;
import com.chunkpilot.network.ChunkPriorityHintPacket;

/**
 * 客户端入口 (DESIGN.md §12)
 *
 * 在客户端渲染线程中运行, 每帧更新帧时间采样.
 * 被 Mixin 调用查询是否应该优先处理某区块.
 *
 * 平台无关: Fabric/NeoForge 的客户端入口分别创建实例并注册 Mixin.
 */
public class ChunkPilotClient {

    private static ChunkPilotClient instance;

    private final ClientRenderConfig config;
    private final ChunkRenderScheduler scheduler;
    private volatile boolean enabled = false;

    // 玩家状态 (由 Mixin 或客户端 tick 更新)
    private volatile int playerChunkX;
    private volatile int playerChunkZ;
    private volatile double speedBlocksPerTick;
    private volatile double directionRad;

    // Sodium 检测
    private volatile boolean sodiumDetected = false;

    // 网络处理器 (服务端协同)
    private ClientNetworkHandler networkHandler;

    public ChunkPilotClient(ClientRenderConfig config) {
        this.config = config;
        this.scheduler = new ChunkRenderScheduler(config);
        this.networkHandler = new ClientNetworkHandler(this);
    }

    /** 初始化 (客户端启动时调用) */
    public static void init(ChunkPilotConfig fullConfig) {
        if (instance == null) {
            instance = new ChunkPilotClient(fullConfig.chunkRender);
        }
        instance.enabled = fullConfig.chunkRender.enabled;
    }

    public static ChunkPilotClient getInstance() {
        return instance;
    }

    /**
     * 每帧结束时调用, 记录帧时间.
     *
     * @param frameNanos 本帧耗时 (纳秒)
     */
    public void onFrameEnd(long frameNanos) {
        if (!enabled) return;
        scheduler.recordFrame(frameNanos);
    }

    /**
     * 更新玩家状态 (每客户端 tick 调用).
     *
     * @param chunkX     玩家当前区块 X
     * @param chunkZ     玩家当前区块 Z
     * @param speedBpt   速度 (blocks/tick)
     * @param direction  移动方向 (弧度, atan2(dz,dx))
     */
    public void updatePlayerState(int chunkX, int chunkZ, double speedBpt, double direction) {
        this.playerChunkX = chunkX;
        this.playerChunkZ = chunkZ;
        this.speedBlocksPerTick = speedBpt;
        this.directionRad = direction;
    }

    /**
     * 查询某区块是否应该被优先 meshing.
     * 优先用服务端给的优先级, 没有服务端数据时回退到本地计算.
     * 由 Sodium Mixin (shouldPrioritizeTask) 调用.
     */
    public boolean shouldPrioritizeChunk(int chunkX, int chunkZ) {
        if (!enabled) return false;
        // 优先用服务端数据 (零计算)
        if (networkHandler != null && networkHandler.serverHasCP()) {
            return networkHandler.shouldPrioritizeChunk(chunkX, chunkZ);
        }
        // 回退到本地计算
        return scheduler.shouldPrioritize(
            chunkX, chunkZ,
            playerChunkX, playerChunkZ,
            speedBlocksPerTick, directionRad);
    }

    /**
     * 对一批区块按权重排序并限流.
     * 由 Vanilla Mixin (ChunkRenderDispatcher.updateView) 调用.
     */
    public java.util.List<int[]> sortAndLimitChunks(Iterable<int[]> chunks) {
        if (!enabled) return null;
        return scheduler.sortAndLimit(
            chunks,
            playerChunkX, playerChunkZ,
            speedBlocksPerTick, directionRad);
    }

    /** Sodium 是否被检测到 */
    public boolean isSodiumDetected() {
        return sodiumDetected;
    }

    /** 由平台代码调用, 设置 Sodium 检测结果 */
    public void setSodiumDetected(boolean detected) {
        this.sodiumDetected = detected;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ClientRenderConfig getConfig() {
        return config;
    }

    public ChunkRenderScheduler getScheduler() {
        return scheduler;
    }

    /** 维度切换/传送后重置 */
    public void reset() {
        scheduler.reset();
    }

    /** 获取网络处理器 */
    public ClientNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    /**
     * 收到服务端优先级提示 (由平台网络代码调用).
     */
    public void onPriorityHint(ChunkPriorityHintPacket packet) {
        if (networkHandler != null) {
            networkHandler.onPriorityHint(packet);
        }
    }

    /**
     * 收到服务端 Capability (由平台网络代码调用).
     */
    public void onServerCapability(boolean hasCP, String version, int protocol) {
        if (networkHandler != null) {
            networkHandler.onServerCapability(hasCP, version, protocol);
        }
    }
}