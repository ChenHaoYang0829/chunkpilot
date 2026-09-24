package com.chunkpilot.neoforge;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.ChunkPilotCommand;
import com.chunkpilot.generation.GenerationScheduler;
import com.chunkpilot.neoforge.network.NeoForgeNetworkSender;
import com.chunkpilot.neoforge.platform.NeoForgePlatform;
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
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * ChunkPilot NeoForge 入口
 */
@Mod(ChunkPilot.MOD_ID)
public class ChunkPilotNeoForge {

    public static final Logger LOGGER = LoggerFactory.getLogger("ChunkPilot");

    // Forge 47 (1.20.1) 支持 @Mod 构造器注入 IEventBus (与 NeoForge 21.x 同形), 无需改签名。
    public ChunkPilotNeoForge(IEventBus modEventBus) {
        LOGGER.info("ChunkPilot initializing on NeoForge...");

        PlatformAbstraction platform = new NeoForgePlatform();
        new ChunkPilot(platform);

        // v0.4.0: 网络注册
        // 1.20.1 (Forge 47): 网络层降级为 no-op —— 见 NeoForgeNetworkSender 的类注释
        // (1.21.x 的 RegisterPayloadHandlersEvent 在 Forge 47 不存在; 需要 SimpleChannel 重写)
        NeoForgeNetworkSender networkSender = new NeoForgeNetworkSender();
        networkSender.register();
        ChunkPilot.getInstance().initNetwork(networkSender);
        networkSender.registerServerReceivers(new PlatformNetworkSender.ServerPacketHandler() {
            @Override
            public void onClientCapability(UUID playerId, boolean clientHasCP, int protocolVersion) {
                ChunkPilot.getInstance().getNetworkDispatcher().onClientCapability(playerId, clientHasCP, protocolVersion);
            }
            @Override
            public void onClientConfigOverride(UUID playerId, com.chunkpilot.network.ClientConfigOverridePacket packet) {
                ChunkPilot.getInstance().getNetworkDispatcher().onClientConfigOverride(playerId, packet);
            }
        });

        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("ChunkPilot initialized on NeoForge");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // 扫描已在线玩家（进服后才启动 mod 的情况：原登录事件已错过）
        net.minecraft.server.MinecraftServer server = event.getServer();
        var optimizer = ChunkPilot.getInstance().getOptimizer();
        for (net.minecraft.server.level.ServerPlayer sp : server.getPlayerList().getPlayers()) {
            NeoForgePlatform.registerPlayer(sp);
            if (optimizer != null && sp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                optimizer.onPlayerChunkUpdate(sp.getUUID(), sl.hashCode(), sp.getX(), sp.getZ());
            }
        }
        LOGGER.info("ChunkPilot: server started, registered {} existing player(s)", server.getPlayerList().getPlayers().size());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("ChunkPilot: server stopped");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // 1.20.1 (Forge 47): ServerTickEvent 分 START/END 两个 phase, 这里只处理 START
        // (NeoForge 21.x 用的是独立的 ServerTickEvent.Pre)
        if (event.phase != TickEvent.Phase.START) return;
        var cp = ChunkPilot.getInstance();
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
            // 调度器异常不应上抛, 否则下一次 onServerTick 会被 NeoForge 取消订阅
            LOGGER.warn("[ChunkPilot] GenerationScheduler tick failed: {}", t.toString());
        }

