package com.chunkpilot.generation;

/**
 * v0.8.0 区块权重算法 — 统一公式骨架.
 *
 * 2026-08-10 定稿 (deepseek-v4-pro 评审 + 用户拍板):
 *   - 删除 v0.3.0 / v0.5.0 / v0.6.0 全部旧公式 (轨道预测 l/L 末端加权废弃)
 *   - 只保留方向权重公式骨架: w = (cos α + C1)^γ / (1 + k·ρ) × (1 + β·v/v_ref)
 *   - v_ref 硬编码 30.0 (典型鞘翅速度 m/s), 不暴露为参数
 *   - 生成器走 deadline 排序 (DeadlineGenerationSorter), 不调用本类
 *   - 发送器/客户端渲染走本类公式 (发送器参数集)
 *
 * 参数:
 *   α   = chunk 中心与玩家飞行方向的夹角 (弧度)
 *   ρ   = chunk 到玩家的欧氏距离 (chunks)
 *   v   = 玩家当前速率 (m/s)
 *   γ   = 方向锐化指数 (发送器默认 4.0, 窄锥)
 *   k   = 距离衰减系数 (发送器默认 0.15)
 *   C1  = 方向基础偏移 (发送器硬编码 0.1, 保证侧后方有底线权重)
 *   β   = 速度敏感系数 (无量纲, 发送器默认 1.0)
 *   v_ref = 30.0 硬编码 (典型鞘翅速度 m/s)
 */
public final class ChunkWeightCalculator {

    private ChunkWeightCalculator() {}

    /** 参考速度 (m/s): 典型鞘翅巡航速度, 硬编码不暴露 */
    public static final double V_REF_MS = 30.0;

    /**
     * 统一方向权重公式: w = (cos α + C1)^γ / (1 + k·ρ) × (1 + β·v/v_ref)
     *
     * @param angleRad chunk 中心与玩家飞行方向的夹角 (弧度)
     * @param d        chunk 中心到玩家的欧氏距离 (chunks)
     * @param v       玩家当前速率 (m/s), <0 当 0 处理
     * @param D        有效前方半径 (chunks) = min(viewDistance, lookAhead_chunks)
     * @param C1       方向基础偏移 (≥1 时恒正; 发送器用 0.1 让后方有底线)
     * @param k        距离衰减速率
     * @param gamma    方向锐化指数 (≥1, =1 无锐化)
     * @param beta     速度敏感系数 (无量纲, =0 无速度加成)
     * @return 权重值; 恒 > 0 (C1 保底)
     */
    public static double computeWeight(
            double angleRad, double d, double v, double D,
            double C1, double k, double gamma, double beta) {
        if (D <= 0.0) return 0.0;
        if (gamma < 1.0) gamma = 1.0;
        if (v < 0.0) v = 0.0;

        double cosA = Math.cos(angleRad);
        if (cosA > 1.0) cosA = 1.0;
        if (cosA < -1.0) cosA = -1.0;

        double rho = d / D;
        double angleFactor = cosA + C1;
        // C1 ≥ 0 时 angleFactor 可能 ≤ 0 (C1=0, α=180°), 防御
        if (angleFactor <= 0.0) angleFactor = 1e-9;
        double anglePower = Math.pow(angleFactor, gamma);

        double vBoost = 1.0 + beta * (v / V_REF_MS);

        return anglePower / (1.0 + k * rho) * vBoost;
    }

    /**
     * 是否应该激活调度 (v0.8.0: 替代 PredictionPath.shouldActivate).
     *
     * @param vBlocksPerTick 玩家速度 (blocks/tick)
     * @param vMinChunksPerS 最低速度阈值 (chunks/s)
     * @return true = 激活
     */
    public static boolean shouldActivate(double vBlocksPerTick, double vMinChunksPerS) {
        double vChunksPerS = vBlocksPerTick * 20.0 / 16.0;
        // 上限检查: v 超过 200 chunks/s (3200 b/s) 视为异常 (tp 瞬移), 不激活
        if (vChunksPerS > 200.0) return false;
        return vChunksPerS >= vMinChunksPerS;
    }

    /**
     * 计算有效前方半径 D = min(viewDistance, lookAhead_chunks)
     */
    public static double computeEffectiveRadius(double viewDistance, double lookAheadChunks) {
        return Math.min(viewDistance, lookAheadChunks);
    }

    /**
     * 计算 chunk 中心与玩家飞行方向的夹角 (弧度).
     */
    public static double computeAngleRad(double chunkCenterX, double chunkCenterZ,
                                          double playerX, double playerZ,
                                          double dirX, double dirZ) {
        double dx = chunkCenterX - playerX;
        double dz = chunkCenterZ - playerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1e-9) return 0.0;
        double cosAngle = (dx * dirX + dz * dirZ) / dist;
        if (cosAngle > 1.0) cosAngle = 1.0;
        if (cosAngle < -1.0) cosAngle = -1.0;
        return Math.acos(cosAngle);
    }

    /**
     * 投影版权重公式 (v0.9.0, 用户 08-10 拍板 + 08-12 补"取倒数"):
     *   w = [ v·(c-p)/|c-p| ] / (1 + k·ρ)
     *
     * 分子 = 接近速度 (m/s) = 速度向量 v 点乘单位方向向量 (c-p)/|c-p|，二维 x/z（忽略 Y）。
     *   - 同时编码方向 + 速度，替换原 cosα 三角函数项（含 γ）
     *   - 去掉原速度项 (1+β·v/v_ref)（投影本身已含 v）
     * 距离项 1/(1+k·ρ) 保留，ρ = d/D。
     * 负权重处理（用户 08-12 拍板方案 A）：只对 w < 0 取倒数，其他不动。
     *   - 正权重保持原值（排序顺序不变）
     *   - 负权重取倒数后变成绝对值很大的负数（1/负小数 = 大负数），降序排序排最后，不干扰正权重
     *
     * @param vMs        玩家速率 (m/s)
     * @param dirX       玩家飞行方向 x 分量（单位向量）
     * @param dirZ       玩家飞行方向 z 分量（单位向量）
     * @param chunkX     chunk 坐标 x
     * @param chunkZ     chunk 坐标 z
     * @param playerX    玩家 chunk 坐标 x
     * @param playerZ    玩家 chunk 坐标 z
     * @param D          有效前方半径 (chunks) = min(viewDistance, lookAhead_chunks)
     * @param k          距离衰减速率
     * @return 权重值; 正权重 > 0, 负权重取倒数后为负
     */
    public static double computeWeightProjection(
            double vMs, double dirX, double dirZ,
            double chunkX, double chunkZ,
            double playerX, double playerZ,
            double D, double k) {
        if (D <= 0.0) return 0.0;
        if (vMs < 0.0) vMs = 0.0;

        double dx = chunkX - playerX;
        double dz = chunkZ - playerZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-9) {
            // 玩家所在 chunk: 投影 = 0, 距离衰减 = 1/(1+0) = 1 → 权重 0
            return 0.0;
        }

        // 接近速度 = v · (c-p)/|c-p|  (二维点乘)
        double closingSpeed = vMs * (dirX * dx + dirZ * dz) / d;

        double rho = d / D;
        double w = closingSpeed / (1.0 + k * rho);

        // 方案 A: 只对负权重取倒数，其他不动
        if (w < 0.0) {
            w = 1.0 / w;
        }
        return w;
    }
}
