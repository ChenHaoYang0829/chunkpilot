package com.chunkpilot.generation;

import java.util.ArrayList;
import java.util.List;

/**
 * v0.8.0 扇形区块扫描器 (供生成器 deadline 排序用).
 *
 * 只扫描玩家速度方向 ±θ° 扇形内的区块, 将候选集从 O(R²) 降到 O(R²×θ/360).
 * 静止时 (v < v_min) 退化为全向扫描.
 */
public final class SectorScanner {

    private SectorScanner() {}

    /**
     * 扫描扇形内的候选区块.
     *
     * @param playerChunkX 玩家区块 X
     * @param playerChunkZ 玩家区块 Z
     * @param dirX         方向单位向量 X (cos)
     * @param dirZ         方向单位向量 Z (sin)
     * @param radiusChunks 扫描半径 (chunks)
     * @param halfAngleRad 扇形半角 (弧度); 传 π 表示全向
     * @return 候选区块坐标列表 [x, z]
     */
    public static List<int[]> scan(
            int playerChunkX, int playerChunkZ,
            double dirX, double dirZ,
            int radiusChunks, double halfAngleRad) {

        List<int[]> result = new ArrayList<>();
        boolean omnidirectional = halfAngleRad >= Math.PI - 1e-6;

        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (dx == 0 && dz == 0) continue;
                double distSq = (double) dx * dx + (double) dz * dz;
                if (distSq > (double) radiusChunks * radiusChunks) continue;

                if (!omnidirectional) {
                    double dist = Math.sqrt(distSq);
                    double cosAngle = (dx * dirX + dz * dirZ) / dist;
                    if (cosAngle > 1.0) cosAngle = 1.0;
                    if (cosAngle < -1.0) cosAngle = -1.0;
                    double angle = Math.acos(cosAngle);
                    if (angle > halfAngleRad) continue;
                }

                result.add(new int[]{playerChunkX + dx, playerChunkZ + dz});
            }
        }
        return result;
    }
}
