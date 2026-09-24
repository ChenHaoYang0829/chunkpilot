package com.chunkpilot.core;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.integration.IntegrationManager;
import com.chunkpilot.integration.VehicleProvider;
import com.chunkpilot.platform.PlatformAbstraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块加载优化器
 *
 * 每 tick 调用 onPlayerChunkUpdate()，决策流程：
 *   1. 读玩家位置（blockPos，不是 chunkPos，精度更高）
 *   2. 更新速度追踪器（滑窗平均）
 *   3. 检查 Mod 联动：玩家是否在某个 Provider 的载具上？
 *      - 固定路径载具 (MTR 列车 / IR 火车 / Create 火车) → 走轨道路径精确预加载
 *      - 高速无轨载具 (飞艇 / 飞机) → 扇形加载
 *   4. 不在载具上 → 用滑窗速度判定扇形档位
 *   5. 差异性更新 ticket（每 tick 只发新 chunk 的 ticket，撤销已离开的）
 */
public class ChunkLoadOptimizer {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private ChunkPilotConfig config;
    private final PlatformAbstraction platform;
    private final SpeedTracker speedTracker;
    private final IntegrationManager integrationManager;

    private boolean enabled = true;
    private long currentTick = 0;

    /**
     * 活跃强制票的续票周期 (tick). CHUNKPILOT_TICKET 的 lifespan=31,
     * 但 applyTicketDiff 只对"新增"区块发票, 稳定/重叠区块不被刷新,
     * 长时间不重算会让票过期 → 区块卸载抖动. 因此每 TICKET_REFRESH_INTERVAL tick
     * 对整组活跃票重发一次 (addRegionTicket 对已存在的票是廉价续期, 不触发 worldgen).
     */
    private static final int TICKET_REFRESH_INTERVAL = 20;
    /** 扇形重算最小间隔 (tick): 抑制高速飞行/位置抖动导致的每 tick 轮换. */
    private static final int RECOMPUTE_INTERVAL = 4;
    private final Map<UUID, Long> lastTicketRefresh = new ConcurrentHashMap<>();

    /** C2ME 检测缓存 (null=未检测, true/false=结果) */
    private Boolean c2mePresent = null;

    /**
     * 检测 C2ME 是否加载 (缓存结果).
     * C2ME 存在时, CP 不主动管理 FORCED ticket (扇形加载),
     * 因为 CP 的 add/remove ticket 会与 C2ME 的调度器冲突,
     * 导致 chunk 被过早卸载 (B组测试: 卸载90 vs A组23).
     * C2ME 自己会处理区块加载, CP 只保留 SpeedTracker + GenerationScheduler + ChunkSendScheduler.
     */
    private boolean isC2MEPresent() {
        if (c2mePresent == null) {
            c2mePresent = platform.isModLoaded("c2me");
            if (c2mePresent) {
                LOG.info(com.chunkpilot.i18n.I18n.tr("chunkpilot.log.c2me_detected"));
            }
        }
        return c2mePresent;
    }

    /**
     * 玩家当前持有的 CP 票 + 它属于哪个维度.
     *
     * v0.11.4 修复 (维度切换票泄漏): 旧实现只存 chunkPos→level, 撤销时却用"当前 worldId".
     *   玩家跨维度传送后, 旧维度的票用新世界的 worldId 去 remove → 一个都撤不掉 (靠 31 tick
     *   自然过期), 而 playerActiveTickets 里那份记录已经被覆盖 → 永久泄漏.
     *   现在把 worldId 一起存下来, 撤销永远用"发票时的那个维度".
     */
    private record PlayerTickets(int worldId, Map<Long, Integer> tickets) {}
    private final Map<UUID, PlayerTickets> playerActiveTickets = new ConcurrentHashMap<>();
    /** 玩家上次处理的 tick（用于诊断） */
    private final Map<UUID, Long> lastUpdateTick = new ConcurrentHashMap<>();

    // P1: 粘滞状态 — 记录上次的玩家 chunk 位置/方向/速度/重算时间
    private record StickyState(int chunkX, int chunkZ, double direction, double speed, long lastRecalcTick) {}
    private final Map<UUID, StickyState> stickyStates = new ConcurrentHashMap<>();
    // v0.10: 共享负载自适应控制器 (生成侧与票侧共用同一平滑 MSPT)
    private final AdaptiveLoadController loadController = new AdaptiveLoadController();
    // P2: 自适应预算 (委托给共享负载控制器)
    private final SectorBudgetController budgetController = new SectorBudgetController(loadController);
    // v0.11.6: 前瞻窗口 (生成侧锚点票 + 发送侧跟踪窗口前移). 两种环境 (含 C2ME) 都生效.
    private final ForwardWindowController forwardWindow = new ForwardWindowController();


