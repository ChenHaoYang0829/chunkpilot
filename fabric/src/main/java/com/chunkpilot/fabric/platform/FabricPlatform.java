package com.chunkpilot.fabric.platform;

import com.chunkpilot.platform.PlatformAbstraction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Fabric 平台实现（Mojang official mappings）
 * 通过 static 方法绑定当前 Server 实例，由 Fabric 入口初始化
 */
public class FabricPlatform implements PlatformAbstraction {
    
    // 当前服务端实例引用（由 ChunkPilotFabric.init 设置）
    private static volatile MinecraftServer currentServer = null;
    
    // 玩家 UUID → ServerPlayer 缓存
    private static final Map<UUID, ServerPlayer> PLAYER_CACHE = new ConcurrentHashMap<>();

    // isChunkLoaded 反射方法缓存 — 每 tick 对每个候选 chunk 调用, 必须缓存
    // findMethod 的类层次遍历, 否则成为巨大 CPU 热点.
    private static java.lang.reflect.Method cachedHasChunk = null;
    private static Class<?> cachedHasChunkClass = null;
    
    // ========== 1.21.11 ticket 系统重写 (port/1.21.11) ==========
    //
    // 1.21.11 把 ticket 系统整体换掉了 (javap 实证, 见 REPORT.md §API 差异表):
    //   * `TicketType` 从"泛型类 + create(name, comparator, expireTicks)" 变成
    //     **record** `TicketType(long timeout, int flags)`. 名字不再是字段, 相等性只看
    //     (timeout, flags). 自定义类型只能用 public 构造器 `new TicketType(timeout, flags)`.
    //   * 票本身变成 `net.minecraft.server.level.Ticket(TicketType type, int ticketLevel)`:
    //     **加载等级从 addTicket 的参数搬进了 Ticket 里**.
    //   * `DistanceManager` 不再有 addTicket/removeTicket; 入口改为
    //     `ServerChunkCache.addTicket(Ticket, ChunkPos)` (public, 直接转发 TicketStorage)
    //     / `TicketStorage.removeTicket(Ticket, ChunkPos)` (字段 private → 走 accessor mixin).
    //   * flags 位 (从 TicketType 静态块与 4 个谓词的字节码解出):
    //       1=PERSIST  2=LOADING  4=SIMULATION  8=KEEP_DIMENSION_ACTIVE  16=CAN_EXPIRE_IF_UNLOADED
    //     **必须带 LOADING(2)**: `TicketStorage.getLowestTicket(list, simulation=false)`
    //     只统计 `doesLoad()` 为真的票 (javap 实证), 没有这一位的票对加载等级**完全不可见**,
    //     等于预生成彻底失效.
    //     **绝不能带 PERSIST(1)**: 自定义 TicketType 没进 `BuiltInRegistries.TICKET_TYPE`
    //     (注册入口 `TicketType.register` 是 private), 一旦被 `TicketStorage.packTickets()`
    //     序列化就会因查不到注册名而炸存档. 而 packTickets 只打包 `persist()` 为真的票
    //     (javap 实证 method_67396: `Ticket.getType().persist(); ifeq → skip`),
    //     所以不设 PERSIST 就永远不会被序列化 —— 这是本方案成立的前提.
    private static final int CP_TICKET_FLAGS = 2; // FLAG_LOADING

    // ChunkPilot 自定义 ticket 类型. timeout 与 1.21.3 语义对齐: 31 / 200 在原 API 里是
    // `TicketType.create(...)` 的 **expireTicks** (不是加载等级), 现在就是 `TicketType.timeout`.
    // 加载等级由 addTicket 传入的 Ticket 决定.
    // ⚠ (timeout, flags) 不能与原版 9 个类型重复 —— 1.21.11 的 TicketType 是 record, equals 只看
    //   (timeout, flags), 而 TicketStorage 判"同类型同等级"(isTicketSameTypeAndLevel) 与判 FORCED
    //   (type.equals(TicketType.FORCED), offset 198 javap 实证) 都用 equals ⇒ 撞车会互相误删.
    //   原版类型 (TicketType 静态块实证): (20,2) (1,2) (0,6) (0,2) (0,12) (0,15) (300,15) (40,14) (1,18).
    //   本类用的 (31,2) 与 (200,2) 都不在其中.
    public static final TicketType CHUNKPILOT_TICKET = new TicketType(31L, CP_TICKET_FLAGS);

