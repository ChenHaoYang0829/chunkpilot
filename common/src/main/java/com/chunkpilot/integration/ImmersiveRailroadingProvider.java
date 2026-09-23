package com.chunkpilot.integration;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.SectorCalculator.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immersive Railroading 联动
 * 火车走固定轨道，精确预加载
 *
 * 实现：
 *  - 反射 cam72cam.immersiverailroading.entity.EntityRollingStock
 *  - 通过 Track API (trackapi) 获取轨道路径
 *  - 服务端 modId 决定是否启用
 */
public class ImmersiveRailroadingProvider implements VehicleProvider, IntegrationManager.ProviderWithCache {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private boolean loaded = false;
    private Class<?> rollingStockClass = null;
    private Method getPositionMethod = null;

    /** 缓存周期默认值 —— 与 ChunkPilotConfig.Integration.cacheTicks 的默认值一致. */
    private static final int DEFAULT_CACHE_TICKS = 10;
    private int pathCacheTicks = DEFAULT_CACHE_TICKS;
    private ChunkPilotConfig.Integration config = new ChunkPilotConfig.Integration();
    // 非 final: updateConfig() 时按 [integration.<mod>] cacheTicks 重建 (默认 10 = 原硬编码值)
    private VehicleProvider.Cache<List<ChunkPos>> pathCache = new VehicleProvider.Cache<>(DEFAULT_CACHE_TICKS);

    public boolean isAvailable() { return loaded; }

    public void detect() {
        // 1. 找 IR 火车实体
        String[] irClasses = {
            "cam72cam.immersiverailroading.entity.EntityRollingStock",
            "cam72cam.immersiverailroading.entity.EntityMoveableRollingStock"
        };
        for (String name : irClasses) {
            try {
                Class<?> c = Class.forName(name);
                this.rollingStockClass = c;
                this.getPositionMethod = findMethod(c, "getPosition");
                if (getPositionMethod != null) {
                    this.loaded = true;
                    LOG.info("[ChunkPilot] IR: detected {}", name);
                    break;
                }
            } catch (ClassNotFoundException ignored) {}
        }
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
        // v0.11.10: 让 [integration...] cacheTicks 真正生效 (此前该键被解析但无人使用,
        // 缓存周期一直硬编码 10). 默认值就是 10, 因此默认行为不变; 改配置才会变.
        int ticks = Math.max(1, cfg.cacheTicks);
        if (ticks != this.pathCacheTicks) {
            this.pathCacheTicks = ticks;
            this.pathCache = new VehicleProvider.Cache<>(ticks);
        }
    }

    @Override
    public String getName() { return "Immersive Railroading"; }

    @Override
    public boolean isOnVehicle(Object player) {
        if (!loaded) return false;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            return vehicle != null && rollingStockClass.isInstance(vehicle);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean hasFixedPath() { return true; }

    @Override
    public List<ChunkPos> getRailPath(Object player, int lookAheadChunks) {
        if (!loaded) return List.of();
        List<ChunkPos> result = new ArrayList<>();
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null || !rollingStockClass.isInstance(vehicle)) return List.of();

            // IR 实体有 getPosition() 返回 BlockPos
            // 简单实现：沿车辆当前朝向（yaw）前方 N 块生成 chunk 列表
            // 完整版需要读 track 路径（gauge + tick position 推算），这里先做方向性 fallback
            Object pos = getPositionMethod.invoke(vehicle);
            int px = (int) pos.getClass().getMethod("getX").invoke(pos);
            int pz = (int) pos.getClass().getMethod("getZ").invoke(pos);

            // 读车辆 yaw → 方向
            float yaw = 0;
            try {
                Object y = vehicle.getClass().getMethod("getYaw").invoke(vehicle);
                if (y instanceof Float f) yaw = f;
            } catch (Throwable ignored) {}
            double dirRad = Math.toRadians(-yaw + 90); // yaw 0=南 → +Z
            double dx = Math.cos(dirRad);
            double dz = Math.sin(dirRad);

            // 沿方向每 8 块（半 chunk）放一个 rail node
            for (int i = 1; i <= lookAheadChunks * 2; i++) {
                int bx = px + (int) (dx * i * 8);
                int bz = pz + (int) (dz * i * 8);
                result.add(new ChunkPos((int) Math.floor(bx / 16.0),
                                          (int) Math.floor(bz / 16.0)));
            }
        } catch (Throwable t) {
            LOG.warn("[ChunkPilot] IR getRailPath failed: {}", t.toString());
        }
        return result;
    }

    @Override
    public List<ChunkPos> getRailPathCached(Object player, int lookAheadChunks) {
        UUID id = playerUuidOf(player);
        if (id == null) return getRailPath(player, lookAheadChunks);
        return pathCache.get(id, currentTick(), () -> getRailPath(player, lookAheadChunks));
    }

    private long currentTick() {
        return com.chunkpilot.ChunkPilot.getInstance() != null
            ? com.chunkpilot.ChunkPilot.getInstance().getOptimizer().getCurrentTick() : 0L;
    }

    private UUID playerUuidOf(Object player) {
        try {
            Object id = player.getClass().getMethod("getUUID").invoke(player);
            return id instanceof UUID u ? u : null;
        } catch (Throwable t) { return null; }
    }

    @Override
    public void invalidateCache(UUID playerId) { pathCache.invalidate(playerId); }

    @Override
    public double getSpeed(Object player) {
        // 读 vehicle velocity（fallback）
        return 0;
    }

    @Override
    public double getDirection(Object player) {
        if (!loaded) return 0;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null || !rollingStockClass.isInstance(vehicle)) return 0;
            float yaw = (float) vehicle.getClass().getMethod("getYaw").invoke(vehicle);
            return Math.toRadians(-yaw + 90);
        } catch (Throwable t) { return 0; }
    }

    @Override
    public int getLookAheadChunks() { return config.lookAheadChunks; }
    @Override
    public int getTrackSideRadius() { return config.trackSideRadius; }
    @Override
    public double getMinSpeed() { return config.minSpeed; }
    @Override
    public double getSectorAngle() { return config.sectorAngle; }
}