    public ChunkLoadOptimizer(ChunkPilotConfig config, PlatformAbstraction platform) {
        this.config = config;
        this.platform = platform;
        this.speedTracker = new SpeedTracker(config.speedWindowTicks, config.speedRecentTicks);
        this.integrationManager = new IntegrationManager();
    }

    public void updateConfig(ChunkPilotConfig newConfig) {
        this.config = newConfig;
        // 暂不重建 speedTracker（避免丢历史样本），新窗口大小在下次创建新玩家时生效
        // 服主如需应用新窗口，使用 /chunkpilot reload config 之前重启服务端
    }

    /**
     * 每 tick 由 mixin 拦截调用
     * @param playerId 玩家 UUID
     * @param worldId 世界 hashCode
     * @param blockX 玩家方块 X
     * @param blockZ 玩家方块 Z
     */
    public void onPlayerChunkUpdate(UUID playerId, int worldId, double blockX, double blockZ) {
        if (!enabled || !config.enabled) {
            forwardWindow.deactivate(playerId, platform);
            return;
        }

        // MSPT 保护
        double mspt = platform.getCurrentMspt();
        if (mspt > config.disableMspt) {
            forwardWindow.deactivate(playerId, platform);
            return;
        }

        // 1. 更新速度追踪 (始终执行, C2ME 也需要速度数据给 GenerationScheduler)
        speedTracker.update(playerId, blockX, blockZ, currentTick);
        lastUpdateTick.put(playerId, currentTick);

        int fwChunkX = (int) Math.floor(blockX / 16.0);
        int fwChunkZ = (int) Math.floor(blockZ / 16.0);

        // 1b. v0.11.6 前瞻窗口: 生成侧锚点票 + 发送侧跟踪窗口前移.
        //     放在 C2ME 分支之前 —— 这是"只动票和可见窗口"的轻量机制,
        //     C2ME 环境下同样需要 (C2ME 只负责提高生成吞吐, 不改窗口形状).
        forwardWindow.update(playerId, worldId, fwChunkX, fwChunkZ,
            speedTracker.getSpeed(playerId), speedTracker.getDirection(playerId),
            platform.getServerRenderDistance(), platform, config, currentTick);

        // C2ME 兼容: 检测到 C2ME 时, 不主动管理 FORCED ticket.
        // 原因: CP 每 tick 重新算扇形并对边界 chunk add/remove ticket,
        //       C2ME 跟着卸载/重新加载, 造成卸载抖动 (B组测试: 90次卸载 vs 纯C2ME 23次).
        // C2ME 自己会处理区块加载, CP 只保留:
        //   - SpeedTracker (速度追踪, GenerationScheduler 需要)
        //   - GenerationScheduler (通过 C2ME API 注入, 不冲突)
        //   - ChunkSendScheduler (Mixin 拦截, C2ME 存在时已跳过)
        if (isC2MEPresent()) {
            // 撤销之前可能存在的 CP ticket (从非 C2ME 切换过来的情况)
            if (!playerActiveTickets.isEmpty()) {
                for (UUID id : new java.util.ArrayList<>(playerActiveTickets.keySet())) {
                    revokeAllTicketsForPlayer(id); // v0.11.4: 用记录里的维度撤销
                }
            }
            return; // 不发 ticket, 不撤销 ticket, 让 C2ME 全权管理加载/卸载
        }

        // 2. 解析加载集
        Set<SectorCalculator.ChunkPos> newChunks = null;
        String source = "vanilla";

        // 3. 检查 mod 联动
        if (config.integrationEnabled) {
            Object player = platform.getPlayerObject(playerId);
            if (player != null) {
                VehicleProvider provider = integrationManager.getActiveProvider(player);
                if (provider != null) {
                    newChunks = computeFromProvider(player, provider, blockX, blockZ);
                    if (newChunks != null) source = "provider:" + provider.getName();
                }
            }
        }

        // 4. 不在 Provider 覆盖范围：滑窗速度判定
        if (newChunks == null) {
            double speed = speedTracker.getSpeed(playerId);
            double direction = speedTracker.getDirection(playerId);
            int chunkX = (int) Math.floor(blockX / 16.0);
            int chunkZ = (int) Math.floor(blockZ / 16.0);
            if (speed < config.lowSpeedThreshold) {
                // 滞回: 显著低速 (<= lowSpeedRevoke) 才真正拆除, 避免巡航速度
                // (如鞘翅 0.75~0.83 b/t) 骑在 lowSpeedThreshold 上每 tick 整拆整建.
                if (speed <= config.lowSpeedRevoke) {
                    // 渐进拆除: 每 tick 最多撤 N 个, 避免落地瞬间一次性卸载 50 个区块的卸载风暴.
                    drainTicketsForPlayer(playerId, worldId);
                    return;
                }
                // 滞回带 (lowSpeedRevoke < speed < lowSpeedThreshold):
                //   已激活 → 保持现状不拆 (防抖动), 并定期续票防过期;
                //   未激活 → 不干预.
                if (playerActiveTickets.containsKey(playerId)) {
                    refreshTicketsIfDue(playerId, currentTick);
                }
                return;
            }
            newChunks = computePlayerChunks(chunkX, chunkZ, speed, direction);
            source = "speed:" + String.format("%.2f", speed);
        }

        if (newChunks == null || newChunks.isEmpty()) {
            revokeAllTicketsForPlayer(playerId);
            stickyStates.remove(playerId);
            return;
        }

        // 5. 差异性更新 ticket (P0: 传 playerChunkX/Z 用于距离优先裁剪)
        int playerChunkX = (int) Math.floor(blockX / 16.0);
        int playerChunkZ = (int) Math.floor(blockZ / 16.0);

        // v0.11.4: 维度切换检测 — 换维度时清掉速度窗口, 否则新维度第一帧的
        //   "位移"= 跨维度坐标差 (极大) → 速度虚高 → 直接命中 extreme 档.
        PlayerTickets existing = playerActiveTickets.get(playerId);
        if (existing != null && existing.worldId() != worldId) {
            speedTracker.resetPlayer(playerId);
            stickyStates.remove(playerId);
            lastTicketRefresh.remove(playerId);
        }

        // 定期续票: 即使粘滞命中(不重算), 也让稳定活跃集不因 31 tick 过期而卸载.
        refreshTicketsIfDue(playerId, currentTick);

        // P1: 粘滞判断 — 减少 changed=true 频率 (治"弹")
        if (shouldStickySkip(playerId, newChunks, playerChunkX, playerChunkZ, currentTick, source)) {
            // 粘滞命中：跳过重算, 沿用上次的 ticket 集
            return;
        }

        applyTicketDiff(playerId, worldId, newChunks, source, playerChunkX, playerChunkZ);
    }