    /**
     * v0.11.5c: CP **预生成**统一使用的票据 level = 33 (= FULL 生成, 但不参与 block/entity tick)。
     *
     * 为什么不是 31: level 31 正是"玩家自身区域"的级别, 预生成用 31 会与玩家急需的区块
     *   同优先级 FIFO 排队 → 主线程 tick 玩家实体时的同步区块等待被排到大队列后面 →
     *   实测 12~15 秒停摆 (jstack 两次签名一致: ServerChunkCache$MainThreadExecutor park;
     *   GC 已排除). 33 仍高于原版环状浅层依赖 (34~41), 保留"提前生成"收益, 但不再抢占玩家区域.
     */
    public static final int PREFETCH_TICKET_LEVEL = 33;

    // v0.3.0 生成请求用的临时 ticket (低优先级, 仅触发异步生成)
    // v0.6.0: 加 expiryTicks=200 (10s) — 浅层预生成的 chunk 必须能自动过期卸载,
    //   否则只 add 不 remove 的浅层 ticket 会让 chunk 永久驻留 (内存泄漏).
    //   玩家接近时 vanilla 的 PLAYER ticket 会重新加载并补 FULL.
    public static final TicketType CHUNKPILOT_GEN_TICKET = new TicketType(200L, CP_TICKET_FLAGS);

    // ========== v0.9.0: CP 独占生成模式 (无 C2ME 时停掉原版生成队列) ==========
    //
    // 背景: CP 单独 (无 C2ME) 时, 单线程 worldgen 跟不上玩家高速飞行,
    //   原版 PLAYER ticket 全向生成 + CP 前方预生成叠加 → 生成队列被塞满 → 服务器过载掉线.
    //
    // 方案: 检测到没有 C2ME 时, 停掉原版自动生成队列 (拦截 ChunkMap.scheduleGenerationTask),
    //   只允许 CP 权重公式请求的 chunk 进入生成队列 (requestedChunks 集合).
    //
    // requestedChunks: 本 tick CP 想生成的 chunk 位置集合 (ChunkPos.toLong()).
    //   由 requestChunkAsync 填充, 每 tick 开头由 ServerInitializer 清空后重建.
    //   ChunkMapGenerationMixin 在 scheduleGenerationTask 里查这个集合:
    //     在集合内 → 放行 (CP 权重公式驱动); 不在集合内 → 取消 (原版自动生成被停).
    private static final java.util.Set<Long> requestedChunks =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 标记某 chunk 为 CP 想生成的 (由 requestChunkAsync 调用). */
    public static void markChunkRequested(long chunkPosLong) {
        requestedChunks.add(chunkPosLong);
    }

    /** 查询某 chunk 是否被 CP 请求生成 (ChunkMapGenerationMixin 调用). */
    public static boolean isChunkRequestedByCp(long chunkPosLong) {
        return requestedChunks.contains(chunkPosLong);
    }

    /** 清空本 tick 的 CP 请求集合 (每 tick 开头由 ServerInitializer 调用). */
    public static void clearRequestedChunks() {
        requestedChunks.clear();
    }

    /** 当前 CP 请求集合大小 (给 /chunkpilot gen stats 用). */
    public static int getRequestedChunksSize() {
        return requestedChunks.size();
    }

    // ========== v0.10.4: 持久 CP ticket 集合 ==========
    //
    // 背景: exclusiveGenerationNoC2me mixin 会取消"不在 requestedChunks 里"的生成任务.
    //   requestedChunks 每 tick 清空, 只含 GenerationScheduler 本 tick 请求的 chunk.
    //   但 ChunkLoadOptimizer 的 sector ticket (CHUNKPILOT_TICKET) 触发的生成任务
    //   不在 requestedChunks 里 → 被 mixin 误取消 → sector 形同虚设 → 玩家飞入未生成区块 → mspt 尖峰.
    //
    // 方案: 新增持久集合 ticketedChunks, 记录"当前有活跃 CP ticket"的 chunk.
    //   由 addChunkTicket/removeChunkTicket 维护 (跨 tick 持久, 不随每 tick 清空).
    //   mixin 放行: 在 requestedChunks (本 tick 生成器请求) 或 ticketedChunks (sector 活跃票) 里的 chunk.
    //   这样只取消"原版自动生成", 不取消 CP 自己的 sector 生成.
    private static final java.util.Set<Long> ticketedChunks =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 标记某 chunk 有活跃 CP ticket (由 addChunkTicket 调用). */
    public static void markChunkTicketed(long chunkPosLong) {
        ticketedChunks.add(chunkPosLong);
    }

