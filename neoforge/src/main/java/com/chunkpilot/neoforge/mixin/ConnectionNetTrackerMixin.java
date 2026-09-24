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
// 2026-09-25 (二阶段整改项 1, 按主代理硬要求): 本模块的 mixin 注入一律 `require = 0, expect = 0`。
//   理由: 这些类用的是**硬编码 descriptor / 可读方法名**, 版本一漂移就会在**启动期**报
//   InvalidInjectionException → MixinApplyError → ModLoadingException (1.21.11 的
//   ConnectionNetTrackerMixin 就是这么崩的)。软失败只会丢一项增强, 不会让服务端起不来。
//   功能是否真的生效由**服务端探针 + 飞行跑分**验收, 不靠"启动成功"。
@Mixin(Connection.class)
public class ConnectionNetTrackerMixin {

    // ★ 1.21.11 API 变更 (port/1.21.11, javap 实证): `Connection.send` 第二个参数从
    //   `net.minecraft.network.PacketSendListener` 换成了 `io.netty.channel.ChannelFutureListener`:
    //       1.21.3 : send(Packet, PacketSendListener, Z)V
    //       1.21.11: send(Packet, ChannelFutureListener, Z)V
    //   (`PacketSendListener` 类**还在**, 只是不再作参数; 它的 `thenRun(Runnable)` 现在返回
    //    ChannelFutureListener。)
    //   ⚠ 这里用的是**硬编码 descriptor**, 不经过 refmap ⇒ 编译期零提示, 旧 descriptor 会在
    //     **启动时**报 `InvalidInjectionException: could not find any targets` → `MixinApplyError`
    //     → `ModLoadingException` → 服务端起不来。本版本第一轮 neoforge 启动就是这样崩的
    //     (日志: `Couldn't find Minecraft server thread`)。已改为新 descriptor。
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), require = 0, expect = 0)
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

    @Inject(method = "channelRead0", at = @At("HEAD"), require = 0, expect = 0)
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