    /**
     * P1: 粘滞判断 — 玩家移动幅度小、转向幅度小、速度变化小 → 跳过重算.
     *
     * 注意: 不再用"速度档位名"做门控 (档位边界抖动会反复触发重算).
     * 并加"最小重算间隔"节流: 高速飞行/反作弊位置抖动会让移动距离每 tick 都超阈值,
     * 导致扇形每 tick 轮换 (v0.9.2 实测 new=8 rm=7 每 tick). 节流保证扇形不会每 tick 重算.
     *
     * @return true=跳过 applyTicketDiff (沿用上次 ticket 集), false=需要重算
     */
    private boolean shouldStickySkip(UUID playerId, Set<SectorCalculator.ChunkPos> newChunks,
                                      int playerChunkX, int playerChunkZ, long currentTick,
                                      String source) {
        StickyState prev = stickyStates.get(playerId);
        if (prev == null) return false; // 首次, 必须重算

        // provider 模式不分档, 任意 provider 都重算
        if (source != null && source.startsWith("provider:")) return false;

        long since = currentTick - prev.lastRecalcTick();

        // 节流: 距上次重算不足 RECOMPUTE_INTERVAL → 跳过, 除非移动/转向很大
        if (since < RECOMPUTE_INTERVAL) {
            int dx = playerChunkX - prev.chunkX();
            int dz = playerChunkZ - prev.chunkZ();
            if (dx * dx + dz * dz < 16) { // 移动 < 4 chunks
                double curDir = speedTracker.getDirection(playerId);
                if (Math.abs(SectorCalculator.normalizeAngle(curDir - prev.direction())) < Math.toRadians(60)) {
                    return true; // 节流内且没大动 → 跳过
                }
            }
        }

        // 超过节流间隔: 正常粘滞判断
        int dx = playerChunkX - prev.chunkX();
        int dz = playerChunkZ - prev.chunkZ();
        if (dx * dx + dz * dz >= 4) return false; // >=2 chunks 移动, 重算

        // 转向 < 30° 才跳过 (方向抖动容忍)
        double curDir = speedTracker.getDirection(playerId);
        if (Math.abs(SectorCalculator.normalizeAngle(curDir - prev.direction())) > Math.toRadians(30)) return false;

        // 速度显著变化 (>50%) → 重算 (防起飞/急加速瞬间虚空)
        double curSpeed = speedTracker.getSpeed(playerId);
        if (curSpeed > prev.speed() * 1.5 || curSpeed < prev.speed() * 0.5) return false;

        // 命中粘滞
        return true;
    }