        // v0.4.0: 网络调度器 tick (发送优先级提示)
        try {
            var dispatcher = cp.getNetworkDispatcher();
            if (dispatcher != null) {
                var tracker = cp.getOptimizer().getSpeedTracker();
                var playerPositions = new java.util.HashMap<java.util.UUID, int[]>();
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                        playerPositions.put(sp.getUUID(), new int[]{sp.blockPosition().getX() >> 4, sp.blockPosition().getZ() >> 4});
                    }
                    dispatcher.onServerTick(tracker, playerPositions);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[ChunkPilot] NetworkDispatcher tick failed: {}", t.toString());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            NeoForgePlatform.registerPlayer(sp);
            // v0.4.0: 发送 Capability 给客户端
            var dispatcher = ChunkPilot.getInstance().getNetworkDispatcher();
            if (dispatcher != null) {
                dispatcher.onPlayerConnect(sp.getUUID());
            }
            LOGGER.info("[ChunkPilot] Player logged in: {} (uuid={}, cache size={})",
                sp.getName().getString(), sp.getUUID(), NeoForgePlatform.getCacheSize());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            var optimizer = ChunkPilot.getInstance().getOptimizer();
            if (optimizer != null && sp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                optimizer.onPlayerRemoved(sp.getUUID(), sl.hashCode());
                if (optimizer.getIntegrationManager() != null) {
                    optimizer.getIntegrationManager().onPlayerRemoved(sp.getUUID());
                }
            }
            // v0.4.0: 清理网络状态
            var dispatcher = ChunkPilot.getInstance().getNetworkDispatcher();
            if (dispatcher != null) {
                dispatcher.onPlayerDisconnect(sp.getUUID());
            }
            NeoForgePlatform.unregisterPlayer(sp);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        SuggestionProvider<CommandSourceStack> playerSuggest = (context, builder) -> {
            return SharedSuggestionProvider.suggest(
                context.getSource().getServer().getPlayerList().getPlayers().stream()
                    .map(p -> p.getGameProfile().getName()),
                builder
            );
        };

        dispatcher.register(
            Commands.literal("chunkpilot")
                // 顶层要求权限 0（默认 status，所有玩家可调用）
                .requires(src -> src.hasPermission(0))
                // /chunkpilot (默认 status)
                .executes(ctx -> runMain(ctx, new String[0]))
                // /chunkpilot status  - 权限 0（所有玩家可看）
                .then(Commands.literal("status")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"status"})))
                // /chunkpilot reload [player|config]
                //   /chunkpilot reload (强制重载自己周边)  - 权限 0
                //   /chunkpilot reload <player>            - 权限 4（强制重载某玩家区块）
                //   /chunkpilot reload config              - 权限 4（同步服务器配置）
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
                // /chunkpilot config show|reload  - 权限 4（整个子树，同步服务器配置）
                .then(Commands.literal("config")
                    .requires(src -> src.hasPermission(4))
                    .then(Commands.literal("show").executes(ctx -> runMain(ctx, new String[]{"config", "show"})))
                    .then(Commands.literal("reload").executes(ctx -> runMain(ctx, new String[]{"config", "reload"}))))
                // /chunkpilot player [name]  - 权限 2
                .then(Commands.literal("player")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"player"}))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"player", StringArgumentType.getString(ctx, "name")}))))
                // /chunkpilot net [name]  - 不带参数 = 权限 0（看自己）, 带参数 = 权限 4（看别人）
                .then(Commands.literal("net")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"net"}))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .requires(src -> src.hasPermission(4))
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"net", StringArgumentType.getString(ctx, "name")}))))
                // /chunkpilot gen [queue|stats]  - 权限 2
                .then(Commands.literal("gen")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"gen"}))
                    .then(Commands.literal("queue")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "queue"})))
                    .then(Commands.literal("stats")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "stats"})))
                    .then(Commands.literal("status")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "status"}))))
                // /chunkpilot send [status]  - 权限 2
                .then(Commands.literal("send")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"send"}))
                    .then(Commands.literal("status")
                        .executes(ctx -> runMain(ctx, new String[]{"send", "status"}))))
                // /chunkpilot debug  - 权限 4
                .then(Commands.literal("debug")
                    .requires(src -> src.hasPermission(4))
                    .executes(ctx -> runMain(ctx, new String[]{"debug"})))
                // /chunkpilot diagnose  - 权限 0（非 OP 走"只看自己"分支）
                .then(Commands.literal("diagnose")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"diagnose"})))
                // /chunkpilot probe [x z]  - 权限 2
                .then(Commands.literal("probe")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> runMain(ctx, new String[]{"probe"}))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> runMain(ctx, new String[]{
                                "probe",
                                String.valueOf(IntegerArgumentType.getInteger(ctx, "x")),
                                String.valueOf(IntegerArgumentType.getInteger(ctx, "z"))})))))
                // /chunkpilot lang [code]  - 不带参数 = 权限 0, 带参数 = 权限 2
                .then(Commands.literal("lang")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"lang"}))
                    .then(Commands.argument("code", StringArgumentType.string())
                        // 切换语言是**服务端全局**行为 (影响控制台与 auto 模式下所有玩家), 需要 op
                        .requires(src -> src.hasPermission(2))
                        .suggests((ctx, b) -> {
                            b.suggest("auto");
                            for (String code : com.chunkpilot.i18n.I18n.availableLanguages()) b.suggest(code);
                            return b.buildFuture();
                        })
                        .executes(ctx -> runMain(ctx, new String[]{"lang", StringArgumentType.getString(ctx, "code")}))))
                // /chunkpilot help  - 权限 0
                .then(Commands.literal("help")
                    .requires(src -> src.hasPermission(0))
                    .executes(ctx -> runMain(ctx, new String[]{"help"})))
        );

        LOGGER.info("ChunkPilot: /chunkpilot command registered (with subcommands)");
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
        // v0.11.9: 权限判定集中在 ChunkPilotCommand (堵住 /chunkpilot 无参绕过 requires 的问题)
        String resp = ChunkPilotCommand.execute(execId, name, args, permLevel(ctx.getSource()));
        for (String line : resp.split("\n")) {
            ctx.getSource().sendSystemMessage(
                net.minecraft.network.chat.Component.literal(line));
        }
        return 1;
    }
}
