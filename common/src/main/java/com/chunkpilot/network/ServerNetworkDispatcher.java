package com.chunkpilot.network;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.core.SpeedTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端网络调度器 (DESIGN.md §12.3)
 *
 * 管理:
 *   1. 握手: 检测客户端是否有 CP (通过 CapabilityPacket)
 *   2. 定期发送 ChunkPriorityHintPacket (每 10 tick = 0.5s)
 *   3. 接收 ClientConfigOverridePacket, 调整该玩家参数
 *
 * 带宽优化: 只给装了 CP 的客户端发 HintPacket
 */
public class ServerNetworkDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotNetwork");

    /** 发送间隔 (tick) = 10 tick = 0.5s */
    private static final int SEND_INTERVAL_TICKS = 10;

    private final ChunkPilotConfig config;
    private final ServerPriorityCalculator priorityCalculator;
    private final PlatformNetworkSender sender;

    /** 装了 CP 的客户端集合 (玩家 UUID → true) */
    private final Set<UUID> cpEnabledClients = ConcurrentHashMap.newKeySet();

    /** 客户端覆写的配置 (玩家 UUID → override) */
    private final Map<UUID, ClientConfigOverridePacket> clientOverrides = new ConcurrentHashMap<>();

    /** 发送计数器 */
    private int tickCounter = 0;

    public ServerNetworkDispatcher(ChunkPilotConfig config, PlatformNetworkSender sender) {
        this.config = config;
        this.priorityCalculator = new ServerPriorityCalculator(config);
        this.sender = sender;
    }

    /**
     * 每 tick 调用 (服务端 tick).
     */
    public void onServerTick(SpeedTracker speedTracker, Map<UUID, int[]> playerChunkPositions) {
        tickCounter++;

        // 每 10 tick 发一次优先级提示
        if (tickCounter % SEND_INTERVAL_TICKS != 0) return;

        var renderConfig = config.chunkRender;
        if (!renderConfig.enabled) return;

        for (var entry : playerChunkPositions.entrySet()) {
            UUID playerId = entry.getKey();
            int[] pos = entry.getValue();

            // 只给装了 CP 的客户端发
            if (!cpEnabledClients.contains(playerId)) continue;

            // 检查客户端覆写: 如果客户端报告帧率很差, 可以降低发送频率
            var override = clientOverrides.get(playerId);
            if (override != null && override.clientRenderEnabled == false) {
                continue; // 客户端关了渲染优化, 不用发
            }

            // 计算附近区块列表 (用服务端 render distance)
            int viewDistance = ChunkPilot.getInstance().getPlatform().getServerRenderDistance();
            List<int[]> nearbyChunks = getNearbyChunks(pos[0], pos[1], viewDistance);

            // 计算优先级
            ChunkPriorityHintPacket hint = priorityCalculator.computeHint(
                playerId, pos[0], pos[1], speedTracker, nearbyChunks, viewDistance);

            if (hint != null) {
                sender.sendPriorityHint(playerId, hint);
            }
        }
    }

    /**
     * 客户端连接时调用 (Configuration 阶段).
     * 发送 CapabilityPacket 告诉客户端服务端有 CP.
     */
    public void onPlayerConnect(UUID playerId) {
        sender.sendCapability(playerId, new CapabilityPacket(
            true, ChunkPilot.getInstance().getConfig().getClass().getPackage().getImplementationVersion(),
            CapabilityPacket.PROTOCOL_VERSION
        ));
    }

    /**
     * 收到客户端的 CapabilityPacket 回复.
     * 如果客户端也有 CP, 加入 cpEnabledClients.
     */
    public void onClientCapability(UUID playerId, boolean clientHasCP, int protocolVersion) {
        if (clientHasCP) {
            cpEnabledClients.add(playerId);
            LOG.info("Player {} has ChunkPilot client (protocol {}), enabling priority hints",
                playerId, protocolVersion);
        } else {
            cpEnabledClients.remove(playerId);
        }
    }

    /**
     * 收到客户端的配置覆写.
     */
    public void onClientConfigOverride(UUID playerId, ClientConfigOverridePacket override) {
        clientOverrides.put(playerId, override);
        LOG.debug("Player {} override: targetFps={}, meshingQueue={}, frameTime={}ms, renderEnabled={}",
            playerId, override.targetFps, override.meshingQueueSize,
            override.avgFrameTimeMs, override.clientRenderEnabled);
    }

    /** 玩家断开 */
    public void onPlayerDisconnect(UUID playerId) {
        cpEnabledClients.remove(playerId);
        clientOverrides.remove(playerId);
    }

    /** 客户端是否装了 CP */
    public boolean isClientCPEnabled(UUID playerId) {
        return cpEnabledClients.contains(playerId);
    }

    /** 获取客户端覆写 (可能为 null) */
    public ClientConfigOverridePacket getClientOverride(UUID playerId) {
        return clientOverrides.get(playerId);
    }

    /** 生成附近区块坐标列表 */
    private List<int[]> getNearbyChunks(int centerX, int centerZ, int radius) {
        List<int[]> chunks = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new int[]{centerX + dx, centerZ + dz});
            }
        }
        return chunks;
    }
}