    /**
     * 记录粘滞状态 (applyTicketDiff 成功后调用)
     */
    private void recordStickyState(UUID playerId, int playerChunkX, int playerChunkZ,
                                    String source, long currentTick) {
        double direction = speedTracker.getDirection(playerId);
        double speed = speedTracker.getSpeed(playerId);
        stickyStates.put(playerId, new StickyState(playerChunkX, playerChunkZ, direction, speed, currentTick));
    }

    private Set<SectorCalculator.ChunkPos> computeFromProvider(
            Object player, VehicleProvider provider, double blockX, double blockZ) {
        try {
            if (provider.hasFixedPath()) {
                // 轨道路径：提取前方 N 个 ChunkPos，再扫轨道两侧
                int lookAhead = provider.getLookAheadChunks();
                List<SectorCalculator.ChunkPos> railChunks = provider.getRailPathCached(player, lookAhead);
                if (railChunks == null || railChunks.isEmpty()) return null;
                int sideRadius = provider.getTrackSideRadius();
                Set<SectorCalculator.ChunkPos> result = new HashSet<>();
                for (SectorCalculator.ChunkPos p : railChunks) {
                    SectorCalculator.addCircle(result, p.x(), p.z(), sideRadius);
                }
                // 加保底圆（玩家脚下）
                int playerChunkX = (int) Math.floor(blockX / 16.0);
                int playerChunkZ = (int) Math.floor(blockZ / 16.0);
                SectorCalculator.addCircle(result, playerChunkX, playerChunkZ, 2);
                return result;
            } else {
                // 高速无轨载具：扇形
                double speed = provider.getSpeed(player);
                double direction = provider.getDirection(player);
                if (speed < provider.getMinSpeed()) return null;
                int chunkX = (int) Math.floor(blockX / 16.0);
                int chunkZ = (int) Math.floor(blockZ / 16.0);
                int renderDistance = platform.getServerRenderDistance();
                Set<SectorCalculator.ChunkPos> sector = SectorCalculator.computeSector(
                    chunkX, chunkZ, direction, Math.toRadians(provider.getSectorAngle()),
                    renderDistance);
                // 加保底圆
                SectorCalculator.addCircle(sector, chunkX, chunkZ, 2);
                return sector;
            }
        } catch (Throwable t) {
            LOG.warn("[ChunkPilot] Provider {} failed, falling back to vanilla speed: {}",
                provider.getName(), t.toString());
            return null;
        }
    }

    /**
     * 差异性发放 ticket
     *
     * P0 修复 (2026-08-07): 限制 ticket 总数 + 距离优先裁剪
     * 背景: 旧逻辑一次可能发 227 个 FULL_TICKING ticket (sectorRadius=12),
     *       玩家每 tick 移动触发 changed=true → 全量增删 → 服务器过载 164 ticks behind.
     * 改动:
     *   1. 按距离排序: 优先保留离玩家近的 chunk (前/脚下优先, 远/后裁掉)
     *   2. 总数裁剪: 超过 config.maxSectorTickets (默认 50) 的部分不发
     *   3. 复用上次的 ticket 集合: 上次已经在前方的 chunk 不再"撤销后重发"
     * P1 计划: 替换为 SectorBudgetController (自适应预算)
     */
    private void applyTicketDiff(UUID playerId, int worldId,
                                  Set<SectorCalculator.ChunkPos> newChunks, String source,
                                  int playerChunkX, int playerChunkZ) {
        applyTicketDiff(playerId, worldId, newChunks, source, playerChunkX, playerChunkZ, null);
    }

