package com.chunkpilot.integration;

import com.chunkpilot.config.ChunkPilotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Immersive Vehicles 联动
 * 高速载具（飞机/快车）扇形加载
 *
 * 反射目标（包路径可能在版本间变化，失败回退）：
 *  - immersive_vehicles (Strupf)
 */
public class ImmersiveVehiclesProvider implements VehicleProvider, IntegrationManager.ProviderWithCache {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private boolean loaded = false;
    private Class<?> vehicleBase = null;

    private ChunkPilotConfig.Integration config = new ChunkPilotConfig.Integration();

    public boolean isAvailable() { return loaded; }

    public void detect() {
        String[] candidates = {
            "immersive_vehicles.entity.EntityVehicle",
            "strupf.immersive_vehicles.entity.EntityVehicle",
            "com.strupf.immersive_vehicles.entity.EntityVehicle"
        };
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name);
                this.vehicleBase = c;
                this.loaded = true;
                LOG.info("[ChunkPilot] Immersive Vehicles: detected {}", name);
                return;
            } catch (ClassNotFoundException ignored) {}
        }
    }

    public void updateConfig(ChunkPilotConfig.Integration cfg) {
        this.config = cfg;
    }

    @Override
    public String getName() { return "Immersive Vehicles"; }

    @Override
    public boolean isOnVehicle(Object player) {
        if (!loaded) return false;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            return vehicle != null && vehicleBase.isInstance(vehicle);
        } catch (Throwable t) { return false; }
    }

    @Override
    public boolean hasFixedPath() { return false; }

    @Override
    public List<com.chunkpilot.core.SectorCalculator.ChunkPos> getRailPath(Object player, int lookAheadChunks) {
        return List.of();
    }

    @Override
    public void invalidateCache(UUID playerId) {}

    @Override
    public double getSpeed(Object player) {
        if (!loaded) return 0;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            if (vehicle == null) return 0;
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
