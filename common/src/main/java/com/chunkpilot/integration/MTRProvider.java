package com.chunkpilot.integration;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.SectorCalculator.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * MTR (Minecraft Transit Railway) 联动
 * 列车走固定轨道，精确预加载
 *
 * 关键设计：
 *  - 完全反射实现，common 模块不依赖 MTR jar
 *  - 反射结果缓存 cacheTicks（默认 10 ticks = 0.5s）— 避免每 tick 反射
 *  - 失败回退到原版逻辑（不崩服务端）
 *  - 服务端 modId 决定是否启用，客户端是否装不影响
 *
 * 适配的 MTR 类（按版本顺序尝试）：
 *  - mtr.entity.VehicleEntity
 *  - org.mtr.mod.entity.VehicleEntity
 *  - mtr.mof.entity.VehicleEntity
 */
public class MTRProvider implements VehicleProvider, IntegrationManager.ProviderWithCache {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private boolean loaded = false;
    private Class<?> vehicleClass = null;
    private Method getPathMethod = null;
    private Method getXMethod = null;
    private Method getZMethod = null;
    private Method getYMethod = null;
    private Method vehicleGetXMethod = null;
    private Method vehicleGetZMethod = null;

    private ChunkPilotConfig.Integration config = new ChunkPilotConfig.Integration();
    private final VehicleProvider.Cache<List<ChunkPos>> pathCache = new VehicleProvider.Cache<>(10);

    public boolean isAvailable() { return loaded; }

    public void detect() {
        String[] candidates = {
            "mtr.entity.VehicleEntity",
            "org.mtr.mod.entity.VehicleEntity",
            "mtr.mof.entity.VehicleEntity"
        };
        for (String className : candidates) {
            try {
                Class<?> c = Class.forName(className);
                // 验证必要方法存在
                Method getPath = findMethod(c, "getPath");
                if (getPath == null) continue;
                this.vehicleClass = c;
                this.getPathMethod = getPath;
                // Rail 类（List<Rail>）的方法
                // Rail 有 getX/getY/getZ
                try {
                    Class<?> railClass = Class.forName("mtr.data.Rail");
                    this.getXMethod = railClass.getMethod("getX");
                    this.getYMethod = railClass.getMethod("getY");
                    this.getZMethod = railClass.getMethod("getZ");
                } catch (ClassNotFoundException | NoSuchMethodException e) {
                    // 新 MTR 可能是 mtr.path.Path 的 getNodes() 等
                    try {
                        Class<?> nodeClass = Class.forName("mtr.path.PathFinder");
                        // 老版本兼容
                    } catch (ClassNotFoundException ignored) {}
                }
                // 车辆位置的 fallback
                this.vehicleGetXMethod = findMethod(c, "getX");
                this.vehicleGetZMethod = findMethod(c, "getZ");
                if (this.vehicleGetXMethod == null) {
                    this.vehicleGetXMethod = findMethod(c, "getBlockX");
                }
                if (this.vehicleGetZMethod == null) {
                    this.vehicleGetZMethod = findMethod(c, "getBlockZ");
                }
                this.loaded = true;
                LOG.info("[ChunkPilot] MTR: detected class {}", className);
                return;
            } catch (ClassNotFoundException e) {
                // 尝试下一个
            } catch (Throwable t) {
                LOG.warn("[ChunkPilot] MTR detect error for {}: {}", className, t.toString());
            }
        }
        loaded = false;
    }

    private static Method findMethod(Class<?> c, String name) {
        try {
            return c.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public void updateConfig(ChunkPilotConfig.Integration cfg) {
        this.config = cfg;
    }

    @Override
    public String getName() { return "MTR"; }

    @Override
    public boolean isOnVehicle(Object player) {
        if (!loaded) return false;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            return vehicle != null && vehicleClass.isInstance(vehicle);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean hasFixedPath() { return true; }

    @Override
    public List<ChunkPos> getRailPath(Object player, int lookAheadChunks) {
        if (!loaded || getPathMethod == null) return List.of();
        List<ChunkPos> result = new ArrayList<>();
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null || !vehicleClass.isInstance(vehicle)) return List.of();

            Object pathObj = getPathMethod.invoke(vehicle);
            if (!(pathObj instanceof List<?> rails) || rails.isEmpty()) return List.of();

            // 找到玩家当前位置对应的 rail index
            double px = readVehicleX(vehicle);
            double pz = readVehicleZ(vehicle);
            int startIdx = findNearestRailIndex(rails, px, pz);
            if (startIdx < 0) startIdx = 0;

            // 收集前方 N 个 rail 的 chunk 坐标
            int end = Math.min(rails.size(), startIdx + lookAheadChunks + 1);
            for (int i = startIdx + 1; i < end; i++) {
                Object rail = rails.get(i);
                if (getXMethod == null) break;
                double rx = (double) getXMethod.invoke(rail);
                double rz = (double) getZMethod.invoke(rail);
                result.add(new ChunkPos(blockToChunk(rx), blockToChunk(rz)));
            }
        } catch (Throwable t) {
            LOG.warn("[ChunkPilot] MTR getRailPath failed: {}", t.toString());
        }
        return result;
    }

    @Override
    public List<ChunkPos> getRailPathCached(Object player, int lookAheadChunks) {
        java.util.UUID id = playerUuidOf(player);
        if (id == null) return getRailPath(player, lookAheadChunks);
        return pathCache.get(id, currentTick(), () -> getRailPath(player, lookAheadChunks));
    }

    private long currentTick() {
        return com.chunkpilot.ChunkPilot.getInstance() != null
            ? com.chunkpilot.ChunkPilot.getInstance().getOptimizer().getCurrentTick() : 0L;
    }

    private java.util.UUID playerUuidOf(Object player) {
        try {
            Object id = player.getClass().getMethod("getUUID").invoke(player);
            return id instanceof java.util.UUID u ? u : null;
        } catch (Throwable t) { return null; }
    }

    @Override
    public void invalidateCache(java.util.UUID playerId) {
        pathCache.invalidate(playerId);
    }

    private double readVehicleX(Object vehicle) {
        try {
            if (vehicleGetXMethod != null) {
                Object v = vehicleGetXMethod.invoke(vehicle);
                if (v instanceof Number n) return n.doubleValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private double readVehicleZ(Object vehicle) {
        try {
            if (vehicleGetZMethod != null) {
                Object v = vehicleGetZMethod.invoke(vehicle);
                if (v instanceof Number n) return n.doubleValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private int findNearestRailIndex(List<?> rails, double px, double pz) {
        if (getXMethod == null) return 0;
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;
        try {
            for (int i = 0; i < rails.size(); i++) {
                Object rail = rails.get(i);
                double rx = (double) getXMethod.invoke(rail);
                double rz = (double) getZMethod.invoke(rail);
                double d = Math.hypot(rx - px, rz - pz);
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = i;
                }
            }
        } catch (Throwable ignored) {}
        return bestIdx;
    }

    private static int blockToChunk(double block) {
        return (int) Math.floor(block / 16.0);
    }

    @Override
    public double getSpeed(Object player) { return 0; }

    @Override
    public double getDirection(Object player) { return 0; }

    @Override
    public int getLookAheadChunks() { return config.lookAheadChunks; }
    @Override
    public int getTrackSideRadius() { return config.trackSideRadius; }
    @Override
    public double getMinSpeed() { return config.minSpeed; }
    @Override
    public double getSectorAngle() { return config.sectorAngle; }
}