    /**
     * @param maxNewOverride 本次新增票的上限覆盖 (null = 用 config.maxNewTicketsPerTick).
     *                       /chunkpilot reload 用预算上限, 一次性把扇区补起来 (仍受预算约束).
     */
    private void applyTicketDiff(UUID playerId, int worldId,
                                  Set<SectorCalculator.ChunkPos> newChunks, String source,
                                  int playerChunkX, int playerChunkZ, Integer maxNewOverride) {
        // v0.10: 远处浅层加载 — 参考原版区块加载等级机制.
        //   近处 (d <= sectorShallowRadius) 用浅层之上的"完整生成"等级;
        //   远处用浅层等级 (默认 CARVERS, 只生成地形骨架, 不触发实体 tick).
        //   这样预加载不再等于整块 worldgen, 压住高速飞行+新世界的生成爆炸.
        //
        // v0.11.5c (2026-09-13): fullLevel 由 31 改为 33.
        //   31 是"玩家自身区域"的级别: 预生成用它 → 与玩家急需的区块同优先级排队 →
        //   主线程 tick 实体时的同步区块等待被排在预生成大队列后面 → 12~15 秒停摆
        //   (jstack 实锤; GC 已排除) → 客户端十几秒收不到区块 → 穿墙/虚空.
        //   33 = FULL 生成但无 tick, 仍高于原版环状依赖 (34~41), 保留提前生成收益.
        int fullLevel = 33;
        int shallowLevel = platform.getShallowChunkLevel(config.sectorShallowStatus);
        int shallowRadius = config.sectorShallowRadius;
        int shallowRadius2 = shallowRadius * shallowRadius;

        // 计算每个 chunk 的目标加载等级
        Map<Long, Integer> targetLevels = new HashMap<>();
        for (SectorCalculator.ChunkPos pos : newChunks) {
            long l = pos.toLong();
            int cx = SectorCalculator.ChunkPos.unpackX(l);
            int cz = SectorCalculator.ChunkPos.unpackZ(l);
            int dx = cx - playerChunkX;
            int dz = cz - playerChunkZ;
            int level = (dx * dx + dz * dz <= shallowRadius2) ? fullLevel : shallowLevel;
            targetLevels.put(l, level);
        }

        // P2: 自适应预算 — 只裁剪近处 FULL_TICKING 数量 (远处浅层便宜, 不占预算)
        int maxTickets = budgetController.getCurrentBudget(config.maxSectorTickets);
        List<Long> nearSorted = targetLevels.entrySet().stream()
            .filter(e -> e.getValue() == fullLevel)
            .map(Map.Entry::getKey)
            .sorted(Comparator.comparingInt(l -> dist2(l, playerChunkX, playerChunkZ)))
            .toList();
        if (nearSorted.size() > maxTickets) {
            Set<Long> keepFull = new HashSet<>(nearSorted.subList(0, maxTickets));
            for (Map.Entry<Long, Integer> e : targetLevels.entrySet()) {
                if (e.getValue() == fullLevel && !keepFull.contains(e.getKey())) {
                    e.setValue(shallowLevel); // 超出预算的近处降为浅层
                }
            }
        }

        // P2: 限速 — 每 tick 最多新增 N 个 ticket
        // v0.11.4: 维度切换时先用"旧维度"把旧票撤干净, 再从空集重新开始 (旧实现撤不掉 → 泄漏).
        PlayerTickets prev = playerActiveTickets.get(playerId);
        if (prev != null && prev.worldId() != worldId) {
            revokeAllTicketsForPlayer(playerId);
            prev = null;
        }
        Map<Long, Integer> previous = prev == null ? Map.of() : prev.tickets();
        int maxNewPerTick = maxNewOverride != null
            ? Math.max(1, maxNewOverride)
            : config.maxNewTicketsPerTick;

        // 新增: 不在 previous 里 (按距离近优先)
        List<Long> toAdd = targetLevels.keySet().stream()
            .filter(l -> !previous.containsKey(l))
            .sorted(Comparator.comparingInt(l -> dist2(l, playerChunkX, playerChunkZ)))
            .limit(maxNewPerTick)
            .toList();

        // 撤销: 在 previous 但不在 target 里
        List<Long> toRemove = previous.keySet().stream()
            .filter(l -> !targetLevels.containsKey(l))
            .toList();

        // 等级变化: 在 previous 且 target 里但等级不同 → 先撤旧等级, 再加新等级
        List<Long> toReload = previous.entrySet().stream()
            .filter(e -> targetLevels.containsKey(e.getKey())
                && !targetLevels.get(e.getKey()).equals(e.getValue()))
            .map(Map.Entry::getKey)
            .toList();

        // 合并最终状态
        Map<Long, Integer> finalActive = new HashMap<>(previous);
        for (Long l : toRemove) finalActive.remove(l);
        for (Long l : toReload) finalActive.remove(l);
        for (Long l : toAdd) finalActive.put(l, targetLevels.get(l));
        for (Long l : toReload) finalActive.put(l, targetLevels.get(l));

        boolean anyChanged = !finalActive.equals(previous);

        if (anyChanged) {
            // 新增
            for (Long l : toAdd) {
                int cx = SectorCalculator.ChunkPos.unpackX(l);
                int cz = SectorCalculator.ChunkPos.unpackZ(l);
                platform.addChunkTicket(worldId, cx, cz, targetLevels.get(l));
            }
            // 等级变化: 先撤旧等级, 再加新等级
            for (Long l : toReload) {
                int cx = SectorCalculator.ChunkPos.unpackX(l);
                int cz = SectorCalculator.ChunkPos.unpackZ(l);
                platform.removeChunkTicket(worldId, cx, cz, previous.get(l));
                platform.addChunkTicket(worldId, cx, cz, targetLevels.get(l));
            }
            // 撤销
            for (Long l : toRemove) {
                int cx = SectorCalculator.ChunkPos.unpackX(l);
                int cz = SectorCalculator.ChunkPos.unpackZ(l);
                platform.removeChunkTicket(worldId, cx, cz, previous.get(l));
            }
            playerActiveTickets.put(playerId, new PlayerTickets(worldId, finalActive));
        }

        // P1: 记录粘滞状态 (任何一次都记, 以便下次对比)
        recordStickyState(playerId, playerChunkX, playerChunkZ, source, currentTick);

        // P2: 更新自适应预算 (基于共享负载控制器的平滑 MSPT)
        budgetController.update(config.maxSectorTickets);

        if ("debug".equalsIgnoreCase(config.logLevel)) {
            LOG.info("[ChunkPilot] player={} source={} chunks={}/{} active={} new={} rm={} reload={} budget={} (changed={})",
                playerId.toString().substring(0, 8), source, finalActive.size(), newChunks.size(),
                finalActive.size(), toAdd.size(), toRemove.size(), toReload.size(), maxTickets, anyChanged);
        }
    }

