package com.chunkpilot.forge.network;

import com.chunkpilot.network.CapabilityPacket;
import com.chunkpilot.network.ChunkPriorityHintPacket;
import com.chunkpilot.network.ClientConfigOverridePacket;
import com.chunkpilot.network.PlatformNetworkSender;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Forge 1.20.1 (47.x) 平台网络实现 —— **整体重写**, 不是 neoforge 版本改个包名。
 *
 * ============================ 为什么必须重写 (javap / 源码实证) ============================
 * 1.21.x 的 NeoForge 版本用的是 `CustomPacketPayload` + `StreamCodec` + `PayloadRegistrar` +
 * `RegisterPayloadHandlersEvent` + `PacketDistributor.sendToPlayer(...)` —— 这一整套
 * **在 Forge 47 里全部不存在**:
 *   - `RegisterPayloadHandlersEvent` (net.neoforged.neoforge.network.event.*) → Forge 47 的
 *     `net.minecraftforge.network.*` 里 0 个同名类 (是 1.20.5+ 的 NeoForge 机制);
 *   - `net.neoforged.neoforge.network.PacketDistributor` → Forge 47 的是
 *     `net.minecraftforge.network.PacketDistributor` (泛型 `PacketDistributor<T>` +
 *     `PLAYER.with(() -> player)` 返回 `PacketTarget`, 然后 `channel.send(target, msg)`);
 *   - 注册入口是 `NetworkRegistry.newSimpleChannel(ResourceLocation, Supplier<String>, Predicate, Predicate)`
 *     得到 `SimpleChannel`, 再用
 *     `registerMessage(int id, Class<MSG>, BiConsumer<MSG,FriendlyByteBuf> encoder,
 *                      Function<FriendlyByteBuf,MSG> decoder,
 *                      BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler,
 *                      Optional<NetworkDirection>)`
 *     [以上签名全部用 javap 在 forge-1.20.1-47.1.106-universal.jar 上核对过]。
 *
 * 2. **1.20.1 没有 configuration 阶段** (1.20.2+ 才有), 所以"配置阶段握手"在这版本永远走不到:
 *    改成服务端在 `PlayerLoggedInEvent` 里主动下发一次 capability —— 与 fabric/1.20.1 的做法一致。
 *
 * 3. **必须让裸协议/vanilla 客户端能连**: bench bot 是协议级客户端 (协议 763), 它没有也不会声明
 *    任何自定义通道。Forge 的 `NetworkHooks.isVanillaConnection(Connection)` 能识别这种情况,
 *    我们对 vanilla 连接**一个包都不发** —— 这样 bot 侧收到的网络流与"没装网络功能的 CP"完全一致,
 *    不会给跑分口径引入任何变量。
 *    (注: `ServerGamePacketListenerImpl.connection` 在 Forge 里被 accesstransformer 提升为 public:
 *     `public net.minecraft.server.network.ServerGamePacketListenerImpl f_9742_ # connection` ——
 *     见 forge-1.20.1-47.1.106-universal.jar 的 META-INF/accesstransformer.cfg, 已实证。)
 *
 * ============================ 线格式 (与 fabric/1.20.1 逐字段一致) ============================
 *   capability        bool hasCP | utf version | varint protocol
 *   client_capability bool hasCP | varint protocol
 *   config_override   varint targetFps | varint meshingQueueSize | float avgFrameTime | bool renderEnabled
 *   priority_hint     varint pChunkX | varint pChunkZ | float speed | float dir |
 *                     varint count | count*(varint cx, varint cz, float weight)
 *
 * ============================ 降级说明 ============================
 * `priority_hint` / `config_override` 是**服务端↔CP 客户端**的双向链路, 与跑分口径无关
 * (bench bot 是协议级客户端, 不加载 CP 客户端)。本实现把它们完整保留在 Forge 通道里;
 * 未做客户端实机验证 —— 如实记在 REPORT-forge.md 的"未验证项"。
 */
public class ForgeNetworkSender implements PlatformNetworkSender {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotNetwork");

    public static final String PROTOCOL_ID = "chunkpilot";
    /** 与其它平台一致的协议版本号 (写进 capability 包) */
    public static final int PROTOCOL_VERSION = 1;
    /** Forge 通道的版本字符串 —— 仅用于 SimpleChannel 的握手版本比对 */
    private static final String CHANNEL_VERSION = "1";

    private static SimpleChannel channel;

    // ===== 状态 (实例字段, 由 registerServerReceivers/registerClientReceivers 注入) =====

    private ServerPacketHandler serverHandler;
    private ClientPacketHandler clientHandler;

    // ============================ 注册 ============================

