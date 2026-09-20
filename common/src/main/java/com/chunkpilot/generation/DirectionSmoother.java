package com.chunkpilot.generation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.8.0 方向 EMA 平滑器 (供生成器 deadline 排序用).
 *
 * 急转保护: 玩家从向东 70 m/s 突然转向北, deadline 假设直线路径,
 * 会瞬间把生成资源从东侧切到北侧 — 东侧已部分生成的区块被放弃, 北侧从零开始.
 *
 * 方案: 指数移动平均 (EMA) 平滑方向 + 扇形角度自适应放宽.
 *   dir_smooth = normalize(0.7 × dir_smooth + 0.3 × dir_instant)
 *
 * 平滑系数 0.7/0.3: 约 3 tick 后方向权重过半, 10 tick 后基本收敛.
 * 对 20 tps 服务器, 即 0.15s / 0.5s 的响应延迟 — 足够快, 又不会因单 tick 抖动而剧烈变化.
 */
public final class DirectionSmoother {

    /** EMA 平滑系数: 新瞬时方向权重 (0.3), 旧平滑方向权重 (0.7) */
    private static final double ALPHA = 0.3;

    private final Map<UUID, double[]> smoothed = new ConcurrentHashMap<>();

    /**
     * 更新并返回平滑方向.
     *
     * @param playerId    玩家 UUID
     * @param dirX        瞬时方向 X (cos)
     * @param dirZ        瞬时方向 Z (sin)
     * @return [dirX, dirZ] 平滑后的单位方向
     */
    public double[] update(UUID playerId, double dirX, double dirZ) {
        double[] prev = smoothed.get(playerId);
        double sx, sz;
        if (prev == null) {
            sx = dirX;
            sz = dirZ;
        } else {
            sx = 0.7 * prev[0] + ALPHA * dirX;
            sz = 0.7 * prev[1] + ALPHA * dirZ;
        }
        // 归一化
        double len = Math.sqrt(sx * sx + sz * sz);
        if (len < 1e-9) {
            sx = dirX;
            sz = dirZ;
            len = Math.sqrt(sx * sx + sz * sz);
            if (len < 1e-9) { sx = 1.0; sz = 0.0; len = 1.0; }
        }
        double[] result = { sx / len, sz / len };
        smoothed.put(playerId, result);
        return result;
    }

    /**
     * 获取当前平滑方向 (不更新).
     * @return [dirX, dirZ], 无历史时返回 null
     */
    public double[] get(UUID playerId) {
        return smoothed.get(playerId);
    }

    /**
     * 计算瞬时方向与平滑方向的偏离角度 (弧度).
     * @return [0, π]
     */
    public static double deviationRad(double dirX, double dirZ,
                                      double smoothX, double smoothZ) {
        double dot = dirX * smoothX + dirZ * smoothZ;
        if (dot > 1.0) dot = 1.0;
        if (dot < -1.0) dot = -1.0;
        return Math.acos(dot);
    }

    /** 移除已离线玩家 */
    public void removePlayer(UUID playerId) {
        smoothed.remove(playerId);
    }
}
