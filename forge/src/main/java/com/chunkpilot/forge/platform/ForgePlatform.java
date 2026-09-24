package com.chunkpilot.forge.platform;

import com.chunkpilot.forge.mixin.ServerChunkCacheAccessor;
import com.chunkpilot.forge.util.NonBlockingStats;
import com.chunkpilot.platform.PlatformAbstraction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkPilot 的 **Forge 1.20.1** 平台实现 (Mojang official mappings)。
 *
 * 职责与 {@code NeoForgePlatform} / {@code FabricPlatform} 一致, 差异只在:
 *   1. "当前服务端实例" 由 `net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()` 提供
 *      (Forge 的静态生命周期钩子; NeoForge 1.21 也用它, Fabric 没有所以要自己缓存);
 *   2. `isModLoaded` 走 `net.minecraftforge.fml.ModList`;
 *   3. 1.20.1 的**版本敏感 API** 全部按 javap 实证改过 —— 具体见下面每个方法的注释,
 *      与 `/data/cp-port/artifacts/1.20.1/REPORT.md` §2 的 21 条差异表一致。
 */
public class ForgePlatform implements PlatformAbstraction {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private static final Map<UUID, ServerPlayer> PLAYER_CACHE = new ConcurrentHashMap<>();

    // ChunkPilot 自定义 ticket 类型 (level 31 = FULL_TICKING)
    public static final TicketType<ChunkPos> CHUNKPILOT_TICKET =
        TicketType.create("chunkpilot:forced", java.util.Comparator.comparingLong(ChunkPos::toLong), 31);

    /**
     * v0.11.5c: CP **预生成**统一使用的票据 level = 33 (= 生成到 FULL, 但不参与 block/entity tick)。
     * 31 会与"玩家自身区域"同优先级 FIFO 抢队 → 实测主线程 park 12~15 秒 (jstack 两次同签名)。
     */
    public static final int PREFETCH_TICKET_LEVEL = 33;

    /** v0.3.0 生成请求用的临时 ticket (低优先级, 仅触发异步生成; timeout=200 tick 防止只加不减泄漏) */
    public static final TicketType<ChunkPos> CHUNKPILOT_GEN_TICKET =
        TicketType.create("chunkpilot:gen", java.util.Comparator.comparingLong(ChunkPos::toLong), 200);

    /** 本 tick CP 请求生成的区块集合 (ChunkPos.toLong()) —— 供 probe 诊断与将来的独占生成用。 */
    private static final java.util.Set<Long> requestedChunks = ConcurrentHashMap.newKeySet();
    /** 当前有活跃 CP ticket 的区块集合 —— 同上 (probe 的 cpTicketed 列)。 */
    private static final java.util.Set<Long> ticketedChunks = ConcurrentHashMap.newKeySet();

    public static void markChunkRequested(long chunkPosLong) { requestedChunks.add(chunkPosLong); }
    public static boolean isChunkRequestedByCp(long chunkPosLong) { return requestedChunks.contains(chunkPosLong); }
    public static void clearRequestedChunks() { requestedChunks.clear(); }

    public static void markChunkTicketed(long chunkPosLong) { ticketedChunks.add(chunkPosLong); }
    public static void unmarkChunkTicketed(long chunkPosLong) { ticketedChunks.remove(chunkPosLong); }
    public static boolean isChunkTicketedByCp(long chunkPosLong) { return ticketedChunks.contains(chunkPosLong); }

