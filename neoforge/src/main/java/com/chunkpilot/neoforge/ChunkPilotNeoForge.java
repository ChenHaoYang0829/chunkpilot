package com.chunkpilot.neoforge;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.ChunkPilotCommand;
import com.chunkpilot.generation.GenerationScheduler;
import com.chunkpilot.neoforge.platform.NeoForgePlatform;
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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * ChunkPilot NeoForge 入口
 */
@Mod(ChunkPilot.MOD_ID)
public class ChunkPilotNeoForge {

    public static final Logger LOGGER = LoggerFactory.getLogger("ChunkPilot");

    public ChunkPilotNeoForge(IEventBus modEventBus) {
        LOGGER.info("ChunkPilot initializing on NeoForge...");

        PlatformAbstraction platform = new NeoForgePlatform();
        new ChunkPilot(platform);

        // 客户端物理侧: 只留一条启动日志 (1.0.0 起客户端无渲染侧功能)
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            com.chunkpilot.neoforge.client.ChunkPilotNeoForgeClient.init();
        }

        NeoForge.EVENT_BUS.register(this);

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
    public void onServerTick(ServerTickEvent.Pre event) {
        var cp = ChunkPilot.getInstance();
        if (cp == null) return;

        // v0.2.0 优化器 tick (扇形/ticket/集成)
        cp.getOptimizer().onServerTick();

        // 二阶段 B2: 清空"本 tick CP 请求集合" —— 与 fabric 侧 ServerInitializer 同一时机
        //   (每 tick 开头清, 之后由 GenerationScheduler → requestChunkAsync 重新填充).
        //   ChunkMapGenerationMixin 用它判断"这个生成任务要不要提优先级". 纯记账, 无原版影响.
        try {
            com.chunkpilot.neoforge.platform.NeoForgePlatform.clearRequestedChunks();
        } catch (Throwable t) {
            // 安全: 不影响主 tick
        }

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

        // 二阶段 B2 安全网 (与 fabric 侧 ServerInitializer v0.9.0 同源):
        //   始终把玩家脚下周围 r=2 的区块标记为"CP 请求", 即使 CP 生成器这一 tick 什么都没请求,
        //   玩家所在区块族也**永远不会**被 ChunkMapGenerationMixin 判为"非 CP 请求"而失去优先级.
        //   (玩家不能站在未生成的 chunk 上; r=2 覆盖落地/转向/重生时周围一圈.)
        try {
            MinecraftServer _srv = ServerLifecycleHooks.getCurrentServer();
            if (_srv != null) {
                for (ServerPlayer sp : _srv.getPlayerList().getPlayers()) {
                    if (sp.level() instanceof ServerLevel) {
                        int cx = sp.chunkPosition().x;
                        int cz = sp.chunkPosition().z;
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                com.chunkpilot.neoforge.platform.NeoForgePlatform.markChunkRequested(
                                    net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            NeoForgePlatform.registerPlayer(sp);
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