    /** 取消某 chunk 的 CP ticket 标记 (由 removeChunkTicket 调用). */
    public static void unmarkChunkTicketed(long chunkPosLong) {
        ticketedChunks.remove(chunkPosLong);
    }

    /** 查询某 chunk 是否有活跃 CP ticket (ChunkMapGenerationMixin 调用). */
    public static boolean isChunkTicketedByCp(long chunkPosLong) {
        return ticketedChunks.contains(chunkPosLong);
    }

    /** 当前活跃 CP ticket 集合大小 (给 /chunkpilot gen stats 用). */
    public static int getTicketedChunksSize() {
        return ticketedChunks.size();
    }

    /**
     * v0.11.4: 用真实活跃票集合重建标记集合.
     * 旧实现只增不减 (只在 removeChunkTicket 时清), 维度切换/自然过期/异常都会留下永久残留.
     */
    public static void rebuildTicketedChunks(java.util.Collection<Long> activeChunkPositions) {
        ticketedChunks.clear();
        if (activeChunkPositions != null && !activeChunkPositions.isEmpty()) {
            ticketedChunks.addAll(activeChunkPositions);
        }
    }

    @Override
    public void rebuildCpTicketMarks(java.util.Collection<Long> activeChunkPositions) {
        rebuildTicketedChunks(activeChunkPositions);
    }
    
