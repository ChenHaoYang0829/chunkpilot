package com.chunkpilot.neoforge.network;

import com.chunkpilot.network.CapabilityPacket;
import com.chunkpilot.network.ChunkPriorityHintPacket;
import com.chunkpilot.network.ClientConfigOverridePacket;
import com.chunkpilot.network.PlatformNetworkSender;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NeoForge 平台网络实现
 *
 * 使用 NeoForge CustomPacketPayload + StreamCodec + PayloadRegistrar
 */
public class NeoForgeNetworkSender implements PlatformNetworkSender {

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

    // ===== 注册 =====

    public void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // 服务端 → 客户端 (optional: 允许 vanilla/裸协议客户端连接)
        // 注意: 非 optional 注册会让 NeoForge 要求客户端声明 mod channels,
        // 裸协议 bot 没声明 → modded_network_setup_failed → 被踢 (0.7.0 实测)
        registrar.optional().playToClient(CapabilityPayload.TYPE, CapabilityPayload.STREAM_CODEC,
            (payload, context) -> {
                if (clientHandler != null) {
                    context.player().level().getServer().execute(() ->
                        clientHandler.onServerCapability(payload.hasCP(), payload.version(), payload.protocol()));
                }
            });

        registrar.optional().playToClient(PriorityHintPayload.TYPE, PriorityHintPayload.STREAM_CODEC,
            (payload, context) -> {
                if (clientHandler != null) {
                    context.player().level().getServer().execute(() -> {
                        var packet = new ChunkPriorityHintPacket(
                            payload.playerChunkX(), payload.playerChunkZ(),
                            payload.speed(), payload.direction(), payload.priorities());
                        clientHandler.onPriorityHint(packet);
                    });
                }
            });

        // 客户端 → 服务端 (optional)
        registrar.optional().playToServer(ClientCapabilityPayload.TYPE, ClientCapabilityPayload.STREAM_CODEC,
            (payload, context) -> {
                if (serverHandler != null) {
                    context.player().level().getServer().execute(() ->
                        serverHandler.onClientCapability(context.player().getUUID(), payload.hasCP(), payload.protocol()));
                }
            });

        registrar.optional().playToServer(ConfigOverridePayload.TYPE, ConfigOverridePayload.STREAM_CODEC,
            (payload, context) -> {
                if (serverHandler != null) {
                    context.player().level().getServer().execute(() -> {
                        var override = new ClientConfigOverridePacket(
                            payload.targetFps(), payload.meshingQueueSize(),
                            payload.avgFrameTime(), payload.renderEnabled());
                        serverHandler.onClientConfigOverride(context.player().getUUID(), override);
                    });
                }
            });
    }

    // ===== 发送方法 =====

    @Override
    public void sendCapability(UUID playerId, CapabilityPacket packet) {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new CapabilityPayload(
                packet.chunkPilotPresent,
                packet.modVersion != null ? packet.modVersion : "",
                packet.protocolVersion));
        } catch (Exception e) {
            // 客户端未声明对应通道时发送会抛异常, 不能影响登录流程
            LOG.debug("sendCapability failed for {}: {}", playerId, e.toString());
        }
    }

    @Override
    public void sendPriorityHint(UUID playerId, ChunkPriorityHintPacket packet) {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new PriorityHintPayload(
                packet.playerChunkX, packet.playerChunkZ,
                packet.speedBlocksPerTick, packet.directionRad,
                packet.priorities));
        } catch (Exception e) {
            LOG.debug("sendPriorityHint failed for {}: {}", playerId, e.toString());
        }
    }

    @Override
    public void registerServerReceivers(ServerPacketHandler handler) {
        this.serverHandler = handler;
    }

    @Override
    public void registerClientReceivers(ClientPacketHandler handler) {
        this.clientHandler = handler;
    }

    @Override
    public void sendConfigOverride(ClientConfigOverridePacket packet) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ConfigOverridePayload(
                packet.targetFps, packet.meshingQueueSize,
                packet.avgFrameTimeMs, packet.clientRenderEnabled));
        } catch (Exception e) {
            LOG.warn("Failed to send config override: {}", e.getMessage());
        }
    }

    @Override
    public void sendClientCapability(boolean hasCP, int protocolVersion) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ClientCapabilityPayload(hasCP, protocolVersion));
        } catch (Exception e) {
            LOG.warn("Failed to send client capability: {}", e.getMessage());
        }
    }
}