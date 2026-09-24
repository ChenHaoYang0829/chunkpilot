package com.chunkpilot.neoforge.mixin;

// port/26.2: 从 fabric 侧按语义等价移植 (二阶段整改"neoforge 缺那族 mixin");
//   源: fabric/src/main/java/com/chunkpilot/fabric/mixin/<同名> (权威语义) +
//       /data/cp-port/1.21.5/neoforge/... 的成品 (含 require=0/expect=0 防御性注入);
//   26.2 适配: 仅 ChunkPos API (asLong→pack / toLong→pack); 其余逐行未改。

import com.chunkpilot.neoforge.util.MoveGuard;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 二阶段 B2 整改: 把 fabric 的 {@code ServerGamePacketListenerMoveMixin} 移植到 neoforge
 * (作业书 §2 表格 "❌ 缺" 的第四项).
 *
 * v0.11.6: 给"玩家移动包处理"打上 ThreadLocal 标记 (见 {@link MoveGuard}).
 *
 * 作用范围就是这个方法内部那几十行 —— 落地检查/流体检查/碰撞解析:
 *   handleMovePlayer → Entity.move → Entity.collide → collectColliders → Level.getChunkForCollisions
 *   handleMovePlayer → ServerPlayer.doCheckFallDamage → … → Level.getFluidState
 * 在这个范围内, CP 让"未就绪区块"按不存在处理, 主线程不再 park.
 *
 * ============================ javap 依据 (四个版本逐版核对) ============================
 *   net.minecraft.server.network.ServerGamePacketListenerImpl:
 *     public void handleMovePlayer(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket);
 *   ⇒ 四个版本签名完全一致 (1.21.5 / 1.21.6 / 1.21.7 / 1.21.8),
 *     核对对象含 NeoForge 打补丁后的 -server.jar.
 *
 * 语义影响: **无** —— 本 mixin 只维护一个 ThreadLocal 计数, 不改变 handleMovePlayer 的任何
 *   返回值/副作用. 真正的行为改动只发生在 {@link LevelNonBlockingReadMixin} 里, 且被
 *   `[protection] nonBlockingCollision` 开关控制.
 *
 * 防御性注入: `require = 0, expect = 0` —— 目标缺失只是"移动期间不做非阻塞替换",
 *   服务端照常启动、照常按原版行为跑.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMoveMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 0, expect = 0)
    private void chunkpilot$enterMoveGuard(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        try {
            MoveGuard.enter();
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"), require = 0, expect = 0)
    private void chunkpilot$exitMoveGuard(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        try {
            MoveGuard.exit();
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }
}
