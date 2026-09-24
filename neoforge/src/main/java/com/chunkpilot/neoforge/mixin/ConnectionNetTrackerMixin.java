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

    // 1.20.1 (javap 实证): Connection 只有 send(Packet) 与 send(Packet, PacketSendListener),
    // 没有 1.21 的三参 send(Packet, PacketSendListener, boolean); 而 send(Packet) 会转到两参版本
    // (字节码: aload_1; aconst_null; invokevirtual send:(Packet;PacketSendListener;)V)
    // ⇒ 挂在两参版本上, 出向包一个都不漏。
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void chunkpilot$onSend(Packet<?> packet, net.minecraft.network.PacketSendListener listener, boolean flush, CallbackInfo ci) {
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
