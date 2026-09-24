package com.chunkpilot.fabric.network;

import com.chunkpilot.network.CapabilityPacket;
import com.chunkpilot.network.ChunkPriorityHintPacket;
import com.chunkpilot.network.ClientConfigOverridePacket;
import com.chunkpilot.network.PlatformNetworkSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fabric 平台网络实现 —— **MC 1.20.1 版** (协议号 763).
 *
 * ============================ 为什么必须重写 (javap 实证) ============================
 * 1.21.3 版用的是 `CustomPacketPayload` + `StreamCodec` + `PayloadTypeRegistry` +
 * `RegistryFriendlyByteBuf` —— 这套 payload 体系是 **1.20.5+** 才有的:
 *   1.20.1 的 minecraft-merged jar 里 `net.minecraft.network.codec` 包、
 *   `net.minecraft.network.protocol.common.custom.CustomPacketPayload`、
 *   `net.minecraft.network.RegistryFriendlyByteBuf` **都不存在**;
 *   1.20.1 的 fabric-api 0.92.12 也**没有** `PayloadTypeRegistry`.
 * 2. 1.20.1 的自定义包是**旧的 FriendlyByteBuf 通道**:
 *     ServerPlayNetworking.registerGlobalReceiver(ResourceLocation, PlayChannelHandler)
 *       PlayChannelHandler.receive(MinecraftServer, ServerPlayer, ServerGamePacketListenerImpl,
 *                                   FriendlyByteBuf, PacketSender)
 *     ServerPlayNetworking.send(ServerPlayer, ResourceLocation, FriendlyByteBuf)
 *   (javap 实证: fabric-networking-api-v1-0.92.12.jar)
 * 3. 1.20.1 **没有 configuration 阶段** (1.20.2+ 才有), 所以能力协商不能再走
 *   "配置阶段互发 capability"; 改成 join 之后由服务端在 ServerPlayConnectionEvents.JOIN
 *   里主动下发 capability (见 {@link #sendCapabilityOnJoin}), 客户端收到后回 client_capability。
 *
 * ============================ 线格式 (与 1.21.x 版**逐字段一致**) ============================
 *   chunkpilot:capability        bool hasCP | string version | varint protocol
 *   chunkpilot:client_capability bool hasCP | varint protocol
 *   chunkpilot:config_override   varint targetFps | varint meshingQueueSize |
 *                                float avgFrameTime | bool renderEnabled
 *   chunkpilot:priority_hint     varint pChunkX | varint pChunkZ | float speed | float dir |
 *                                varint count | count*(varint cx, varint cz, float weight)
 *
 * ============================ 与 1.21.3 行为的关系 ============================
 * 本文件只存在于 port/1.20.1 分支, **不影响** 1.21.3 的任何代码路径
 * (那边仍走 CustomPacketPayload 实现). bench bot 是协议级 bot, 不加载 fabric-api 的
 * 自定义通道, 因此网络层不影响跑分口径。
 */
public class FabricNetworkSender implements PlatformNetworkSender {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotNetwork");

    public static final String PROTOCOL_ID = "chunkpilot";

    public static final ResourceLocation CH_CAPABILITY = id("capability");
    public static final ResourceLocation CH_CLIENT_CAPABILITY = id("client_capability");
    public static final ResourceLocation CH_CONFIG_OVERRIDE = id("config_override");
    public static final ResourceLocation CH_PRIORITY_HINT = id("priority_hint");

    private static ResourceLocation id(String path) {
        // 1.20.1: ResourceLocation 构造器仍是 public (1.21 才收起来改用 fromNamespaceAndPath)
        return new ResourceLocation(PROTOCOL_ID, path);
    }

    // ===== 状态 =====

    private ServerPacketHandler serverHandler;
    private ClientPacketHandler clientHandler;

    @Override
    public void sendCapability(UUID playerId, CapabilityPacket packet) {
        var mcServer = getServer();
        if (mcServer == null) return;
        ServerPlayer player = mcServer.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(packet.chunkPilotPresent);
        buf.writeUtf(packet.modVersion != null ? packet.modVersion : "");
        buf.writeVarInt(packet.protocolVersion);
        send(player, CH_CAPABILITY, buf);
    }

    @Override
    public void sendPriorityHint(UUID playerId, ChunkPriorityHintPacket packet) {
        var mcServer = getServer();
        if (mcServer == null) return;
        ServerPlayer player = mcServer.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(packet.playerChunkX);
        buf.writeVarInt(packet.playerChunkZ);
        buf.writeFloat(packet.speedBlocksPerTick);
        buf.writeFloat(packet.directionRad);
        List<ChunkPriorityHintPacket.ChunkPriority> list = packet.priorities;
        buf.writeVarInt(list == null ? 0 : list.size());
        if (list != null) {
            for (var pr : list) {
                buf.writeVarInt(pr.chunkX());
                buf.writeVarInt(pr.chunkZ());
                buf.writeFloat(pr.weight());
            }
        }
        send(player, CH_PRIORITY_HINT, buf);
    }

    /** 发包 + 释放 buffer. canSend 检查避免给"没装 CP 的客户端"刷无谓包与日志. */
    private static void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        try {
            if (!ServerPlayNetworking.canSend(player, channel)) {
                buf.release();
                return;
            }
            ServerPlayNetworking.send(player, channel, buf);
        } catch (Throwable t) {
            try { buf.release(); } catch (Throwable ignored) { }
            LOG.debug("[ChunkPilot] 发送 {} 失败: {}", channel, t.toString());
        }
    }

    @Override
    public void registerServerReceivers(ServerPacketHandler handler) {
        this.serverHandler = handler;

        ServerPlayNetworking.registerGlobalReceiver(CH_CLIENT_CAPABILITY,
            (server, player, netHandler, buf, responseSender) -> {
                boolean hasCP = buf.readBoolean();
                int protocol = buf.readVarInt();
                server.execute(() ->
                    handler.onClientCapability(player.getUUID(), hasCP, protocol));
            });

        ServerPlayNetworking.registerGlobalReceiver(CH_CONFIG_OVERRIDE,
            (server, player, netHandler, buf, responseSender) -> {
                int targetFps = buf.readVarInt();
                int meshingQueueSize = buf.readVarInt();
                float avgFrameTime = buf.readFloat();
                boolean renderEnabled = buf.readBoolean();
                var override = new ClientConfigOverridePacket(
                    targetFps, meshingQueueSize, avgFrameTime, renderEnabled);
                server.execute(() ->
                    handler.onClientConfigOverride(player.getUUID(), override));
            });
    }

    @Override
    public void registerClientReceivers(ClientPacketHandler handler) {
        this.clientHandler = handler;

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            CH_CAPABILITY, (client, netHandler, buf, responseSender) -> {
                boolean hasCP = buf.readBoolean();
                String version = buf.readUtf();
                int protocol = buf.readVarInt();
                // 客户端连专用服务器时 ClientLevel.getServer() 为 null, 不能 execute().
                // handler 只是写 volatile 状态, 直接在网络线程调用是安全的.
                handler.onServerCapability(hasCP, version, protocol);
            });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            CH_PRIORITY_HINT, (client, netHandler, buf, responseSender) -> {
                int pcx = buf.readVarInt();
                int pcz = buf.readVarInt();
                float speed = buf.readFloat();
                float dir = buf.readFloat();
                int count = buf.readVarInt();
                var list = new ArrayList<ChunkPriorityHintPacket.ChunkPriority>(Math.max(0, count));
                for (int i = 0; i < count; i++) {
                    list.add(new ChunkPriorityHintPacket.ChunkPriority(
                        buf.readVarInt(), buf.readVarInt(), buf.readFloat()));
                }
                var packet = new ChunkPriorityHintPacket(pcx, pcz, speed, dir, list);
                handler.onPriorityHint(packet);
            });
    }

    @Override
    public void sendConfigOverride(ClientConfigOverridePacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(packet.targetFps);
        buf.writeVarInt(packet.meshingQueueSize);
        buf.writeFloat(packet.avgFrameTimeMs);
        buf.writeBoolean(packet.clientRenderEnabled);
        try {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                .send(CH_CONFIG_OVERRIDE, buf);
        } catch (Throwable t) {
            try { buf.release(); } catch (Throwable ignored) { }
            LOG.debug("[ChunkPilot] 客户端发送 config_override 失败: {}", t.toString());
        }
    }

    @Override
    public void sendClientCapability(boolean hasCP, int protocolVersion) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(hasCP);
        buf.writeVarInt(protocolVersion);
        try {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                .send(CH_CLIENT_CAPABILITY, buf);
        } catch (Throwable t) {
            try { buf.release(); } catch (Throwable ignored) { }
            LOG.debug("[ChunkPilot] 客户端发送 client_capability 失败: {}", t.toString());
        }
    }

    private net.minecraft.server.MinecraftServer getServer() {
        // Fabric 不提供静态获取 server 的方法, 通过 ChunkPilotFabric 缓存的 server 获取
        return com.chunkpilot.fabric.platform.FabricPlatform.getServer();
    }

    /**
     * 1.20.1 专用: join 之后服务端主动下发 capability。
     * (1.20.1 没有 configuration 阶段, ChunkPilot 原实现在配置阶段握手 —— 那个阶段在
     *  1.20.1 不存在, 所以必须在 JOIN 事件里补一次。)
     */
    public static void sendCapabilityOnJoin(ServerPlayer player, String modVersion, int protocol) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(true);
        buf.writeUtf(modVersion != null ? modVersion : "");
        buf.writeVarInt(protocol);
        try {
            if (!ServerPlayNetworking.canSend(player, CH_CAPABILITY)) {
                buf.release();
                return;
            }
            ServerPlayNetworking.send(player, CH_CAPABILITY, buf);
        } catch (Throwable t) {
            try { buf.release(); } catch (Throwable ignored) { }
            LOG.debug("[ChunkPilot] join capability 下发失败: {}", t.toString());
        }
    }
}
