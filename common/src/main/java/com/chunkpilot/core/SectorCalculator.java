package com.chunkpilot.core;

import com.chunkpilot.config.ChunkPilotConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * 扇形区块计算器
 * 根据速度和方向计算应加载的区块集合
 */
public class SectorCalculator {
    
    /**
     * 计算应加载的区块集合
     * @param playerChunkX 玩家区块 X
     * @param playerChunkZ 玩家区块 Z
     * @param speed 速度 (blocks/tick)
     * @param direction 移动方向 (弧度)
     * @param renderDistance 服务端视距
     * @param config 配置
     * @return 应加载的区块坐标集合，null 表示不干预（使用原版）
     */
    public static Set<ChunkPos> computeChunks(
            int playerChunkX, int playerChunkZ,
            double speed, double direction,
            int renderDistance,
            ChunkPilotConfig config) {
        
        ChunkPilotConfig.SpeedTier tier = config.matchTier(speed);
        if (tier == null || tier.coreRadius == -1) {
            return null; // 不干预，使用原版
        }
        
        Set<ChunkPos> chunks = new HashSet<>();
        
        // 1. 保底圆
        addCircle(chunks, playerChunkX, playerChunkZ, tier.coreRadius);
        
        // 2. 前方扇形
        int sectorRadius = resolveRadius(tier.sectorRadius, renderDistance);
        addSector(chunks, playerChunkX, playerChunkZ,
                direction, Math.toRadians(tier.sectorAngle), sectorRadius);
        
        // 3. 尾部
        if (tier.tailRadius > 0) {
            addCircle(chunks, playerChunkX, playerChunkZ, tier.tailRadius);
        }
        
        return chunks;
    }
    
    private static int resolveRadius(int configRadius, int renderDistance) {
        if (configRadius == -1) return renderDistance;
        if (configRadius == -2) return renderDistance + 2;
        return configRadius;
    }
    
    /**
     * 添加圆形区域
     */
    public static void addCircle(Set<ChunkPos> chunks, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    chunks.add(new ChunkPos(cx + dx, cz + dz));
                }
            }
        }
    }
    
    /**
     * 添加前方扇形区域
     * @param direction 移动方向弧度 (0=+X, π/2=+Z)
     * @param halfAngle 半角 (弧度)
     */
    public static void addSector(Set<ChunkPos> chunks, int cx, int cz,
                                   double direction, double halfAngle, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                int distSq = dx * dx + dz * dz;
                if (distSq > radius * radius) continue;
                
                double chunkAngle = Math.atan2(dz, dx);
                double angleDiff = normalizeAngle(chunkAngle - direction);
                
                if (Math.abs(angleDiff) <= halfAngle) {
                    chunks.add(new ChunkPos(cx + dx, cz + dz));
                }
            }
        }
    }
    
    /**
     * 将角度归一化到 [-π, π]
     */
    public static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
    
    /**
     * 高速无轨载具的纯扇形计算（不走速度档位）
     * 用于 Provider 模式下的飞艇/飞机
     */
    public static Set<ChunkPos> computeSector(
            int chunkX, int chunkZ, double direction, double halfAngleRad, int radius) {
        Set<ChunkPos> chunks = new HashSet<>();
        addSector(chunks, chunkX, chunkZ, direction, halfAngleRad, radius);
        return chunks;
    }

    /**
     * 简单的 ChunkPos 替代（不依赖 Minecraft API，方便 common 模块使用）
     */
    public record ChunkPos(int x, int z) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkPos other)) return false;
            return x == other.x && z == other.z;
        }
        
        @Override
        public int hashCode() {
            return x * 31 + z;
        }
        
        @Override
        public String toString() {
            return "[" + x + ", " + z + "]";
        }
        
        /**
         * 编码为 long（与 Minecraft ChunkPos.asLong 一致：低 32 位 = x，高 32 位 = z）
         */
        public long toLong() {
            // ⚠ 这里的 `ChunkPos` 是 **SectorCalculator 的嵌套类**, 不是 net.minecraft.world.level.ChunkPos
            //   (26.3 把 MC 的 asLong 改名为 pack, 但本嵌套类自带的 asLong(int,int) 不受影响)。
            return ChunkPos.asLong(x, z);
        }
        
        /**
         * 静态编码方法
         */
        public static long asLong(int x, int z) {
            return ((long) z << 32) | (x & 0xFFFFFFFFL);
        }
        
        /**
         * 从 long 解码 X 坐标
         */
        public static int unpackX(long packed) {
            return (int) packed;
        }
        
        /**
         * 从 long 解码 Z 坐标
         */
        public static int unpackZ(long packed) {
            return (int) (packed >> 32);
        }
    }
}