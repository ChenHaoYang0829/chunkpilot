package com.chunkpilot.neoforge.platform;

import com.chunkpilot.platform.PlatformAbstraction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    // ========== port/1.21.10: TicketType / ticket 投递 API 变了 (与 fabric 侧同步) ==========
    //
    // javap 实证 (1.21.10 Mojang official):
    //   TicketType 变成 record (long timeout, int flags), `TicketType.create(...)` 已不存在;
    //   等级封装进 `new Ticket(TicketType, int level)`;
    //   DistanceManager.addTicket/removeTicket 已移除 (挪进 net.minecraft.world.level.TicketStorage,
    //   由 ServerChunkCache 私有字段 ticketStorage 持有);
    //   ServerChunkCache 只保留 public addTicket(Ticket, ChunkPos) /
    //   addTicketWithRadius / removeTicketWithRadius (radius 语义, 不适用显式等级票据).
    // NeoForge 运行时是 Mojang 命名 (remapJar 被禁用), 所以移除路径直接用**按名反射**取
    // ticketStorage 是安全的 (与 fabric 侧不同 —— 那边必须用 mixin accessor).
    public static final TicketType CHUNKPILOT_TICKET = chunkpilot$newTicketType(
        TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);

    // v0.3.0 生成请求用的临时 ticket (低优先级, 仅触发异步生成), 200 ticks 过期.
    public static final TicketType CHUNKPILOT_GEN_TICKET =
        chunkpilot$newTicketType(200L, TicketType.FLAG_LOADING);

    /** port/1.21.10: 预生成票据等级 = 33 (FULL, 不参与 block/entity tick), 与 fabric 侧一致. */
    public static final int PREFETCH_TICKET_LEVEL = 33;

    /**
     * port/1.21.10: 构造 CP 私有 TicketType。
     *
     * **注册不在本方法里做** —— NeoForge 在 mod 构造期 BuiltInRegistries 已经是 frozen,
     * 直接 `Registry.register(...)` 会抛 `IllegalStateException: Registry is already frozen`
     * (2026-09-24 05:21 实测日志)。NeoForge 的正确姿势是 `DeferredRegister` 在
     * `RegisterEvent` 阶段注册, 见 `ChunkPilotNeoForge#TICKET_TYPES`。
     *
     * 为什么必须注册: `Ticket.CODEC` 用 `BuiltInRegistries.TICKET_TYPE.byNameCodec()` 序列化类型
     * (javap 实证), 未注册的类型会让存档保存报错。
     */
    private static TicketType chunkpilot$newTicketType(long timeout, int flags) {
        return new TicketType(timeout, flags);
    }

    /** 缓存 ServerChunkCache.ticketStorage 字段 (Mojang 命名, NeoForge 运行时可直接按名反射). */
    private static java.lang.reflect.Field ticketStorageField = null;
    private static boolean ticketStorageResolved = false;

    private static Object chunkpilot$ticketStorage(net.minecraft.server.level.ServerChunkCache source) {
        if (!ticketStorageResolved) {
            ticketStorageResolved = true;
            try {
                Class<?> cur = source.getClass();
                while (cur != null) {
                    try {
                        java.lang.reflect.Field f = cur.getDeclaredField("ticketStorage");
                        f.setAccessible(true);
                        ticketStorageField = f;
                        break;
                    } catch (NoSuchFieldException ignored) {
                        cur = cur.getSuperclass();
                    }
                }
            } catch (Throwable t) {
                LOG.warn("[ChunkPilot] ticketStorage 反射失败: {}", t.toString());
            }
        }
        if (ticketStorageField == null) return null;
        try {
            return ticketStorageField.get(source);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean chunkpilot$removeTicket(ServerLevel world, TicketType type, ChunkPos pos, int level) {
        try {
            Object storage = chunkpilot$ticketStorage(world.getChunkSource());
            if (storage == null) return false;
            // public TicketStorage.removeTicket(Ticket, ChunkPos)
            storage.getClass()
                .getMethod("removeTicket", net.minecraft.server.level.Ticket.class, ChunkPos.class)
                .invoke(storage, new net.minecraft.server.level.Ticket(type, level), pos);
            return true;
        } catch (Throwable t) {
            LOG.debug("[ChunkPilot] removeTicket failed: {}", t.toString());
            return false;
        }
    }

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
        return new int[]{pos.x, pos.z};
    }

    @Override
    public double getPlayerDirection(UUID playerId) {
        return 0;
    }

    // ================= port/1.21.10: 补齐 main `63b6f8c` 记录的 neoforge 缺口 =================
    //
    // main 实测记录: `NeoForgePlatform` 未覆写 isChunkLoaded / isChunkReadyFull / probeChunk /
    //   getOverworldId ⇒ ① "跳过已生成区块"优化在 neoforge 失效 ② `/chunkpilot probe` 无输出
    //   ③ bench 的 mspt.csv 里 own_loaded_srv / lead_loaded_srv 恒为 0 ⇒ **无法独立验收"CP 是否真的生效"**。
    // 这里按 fabric 侧同口径补齐。NeoForge 运行时是 **Mojang 官方命名** ⇒ 反射按名调用可用
    //   (fabric 侧因为 remap 到 intermediary 才必须走 mixin accessor)。

    @Override
    public boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            ServerLevel level = findWorld(server, worldId);
            if (level == null) return false;
            // ServerChunkCache.hasChunk(x,z) = "ChunkHolder 存在且票等级 <= FULL(33)",直接调用即可
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
            // javap 实证 (1.21.10): ServerChunkCache 上没有 getChunkHolder(long), 只有
            //   `private ChunkHolder getVisibleChunkIfPresent(long)` —— 与 fabric 侧 accessor 同一个方法名。
            Object holder = chunkpilot$invokeByName(level.getChunkSource(), "getVisibleChunkIfPresent",
                new Class<?>[]{long.class}, new Object[]{ChunkPos.asLong(chunkX, chunkZ)});
            if (holder == null) return false;
            Object fut = chunkpilot$invokeByName(holder, "getFullChunkFuture", new Class<?>[0], new Object[0]);
            if (!(fut instanceof java.util.concurrent.CompletableFuture<?> cf)) return false;
            if (!cf.isDone()) return false;
            Object res = cf.getNow(null);
            if (res == null) return false;
            Object ok = chunkpilot$invokeByName(res, "isSuccess", new Class<?>[0], new Object[0]);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int getOverworldId() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? 0 : server.overworld().hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 按名反射调用 (NeoForge 运行时是 Mojang 命名, 可直接按名找)。
     *
     * ⚠ 必须用 **getDeclaredMethod + setAccessible** 并向上遍历父类 —— `ServerChunkCache.getChunkHolder(long)`
     * 与 `ChunkHolder` 的部分方法**不是 public**, 用 `getMethod` 会恒失败(实测: `ticketLevel` 恒 -1)。
     * (fabric 侧因为运行期是 intermediary 命名, 连按名找都不行, 必须走 mixin accessor。)
     */
    private static Object chunkpilot$invokeByName(Object target, String name,
                                                  Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> cur = target.getClass();
            while (cur != null) {
                try {
                    java.lang.reflect.Method m = cur.getDeclaredMethod(name, paramTypes);
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (NoSuchMethodException ignored) {
                    cur = cur.getSuperclass();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ) {
        return probeChunk(worldId, chunkX, chunkZ, null);
    }

    /**
     * port/1.21.10: `/chunkpilot probe` —— 零副作用只读探针,与 fabric 侧同格式
     * (`chunkpilot.probe.line` 的字段顺序必须一致,bench_run 用正则读 `loaded=` / `ticketLevel=`)。
     */
    @Override
    public String probeChunk(int worldId, int chunkX, int chunkZ, java.util.UUID viewerId) {
        StringBuilder sb = new StringBuilder();
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.server_not_bound");
            }
            ServerLevel level = findWorld(server, worldId);
            if (level == null) {
                return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.world_not_found", worldId);
            }
            long posLong = ChunkPos.asLong(chunkX, chunkZ);
            boolean loaded = level.getChunkSource().hasChunk(chunkX, chunkZ);
            int ticketLevel = -1;
            boolean holderPresent = false;
            String statusName = "?";
            try {
                Object holder = chunkpilot$invokeByName(level.getChunkSource(), "getVisibleChunkIfPresent",
                    new Class<?>[]{long.class}, new Object[]{posLong});
                if (holder != null) {
                    holderPresent = true;
                    Object tl = chunkpilot$invokeByName(holder, "getTicketLevel", new Class<?>[0], new Object[0]);
                    if (tl instanceof Integer i) {
                        ticketLevel = i;
                        statusName = chunkpilot$levelTypeName(i);
                    }
                }
            } catch (Throwable ignored) {
            }
            boolean fullReady = isChunkReadyFull(worldId, chunkX, chunkZ);

            int pcx = 0, pcz = 0;
            double best = -1;
            for (var sp : server.getPlayerList().getPlayers()) {
                if (sp.level() != level) continue;
                int cx = sp.chunkPosition().x, cz = sp.chunkPosition().z;
                double d = Math.hypot(chunkX - cx, chunkZ - cz);
                if (best < 0 || d < best) {
                    best = d; pcx = cx; pcz = cz;
                }
            }

            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.line",
                chunkX, chunkZ, chunkX << 4, chunkZ << 4, loaded, holderPresent,
                ticketLevel, fullReady, statusName));
            if (best >= 0) {
                sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.nearest", pcx, pcz, best));
            }
            sb.append(com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.view", getServerRenderDistance()));
        } catch (Throwable t) {
            return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.probe.failed", String.valueOf(t));
        }
        return sb.toString();
    }

    /** 票等级 → 人类可读加载类型 (与 ChunkLevel 常量一致: 31=ENTITY_TICKING / 32=BLOCK_TICKING / 33=FULL)。 */
    private static String chunkpilot$levelTypeName(int level) {
        if (level < 0) return "unknown";
        if (level <= 31) return "ENTITY_TICKING";
        if (level == 32) return "BLOCK_TICKING";
        if (level == 33) return "FULL";
        if (level < 44) return "BORDER";
        return "UNLOADED";
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

        // v0.10: 直接指定加载等级, 支持远处浅层加载.
        // port/1.21.10: DistanceManager.addTicket → ServerChunkCache.addTicket(new Ticket(type, level), pos)
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        world.getChunkSource().addTicket(new net.minecraft.server.level.Ticket(CHUNKPILOT_TICKET, ticketLevel), pos);
        return true;
    }

    @Override
    public boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        ServerLevel world = findWorld(server, worldId);
        if (world == null) return false;

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        chunkpilot$removeTicket(world, CHUNKPILOT_TICKET, pos, ticketLevel);
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
            // port/1.21.10: addRegionTicket 已不存在 → ServerChunkCache.addTicket(new Ticket(type, 33), pos)
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            level.getChunkSource().addTicket(
                new net.minecraft.server.level.Ticket(CHUNKPILOT_GEN_TICKET, PREFETCH_TICKET_LEVEL), pos);
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
                level.getChunkSource().addTicket(
                    new net.minecraft.server.level.Ticket(CHUNKPILOT_GEN_TICKET, PREFETCH_TICKET_LEVEL), pos);
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
                level.getChunkSource().addTicket(
                    new net.minecraft.server.level.Ticket(CHUNKPILOT_GEN_TICKET, PREFETCH_TICKET_LEVEL), pos);
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
}
