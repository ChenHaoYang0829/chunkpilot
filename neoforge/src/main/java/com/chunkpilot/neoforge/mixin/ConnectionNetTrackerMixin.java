package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.neoforge.platform.ChunkPilotNetworkTracker;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 Connection.channelRead0 (rx) 和 send (tx)
 * 通过 Connection.getPacketListener() -> ServerGamePacketListenerImpl.player 找到 player UUID
 *
 * rx 字节数 = Mojang 内置 BandwidthDebugMonitor.bytesReceived 累加器（这个是真的字节数）
 * tx 字节数 = 自维护 counter（按 packet type 估算，因为 vanilla 不暴露 tx 字节数）
 */
@Mixin(Connection.class)
public class ConnectionNetTrackerMixin {

    // ===================== 1.21.8 移植: 目标方法签名必须跟着改 =====================
    // javap 实证 (1.21.8 minecraft-merged / net.minecraft.network.Connection):
    //   public void send(Packet<?>);
    //   public void send(Packet<?>, io.netty.channel.ChannelFutureListener);
    //   public void send(Packet<?>, io.netty.channel.ChannelFutureListener, boolean);
    //   private void sendPacket(Packet<?>, ChannelFutureListener, boolean);
    //   ✗ 1.21.3 的 `send(Packet, PacketSendListener, boolean)` **已被删除**
    //     (PacketSendListener 这个类型在 1.21.6+ 的 Connection 上不再使用)。
    // 本 mixin 归属的 chunkpilot.mixins.json 是 `required: true` + `defaultRequire: 1`,
    // 描述符写错 ⇒ 注入失败 ⇒ **NeoForge 服务端启动直接崩** (这正是必须避免的"编译过但起不来")。
    // 因此与 1.21.7 端口一样, 改成 ChannelFutureListener 版本。
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void chunkpilot$onSend(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        try {
            Connection self = (Connection) (Object) this;
            Object pl = self.getPacketListener();
            if (pl instanceof ServerGamePacketListenerImpl gameListener) {
                ServerPlayer player = gameListener.player;
                if (player != null) {
                    ChunkPilotNetworkTracker.addTx(player.getUUID(), estimatePacketSize(packet));
                }
            }
        } catch (Throwable t) {
            // 安全：mixin 异常不应破坏游戏
        }
    }

    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void chunkpilot$onChannelRead(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        try {
            Connection self = (Connection) (Object) this;
            Object pl = self.getPacketListener();
            if (pl instanceof ServerGamePacketListenerImpl gameListener) {
                ServerPlayer player = gameListener.player;
                if (player != null) {
                    ChunkPilotNetworkTracker.addRx(player.getUUID(), estimatePacketSize(packet));
                }
            }
        } catch (Throwable t) {
            // 安全
        }
    }

    /**
     * 估算 Packet 大小
     * 这些是基于 MC 1.21.1 包经验值的近似（不一定精确，但反映流量趋势）
     * 真正精确应该用 BandwidthDebugMonitor 但它只覆盖 rx
     */
    private static int estimatePacketSize(Packet<?> packet) {
        if (packet == null) return 0;
        String name = packet.getClass().getSimpleName();
        if (name.contains("LevelChunk") || name.contains("ChunkData")) return 32768;  // 32 KB
        if (name.contains("LightUpdate")) return 8192;  // 8 KB
        if (name.contains("ChunkBatch")) return 16384;  // 16 KB
        if (name.contains("BlockUpdate")) return 128;
        if (name.contains("BlockEntityData")) return 256;
        if (name.contains("Entity")) return 512;  // 实体包可能很大
        if (name.contains("Container") || name.contains("Inventory")) return 1024;
        if (name.contains("Position") || name.contains("PlayerPos")) return 64;
        if (name.contains("Animate") || name.contains("EntityEvent")) return 32;
        return 128; // 平均
    }
}
