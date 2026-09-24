package com.chunkpilot.fabric;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.core.ChunkPilotCommand;
import com.chunkpilot.fabric.network.FabricNetworkSender;
import com.chunkpilot.fabric.platform.FabricPlatform;
import com.chunkpilot.network.PlatformNetworkSender;
import com.chunkpilot.platform.PlatformAbstraction;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;

/**
 * 服务端初始化逻辑 (延迟加载, 避免客户端类加载时触发服务端类依赖)
 *
 * 此类只有在服务端环境才会被加载, 因此可以安全引用服务端类.
 */
public class ServerInitializer {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ChunkPilot");

    /** v0.11.4: 静默异常计数 (低频留痕用) */
    private static long playerUpdateFailures = 0;
    private static long heartbeatFailures = 0;


    public static void initialize() {
        ChunkPilotFabric.LOGGER.info("ChunkPilot: server environment, initializing...");

        PlatformAbstraction platform = new FabricPlatform();
        new ChunkPilot(platform);

        // 绑定/解绑 MinecraftServer 到 FabricPlatform
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            FabricPlatform.setServer(server);
            FabricNetworkSender networkSender = new FabricNetworkSender();
            ChunkPilot.getInstance().initNetwork(networkSender);
            networkSender.registerServerReceivers(new PlatformNetworkSender.ServerPacketHandler() {
                @Override
                public void onClientCapability(java.util.UUID playerId, boolean clientHasCP, int protocolVersion) {
                    ChunkPilot.getInstance().getNetworkDispatcher().onClientCapability(playerId, clientHasCP, protocolVersion);
                }
                @Override
                public void onClientConfigOverride(java.util.UUID playerId, com.chunkpilot.network.ClientConfigOverridePacket packet) {
                    ChunkPilot.getInstance().getNetworkDispatcher().onClientConfigOverride(playerId, packet);
                }
            });
            ChunkPilotFabric.LOGGER.info("ChunkPilot: server bound, network initialized");
            // 启动冻结检测器 (诊断: 主线程卡住时 dump 玩家周边 chunk 状态)
            try { com.chunkpilot.fabric.platform.FabricPlatform.startFreezeDetector(); }
            catch (Throwable ignored) {}
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            FabricPlatform.setServer(null);
            ChunkPilotFabric.LOGGER.info("ChunkPilot: server unbound");
        });

        // 每 tick 推进 ChunkPilot
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            var cp = ChunkPilot.getInstance();
            if (cp == null) return;

            // 冻结检测器心跳: 每 tick 无条件更新 (无玩家时也更新, 避免误报)
            try { com.chunkpilot.fabric.platform.FabricPlatform.onServerTickHeartbeat(); }
            catch (Throwable ignored) {}

            // v0.9.0: 清空本 tick 的 CP 请求集合.
            //   CP 的 onServerTick 会通过 requestChunkAsync 重新填充,
            //   ChunkMapGenerationMixin 在 runGenerationTask 里查这个集合决定放行/跳过.
            //   这样每 tick 的生成完全由 CP 权重公式决定, 原版自动生成被停.
            try {
                com.chunkpilot.fabric.platform.FabricPlatform.clearRequestedChunks();
            } catch (Throwable ignored) {}

            // 优化器 tick (累加 currentTick)
            cp.getOptimizer().onServerTick();

            // 主动遍历在线玩家位置 — 每 tick 更新 speedTracker
            // 不依赖 Mixin, 确保玩家能动也能被检测到
            try {
                var optimizer = cp.getOptimizer();
                if (optimizer != null) {
                    for (var sp : server.getPlayerList().getPlayers()) {
                        if (sp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                            optimizer.onPlayerChunkUpdate(sp.getUUID(), sl.hashCode(), sp.getX(), sp.getZ());
                            // 冻结检测器心跳: 记录玩家所在 chunk
                            try {
                                com.chunkpilot.fabric.platform.FabricPlatform.onServerTickHeartbeat(
                                    sl.hashCode(), sp.chunkPosition().x(), sp.chunkPosition().z());
                            } catch (Throwable t) {
                                heartbeatFailures++;
                                if (heartbeatFailures <= 3 || heartbeatFailures % 600 == 0) {
                                    LOG.warn("[ChunkPilot] 冻结检测心跳失败 (第 {} 次): {}", heartbeatFailures, t.toString());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // 安全: 不影响主 tick. v0.11.4: 不再静默 — 这里吞掉的异常会让 CP 整个停止工作
                //   却毫无痕迹 (飞行测试时曾长期无人发现). 低频留痕, 避免每 tick 刷日志.
                playerUpdateFailures++;
                if (playerUpdateFailures <= 3 || playerUpdateFailures % 600 == 0) {
                    LOG.warn("[ChunkPilot] 玩家区块更新失败 (第 {} 次): {}", playerUpdateFailures, t.toString());
                    if (playerUpdateFailures <= 3) {
                        LOG.warn("[ChunkPilot] 异常栈:", t);
                    }
                }
            }

            // v0.5.1 修复: 接上 generationScheduler (v0.5.0 没接, 导致 genQ 恒 0)
            // v0.3.0 neoforge 已经在 ChunkPilotNeoForge.onServerTick 调过这个
            try {
                var cfg = cp.getConfig();
                if (cfg != null && cfg.generation != null && cfg.generation.enabled) {
                    var gen = cp.getGenerationScheduler();
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
                org.slf4j.LoggerFactory.getLogger("ChunkPilot").warn(
                    "[ChunkPilot] GenerationScheduler tick failed: {}", t.toString());
            }

            // v0.9.0 安全网: 始终把玩家脚下周围一小片(正方形 r=2)标记为 CP 请求.
            //   即使 CP 生成器因某种原因没请求到这些 chunk, 也保证它们不被
            //   ChunkMapGenerationMixin 过滤掉 (玩家不能站在未生成的 chunk 上;
            //   r=2 覆盖玩家落地/转向/重生时周围一圈).
            try {
                for (var sp : server.getPlayerList().getPlayers()) {
                    if (sp.level() instanceof net.minecraft.server.level.ServerLevel) {
                        int cx = sp.chunkPosition().x();
                        int cz = sp.chunkPosition().z();
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                com.chunkpilot.fabric.platform.FabricPlatform.markChunkRequested(
                                    net.minecraft.world.level.ChunkPos.pack(cx + dx, cz + dz));
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // v0.4.0: 网络调度器 tick (发送优先级提示)
            if (cp.getNetworkDispatcher() != null) {
                var tracker = cp.getOptimizer().getSpeedTracker();
                var playerPositions = new java.util.HashMap<java.util.UUID, int[]>();
                for (var sp : server.getPlayerList().getPlayers()) {
                    playerPositions.put(sp.getUUID(), new int[]{sp.blockPosition().getX() >> 4, sp.blockPosition().getZ() >> 4});
                }
                cp.getNetworkDispatcher().onServerTick(tracker, playerPositions);
            }
        });

        // 玩家进出事件
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            FabricPlatform.registerPlayer(handler.player);
            var dispatcher = ChunkPilot.getInstance().getNetworkDispatcher();
            if (dispatcher != null) {
                dispatcher.onPlayerConnect(handler.player.getUUID());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var optimizer = ChunkPilot.getInstance().getOptimizer();
            if (optimizer != null && handler.player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                optimizer.onPlayerRemoved(handler.player.getUUID(), sl.hashCode());
                if (optimizer.getIntegrationManager() != null) {
                    optimizer.getIntegrationManager().onPlayerRemoved(handler.player.getUUID());
                }
            }
            var dispatcher = ChunkPilot.getInstance().getNetworkDispatcher();
            if (dispatcher != null) {
                dispatcher.onPlayerDisconnect(handler.player.getUUID());
            }
            FabricPlatform.unregisterPlayer(handler.player);
        });

        // 注册 /chunkpilot 命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerChunkPilotCommand(dispatcher);
        });

        ChunkPilotFabric.LOGGER.info("ChunkPilot initialized on Fabric (server)");
    }

    private static void registerChunkPilotCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        SuggestionProvider<CommandSourceStack> playerSuggest = (context, builder) -> {
            MinecraftServer server = context.getSource().getServer();
            return SharedSuggestionProvider.suggest(
                server.getPlayerList().getPlayers().stream()
                    .map(p -> p.getGameProfile().name()),
                builder
            );
        };

        dispatcher.register(
            net.minecraft.commands.Commands.literal("chunkpilot")
                .requires(src -> hasPerm(src, 0))
                .executes(ctx -> runMain(ctx, new String[0]))
                .then(net.minecraft.commands.Commands.literal("status")
                    .requires(src -> hasPerm(src, 0))
                    .executes(ctx -> runMain(ctx, new String[]{"status"})))
                .then(net.minecraft.commands.Commands.literal("reload")
                    .requires(src -> hasPerm(src, 0))
                    .executes(ctx -> runMain(ctx, new String[]{"reload"}))
                    .then(net.minecraft.commands.Commands.literal("config")
                        .requires(src -> hasPerm(src, 4))
                        .executes(ctx -> runMain(ctx, new String[]{"reload", "config"})))
                    .then(net.minecraft.commands.Commands.argument("player", StringArgumentType.string())
                        .requires(src -> hasPerm(src, 4))
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"reload", StringArgumentType.getString(ctx, "player")}))))
                .then(net.minecraft.commands.Commands.literal("config")
                    .requires(src -> hasPerm(src, 4))
                    .then(net.minecraft.commands.Commands.literal("show").executes(ctx -> runMain(ctx, new String[]{"config", "show"})))
                    .then(net.minecraft.commands.Commands.literal("reload").executes(ctx -> runMain(ctx, new String[]{"config", "reload"}))))
                .then(net.minecraft.commands.Commands.literal("player")
                    .requires(src -> hasPerm(src, 2))
                    .executes(ctx -> runMain(ctx, new String[]{"player"}))
                    .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.string())
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"player", StringArgumentType.getString(ctx, "name")}))))
                .then(net.minecraft.commands.Commands.literal("net")
                    .requires(src -> hasPerm(src, 0))
                    .executes(ctx -> runMain(ctx, new String[]{"net"}))
                    .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.string())
                        .requires(src -> hasPerm(src, 4))
                        .suggests(playerSuggest)
                        .executes(ctx -> runMain(ctx, new String[]{"net", StringArgumentType.getString(ctx, "name")}))))
                .then(net.minecraft.commands.Commands.literal("gen")
                    .requires(src -> hasPerm(src, 2))
                    .executes(ctx -> runMain(ctx, new String[]{"gen"}))
                    .then(net.minecraft.commands.Commands.literal("queue")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "queue"})))
                    .then(net.minecraft.commands.Commands.literal("stats")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "stats"})))
                    .then(net.minecraft.commands.Commands.literal("status")
                        .executes(ctx -> runMain(ctx, new String[]{"gen", "status"})))
                    )
                .then(net.minecraft.commands.Commands.literal("debug")
                    .requires(src -> hasPerm(src, 4))
                    .executes(ctx -> runMain(ctx, new String[]{"debug"})))
                .then(net.minecraft.commands.Commands.literal("send")
                    .requires(src -> hasPerm(src, 2))
                    .executes(ctx -> runMain(ctx, new String[]{"send"}))
                    .then(net.minecraft.commands.Commands.literal("status")
                        .executes(ctx -> runMain(ctx, new String[]{"send", "status"})))
                    )
                .then(net.minecraft.commands.Commands.literal("diagnose")
                    .requires(src -> hasPerm(src, 0))   // 非 OP 走"只看自己"
                    .executes(ctx -> runMain(ctx, new String[]{"diagnose"})))
                .then(net.minecraft.commands.Commands.literal("probe")
                    .requires(src -> hasPerm(src, 2))
                    .executes(ctx -> runMain(ctx, new String[]{"probe"}))
                    .then(net.minecraft.commands.Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(net.minecraft.commands.Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                            .executes(ctx -> runMain(ctx, new String[]{
                                "probe",
                                String.valueOf(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x")),
                                String.valueOf(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z"))})))))
                .then(net.minecraft.commands.Commands.literal("lang")
                    .requires(src -> hasPerm(src, 0))
                    .executes(ctx -> runMain(ctx, new String[]{"lang"}))
                    .then(net.minecraft.commands.Commands.argument("code", StringArgumentType.string())
                        // 切换语言是**服务端全局**行为 (影响控制台与 auto 模式下所有玩家), 需要 op
                        .requires(src -> hasPerm(src, 2))
                        .suggests((ctx, b) -> {
                            b.suggest("auto");
                            for (String code : com.chunkpilot.i18n.I18n.availableLanguages()) b.suggest(code);
                            return b.buildFuture();
                        })
                        .executes(ctx -> runMain(ctx, new String[]{"lang", StringArgumentType.getString(ctx, "code")}))))
                .then(net.minecraft.commands.Commands.literal("help")
                    .requires(src -> hasPerm(src, 0))
                    .executes(ctx -> runMain(ctx, new String[]{"help"})))
        );
    }

    /**
     * 1.21.11 权限模型迁移: `CommandSourceStack.hasPermission(int)` **已被删除**
     * (javap 实证: 只剩 withPermission / withMaximumPermission / permissions())。
     * 新模型: `net.minecraft.server.permissions.PermissionSet` + `Permission`,
     * 等级用 `PermissionLevel` 枚举表达, 判定是
     * `LevelBasedPermissionSet.hasPermission(new Permission.HasCommandLevel(level))`
     * → `this.level.isEqualOrHigherThan(required)` (javap 实证),
     * 与老 `hasPermission(n)` 的 "等级 >= n" 语义**完全一致**.
     *
     * 这里保留 CP 原来的整数调用点, 只把 N 映射到对应的 Permission:
     *   0 → 无条件通过 (老 hasPermission(0) 对所有人都是 true)
     *   1 → Permissions.COMMANDS_MODERATOR  (MODERATORS)
     *   2 → Permissions.COMMANDS_GAMEMASTER (GAMEMASTERS)
     *   3 → Permissions.COMMANDS_ADMIN      (ADMINS)
     *   4 → Permissions.COMMANDS_OWNER      (OWNERS)
     */
    private static boolean hasPerm(CommandSourceStack src, int level) {
        if (level <= 0) return true;
        net.minecraft.server.permissions.Permission required = switch (level) {
            case 1 -> net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR;
            case 2 -> net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER;
            case 3 -> net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN;
            default -> net.minecraft.server.permissions.Permissions.COMMANDS_OWNER;
        };
        try {
            return src.permissions().hasPermission(required);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把命令源的权限等级压成 CP 认识的三档 (0 / 2 / 4). 控制台恒为 4。 */
    private static int permLevel(CommandSourceStack src) {
        if (hasPerm(src, 4)) return 4;
        if (hasPerm(src, 3)) return 3;
        if (hasPerm(src, 2)) return 2;
        return hasPerm(src, 1) ? 1 : 0;
    }

    private static int runMain(CommandContext<CommandSourceStack> ctx, String[] args) {
        var player = ctx.getSource().getPlayer();
        java.util.UUID execId = player != null ? player.getUUID() : null;
        String name = player != null ? player.getGameProfile().name() : "console";
        // v0.11.9: 权限判定集中在 ChunkPilotCommand (堵住 /chunkpilot 无参绕过 requires 的问题)
        String resp = ChunkPilotCommand.execute(execId, name, args, permLevel(ctx.getSource()));
        for (String line : resp.split("\n")) {
            ctx.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        }
        return 1;
    }
}