    /**
     * v0.11.4: 用真实活跃票集合**重建**标记集合 (旧实现只增不减 → 维度切换/自然过期会永久残留)。
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

    public static void registerPlayer(ServerPlayer player) {
        PLAYER_CACHE.put(player.getUUID(), player);
        ChunkPilotNetworkTracker.onPlayerJoin(player.getUUID());
    }

    public static void unregisterPlayer(ServerPlayer player) {
        ChunkPilotNetworkTracker.onPlayerLeave(player.getUUID());
        PLAYER_CACHE.remove(player.getUUID());
    }

    public static int getCacheSize() {
        return PLAYER_CACHE.size();
    }

    private static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    // ==================== 基础 ====================

    @Override
    public boolean isModLoaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String getPlatformName() {
        return "forge";
    }

    @Override
    public int getServerRenderDistance() {
        MinecraftServer server = server();
        if (server == null) return 8;
        return server.getPlayerList().getViewDistance();
    }

    @Override
    public double getCurrentMspt() {
        MinecraftServer server = server();
        if (server == null) return 0;
        // 1.20.1 (javap 实证): MinecraftServer **没有** getAverageTickTimeNanos(),
        //   只有 public float getAverageTickTime() (已是毫秒) 与 public final long[] tickTimes (纳秒)。
        return server.getAverageTickTime();
    }

    @Override
    public double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return max > 0 ? (double) used / max * 100 : 0;
    }

    // ==================== 玩家 ====================

    @Override
    public int[] getPlayerChunkPos(UUID playerId) {
        ServerPlayer player = PLAYER_CACHE.get(playerId);
        if (player == null || !player.isAlive()) return null;
        ChunkPos pos = player.chunkPosition();
        return new int[]{pos.x, pos.z};
    }

    @Override
    public double getPlayerDirection(UUID playerId) {
        return 0; // SpeedTracker 内部从位置变化推算
    }

    @Override
    public double getPlayerSpeed(UUID playerId) {
        return 0; // SpeedTracker 内部计算
    }

    @Override
    public int getPlayerWorldId(UUID playerId) {
        ServerPlayer player = PLAYER_CACHE.get(playerId);
        if (player == null) {
            MinecraftServer server = server();
            if (server != null) player = server.getPlayerList().getPlayer(playerId);
        }
        if (player == null) return 0;
        return player.level().hashCode();
    }

    @Override
    public Object getPlayerObject(UUID playerId) {
        return PLAYER_CACHE.get(playerId);
    }

    @Override
    public Map<UUID, String> getOnlinePlayers() {
        Map<UUID, String> map = new java.util.HashMap<>();
        MinecraftServer server = server();
        if (server == null) return map;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            map.put(p.getUUID(), p.getGameProfile().getName());
        }
        return map;
    }

    /**
     * v0.11.7 i18n: 1.20.1 降级 —— `ClientInformation` / `clientInformation()` 都是 1.20.2+ 的,
     * 1.20.1 的 `ServerPlayer` 既不保存 language 字段也没有 getLanguage() (javap 实证)。
     * → 反射兜底后返回 null, i18n 回退到服务端全局语言 (功能可用, 只是做不到"按玩家语言")。
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
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 票 ====================

    @Override
    public boolean addChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = server();
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        // v0.10: 用 addTicket (单区块) 直接指定加载等级, 支持远处浅层加载
        //   (`DistanceManager.addTicket(TicketType,ChunkPos,int,T)` 在 1.20.1 存在, javap 实证)
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().chunkMap.getDistanceManager()
            .addTicket(CHUNKPILOT_TICKET, pos, ticketLevel, pos);
        markChunkTicketed(pos.toLong());
        return true;
    }

    @Override
    public boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = server();
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().chunkMap.getDistanceManager()
            .removeTicket(CHUNKPILOT_TICKET, pos, ticketLevel, pos);
        unmarkChunkTicketed(pos.toLong());
        return true;
    }

    @Override
    public int getShallowChunkLevel(String statusName) {
        try {
            // 1.20.1: ChunkStatus 在 net.minecraft.world.level.chunk.ChunkStatus
            //   (1.21.2+ 才挪到 ...chunk.status.ChunkStatus); ChunkLevel.byStatus 语义逐位一致
            net.minecraft.world.level.chunk.ChunkStatus status =
                net.minecraft.world.level.chunk.ChunkStatus.byName(statusName);
            if (status == null) return 31;
            return net.minecraft.server.level.ChunkLevel.byStatus(status);
        } catch (Throwable t) {
            return 31;
        }
    }

    // ==================== 生成 ====================

    @Override
    public boolean requestChunkAsync(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = server();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;

            markChunkRequested(ChunkPos.asLong(chunkX, chunkZ));
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
            MinecraftServer server = server();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;

            net.minecraft.world.level.chunk.ChunkStatus target =
                net.minecraft.world.level.chunk.ChunkStatus.FULL;
            if (targetStatus != null && !targetStatus.isEmpty()) {
                net.minecraft.world.level.chunk.ChunkStatus parsed =
                    net.minecraft.world.level.chunk.ChunkStatus.byName(targetStatus);
                if (parsed != null) target = parsed;
            }
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
     * v0.5.1: 是否"已排期加载" (ChunkHolder 存在且票等级 ≤ FULL(33))。
     * 1.20.1 的入口就是 public `ServerChunkCache.hasChunk(int,int)` (javap 实证) ——
     * **直接调用**, 不用按名字反射 (老实现用反射找 hasChunk/isChunkLoaded, 而运行期是 SRG 成员名,
     * 必然恒 false, 已修)。
     */
    @Override
    public boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = server();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v0.11.8: 真正的"已生成到 FULL"判定 (非阻塞)。
     *
     * 1.20.1 移植 (javap 实证):
     *   - `ServerChunkCache.getVisibleChunkIfPresent(long)` 是 **private** → 走本模块的
     *     mixin accessor `ServerChunkCacheAccessor` (@Invoker 会被 loom 正确 remap 到 SRG);
     *   - `ChunkHolder.getFullChunkFuture()` 返回
     *     `CompletableFuture<Either<LevelChunk, ChunkHolder$ChunkLoadingFailure>>`
     *     —— **没有** 1.21.2+ 的 ChunkResult, "成功" = `either.left().isPresent()`。
     */
    @Override
    public boolean isChunkReadyFull(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = server();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;

            ChunkHolder holder = ((ServerChunkCacheAccessor) level.getChunkSource())
                .chunkpilot$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
            if (holder == null) return false;

            java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<
                net.minecraft.world.level.chunk.LevelChunk,
                ChunkHolder.ChunkLoadingFailure>> full = holder.getFullChunkFuture();
            if (full == null || !full.isDone()) return false;
            com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.LevelChunk,
                ChunkHolder.ChunkLoadingFailure> res = full.getNow(null);
            return res != null && res.left().isPresent() && res.left().get() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 探针 ====================

    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ) {
        return probeChunk(worldId, chunkX, chunkZ, null);
    }

