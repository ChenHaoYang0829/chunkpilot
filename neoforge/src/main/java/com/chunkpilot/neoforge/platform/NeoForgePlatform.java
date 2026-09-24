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
 * NeoForge 平台实现（Mojang official mappings，1.21+；26.1 起 MC **不再混淆**，无需任何映射）
 *
 * 26.1 相对 1.21.11 的增量差异（javap 实证, 见 artifacts/26.1/REPORT.md §API 差异表）:
 *   · `ChunkPos` 变成 **record** ⇒ 字段 `x`/`z` → 访问器 `x()`/`z()`, `toLong()` → `pack()`,
 *     `asLong(int,int)` → `pack(int,int)`；
 *   · ticket 系统与 1.21.11 **一致**（TicketType record / Ticket / addTicket / removeTicketWithRadius
 *     全部沿用 1.21.11 的迁移写法, 26.1 未再改动）。
 */
public class NeoForgePlatform implements PlatformAbstraction {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private static final Map<UUID, ServerPlayer> PLAYER_CACHE = new ConcurrentHashMap<>();

    // ========== 1.21.11 ticket 系统重写 (port/1.21.11) ==========
    //
    // 与 fabric 侧同一套迁移 (javap 实证, 见 REPORT.md §API 差异表):
    //   TicketType 变成 record `TicketType(long timeout, int flags)` (不再泛型, 名字不是字段);
    //   加载等级从 addTicket 参数搬进 `new Ticket(TicketType, int level)`;
    //   `DistanceManager.addTicket/addRegionTicket` 全部删除, 入口改为
    //   `ServerChunkCache.addTicket(Ticket, ChunkPos)` / `addTicketWithRadius(TicketType, ChunkPos, radius)`
    //   / `removeTicketWithRadius(TicketType, ChunkPos, radius)`.
    // flags: 1=PERSIST 2=LOADING 4=SIMULATION 8=KEEP_DIMENSION_ACTIVE 16=CAN_EXPIRE_IF_UNLOADED.
    //   必须带 LOADING(2) —— TicketStorage.getLowestTicket(list, false) 只统计 doesLoad() 的票;
    //   绝不能带 PERSIST(1) —— 自定义 TicketType 未注册进 BuiltInRegistries.TICKET_TYPE,
    //   一旦被 packTickets() 序列化就会炸存档 (packTickets 只打包 persist() 的票).
    // 注意: 两个类型不能有相同的 (timeout, flags), 否则 TicketType.equals 会判为同一类型.
    private static final int CP_TICKET_FLAGS = 2; // FLAG_LOADING

    // ChunkPilot 自定义 ticket 类型 (timeout 沿用原 create(...) 的 expireTicks 值).
    public static final TicketType CHUNKPILOT_TICKET = new TicketType(31L, CP_TICKET_FLAGS);

    // v0.3.0 生成请求用的临时 ticket (低优先级, 仅触发异步生成)
    //   timeout 从 31 → 200 (与 fabric 侧一致): 原实现与 CHUNKPILOT_TICKET 的 31 撞车, 而
    //   1.21.11 的 `TicketType` 是 record, `equals` 只比较 (timeout, flags) —— `TicketStorage`
    //   判"同类型同等级" (`isTicketSameTypeAndLevel`) 与判 FORCED (`type.equals(TicketType.FORCED)`)
    //   都用 **equals** (offset 198 javap 实证), 所以 (timeout,flags) 撞车会让两种票互相误删.
    //   ⚠ 不能图省事用 20: 原版 `PLAYER_SPAWN = register("player_spawn", 20, 2)` ——
    //     (20,2) 正好与它撞车 (TicketType 静态块实证). 200 与原版 9 个类型都不重复:
    //     (20,2) (1,2) (0,6) (0,2) (0,12) (0,15) (300,15) (40,14) (1,18).
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

