package com.chunkpilot.integration;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.platform.PlatformAbstraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod 联动管理器
 * 负责注册和调度所有交通 mod 的 VehicleProvider
 *
 * 行为：
 *  - 启动时按 server mod 加载情况 + config.integrationEnabled 决定注册哪些 Provider
 *  - 每个 Provider 也受 config.integration.<modId>.enabled 控制
 *  - 调度：每 tick 按 provider 注册顺序匹配第一个命中
 *  - 失败兜底：单个 Provider 抛异常不影响其他
 */
public class IntegrationManager {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    private final List<VehicleProvider> providers = new ArrayList<>();
    private boolean globalEnabled = true;

    /**
     * 初始化：检测已安装的 mod 并注册 Provider
     * 必须先调用 platform.isModLoaded()，未安装的 Provider 不会被注册
     */
    public void init(PlatformAbstraction platform, ChunkPilotConfig config) {
        this.globalEnabled = config.integrationEnabled;
        providers.clear();

        if (!globalEnabled) {
            LOG.info("IntegrationManager: disabled by config (integration.enabled = false)");
            return;
        }

        // 1. 固定轨道优先（效果最好）
        if (config.mtr.enabled && platform.isModLoaded("mtr")) {
            MTRProvider p = new MTRProvider();
            p.detect();
            if (p.isAvailable()) {
                p.updateConfig(config.mtr);
                providers.add(p);
                LOG.info("Provider registered: MTR (lookAhead={}, side={})",
                    config.mtr.lookAheadChunks, config.mtr.trackSideRadius);
            } else {
                LOG.warn("MTR mod loaded but class not found — skipping");
            }
        }

        if (config.immersiveRailroading.enabled && platform.isModLoaded("immersiverailroading")) {
            ImmersiveRailroadingProvider p = new ImmersiveRailroadingProvider();
            p.detect();
            if (p.isAvailable()) {
                p.updateConfig(config.immersiveRailroading);
                providers.add(p);
                LOG.info("Provider registered: Immersive Railroading (lookAhead={}, side={})",
                    config.immersiveRailroading.lookAheadChunks, config.immersiveRailroading.trackSideRadius);
            } else {
                LOG.warn("Immersive Railroading mod loaded but class not found — skipping");
            }
        }

        // 2. Create 火车
        if (config.create.enabled && platform.isModLoaded("create")) {
            CreateProvider p = new CreateProvider();
            p.detect();
            if (p.isAvailable()) {
                p.updateConfig(config.create);
                providers.add(p);
                LOG.info("Provider registered: Create (lookAhead={})", config.create.lookAheadChunks);
            } else {
                LOG.warn("Create mod loaded but contraption class not found — skipping");
            }
        }

        // 3. Create Aeronautics（高速载具）
        if (config.createAeronautics.enabled && platform.isModLoaded("create_aeronautics")) {
            CreateAeronauticsProvider p = new CreateAeronauticsProvider();
            p.detect();
            if (p.isAvailable()) {
                p.updateConfig(config.createAeronautics);
                providers.add(p);
                LOG.info("Provider registered: Create: Aeronautics");
            } else {
                LOG.warn("Create: Aeronautics mod loaded but class not found — skipping");
            }
        }

        // 4. Immersive Vehicles
        if (config.immersiveVehicles.enabled && platform.isModLoaded("immersive_vehicles")) {
            ImmersiveVehiclesProvider p = new ImmersiveVehiclesProvider();
            p.detect();
            if (p.isAvailable()) {
                p.updateConfig(config.immersiveVehicles);
                providers.add(p);
                LOG.info("Provider registered: Immersive Vehicles");
            } else {
                LOG.warn("Immersive Vehicles mod loaded but class not found — skipping");
            }
        }

        if (providers.isEmpty()) {
            LOG.info("IntegrationManager: no providers registered (no traffic mods installed or all disabled)");
        } else {
            LOG.info("IntegrationManager: {} provider(s) registered: {}",
                providers.size(), getProviderSummary());
        }
    }

    /**
     * 检测玩家当前所在的载具
     * @return 第一个命中的 Provider，或 null
     */
    public VehicleProvider getActiveProvider(Object player) {
        if (!globalEnabled || providers.isEmpty()) return null;
        for (VehicleProvider provider : providers) {
            try {
                if (provider.isOnVehicle(player)) {
                    return provider;
                }
            } catch (Throwable t) {
                // 单个 Provider 失败不影响其他
                LOG.warn("[ChunkPilot] Provider {} isOnVehicle() failed: {}",
                    provider.getName(), t.toString());
            }
        }
        return null;
    }

    /** 用于 status 输出 */
    public String getProviderSummary() {
        if (providers.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < providers.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(providers.get(i).getName());
        }
        return sb.toString();
    }

    /** 玩家离线时清理各 Provider 缓存 */
    public void onPlayerRemoved(java.util.UUID playerId) {
        for (VehicleProvider p : providers) {
            try {
                if (p instanceof ProviderWithCache pc) pc.invalidateCache(playerId);
            } catch (Throwable ignored) {}
        }
    }

    public interface ProviderWithCache {
        void invalidateCache(java.util.UUID playerId);
    }
}
