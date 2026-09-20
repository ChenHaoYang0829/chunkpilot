package com.chunkpilot.integration;

import com.chunkpilot.core.SectorCalculator.ChunkPos;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交通 Mod 联动统一接口 (SPI)
 * 每个交通 mod 实现一个 Provider，核心逻辑不关心具体是哪个 mod
 */
public interface VehicleProvider {

    /**
     * Provider 名称（调试/日志用）
     */
    String getName();

    /**
     * 检测玩家是否在此 mod 的载具上
     * @param player 服务端玩家对象（Object，避免 common 依赖 mc api）
     */
    boolean isOnVehicle(Object player);

    /**
     * 是否有固定轨道路径
     * true → 调用 getRailPathCached() 做精确预加载
     * false → 调用 getSpeed()/getDirection() 做扇形加载
     */
    boolean hasFixedPath();

    /**
     * 原始轨道路径（每次都重新反射）
     * Provider 自己负责 cache
     */
    List<ChunkPos> getRailPath(Object player, int lookAheadChunks);

    /**
     * 带缓存的轨道路径（默认实现：每 cacheTicks tick 缓存一次）
     * 核心层通过这个方法调用，避免每 tick 反射开销
     */
    default List<ChunkPos> getRailPathCached(Object player, int lookAheadChunks) {
        // 默认走简单缓存
        return getRailPath(player, lookAheadChunks);
    }

    /** 载具速度 (blocks/tick) */
    double getSpeed(Object player);

    /** 载具移动方向 (弧度) */
    double getDirection(Object player);

    /** 配置参数：轨道向前预加载 chunks */
    default int getLookAheadChunks() { return 8; }
    /** 配置参数：轨道两侧宽度（格子） */
    default int getTrackSideRadius() { return 2; }
    /** 配置参数：扇形模式最低速度 */
    default double getMinSpeed() { return 1.0; }
    /** 配置参数：扇形模式角度 */
    default double getSectorAngle() { return 60; }

    /**
     * 通用缓存辅助：带 cacheTicks 周期的反射结果缓存
     * Provider 可以直接用这个对象避免重复反射
     */
    class Cache<T> {
        private final Map<UUID, Entry<T>> map = new ConcurrentHashMap<>();
        private final long cacheTicks;

        public Cache(long cacheTicks) {
            this.cacheTicks = cacheTicks;
        }

        public T get(UUID playerId, long currentTick, java.util.function.Supplier<T> supplier) {
            Entry<T> e = map.get(playerId);
            if (e != null && currentTick - e.tick < cacheTicks) {
                return e.value;
            }
            T v = supplier.get();
            map.put(playerId, new Entry<>(v, currentTick));
            return v;
        }

        public void invalidate(UUID playerId) {
            map.remove(playerId);
        }

        public void clear() { map.clear(); }

        private record Entry<T>(T value, long tick) {}
    }
}
