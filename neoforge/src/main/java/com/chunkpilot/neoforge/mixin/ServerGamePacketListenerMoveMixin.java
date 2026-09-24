package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.neoforge.util.MoveGuard;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v0.11.6 (neoforge 移植): 给"玩家移动包处理"打上 ThreadLocal 标记 (见 {@link MoveGuard})。
 *
 * **纯标记, 自身不改变任何原版行为** —— 它只是把 `LevelNonBlockingReadMixin` 的
 * `getBlockState`/`getFluidState` 两个分支的作用范围精确限制在
 * `handleMovePlayer` 内部那几十行 (落地检查 / 流体检查 / 碰撞解析)。
 *
 * 兼容性: 不读配置 (开关在 LevelNonBlockingReadMixin 里判), 不吞异常,
 * `require=0, expect=0` ⇒ 该版本方法不存在时只是本标记不生效 (非阻塞读取退回"不生效"=原版)。
 *
 * javap 依据 (1.21.9/1.21.10/1.21.11 一致):
 *   ServerGamePacketListenerImpl.handleMovePlayer(ServerboundMovePlayerPacket) -> void
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
