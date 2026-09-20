package com.chunkpilot.network;

import java.util.UUID;

/**
 * 平台网络发送接口 (DESIGN.md §12.3)
 *
 * Fabric 和 NeoForge 各自实现实际的 packet 发送.
 * common 模块只定义接口, 不依赖 Minecraft 的网络 API.
 */
public interface PlatformNetworkSender {

    /**
     * 发送 CapabilityPacket 给指定玩家 (Configuration 阶段)
     */
    void sendCapability(UUID playerId, CapabilityPacket packet);

    /**
     * 发送 ChunkPriorityHintPacket 给指定玩家 (Play 阶段)
     */
    void sendPriorityHint(UUID playerId, ChunkPriorityHintPacket packet);

    /**
     * 注册网络包接收处理器 (服务端侧)
     * @param handler 处理 ClientConfigOverridePacket 的回调
     */
    void registerServerReceivers(ServerPacketHandler handler);

    /**
     * 注册网络包接收处理器 (客户端侧)
     * @param handler 处理 CapabilityPacket 和 ChunkPriorityHintPacket 的回调
     */
    void registerClientReceivers(ClientPacketHandler handler);

    /**
     * 发送 ClientConfigOverridePacket 给服务端 (客户端侧)
     */
    void sendConfigOverride(ClientConfigOverridePacket packet);

    /**
     * 发送 CapabilityPacket 回复给服务端 (客户端侧)
     */
    void sendClientCapability(boolean hasCP, int protocolVersion);

    /**
     * 服务端包处理器
     */
    interface ServerPacketHandler {
        void onClientCapability(UUID playerId, boolean clientHasCP, int protocolVersion);
        void onClientConfigOverride(UUID playerId, ClientConfigOverridePacket packet);
    }

    /**
     * 客户端包处理器
     */
    interface ClientPacketHandler {
        void onServerCapability(boolean serverHasCP, String version, int protocolVersion);
        void onPriorityHint(ChunkPriorityHintPacket packet);
    }
}