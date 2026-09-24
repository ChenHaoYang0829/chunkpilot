package com.chunkpilot.neoforge.network;

import com.chunkpilot.network.CapabilityPacket;
import com.chunkpilot.network.ChunkPriorityHintPacket;
import com.chunkpilot.network.ClientConfigOverridePacket;
import com.chunkpilot.network.PlatformNetworkSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * NeoForge 1.20.1 (Forge 47) 网络实现 —— **降级为 no-op**。
 *
 * ============================ 为什么降级 (写清楚, 别当它是"忘了") ============================
 * 1.21.x 的 NeoForge 用 payload 体系: `RegisterPayloadHandlersEvent` + `PayloadRegistrar` +
 * `PacketDistributor.sendToPlayer/sendToServer` + `CustomPacketPayload`。
 * **1.20.1 (Forge 47) 这三样都不存在**:
 *   · `net.minecraftforge.network.event.RegisterPayloadHandlersEvent` 没有 (那是 NeoForge 1.20.5+);
 *   · 1.20.1 的自定义包是旧的 `SimpleChannel` (`NetworkRegistry.ChannelBuilder...simpleChannel()` +
 *     `channel.messageBuilder(Class, int, NetworkDirection)` + `FriendlyByteBuf` 手写编解码);
 *   · `PacketDistributor` 在 1.20.1 是 `SimpleChannel.send(PacketDistributor.PLAYER.with(...), msg)`。
 *   也就是要把 4 个消息 (capability / client_capability / config_override / priority_hint)
 *   全部按 SimpleChannel 重写成 ~150 行。
 *
 * 本轮取舍: 优先保证"**能构建 + 能启动 + 能飞**"的 neoforge 产物 (用户硬要求),
 * 网络层先降级成 no-op ——
 *   · 服务端侧与 bench 全部不受影响 (bench bot 是协议级 bot, 不用 CP 的自定义通道);
 *   · 受影响的是**装了 CP 客户端的玩家**: 收不到 priority_hint / config_override,
 *     即客户端渲染侧的自适应/前瞻提示失效; 服务端的生成侧锚点票、非阻塞碰撞等**照常工作**。
 *   · 能力协商 (capability) 也因此不发生 → CP 侧会把每个客户端都当"没有 CP 客户端"处理,
 *     这在设计上本来就是合法分支 (`clientHasCP=false`), 不会报错。
 *
 * 与 fabric 侧对比: fabric(1.20.1) 的网络层是**完整可用**的 (已按 1.20.1 的
 * `ServerPlayNetworking.registerGlobalReceiver(ResourceLocation, PlayChannelHandler)` 重写)。
 * 所以这是"neoforge 1.20.1 相对 fabric 1.20.1 的功能缺口", 已记进交付报告。
 */
public class NeoForgeNetworkSender implements PlatformNetworkSender {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotNetwork");

    /** 兼容旧调用点 (1.21.x 里是 modEventBus.addListener(this::register)) —— 1.20.1 无事件可挂。 */
    public void register() {
        LOG.info("ChunkPilot: 1.20.1 neoforge 网络层降级为 no-op (Forge 47 是 SimpleChannel, 未实现)");
    }

    @Override
    public void sendCapability(UUID playerId, CapabilityPacket packet) {
        // no-op (见类注释)
    }

    @Override
    public void sendPriorityHint(UUID playerId, ChunkPriorityHintPacket packet) {
        // no-op
    }

    @Override
    public void registerServerReceivers(ServerPacketHandler handler) {
        // no-op: 1.20.1 客户端不会收到也不会发出 CP 自定义包
    }

    @Override
    public void registerClientReceivers(ClientPacketHandler handler) {
        // no-op
    }

    @Override
    public void sendConfigOverride(ClientConfigOverridePacket packet) {
        // no-op
    }

    @Override
    public void sendClientCapability(boolean hasCP, int protocolVersion) {
        // no-op
    }
}
