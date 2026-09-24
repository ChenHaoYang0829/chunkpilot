package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.neoforge.util.MoveGuard;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v0.11.6: 给"玩家移动包处理"打上 ThreadLocal 标记 (NeoForge 移植, 与 fabric 侧
 * `ServerGamePacketListenerMoveMixin` 等价).
 *
 * 作用范围就是这个方法内部那几十行 —— 落地检查/流体检查/碰撞解析.
 * 在这个范围内, CP 让"未就绪区块"按不存在处理 (`[protection] nonBlockingCollision`
 * + `nonBlockingReads=false` 时的方块/流体读取), 主线程不再 park.
 *
 * javap 实证 (1.21.1~1.21.4 一致):
 *   public void net.minecraft.server.network.ServerGamePacketListenerImpl
 *        .handleMovePlayer(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket);
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMoveMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 0, expect = 0)
    private void chunkpilot$enterMoveGuard(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        MoveGuard.enter();
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"), require = 0, expect = 0)
    private void chunkpilot$exitMoveGuard(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        MoveGuard.exit();
    }
}
