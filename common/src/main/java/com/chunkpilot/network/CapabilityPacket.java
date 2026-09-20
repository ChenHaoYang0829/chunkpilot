package com.chunkpilot.network;

/**
 * 握手包: 服务端 → 客户端, Configuration 阶段
 *
 * 轻量: 只含 boolean + 版本号
 * 客户端收到后知道服务端有 CP, 可以期待后续 PriorityHint 包
 */
public final class CapabilityPacket {
    public final boolean chunkPilotPresent;
    public final String modVersion;
    public final int protocolVersion;

    public CapabilityPacket(boolean present, String version, int protocol) {
        this.chunkPilotPresent = present;
        this.modVersion = version;
        this.protocolVersion = protocol;
    }

    public static final int PROTOCOL_VERSION = 1;
}