package com.chunkpilot.neoforge.platform;

import com.chunkpilot.platform.PlatformAbstraction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 平台实现（Mojang official mappings，1.21+）
 */
public class NeoForgePlatform implements PlatformAbstraction {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private static final Map<UUID, ServerPlayer> PLAYER_CACHE = new ConcurrentHashMap<>();

    // ===== 26.2 ticket 系统 (与 FabricPlatform 同一套迁移, javap 实证) =====
    //   `TicketType` 是 record `TicketType(long timeout, int flags)`(不再泛型、无 name 字段);
    //   加载等级搬进 `Ticket(TicketType, int level)`; `DistanceManager.addTicket/addRegionTicket/
    //   removeTicket` 全部删除, 入口改为 `ServerChunkCache.addTicket(Ticket, ChunkPos)` /
    //   `addTicketWithRadius(TicketType, ChunkPos, radius)` / `removeTicketWithRadius(...)`。
    //   flags: 1=PERSIST 2=LOADING 4=SIMULATION 8=KEEP_DIMENSION_ACTIVE 16=CAN_EXPIRE_IF_UNLOADED。
    //   必须带 LOADING(2)(否则 getLowestTicket(list,false) 看不见这张票 ⇒ 预生成静默失效);
    //   绝不能带 PERSIST(1)(自定义类型没进 BuiltInRegistries.TICKET_TYPE, 被 packTickets 序列化会炸存档)。
    //   两个类型不能用相同 (timeout, flags) —— record 的 equals 只看这两个字段, 撞车会互删。
    //   原版 9 个组合: (20,2) (1,2) (0,6) (0,2) (0,12) (0,15) (300,15) (40,14) (1,18)。
    private static final int CP_TICKET_FLAGS = 2; // FLAG_LOADING

    public static final TicketType CHUNKPILOT_TICKET = new TicketType(31L, CP_TICKET_FLAGS);

    // v0.3.0 生成请求用的临时 ticket (低优先级, 仅触发异步生成)
    // 26.2: 用 200 与 CHUNKPILOT_TICKET 区分(record equals 只看 (timeout,flags))
    public static final TicketType CHUNKPILOT_GEN_TICKET = new TicketType(200L, CP_TICKET_FLAGS);

    // C2ME 兼容: 检测 C2ME 是否加载
    private Boolean c2meCached = null;
    private Object c2meScheduler = null; // TheChunkSystem instance, lazily resolved
    private Method c2meAddTicketMethod = null;
    private Method c2meGetHolderMethod = null; // StatusAdvancingScheduler.getHolder
    private Object c2meTargetStatus = null; // NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING
    private boolean c2meResolved = false;

    public static void registerPlayer(ServerPlayer player) {
        PLAYER_CACHE.put(player.getUUID(), player);
        ChunkPilotNetworkTracker.onPlayerJoin(player.getUUID());
    }

    public static int getCacheSize() {
        return PLAYER_CACHE.size();
    }

    public static void unregisterPlayer(ServerPlayer player) {
        ChunkPilotNetworkTracker.onPlayerLeave(player.getUUID());
        PLAYER_CACHE.remove(player.getUUID());
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public String getPlatformName() {
        return "neoforge";
    }

    @Override
    public int getServerRenderDistance() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 8;
        return server.getPlayerList().getViewDistance();
    }

