package com.chunkpilot.platform;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 平台抽象层接口
 *
 * Fabric 和 NeoForge 各自直接实现本接口（{@code FabricPlatform} / {@code NeoForgePlatform}），
 * 由各平台入口显式构造后注入 ChunkPilot：
 *   - Fabric:  {@code ChunkPilotFabric.onInitialize()} → {@code ServerInitializer.initialize()}
 *              → {@code new FabricPlatform()} → {@code new ChunkPilot(platform)}
 *   - NeoForge: {@code ChunkPilotNeoForge} 构造器 → {@code new NeoForgePlatform()}
 *              → {@code new ChunkPilot(platform)}
 *
 * 注意: **没有**使用 Architectury 的 @ExpectPlatform（全仓库 0 处使用），
 *       本注释历史上曾错误地如此描述。
 */
public interface PlatformAbstraction {
    
    /**
     * 检查某个 mod 是否已加载
     */
    boolean isModLoaded(String modId);
    
    /**
     * 获取当前平台名称 (fabric / neoforge)
     */
    String getPlatformName();
    
    /**
     * 获取服务端视距 (render distance)
     */
    int getServerRenderDistance();
    
    /**
     * 获取当前 MSPT (milliseconds per tick)
     */
    double getCurrentMspt();
    
    /**
     * 获取当前内存使用百分比
     */
    double getMemoryUsagePercent();
    
    // ========== Chunk Ticket 操作（Mixin 拦截后由平台实现调用） ==========
    
    /**
     * 获取玩家所在区块坐标
     * @param playerId 玩家 UUID
     * @return [chunkX, chunkZ]，玩家不在世界则返回 null
     */
    int[] getPlayerChunkPos(UUID playerId);
    
    /**
     * 获取玩家移动方向（弧度）
     * @param playerId 玩家 UUID
     * @return 方向（弧度），atan2(dz, dx)
     */
    double getPlayerDirection(UUID playerId);
    
    /**
     * 获取玩家移动速度 (blocks/tick)
     * @param playerId 玩家 UUID
     * @return 速度
     */
    double getPlayerSpeed(UUID playerId);
    
