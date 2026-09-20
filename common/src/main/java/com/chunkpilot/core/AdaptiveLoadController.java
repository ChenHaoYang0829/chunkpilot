package com.chunkpilot.core;

/**
 * 共享负载自适应控制器 — 统一生成侧与票侧的自适应回路 (v0.10 重构).
 *
 * 目标 (2026-08-27 评审后落地, 前瞻部分暂缓):
 *   1. 修掉 SectorBudgetController "保底下限把高负载降档顶回去" 的自废武功:
 *      高负载档位真的降, 只有低负载档才保底防饿死 (down-adjust 优先于 floor).
 *   2. 统一生成侧(GenerationScheduler)与票侧(SectorBudgetController)的自适应:
 *      二者共享同一个平滑 MSPT, 避免两套回路各自反应、互相抵消.
 *   3. MSPT 用不对称 EMA 平滑: 上升快(立刻响应 worldgen 爆炸), 下降慢(避免抖动/过冲).
 *      生成侧用连续映射 (非旧的 ±1 步进), 负载变化时生成量跟随平滑 MSPT 连续变化.
 *
 * 负载系数 loadFactor ∈ [0,1]:
 *   1.0 = 负载健康可满载; 0.0 = 高负载/紧急停手.
 *   在 [MSPT_VERY_LOW=30, MSPT_HIGH=45] 之间线性插值.
 */
public final class AdaptiveLoadController {

    // ===== 档位阈值 (ms) =====
    /** 紧急: 完全停手 (>= 此值) */
    public static final double MSPT_EMERGENCY = 50.0;
    /** 高负载: loadFactor=0, 票侧 20% */
    private static final double MSPT_HIGH = 45.0;
    /** 中: 票侧 40%; 只有低于此档才允许保底 (防止把降档顶回去) */
    private static final double MSPT_MID = 40.0;
    /** 低: 票侧 60% */
    private static final double MSPT_LOW = 35.0;
    /** 很低: 票侧 80%, loadFactor=1.0 的起点 */
    private static final double MSPT_VERY_LOW = 30.0;

    // ===== 不对称 EMA =====
    /** 上升系数 (快): mspt 升高时立刻响应 */
    private static final double ALPHA_RISE = 0.5;
    /** 下降系数 (慢): mspt 回落后缓慢恢复, 避免抖动/过冲 */
    private static final double ALPHA_FALL = 0.05;

    private double smoothedMspt = 0.0;
    private boolean initialized = false;

    // ===== 采样 (每 tick 由 ChunkLoadOptimizer.onServerTick 调用一次) =====

    /**
     * 喂入当前原始 MSPT, 更新不对称 EMA 平滑值, 返回平滑后的 MSPT.
     */
    public double sample(double rawMspt) {
        if (!initialized) {
            smoothedMspt = Math.max(0, rawMspt);
            initialized = true;
        } else if (rawMspt > smoothedMspt) {
            smoothedMspt += (rawMspt - smoothedMspt) * ALPHA_RISE;
        } else {
            smoothedMspt += (rawMspt - smoothedMspt) * ALPHA_FALL;
        }
        return smoothedMspt;
    }

    /** 当前平滑 MSPT (生成侧与票侧共享此值) */
    public double getSmoothedMspt() { return smoothedMspt; }

    // ===== 负载系数 =====

    /** 由指定 mspt 推导负载系数 [0,1] */
    public static double loadFactor(double mspt) {
        if (mspt >= MSPT_HIGH) return 0.0;
        if (mspt <= MSPT_VERY_LOW) return 1.0;
        return (MSPT_HIGH - mspt) / (MSPT_HIGH - MSPT_VERY_LOW);
    }

    // ===== 生成侧 (GenerationScheduler) =====

    /**
     * 静态: 由平滑 mspt 推导本 tick 允许生成的 chunk 数 (连续映射).
     * 紧急(>=50)直接归 0; 其余按 loadFactor 线性缩放, 高负载可降到 0.
     *
     * @param maxCount 配置上限 (GenerationConfig.maxChunksPerTick)
     * @param mspt     平滑后的 MSPT (生产环境传共享控制器的平滑值)
     */
    public static int suggestGenerationCount(int maxCount, double mspt) {
        if (maxCount <= 0) return 0;
        if (mspt >= MSPT_EMERGENCY) return 0;
        return (int) Math.round(maxCount * loadFactor(mspt));
    }

    /** 实例: 用本控制器共享的平滑 MSPT */
    public int suggestGenerationCount(int maxCount) {
        return suggestGenerationCount(maxCount, smoothedMspt);
    }

    // ===== 票侧 (SectorBudgetController) =====

    /**
     * 用共享平滑 MSPT 推导扇形 ticket 预算.
     *
     * 分档预算 (高负载低比例), 关键改动:
     *   保底只对"负载未到需要减量的档位 (mspt < MSPT_MID)"生效;
     *   一旦进入高负载降档, 保底不再把预算顶回去, 让降档真正生效.
     *
     * @param maxBudget 硬上限 (ChunkPilotConfig.maxSectorTickets)
     * @return [0, maxBudget] 的当前预算
     */
    public int suggestBudget(int maxBudget) {
        if (maxBudget <= 0) return 0;
        if (smoothedMspt >= MSPT_EMERGENCY) return 0;  // 紧急: 完全停手

        double ratio;
        if (smoothedMspt >= MSPT_HIGH) {
            ratio = 0.2;   // 高负载: 20%
        } else if (smoothedMspt >= MSPT_MID) {
            ratio = 0.4;   // 中: 40%
        } else if (smoothedMspt >= MSPT_LOW) {
            ratio = 0.6;   // 低: 60%
        } else if (smoothedMspt >= MSPT_VERY_LOW) {
            ratio = 0.8;   // 很低: 80%
        } else {
            ratio = 1.0;   // 健康: 100%
        }
        int target = (int) Math.round(maxBudget * ratio);

        // 保底只在负载未进入"需要减量"档位时生效, 防止把降档顶回去
        if (smoothedMspt < MSPT_MID) {
            int minBudget = Math.max(1, (int) Math.round(maxBudget * 0.2));
            target = Math.max(minBudget, target);
        }
        return Math.max(0, Math.min(maxBudget, target));
    }
}