    @Override
    public double getCurrentMspt() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
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
        return new int[]{pos.x(), pos.z()};
    }

    @Override
    public double getPlayerDirection(UUID playerId) {
        return 0;
    }

    @Override
    public double getPlayerSpeed(UUID playerId) {
        return 0;
    }

    @Override
    public boolean addChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        // v0.10: 单区块 addTicket 直接指定加载等级, 支持远处浅层加载.
        // 26.2: 等级装在 Ticket 里 (DistanceManager.addTicket 已删除)。
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().addTicket(new Ticket(CHUNKPILOT_TICKET, ticketLevel), pos);
        // 标记该 chunk 有活跃 CP ticket (ChunkMapGenerationMixin 放行其生成, 与 fabric 同语义)
        markChunkTicketed(pos.pack());
        return true;
    }

    @Override
    public boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        // 26.2: ServerChunkCache 只公开 removeTicketWithRadius(type,pos,radius), 其内部按
        //   `ChunkLevel.byStatus(FullChunkStatus.FULL) - radius` 反推等级 (javap 实证)。
        //   反解 radius = byStatus(FULL) - ticketLevel 即可精确命中加入时的那张 Ticket。
        int radius = net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)
                   - ticketLevel;
        world.getChunkSource().removeTicketWithRadius(CHUNKPILOT_TICKET, pos, radius);
        // 与 addChunkTicket 对称: 取消该 chunk 的 CP ticket 标记
        unmarkChunkTicketed(pos.pack());
        return true;
    }

    @Override
    public int getShallowChunkLevel(String statusName) {
        try {
            ChunkStatus status = ChunkStatus.byName(statusName);
            if (status == null) return 31;
            return net.minecraft.server.level.ChunkLevel.byStatus(status);
        } catch (Throwable t) {
            return 31; // 解析失败回退 FULL_TICKING
        }
    }

    // ========== v0.3.0 异步生成请求 (D 子任务) ==========

    /**
     * 异步请求生成指定 chunk. GenerationScheduler 在 v0.3.0 通过此方法
     * 向世界调度器注册"希望尽快生成"的需求.
     *
     * 如果检测到 C2ME: 通过 C2ME 的 TheChunkSystem (StatusAdvancingScheduler)
     * 注入 EXTERNAL ticket, 目标状态 SERVER_ACCESSIBLE_CHUNK_SENDING,
     * 走 C2ME 的并行生成管道.
     *
     * 如果没有 C2ME: 用 vanilla addRegionTicket 触发生成.
     */
    @Override
    public boolean requestChunkAsync(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;

            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;

            // 检测 C2ME
            if (isC2MEPresent()) {
                return requestChunkAsyncC2ME(level, chunkX, chunkZ);
            }

            // vanilla 路径 (26.2: addRegionTicket 已删除, 用 addTicketWithRadius;
            //   radius=31 ⇒ ChunkLevel.byStatus(FULL) - 31 = level 2, 与 1.21.x 的 addRegionTicket(...,31,...)
            //   在字节码层面逐位相同 ⇒ 刻意保持取值不变)
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().addTicketWithRadius(CHUNKPILOT_GEN_TICKET, pos, 31);
            markChunkRequested(pos.pack());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 通过 C2ME 的 TheChunkSystem 请求异步生成.
     * 利用 C2ME 的并行调度器, 不绕过其 chunk 管道.
     */
    @SuppressWarnings("unchecked")
    private boolean requestChunkAsyncC2ME(ServerLevel level, int chunkX, int chunkZ) {
        try {
            // 获取 ChunkMap
            Object chunkMap = level.getChunkSource().chunkMap;
            if (chunkMap == null) return false;

            // 懒加载 C2ME 的 TheChunkSystem 和相关方法 (每个 server 实例只解析一次)
            if (!c2meResolved) {
                resolveC2MEAPI(chunkMap);
                c2meResolved = true;
            }
            if (c2meScheduler == null || c2meAddTicketMethod == null || c2meTargetStatus == null) {
                // C2ME API 解析失败, 回退到 vanilla
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                level.getChunkSource().addTicketWithRadius(CHUNKPILOT_GEN_TICKET, pos, 31);
                return true;
            }

            ChunkPos cpos = new ChunkPos(chunkX, chunkZ);

            // 方案1: 先检查该 chunk 是否已有 holder (已有 ticket)
            // 如果 C2ME 已经在处理这个 chunk, 跳过避免 Ticket already exists 异常
            if (c2meGetHolderMethod != null) {
                Object existingHolder = c2meGetHolderMethod.invoke(c2meScheduler, cpos);
                if (existingHolder != null) {
                    // chunk 已在 C2ME 调度器中, 不需要重复注入 ticket
                    return true;
                }
            }

            // 调用 TheChunkSystem.addTicket(ChunkPos, TicketType, Object, ItemStatus, Runnable)
            c2meAddTicketMethod.invoke(c2meScheduler,
                cpos,                          // key (ChunkPos)
                ItemTicketType_EXTERNAL,       // ticket type (EXTERNAL)
                cpos,                          // ticket object
                c2meTargetStatus,              // target status (SERVER_ACCESSIBLE_CHUNK_SENDING)
                NO_OP_CALLBACK                 // callback (no-op)
            );
            return true;
        } catch (Throwable t) {
            LOG.debug("[Gen] C2ME requestChunkAsync failed for ({},{}): {}", chunkX, chunkZ, t.toString());
            // 回退到 vanilla
            try {
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                level.getChunkSource().addTicketWithRadius(CHUNKPILOT_GEN_TICKET, pos, 31);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    // C2ME 反射缓存常量
    private static Object ItemTicketType_EXTERNAL = null;
    private static final Runnable NO_OP_CALLBACK = () -> {};

    /**
     * 解析 C2ME 的 TheChunkSystem 和相关 API.
     * 通过反射获取, 避免编译时依赖 C2ME.
     */
    private void resolveC2MEAPI(Object chunkMap) {
        try {
            // 1. 通过 IChunkSystemAccess 接口拿 TheChunkSystem
            Class<?> iChunkSystemAccess = Class.forName("com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess");
            Method getTheChunkSystem = iChunkSystemAccess.getMethod("c2me$getTheChunkSystem");
            c2meScheduler = getTheChunkSystem.invoke(chunkMap);
            if (c2meScheduler == null) {
                LOG.warn("[ChunkPilot] C2ME detected but TheChunkSystem is null, falling back to vanilla");
                return;
            }

            // 2. 获取 NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING
            Class<?> newChunkStatusClass = Class.forName("com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus");
            java.lang.reflect.Field sacsField = newChunkStatusClass.getField("SERVER_ACCESSIBLE_CHUNK_SENDING");
            c2meTargetStatus = sacsField.get(null);

            // 3. 获取 ItemTicket.TicketType.EXTERNAL
            Class<?> ticketTypeClass = Class.forName("com.ishland.flowsched.scheduler.ItemTicket$TicketType");
            java.lang.reflect.Field externalField = ticketTypeClass.getField("EXTERNAL");
            ItemTicketType_EXTERNAL = externalField.get(null);

            // 4. 找到 addTicket(ChunkPos, TicketType, Object, ItemStatus, Runnable) 方法
            Class<?> itemStatusClass = Class.forName("com.ishland.flowsched.scheduler.ItemStatus");
            c2meAddTicketMethod = c2meScheduler.getClass().getMethod(
                "addTicket",
                Object.class,          // K (ChunkPos)
                ticketTypeClass,       // TicketType
                Object.class,          // ticket object
                itemStatusClass,       // target ItemStatus
                Runnable.class         // callback
            );

            // 5. 找到 getHolder(ChunkPos) 方法 — 用于检查 chunk 是否已在调度器中
            c2meGetHolderMethod = c2meScheduler.getClass().getMethod(
                "getHolder",
                Object.class           // K (ChunkPos)
            );

            LOG.info("[ChunkPilot] C2ME integration active: using TheChunkSystem for async chunk generation");
        } catch (Throwable t) {
            LOG.warn("[ChunkPilot] Failed to resolve C2ME API: {}, falling back to vanilla tickets", t.toString());
            c2meScheduler = null;
            c2meAddTicketMethod = null;
            c2meGetHolderMethod = null;
            c2meTargetStatus = null;
        }
    }

    /**
     * 检测 C2ME 是否加载 (缓存结果)
     */
    private boolean isC2MEPresent() {
        if (c2meCached == null) {
            c2meCached = ModList.get().isLoaded("c2me");
        }
        return c2meCached;
    }

    private static ServerLevel findWorld(MinecraftServer server, int worldId) {
        for (ServerLevel w : server.getAllLevels()) {
            if (w.hashCode() == worldId) return w;
        }
        return null;
    }

    @Override
    public int getPlayerWorldId(UUID playerId) {
        ServerPlayer player = PLAYER_CACHE.get(playerId);
        if (player == null) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                player = server.getPlayerList().getPlayer(playerId);
            }
        }
        if (player == null) return 0;
        return player.level().hashCode();
    }

    @Override
    public Object getPlayerObject(UUID playerId) {
        return PLAYER_CACHE.get(playerId);
    }

    @Override
    public NetStats getPlayerNetStats(UUID playerId) {
        Object player = PLAYER_CACHE.get(playerId);
        if (player == null) return null;
        try {
            long pingMs = -1;
            long tickLat = -1;

            // 取 ServerPlayer.connection.connection (Mojang 1.21.1)
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

            // ping 字段（vanilla 1.21 没有公开字段，readLongField 找不到就回 -1）
            if (gameConn != null) {
                pingMs = readLongField(gameConn, "latency");
                if (pingMs < 0) pingMs = readLongField(gameConn, "ping");
            }

            // Netty channel 实时 buffer
            long rxBytes = 0, txBytes = 0, rxPackets = 0, txPackets = 0;
            Object channel = null;
            if (netConn != null) {
                try {
                    channel = netConn.getClass().getMethod("channel").invoke(netConn);
                } catch (Throwable ignored) {}
            }
            if (channel != null) {
                try {
                    rxBytes = readLongField(channel, "bytesRead");
                    txBytes = readLongField(channel, "bytesWritten");
                } catch (Throwable ignored) {}
            }

            // + mixin 累计的真实字节数
            long[] tracker = ChunkPilotNetworkTracker.getCurrent(playerId);
            rxBytes += tracker[0];
            txBytes += tracker[1];

            return new NetStats(rxBytes, txBytes, rxPackets, txPackets, pingMs, tickLat);
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
    public Map<UUID, String> getOnlinePlayers() {
        Map<UUID, String> map = new java.util.HashMap<>();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
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
        net.minecraft.network.chat.Component comp = net.minecraft.network.chat.Component.literal(message);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (playerId == null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(comp);
            }
        } else {
            ServerPlayer target = PLAYER_CACHE.get(playerId);
            if (target == null) target = server.getPlayerList().getPlayer(playerId);
            if (target != null) target.sendSystemMessage(comp);
        }
    }

    @Override
    public void logCommand(String executor, String message) {
        org.slf4j.LoggerFactory.getLogger("ChunkPilot").info(
            "[CMD] {} executed: {}", executor, message);
    }

    // ========== port/26.2: CP 请求/持票 chunk 记账 (供 ChunkMapGenerationMixin 判据用) ==========
    //
    // 为什么需要 (与 1.21.5 的 neoforge 侧同一处整改): fabric 的 `ChunkMapGenerationMixin` 用
    //   `FabricPlatform.isChunkRequestedByCp / isChunkTicketedByCp` 判断"这个生成任务是不是 CP 自己发起的";
    //   neoforge 平台此前**没有**这两个集合 ⇒ `generation.exclusiveGenerationNoC2me` 在 neoforge 上只能空转
    //   (PORTING_REPORT §7.5.1a 点名的缺口)。
    //
    // 安全性: 本记账**只由 CP 自己的 requestChunkAsync / addChunkTicket / removeChunkTicket 写入**,
    //   不触碰任何原版字段/方法/返回值, 也不改变票的语义; 关掉 `[generation] enabled` 之后
    //   `ChunkMapGenerationMixin` 根本不会读它。语义与 fabric 侧逐条一致:
    //   requested = 本 tick 的请求集合 (每 tick 开头由入口清空); ticketed = 跨 tick 的活跃 CP 票集合。
    private static final java.util.Set<Long> requestedChunks =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Long> ticketedChunks =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 标记某 chunk 为 CP 想生成的 (由 requestChunkAsync 调用). */
    public static void markChunkRequested(long chunkPosLong) { requestedChunks.add(chunkPosLong); }

    /** 清空本 tick 的 CP 请求集合 (每 tick 开头由 ChunkPilotNeoForge 调用). */
    public static void clearRequestedChunks() { requestedChunks.clear(); }

    /** 查询某 chunk 是否被 CP 请求生成 (ChunkMapGenerationMixin 调用). */
    public static boolean isChunkRequestedByCp(long chunkPosLong) { return requestedChunks.contains(chunkPosLong); }

    /** 标记某 chunk 有活跃 CP ticket (由 addChunkTicket 调用). */
    public static void markChunkTicketed(long chunkPosLong) { ticketedChunks.add(chunkPosLong); }

    /** 取消某 chunk 的 CP ticket 标记 (由 removeChunkTicket 调用). */
    public static void unmarkChunkTicketed(long chunkPosLong) { ticketedChunks.remove(chunkPosLong); }

    /** 查询某 chunk 是否有活跃 CP ticket (ChunkMapGenerationMixin 调用). */
    public static boolean isChunkTicketedByCp(long chunkPosLong) { return ticketedChunks.contains(chunkPosLong); }

    /** 与 fabric 同法: 用真实活跃票集合重建标记 (旧实现只增不减会留下永久残留). */
    @Override
    public void rebuildCpTicketMarks(java.util.Collection<Long> activeChunkPositions) {
        ticketedChunks.clear();
        if (activeChunkPositions != null && !activeChunkPositions.isEmpty()) {
            ticketedChunks.addAll(activeChunkPositions);
        }
    }

    /** /chunkpilot status 用: 被替换掉的 park 次数 (与 fabric 同名指标). */
    @Override
    public long getParkSubstitutions() {
        return com.chunkpilot.neoforge.util.NonBlockingStats.parkSubstitutions();
    }

    // ==================================================================================
    // port/26.2 补齐: isChunkLoaded / isChunkReadyFull / probeChunk / getOverworldId
    //
    // 为什么必须补 (任务书点名 + PORTING_REPORT §7.5.1 的已知缺口):
    //   这四个方法 NeoForgePlatform **一个都没覆写**, 于是落到 PlatformAbstraction 的默认实现:
    //     isChunkLoaded    → 恒 false  ⇒ GenerationScheduler 的"跳过已加载区块"优化在 neoforge 上失效
    //                                   (对已经生成好的区块反复请求), isChunkReadyFull 跟着恒 false;
    //     probeChunk       → "probe unsupported on this platform" ⇒ `/chunkpilot probe` 无输出;
    //     getOverworldId   → 恒 0。
    //   后果(实测): bench 的服务端权威列 `own_loaded_srv` / `lead_loaded_srv` **恒为 0** ⇒
    //   CP 轮与 vanilla 轮**不可区分** ⇒ neoforge 无法被机械判据独立验收 (AGENT_BRIEF §8.5 的坑)。
    //
    // 26.2 的取数依据 (javap 实证, 内层 server jar):
    //   `ServerChunkCache.hasChunk(int,int)`            → public, 直接调用;
    //   `ServerChunkCache.getVisibleChunkIfPresent(long)` → private, 只能反射取 ChunkHolder。
    //     ⚠ 26.x 起 MC **不再混淆**(运行期 == 编译期 == Mojang 名), 所以按名反射是**可靠**的
    //       —— 这也正是 1.21.x 时代 fabric 侧必须走 mixin accessor、而 neoforge 侧可以按名反射的原因。
    //     反射失败只降级"票等级"这一个诊断字段, 不影响 loaded/fullReady。
    // ==================================================================================

    @Override
    public boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;
            // hasChunk = "ChunkHolder 存在且票等级 <= FULL", 只查表不加载
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isChunkReadyFull(int worldId, int chunkX, int chunkZ) {
        // 与 fabric 侧同口径: hasChunk 为真即已到 FULL 边界。
        // 更严格的判定要 ChunkHolder.getFullChunkFuture() (反射面更大), 而该值只用于诊断/探针。
        return isChunkLoaded(worldId, chunkX, chunkZ);
    }

    @Override
    public int getOverworldId() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return 0;
            return server.overworld().hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ) {
        return probeChunk(worldId, chunkX, chunkZ, null);
    }

    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ, UUID viewerId) {
        StringBuilder sb = new StringBuilder();
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return com.chunkpilot.i18n.I18n.trFor(viewerId,
                "chunkpilot.probe.server_not_bound");
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return com.chunkpilot.i18n.I18n.trFor(viewerId,
                "chunkpilot.probe.world_not_found", worldId);

            long posLong = ChunkPos.pack(chunkX, chunkZ);
            boolean loaded = isChunkLoaded(worldId, chunkX, chunkZ);
            int ticketLevel = -1;
            boolean holderPresent = false;
            String statusName = "?";
            Object holder = chunkpilot$visibleHolder(level, posLong);
            if (holder instanceof net.minecraft.server.level.ChunkHolder ch) {
                holderPresent = true;
                ticketLevel = ch.getTicketLevel();
                statusName = chunkpilot$levelTypeName(ticketLevel);
            }

            int pcx = 0, pcz = 0;
            double best = -1;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp.level() != level) continue;
                int cx = sp.chunkPosition().x(), cz = sp.chunkPosition().z();
                double d = Math.hypot(chunkX - cx, chunkZ - cz);
                if (best < 0 || d < best) { best = d; pcx = cx; pcz = cz; }
            }

            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.line",
                chunkX, chunkZ, chunkX << 4, chunkZ << 4, loaded, holderPresent,
                ticketLevel, loaded, statusName));
            if (best >= 0) {
                sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.nearest",
                    pcx, pcz, best));
            }
            // 刻意**不输出** cpRequested/cpTicketed: NeoForge 侧没有 fabric 那套请求/发票跟踪集合,
            //   硬填 false 就是"第二个假信号"(PORTING_REPORT §7.5.0c 的同一条结论)。
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.view",
                server.getPlayerList().getViewDistance()));
        } catch (Throwable t) {
            return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.failed",
                String.valueOf(t));
        }
        return sb.toString();
    }

    /**
     * 取"可见表里的 ChunkHolder"。
     * 26.2 javap: `ServerChunkCache.getVisibleChunkIfPresent(long)` 是 private ⇒ 只能反射;
     * 26.x 运行期就是 Mojang 官方名, 所以按名反射可行。失败返回 null (只少一个诊断字段)。
     */
    private static Object chunkpilot$visibleHolder(ServerLevel level, long posLong) {
        try {
            Object source = level.getChunkSource();
            for (Class<?> c = source.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("getVisibleChunkIfPresent", long.class);
                    m.setAccessible(true);
                    return m.invoke(source, posLong);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
        } catch (Throwable ignored) {
            // 反射被模块系统挡住也不影响 loaded/探针主体
        }
        return null;
    }

    /** 票等级 → 人类可读的加载类型 (与 FabricPlatform 同名助手一致). */
    private static String chunkpilot$levelTypeName(int level) {
        if (level < 0) return "unknown";
        if (level <= 31) return "ENTITY_TICKING";
        if (level == 32) return "BLOCK_TICKING";
        if (level == 33) return "FULL";
        if (level < 44) return "BORDER";
        return "UNLOADED";
    }
}
