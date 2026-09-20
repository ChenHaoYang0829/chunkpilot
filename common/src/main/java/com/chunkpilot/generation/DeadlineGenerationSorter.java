package com.chunkpilot.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * v0.8.0 生成器 deadline 排序器 (替代 v0.3.0 权重公式).
 *
 * 2026-08-10 定稿 (deepseek-v4-pro 评审 + 用户拍板):
 *   - 生成环节目标: 最小化"玩家到达时区块未就绪"的概率
 *   - deadline(chunk) = ρ / max(v, v_min)  — 玩家还有多少秒到达
 *   - 排序键 = deadline 升序 (零参数, 物理直觉强)
 *   - 候选集 = 速度方向 ±θ° 扇形, 半径 = min(viewDistance, v × T_lookahead)
 *   - 静止回退: v < v_min → 全向 + 纯距离排序
 *   - 急转保护: 方向 EMA 平滑 + 扇形角度自适应放宽 (15°~45° 偏离线性放宽到 90°)
 *
 * 边界处理:
 *   - 静止 (v < v_min): θ=360°, R=viewDistance, sortKey=ρ
 *   - 急转 (deviation > 45°): θ=90° 宽扇形兜底, 覆盖新旧两个方向
 *   - 后方: 扇形已排除 (α > θ_eff), 无需额外处理
 */
public final class DeadlineGenerationSorter {

    /** 稳定飞行扇形半角 (度) */
    public static final double SECTOR_ANGLE_BASE_DEG = 60.0;
    /** 急转时扇形半角上限 (度) */
    public static final double SECTOR_ANGLE_MAX_DEG = 90.0;
    /** 开始放宽扇形的偏离阈值 (度) */
    public static final double DEVIATION_THRESHOLD_DEG = 15.0;
    /** 完全放宽的偏离阈值 (度) */
    public static final double DEVIATION_MAX_DEG = 45.0;

    private DeadlineGenerationSorter() {}

    /**
     * 计算生成候选区块, 按 deadline 升序返回 top-K.
     *
     * @param playerChunkX  玩家区块 X
     * @param playerChunkZ  玩家区块 Z
     * @param vMs           玩家当前速率 (m/s)
     * @param dirX          瞬时方向 X (cos)
     * @param dirZ          瞬时方向 Z (sin)
     * @param smoothX       平滑方向 X (cos)
     * @param smoothZ       平滑方向 Z (sin)
     * @param viewDistance  服务端视距 (chunks)
     * @param lookAheadSeconds 提前量 (秒)
     * @param vMinMs        静止判定阈值 (m/s)
     * @param topK          返回数量上限
     * @return 按 deadline 升序的区块坐标列表 [x, z]
     */
    public static List<int[]> sortByDeadline(
            int playerChunkX, int playerChunkZ,
            double vMs, double dirX, double dirZ,
            double smoothX, double smoothZ,
            int viewDistance, double lookAheadSeconds,
            double vMinMs, int topK) {

        // 1. 确定扇形参数
        double halfAngleRad;
        int radiusChunks;
        // 防御: 静止判定阈值必须 > 0, 否则 vMs=0 时 vEff=0 → 排序键 +Infinity, 排序失效
        if (!(vMinMs > 0.0)) vMinMs = 0.5;
        boolean stationary = vMs < vMinMs;

        if (stationary) {
            halfAngleRad = Math.PI;                 // 全向
            radiusChunks = viewDistance;            // 标准半径
        } else {
            // 急转保护: 瞬时方向偏离平滑方向越大, 扇形越宽
            double deviationDeg = Math.toDegrees(
                DirectionSmoother.deviationRad(dirX, dirZ, smoothX, smoothZ));
            double thetaDeg = SECTOR_ANGLE_BASE_DEG;
            if (deviationDeg > DEVIATION_THRESHOLD_DEG) {
                double t = (deviationDeg - DEVIATION_THRESHOLD_DEG)
                         / (DEVIATION_MAX_DEG - DEVIATION_THRESHOLD_DEG);
                if (t > 1.0) t = 1.0;
                thetaDeg = SECTOR_ANGLE_BASE_DEG
                         + t * (SECTOR_ANGLE_MAX_DEG - SECTOR_ANGLE_BASE_DEG);
            }
            halfAngleRad = Math.toRadians(thetaDeg);

            // 速度自适应扫描半径: R = min(viewDistance, v × T_lookahead)
            double lookAheadChunks = vMs * lookAheadSeconds / 16.0;
            radiusChunks = (int) Math.ceil(Math.min(viewDistance, lookAheadChunks));
            if (radiusChunks < 1) radiusChunks = 1;
        }

        // 2. 收集候选区块
        List<int[]> candidates = SectorScanner.scan(
            playerChunkX, playerChunkZ,
            dirX, dirZ, radiusChunks, halfAngleRad);

        // 3. 按 deadline 排序 (升序)
        final double vEff = stationary ? 1.0 : Math.max(vMs, vMinMs);
        final int pcx = playerChunkX;
        final int pcz = playerChunkZ;

        candidates.sort(Comparator.comparingDouble(c -> {
            double dx = c[0] - pcx;
            double dz = c[1] - pcz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (stationary) return dist;            // 纯距离排序
            return dist * 16.0 / vEff;              // deadline = 距离(m) / 速度(m/s)
        }));

        // 4. 取 top-K
        if (candidates.size() > topK) {
            return new ArrayList<>(candidates.subList(0, topK));
        }
        return candidates;
    }
}