    /** chunk 到玩家 (chunk 坐标) 的平方距离, 用于近处优先排序 */
    private static int dist2(long chunkLong, int px, int pz) {
        int dx = SectorCalculator.ChunkPos.unpackX(chunkLong) - px;
        int dz = SectorCalculator.ChunkPos.unpackZ(chunkLong) - pz;
        return dx * dx + dz * dz;
    }

    /**
     * 玩家离线清理
     */
    public void onPlayerRemoved(UUID playerId, int worldId) {
        forwardWindow.deactivate(playerId, platform); // v0.11.6: 撤掉前瞻锚点票
        revokeAllTicketsForPlayer(playerId); // v0.11.4: 用记录里的维度撤销, worldId 参数保留兼容
        speedTracker.removePlayer(playerId);
        playerActiveTickets.remove(playerId);
        lastUpdateTick.remove(playerId);
        lastTicketRefresh.remove(playerId);
    }

    /**
     * 渐进拆除玩家扇形票: 每 tick 最多撤销 maxNewTicketsPerTick 个, 避免落地/停顿时
     * 一次性撤销几十个区块票造成卸载风暴 (v0.9.1 实测落地时 Can't keep up 496 ticks).
     * 拆空后清理粘滞/续票状态.
     */
    private void drainTicketsForPlayer(UUID playerId, int worldId) {
        PlayerTickets entry = playerActiveTickets.get(playerId);
        if (entry == null || entry.tickets().isEmpty()) {
            stickyStates.remove(playerId);
            lastTicketRefresh.remove(playerId);
            return;
        }
        // v0.11.4: 撤销用"发票时的维度", 不用调用方传来的当前维度
        int ticketWorld = entry.worldId();
        Map<Long, Integer> active = entry.tickets();
        int n = 0;
        java.util.Iterator<Map.Entry<Long, Integer>> it = active.entrySet().iterator();
        while (it.hasNext() && n < config.maxNewTicketsPerTick) {
            Map.Entry<Long, Integer> e = it.next();
            long chunkLong = e.getKey();
            int cx = SectorCalculator.ChunkPos.unpackX(chunkLong);
            int cz = SectorCalculator.ChunkPos.unpackZ(chunkLong);
            platform.removeChunkTicket(ticketWorld, cx, cz, e.getValue());
            it.remove();
            n++;
        }
        if (active.isEmpty()) {
            playerActiveTickets.remove(playerId);
            stickyStates.remove(playerId);
            lastTicketRefresh.remove(playerId);
        }
    }

