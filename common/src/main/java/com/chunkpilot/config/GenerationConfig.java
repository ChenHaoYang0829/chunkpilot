package com.chunkpilot.config;

/**
 * v0.8.0 生成器配置 — deadline 排序 (替代 v0.3.0 权重公式)
 *
 * 2026-08-10 定稿 (deepseek-v4-pro 评审 + 用户拍板):
 *   - 生成器走 deadline 排序: deadline(chunk) = 距离 / 速度 (零参数, 物理直觉)
 *   - 候选集 = 速度方向 ±θ° 扇形, 半径 = min(viewDistance, v × T_lookahead)
 *   - 静止回退: v < v_min → 全向 + 纯距离排序
 *   - 急转保护: 方向 EMA 平滑 + 扇形角度自适应放宽
 *   - 删除 v0.3.0 旧公式参数: n, logBase (l/L 末端加权废弃)
 *
 * 全部字段在 chunkpilot.toml [generation] 段可调
 */
public class GenerationConfig {
    /** 风险开关: false = 完全跳过生成器调度 */
    public boolean enabled = false;

    /** 最低速度阈值 (chunks/s), 低于此速度不干预 */
    public double v_min = 0.7;

    /** 提前量 (秒): 决定向前看多远, R = min(viewDistance, v × T_lookahead) */
    public double lookAheadSeconds = 3.0;

    /** 预测 chunk 过期时间 (秒): 超过此时间的预测条目丢弃 */
    public double predictionTTLSeconds = 5.0;

    /** 每 tick 最大生成 chunk 数 (防止 mspt 飙升) — v0.10.2: 2→1, 进一步压每 tick 生成量 */
    public int maxChunksPerTick = 1;

    /** 自适应 MSPT 目标 (ms): 超过此值降速生成 */
    public double targetMspt = 35.0;

    // ===== v0.8.0 deadline 排序参数 =====

    /** 稳定飞行扇形半角 (度), 默认 60° */
    public double sectorAngleBaseDeg = 60.0;

    /** 急转时扇形半角上限 (度), 默认 90° */
    public double sectorAngleMaxDeg = 90.0;

    /** 静止判定阈值 (m/s): v < 此值 → 全向 + 纯距离排序 */
    public double vMinMs = 0.5;

    /**
     * v0.6.0: 浅层预生成 (调度器而非增压器).
     *
     * 0804 崩溃教训: FULL 预生成会把方块实体提前塞进主线程.
     * 远处 chunk 只生成到"地形骨架" (CARVERS: 噪声+地表+洞穴, 无方块实体),
     * 玩家接近时 vanilla 自然补 FULL.
     */
    /** 浅层预生成开关 */
    public boolean shallowGenerationEnabled = true;

    /** 浅层预生成的目标深度 (ChunkStatus 名).
     *  v0.10.1 修正: 原 "CARVERS" 在 1.21.3 已不是有效状态名 (解析成 empty 不生成),
     *  改用 "surface"(level 35, 噪声+地表, 无方块实体/特征). */
    public String shallowTargetStatus = "surface";

    /** 距离半径阈值 (chunks): d <= 此值用 FULL 深度, d > 此值用浅层. v0.10.2: 8->2 与扇形错开, 减少重复 FULL. */
    public int shallowDistanceRadius = 2;

    /**
     * v0.6.0: FULL 待办回补队列 (调度器而非增压器).
     *
     * 0804 教训 + 用户设计意图: 本该 FULL 生成但本 tick 没生成完的 chunk
     * 不能因 TTL 丢弃 — 保留在待办清单里, 等 MSPT 低时回补 FULL 生成.
     * 这就是"负载高囤需求, 负载低消化"的调度器语义.
     */
    /** FULL 待办回补开关 */
    public boolean deferredFullQueueEnabled = true;

    /**
     * v0.11.5: backlog 容量上限. 原默认 512 对"收集全部未生成候选"太小 → 会被频繁淘汰.
     * 超容量时淘汰**权重最低**的条目, 并打 warn 日志 (不再静默丢队首).
     */
    public int deferredFullQueueMax = 8192;