        // v0.10: 单区块 addTicket, 直接指定加载等级, 支持远处浅层加载.
        // 1.21.11: 等级装进 Ticket; ServerChunkCache.addTicket(Ticket, ChunkPos) 是新的公开入口.
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().addTicket(new Ticket(CHUNKPILOT_TICKET, ticketLevel), pos);
        return true;
    }

    @Override
    public boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        // 1.21.11: DistanceManager.removeTicket 已删除; ServerChunkCache 只公开
        //   removeTicketWithRadius(type, pos, radius), 其内部按
        //   `ChunkLevel.byStatus(FullChunkStatus.FULL) - radius` 反推等级 (javap 实证).
        //   反解 radius = byStatus(FULL) - ticketLevel 即可精确命中加入时的那个 Ticket,
        //   因此**不需要**为 neoforge 新增 TicketStorage accessor mixin
        //   (neoforge 的 refmap 是 build.gradle 里硬编码的, 新增 mixin 要同步改两处).
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        int radius = net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)
                   - ticketLevel;
        world.getChunkSource().removeTicketWithRadius(CHUNKPILOT_TICKET, pos, radius);
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
     * 如果没有 C2ME: 用 vanilla addTicketWithRadius 触发生成 (1.21.11 的 addRegionTicket).
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
            level.getChunkSource().addTicketWithRadius(CHUNKPILOT_GEN_TICKET, pos, 31);
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
            map.put(p.getUUID(), p.getGameProfile().name());  // authlib 7.0.61: GameProfile 是 record
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

    // ==================================================================================
    // v0.11.9 (专项推广: 与 port/1.21.9 `43b49c8` 同口径):
    //     isChunkLoaded / isChunkReadyFull / probeChunk / getOverworldId
    //
    // 为什么必须补 (main `63b6f8c` / PORTING_REPORT §7.5.1 记录的缺口):
    //   这四个方法 NeoForgePlatform **一个都没覆写**, 于是走 PlatformAbstraction 的默认实现:
    //     isChunkLoaded    → 恒 false ⇒ GenerationScheduler 的"跳过已加载区块"优化在 neoforge 上
    //                        完全失效(对已生成的区块反复排队/请求), isChunkReadyFull 也跟着恒 false;
    //     probeChunk       → 返回 "unavailable" ⇒ `/chunkpilot probe` 在 neoforge 上没有输出;
    //     getOverworldId   → 恒 0。
    //   后果: bench_run 的**服务端权威检查** mspt.csv 里 lead_loaded_srv **整列缺失** ⇒
    //   neoforge 无法被"服务端探针"这个行为判据独立验收(只能靠 far_ahead 与 vanilla 对照间接判断);
    //   另外"跳过已加载"失效是**实打实的性能损失**(1.21.9 量测: 同代码 NF 巡航 MSPT 6.35/6.93ms → 3.46/3.10ms)。
    //
    // 本版本 javap 实证 (minecraft-merged 官方映射 jar):
    //   `ServerChunkCache.hasChunk(int,int)`                 → **public** ⇒ 直接调用, 无需反射/accessor
    //                                                          (该方法 1.20.1~1.21.11 均存在且 public)
    //   `ServerChunkCache.getVisibleChunkIfPresent(long)`     → private ⇒ 取 ChunkHolder 只能按名反射;
    //        NeoForge 运行期就是 **Mojang 官方成员名**, 所以按名反射可行
    //        (fabric 侧运行期是 intermediary, 因此那边必须走 mixin accessor);
    //        本版本已无 `getChunkHolder(long)` ⇒ 不要用它。
    //   `ChunkHolder.getTicketLevel()`                       → public
    //   反射失败**只降级票等级一项**, 绝不让 probe 抛异常。
    //
    // 刻意**不输出** cpRequested/cpTicketed 两列: NeoForge 没有 fabric 那套
    //   请求/发票跟踪集合(ChunkMapTrackingView / cpRequested 集合), 硬填 false 只会让
    //   bench_run 的 `lead_cp_ticketed` 变成"假信号"(恒 0), 不如留空 = 无数据。
    // ==================================================================================

    @Override
    public boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;
            // hasChunk = "ChunkHolder 存在且票等级 <= FULL(33)"; 只查表, 不触发加载/生成
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isChunkReadyFull(int worldId, int chunkX, int chunkZ) {
        // 与 fabric 侧同口径: hasChunk 为真即表示已到 FULL 边界 (票等级 <= 33)。
        // 更严格的判定需要 ChunkHolder.getFullChunkFuture, 而它只能反射取 ⇒ 失败面不值得,
        // 该值只用于诊断/探针(与 1.21.9 参考实现一致)。
        return isChunkLoaded(worldId, chunkX, chunkZ);
    }

    @Override
    public int getOverworldId() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return 0;
            // 与 findWorld() 的 worldId 口径一致 (hashCode)
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
            boolean fullReady = isChunkReadyFull(worldId, chunkX, chunkZ);
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

            // 输出格式**复刻 FabricPlatform.probeChunk**: bench_run.py 按 loaded= / ticketLevel= /
            //   levelType= 这些键做正则解析, 键名/顺序必须一致。
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.line",
                chunkX, chunkZ, chunkX << 4, chunkZ << 4, loaded, holderPresent,
                ticketLevel, fullReady, statusName));
            if (best >= 0) {
                sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.nearest",
                    pcx, pcz, best));
            }
            // 这里刻意**不** append "chunkpilot.probe.marks"(cpRequested/cpTicketed) —— 见文件头说明
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.view",
                server.getPlayerList().getViewDistance()));
        } catch (Throwable t) {
            return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.failed",
                String.valueOf(t));
        }
        return sb.toString();
    }

    /**
     * 取"可见表里的 ChunkHolder" —— **仅用于探针的票等级**, 拿不到就降级(ticketLevel=-1)。
     * javap 实证: `ServerChunkCache.getVisibleChunkIfPresent(long)` 是 package-private ⇒ 只能反射;
     * NeoForge 运行期是 Mojang 官方成员名, 所以**按名反射是可行的**(fabric 侧相反, 走 mixin accessor)。
     */
    private static Object chunkpilot$visibleHolder(ServerLevel level, long posLong) {
        try {
            Object source = level.getChunkSource();
            for (Class<?> c = source.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod("getVisibleChunkIfPresent", long.class);
                    m.setAccessible(true);
                    return m.invoke(source, posLong);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
        } catch (Throwable ignored) {
            // 反射被模块系统挡住也只是少一列票等级, 不影响 loaded/探针主体
        }
        return null;
    }

    /** 票等级 → 人类可读的加载类型 (与 FabricPlatform 的同名助手同口径). */
    private static String chunkpilot$levelTypeName(int level) {
        if (level < 0) return "unknown";
        if (level <= 31) return "ENTITY_TICKING";
        if (level == 32) return "BLOCK_TICKING";
        if (level == 33) return "FULL";
        if (level < 44) return "BORDER";
        return "UNLOADED";
    }
}
