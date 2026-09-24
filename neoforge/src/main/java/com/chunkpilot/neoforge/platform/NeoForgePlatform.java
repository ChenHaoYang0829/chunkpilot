package com.chunkpilot.neoforge.platform;

import com.chunkpilot.platform.PlatformAbstraction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
// 1.20.1 (javap 实证): ChunkStatus 在 net.minecraft.world.level.chunk (没有 .status 子包)
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;

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

    // ChunkPilot 自定义 ticket 类型（level 31 = FULL_TICKING）
    public static final TicketType<ChunkPos> CHUNKPILOT_TICKET =
        TicketType.create("chunkpilot:forced", Comparator.comparingLong(ChunkPos::toLong), 31);

    // v0.3.0 生成请求用的临时 ticket (低优先级, 仅触发异步生成)
    public static final TicketType<ChunkPos> CHUNKPILOT_GEN_TICKET =
        TicketType.create("chunkpilot:gen", Comparator.comparingLong(ChunkPos::toLong), 31);

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
        // 1.20.1: 没有 getAverageTickTimeNanos(), 只有 getAverageTickTime() (已是毫秒)
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

        // v0.10: 改用 addTicket (单区块) 直接指定加载等级, 支持远处浅层加载.
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().chunkMap.getDistanceManager()
            .addTicket(CHUNKPILOT_TICKET, pos, ticketLevel, pos);
        return true;
    }

    @Override
    public boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().chunkMap.getDistanceManager()
            .removeTicket(CHUNKPILOT_TICKET, pos, ticketLevel, pos);
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

            // vanilla 路径
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().addRegionTicket(
                CHUNKPILOT_GEN_TICKET, pos, 31, pos);
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
                level.getChunkSource().addRegionTicket(CHUNKPILOT_GEN_TICKET, pos, 31, pos);
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
                level.getChunkSource().addRegionTicket(CHUNKPILOT_GEN_TICKET, pos, 31, pos);
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


    // ==================================================================================
    // 2026-09-24 port/1.20.1: 补齐 PlatformAbstraction 的四个平台方法
    //   (getOverworldId / isChunkLoaded / isChunkReadyFull / probeChunk x2)
    //
    // 为什么必须补: 不补的话 `PlatformAbstraction` 的 default 会让
    //   probeChunk 返回 "probe unavailable"、isChunkLoaded 恒 false ⇒
    //   **bench_run 的 `lead_loaded_srv` 整列是空的**, 于是"CP 到底有没有生效"就无法验证
    //   (主代理在 1.21.10 上中过这一枪: "CP 跑分"其实是 vanilla)。
    //   实现与 port/1.21.3 分支逐行对齐, 只把 1.21.2+ 的 `ChunkResult` 换成 1.20.1 的
    //   `Either<LevelChunk, ChunkLoadingFailure>` (1.20.1 没有 ChunkResult 类)。
    // 全部非阻塞, 不用可读名反射 (fabric 侧被它坑过: ticketLevel 恒 -1)。
    // ==================================================================================

    //   PlatformAbstraction.probeChunk 是 default(返回 "probe unavailable"),
    //   isChunkLoaded 的 default 是 return false ⇒ 探针列在 neoforge 上永远是空的/恒 false。
    //   本段把缺的四个方法补齐(实现与 fabric 侧逐行对齐), 于是跑分脚本在 neoforge 上也能拿到
    //   loaded= / ticketLevel= / fullReady= / levelType= 这些字段。
    //
    // 全部非阻塞: hasChunk(一次 map 查找) + ChunkHolder.getTicketLevel(字段读) +
    //   getFullChunkFuture().getNow(null)(绝不 join)。**不用可读名反射** ——
    //   fabric 侧就是被它坑过(ticketLevel 恒 -1), 这里用 accessor + 直接调用。
    // ==================================================================================

    @Override
    public int getOverworldId() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? 0 : server.overworld().hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;
            // 1.21.3 official: ServerChunkCache.hasChunk(int,int) = "ChunkHolder 存在且票等级 <= FULL(33)"
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isChunkReadyFull(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;
            ChunkHolder holder = ((com.chunkpilot.neoforge.mixin.ServerChunkCacheAccessor) level.getChunkSource())
                .chunkpilot$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
            if (holder == null) return false;
            // 1.20.1 (javap 实证): **没有 ChunkResult**, getFullChunkFuture() 返回
            //   CompletableFuture<Either<LevelChunk, ChunkHolder$ChunkLoadingFailure>>
            //   ⇒ "成功"判定 = either.left().isPresent()
            java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<
                net.minecraft.world.level.chunk.LevelChunk,
                ChunkHolder.ChunkLoadingFailure>> fut = holder.getFullChunkFuture();
            if (fut == null || !fut.isDone()) return false;   // 非阻塞: 没完成就是"没到 FULL"
            com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.LevelChunk,
                ChunkHolder.ChunkLoadingFailure> res = fut.getNow(null);
            return res != null && res.left().isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String chunkpilot$levelTypeName(int level) {
        if (level < 0) return "unknown";
        if (level <= 31) return "ENTITY_TICKING";
        if (level == 32) return "BLOCK_TICKING";
        if (level == 33) return "FULL";
        if (level < 44) return "BORDER(部分生成)";
        return "UNLOADED";
    }

    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ, java.util.UUID viewerId) {
        StringBuilder sb = new StringBuilder();
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.server_not_bound");
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.world_not_found", worldId);

            long posLong = ChunkPos.asLong(chunkX, chunkZ);
            boolean loaded = isChunkLoaded(worldId, chunkX, chunkZ);
            int ticketLevel = -1;
            boolean holderPresent = false;
            String statusName = "?";
            try {
                ChunkHolder holder = ((com.chunkpilot.neoforge.mixin.ServerChunkCacheAccessor) level.getChunkSource())
                    .chunkpilot$getVisibleChunkIfPresent(posLong);
                if (holder != null) {
                    holderPresent = true;
                    ticketLevel = holder.getTicketLevel();
                    statusName = chunkpilot$levelTypeName(ticketLevel);
                }
            } catch (Throwable ignored) {
                // 拿不到 holder 就保留 -1/false, 不影响其它字段
            }

            int pcx = 0, pcz = 0;
            double best = -1;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp.level() != level) continue;
                int cx = sp.chunkPosition().x, cz = sp.chunkPosition().z;
                double d = Math.hypot(chunkX - cx, chunkZ - cz);
                if (best < 0 || d < best) {
                    best = d; pcx = cx; pcz = cz;
                }
            }

            boolean fullReady = isChunkReadyFull(worldId, chunkX, chunkZ);
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.line",
                chunkX, chunkZ, chunkX << 4, chunkZ << 4, loaded, holderPresent,
                ticketLevel, fullReady, statusName));
            if (best >= 0) {
                sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.nearest", pcx, pcz, best));
            }
            // ⚠ 故意**不输出** chunkpilot.probe.marks(cpRequested/cpTicketed):
            //   neoforge 侧没有 fabric 那套 requestedChunks/ticketedChunks 跟踪集合, 硬填 false
            //   就是第二个 3ccbf0c 式的假信号。跑分脚本读不到 cpTicketed 就留空 —— 留空是诚实的。
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.view", getServerRenderDistance()));
        } catch (Throwable t) {
            return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.failed", String.valueOf(t));
        }
        return sb.toString();
    }

    @Override
    public void logCommand(String executor, String message) {
        org.slf4j.LoggerFactory.getLogger("ChunkPilot").info(
            "[CMD] {} executed: {}", executor, message);
    }
}
