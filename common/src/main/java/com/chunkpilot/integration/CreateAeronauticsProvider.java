package com.chunkpilot.integration;

import com.chunkpilot.config.ChunkPilotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Create: Aeronautics 联动
 * 飞艇/飞机是高速无轨载具，扇形加载
 *
 * 反射目标：
 *  - uwu.creamtogether.catnether.airship.Airship (推测类名)
 *  - 实际类名可能在不同版本变化，失败时回退到原版逻辑
 */
public class CreateAeronauticsProvider implements VehicleProvider, IntegrationManager.ProviderWithCache {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private boolean loaded = false;
    private Class<?> airshipClass = null;

    private ChunkPilotConfig.Integration config = new ChunkPilotConfig.Integration();

    public boolean isAvailable() { return loaded; }

    public void detect() {
        String[] candidates = {
            "com.erectionofcathedral.createaeronautics.entity.AirshipEntity",
            "uwu.creamtogether.catnether.entity.AirshipEntity",
            "com.aeronautics.entity.Airship",
            "com.simibubi.create_aeronautics.entity.AirshipEntity"
        };
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name);
                this.airshipClass = c;
                this.loaded = true;
                LOG.info("[ChunkPilot] Aeronautics: detected {}", name);
                return;
            } catch (ClassNotFoundException ignored) {}
        }
    }

    public void updateConfig(ChunkPilotConfig.Integration cfg) {
        this.config = cfg;
    }

    @Override
    public String getName() { return "Create: Aeronautics"; }

    @Override
    public boolean isOnVehicle(Object player) {
        if (!loaded) return false;
        try {
            Object vehicle = player.getClass().getMethod("getVehicle").invoke(player);
            return vehicle != null && airshipClass.isInstance(vehicle);
        } catch (Throwable t) { return false; }
    }

    @Override
    public boolean hasFixedPath() { return false; }

    @Override
    public java.util.List<com.chunkpilot.core.SectorCalculator.ChunkPos> getRailPath(Object player, int lookAheadChunks) {
        return java.util.List.of();
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