    /** 回补触发: 仅当 mspt 低于 targetMspt - 此值 (ms) 时才回补 FULL */
    public double deferredFullMsptMargin = 10.0;

    /** 每 tick 最多回补几个 FULL (负载回补速率上限) */
    public int deferredFullMaxPerTick = 2;

    /**
     * v0.11.5: backlog 条目"不再相关"的兜底保留时长 (ticks, 默认 1200 = 60s).
     *
     * 语义: 原默认 300 (15s) 太短 —— 高速飞行时"收集了但还没生成"的区块会在 15s 后被静默丢弃.
     *   现在条目在其仍是候选期间 (每次候选扫描刷新 expiresAtTick) 永不超时; 只有玩家飞远、
     *   该区块不再是任何玩家的候选之后, 再保留本时长仍未被生成才移除.
     *   注意: 这是唯一的时间型移除, 是防内存无界的安全阀, 不是"未生成就丢".
     */
    public int deferredFullTtlTicks = 1200;

    /**
     * v0.9.0: CP 独占生成模式 (无 C2ME 时停掉原版生成队列).
     *
     * 背景: CP 单独 (无 C2ME) 时, 单线程 worldgen 跟不上玩家高速飞行,
     *   原版 PLAYER ticket 全向生成 + CP 前方预生成叠加 → 生成队列被塞满 → 服务器过载掉线.
     *
     * 开启后: 检测到没有 C2ME 时, 拦截 ChunkMap.scheduleGenerationTask,
     *   只放行 CP 权重公式请求的 chunk, 停掉原版自动生成.
     * 有 C2ME 时自动忽略 (C2ME 自己管生成).
     */
    public boolean exclusiveGenerationNoC2me = true;

    /**
     * v0.11.0: CP 生成请求的"在途窗口"上限 (admission control).
     *
     * 实测 (2026-09-07 飞行测试, 仪器化 [Gen] 日志):
     *   maxChunksPerTick=1 + deferredFullMaxPerTick=2 会让 CP 每 tick 发 3 个生成请求 (=60 个/秒),
     *   而玩家只以 1.66 chunks/s 前进 —— 请求速率是需求的 ~36 倍.
     *   结果: worldgen 积压, 服务器 tick 时间飙到 170ms (≈6 TPS) → 玩家所在区块迟迟不到 FULL
     *   → 主线程 Entity.tick -> getChunk 阻塞数秒 (飞行中 3~6s 的卡顿/掉帧).
     *
     * 控制律: 只在"CP 已请求但尚未生成完"的 chunk 数低于本上限时才发新请求.
     *   这样请求速率自动跟随 worldgen 实际吞吐 (自我限速), 积压有界.
     *   0 或负数 = 关闭窗口限流 (回退旧行为).
     */
    public int maxOutstandingRequests = 32;

    /**
     * v0.11.0: 在途窗口条目 TTL (ticks). 超过此时间仍未 loaded 的请求强制移出窗口.
     * 实测教训: 玩家飞远后 ticket 过期, 这些 chunk 可能永远不 loaded → 窗口永久 32/32, CP 预生成停摆.
     */
    public int outstandingTtlTicks = 200;

    /**
     * v0.11.8: "跳过已生成区块" 改用**真正的 FULL 判定** (isChunkReadyFull),
     * 而不是原来的 isChunkLoaded(只代表"已排期")。
     *
     * 背景 (2026-09-20 第二实例实测, 34 m/s + C2ME):
     *   旧语义下候选 106 个/tick 里有 134 次命中"已有 holder"⇒ 全部跳过 ⇒ 入队 0 ⇒
     *   队列恒空、调度器实际只在请求玩家脚下那一个区块。开启本项后调度器才真的会
     *   对"已排期但还没生成完"的前方区块下 FULL 预生成票。
     *
     * 默认 false (保持旧行为), 需要时打开并观察 MSPT 与前方前沿。
     */
    public boolean strictSkipGenerated = false;
}