    /**
     * 建立通道并注册 4 个消息类型。
     *
     * 版本谓词恒为 true: 这是**有意**的 —— 通道必须对 vanilla/裸协议客户端"缺席即可"。
     * 若用 `acceptMissingOr("1")` 之外的严格谓词, Forge 会在握手时把"没有该通道的客户端"
     * 判为不兼容。用恒 true 的谓词 + 我们自己按 isVanillaConnection 决定发不发,
     * 是最不干扰跑分口径的做法。
     */
    public void register() {
        if (channel != null) return;
        channel = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PROTOCOL_ID, "main"),
            () -> CHANNEL_VERSION,
            version -> true,
            version -> true
        );

        // 0: 服务端 → 客户端 capability
        channel.registerMessage(0, CapabilityMsg.class, CapabilityMsg::encode, CapabilityMsg::decode,
            (msg, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                c.enqueueWork(() -> {
                    if (clientHandler != null) {
                        clientHandler.onServerCapability(msg.hasCP, msg.version, msg.protocol);
                    }
                });
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 1: 服务端 → 客户端 优先级提示
        channel.registerMessage(1, PriorityHintMsg.class, PriorityHintMsg::encode, PriorityHintMsg::decode,
            (msg, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                c.enqueueWork(() -> {
                    if (clientHandler != null) {
                        clientHandler.onPriorityHint(new ChunkPriorityHintPacket(
                            msg.playerChunkX, msg.playerChunkZ, msg.speed, msg.direction, msg.priorities));
                    }
                });
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 2: 客户端 → 服务端 capability
        channel.registerMessage(2, ClientCapabilityMsg.class,
            ClientCapabilityMsg::encode, ClientCapabilityMsg::decode,
            (msg, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                ServerPlayer sender = c.getSender();
                c.enqueueWork(() -> {
                    if (serverHandler != null && sender != null) {
                        serverHandler.onClientCapability(sender.getUUID(), msg.hasCP, msg.protocol);
                    }
                });
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // 3: 客户端 → 服务端 配置覆盖
        channel.registerMessage(3, ConfigOverrideMsg.class,
            ConfigOverrideMsg::encode, ConfigOverrideMsg::decode,
            (msg, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                ServerPlayer sender = c.getSender();
                c.enqueueWork(() -> {
                    if (serverHandler != null && sender != null) {
                        serverHandler.onClientConfigOverride(sender.getUUID(),
                            new ClientConfigOverridePacket(msg.targetFps, msg.meshingQueueSize,
                                msg.avgFrameTime, msg.renderEnabled));
                    }
                });
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        LOG.info("[ChunkPilot] Forge SimpleChannel '{}:main' registered (4 messages, protocol v{})",
            PROTOCOL_ID, CHANNEL_VERSION);
    }

    // ============================ 发送 ============================

    @Override
    public void sendCapability(UUID playerId, CapabilityPacket packet) {
        ServerPlayer player = findPlayer(playerId);
        if (player == null) return;
        sendToPlayer(player, new CapabilityMsg(packet.chunkPilotPresent,
            packet.modVersion != null ? packet.modVersion : "", packet.protocolVersion));
    }

    @Override
    public void sendPriorityHint(UUID playerId, ChunkPriorityHintPacket packet) {
        ServerPlayer player = findPlayer(playerId);
        if (player == null) return;
        sendToPlayer(player, new PriorityHintMsg(packet.playerChunkX, packet.playerChunkZ,
            packet.speedBlocksPerTick, packet.directionRad, packet.priorities));
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
            if (channel == null) return;
            channel.sendToServer(new ConfigOverrideMsg(packet.targetFps, packet.meshingQueueSize,
                packet.avgFrameTimeMs, packet.clientRenderEnabled));
        } catch (Throwable t) {
            LOG.debug("[ChunkPilot] 客户端发送 config_override 失败: {}", t.toString());
        }
    }

    @Override
    public void sendClientCapability(boolean hasCP, int protocolVersion) {
        try {
            if (channel == null) return;
            channel.sendToServer(new ClientCapabilityMsg(hasCP, protocolVersion));
        } catch (Throwable t) {
            LOG.debug("[ChunkPilot] 客户端发送 client_capability 失败: {}", t.toString());
        }
    }

    /**
     * 1.20.1 专用: join 之后服务端主动下发 capability。
     * (1.20.1 没有 configuration 阶段, 原实现在配置阶段握手 —— 那个阶段在 1.20.1 不存在。)
     */
    public static void sendCapabilityOnJoin(ServerPlayer player, String modVersion, int protocol) {
        if (player == null) return;
        sendToPlayer(player, new CapabilityMsg(true, modVersion != null ? modVersion : "", protocol));
    }

    /** 统一的"发给某个玩家"入口 —— 带 vanilla 客户端跳过 + 全异常隔离。 */
    private static void sendToPlayer(ServerPlayer player, Object msg) {
        if (channel == null) return;
        try {
            // ★ 不给 vanilla / 裸协议客户端发任何自定义包。
            //   这是**跑分口径的关键**: bench bot 是协议级客户端, 它的接收流必须与
            //   "没有网络功能" 的情况一致, 否则每轮的 chunks_received / bytes 都不可比。
            if (isVanillaPlayer(player)) {
                LOG.debug("[ChunkPilot] skip send to vanilla connection: {}", player.getUUID());
                return;
            }
            channel.send(PacketDistributor.PLAYER.with(() -> player), msg);
        } catch (Throwable t) {
            // 客户端未声明的通道 / 握手未完成等都会抛, 绝不能影响登录与主 tick
            LOG.debug("[ChunkPilot] send failed for {}: {}", player.getUUID(), t.toString());
        }
    }

    private static boolean isVanillaPlayer(ServerPlayer player) {
        try {
            // Forge 的 accesstransformer 把 ServerGamePacketListenerImpl.connection 提升为 public
            Connection conn = player.connection.connection;
            if (conn == null) return true;
            return NetworkHooks.isVanillaConnection(conn);
        } catch (Throwable t) {
            // 判定失败时**宁可发**: 真 CP 客户端不会因此丢能力, 而裸协议 bot 收到未知自定义包
            // 也只是被它自己的 handle_packet 忽略 (bench_bot.py: else: pass, 已核对)。
            return false;
        }
    }

    private static ServerPlayer findPlayer(UUID playerId) {
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return null;
            return server.getPlayerList().getPlayer(playerId);
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================ 消息载体 ============================
    // 都是纯数据类 + 静态 encode/decode; 线格式与 fabric/1.20.1 逐字段一致。

    public static final class CapabilityMsg {
        public final boolean hasCP;
        public final String version;
        public final int protocol;

        public CapabilityMsg(boolean hasCP, String version, int protocol) {
            this.hasCP = hasCP;
            this.version = version;
            this.protocol = protocol;
        }

        public static void encode(CapabilityMsg m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.hasCP);
            buf.writeUtf(m.version != null ? m.version : "");
            buf.writeVarInt(m.protocol);
        }

        public static CapabilityMsg decode(FriendlyByteBuf buf) {
            return new CapabilityMsg(buf.readBoolean(), buf.readUtf(), buf.readVarInt());
        }
    }

    public static final class ClientCapabilityMsg {
        public final boolean hasCP;
        public final int protocol;

        public ClientCapabilityMsg(boolean hasCP, int protocol) {
            this.hasCP = hasCP;
            this.protocol = protocol;
        }

        public static void encode(ClientCapabilityMsg m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.hasCP);
            buf.writeVarInt(m.protocol);
        }

        public static ClientCapabilityMsg decode(FriendlyByteBuf buf) {
            return new ClientCapabilityMsg(buf.readBoolean(), buf.readVarInt());
        }
    }

    public static final class ConfigOverrideMsg {
        public final int targetFps;
        public final int meshingQueueSize;
        public final float avgFrameTime;
        public final boolean renderEnabled;

        public ConfigOverrideMsg(int targetFps, int meshingQueueSize, float avgFrameTime, boolean renderEnabled) {
            this.targetFps = targetFps;
            this.meshingQueueSize = meshingQueueSize;
            this.avgFrameTime = avgFrameTime;
            this.renderEnabled = renderEnabled;
        }

        public static void encode(ConfigOverrideMsg m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.targetFps);
            buf.writeVarInt(m.meshingQueueSize);
            buf.writeFloat(m.avgFrameTime);
            buf.writeBoolean(m.renderEnabled);
        }

        public static ConfigOverrideMsg decode(FriendlyByteBuf buf) {
            return new ConfigOverrideMsg(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readBoolean());
        }
    }

    public static final class PriorityHintMsg {
        public final int playerChunkX;
        public final int playerChunkZ;
        public final float speed;
        public final float direction;
        public final List<ChunkPriorityHintPacket.ChunkPriority> priorities;

        public PriorityHintMsg(int playerChunkX, int playerChunkZ, float speed, float direction,
                               List<ChunkPriorityHintPacket.ChunkPriority> priorities) {
            this.playerChunkX = playerChunkX;
            this.playerChunkZ = playerChunkZ;
            this.speed = speed;
            this.direction = direction;
            this.priorities = priorities;
        }

        public static void encode(PriorityHintMsg m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.playerChunkX);
            buf.writeVarInt(m.playerChunkZ);
            buf.writeFloat(m.speed);
            buf.writeFloat(m.direction);
            List<ChunkPriorityHintPacket.ChunkPriority> list = m.priorities;
            buf.writeVarInt(list == null ? 0 : list.size());
            if (list != null) {
                for (ChunkPriorityHintPacket.ChunkPriority p : list) {
                    buf.writeVarInt(p.chunkX());
                    buf.writeVarInt(p.chunkZ());
                    buf.writeFloat(p.weight());
                }
            }
        }

        public static PriorityHintMsg decode(FriendlyByteBuf buf) {
            int cx = buf.readVarInt();
            int cz = buf.readVarInt();
            float speed = buf.readFloat();
            float dir = buf.readFloat();
            int count = buf.readVarInt();
            List<ChunkPriorityHintPacket.ChunkPriority> list = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                list.add(new ChunkPriorityHintPacket.ChunkPriority(buf.readVarInt(), buf.readVarInt(), buf.readFloat()));
            }
            return new PriorityHintMsg(cx, cz, speed, dir, list);
        }
    }
}
