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
 * Create mod 联动
 *
 * 检测两类：
 *  1. Create 火车（Train）：固定轨道，hasFixedPath=true
 *  2. 高速 contraption：扇形加载（hasFixedPath=false，由 getSpeed/getDirection 决定）
 *
 * 反射目标：
 *  - com.simibubi.create.content.trains.entity.Train (Create 6.0+)
 *  - com.simibubi.create.content.contraptions.AbstractContraptionEntity
 */
public class CreateProvider implements VehicleProvider, IntegrationManager.ProviderWithCache {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private boolean loaded = false;
    private Class<?> trainClass = null;
    private Class<?> contraptionClass = null;

    private ChunkPilotConfig.Integration config = new ChunkPilotConfig.Integration();
    private final VehicleProvider.Cache<List<ChunkPos>> pathCache = new VehicleProvider.Cache<>(10);

    public boolean isAvailable() { return loaded; }

    public void detect() {
        // Try Train (Create 6.0+)
        String[] trainCandidates = {
            "com.simibubi.create.content.trains.entity.Train",
            "com.simibubi.create.content.trains.entity.Carriage"
        };
        for (String name : trainCandidates) {
            try {
                Class<?> c = Class.forName(name);
                this.trainClass = c;
                this.loaded = true;
                LOG.info("[ChunkPilot] Create: detected train class {}", name);
                break;
            } catch (ClassNotFoundException ignored) {}
        }
        // Try Contraption (fallback)
        try {
            this.contraptionClass = Class.forName(
                "com.simibubi.create.content.contraptions.AbstractContraptionEntity");
            if (!this.loaded) {
                this.loaded = true;
                LOG.info("[ChunkPilot] Create: detected AbstractContraptionEntity");
            }
        } catch (ClassNotFoundException ignored) {}
    }

    public void updateConfig(ChunkPilotConfig.Integration cfg) {
        this.config = cfg;
    }

    @Override
    public String getName() { return "Create"; }

    @Override
    public boolean isOnVehicle(Object player) {
        if (!loaded) return false;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null) return false;
            if (trainClass != null && trainClass.isInstance(vehicle)) return true;
            if (contraptionClass != null && contraptionClass.isInstance(vehicle)) return true;
            return false;
        } catch (Throwable t) { return false; }
    }

    @Override
    public boolean hasFixedPath() {
        // Create 火车有轨道，contraption 不一定（部分 contraption 是高速无轨）
        // 简化：默认按"玩家在火车上"判断 — 运行时进一步判断
        return trainClass != null;
    }

    @Override
    public List<ChunkPos> getRailPath(Object player, int lookAheadChunks) {
        if (!loaded) return List.of();
        List<ChunkPos> result = new ArrayList<>();
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null) return List.of();

            // 火车: 用 getYaw() 方向 + 玩家位置推算
            if (trainClass != null && trainClass.isInstance(vehicle)) {
                double px = (double) readDoubleMethod(vehicle, "getX");
                double pz = (double) readDoubleMethod(vehicle, "getZ");
                float yaw = (float) readFloatMethod(vehicle, "getYaw");
                if (px == 0 && pz == 0) return List.of();
                double dirRad = Math.toRadians(-yaw + 90);
                double dx = Math.cos(dirRad);
                double dz = Math.sin(dirRad);
                for (int i = 1; i <= lookAheadChunks * 2; i++) {
                    int bx = (int) (px + dx * i * 8);
                    int bz = (int) (pz + dz * i * 8);
                    result.add(new ChunkPos((int) Math.floor(bx / 16.0),
                                              (int) Math.floor(bz / 16.0)));
                }
            }
        } catch (Throwable t) {
            LOG.warn("[ChunkPilot] Create getRailPath failed: {}", t.toString());
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

    private static Object readDoubleMethod(Object obj, String name) {
        try {
            return obj.getClass().getMethod(name).invoke(obj);
        } catch (Throwable t) { return 0; }
    }

    private static Object readFloatMethod(Object obj, String name) {
        try {
            return obj.getClass().getMethod(name).invoke(obj);
        } catch (Throwable t) { return 0f; }
    }

    @Override
    public double getSpeed(Object player) {
        if (!loaded) return 0;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null) return 0;
            // Vec3 getDeltaMovement()
            Object delta = vehicle.getClass().getMethod("getDeltaMovement").invoke(vehicle);
            double vx = (double) delta.getClass().getMethod("x").invoke(delta);
            double vz = (double) delta.getClass().getMethod("z").invoke(delta);
            return Math.hypot(vx, vz);
        } catch (Throwable t) { return 0; }
    }

    @Override
    public double getDirection(Object player) {
        if (!loaded) return 0;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null) return 0;
            float yaw = (float) readFloatMethod(vehicle, "getYaw");
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