    /**
     * v0.11.5e: 只读区块探针 —— **绝不加载区块**。
     * bench 采样器 (bench_run.py Sampler) 每秒用 RCON `chunkpilot probe` 取 `loaded=` /
     * `ticketLevel=` / `cpTicketed=`, 所以这个输出格式必须与其它平台逐字一致。
     */
    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ, java.util.UUID viewerId) {
        StringBuilder sb = new StringBuilder();
        try {
            MinecraftServer server = server();
            if (server == null) {
                return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.server_not_bound");
            }
            ServerLevel level = findWorld(server, worldId);
            if (level == null) {
                return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.world_not_found", worldId);
            }

            long posLong = ChunkPos.asLong(chunkX, chunkZ);
            boolean loaded = isChunkLoaded(worldId, chunkX, chunkZ);
            int ticketLevel = -1;
            boolean holderPresent = false;
            String statusName = "?";
            try {
                ChunkHolder holder = ((ServerChunkCacheAccessor) level.getChunkSource())
                    .chunkpilot$getVisibleChunkIfPresent(posLong);
                if (holder != null) {
                    holderPresent = true;
                    // **直接方法调用** —— 编译期是官方名, loom 打包时 remap 成 SRG 成员名。
                    // (老实现用可读名反射调 getTicketLevel(), 运行期必然失败 → 恒 -1)
                    ticketLevel = holder.getTicketLevel();
                    statusName = levelTypeName(ticketLevel);
                }
            } catch (Throwable ignored) {
            }

            int pcx = 0, pcz = 0;
            double best = -1;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp.level() != level) continue;
                int cx = sp.chunkPosition().x, cz = sp.chunkPosition().z;
                double d = Math.hypot(chunkX - cx, chunkZ - cz);
                if (best < 0 || d < best) {
                    best = d;
                    pcx = cx;
                    pcz = cz;
                }
            }

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
            return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.failed", String.valueOf(t));
        }
        return sb.toString();
    }

    /** 票等级 → 人类可读加载类型 (与原版 ChunkLevel/FullChunkStatus 对应)。 */
    private static String levelTypeName(int level) {
        if (level < 0) return "unknown";
        if (level <= 31) return "ENTITY_TICKING";
        if (level == 32) return "BLOCK_TICKING";
        if (level == 33) return "FULL";
        if (level < 44) return "BORDER(部分生成)";
        return "UNLOADED";
    }

    @Override
    public int getOverworldId() {
        try {
            MinecraftServer server = server();
            if (server == null) return 0;
            return server.overworld().hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public long getParkSubstitutions() {
        return NonBlockingStats.parkSubstitutions();
    }

    // ==================== 网络统计 / 消息 ====================

    @Override
    public NetStats getPlayerNetStats(UUID playerId) {
        ServerPlayer player = PLAYER_CACHE.get(playerId);
        if (player == null) return null;
        try {
            long pingMs = -1;
            long tickLat = -1;

            Object gameConn = null;
            try {
                gameConn = player.getClass().getMethod("connection").invoke(player);
            } catch (Throwable ignored) { }
            Object netConn = null;
            if (gameConn != null) {
                try {
                    netConn = gameConn.getClass().getMethod("connection").invoke(gameConn);
                } catch (Throwable ignored) { }
            }
            if (gameConn != null) {
                pingMs = readLongField(gameConn, "latency");
                if (pingMs < 0) pingMs = readLongField(gameConn, "ping");
            }

            long rxBytes = 0, txBytes = 0;
            Object channel = null;
            if (netConn != null) {
                try {
                    channel = netConn.getClass().getMethod("channel").invoke(netConn);
                } catch (Throwable ignored) { }
            }
            if (channel != null) {
                rxBytes = readLongField(channel, "bytesRead");
                txBytes = readLongField(channel, "bytesWritten");
            }

            long[] tracker = ChunkPilotNetworkTracker.getCurrent(playerId);
            rxBytes += tracker[0];
            txBytes += tracker[1];

            return new NetStats(rxBytes, txBytes, 0, 0, pingMs, tickLat);
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
                } catch (NoSuchFieldException ignored) { }
                cur = cur.getSuperclass();
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public void broadcastMessage(UUID playerId, String message) {
        sendMessage(playerId, message);
    }

    @Override
    public void sendMessage(UUID playerId, String message) {
        net.minecraft.network.chat.Component comp = net.minecraft.network.chat.Component.literal(message);
        MinecraftServer server = server();
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
        LOG.info("[CMD] {} executed: {}", executor, message);
    }

    private static ServerLevel findWorld(MinecraftServer server, int worldId) {
        for (ServerLevel w : server.getAllLevels()) {
            if (w.hashCode() == worldId) return w;
        }
        return null;
    }
}
