package com.chunkpilot.forge;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.ChunkPilotCommand;
import com.chunkpilot.forge.network.ForgeNetworkSender;
import com.chunkpilot.forge.platform.ForgePlatform;
import com.chunkpilot.generation.GenerationScheduler;
import com.chunkpilot.network.PlatformNetworkSender;
import com.chunkpilot.platform.PlatformAbstraction;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * ChunkPilot 的 **Forge 1.20.1 (47.x)** 入口.
 *
 * ============================ 与 neoforge/ 模块的关系 ============================
 * 1.20.1 的 Forge 与 1.21.x 的 NeoForge **没有**任何共享的 API 面:
 *   - 事件: `net.minecraftforge.event.*` (不是 net.neoforged.neoforge.event.*),
 *     总线 `net.minecraftforge.eventbus.api.*` (不是 net.neoforged.bus.api.*);
 *   - 生命周期事件是 `ServerStartedEvent/ServerStoppedEvent` (NeoForge 亦同, 但包名不同);
 *   - tick 事件是 `TickEvent.ServerTickEvent` + `event.phase == TickEvent.Phase.START`
 *     (NeoForge 1.21 是 `ServerTickEvent.Pre`); **javap 实证**:
 *     `net.minecraftforge.event.TickEvent$ServerTickEvent` 只有构造函数
 *     `(Phase, BooleanSupplier, MinecraftServer)` 与 `getServer()`, 没有 Pre/Post 子类;
 *   - mod 注解 `net.minecraftforge.fml.common.Mod`, 取 mod 总线走
 *     `FMLJavaModLoadingContext.get().getModEventBus()`;
 *   - 服务器实例 `net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()`;
 *   - mod 列表 `net.minecraftforge.fml.ModList.get().isLoaded(...)`.
 *
 * 本类的骨架与 {@code ChunkPilotNeoForge} 对齐 (同样的 tick 顺序、同样的异常隔离口径),
 * 只把 API 面换成 Forge 47 的。**没有**复制 NeoForge 的任何一处 net.neoforged 依赖。
 */
@Mod(ChunkPilot.MOD_ID)
public class ChunkPilotForge {

    public static final Logger LOGGER = LoggerFactory.getLogger("ChunkPilot");

    public ChunkPilotForge() {
        LOGGER.info("ChunkPilot initializing on Forge 1.20.1 (net.minecraftforge.*)...");

        PlatformAbstraction platform = new ForgePlatform();
        new ChunkPilot(platform);

        // v0.4.0 网络: Forge 47 走 SimpleChannel (NetworkRegistry.newSimpleChannel), 见 ForgeNetworkSender
        ForgeNetworkSender networkSender = new ForgeNetworkSender();
        networkSender.register();

        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp != null) {
            cp.initNetwork(networkSender);
            networkSender.registerServerReceivers(new PlatformNetworkSender.ServerPacketHandler() {
                @Override
                public void onClientCapability(UUID playerId, boolean clientHasCP, int protocolVersion) {
                    ChunkPilot.getInstance().getNetworkDispatcher()
                        .onClientCapability(playerId, clientHasCP, protocolVersion);
                }

                @Override
                public void onClientConfigOverride(UUID playerId, com.chunkpilot.network.ClientConfigOverridePacket packet) {
                    ChunkPilot.getInstance().getNetworkDispatcher()
                        .onClientConfigOverride(playerId, packet);
                }
            });
        }

        // 游戏总线: tick / 玩家登录登出 / 服务器生命周期 / 命令注册
        MinecraftForge.EVENT_BUS.register(this);