    /**
     * 在指定世界为某区块发放 FORCED ticket
     * @param worldId 世界唯一标识（Level/ServerLevel 的 hashCode 即可）
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @param ticketLevel 优先级（31=FULL, 33=BORDER）
     * @return 是否成功
     */
    boolean addChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel);
    
    /**
     * 移除之前发放的 FORCED ticket
     * @param worldId 世界唯一标识
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @param ticketLevel 发放时的等级 (必须与 addChunkTicket 一致才能移除)
     * @return 是否成功
     */
    boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel);

    /**
     * v0.11.4: 用"当前真实活跃的 CP 票集合"重建平台侧的票标记.
     *
     * 背景: Fabric 平台的 ticketedChunks 只在显式 removeChunkTicket 时清除, 任何漏掉的
     *   撤销路径 (维度切换、自然过期、异常) 都会让标记永久残留 → 只增不减的内存/语义泄漏.
     *   CP 每 5 秒用真实活跃票集合重建一次, 保证标记集合永远等于真实集合.
     *
     * @param activeChunkPositions 当前所有玩家活跃 CP 票的 ChunkPos.toLong() 集合
     */
    default void rebuildCpTicketMarks(java.util.Collection<Long> activeChunkPositions) {
        // 默认空实现 (不需要标记概念的平台)
    }

    /**
     * v0.10: 把 ChunkStatus 名解析为区块加载等级 (参考原版 ChunkLevel.byStatus).
     * 用于扇形远处区块的浅层加载 (只生成地形骨架, 不触发实体 tick).
     * 默认返回 31 (FULL_TICKING), 平台层应覆写为 ChunkLevel.byStatus(status).
     *
     * @param statusName ChunkStatus 名 (如 "CARVERS" / "FULL")
     * @return 区块加载等级 (数值越大越浅)
     */
    default int getShallowChunkLevel(String statusName) {
        return 31; // 默认 FULL_TICKING (未覆写则远处也 FULL, 行为不变)
    }
    
    // ========== 命令系统辅助 ==========
    
    /**
     * 获取玩家当前所在世界
     * @param playerId 玩家 UUID
     * @return 玩家对象（ServerPlayer 的引用），null 表示离线
     */
    Object getPlayerObject(UUID playerId);

    /**
     * 获取玩家网络统计
     * @param playerId 玩家 UUID
     * @return NetStats{rx_bytes, tx_bytes, rx_packets, tx_packets, ping_ms, tick_latency_ms}
     *         null = 玩家离线
     */
    NetStats getPlayerNetStats(UUID playerId);

    /**
     * 玩家网络统计
     */
    class NetStats {
        public final long rxBytes;
        public final long txBytes;
        public final long rxPackets;
        public final long txPackets;
        /** 玩家与服务端 RTT（毫秒）。-1 = 未知。 */
        public final long pingMs;
        /** 本服务器处理玩家最近一包数据到现在的 tick 差。-1 = 未知。 */
        public final long tickLatency;

        public NetStats(long rx, long tx, long rxp, long txp, long ping, long tickLat) {
            this.rxBytes = rx;
            this.txBytes = tx;
            this.rxPackets = rxp;
            this.txPackets = txp;
            this.pingMs = ping;
            this.tickLatency = tickLat;
        }

        public static NetStats empty() {
            return new NetStats(0, 0, 0, 0, -1, -1);
        }
    }

    /**
     * 获取当前在线玩家（返回 Map 便于 /chunkpilot status 等遍历）
     */
    Map<UUID, String> getOnlinePlayers();
    
    /**
     * 获取玩家所在世界的唯一标识（用于 addChunkTicket/removeChunkTicket）
     * @param playerId 玩家 UUID
     * @return worldId（0 表示未找到）
     */
    int getPlayerWorldId(UUID playerId);

    /**
     * v0.3.0 异步请求生成指定 chunk (DESIGN.md v12 第 11 章).
     *
     * 调用方 (GenerationScheduler) 通过此方法向世界调度器注册"希望尽快生成"的需求.
     * 实际实现由子任务 D 提供 (fabric/neoforge 各自的 Mixin/平台调用).
     *
     * @param worldId 世界唯一标识
     * @param chunkX  区块 X
     * @param chunkZ  区块 Z
     * @return true = 调度成功, false = 失败/不支持
     */
    default boolean requestChunkAsync(int worldId, int chunkX, int chunkZ) {
        // v0.3.0 子任务 D 实现. 当前 default 返回 false (不支持),
        // 调度器收到 false 视为静默失败, 不影响主流程.
        return false;
    }

    /**
     * v0.6.0: 请求异步生成 chunk 到指定深度 (浅层预生成).
     *
     * 0804 崩溃教训: 预生成 FULL 会把方块实体提前塞进主线程 tick 列表 (增压器).
     * 浅层预生成只生成到"地形骨架" (CARVERS: 噪声+生物群系+地表+洞穴),
     * 不产生方块实体/结构建筑 — 玩家接近时 vanilla 会自然补 FULL.
     *
     * @param worldId 世界唯一标识
     * @param chunkX  区块 X
     * @param chunkZ  区块 Z
     * @param targetStatus 目标深度 ("CARVERS" / "FULL" 等 ChunkStatus 名)
     * @return true = 调度成功, false = 失败/不支持
     */
    default boolean requestChunkGeneration(int worldId, int chunkX, int chunkZ, String targetStatus) {
        // 默认退化为 FULL 异步生成
        return requestChunkAsync(worldId, chunkX, chunkZ);
    }

    /**
     * v0.5.1: 检查 chunk 是否已加载 (已生成/已加载到无拟主线程).
     *
     * 调用方 (GenerationScheduler) 在扫描候选区块时跳过“已生成的”, 避免队伍被污染.
     * 如果不跳过, addRegionTicket 对已生成 chunk 只是 keep-alive, 浪费调度预算.
     *
     * 注意: 这里用 ServerChunkManager.isChunkLoaded(), 1.21.3 yarn 名称.
     *    在官方映射下对应 ServerChunkCache.isChunkLoaded().
     *
     * @param worldId 世界唯一标识
     * @param chunkX  区块 X
     * @param chunkZ  区块 Z
     * @return true = 已加载(已生成), false = 未加载(需要生成)
     */
    default boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        // default返回 false 表示“未知” — 调度器会当作“未生成”处理, 不会错过但不帮优化.
        return false;
    }

    /**
     * v0.11.8: 区块是否**已经生成到 FULL**(而不是"只是排上队").
     *
     * 为什么必须和 {@link #isChunkLoaded} 分开:
     *   `isChunkLoaded` 走的是 `ServerChunkCache.hasChunk`, 语义是
     *   "可见表里有 ChunkHolder 且票级 ≤ FULL" —— 也就是**已排期**, 不代表已经生成完。
     *   生成调度器原先用 isChunkLoaded 做"跳过已生成区块"的判断, 实际上把
     *   "刚排上队还没生成"的区块也一起跳过了 ⇒ 队列恒为空 (2026-09-20 实测:
     *   cand=106 skipped=134 enqueued=0, 开关前瞻窗口都一样)。
     *
     * 默认实现退化为 isChunkLoaded (旧行为), 平台应覆写为真正的 FULL 判定。
     */
    default boolean isChunkReadyFull(int worldId, int chunkX, int chunkZ) {
        return isChunkLoaded(worldId, chunkX, chunkZ);
    }

    /**
     * v0.11.5e: 只读区块探针 — **绝不加载区块**.
     *
     * 为什么需要它: 1.21.3 的 `/execute if loaded <pos>` 内部走
     *   `ServerChunkCache.getWorldChunk(x, z)`, 那是**带 load=true** 的读取,
     *   拿它当探针 = 每秒逼主线程同步加载区块, 会自己制造 "Can't keep up" 停摆
     *   (bench bot 实测每轮 2 次 12~24 秒). 所以 CP 自带一个纯查询入口.
     *
     * 返回一行人类可读文本 (供命令输出), 不加载任何区块.
     *
     * @param worldId 世界标识 (platform.getPlayerWorldId)
     * @param chunkX  区块 X
     * @param chunkZ  区块 Z
     */
    default String probeChunk(int worldId, int chunkX, int chunkZ) {
        return com.chunkpilot.i18n.I18n.tr("chunkpilot.command.probe.unavailable");
    }

    /**
     * v0.11.7 i18n 扩展: 允许按"命令执行者的语言"输出探针文本。
     *
     * @param viewerId 观看者的 UUID (可为 null → 用全局语言)
     */
    default String probeChunk(int worldId, int chunkX, int chunkZ, java.util.UUID viewerId) {
        // 未覆写 4 参版本的平台 (如 NeoForge) 自动回退到它自己的 3 参实现
        return probeChunk(worldId, chunkX, chunkZ);
    }

    /**
     * 主世界 (overworld) 的 worldId — 控制台/RCON 没有玩家时也能探针.
     * @return 0 = 未知
     */
    default int getOverworldId() {
        return 0;
    }

    /**
     * v0.11.6: "主线程本该 park 的区块读取被换成空区块" 的累计次数.
     * 涨得快 = 服务器当前确实生成跟不上; 一直是 0 = 从未触发, 属于正常状态.
     */
    default long getParkSubstitutions() {
        return 0;
    }

    /**
     * v0.11.7 i18n: 玩家客户端的语言代码 (来自 `serverbound/client_information` 包),
     * 例如 "zh_cn" / "en_us"。拿不到返回 null —— 调用方会自动回退到默认语言。
     *
     * @param playerId 玩家 UUID
     * @return 语言代码; null = 未知 (离线/平台不支持)
     */
    default String getPlayerLanguage(UUID playerId) {
        return null;
    }

    /**
     * 广播消息给所有在线玩家
     * @param playerId 目标玩家 UUID，null 时广播到所有在线玩家
     * @param message 消息文本
     */
    void broadcastMessage(UUID playerId, String message);
    
    /**
     * 发送消息给某玩家（普通聊天，绿色）
     * @param playerId 目标玩家 UUID，null 时广播到所有在线玩家
     * @param message 消息文本（支持 Minecraft 颜色代码 §）
     */
    void sendMessage(UUID playerId, String message);
    
    /**
     * 记录命令日志（操作者，执行结果）
     */
    void logCommand(String executor, String message);
}