    /**
     * 每 TICKET_REFRESH_INTERVAL tick 对玩家整组活跃票重发一次 (廉价续期),
     * 防止 CHUNKPILOT_TICKET 因 31 tick lifespan 过期导致已加载区块卸载抖动.
     * 对已存在的 region ticket 重发只重置过期时间, 不触发 worldgen.
     */
    private void refreshTicketsIfDue(UUID playerId, long currentTick) {
        PlayerTickets entry = playerActiveTickets.get(playerId);
        if (entry == null || entry.tickets().isEmpty()) return;
        Long last = lastTicketRefresh.get(playerId);
        if (last != null && currentTick - last < TICKET_REFRESH_INTERVAL) return;
        int ticketWorld = entry.worldId(); // v0.11.4: 续票也要用发票时的维度
        for (Map.Entry<Long, Integer> e : entry.tickets().entrySet()) {
            long chunkLong = e.getKey();
            int cx = SectorCalculator.ChunkPos.unpackX(chunkLong);
            int cz = SectorCalculator.ChunkPos.unpackZ(chunkLong);
            platform.addChunkTicket(ticketWorld, cx, cz, e.getValue());
        }
        lastTicketRefresh.put(playerId, currentTick);
        if ("debug".equalsIgnoreCase(config.logLevel)) {
            LOG.debug("[ChunkPilot] player={} refreshed {} active tickets (tick={})",
                playerId.toString().substring(0, 8), entry.tickets().size(), currentTick);
        }
    }

    /**
     * 强制重载：撤掉现有票 + 立即按当前速度/方向重算一次扇区票.
     * 用于 /chunkpilot reload
     *
     * v0.11.4 修复 (卸载风暴): 旧实现一次性铺满 (2*viewDistance+1)^2 张 31 级票
     *   (默认 21x21 = 441 张), 完全绕过 maxSectorTickets / maxNewTicketsPerTick,
     *   而下一 tick 的 diff 发现这些票几乎都不在扇区目标里 → 全撤 → 一瞬间 400+ 次
     *   removeTicket → 卸载风暴. 现在: 撤销走正常路径, 新增量受自适应预算约束
     *   (预算上限, 仍远超每 tick 的 2 张, 保证 reload 立刻见效).
     */
    public int forceReloadPlayer(UUID playerId, int worldId) {
        if (!enabled || !config.enabled) return 0;

        int[] chunkPos = platform.getPlayerChunkPos(playerId);
        if (chunkPos == null) return 0;
        int chunkX = chunkPos[0], chunkZ = chunkPos[1];

        revokeAllTicketsForPlayer(playerId);
        stickyStates.remove(playerId);
        lastTicketRefresh.remove(playerId);

        double speed = speedTracker.getSpeed(playerId);
        double direction = speedTracker.getDirection(playerId);
        Set<SectorCalculator.ChunkPos> newChunks =
            computePlayerChunks(chunkX, chunkZ, speed, direction);
        if (newChunks == null || newChunks.isEmpty()) return 0;

        applyTicketDiff(playerId, worldId, newChunks, "reload", chunkX, chunkZ,
            budgetController.getCurrentBudget(config.maxSectorTickets));
        return getPlayerTicketCount(playerId);
    }

    public int getPlayerTicketCount(UUID playerId) {
        PlayerTickets s = playerActiveTickets.get(playerId);
        return s == null ? 0 : s.tickets().size();
    }

    public int getActivePlayerCount() {
        return playerActiveTickets.size();
    }

    /** 推进 tick 计数（由 mixin 入口处或平台 tick 事件调用） */
    /** v0.11.4: 票标记重建周期 (5s) */
    private static final int MARK_REBUILD_INTERVAL = 100;
    private long markRebuildFailures = 0;

