package com.chunkpilot.generation;

import com.chunkpilot.config.GenerationConfig;
import com.chunkpilot.core.AdaptiveLoadController;
import com.chunkpilot.core.SpeedTracker;
import com.chunkpilot.platform.PlatformAbstraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.8.0 调度器 — deadline 排序 (替代 v0.3.0 权重公式).
 *
 * 2026-08-10 定稿 (deepseek-v4-pro 评审 + 用户拍板):
 *   1. 收集在线玩家
 *   2. 为每个快速移动玩家计算 deadline 排序候选 (扇形扫描 + 速度自适应半径)
 *   3. 玩家脚下 chunk 强制最高优 (保底, 防止脚下漏)
 *   4. 清理过期条目
 *   5. 自适应调整本 tick 生成数
 *   6. 弹出 top N, 通过 PlatformAbstraction.requestChunkAsync 触发生成
 *
 * v0.8.0 变化:
 *   - 删除 PredictionPath / projectOntoPath / v0.3.0 权重公式 (轨道预测废弃)
 *   - 生成候选 = DeadlineGenerationSorter.sortByDeadline (零参数, 物理直觉)
 *   - 方向 EMA 平滑 (DirectionSmoother) 防急转断层
 *   - 保留: 脚下保底 / 浅层预生成 / FULL 待办回补 / 自适应
 */