    public static void setServer(MinecraftServer server) {
        currentServer = server;
        PLAYER_CACHE.clear();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                PLAYER_CACHE.put(p.getUUID(), p);
            }
        }
    }
    
    public static void registerPlayer(ServerPlayer player) {
        PLAYER_CACHE.put(player.getUUID(), player);
    }
    
    public static void unregisterPlayer(ServerPlayer player) {
        PLAYER_CACHE.remove(player.getUUID());
    }
    
    public static MinecraftServer getServer() {
        return currentServer;
    }
    
    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }
    
    @Override
    public String getPlatformName() {
        return "fabric";
    }
    
    @Override
    public int getServerRenderDistance() {
        MinecraftServer server = currentServer;
        if (server == null) return 8;
        return server.getPlayerList().getViewDistance();
    }
    
    @Override
    public double getCurrentMspt() {
        MinecraftServer server = currentServer;
        if (server == null) return 0;
        // Mojang 1.21: server.getCurrentServerTime() 不直接给 MSPT
        // 用 averageTickTimeNanos / 1e6
        return server.getAverageTickTimeNanos() / 1_000_000.0;
    }
    
    @Override
    public double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return max > 0 ? (double) used / max * 100 : 0;
    }
    
    @Override
    public int[] getPlayerChunkPos(UUID playerId) {
        ServerPlayer player = PLAYER_CACHE.get(playerId);
        if (player == null || !player.isAlive()) return null;
        ChunkPos pos = player.chunkPosition();
        return new int[]{pos.x, pos.z};
    }
    
    @Override
    public double getPlayerDirection(UUID playerId) {
        // SpeedTracker 内部从位置变化推算方向
        return 0;
    }
    
    @Override
    public double getPlayerSpeed(UUID playerId) {
        // SpeedTracker 内部计算速度
        return 0;
    }
    
    @Override
    public boolean addChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = currentServer;
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        // v0.10: 单区块 addTicket, 直接指定加载等级, 支持远处浅层加载.
        // 1.21.11: 等级现在装在 Ticket 里 (DistanceManager.addTicket 已删除):
        //   ServerChunkCache.addTicket(new Ticket(type, level), pos)
        //   level 直接决定加载深度 (31=FULL_TICKING, 33=FULL, 40≈CARVERS).
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().addTicket(new Ticket(CHUNKPILOT_TICKET, ticketLevel), pos);
        // v0.10.4: 标记该 chunk 有活跃 CP ticket, 让 mixin 放行其生成 (不误取消 sector 生成)
        markChunkTicketed(pos.toLong());
        return true;
    }
    
    @Override
    public boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = currentServer;
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        // 与 addChunkTicket 对称: 同一 (type, level) 移除.
        // 1.21.11: ServerChunkCache 只暴露 removeTicketWithRadius(type, pos, radius), 它按
        //   `ChunkLevel.byStatus(FullChunkStatus.FULL) - radius` 反推等级 —— 拿不到任意等级.
        //   所以走 accessor mixin 取 TicketStorage.ticketStorage, 直接 removeTicket(Ticket, pos)
        //   (TicketStorage.removeTicket 按 "类型 + 等级" 匹配, 与加入时一致).
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        removeCpTicket(world, CHUNKPILOT_TICKET, ticketLevel, pos);
        // v0.10.4: 取消该 chunk 的 CP ticket 标记
        unmarkChunkTicketed(pos.toLong());
        return true;
    }

    /**
     * 1.21.11 专用: 按任意等级移除 CP ticket.
     *
     * `DistanceManager.removeTicket(type, value, level, value2)` 在 1.21.11 已删除;
     * `ServerChunkCache.removeTicketWithRadius` 只能表达 `33 - radius` 这一族等级.
     * 因此这里用 {@link com.chunkpilot.fabric.mixin.ServerChunkCacheAccessor} 拿到
     * `TicketStorage`, 再调用其 public `removeTicket(Ticket, ChunkPos)`.
     * 任何异常都吞掉并返回, 绝不影响服务器.
     */
    private static void removeCpTicket(ServerLevel world, TicketType type, int ticketLevel, ChunkPos pos) {
        try {
            Object cs = world.getChunkSource();
            if (cs instanceof com.chunkpilot.fabric.mixin.ServerChunkCacheAccessor acc) {
                net.minecraft.world.level.TicketStorage storage = acc.chunkpilot$ticketStorage();
                if (storage != null) {
                    storage.removeTicket(new Ticket(type, ticketLevel), pos);
                }
            }
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").warn(
                "[ChunkPilot] removeCpTicket failed: {}", t.toString());
        }
    }

    /**
     * v0.11.8: 真正的"已生成到 FULL"判定 (非阻塞).
     * 用 ChunkHolder.fullChunkFuture.isDone() —— 只有 FULL 完成才 done。
     */
    @Override
    public boolean isChunkReadyFull(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = currentServer;
            if (server == null) return false;
            ServerLevel world = findWorld(server, worldId);
            if (world == null) return false;
            long posLong = ChunkPos.asLong(chunkX, chunkZ);
            Object holder = null;
            for (java.lang.reflect.Method m : world.getChunkSource().getClass().getMethods()) {
                if (m.getName().equals("getChunkHolder") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == long.class) {
                    holder = m.invoke(world.getChunkSource(), posLong);
                    break;
                }
            }
            if (holder == null) return false;
            for (java.lang.reflect.Method m : holder.getClass().getMethods()) {
                if (m.getName().equals("getFullChunkFuture") && m.getParameterCount() == 0) {
                    Object fut = m.invoke(holder);
                    if (fut instanceof java.util.concurrent.CompletableFuture<?> cf) {
                        if (!cf.isDone()) return false;
                        Object res = cf.getNow(null);
                        if (res == null) return false;
                        for (java.lang.reflect.Method rm : res.getClass().getMethods()) {
                            if (rm.getName().equals("isSuccess") && rm.getParameterCount() == 0) {
                                return Boolean.TRUE.equals(rm.invoke(res));
                            }
                        }
                    }
                    return false;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int getShallowChunkLevel(String statusName) {
        try {
            net.minecraft.world.level.chunk.status.ChunkStatus status =
                net.minecraft.world.level.chunk.status.ChunkStatus.byName(statusName);
            if (status == null) return 31;
            return net.minecraft.server.level.ChunkLevel.byStatus(status);
        } catch (Throwable t) {
            return 31; // 解析失败回退 FULL_TICKING
        }
    }
    
    private static ServerLevel findWorld(MinecraftServer server, int worldId) {
        for (ServerLevel w : server.getAllLevels()) {
            if (w.hashCode() == worldId) return w;
        }
        return null;
    }

    // ========== v0.3.0 异步生成请求 ==========

    @Override
    public boolean requestChunkAsync(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = currentServer;
            if (server == null) return false;

            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;

            // v0.9.0: 标记该 chunk 为 CP 想生成的.
            //   ChunkMapGenerationMixin 在 runGenerationTask 里查这个集合:
            //   无 C2ME 时只放行 CP 请求的 chunk, 停掉原版自动生成.
            markChunkRequested(ChunkPos.asLong(chunkX, chunkZ));

            // v0.11.5c (2026-09-13 停摆根因修复): 预生成票据的 level 从 31 改为 33.
            //
            //  实测证据: 飞行中主线程会 tick 玩家实体 → 实体查询需要区块 → 同步等区块生成,
            //    jstack 抓到主线程停在 ServerChunkCache$MainThreadExecutor 上 park 12~15 秒
            //    (两次停摆签名完全一致; GC 已排除 —— 窗口内只有 Young GC, 最大 60.6ms, 0 次 Full GC).
            //  根因: 旧代码用 addRegionTicket(radius=0) 恒得 level 31 = **玩家自身区域的级别**,
            //   于是 CP 预生成的几百个区块与玩家脚下/身边区域**同优先级 FIFO 排队**,
            //    玩家 tick 真正需要的区块排在大队列后面 → 主线程停摆 → 客户端十几秒收不到区块 → 穿墙/虚空.
            //  修法: 预生成用 level 33 (= FULL 生成, 但不参与 block/entity tick),
            //    仍高于原版环状浅层依赖 (34~41) → CP 的"提前生成"收益保留;
            //    但低于玩家自身区域 (31~32) → 不再抢占玩家急需的区块.
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().addTicket(new Ticket(CHUNKPILOT_GEN_TICKET, PREFETCH_TICKET_LEVEL), pos);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean requestChunkGeneration(int worldId, int chunkX, int chunkZ, String targetStatus) {
        try {
            MinecraftServer server = currentServer;
            if (server == null) return false;

            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;

            // 目标状态: 默认 FULL (安全)
            net.minecraft.world.level.chunk.status.ChunkStatus target =
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
            if (targetStatus != null && !targetStatus.isEmpty()) {
                net.minecraft.world.level.chunk.status.ChunkStatus parsed =
                    net.minecraft.world.level.chunk.status.ChunkStatus.byName(targetStatus);
                if (parsed != null) target = parsed;
            }

            // v0.6.0 浅层预生成: 用 ChunkLevel.byStatus 拿精确 level, 走 ServerChunkCache.addTicket
            // (非 region, 直接指定 level, 没有半径爆炸问题).
            // 1.21.11: `ChunkLevel.byStatus(ChunkStatus)` 仍在 (javap 实证), 等级搬进 Ticket.
            //   byStatus(CARVERS) -> level 约 40 -> 只生成到地形骨架 (无方块实体)
            //   byStatus(FULL)    -> level 33   -> 完整生成
            // 玩家接近时 vanilla 的 PLAYER ticket (level 更小) 会自然把 chunk 补到 FULL.
            int targetLevel = net.minecraft.server.level.ChunkLevel.byStatus(target);
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().addTicket(new Ticket(CHUNKPILOT_GEN_TICKET, targetLevel), pos);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v0.11.5e: 只读区块探针 (不加载区块).
     *
     * 用到的都是**无副作用**查询:
     *   - ServerChunkCache.hasChunk(x, z)              → 是否已加载到 FULL (官方映射名)
     *   - ServerChunkCache.getChunkHolder(long)        → ChunkHolder (只查表, 不加载)
     *   - ChunkHolder.getTicketLevel()                 → 该区块当前票等级 (越小越优先,
     *                                                    23=脚下/31=entity-ticking/33=FULL 边界/>33 未加载)
     * 绝不调用任何带 load=true 的 getChunk/getWorldChunk.
     */
    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ) {
        return probeChunk(worldId, chunkX, chunkZ, null);
    }

    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ, java.util.UUID viewerId) {
        StringBuilder sb = new StringBuilder();
        try {
            MinecraftServer server = currentServer;
            if (server == null) return com.chunkpilot.i18n.I18n.trFor(viewerId,
                "chunkpilot.probe.server_not_bound");
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return com.chunkpilot.i18n.I18n.trFor(viewerId,
                "chunkpilot.probe.world_not_found", worldId);

            long posLong = ChunkPos.asLong(chunkX, chunkZ);
            boolean loaded = isChunkLoaded(worldId, chunkX, chunkZ);
            int ticketLevel = -1;
            int completedLevel = -1;
            boolean holderPresent = false;
            String statusName = "?";
            try {
                Object source = level.getChunkSource();
                Object holder = null;
                for (java.lang.reflect.Method m : source.getClass().getMethods()) {
                    if (m.getName().equals("getChunkHolder") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == long.class) {
                        holder = m.invoke(source, posLong);
                        break;
                    }
                }
                if (holder != null) {
                    holderPresent = true;
                    for (java.lang.reflect.Method m : holder.getClass().getMethods()) {
                        if (m.getName().equals("getTicketLevel") && m.getParameterCount() == 0) {
                            ticketLevel = (Integer) m.invoke(holder);
                        } else if (m.getName().equals("getCompletedLevel") && m.getParameterCount() == 0) {
                            completedLevel = (Integer) m.invoke(holder);
                        }
                    }
                    statusName = ChunkLevelTypeName(ticketLevel);
                }
            } catch (Throwable ignored) {
            }

            int pcx = 0, pcz = 0;
            double best = -1;
            for (var sp : server.getPlayerList().getPlayers()) {
                if (sp.level() != level) continue;
                int cx = sp.chunkPosition().x, cz = sp.chunkPosition().z;
                double d = Math.hypot(chunkX - cx, chunkZ - cz);
                if (best < 0 || d < best) {
                    best = d;
                    pcx = cx;
                    pcz = cz;
                }
            }

            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.line",
                chunkX, chunkZ, chunkX << 4, chunkZ << 4, loaded, holderPresent,
                ticketLevel, completedLevel, statusName));
            if (best >= 0) {
                sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.nearest",
                    pcx, pcz, best));
            }
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.marks",
                isChunkRequestedByCp(posLong), isChunkTicketedByCp(posLong)));
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.view",
                getServerRenderDistance()));
        } catch (Throwable t) {
            return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.failed",
                String.valueOf(t));
        }
        return sb.toString();
    }

    /**
     * v0.11.7 i18n: 玩家客户端语言 (来自 `serverbound/client_information`).
     * 1.21.3 official 映射: ServerPlayer.clientInformation() → ClientInformation.language().
     */
    @Override
    public String getPlayerLanguage(UUID playerId) {
        try {
            ServerPlayer player = PLAYER_CACHE.get(playerId);
            if (player == null) return null;
            var info = player.clientInformation();
            return info == null ? null : info.language();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public long getParkSubstitutions() {
        return com.chunkpilot.fabric.util.NonBlockingStats.parkSubstitutions();
    }

    @Override
    public int getOverworldId() {
        try {
            MinecraftServer server = currentServer;
            if (server == null) return 0;
            return server.overworld().hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 票等级 → 人类可读的加载类型 (与原版 ChunkLevel/FullChunkStatus 对应). */
    private static String ChunkLevelTypeName(int level) {
        if (level < 0) return "unknown";
        if (level <= 31) return "ENTITY_TICKING";
        if (level == 32) return "BLOCK_TICKING";
        if (level == 33) return "FULL";
        if (level < 44) return "BORDER(部分生成)";
        return "UNLOADED";
    }

    @Override
    public boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = currentServer;
            if (server == null) return false;

            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;

            // 1.21.3 official 命名空间: ServerChunkCache.hasChunk(int, int)
            //   (yarn/旧版叫 isChunkLoaded, official 已统一为 hasChunk)
            // 反射查找兼容两种命名 — 以 hasChunk 优先; 结果按运行时类缓存.
            // v0.11.5e 修复 (重要): 原来用**按名字反射**找 hasChunk/isChunkLoaded,
            //   但发布 jar 是 remapJar 到 intermediary 的, 运行期方法名是 method_12123,
            //   名字反射必然找不到 → 本方法**恒返回 false**:
            //     · GenerationScheduler 的"跳过已生成区块"过滤完全失效 → 队列被已生成区块污染,
            //       无谓消耗每 tick 的请求预算;
            //     · 生成在途窗口 (maxOutstandingRequests) 只能靠 TTL 过期才能腾位置 →
            //       预生成吞吐被压到 ~32 请求/10 秒, 这正是之前"窗口长期 32/32 满载"的真因.
            //   现在直接调用 (loom 会正确 remap 到 intermediary):
            //     ServerChunkCache.hasChunk(int,int) = "ChunkHolder 存在且票等级 <= FULL(33)"
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 缓存并返回 chunk 已加载检查方法 (hasChunk 优先, 回退 isChunkLoaded). */
    private static java.lang.reflect.Method resolveHasChunk(Class<?> cls) {
        if (cachedHasChunk != null && cachedHasChunkClass == cls) return cachedHasChunk;
        java.lang.reflect.Method m = findMethod(cls, "hasChunk", int.class, int.class);
        if (m == null) m = findMethod(cls, "isChunkLoaded", int.class, int.class);
        if (m != null) m.setAccessible(true);
        cachedHasChunk = m;
        cachedHasChunkClass = cls;
        return m;
    }

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            // 遍历父类
            Class<?> sup = clazz.getSuperclass();
            while (sup != null) {
                try {
                    return sup.getDeclaredMethod(name, paramTypes);
                } catch (NoSuchMethodException e2) {
                    sup = sup.getSuperclass();
                }
            }
            return null;
        }
    }
    
    @Override
    public int getPlayerWorldId(UUID playerId) {
        ServerPlayer player = PLAYER_CACHE.get(playerId);
        if (player == null) return 0;
        return player.level().hashCode();
    }

    @Override
    public Object getPlayerObject(UUID playerId) {
        return PLAYER_CACHE.get(playerId);
    }

    @Override
    public com.chunkpilot.platform.PlatformAbstraction.NetStats getPlayerNetStats(UUID playerId) {
        Object player = PLAYER_CACHE.get(playerId);
        if (player == null) return null;
        try {
            long pingMs = -1;
            long tickLat = -1;

            // Get ServerPlayer.connection.connection (Mojang 1.21.1)
            Object gameConn = null;
            try {
                gameConn = player.getClass().getMethod("connection").invoke(player);
            } catch (Throwable ignored) {}
            Object netConn = null;
            if (gameConn != null) {
                try {
                    netConn = gameConn.getClass().getMethod("connection").invoke(gameConn);
                } catch (Throwable ignored) {}
            }

            // Ping
            if (gameConn != null) {
                pingMs = readLongField(gameConn, "latency");
                if (pingMs < 0) pingMs = readLongField(gameConn, "ping");
            }

            long rxBytes = 0, txBytes = 0;
            Object channel = null;
            if (netConn != null) {
                try {
                    channel = netConn.getClass().getMethod("channel").invoke(netConn);
                } catch (Throwable ignored) {}
            }
            if (channel != null) {
                rxBytes = readLongField(channel, "bytesRead");
                txBytes = readLongField(channel, "bytesWritten");
            }

            return new com.chunkpilot.platform.PlatformAbstraction.NetStats(
                rxBytes, txBytes, 0, 0, pingMs, tickLat);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long readLongField(Object obj, String name) {
        try {
            Class<?> cur = obj.getClass();
            while (cur != null) {
                try {
                    java.lang.reflect.Field f = cur.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof Number n) return n.longValue();
                    return -1;
                } catch (NoSuchFieldException ignored) {}
                cur = cur.getSuperclass();
            }
            return -1;
        } catch (Throwable t) { return -1; }
    }
    
    @Override
    public java.util.Map<UUID, String> getOnlinePlayers() {
        java.util.Map<UUID, String> map = new java.util.HashMap<>();
        MinecraftServer server = currentServer;
        if (server == null) return map;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            map.put(p.getUUID(), p.getGameProfile().name());
        }
        return map;
    }
    
    @Override
    public void broadcastMessage(UUID playerId, String message) {
        sendMessage(playerId, message);
    }
    
    @Override
    public void sendMessage(UUID playerId, String message) {
        MinecraftServer server = currentServer;
        if (server == null) return;
        net.minecraft.network.chat.Component comp = net.minecraft.network.chat.Component.literal(message);
        if (playerId == null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(comp);
            }
        } else {
            ServerPlayer target = PLAYER_CACHE.get(playerId);
            if (target != null) target.sendSystemMessage(comp);
        }
    }
    
    @Override
    public void logCommand(String executor, String message) {
        org.slf4j.LoggerFactory.getLogger("ChunkPilot").info(
            "[CMD] {} executed: {}", executor, message);
    }

    // ========== 冻结检测器 (诊断: 主线程卡住时 dump 玩家周边 chunk 状态) ==========
    private static volatile long lastTickNanos = System.nanoTime();
    private static volatile long lastDumpNanos = 0;
    private static final Map<Integer, int[]> playerChunkPos = new ConcurrentHashMap<>();
    private static volatile Thread freezeDetectorThread = null;

    /** 每 tick 由 ServerInitializer 调用, 记录心跳 + 玩家所在 chunk. */
    public static void onServerTickHeartbeat(int worldId, int cx, int cz) {
        lastTickNanos = System.nanoTime();
        playerChunkPos.put(worldId, new int[]{cx, cz});
    }

    /** 每 tick 无条件更新心跳 (无玩家时也调用, 避免误报冻结). */
    public static void onServerTickHeartbeat() {
        lastTickNanos = System.nanoTime();
    }

    /** 启动后台冻结检测线程 (SERVER_STARTED 时调用). */
    public static void startFreezeDetector() {
        if (freezeDetectorThread != null && freezeDetectorThread.isAlive()) return;
        freezeDetectorThread = new Thread(() -> {
            while (true) {
                try {
                    long now = System.nanoTime();
                    if (now - lastTickNanos > 3_000_000_000L && now - lastDumpNanos > 5_000_000_000L) {
                        lastDumpNanos = now;
                        dumpFrozenState();
                    }
                    Thread.sleep(500);
                } catch (Throwable t) { /* 检测器绝不影响服务器 */ }
            }
        }, "ChunkPilot-FreezeDetector");
        freezeDetectorThread.setDaemon(true);
        freezeDetectorThread.start();
    }

    /** 主线程卡住时, dump 玩家周边 r=4 内所有未到 FULL 的 chunk. */
    private static void dumpFrozenState() {
        try {
            MinecraftServer server = currentServer;
            if (server == null) return;
            // 无玩家时服务器可能因空置而暂停 (主线程停→心跳停), 不算冻结
            if (playerChunkPos.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            sb.append("\n===== ChunkPilot FREEZE DETECTED (main thread stalled) =====");
            for (Map.Entry<Integer, int[]> e : playerChunkPos.entrySet()) {
                int worldId = e.getKey();
                int[] pc = e.getValue();
                if (pc == null) continue;
                sb.append("\n[world ").append(worldId).append("] player chunk=(")
                  .append(pc[0]).append(",").append(pc[1]).append(")");
                ServerLevel level = findWorld(server, worldId);
                if (level == null) { sb.append(" (level not found)"); continue; }
                int notFull = 0;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        int cx = pc[0] + dx, cz = pc[1] + dz;
                        boolean loaded = false;
                        try {
                            loaded = level.getChunkSource().getChunkNow(cx, cz) != null;
                        } catch (Throwable t) { /* ignore */ }
                        if (!loaded) {
                            sb.append("\n  NOT-FULL (").append(cx).append(",").append(cz).append(")");
                            notFull++;
                        }
                    }
                }
                sb.append("\n  [r=4 ring: ").append(notFull).append(" chunks not FULL]");
            }
            sb.append("\n===== end freeze dump =====");
            org.slf4j.LoggerFactory.getLogger("ChunkPilotGen").info(sb.toString());
        } catch (Throwable t) { /* ignore */ }
    }
}
