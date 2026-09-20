package com.chunkpilot.core;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.i18n.I18n;
import com.chunkpilot.platform.PlatformAbstraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * /chunkpilot 命令的业务逻辑
 * 平台侧负责注册命令并转发参数到本类
 *
 * 子命令:
 *   - status                       查看 ChunkPilot 当前状态
 *   - reload [player]              立刻重载某玩家（或自己）周边 view_distance 个区块
 *   - reload config                热重载配置文件
 *   - config show                  显示当前配置关键值
 *   - player [name]                查看某玩家加载详情
 *   - net [name]                   查看玩家网络统计
 *   - gen [queue|stats]            生成器状态
 *   - send [status]                发送排序器状态
 *   - probe [x z]                  只读区块探针（不加载区块）
 *   - diagnose                     服务器体检
 *   - lang [auto|en_us|zh_cn]      查看 / 切换输出语言
 *   - debug                        切换 debug 日志
 *   - help                         显示帮助
 *
 * ============================ v0.11.7 i18n ============================
 * 本类**所有面向人的输出**都走 {@link I18n}，不再有硬编码文案。
 *   - 玩家执行的命令 → `I18n.trFor(executorId, key, args)`: 自动用**该玩家客户端的语言**，
 *     控制台执行则用全局语言（默认 en_us）。
 *   - 控制台 / RCON → 全局语言。
 *   - 参数名 (enabled / view / v_min …) 故意**不翻译** —— 它们是 `chunkpilot.toml` 里的键名,
 *     翻译反而会让管理员对不上配置；翻译的是标题、说明、建议这些"人话"。
 */
public class ChunkPilotCommand {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotCommand");

    /** 翻译后的命令前缀 (含 § 颜色码). 每次读取, 支持运行时切语言。 */
    public static String prefix() {
        return I18n.tr("chunkpilot.prefix");
    }

    /** 主入口 */
    public static String execute(UUID executorId, String executorName, String[] args) {
        if (args == null || args.length == 0) return executeStatus(executorId, executorName);
        switch (args[0].toLowerCase()) {
            case "status":  return executeStatus(executorId, executorName);
            case "reload":  return executeReload(executorId, executorName, args);
            case "config":  return executeConfig(executorId, executorName, args);
            case "player":  return executePlayer(executorId, executorName, args);
            case "net":     return executeNet(executorId, executorName, args);
            case "gen":     return executeGen(executorId, executorName, args);
            case "send":    return executeSend(executorId, executorName, args);
            case "probe":   return executeProbe(executorId, executorName, args);
            case "lang":
            case "language":return executeLang(executorId, executorName, args);
            case "diagnose":
            case "diag":    return executeDiagnose(executorId, executorName, args);
            case "debug":   return executeDebug(executorId, executorName);
            case "help":
            case "?":
            default:        return getHelpText(executorId);
        }
    }

    // ================= v0.11.7: /chunkpilot lang =================

