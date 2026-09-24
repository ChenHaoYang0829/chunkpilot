package com.chunkpilot.forge.mixin;

import com.chunkpilot.forge.platform.ChunkPilotNetworkTracker;
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
 * 拦截 {@link Connection} 的收发, 累计每玩家的 rx/tx 字节数 (供 /chunkpilot net 展示)。
 *
 * ============================ 1.20.1 的签名差异 (javap 实证) ============================
 * neoforge (1.21.x) 版本注入的是:
 *   `send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V`
 * 1.20.1 **没有那个三参数版本**。javap 实证 `net.minecraft.network.Connection` 只有:
 *   `public void send(Packet<?>)`
 *   `public void send(Packet<?>, PacketSendListener)`
 *   `private void sendPacket(Packet<?>, PacketSendListener)`
 * 且 `send(Packet)` 的字节码是 `aload_1; aconst_null; invokevirtual send:(Packet;PacketSendListener)V`
 * ⇒ 注入两参数版本 HEAD 可以**恰好一次**捕获所有出站包 (不重不漏)。
 *
 * `channelRead0(ChannelHandlerContext, Packet)` 在 1.20.1 仍在且是 protected (javap 实证),
 * 原样保留。
 *
 * 目标方法名会由 loom 在打包时 remap 成 SRG (send → m_129512_, channelRead0 → m_129514_ 之类),
 * 所以这里写官方名即可。
 */
@Mixin(Connection.class)
public class ConnectionNetTrackerMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"))
    private void chunkpilot$onSend(Packet<?> packet, net.minecraft.network.PacketSendListener listener,
                                   CallbackInfo ci) {
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
            // 安全: mixin 异常不应破坏游戏
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
     * 估算 Packet 大小 (vanilla 不暴露精确的 tx 字节数)。
     * 1.20.1 的包类名与 1.21.x 基本一致; "ChunkBatch" 那一条在 1.20.1 永远不命中
     * (batch ack 是 1.20.3+ 的协议), 保留它只是为了让两个分支的估算函数保持同源。
     */
    private static int estimatePacketSize(Packet<?> packet) {
        if (packet == null) return 0;
        String name = packet.getClass().getSimpleName();
        if (name.contains("LevelChunk") || name.contains("ChunkData")) return 32768;  // 32 KB
        if (name.contains("LightUpdate")) return 8192;   // 8 KB
        if (name.contains("ChunkBatch")) return 16384;   // 1.20.1 不会命中
        if (name.contains("BlockUpdate")) return 128;
        if (name.contains("BlockEntityData")) return 256;
        if (name.contains("Entity")) return 512;
        if (name.contains("Container") || name.contains("Inventory")) return 1024;
        if (name.contains("Position") || name.contains("PlayerPos")) return 64;
        if (name.contains("Animate") || name.contains("EntityEvent")) return 32;
        return 128;
    }
}
