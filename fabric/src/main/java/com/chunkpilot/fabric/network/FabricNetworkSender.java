package com.chunkpilot.fabric.network;

import com.chunkpilot.network.CapabilityPacket;
import com.chunkpilot.network.ChunkPriorityHintPacket;
import com.chunkpilot.network.ClientConfigOverridePacket;
import com.chunkpilot.network.PlatformNetworkSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fabric 平台网络实现 (1.21.1 CustomPacketPayload API)
 *
 * 使用 Fabric Networking API v1 的新 Payload 接口:
 *   - 定义 CustomPacketPayload + StreamCodec
 *   - ServerPlayNetworking.send() 发送
 *   - ServerPlayNetworking.registerGlobalReceiver() 接收
 */
public class FabricNetworkSender implements PlatformNetworkSender {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotNetwork");
    public static final String PROTOCOL_ID = "chunkpilot";

    // ===== Payload 类型定义 =====

    public record CapabilityPayload(boolean hasCP, String version, int protocol) implements CustomPacketPayload {
        public static final Type<CapabilityPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PROTOCOL_ID, "capability"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CapabilityPayload> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, CapabilityPayload::hasCP,
                ByteBufCodecs.STRING_UTF8, CapabilityPayload::version,
                ByteBufCodecs.INT, CapabilityPayload::protocol,
                CapabilityPayload::new);
        @Override public Type<CapabilityPayload> type() { return TYPE; }
    }

    public record ClientCapabilityPayload(boolean hasCP, int protocol) implements CustomPacketPayload {
        public static final Type<ClientCapabilityPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PROTOCOL_ID, "client_capability"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientCapabilityPayload> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, ClientCapabilityPayload::hasCP,
                ByteBufCodecs.INT, ClientCapabilityPayload::protocol,
                ClientCapabilityPayload::new);
        @Override public Type<ClientCapabilityPayload> type() { return TYPE; }
    }

    public record ConfigOverridePayload(int targetFps, int meshingQueueSize, float avgFrameTime, boolean renderEnabled) implements CustomPacketPayload {
        public static final Type<ConfigOverridePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PROTOCOL_ID, "config_override"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigOverridePayload> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.INT, ConfigOverridePayload::targetFps,
                ByteBufCodecs.INT, ConfigOverridePayload::meshingQueueSize,
                ByteBufCodecs.FLOAT, ConfigOverridePayload::avgFrameTime,
                ByteBufCodecs.BOOL, ConfigOverridePayload::renderEnabled,
                ConfigOverridePayload::new);
        @Override public Type<ConfigOverridePayload> type() { return TYPE; }
    }

    public record PriorityHintPayload(int playerChunkX, int playerChunkZ, float speed, float direction,
                                       List<ChunkPriorityHintPacket.ChunkPriority> priorities) implements CustomPacketPayload {
        public static final Type<PriorityHintPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PROTOCOL_ID, "priority_hint"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PriorityHintPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PriorityHintPayload decode(RegistryFriendlyByteBuf buf) {
                    int cx = buf.readInt(), cz = buf.readInt();
                    float speed = buf.readFloat(), dir = buf.readFloat();
                    int count = buf.readInt();
                    var list = new ArrayList<ChunkPriorityHintPacket.ChunkPriority>(count);
                    for (int i = 0; i < count; i++) {
                        list.add(new ChunkPriorityHintPacket.ChunkPriority(buf.readInt(), buf.readInt(), buf.readFloat()));
                    }
                    return new PriorityHintPayload(cx, cz, speed, dir, list);
                }
                @Override
                public void encode(RegistryFriendlyByteBuf buf, PriorityHintPayload p) {
                    buf.writeInt(p.playerChunkX).writeInt(p.playerChunkZ);
                    buf.writeFloat(p.speed).writeFloat(p.direction);
                    buf.writeInt(p.priorities.size());
                    for (var pr : p.priorities) {
                        buf.writeInt(pr.chunkX()).writeInt(pr.chunkZ()).writeFloat(pr.weight());
                    }
                }
            };
        @Override public Type<PriorityHintPayload> type() { return TYPE; }
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
        ServerPlayNetworking.send(player, new CapabilityPayload(
            packet.chunkPilotPresent,
            packet.modVersion != null ? packet.modVersion : "",
            packet.protocolVersion));
    }

    @Override
    public void sendPriorityHint(UUID playerId, ChunkPriorityHintPacket packet) {
        var mcServer = getServer();
        if (mcServer == null) return;
        ServerPlayer player = mcServer.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        ServerPlayNetworking.send(player, new PriorityHintPayload(
            packet.playerChunkX, packet.playerChunkZ,
            packet.speedBlocksPerTick, packet.directionRad,
            packet.priorities));
    }

    @Override
    public void registerServerReceivers(ServerPacketHandler handler) {
        this.serverHandler = handler;

        // 1.21.2+: 先注册 payload type (serverbound/clientbound), 再注册 receiver
        PayloadTypeRegistry.playC2S().register(ClientCapabilityPayload.TYPE, ClientCapabilityPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigOverridePayload.TYPE, ConfigOverridePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(CapabilityPayload.TYPE, CapabilityPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PriorityHintPayload.TYPE, PriorityHintPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ClientCapabilityPayload.TYPE,
            (payload, context) -> {
                context.player().level().getServer().execute(() ->
                    handler.onClientCapability(context.player().getUUID(), payload.hasCP(), payload.protocol()));
            });

        ServerPlayNetworking.registerGlobalReceiver(ConfigOverridePayload.TYPE,
            (payload, context) -> {
                var override = new ClientConfigOverridePacket(
                    payload.targetFps(), payload.meshingQueueSize(),
                    payload.avgFrameTime(), payload.renderEnabled());
                context.player().level().getServer().execute(() ->
                    handler.onClientConfigOverride(context.player().getUUID(), override));
            });
    }

    @Override
    public void registerClientReceivers(ClientPacketHandler handler) {
        this.clientHandler = handler;

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(CapabilityPayload.TYPE,
            (payload, context) -> {
                // 客户端连专用服务器时 ClientLevel.getServer() 为 null, 不能 execute().
                // handler 只是写 volatile 状态, 直接在网络线程调用是安全的.
                handler.onServerCapability(payload.hasCP(), payload.version(), payload.protocol());
            });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(PriorityHintPayload.TYPE,
            (payload, context) -> {
                var packet = new ChunkPriorityHintPacket(
                    payload.playerChunkX(), payload.playerChunkZ(),
                    payload.speed(), payload.direction(), payload.priorities());
                handler.onPriorityHint(packet);
            });
    }

    @Override
    public void sendConfigOverride(ClientConfigOverridePacket packet) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new ConfigOverridePayload(packet.targetFps, packet.meshingQueueSize,
                                       packet.avgFrameTimeMs, packet.clientRenderEnabled));
    }

    @Override
    public void sendClientCapability(boolean hasCP, int protocolVersion) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new ClientCapabilityPayload(hasCP, protocolVersion));
    }

    private net.minecraft.server.MinecraftServer getServer() {
        // Fabric 不提供静态获取 server 的方法, 通过 ChunkPilotFabric 缓存的 server 获取
        return com.chunkpilot.fabric.platform.FabricPlatform.getServer();
    }
}