public class GenerationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotGen");

    /** 队列 (降序) */
    private final PriorityQueue<GenerationQueueEntry> queue = new PriorityQueue<>();

    /** chunkPos → entry 索引, O(1) 查找避免遍历 PriorityQueue */
    private final Map<Long, GenerationQueueEntry> index = new HashMap<>();

    /** 当前 tick 发的 chunk 数 (初始 2) */
    private int currentChunkCount = 2;

    /** 上一次 mspt (用于自适应) */
    private double lastMspt = 0.0;

    /** 当前 server tick */
    private long tickCounter = 0;

    /** 玩家当前涉及的 chunk (player -> set of chunkPos) */
    private final Map<UUID, Set<Long>> playerContributingChunks = new ConcurrentHashMap<>();

    /** 当前 tick 调生成器的累计数 (本 tick 重置, 给 stats 用) */
    private int lastTickPoppedCount = 0;

    /** v0.8.0: 方向 EMA 平滑器 (急转保护) */
    private final DirectionSmoother directionSmoother = new DirectionSmoother();

    /**
     * v0.6.0: FULL 待办回补队列.
     * 凡是"本该 FULL 生成但本 tick 没生成完"的 chunk 移到这里保留,
     * 只要玩家还在附近 (TTL 内) 就不丢弃, 等 MSPT 低时回补 FULL 生成.
     */
    private final java.util.LinkedHashMap<Long, GenerationQueueEntry> deferredFullQueue = new java.util.LinkedHashMap<>();

    /** 本 tick 回补的 FULL 数 (给 stats) */
    private int lastTickDeferredPopped = 0;

    /** 前方扇形的权重基数: w = FRONT_WEIGHT_BASE / (d+1) */
    private static final double FRONT_WEIGHT_BASE = 1000.0;
    /**
     * v0.11.4: 核心区权重系数 (相对前方扇形).
     *   1.0 = 与前方同权; <1 = 同等距离下前方优先. 0.5 的效果:
     *   d=1 核心(250) < d=1 前方(500), d=3 核心(125) < d=3 前方(250),
     *   但 d=1 核心(250) > d=5 前方(167) —— 近处仍然优先, 只是不再碾压前方.
     */
    private static final double CORE_WEIGHT_FACTOR = 0.5;

    /** v0.11.0 诊断: 累计发出的生成请求数 (飞行测试用于判断 CP 预生成是否压垮 worldgen) */
    private long totalRequests = 0;
    private int lastTickCandidates = 0;
    private int lastTickSkippedLoaded = 0;
    private int lastTickEnqueued = 0;
    private long totalSkippedLoaded = 0;
    private int activeGeneratingPlayers = 0;
    /** v0.11.8 backlog 诊断: 本 tick 因"真的到 FULL 了"而正常出队 / 玩家离线丢弃 的条数 */
    private int lastTickDeferredDone = 0;
    private int lastTickDeferredOffline = 0;
    /** v0.11.8 backlog 诊断: 本 tick 因 TTL 过期被清理 的条数 */
    private int lastTickDeferredExpired = 0;

    /** v0.11.0 诊断: 本 tick 实际发出的生成请求数 */
    private int lastTickRequests = 0;

    /** v0.11.5 诊断: 本 tick 新收集进 backlog 的条目数 */
    private int lastTickCollected = 0;

    /** v0.11.5 诊断: backlog 因超容量被淘汰的累计条目数 */
    private long deferredEvictions = 0;

    /**
     * v0.11.5: 每 tick 回补时最多"考察"多少条 backlog 条目.
     * 防止一次选中大量"已生成"条目时空转遍历数千条 (每条都要一次 isChunkLoaded).
     */
    private static final int BACKFILL_CONSIDER_PER_TICK = 16;

    /**
     * v0.11.4: 在途窗口键 = (维度, chunkPos).
     *   旧实现只用 chunkPos 当键 → 主世界与地狱/末地同坐标互相覆盖, 在途数被低估 (窗口形同虚设).
     */
    private record WindowKey(int worldId, long chunkPos) {}

    /**
     * v0.11.0: 在途请求窗口. 记录 CP 已请求但尚未生成完的 chunk (key → 入队 tick);
     * 窗口满时停发新请求, 使请求速率自动跟随 worldgen 吞吐; 入队 tick 用于 TTL 兜底
     * (防止窗口被"永远生成不完"的 chunk 卡死).
     */
    private final java.util.LinkedHashMap<WindowKey, Long> outstandingRequests = new java.util.LinkedHashMap<>();

    /** 待办队列当前大小 (给 stats) */
    private int deferredQueueSize = 0;

    public GenerationScheduler() {}

    // ========== 状态查询 (供 /chunkpilot gen stats) ==========

    public int getQueueSize() { return queue.size(); }
    public int getCurrentChunkCount() { return currentChunkCount; }
    public double getLastMspt() { return lastMspt; }
    public long getTickCounter() { return tickCounter; }
    public int getLastTickPoppedCount() { return lastTickPoppedCount; }
    /** v0.6.0: FULL 待办队列大小 */
    public int getDeferredQueueSize() { return deferredQueueSize; }
    /** v0.6.0: 本 tick 回补 FULL 数 */
    public int getLastTickDeferredPopped() { return lastTickDeferredPopped; }
    /** v0.11.5: 本 tick 新收集进 backlog 的条目数 */
    public int getLastTickCollected() { return lastTickCollected; }

    /**
     * v0.11.8 诊断: "生成队列为什么是空的" 三个计数 —— 让 `/chunkpilot gen queue` 能自己回答。
     *
     *   候选数 candidates        = 本 tick deadline 扇形 + 核心区一共考虑了多少个区块
     *   已加载跳过 skippedLoaded  = 其中"已经有 ChunkHolder 且票级 ≤ FULL"因而被跳过的数量
     *   入队 enqueued            = 真正进实时队列的数量
     *
     * 高速飞行 + 前瞻窗口开启时, 前方可达 vd+forwardExtra (默认 16) 区块全部已被锚点票
     * 覆盖 ⇒ skippedLoaded ≈ candidates, enqueued = 0 ⇒ 队列必然为空。这是**正常且想要的状态**,
     * 不是"没接线"。
     */
    public int getLastTickCandidates() { return lastTickCandidates; }
    public int getLastTickSkippedLoaded() { return lastTickSkippedLoaded; }
    public int getLastTickEnqueued() { return lastTickEnqueued; }
    public long getTotalSkippedLoaded() { return totalSkippedLoaded; }
    public int getActivePlayerCount() { return activeGeneratingPlayers; }
    public int getLastTickDeferredDone() { return lastTickDeferredDone; }
    public int getLastTickDeferredOffline() { return lastTickDeferredOffline; }
    public int getLastTickDeferredExpired() { return lastTickDeferredExpired; }
    /** v0.11.5: backlog 超容量淘汰的累计条目数 */
    public long getDeferredEvictions() { return deferredEvictions; }

    // ========== 主入口 ==========

    /**
     * 每 tick 由平台入口调用.
     *
     * @param platform     平台抽象 (调 getOnlinePlayers / getPlayerChunkPos / getPlayerWorldId /
     *                     getCurrentMspt / requestChunkAsync / isChunkLoaded)
     * @param cfg          生成器配置 (启用开关 + 阈值/半径/上限等)
     * @param currentMspt  当前服务器 MSPT (毫秒)
     * @param speedTracker 速度追踪器 (拿 getSpeed / getDirection)
     * @param currentTick  当前 server tick
     */
    public void onServerTick(PlatformAbstraction platform, GenerationConfig cfg,
                             double currentMspt, SpeedTracker speedTracker, long currentTick) {
        this.tickCounter = currentTick;
        this.lastMspt = currentMspt;
        this.lastTickPoppedCount = 0;
        this.lastTickRequests = 0;
        // v0.11.5b: 这两个计数在 tick 开头重置 (回补挪到了 pop 之前, 会被 pop 的预算计算读到)
        this.lastTickDeferredPopped = 0;
        this.lastTickCollected = 0;
        this.lastTickCandidates = 0;
        this.lastTickSkippedLoaded = 0;
        this.lastTickEnqueued = 0;
        this.activeGeneratingPlayers = 0;
        this.lastTickDeferredDone = 0;
        this.lastTickDeferredOffline = 0;
        this.lastTickDeferredExpired = 0;

        if (!cfg.enabled) return;

        // v0.11.0: exclusiveGeneration 是否实际激活 (无 C2ME 时才生效).
        //   激活时禁止浅层预生成 — 浅层 chunk 依赖 PLAYER ticket 升级到 FULL,
        //   但 exclusive mixin 会取消 PLAYER 升级 → 浅层 chunk 永远无法 FULL → 死锁.
        boolean exclusiveActive = cfg.exclusiveGenerationNoC2me
            && !platform.isModLoaded("c2me");

        // v0.11.0: 在途窗口维护 — 已生成完的移出窗口, 得到本 tick 还能发多少请求.
        if (!outstandingRequests.isEmpty()) {
            java.util.Iterator<java.util.Map.Entry<WindowKey, Long>> wit = outstandingRequests.entrySet().iterator();
            while (wit.hasNext()) {
                java.util.Map.Entry<WindowKey, Long> en = wit.next();
                WindowKey k = en.getKey();
                boolean done = platform.isChunkLoaded(k.worldId(), unpackX(k.chunkPos()), unpackZ(k.chunkPos()));
                if (!done) {
                    // v0.11.0 TTL 兜底: 玩家飞远后 ticket 过期, 这类 chunk 可能永远不会变成 loaded,
                    //   若不设 TTL, 窗口会被永久占满 → CP 的预生成彻底停摆 (实测 post-flight 仍 32/32).
                    if (currentTick - en.getValue() > cfg.outstandingTtlTicks) done = true;
                }
                if (done) wit.remove();
            }
        }
        final int windowRoom = cfg.maxOutstandingRequests > 0
            ? Math.max(0, cfg.maxOutstandingRequests - outstandingRequests.size())
            : Integer.MAX_VALUE;

        // 1. 更新所有在线玩家的预测路径
        Map<UUID, String> online = platform.getOnlinePlayers();
        Set<UUID> playersToRemove = new HashSet<>(playerContributingChunks.keySet());
        playersToRemove.removeAll(online.keySet());
        for (UUID gone : playersToRemove) {
            removePlayerChunks(gone);
            directionSmoother.removePlayer(gone);
        }

        int viewDistance = platform.getServerRenderDistance();

        for (UUID playerId : online.keySet()) {
            int[] chunkPos = platform.getPlayerChunkPos(playerId);
            if (chunkPos == null) continue;

            double vBpt = speedTracker.getSpeed(playerId);
            if (!ChunkWeightCalculator.shouldActivate(vBpt, cfg.v_min)) {
                removePlayerChunks(playerId);
                continue;
            }

            // v0.8.0: 速度 (m/s) + 方向 (弧度)
            double vMs = vBpt * 20.0;  // blocks/tick × 20 = blocks/s = m/s
            double directionRad = speedTracker.getDirection(playerId);
            double dirX = Math.cos(directionRad);
            double dirZ = Math.sin(directionRad);

            // 方向 EMA 平滑 (急转保护)
            double[] smooth = directionSmoother.update(playerId, dirX, dirZ);

            int worldId = platform.getPlayerWorldId(playerId);
            if (worldId == 0) continue;

            // 2. deadline 排序候选
            Set<Long> playerChunks = new HashSet<>();
            long expires = currentTick + (long) (cfg.predictionTTLSeconds * 20.0);

            // ---- 脚下保底 ----
            // 玩家所在 chunk 强制最高优, 无论已生成与否
            int playerCX = chunkPos[0];
            int playerCZ = chunkPos[1];
            long playerChunkPos = packChunkPos(playerCX, playerCZ);
            addOrUpdate(playerChunkPos, 999999.0, expires, playerId);
            playerChunks.add(playerChunkPos);

            // ---- v0.10.6 玩家周围核心区: 无条件生成 (对应发送器核心区) ----
            // 根因: exclusiveGenerationNoC2me 拦停原版生成, 只生成前方扇形 (deadline 排序),
            // 玩家周围 (后方/侧方) 区块从不生成 → 小地图中心圆黑像素(玩家周围未接收).
            // 这里把玩家周围 coreRadius 内的区块全部加入生成候选, 权重极高 (近者先, 类似发送器).
            double genCoreRadius = 3.0; // chunks, 与发送器 ChunkSendScheduler 一致 (v0.10.9: 实测最佳平衡)
            double genCoreRadiusSq = genCoreRadius * genCoreRadius;
            int coreRange = (int) Math.ceil(genCoreRadius);
            for (int dcx = -coreRange; dcx <= coreRange; dcx++) {
                for (int dcz = -coreRange; dcz <= coreRange; dcz++) {
                    if (dcx == 0 && dcz == 0) continue; // 玩家自己 chunk 已入队
                    double d2 = (double)(dcx*dcx) + (double)(dcz*dcz);
                    if (d2 > genCoreRadiusSq) continue;
                    int cx = playerCX + dcx;
                    int cz = playerCZ + dcz;
                    long cpLong = packChunkPos(cx, cz);
                    // 跳过"已经有了"的区块 —— 计数用于回答"队列为什么空"
                    if (chunkpilot$alreadyThere(platform, cfg, worldId, cx, cz)) {
                        this.lastTickSkippedLoaded++;
                        this.totalSkippedLoaded++;
                        continue;
                    }
                    double d = Math.sqrt(d2);
                    // v0.11.4 修复 (优先级倒挂): 旧权重 5_000_000 比前方扇形 (1000/(d+1), 最大 1000)
                    //   高 4 个数量级 → 只要核心区还有没生成的区块, 前方扇形就永远排在后面被饿死,
                    //   而核心区本来就有 vanilla PLAYER ticket 在生成 (v0.11.0 起 CP 已不再取消原版任务).
                    //   现在与前方扇形同量级: 同等距离"前方优先", 更近的核心区仍然更优先.
                    double w = FRONT_WEIGHT_BASE / (d + 1.0) * CORE_WEIGHT_FACTOR;
                    addOrUpdate(cpLong, w, expires, playerId);
                    playerChunks.add(cpLong);
                }
            }

            // ---- deadline 排序扫描 ----
            // topK 传大值: 截断交给队列 pop 阶段, 这里返回全部候选
            // (只返回 top-16 会导致近处区块全被移入待办, 实时队列被清空)
            List<int[]> candidates = DeadlineGenerationSorter.sortByDeadline(
                playerCX, playerCZ,
                vMs, dirX, dirZ,
                smooth[0], smooth[1],
                viewDistance, cfg.lookAheadSeconds,
                cfg.vMinMs, Integer.MAX_VALUE);

            this.lastTickCandidates += candidates.size();
            this.activeGeneratingPlayers++;
            for (int[] c : candidates) {
                int cx = c[0], cz = c[1];

                // 脚下已入队, 跳过
                if (cx == playerCX && cz == playerCZ) continue;

                long chunkPosLong = packChunkPos(cx, cz);

                // 跳过"已经有了"的区块 (避免队列被已生成 chunk 污染)
                if (chunkpilot$alreadyThere(platform, cfg, worldId, cx, cz)) {
                    this.lastTickSkippedLoaded++;
                    this.totalSkippedLoaded++;
                    continue;
                }

                // v0.8.0: deadline 排序已按"到达时间"排好, 权重 = 1/deadline 量级
                // 用距离作为权重 (deadline 升序 = 权重降序), 保证队列顺序
                double dx = cx - playerCX;
                double dz = cz - playerCZ;
                double d = Math.sqrt(dx * dx + dz * dz);
                double w = FRONT_WEIGHT_BASE / (d + 1.0);  // 近处权重高, 与 deadline 排序一致

                addOrUpdate(chunkPosLong, w, expires, playerId);
                this.lastTickEnqueued++;
                playerChunks.add(chunkPosLong);
            }
            playerContributingChunks.put(playerId, playerChunks);
        }

        // 4. 自适应 (v0.10: 共享负载控制器, 连续映射 + 平滑 MSPT)
        //    生产环境 currentMspt = 共享控制器的平滑 MSPT (由平台入口传入),
        //    与票侧 SectorBudgetController 用同一平滑值, 避免双回路各自反应.
        this.currentChunkCount = AdaptiveLoadController.suggestGenerationCount(
            cfg.maxChunksPerTick, currentMspt);

        // 队列空时不提速 (避免和扇形预算形成二阶反馈振荡)
        if (queue.isEmpty()) {
            this.currentChunkCount = Math.min(this.currentChunkCount, 1);
        }

        // 4.5 v0.11.5b: backlog 回补 —— 与下面的实时队列 pop **共用同一个每 tick 请求预算**.
        //
        //  背景 (2026-09-13 实测回归, 必须记住): 原实现把回补 (最多 deferredFullMaxPerTick=2) 与
        //  实时 pop (currentChunkCount=1) **相加** = 3 req/tick = 60/s. 收集范围放宽后 backlog
        //  "永远有货" → 回补几乎每 tick 都触发, 实测:
        //    请求速率 5.0 → 27.3 req/s (×5.5), mspt p90 10.6 → 51.8ms, max 27.4 → 171.5ms,
        //    "Can't keep up" 5~7 次 (最差落后 323 ticks).
        //  这正是 v0.11.0 报告警告过的 "3/tick=60/s vs 玩家 1.66 chunks/s → mspt 170ms".
        //
        //  现在: 本 tick 的总请求数被 currentChunkCount 卡死 (健康负载 = 1/tick), 由 backlog
        //  优先消费该预算 (用户要求: 收集到的区块在负载低时优先生成), 剩余额度才给实时队列.
        //  因为 backlog 条目在每次候选扫描时会被刷新 (put 覆盖), "仍相关(live)" 的条目权重是最新的,
        //  回补挑的是 (live, 权重) 最优 → 飞行中回补的正是实时队列本来要生成的那批, 不额外浪费.
        if (cfg.deferredFullQueueEnabled) {
            double msptThresholdEarly = cfg.targetMspt - cfg.deferredFullMsptMargin;
            if (currentMspt < msptThresholdEarly && !deferredFullQueue.isEmpty()) {
                int backfillCap = Math.min(this.currentChunkCount, cfg.deferredFullMaxPerTick);
                int considered = 0;
                while (this.lastTickDeferredPopped < backfillCap
                        && this.lastTickRequests < windowRoom
                        && considered < BACKFILL_CONSIDER_PER_TICK
                        && !deferredFullQueue.isEmpty()) {
                    // 按 (是否仍相关, 权重) 选最优: "仍相关" = 上一个 tick 的候选扫描刚刷新过它
                    Map.Entry<Long, GenerationQueueEntry> best = null;
                    boolean bestLive = false;
                    for (Map.Entry<Long, GenerationQueueEntry> en : deferredFullQueue.entrySet()) {
                        GenerationQueueEntry cand = en.getValue();
                        boolean live = cand.expiresAtTick >= currentTick;
                        if (best == null
                                || (live && !bestLive)
                                || (live == bestLive && cand.weight > best.getValue().weight)) {
                            best = en;
                            bestLive = live;
                        }
                    }
                    if (best == null) break;
                    GenerationQueueEntry e = best.getValue();
                    deferredFullQueue.remove(best.getKey());
                    considered++;
                    UUID fp = e.contributingPlayers.isEmpty() ? null : e.contributingPlayers.get(0);
                    int wid = fp != null ? platform.getPlayerWorldId(fp) : 0;
                    if (wid == 0) { this.lastTickDeferredOffline++; continue; } // 玩家离线, 丢弃
                    // v0.11.8 修复: 这里必须问"**真的生成到 FULL 了吗**", 而不是 isChunkLoaded。
                    //   旧实现用 isChunkLoaded (= hasChunk = "可见表里有 holder 且票级 ≤ FULL" = 已排期),
                    //   于是 backlog 里"只是被原版玩家票排上队、还没生成完"的条目全被判成"已生成"→
                    //   静默丢弃, backfill 计数恒为 0 (2026-09-20 第二实例实测: deferred 单调涨到 2337,
                    //   backfill=0, 回补功能等于没生效). 现在只有真到 FULL 才算"出队完成"。
                    if (platform.isChunkReadyFull(wid, unpackX(e.chunkPos), unpackZ(e.chunkPos))) {
                        this.lastTickDeferredDone++;
                        continue;
                    }
                    try {
                        platform.requestChunkAsync(wid, unpackX(e.chunkPos), unpackZ(e.chunkPos));
                        this.totalRequests++;
                        this.lastTickRequests++;
                        this.lastTickDeferredPopped++;
                        outstandingRequests.put(new WindowKey(wid, e.chunkPos), currentTick);
                    } catch (Exception ex) {
                        LOG.debug("[Gen] backlog backfill failed for ({},{}): {}",
                            unpackX(e.chunkPos), unpackZ(e.chunkPos), ex.toString());
                    }
                }
            }
        }

        // 5. Pop top N 给生成器 (预算 = currentChunkCount 减去 backlog 已用掉的额度)
        java.util.Set<Long> generatedThisTick = new java.util.HashSet<>();
        final int issueBudget = Math.max(0,
            Math.min(this.currentChunkCount, windowRoom) - this.lastTickDeferredPopped);
        for (int i = 0; i < issueBudget; i++) {
            GenerationQueueEntry entry = queue.poll();
            if (entry == null) break;
            index.remove(entry.chunkPos);
            int cx = unpackX(entry.chunkPos);
            int cz = unpackZ(entry.chunkPos);
            UUID firstPlayer = entry.contributingPlayers.isEmpty()
                ? null
                : entry.contributingPlayers.get(0);
            int worldId = firstPlayer != null ? platform.getPlayerWorldId(firstPlayer) : 0;
            if (worldId != 0) {
                try {
                    // v0.6.0: 浅层预生成 (调度器而非增压器)
                    // v0.11.0: exclusiveGeneration 激活时禁止浅层 (见 onServerTick 顶部 exclusiveActive 注释).
                    boolean useShallow = false;
                    if (cfg.shallowGenerationEnabled && !exclusiveActive) {
                        int[] playerChunk = platform.getPlayerChunkPos(firstPlayer);
                        if (playerChunk != null) {
                            double d = Math.sqrt(
                                (double) (cx - playerChunk[0]) * (cx - playerChunk[0]) +
                                (double) (cz - playerChunk[1]) * (cz - playerChunk[1]));
                            useShallow = d > cfg.shallowDistanceRadius;
                        }
                    }
                    if (useShallow) {
                        platform.requestChunkGeneration(worldId, cx, cz, cfg.shallowTargetStatus);
                        // v0.11.4: 浅层请求以前完全不记账 → 绕过在途窗口限流. 现在同样计入.
                        this.totalRequests++;
                        this.lastTickRequests++;
                        outstandingRequests.put(new WindowKey(worldId, entry.chunkPos), currentTick);
                    } else {
                        // 近处 FULL 生成
                        platform.requestChunkAsync(worldId, cx, cz);
                        this.totalRequests++;
                        this.lastTickRequests++;
                        outstandingRequests.put(new WindowKey(worldId, entry.chunkPos), currentTick);
                        generatedThisTick.add(entry.chunkPos);
                    }
                } catch (Exception e) {
                    LOG.debug("[Gen] requestChunk failed for ({},{}): {}", cx, cz, e.toString());
                }
            }
            this.lastTickPoppedCount++;
        }

        // 6. v0.11.5: 未生成区块 backlog (原 v0.6.0 FULL 待办回补队列)
        if (cfg.deferredFullQueueEnabled) {
            // v0.11.5b: lastTickDeferredPopped 已在 tick 开头重置、并在步骤 4.5 累加,
            //   这里不能再清零 (否则日志/统计读不到本 tick 的回补数).
            // a) 收集: 本 tick 没能 pop 出去的未生成候选 -> backlog
            //    v0.11.5 (用户 2026-09-13 要求): 原实现只收 d<=shallowDistanceRadius(2) 的近处条目,
            //    前方扇形 3~6 格的未生成区块进不了 backlog, 5s TTL 一到就被丢弃 —— 与"前方预生成"
            //    的目标相悖. 现在收【全部】未生成候选; 唯一例外是玩家脚下 d==0 的保底块,
            //    它必须留在实时队列, 否则会被降级成"只在低负载回补", 玩家脚下可能排不上生成.
            java.util.List<GenerationQueueEntry> snapshot = new java.util.ArrayList<>(queue);
            int collected = 0;
            for (GenerationQueueEntry e : snapshot) {
                UUID firstPlayer = e.contributingPlayers.isEmpty() ? null : e.contributingPlayers.get(0);
                if (firstPlayer == null) continue;
                int[] pc = platform.getPlayerChunkPos(firstPlayer);
                if (pc == null) continue;
                double d = Math.sqrt(
                    (double) (unpackX(e.chunkPos) - pc[0]) * (unpackX(e.chunkPos) - pc[0]) +
                    (double) (unpackZ(e.chunkPos) - pc[1]) * (unpackZ(e.chunkPos) - pc[1]));
                if (d <= 0.0) continue;                               // 玩家脚下保底: 永不留进 backlog
                if (generatedThisTick.contains(e.chunkPos)) continue;  // 本 tick 已 FULL 下单
                deferredFullQueue.put(e.chunkPos, e);                  // 移入 backlog (不丢弃, 等负载低回补)
                queue.remove(e);                                       // 从实时队列移除, 不再占本 tick 优先级
                index.remove(e.chunkPos);
                collected++;
            }
            this.lastTickCollected = collected;

            // b) 清理过期 (实时队列已在上一步搬空, 这里只兜底)
            removeExpired(currentTick, cfg);

            // c) (v0.11.5b) 回补已挪到步骤 4.5 —— 目的是与实时 pop 共用同一个每 tick 预算,
            //    避免 3 req/tick 造成 mspt 尖峰. 见步骤 4.5 的注释.

            // d) 清理 backlog: 只移除"已无意义"的条目 (玩家离线 / 长期不再相关)
            //    v0.11.5: 去掉了原实现两条静默丢弃路径 ——
            //      (1) 不再对每条做 isChunkLoaded: backlog 可达数千条, 每 tick 全量 chunk 查询会拖 mspt;
            //          "已生成"的条目在被回补选中时自然出队.
            //      (2) 超容量不再"丢队首 (最早插入)", 改为淘汰权重最低的 (最不紧急), 并且留日志痕迹.
            long now = currentTick;
            java.util.Iterator<Map.Entry<Long, GenerationQueueEntry>> sit =
                deferredFullQueue.entrySet().iterator();
            while (sit.hasNext()) {
                GenerationQueueEntry e = sit.next().getValue();
                UUID firstPlayer = e.contributingPlayers.isEmpty() ? null : e.contributingPlayers.get(0);
                int worldId = firstPlayer != null ? platform.getPlayerWorldId(firstPlayer) : 0;
                if (worldId == 0) {
                    sit.remove(); // 玩家离线
                    continue;
                }
                if (e.expiresAtTick < now - cfg.deferredFullTtlTicks) {
                    sit.remove(); // 长期不再相关 (已飞远且一直没生成)
                    this.lastTickDeferredExpired++;
                }
            }
            // 容量上限: 一次性淘汰"权重最低"的若干条 (不做逐条 O(n) 扫描)
            // deferredFullQueueMax <= 0 视为"不限容量" (避免误配成 0 导致 backlog 被整体清空)
            int over = cfg.deferredFullQueueMax > 0
                ? deferredFullQueue.size() - cfg.deferredFullQueueMax
                : 0;
            if (over > 0) {
                java.util.List<Map.Entry<Long, GenerationQueueEntry>> all =
                    new java.util.ArrayList<>(deferredFullQueue.entrySet());
                all.sort(java.util.Comparator.comparingDouble(en -> en.getValue().weight));
                int evicted = 0;
                for (int i = 0; i < over && i < all.size(); i++) {
                    deferredFullQueue.remove(all.get(i).getKey());
                    evicted++;
                }
                long n = (deferredEvictions += evicted);
                if (n <= 3 || n % 200 == 0) {
                    LOG.warn("[Gen] backlog 超容量 {} → 淘汰最低权重 {} 条 (累计 {} 次); "
                        + "若频繁出现说明 backlog 装不下, 可调大 generation.deferredFullQueueMax",
                        cfg.deferredFullQueueMax, evicted, n);
                }
            }
            this.deferredQueueSize = deferredFullQueue.size();
        } else {
            // 开关关闭: 清空待办, 回退到原有逻辑
            this.lastTickDeferredPopped = 0;
            this.deferredQueueSize = 0;
            deferredFullQueue.clear();
            removeExpired(currentTick, cfg);
        }

        // v0.11.0 诊断: 每 100 tick (5s) 汇报一次生成请求速率, 用于定位"CP 预生成是否压垮 worldgen"
        // v0.11.5: 增加 backlog 诊断 (collected/backfill/evict) —— 验证"收集后不丢弃, 负载低才回补"
        if (cfg.enabled && currentTick % 100 == 0) {
            LOG.info("[Gen] tick={} mspt={} queue={} reqThisTick={} totalReq={} deferred={} "
                    + "collected={} backfill={} evict={} outstanding={}/{} "
                    + "cand={} skippedLoaded={} enq={} flying={} "
                    + "backfillDone={} backfillOffline={} backfillExpired={}",
                currentTick, String.format(java.util.Locale.ROOT, "%.1f", currentMspt),
                queue.size(), this.lastTickRequests, this.totalRequests, this.deferredQueueSize,
                this.lastTickCollected, this.lastTickDeferredPopped, this.deferredEvictions,
                outstandingRequests.size(), cfg.maxOutstandingRequests,
                this.lastTickCandidates, this.lastTickSkippedLoaded, this.lastTickEnqueued,
                this.activeGeneratingPlayers,
                this.lastTickDeferredDone, this.lastTickDeferredOffline, this.lastTickDeferredExpired);
        }
    }

    /**
     * v0.11.8: "这个区块已经有 CP 关心的东西了吗" 的统一判断。
     *
     *   - 默认 (strictSkipGenerated=false): 用 {@code isChunkLoaded} —— 语义是
     *     "可见表里有 ChunkHolder 且票级 ≤ FULL", 即**已排期**。原版玩家票覆盖
     *     ±视距, 所以扇形候选几乎全命中 ⇒ 队列恒空 (实测 cand=106 / skipped=134 / enq=0)。
     *   - 开启后: 用 {@code isChunkReadyFull} —— 只有**真的生成到 FULL** 才算"已经有",
     *     于是"已排期但还没生成完"的前方区块会被正常入队并下 FULL 预生成票。
     */
    private static boolean chunkpilot$alreadyThere(PlatformAbstraction platform,
                                                   com.chunkpilot.config.GenerationConfig cfg,
                                                   int worldId, int cx, int cz) {
        if (cfg != null && cfg.strictSkipGenerated) {
            return platform.isChunkReadyFull(worldId, cx, cz);
        }
        return platform.isChunkLoaded(worldId, cx, cz);
    }

    // ========== 队列操作 ==========

    /**
     * addOrUpdate: O(1) 查找 (HashMap 索引), O(log Q) 插入/更新.
     * 如果 chunkPos 已存在, 取 max weight + 合并玩家 + 延长 TTL;
     * 否则新增 entry.
     */
    private void addOrUpdate(long chunkPos, double weight, long expiresAtTick, UUID playerId) {
        GenerationQueueEntry existing = index.get(chunkPos);
        if (existing != null) {
            boolean changed = false;
            if (weight > existing.weight) {
                existing.weight = weight;
                changed = true;
            }
            if (expiresAtTick > existing.expiresAtTick) {
                existing.expiresAtTick = expiresAtTick;
                changed = true;
            }
            if (!existing.contributingPlayers.contains(playerId)) {
                existing.contributingPlayers.add(playerId);
            }
            if (changed) {
                // 修复堆序: remove + re-add
                queue.remove(existing);
                queue.add(existing);
            }
            return;
        }
        GenerationQueueEntry entry = new GenerationQueueEntry(chunkPos, weight, expiresAtTick);
        entry.contributingPlayers.add(playerId);
        queue.add(entry);
        index.put(chunkPos, entry);
    }

    /**
     * 移除过期条目 (只从实时队列移除; 近处 FULL 待办收集统一在 onServerTick 步骤 6a 处理).
     */
    private void removeExpired(long currentTick, GenerationConfig cfg) {
        queue.removeIf(e -> {
            if (e.expiresAtTick < currentTick) {
                index.remove(e.chunkPos);
                return true;
            }
            return false;
        });
    }

    /** 移除某玩家的所有 chunk (玩家离线或 v < v_min) */
    private void removePlayerChunks(UUID playerId) {
        Set<Long> chunks = playerContributingChunks.remove(playerId);
        if (chunks != null) {
            for (long chunkPos : chunks) {
                GenerationQueueEntry entry = index.get(chunkPos);
                if (entry != null && entry.contributingPlayers.size() <= 1) {
                    queue.remove(entry);
                    index.remove(chunkPos);
                }
            }
        }
        // v0.11.5: 不再在这里清 backlog.
        //   本方法有两个调用点: (1) 玩家离线 (2) 玩家速度跌破 v_min (落地/减速).
        //   情况 (2) 下这些区块仍然"未生成且需要生成", 清掉就是丢弃, 与"收集后不丢弃"冲突.
        //   离线情况由步骤 6d 的 `worldId == 0` 检查在下一 tick 移除; 只是减速的条目则退化为
        //   "不再被候选扫描刷新", 由 deferredFullTtlTicks 兜底判定是否真的不再相关.
    }

    /**
     * 取前 N 个 chunk 供 /chunkpilot gen queue 命令.
     */
    public List<GenerationQueueEntry> peekTop(int n) {
        List<GenerationQueueEntry> list = new ArrayList<>();
        int i = 0;
        for (GenerationQueueEntry e : queue) {
            if (i >= n) break;
            list.add(e);
            i++;
        }
        return list;
    }

    /**
     * v0.11.5b: 测试/诊断用 —— 取出 backlog 当前全部条目 (返回副本, 不改动状态).
     * 之所以需要: 未 pop 的未生成候选会在 tick 末尾被收进 backlog, 实时队列可能已空,
     * 断言/排查只读 getQueueSize() 会看不到它们.
     */
    public List<GenerationQueueEntry> deferredEntriesForTest() {
        return new ArrayList<>(deferredFullQueue.values());
    }

    /**
     * 测试专用: 取出队顶 entry 并从队列中移除.
     */
    public GenerationQueueEntry pollForTest() {
        GenerationQueueEntry e = queue.poll();
        if (e != null) index.remove(e.chunkPos);
        return e;
    }

    /** 测试专用: 检查 index 是否包含某 chunkPos */
    public boolean indexContains(long chunkPos) {
        return index.containsKey(chunkPos);
    }

    // ========== ChunkPos 编码 (v0.3.0 自用, 不与 Mojang 互通) ==========

    public static long packChunkPos(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int unpackX(long pos) {
        return (int) pos;
    }

    public static int unpackZ(long pos) {
        return (int) (pos >> 32);
    }
}
