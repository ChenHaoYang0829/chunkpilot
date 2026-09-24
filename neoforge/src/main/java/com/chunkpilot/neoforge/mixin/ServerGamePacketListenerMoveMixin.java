package com.chunkpilot.neoforge.mixin;

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
 
 * ============================ ★ 26.x 适配 (port/26.1, 2026-09-24) ============================
 * 本文件从 `/data/cp-port/1.21.5/neoforge/...` 的 B2 整改版**逐字照抄**, 只做了 26.1 的真实 API 差异替换
 * (每一条都有 javap 实证, 见 `artifacts/26.1/REPORT.md` §3.2 与 §11):
 *   · `ChunkPos` 在 26.1 变成 **record** ⇒ `asLong(int,int)` → `pack(int,int)`,
 *     字段 `x`/`z` → 访问器 `x()`/`z()`; (`toLong()` → `pack()`)
 *   · 26.1 **不再混淆** ⇒ 不再需要任何 refmap; 本模块的 `fixRefmap` 已整体删除
 *     (见根报告 §2.2 B6), 所以"新增 mixin 要同步改硬编码 refmap"这条历史约束**已消失**,
 *     新增 `ServerChunkCacheAccessor` 不再有额外成本。
 *   · 注入点全部用 26.1 **内层真实服务端 jar**(`META-INF/versions/26.1/server-26.1.jar`)
 *     与 loom 的 `minecraft-merged-deobf-26.1.jar` 双向核对:
 *       Level.getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;                       public
 *       Level.getBlockState(Lnet/minecraft/core/BlockPos;)L.../block/state/BlockState;               public
 *       Level.getFluidState(Lnet/minecraft/core/BlockPos;)L.../material/FluidState;                  public
 *       ServerChunkCache.getChunk(IIL.../chunk/status/ChunkStatus;Z)L.../chunk/ChunkAccess;          public
 *       ServerChunkCache.getVisibleChunkIfPresent(J)Lnet/minecraft/server/level/ChunkHolder;         private
 *       ServerChunkCache.mainThread:Ljava/lang/Thread;                                               包可见 final
 *       ServerChunkCache.getChunkFutureMainThread(IIL.../ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;  private
 *       ChunkMap.updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V                     private (offset 58 调用 ChunkTrackingView.of)
 *       ChunkMap.runGenerationTask(Lnet/minecraft/server/level/ChunkGenerationTask;)V                private (offset 34 调用 ChunkTaskDispatcher.submit)
 *       ChunkTaskDispatcher.submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V           public
 *       ServerGamePacketListenerImpl.handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V  public
 *     ⇒ 全部命中, 与 1.21.5 的字节码形状一致; 且 26.1 / 26.1.1 / 26.1.2 三者 javap 输出 IDENTICAL。
 *   · 每个注入点都保留 `require = 0, expect = 0`: 目标缺失只是**该点不生效**, 绝不让服务端启动崩。
 * ================================================================================
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