    /**
     * `/chunkpilot lang`            → 显示当前语言 + 可用语言
     * `/chunkpilot lang <code>`     → 运行时切换 (不写回配置文件)
     * `/chunkpilot lang auto`       → 恢复自动 (玩家跟随客户端语言)
     */
    public static String executeLang(UUID executorId, String executorName, String[] args) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp != null && cp.getPlatform() != null) {
            cp.getPlatform().logCommand(executorName, args.length >= 2 ? "lang " + args[1] : "lang");
        }
        String available = String.join("§7, §e", I18n.availableLanguages());

        if (args.length < 2) {
            String mode = I18n.isForced()
                ? I18n.trFor(executorId, "chunkpilot.command.lang.mode_forced")
                : I18n.trFor(executorId, "chunkpilot.command.lang.mode_auto");
            return prefix() + I18n.trFor(executorId, "chunkpilot.command.lang.current",
                    I18n.language(), I18n.displayName(I18n.language()), mode)
                + "\n" + prefix() + I18n.trFor(executorId, "chunkpilot.command.lang.list", available);
        }

        String code = args[1].trim();
        if (I18n.AUTO.equalsIgnoreCase(code)) {
            I18n.override(null);
            return prefix() + I18n.trFor(executorId, "chunkpilot.command.lang.auto_set");
        }
        String normalized = I18n.normalize(code);
        if (!I18n.availableLanguages().contains(normalized)) {
            return prefix() + I18n.trFor(executorId, "chunkpilot.command.lang.unknown", code, available);
        }
        I18n.override(normalized);
        // 切完之后用**新语言**回话, 让管理员立刻看到效果
        return prefix() + I18n.trFor(executorId, "chunkpilot.command.lang.set",
                normalized, I18n.displayName(normalized));
    }

    // ================= probe =================

    /**
     * v0.11.5e: `/chunkpilot probe [x z]` — 只读区块探针 (不加载区块).
     *
     * 存在的理由: 1.21.3 的 `/execute if loaded <pos>` 会**同步加载**该区块
     *   (实现走 getWorldChunk(x,z), load=true), 用它做监控会自己制造主线程停摆.
     * 这里只查 hasChunk / ChunkHolder / ticketLevel, 零副作用, 可以放心高频调用.
     */
    public static String executeProbe(UUID executorId, String executorName, String[] args) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();

        UUID target = executorId;
        if (target == null) {
            var online = platform.getOnlinePlayers();
            if (!online.isEmpty()) target = online.keySet().iterator().next();
        }
        int worldId = target == null ? 0 : platform.getPlayerWorldId(target);
        if (worldId == 0) worldId = platform.getOverworldId();   // 控制台/无玩家 → 主世界
        if (worldId == 0) return prefix() + I18n.trFor(executorId, "chunkpilot.error.no_world");

        int cx;
        int cz;
        if (args.length >= 3) {
            try {
                cx = Integer.parseInt(args[1]) >> 4;
                cz = Integer.parseInt(args[2]) >> 4;
            } catch (NumberFormatException e) {
                return prefix() + I18n.trFor(executorId, "chunkpilot.command.probe.usage");
            }
        } else {
            int[] pc = platform.getPlayerChunkPos(target);
            if (pc == null) return prefix() + I18n.trFor(executorId, "chunkpilot.command.probe.no_chunkpos");
            cx = pc[0];
            cz = pc[1];
        }

        StringBuilder sb = new StringBuilder();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.probe.title"))
          // 注意: 语言跟随**命令执行者** (executorId), 不是被探测的玩家 (target)
          .append(platform.probeChunk(worldId, cx, cz, executorId));
        if (target != null) {
            double speed = cp.getOptimizer().getSpeedTracker().getSpeed(target);
            double dir = cp.getOptimizer().getSpeedTracker().getDirection(target);
            sb.append(I18n.trFor(executorId, "chunkpilot.command.probe.player",
                    prefix(), speed, speed * 20.0, Math.toDegrees(dir)));
        }
        platform.logCommand(executorName, "probe " + cx + " " + cz);
        return sb.toString();
    }

    // ================= diagnose =================

    /**
     * v0.11.6: `/chunkpilot diagnose` —— "服务器扛得住吗" 体检.
     *
     * 背景 (bench bot 实测): 无 C2ME 时原版 worldgen/后处理吞吐只有 ~20~40 区块/秒,
     * 而一个玩家 34 m/s + 视距 10 的需求就是 `(34/16) × (2×10+1) ≈ 45 区块/秒`, 5 个玩家 ≈ 223/秒。
     * 结果就是"前方已加载前沿塌陷", 玩家一直被服务端拉回。
     * 这个命令把"需求"和"实际前沿深度"摆在一起, 让问题在游戏里就能看见。
     *
     * 所有查询都用非加载 API (hasChunk / probeChunk), 不会自己制造停摆.
     */
    public static String executeDiagnose(UUID executorId, String executorName, String[] args) {
        try {
            return diagnose0(executorId, executorName);
        } catch (Throwable t) {
            // 体检本身绝不能因为玩家刚好掉线/跨维度而报 "unexpected error"
            LOG.warn("[ChunkPilot] diagnose failed: {}", t.toString());
            return prefix() + I18n.trFor(executorId, "chunkpilot.error.diagnose_failed", String.valueOf(t));
        }
    }

    private static String diagnose0(UUID executorId, String executorName) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();

        boolean c2me = platform.isModLoaded("c2me");
        int vd = platform.getServerRenderDistance();
        var online = new java.util.LinkedHashMap<>(platform.getOnlinePlayers());
        int ring = 2 * vd + 1;

        StringBuilder sb = new StringBuilder();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.title",
            I18n.trFor(executorId, c2me ? "chunkpilot.command.diagnose.c2me.yes"
                                        : "chunkpilot.command.diagnose.c2me.no"),
            vd, online.size()));

        double totalDemand = 0.0;
        int worstFrontier = Integer.MAX_VALUE;
        String worstPlayer = null;
        for (var e : online.entrySet()) {
            UUID id = e.getKey();
            int[] pc = platform.getPlayerChunkPos(id);
            if (pc == null) continue;
            int worldId = platform.getPlayerWorldId(id);
            double vBpt = cp.getOptimizer().getSpeedTracker().getSpeed(id);
            double vMs = vBpt * 20.0;
            double demand = (vMs / 16.0) * ring;          // 每秒新进入视距的区块数
            totalDemand += demand;

            // 前方已加载前沿深度 (沿速度方向逐步试探, 全部非加载查询)
            double dir = cp.getOptimizer().getSpeedTracker().getDirection(id);
            double dx = Math.cos(dir), dz = Math.sin(dir);
            int frontier = 0;
            for (int k = 1; k <= vd + 14; k++) {
                int cx = pc[0] + (int) Math.round(dx * k);
                int cz = pc[1] + (int) Math.round(dz * k);
                if (!platform.isChunkLoaded(worldId, cx, cz)) break;
                frontier = k;
            }
            if (frontier < worstFrontier) {
                worstFrontier = frontier;
                worstPlayer = e.getValue();
            }
            String flag = (vBpt > 1.0 && frontier < 4) ? "§c" : "§a";
            var fwInfo = cp.getOptimizer().getForwardWindow().info(id);
            String fwText = fwInfo == null
                ? I18n.trFor(id, "chunkpilot.command.diagnose.fw.off")
                : I18n.trFor(id, "chunkpilot.command.diagnose.fw.on",
                    fwInfo.anchorX(), fwInfo.anchorZ(), fwInfo.anchorLevel(),
                    fwInfo.shiftX(), fwInfo.shiftZ());
            sb.append(prefix()).append(I18n.trFor(id, "chunkpilot.command.diagnose.player",
                flag, vBpt, vMs, pc[0], pc[1], frontier, demand, fwText));
        }
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.total",
                totalDemand, vd, online.size()));
        long parkSubs = platform.getParkSubstitutions();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.park",
                parkSubs,
                parkSubs > 0 ? I18n.trFor(executorId, "chunkpilot.command.diagnose.park.warn") : ""));
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.fw_stats",
                cp.getOptimizer().getForwardWindow().statsText(executorId)));

        // 结论
        if (!c2me) {
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.noc2me.warn"));
            if (totalDemand > 40) {
                sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.noc2me.over",
                        totalDemand));
                int suggestVd = (int) Math.max(2,
                    (16.0 * 25.0 / Math.max(1.0, totalDemand / Math.max(1, online.size())) - 1) / 2);
                String suggestSpeed = String.format("%.0f", 16.0 * 25.0 / (double) ring);
                sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.advice",
                        suggestVd, suggestSpeed));
            } else {
                sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.ok_single"));
            }
        } else {
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.c2me_ok"));
        }
        if (worstPlayer != null) {
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.diagnose.worst",
                    worstPlayer, worstFrontier,
                    worstFrontier < 4
                        ? I18n.trFor(executorId, "chunkpilot.command.diagnose.worst.bad") : ""));
        }
        platform.logCommand(executorName, "diagnose");
        return sb.toString();
    }

    // ================= status / reload / config =================

    public static String executeStatus(UUID executorId, String executorName) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");

        PlatformAbstraction platform = cp.getPlatform();
        String status = cp.getOptimizer().getStatusText(executorId);
        String online = I18n.trFor(executorId, "chunkpilot.status.online",
                platform.getOnlinePlayers().size());

        platform.logCommand(executorName, "status");
        return prefix() + status + "\n" + prefix() + online;
    }

    public static String executeReload(UUID executorId, String executorName, String[] args) {
        // /chunkpilot reload config
        if (args.length >= 2 && "config".equalsIgnoreCase(args[1])) {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
            cp.reloadConfig();
            cp.getPlatform().logCommand(executorName, "reload config");
            return prefix() + I18n.trFor(executorId, "chunkpilot.command.reload.config_ok");
        }
        // /chunkpilot reload [player]
        return executeReloadPlayer(executorId, executorName, args.length >= 2 ? args[1] : null);
    }

    public static String executeReloadPlayer(UUID executorId, String executorName, String targetName) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();
        String execName = platform.getOnlinePlayers().getOrDefault(executorId, "console");
        if (!cp.getConfig().enabled) {
            platform.logCommand(execName, "reload (disabled)");
            return prefix() + I18n.trFor(executorId, "chunkpilot.error.disabled");
        }

        Map<UUID, String> online = platform.getOnlinePlayers();

        UUID targetId = null;
        String targetDisplay = null;

        if (targetName == null || targetName.isEmpty()) {
            if (executorId == null) {
                platform.logCommand(execName, "reload (no target from console)");
                return prefix() + I18n.trFor(executorId, "chunkpilot.error.console_needs_player");
            }
            targetId = executorId;
            targetDisplay = online.get(executorId);
            if (targetDisplay == null) targetDisplay = executorId.toString().substring(0, 8);
        } else {
            for (Map.Entry<UUID, String> e : online.entrySet()) {
                if (e.getValue().equalsIgnoreCase(targetName)) {
                    targetId = e.getKey();
                    targetDisplay = e.getValue();
                    break;
                }
            }
            if (targetId == null) {
                platform.logCommand(execName, "reload (player not found: " + targetName + ")");
                return prefix() + I18n.trFor(executorId, "chunkpilot.error.player_not_found", targetName);
            }
        }

        int[] chunkPos = platform.getPlayerChunkPos(targetId);
        if (chunkPos == null) {
            platform.logCommand(execName, "reload (no chunkpos for " + targetDisplay + ")");
            return prefix() + I18n.trFor(executorId, "chunkpilot.error.no_chunkpos");
        }

        int worldId = platform.getPlayerWorldId(targetId);
        if (worldId == 0) {
            platform.logCommand(execName, "reload (no world for " + targetDisplay + ")");
            return prefix() + I18n.trFor(executorId, "chunkpilot.error.no_world_for_target");
        }

        int reloaded = cp.getOptimizer().forceReloadPlayer(targetId, worldId);

        platform.logCommand(execName, String.format("reload %s (%d tickets)", targetDisplay, reloaded));

        // v0.11.4: 语义变更 — 不再是"按视距铺满正方形", 而是撤销现有票后立即按当前速度/方向
        //   重算扇区 (新增量受自适应预算约束). 旧实现一次铺 (2r+1)^2 张票, 下一 tick 又被 diff
        //   全撤 → 卸载风暴.
        return prefix() + I18n.trFor(executorId, "chunkpilot.command.reload.player_ok",
                reloaded, targetDisplay, chunkPos[0], chunkPos[1]);
    }

    public static String executeConfig(UUID executorId, String executorName, String[] args) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();
        if (args.length < 2) return configShow(cp.getConfig(), executorId);

        switch (args[1].toLowerCase()) {
            case "show":
                platform.logCommand(executorName, "config show");
                return configShow(cp.getConfig(), executorId);
            case "reload":
                cp.reloadConfig();
                platform.logCommand(executorName, "config reload");
                return prefix() + I18n.trFor(executorId, "chunkpilot.command.config.reloaded");
            default:
                return prefix() + I18n.trFor(executorId, "chunkpilot.error.unknown_config_sub", args[1]);
        }
    }

    /** 键名 (enabled / view / v_min …) 故意不翻译 —— 它们必须和 chunkpilot.toml 对得上。 */
    private static String configShow(ChunkPilotConfig c, UUID executorId) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.config.header"));
        kv(sb, executorId, "general.enabled", c.enabled);
        kv(sb, executorId, "general.mode", c.mode);
        kv(sb, executorId, "general.logLevel", c.logLevel);
        kv(sb, executorId, "general.language", c.language);
        kv(sb, executorId, "speed.windowTicks", c.speedWindowTicks);
        kv(sb, executorId, "speed.recentTicks", c.speedRecentTicks);
        kvUnit(sb, executorId, "speed.lowSpeedThreshold", c.lowSpeedThreshold,
                I18n.trFor(executorId, "chunkpilot.unit.blocks_per_tick"));
        kvUnit(sb, executorId, "speedTiers", c.speedTiers.size(),
                I18n.trFor(executorId, "chunkpilot.unit.tiers"));
        for (var t : c.speedTiers) {
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.config.tier",
                t.name, t.minSpeed, t.maxSpeed, t.coreRadius, t.sectorAngle, t.sectorRadius, t.tailRadius))
              .append('\n');
        }
        kv(sb, executorId, "forward_window.enabled", c.forwardWindow != null && c.forwardWindow.enabled);
        if (c.forwardWindow != null) {
            kv(sb, executorId, "forward_window.aheadChunks", c.forwardWindow.aheadChunks);
            kv(sb, executorId, "forward_window.forwardExtra", c.forwardWindow.forwardExtra);
            kv(sb, executorId, "forward_window.trackingShift", c.forwardWindow.trackingShift);
        }
        kv(sb, executorId, "protection.maxMspt", c.maxMspt);
        kv(sb, executorId, "protection.disableMspt", c.disableMspt);
        kv(sb, executorId, "protection.nonBlockingCollision", c.nonBlockingCollision);
        kv(sb, executorId, "protection.nonBlockingGetChunk", c.nonBlockingGetChunk);
        kv(sb, executorId, "protection.ticketExpiryTicks", c.ticketExpiryTicks);
        kv(sb, executorId, "integration.enabled", c.integrationEnabled);
        kv(sb, executorId, "integration.mtr.enabled", c.mtr.enabled);
        kv(sb, executorId, "integration.immersive_railroading.enabled", c.immersiveRailroading.enabled);
        kv(sb, executorId, "integration.create.enabled", c.create.enabled);
        kv(sb, executorId, "integration.create_aeronautics.enabled", c.createAeronautics.enabled);
        kv(sb, executorId, "integration.immersive_vehicles.enabled", c.immersiveVehicles.enabled);
        kv(sb, executorId, "generation.enabled", c.generation.enabled);
        kv(sb, executorId, "chunk_send.enabled", c.chunkSend.enabled);
        return sb.toString();
    }

    private static void kv(StringBuilder sb, UUID id, String key, Object value) {
        sb.append(prefix()).append(I18n.trFor(id, "chunkpilot.command.config.kv", key, value)).append('\n');
    }

    private static void kvUnit(StringBuilder sb, UUID id, String key, Object value, String unit) {
        sb.append(prefix()).append(I18n.trFor(id, "chunkpilot.command.config.kv_unit", key, value, unit))
          .append('\n');
    }

    // ================= player / net =================

    public static String executePlayer(UUID executorId, String executorName, String[] args) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();
        Map<UUID, String> online = platform.getOnlinePlayers();
        String targetName = args.length >= 2 ? args[1] : null;

        UUID targetId = resolveTarget(online, executorId, targetName);
        if (targetId == null) {
            String who = (targetName == null || targetName.isEmpty())
                ? I18n.trFor(executorId, "chunkpilot.unit.self") : targetName;
            return prefix() + I18n.trFor(executorId, "chunkpilot.error.player_not_found", who);
        }
        platform.logCommand(executorName, "player " + online.getOrDefault(targetId, targetId.toString()));
        return prefix() + cp.getOptimizer().getPlayerDebug(targetId);
    }

    /** /chunkpilot net [player] */
    public static String executeNet(UUID executorId, String executorName, String[] args) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();
        Map<UUID, String> online = platform.getOnlinePlayers();
        String targetName = args.length >= 2 ? args[1] : null;

        UUID targetId = resolveTarget(online, executorId, targetName);
        if (targetId == null) {
            String who = (targetName == null || targetName.isEmpty())
                ? I18n.trFor(executorId, "chunkpilot.unit.self") : targetName;
            return prefix() + I18n.trFor(executorId, "chunkpilot.error.player_not_found", who);
        }
        PlatformAbstraction.NetStats stats = platform.getPlayerNetStats(targetId);
        if (stats == null) {
            return prefix() + I18n.trFor(executorId, "chunkpilot.error.no_net_stats",
                    online.getOrDefault(targetId, targetId.toString()));
        }
        String displayName = online.getOrDefault(targetId, targetId.toString().substring(0, 8));
        platform.logCommand(executorName, "net " + displayName);
        return prefix() + formatNetStats(displayName, stats, executorId);
    }

    private static UUID resolveTarget(Map<UUID, String> online, UUID executorId, String name) {
        if (name == null || name.isEmpty()) return executorId;
        for (Map.Entry<UUID, String> e : online.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    private static String formatNetStats(String name, PlatformAbstraction.NetStats s, UUID executorId) {
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.trFor(executorId, "chunkpilot.command.net.header", name));
        String cumulative = I18n.trFor(executorId, "chunkpilot.unit.bytes_per_second_cumulative");
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.net.rx",
                formatBytes(s.rxBytes), cumulative)).append('\n');
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.net.tx",
                formatBytes(s.txBytes), cumulative)).append('\n');
        String ping = s.pingMs >= 0
            ? I18n.trFor(executorId, "chunkpilot.command.net.ping_value", s.pingMs)
            : I18n.trFor(executorId, "chunkpilot.unit.unknown");
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.net.ping", ping)).append('\n');
        if (s.tickLatency >= 0) {
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.net.tick_latency",
                    s.tickLatency, I18n.trFor(executorId, "chunkpilot.unit.ticks"))).append('\n');
        }
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.net.hint"));
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    public static String executeDebug(UUID executorId, String executorName) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        cp.getPlatform().logCommand(executorName, "debug");
        return prefix() + I18n.trFor(executorId, "chunkpilot.command.debug.hint");
    }

    // ================= gen / send =================

    /**
     * /chunkpilot gen [queue|stats]
     * v0.3.0 轨道优先生成器状态查询
     */
    public static String executeGen(UUID executorId, String executorName, String[] args) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";

        switch (sub) {
            case "queue":
                platform.logCommand(executorName, "gen queue");
                return genQueue(cp, executorId);
            case "stats":
                platform.logCommand(executorName, "gen stats");
                return genStats(cp, executorId);
            case "status":
            default:
                platform.logCommand(executorName, "gen status");
                return genStatus(cp, executorId);
        }
    }

    /** 前瞻窗口把前方可达推到 视距 + forwardExtra; 没开就是视距。 */
    private static int forwardBand(ChunkPilot cp) {
        var c = cp.getConfig();
        int vd = cp.getPlatform().getServerRenderDistance();
        if (c.forwardWindow != null && c.forwardWindow.enabled) {
            return vd + Math.max(0, Math.min(c.forwardWindow.forwardExtra, c.forwardWindow.aheadChunks));
        }
        return vd;
    }

    private static String genStatus(ChunkPilot cp, UUID executorId) {
        com.chunkpilot.config.GenerationConfig g = cp.getConfig().generation;
        StringBuilder sb = new StringBuilder();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.gen.header"));
        kv(sb, executorId, "generation.enabled", g.enabled);
        kvUnit(sb, executorId, "generation.v_min", g.v_min,
                I18n.trFor(executorId, "chunkpilot.unit.chunks_per_second"));
        kvUnit(sb, executorId, "generation.lookAheadSeconds", g.lookAheadSeconds,
                I18n.trFor(executorId, "chunkpilot.unit.seconds"));
        kv(sb, executorId, "generation.sectorAngleBaseDeg", g.sectorAngleBaseDeg + "°");
        kv(sb, executorId, "generation.sectorAngleMaxDeg", g.sectorAngleMaxDeg + "°");
        kvUnit(sb, executorId, "generation.vMinMs", g.vMinMs,
                I18n.trFor(executorId, "chunkpilot.unit.meters_per_second"));
        kvUnit(sb, executorId, "generation.predictionTTLSeconds", g.predictionTTLSeconds,
                I18n.trFor(executorId, "chunkpilot.unit.seconds"));
        kv(sb, executorId, "generation.maxChunksPerTick", g.maxChunksPerTick);
        kvUnit(sb, executorId, "generation.targetMspt", g.targetMspt,
                I18n.trFor(executorId, "chunkpilot.unit.milliseconds"));
        if (!g.enabled) {
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.gen.disabled_hint"));
        }
        return sb.toString();
    }

    private static String genQueue(ChunkPilot cp, UUID executorId) {
        com.chunkpilot.generation.GenerationScheduler gen = cp.getGenerationScheduler();
        if (gen == null) return prefix() + I18n.trFor(executorId, "chunkpilot.error.gen_not_initialized");
        var top = gen.peekTop(10);
        // v0.11.8: 队列为空时不再只回一句 "(empty)" —— 把"为什么空"直接摆出来,
        //   否则很容易误判成"生成侧没接线"。
        String explain = prefix() + I18n.trFor(executorId, "chunkpilot.command.gen.queue.explain",
            gen.getLastTickCandidates(), gen.getLastTickSkippedLoaded(), gen.getLastTickEnqueued(),
            gen.getActivePlayerCount(), gen.getTotalSkippedLoaded());
        String hint = "\n" + prefix() + I18n.trFor(executorId, "chunkpilot.command.gen.queue.hint",
            forwardBand(cp));
        if (top.isEmpty()) {
            return prefix() + I18n.trFor(executorId, "chunkpilot.command.gen.queue.empty")
                + "\n" + explain + hint;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.gen.queue.header"));
        sb.append(explain).append('\n');
        for (int i = 0; i < top.size(); i++) {
            var e = top.get(i);
            int cx = com.chunkpilot.generation.GenerationScheduler.unpackX(e.chunkPos);
            int cz = com.chunkpilot.generation.GenerationScheduler.unpackZ(e.chunkPos);
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.gen.queue.entry",
                i + 1, cx, cz, e.weight, e.expiresAtTick, e.contributingPlayers.size()));
        }
        return sb.toString().trim();
    }

    private static String genStats(ChunkPilot cp, UUID executorId) {
        com.chunkpilot.generation.GenerationScheduler gen = cp.getGenerationScheduler();
        if (gen == null) return prefix() + I18n.trFor(executorId, "chunkpilot.error.gen_not_initialized");
        StringBuilder sb = new StringBuilder();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.gen.stats.header"));
        kv(sb, executorId, "queueSize", gen.getQueueSize());
        kv(sb, executorId, "currentChunkCount", gen.getCurrentChunkCount());
        kvUnit(sb, executorId, "lastMspt", String.format("%.1f", gen.getLastMspt()),
                I18n.trFor(executorId, "chunkpilot.unit.milliseconds"));
        kv(sb, executorId, "tickCounter", gen.getTickCounter());
        kvUnit(sb, executorId, "lastTickPopped", gen.getLastTickPoppedCount(),
                I18n.trFor(executorId, "chunkpilot.unit.chunks"));
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.gen.stats.diag",
            gen.getLastTickCandidates(), gen.getLastTickSkippedLoaded(), gen.getLastTickEnqueued(),
            gen.getActivePlayerCount(), gen.getTotalSkippedLoaded())).append('\n');
        // v0.11.8: "搁置 → 负载低回补" 这条链路的实时状态 (回补数/已生成出队/离线丢弃/过期清理)
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.gen.stats.backlog",
            gen.getDeferredQueueSize(), gen.getLastTickDeferredPopped(), gen.getLastTickDeferredDone(),
            gen.getLastTickDeferredOffline(), gen.getLastTickDeferredExpired())).append('\n');
        kvUnit(sb, executorId, "deferredQueue", gen.getDeferredQueueSize(),
                String.format("(backfilled=%d, collected=%d, evicted=%d)",
                    gen.getLastTickDeferredPopped(), gen.getLastTickCollected(),
                    gen.getDeferredEvictions()));
        return sb.toString();
    }

    /**
     * /chunkpilot send [status]
     * v0.3.0 区块发送顺序优化器状态查询
     */
    public static String executeSend(UUID executorId, String executorName, String[] args) {
        ChunkPilot cp = ChunkPilot.getInstance();
        if (cp == null) return I18n.trFor(executorId, "chunkpilot.error.not_initialized");
        PlatformAbstraction platform = cp.getPlatform();
        platform.logCommand(executorName, "send status");
        return sendStatus(cp, executorId);
    }

    private static String sendStatus(ChunkPilot cp, UUID executorId) {
        com.chunkpilot.config.ChunkSendConfig s = cp.getConfig().chunkSend;
        StringBuilder sb = new StringBuilder();
        sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.send.header"));
        kv(sb, executorId, "chunk_send.enabled", s.enabled);
        kvUnit(sb, executorId, "chunk_send.v_min", s.v_min,
                I18n.trFor(executorId, "chunkpilot.unit.chunks_per_second"));
        kv(sb, executorId, "chunk_send.k", s.k);
        kv(sb, executorId, "chunk_send.direction_gamma", s.direction_gamma);
        kv(sb, executorId, "chunk_send.v_boost_beta", s.v_boost_beta);
        kvUnit(sb, executorId, "chunk_send.lookAheadSeconds", s.lookAheadSeconds,
                I18n.trFor(executorId, "chunkpilot.unit.seconds"));
        if (!s.enabled) {
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.send.disabled_hint"));
        } else {
            // 显示当前活跃玩家速度
            var optimizer = cp.getOptimizer();
            var online = cp.getPlatform().getOnlinePlayers();
            int activeCount = 0;
            for (UUID pid : online.keySet()) {
                double speed = optimizer.getSpeedTracker().getSpeed(pid);
                double vChunks = speed * 20.0 / 16.0;
                if (vChunks >= s.v_min) activeCount++;
            }
            sb.append(prefix()).append(I18n.trFor(executorId, "chunkpilot.command.send.active",
                    activeCount, online.size()));
        }
        return sb.toString();
    }

    // ================= help =================

    public static String getHelpText() {
        return getHelpText(null);
    }

    public static String getHelpText(UUID executorId) {
        String p = prefix();
        String[] keys = {
            "chunkpilot.command.help.header",
            "chunkpilot.command.help.status",
            "chunkpilot.command.help.reload",
            "chunkpilot.command.help.reload_config",
            "chunkpilot.command.help.config_show",
            "chunkpilot.command.help.config_reload",
            "chunkpilot.command.help.player",
            "chunkpilot.command.help.net",
            "chunkpilot.command.help.gen",
            "chunkpilot.command.help.send",
            "chunkpilot.command.help.probe",
            "chunkpilot.command.help.diagnose",
            "chunkpilot.command.help.probe_warning",
            "chunkpilot.command.help.lang",
            "chunkpilot.command.help.debug",
            "chunkpilot.command.help.help",
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            String line = I18n.trFor(executorId, keys[i]);
            sb.append(i == 0 ? p : "\n" + p).append(line);
        }
        return sb.toString();
    }
}
