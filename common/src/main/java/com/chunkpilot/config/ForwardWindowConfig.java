package com.chunkpilot.config;

/**
 * v0.11.6 前瞻窗口 (Forward Look-ahead Window) — 配置.
 *
 * ============================ 为什么需要它 ============================
 * 原版给玩家的是一个**以玩家为中心**的对称加载窗口:
 *   - 区块加载 (PLAYER ticket): 半径 = server view-distance, 得到 [p-vd, p+vd] 的方块;
 *   - 客户端可见 (ChunkTrackingView): 同样是 [p-vd, p+vd] 的方块.
 * 玩家高速前进时, 前方 vd 区块处的区块**此刻才开始生成**, 而玩家
 * vd/(v/16) 秒后就会到达 —— 传送/生成跟不上时, 玩家撞上"还没送达"的区块,
 * 服务端认为玩家在方块里 → moved wrongly → 回弹("墙").
 *
 * 本功能把加载/可见窗口**整体前移**, 给前方区块提前量:
 *   1) 生成侧: 在玩家前方 A 区块处额外放一张锚点票 (level = base + A - B,
 *      base = 33 - viewDistance). 因为区块等级取所有票的最小值, 而两个原版式
 *      1-Lipschitz 等级场的 min 仍是 1-Lipschitz, 所以**不会造成依赖链断裂**
 *      (这是 v0.11.5c 停摆事故的根因: 当时给几百个预生成区块压等级并跨级断链).
 *      效果: 前方可加载距离由 vd 变成 vd + B, 后方仍是 vd.
 *   2) 发送侧: 把该玩家的 ChunkTrackingView 中心前移 S 区块 (Mixin 拦截
 *      ChunkMap.updateChunkTracking). 客户端可见/可达范围变成
 *      前 vd + S / 后 vd - S, 前方区块一旦生成就立刻下发, 而不是等玩家走近。
 *
 * 期望结果 (bench bot 指标):
 *   - far_ahead (前方已加载最远距离) 显著上升;
 *   - 巡航期间到达的区块里 forward(ahead+a_flank) 占比显著上升;
 *   - 回弹 (corrections / moved_too_quickly) 下降.
 *
 * 两种环境都生效 (含 C2ME): 只动"票"和"跟踪窗口", 不碰 C2ME 的调度器.
 */
public class ForwardWindowConfig {

    /** 总开关. false = 完全回退原版行为 (票和跟踪窗口都不动). */
    public boolean enabled = true;

    /** 激活速度阈值 (chunks/s). 低于此值不做任何干预 (静止/走路保持原版). */
    public double minSpeed = 0.7;

    /**
     * 锚点前移量 A (chunks). 锚点票放在 player + A·dir 处.
     * A = 0 → 关闭生成侧前移 (只留发送侧跟踪窗口前移).
     *
     * **经验值: A ≈ 视距 + forwardExtra/2** (视距 10, forwardExtra 6 → A = 13)。
     * 原因: 锚点的额外加载面积 = forwardExtra × (forwardExtra+1) 格, **与 A 无关**,
     * 而 A 越大 → 锚点等级越高 → 锚点那个方块越小 → 落在"玩家自身方块之外"的部分越省。
     * A=13 只多加载 42 格, A=8 要 102 格; 同样把前方可达从 10 推到 16,
     * 实测 5-bot 巡航 MSPT 从 28ms 降到 16~20ms, 而前方集中度反而更高。
     */
    public int aheadChunks = 13;

    /**
     * 生成侧前向延伸量 B (chunks). 锚点等级 = max(2, 33-vd) + A - B.
     *   B = 0 → 锚点等级 = 原版该位置的等级 → 无效果 (等同于关闭生成前移);
     *   B = A → 锚点等级 = 玩家自身区块等级 (前移最猛, 前方可达 vd+A).
     * 有效范围 [0, A], 超出会被钳制.
     */
    public int forwardExtra = 6;

    /**
     * 发送侧 (ChunkTrackingView 中心) 前移量 S (chunks). -1 = 用 aheadChunks.
     * 会被钳制到 <= min(forwardExtra, viewDistance-2) —— 玩家自身必须留在可见窗口内.
     * S 越大前方可见越远(集中度越高), 但玩家身后留在窗口内的区块越少。
     */
    public int trackingShift = 6;

    /** 锚点重算/续票的最小间隔 (tick). 1 = 每 tick 重算. */
    public int updateTicks = 2;

    /** 锚点票续期周期 (tick). CP ticket lifespan = 31 tick, 必须在到期前续期. */
    public int refreshTicks = 20;

    /**
     * 转向抑制阈值 (度). 方向变化超过它时立即重算锚点 (不等 updateTicks).
     * 目的: 转弯时不让锚点留在旧方向.
     */
    public double turnThresholdDeg = 25.0;

    /**
     * 速度上限保护 (chunks/s). 超过视为传送/异常, 不做前移 (避免把窗口甩到极远处).
     */
    public double maxSpeed = 200.0;
}
