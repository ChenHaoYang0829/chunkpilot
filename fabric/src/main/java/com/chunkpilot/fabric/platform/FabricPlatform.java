package com.chunkpilot.fabric.platform;

import com.chunkpilot.platform.PlatformAbstraction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
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

    // ChunkPilot 自定义 ticket 类型（level 31 = FULL_TICKING）
    public static final TicketType<ChunkPos> CHUNKPILOT_TICKET = 
        TicketType.create("chunkpilot:forced", java.util.Comparator.comparingLong(ChunkPos::toLong), 31);

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
    public static final TicketType<ChunkPos> CHUNKPILOT_GEN_TICKET =
        TicketType.create("chunkpilot:gen", java.util.Comparator.comparingLong(ChunkPos::toLong), 200);

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
        // 1.20.1 (javap 实证): MinecraftServer **没有** getAverageTickTimeNanos(),
        //   只有 public float getAverageTickTime() (已是毫秒) 与 public final long[] tickTimes (纳秒).
        return server.getAverageTickTime();
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

        // v0.10: 改用 addTicket (单区块) 直接指定加载等级, 支持远处浅层加载.
        //   旧代码 addRegionTicket(..., radius=0) 恒为 FULL_TICKING (level 31),
        //   无法表达"远处只生成地形骨架"的浅层等级.
        //   addTicket(type, pos, level, value): level 直接决定加载深度 (31=FULL, 40≈CARVERS).
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().chunkMap.getDistanceManager()
            .addTicket(CHUNKPILOT_TICKET, pos, ticketLevel, pos);
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

        // 与 addChunkTicket 对称: 用同一等级移除
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().chunkMap.getDistanceManager()
            .removeTicket(CHUNKPILOT_TICKET, pos, ticketLevel, pos);
        // v0.10.4: 取消该 chunk 的 CP ticket 标记
        unmarkChunkTicketed(pos.toLong());
        return true;
    }

    /**
     * v0.11.8: 真正的"已生成到 FULL"判定 (非阻塞).
     * 用 ChunkHolder.fullChunkFuture.isDone() —— 只有 FULL 完成才 done。
     *
     * 1.20.1 移植说明 (javap 实证):
     *   - `ChunkHolder.getFullChunkFuture()` 在 1.20.1 返回
     *     `CompletableFuture<Either<LevelChunk, ChunkHolder$ChunkLoadingFailure>>`
     *     —— **没有** 1.21.2+ 的 `ChunkResult` 包装, 所以"成功"判定是 `either.left().isPresent()`.
     *   - 1.20.1 的 ServerChunkCache **没有** getChunkHolder(long) (旧实现按名字反射必然恒 false);
     *     真实入口是 private 的 `getVisibleChunkIfPresent(long)`, 这里反射拿一次并缓存.
     */
    @Override
    public boolean isChunkReadyFull(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = currentServer;
            if (server == null) return false;
            ServerLevel world = findWorld(server, worldId);
            if (world == null) return false;
            long posLong = ChunkPos.asLong(chunkX, chunkZ);
            // main 的结构性修复 (3ccbf0c) 必须保留: 原来用运行期反射找 getChunkHolder/getFullChunkFuture,
            //   而 fabric 运行期是 **intermediary 命名** (method_14131/field_17253), 可读名反射必然失败
            //   ⇒ isChunkReadyFull 恒返回 false。改用 mixin accessor (@Invoker 会被 loom 正确 remap)。
            net.minecraft.server.level.ChunkHolder holder =
                ((com.chunkpilot.fabric.mixin.ServerChunkCacheAccessor) world.getChunkSource())
                    .chunkpilot$getVisibleChunkIfPresent(posLong);
            if (holder == null) return false;
            // 1.20.1 适配 (port/1.20.1): 1.20.1 **没有** ChunkResult,
            //   getFullChunkFuture() 返回 CompletableFuture<Either<LevelChunk, ChunkLoadingFailure>>,
            //   "成功"判定 = either.left().isPresent()。语义与 main 的 res.isSuccess() 等价。
            java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<
                net.minecraft.world.level.chunk.LevelChunk,
                net.minecraft.server.level.ChunkHolder.ChunkLoadingFailure>> full =
                holder.getFullChunkFuture();
            if (full == null || !full.isDone()) return false;
            com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.LevelChunk,
                net.minecraft.server.level.ChunkHolder.ChunkLoadingFailure> res = full.getNow(null);
            return res != null && res.left().isPresent() && res.left().get() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int getShallowChunkLevel(String statusName) {
        try {
            // 1.20.1 (javap 实证): ChunkStatus 在 net.minecraft.world.level.chunk.ChunkStatus
            //   (1.21.2+ 才挪到 ...chunk.status.ChunkStatus)
            //   ChunkLevel.byStatus(ChunkStatus) 在 1.20.1 存在 → 浅层加载等级可精确表达.
            net.minecraft.world.level.chunk.ChunkStatus status =
                net.minecraft.world.level.chunk.ChunkStatus.byName(statusName);
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
            level.getChunkSource().chunkMap.getDistanceManager()
                .addTicket(CHUNKPILOT_GEN_TICKET, pos, PREFETCH_TICKET_LEVEL, pos);
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
            // 1.20.1: ChunkStatus 在 net.minecraft.world.level.chunk.ChunkStatus (javap 实证)
            net.minecraft.world.level.chunk.ChunkStatus target =
                net.minecraft.world.level.chunk.ChunkStatus.FULL;
            if (targetStatus != null && !targetStatus.isEmpty()) {
                net.minecraft.world.level.chunk.ChunkStatus parsed =
                    net.minecraft.world.level.chunk.ChunkStatus.byName(targetStatus);
                if (parsed != null) target = parsed;
            }

            // v0.6.0 浅层预生成: 用 ChunkLevel.byStatus 拿精确 level, 走 DistanceManager.addTicket
            // (非 region, 直接指定 level, 没有半径爆炸问题).
            //   byStatus(CARVERS) -> level 约 40 -> 只生成到地形骨架 (无方块实体)
            //   byStatus(FULL)    -> level 31   -> 完整生成
            // 玩家接近时 vanilla 的 PLAYER ticket (level 更小) 会自然把 chunk 补到 FULL.
            int targetLevel = net.minecraft.server.level.ChunkLevel.byStatus(target);
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().chunkMap.getDistanceManager()
                .addTicket(CHUNKPILOT_GEN_TICKET, pos, targetLevel, pos);
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
            boolean holderPresent = false;
            String statusName = "?";
            try {
                // v0.11.9 修复: 原实现用反射找 ServerChunkCache.getChunkHolder(long) —— 该方法在
                //   1.21.1 与 1.21.3 **都不是 public**, getMethods() 永远找不到 ⇒ holder 恒为 null
                //   ⇒ probe 的 ticketLevel/completedLevel 恒为 -1。改用已实证注入成功的 mixin
                //   accessor (@Invoker 包 private getVisibleChunkIfPresent), 全程非阻塞。
                var source = (com.chunkpilot.fabric.mixin.ServerChunkCacheAccessor) level.getChunkSource();
                net.minecraft.server.level.ChunkHolder holder =
                    source.chunkpilot$getVisibleChunkIfPresent(posLong);
                if (holder != null) {
                    holderPresent = true;
                    // v0.11.9 修复: 这里原先也用可读名反射调 getTicketLevel() —— **运行期 MC 是
                    //   intermediary 命名** (getTicketLevel → method_12279), 可读名反射必然失败,
                    //   所以 probe 一直显示 ticketLevel=-1。改成**直接方法调用** (编译期 official 名,
                    //   loom 在打包时自动 remap 成运行期名)。
                    ticketLevel = holder.getTicketLevel();
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

            // completedLevel 在 1.21.3 已不存在 (getCompletedLevel 被移除), 恒为 -1 只会误导;
            // 换成真正有意义的"是否已生成到 FULL"(与生成调度器同口径, 非阻塞)。
            boolean fullReady = isChunkReadyFull(worldId, chunkX, chunkZ);
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.line",
                chunkX, chunkZ, chunkX << 4, chunkZ << 4, loaded, holderPresent,
                ticketLevel, fullReady, statusName));
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
     *
     * 1.20.1 降级 (javap 实证): `ClientInformation` / `clientInformation()` 都是 1.20.2+ 才有的
     *   (1.20.1 只把 `ServerboundClientInformationPacket` 交给 ServerPlayer.updateOptions,
     *    **不保存 language 字段**, ServerPlayer 上既无 language 字段也无 getLanguage()).
     *   → 返回 null, i18n 回退到服务端全局语言 (功能可用, 只是无法做到"按玩家语言").
     *   用反射兜底: 万一某整合/前置提供了 language 字段, 仍能取到.
     */
    @Override
    public String getPlayerLanguage(UUID playerId) {
        try {
            ServerPlayer player = PLAYER_CACHE.get(playerId);
            if (player == null) return null;
            try {
                java.lang.reflect.Method m = player.getClass().getMethod("getLanguage");
                Object v = m.invoke(player);
                if (v instanceof String s) return s;
            } catch (Throwable ignored) { }
            try {
                Class<?> c = player.getClass();
                while (c != null) {
                    try {
                        java.lang.reflect.Field f = c.getDeclaredField("language");
                        f.setAccessible(true);
                        Object v = f.get(player);
                        if (v instanceof String s) return s;
                    } catch (NoSuchFieldException ignored) { }
                    c = c.getSuperclass();
                }
            } catch (Throwable ignored) { }
            return null;
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
            map.put(p.getUUID(), p.getGameProfile().getName());
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