        // 客户端 tick (同一个 jar 里同时含服务端与客户端逻辑).
        // 只在物理客户端注册 —— 这个判断用的是 Forge 自己的 FMLEnvironment, 不加载任何客户端类,
        // 所以专用服务端上这条分支不执行, 也不会拖入 net.minecraft.client.* 。
        try {
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                MinecraftForge.EVENT_BUS.register(new com.chunkpilot.forge.client.ChunkPilotForgeClientEvents());
                LOGGER.info("ChunkPilot: client tick listener registered");
            }
        } catch (Throwable t) {
            LOGGER.warn("ChunkPilot: client tick registration skipped: {}", t.toString());
        }

        LOGGER.info("ChunkPilot initialized on Forge 1.20.1");
    }

    // ==================== 生命周期 ====================

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // 扫描已在线玩家 (进服后才启动 mod 的情况: 原登录事件已错过)
        MinecraftServer server = event.getServer();
        var optimizer = ChunkPilot.getInstance().getOptimizer();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            ForgePlatform.registerPlayer(sp);
            if (optimizer != null && sp.level() instanceof ServerLevel sl) {
                optimizer.onPlayerChunkUpdate(sp.getUUID(), sl.hashCode(), sp.getX(), sp.getZ());
            }
        }
        LOGGER.info("ChunkPilot: server started, registered {} existing player(s)",
            server.getPlayerList().getPlayers().size());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("ChunkPilot: server stopped");
    }

    // ==================== 每 tick ====================

    /**
     * Forge 47 的 tick 事件是**一个类 + phase 字段** (javap 实证):
     *   `TickEvent.ServerTickEvent(Phase phase, BooleanSupplier haveTime, MinecraftServer server)`
     * 我们只在 START 阶段跑 (与原 NeoForge 的 ServerTickEvent.Pre 对齐)。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return;

        // v0.2.0 优化器 tick (扇形/ticket/集成)
        cp.getOptimizer().onServerTick();

        // 主动遍历在线玩家位置 — 每 tick 更新 speedTracker
        // 这不依赖 Mixin, RCON tp 也能被检测到
        try {
            var optimizer = cp.getOptimizer();
            if (optimizer != null) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                        if (sp.level() instanceof ServerLevel sl) {
                            optimizer.onPlayerChunkUpdate(sp.getUUID(), sl.hashCode(), sp.getX(), sp.getZ());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 安全: 不影响主 tick
        }

        // v0.3.0 生成器 tick (轨道优先生成器)
        // 异常隔离: 调度器崩了不能让整个 mod 崩
        try {
            ChunkPilotConfig cfg = cp.getConfig();
            if (cfg != null && cfg.generation != null && cfg.generation.enabled) {
                GenerationScheduler gen = cp.getGenerationScheduler();
                if (gen != null) {
                    double mspt = cp.getOptimizer().getLoadController().getSmoothedMspt();
                    long currentTick = cp.getOptimizer().getCurrentTick();
                    gen.onServerTick(
                        cp.getPlatform(),
                        cfg.generation,
                        mspt,
                        cp.getOptimizer().getSpeedTracker(),
                        currentTick
                    );
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[ChunkPilot] GenerationScheduler tick failed: {}", t.toString());
        }

        // v0.4.0: 网络调度器 tick (发送优先级提示)
        try {
            var dispatcher = cp.getNetworkDispatcher();
            if (dispatcher != null) {
                var tracker = cp.getOptimizer().getSpeedTracker();
                var playerPositions = new java.util.HashMap<UUID, int[]>();
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                        playerPositions.put(sp.getUUID(),
                            new int[]{sp.blockPosition().getX() >> 4, sp.blockPosition().getZ() >> 4});
                    }
                    dispatcher.onServerTick(tracker, playerPositions);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[ChunkPilot] NetworkDispatcher tick failed: {}", t.toString());
        }
    }

    // ==================== 玩家 ====================

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ForgePlatform.registerPlayer(sp);
            var dispatcher = ChunkPilot.getInstance().getNetworkDispatcher();
            if (dispatcher != null) {
                dispatcher.onPlayerConnect(sp.getUUID());
            }
            // 1.20.1 **没有 configuration 阶段** (1.20.2+ 才有), 原来的"配置阶段握手"在本版本
            //   永远走不到 ⇒ 改成登录后由服务端主动下发一次 capability (同 fabric/1.20.1 的做法)。
            ForgeNetworkSender.sendCapabilityOnJoin(sp, ChunkPilot.VERSION, ForgeNetworkSender.PROTOCOL_VERSION);
            LOGGER.info("[ChunkPilot] Player logged in: {} (uuid={}, cache size={})",
                sp.getName().getString(), sp.getUUID(), ForgePlatform.getCacheSize());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            var optimizer = ChunkPilot.getInstance().getOptimizer();
            if (optimizer != null && sp.level() instanceof ServerLevel sl) {
                optimizer.onPlayerRemoved(sp.getUUID(), sl.hashCode());
                if (optimizer.getIntegrationManager() != null) {
                    optimizer.getIntegrationManager().onPlayerRemoved(sp.getUUID());
                }
            }
            var dispatcher = ChunkPilot.getInstance().getNetworkDispatcher();
            if (dispatcher != null) {
                dispatcher.onPlayerDisconnect(sp.getUUID());
            }
            ForgePlatform.unregisterPlayer(sp);
        }
    }

    // ==================== 命令 ====================

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        SuggestionProvider<CommandSourceStack> playerSuggest = (context, builder) ->
            SharedSuggestionProvider.suggest(
                context.getSource().getServer().getPlayerList().getPlayers().stream()
                    .map(p -> p.getGameProfile().getName()),
                builder
            );

        dispatcher.register(
            Commands.literal("chunkpilot")
                .requires(src -> src.hasPermission(0))
                .executes(ctx -> runMain(ctx, new String[0]))
                .then(Commands.literal("status")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"status"})))
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"reload"}))
                    .then(Commands.literal("config")
                        .requires(src -> src.hasPermission(4))
                        .executes(ctx -> runMain(ctx, new String[]{"reload", "config"})))
                    .then(Commands.argument("player", StringArgumentType.string())
                        .requires(src -> src.hasPermission(4))
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"reload", StringArgumentType.getString(ctx, "player")}))))
                .then(Commands.literal("config")
                    .requires(src -> src.hasPermission(4))
                    .then(Commands.literal("show").executes(ctx -> runMain(ctx, new String[]{"config", "show"})))
                    .then(Commands.literal("reload").executes(ctx -> runMain(ctx, new String[]{"config", "reload"}))))
                .then(Commands.literal("player")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"player"}))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"player", StringArgumentType.getString(ctx, "name")}))))
                .then(Commands.literal("net")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"net"}))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .requires(src -> src.hasPermission(4))
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"net", StringArgumentType.getString(ctx, "name")}))))
                .then(Commands.literal("gen")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"gen"}))
                    .then(Commands.literal("queue")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "queue"})))
                    .then(Commands.literal("stats")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "stats"})))
                    .then(Commands.literal("status")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "status"}))))
                .then(Commands.literal("send")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"send"}))
                    .then(Commands.literal("status")
                        .executes(ctx -> runMain(ctx, new String[]{"send", "status"}))))
                .then(Commands.literal("debug")
                    .requires(src -> src.hasPermission(4))
                    .executes(ctx -> runMain(ctx, new String[]{"debug"})))
                .then(Commands.literal("diagnose")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"diagnose"})))
                .then(Commands.literal("probe")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"probe"}))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> runMain(ctx, new String[]{
                                "probe",
                                String.valueOf(IntegerArgumentType.getInteger(ctx, "x")),
                                String.valueOf(IntegerArgumentType.getInteger(ctx, "z"))})))))
                .then(Commands.literal("lang")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"lang"}))
                    .then(Commands.argument("code", StringArgumentType.string())
                        .requires(src -> src.hasPermission(2))
                        .suggests((ctx, b) -> {
                            b.suggest("auto");
                            for (String code : com.chunkpilot.i18n.I18n.availableLanguages()) b.suggest(code);
                            return b.buildFuture();
                        })
                        .executes(ctx -> runMain(ctx, new String[]{"lang", StringArgumentType.getString(ctx, "code")}))))
                .then(Commands.literal("help")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"help"})))
                // ===== 1.20.1 专用: /chunkpilot tickstats =====
                // 1.20.1 **没有 /tick 命令** (javap 实证: server jar 里没有 TickCommand —— /tick 是 1.20.3+),
                // 所以 bench 采样器拿不到 MSPT。这里补一个与 `/tick query` **同格式**的输出,
                // 让 bench_run.py 的采样正则一行都不用改。
                .then(Commands.literal("tickstats")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> {
                        for (String line : tickStatsLines(ctx.getSource().getServer())) {
                            ctx.getSource().sendSystemMessage(
                                net.minecraft.network.chat.Component.literal(line));
                        }
                        return 1;
                    }))
        );

        LOGGER.info("ChunkPilot: /chunkpilot command registered (with subcommands + tickstats)");
    }

    /**
     * 用 1.20.1 可用的 `MinecraftServer.tickTimes` (public final long[], 纳秒) +
     * `getAverageTickTime()` (public float, 毫秒) 生成与 1.21.x `/tick query` 同格式的文本。
     * 采样器正则: `Average time per tick:\s*([\d.]+)ms` / `P50:` / `P95:` / `P99:` / `sample:`。
     */
    private static java.util.List<String> tickStatsLines(MinecraftServer server) {
        java.util.List<String> out = new java.util.ArrayList<>();
        long[] times = server.tickTimes;
        int n = 0;
        long[] copy = new long[times.length];
        for (long t : times) {
            if (t > 0) copy[n++] = t;
        }
        copy = java.util.Arrays.copyOf(copy, n);
        java.util.Arrays.sort(copy);
        double avg = server.getAverageTickTime();
        out.add("Target tick rate: 20.0 per second");
        out.add(String.format(java.util.Locale.ROOT,
            "Average time per tick: %.3fms (Target: 50.000ms; %.1f%% of tick)",
            avg, avg / 50.0 * 100.0));
        double p50 = pct(copy, 0.50), p95 = pct(copy, 0.95), p99 = pct(copy, 0.99);
        out.add(String.format(java.util.Locale.ROOT,
            "Percentiles: P50: %.3fms P95: %.3fms P99: %.3fms", p50, p95, p99));
        long over50 = 0;
        for (long t : copy) if (t > 50_000_000L) over50++;
        double max = copy.length == 0 ? 0 : copy[copy.length - 1] / 1e6;
        out.add(String.format(java.util.Locale.ROOT,
            "sample: %d ticks, max: %.3fms, over50ms: %d", copy.length, max, over50));
        return out;
    }

    private static double pct(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.floor(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))] / 1e6;
    }

    /** 把命令源的权限等级压成 CP 认识的三档 (0 / 2 / 4). 控制台恒为 4。 */
    private static int permLevel(CommandSourceStack src) {
        if (src.hasPermission(4)) return 4;
        if (src.hasPermission(3)) return 3;
        if (src.hasPermission(2)) return 2;
        return src.hasPermission(1) ? 1 : 0;
    }

    private static int runMain(CommandContext<CommandSourceStack> ctx, String[] args) {
        var player = ctx.getSource().getPlayer();
        UUID execId = player != null ? player.getUUID() : null;
        String name = player != null ? player.getGameProfile().getName() : "console";
        String resp = ChunkPilotCommand.execute(execId, name, args, permLevel(ctx.getSource()));
        for (String line : resp.split("\n")) {
            ctx.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        }
        return 1;
    }
}