    public void onServerTick() {
        currentTick++;
        // v0.10: 每 tick 采样一次平滑 MSPT, 生成侧与票侧共享
        loadController.sample(platform.getCurrentMspt());

        // v0.11.4: 每 5s 用"真实活跃票集合"重建平台标记, 防止标记只增不减泄漏
        //   (旧实现只在 removeChunkTicket 时清除, 维度切换/过期/异常都会留下永久残留).
        if (currentTick % MARK_REBUILD_INTERVAL == 0) {
            try {
                Set<Long> live = new HashSet<>();
                for (PlayerTickets pt : playerActiveTickets.values()) {
                    live.addAll(pt.tickets().keySet());
                }
                platform.rebuildCpTicketMarks(live);
            } catch (Throwable t) {
                if (markRebuildFailures++ % 20 == 0) {
                    LOG.warn("[ChunkPilot] 票标记重建失败 (第 {} 次): {}", markRebuildFailures, t.toString());
                }
            }
        }
    }

    /** v0.10: 共享负载自适应控制器 (生成侧与票侧共用同一平滑 MSPT) */
    public AdaptiveLoadController getLoadController() { return loadController; }

    public long getCurrentTick() { return currentTick; }

    public String getStatusText() {
        return getStatusText(null);
    }

    /**
     * @param viewerId 命令执行者 (玩家 → 用其客户端语言; null/控制台 → 全局语言)
     */
    public String getStatusText(java.util.UUID viewerId) {
        // v0.11.9: 本命令 0 级可用 ⇒ 不再输出任何服务端内部计数
        //   (原来会输出 CP 票数 / mixin 调用数 / override 调用数 / 模组联动列表)
        return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.optimizer.status",
            com.chunkpilot.ChunkPilot.VERSION,
            config.enabled, platform.getServerRenderDistance(),
            platform.getCurrentMspt(), getActivePlayerCount());
    }

    /** 玩家级调试信息 (输出语言跟随该玩家自己的客户端语言) */
    public String getPlayerDebug(UUID playerId) {
        int tickets = getPlayerTicketCount(playerId);
        String speedInfo = speedTracker.getDebugInfo(playerId);
        double speed = speedTracker.getSpeed(playerId);
        double dir = Math.toDegrees(speedTracker.getDirection(playerId));
        Long lastTick = lastUpdateTick.get(playerId);
        return com.chunkpilot.i18n.I18n.trFor(playerId, "chunkpilot.optimizer.player_debug",
            playerId.toString().substring(0, 8), speed, dir, tickets,
            lastTick == null ? com.chunkpilot.i18n.I18n.trFor(playerId, "chunkpilot.unit.never")
                             : lastTick.toString(), speedInfo);
    }

    /**
     * 撤销该玩家全部 CP 票.
     * v0.11.4: 用记录里的世界 id 撤销 (调用方传当前维度会在跨维度传送后撤错世界 → 泄漏).
     */
    private void revokeAllTicketsForPlayer(UUID playerId) {
        PlayerTickets previous = playerActiveTickets.remove(playerId);
        if (previous == null) return;
        for (Map.Entry<Long, Integer> e : previous.tickets().entrySet()) {
            int cx = SectorCalculator.ChunkPos.unpackX(e.getKey());
            int cz = SectorCalculator.ChunkPos.unpackZ(e.getKey());
            platform.removeChunkTicket(previous.worldId(), cx, cz, e.getValue());
        }
    }

    public Set<SectorCalculator.ChunkPos> computePlayerChunks(
            int chunkX, int chunkZ, double speed, double direction) {
        int renderDistance = platform.getServerRenderDistance();
        double mspt = platform.getCurrentMspt();
        int effectiveRenderDistance = renderDistance;
        if (mspt > config.maxMspt) {
            effectiveRenderDistance = Math.max(4, (int) (renderDistance * 0.8));
        }
        return SectorCalculator.computeChunks(chunkX, chunkZ, speed, direction,
            effectiveRenderDistance, config);
    }

    public SpeedTracker getSpeedTracker() { return speedTracker; }
    /** v0.11.6: 前瞻窗口控制器 (Mixin / 命令 / 诊断读取). */
    public ForwardWindowController getForwardWindow() { return forwardWindow; }
    public IntegrationManager getIntegrationManager() { return integrationManager; }
}
