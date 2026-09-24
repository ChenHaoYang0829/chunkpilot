package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.core.ForwardWindowController;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 二阶段 B2 整改: 把 fabric 的 {@code ChunkMapTrackingViewMixin} 移植到 neoforge
 * (作业书 §2 表格 "❌ 缺" 的第五项; 也是 §7.5.1a 点名的"发送侧窗口前移").
 *
 * ============================ 原版行为 ============================
 *   ChunkMap.tick()
 *     → for each player: updateChunkTracking(player)
 *         ChunkPos chunkPos = player.chunkPosition();
 *         int vd = getPlayerViewDistance(player);      // clamp(requested, 2, serverViewDistance)
 *         if (player.getChunkTrackingView() 已是以 chunkPos 为中心、半径 vd) return;
 *         applyChunkTrackingView(player, ChunkTrackingView.of(chunkPos, vd));
 *   而 ChunkMap.onChunkReadyToSend(holder, chunk) 里有:
 *         if (player.getChunkTrackingView().contains(chunk.getPos())) markChunkPendingToSend(player, chunk);
 *
 *   ⇒ **ChunkTrackingView 就是"服务端愿意下发给这个玩家的区块"的唯一闸门**.
 *     它是以玩家为中心的对称方块 [p-vd, p+vd], 所以玩家前方的可用提前量 = vd 区块.
 *   ⇒ 缺了这条, NeoForge 上"身后已加载"压不下来 (NF CP 118~120 ≈ NF vanilla 116.8,
 *     而 fabric CP 能压到 31), 且 CP 预生成的区块**生成了也送不到玩家手上** (§7.5.1a).
 *
 * ============================ 本 Mixin 做什么 ============================
 *   把 ChunkTrackingView.of(chunkPos, vd) 的**中心**换成
 *   chunkPos + shift (shift 由 ForwardWindowController 按玩家速度/方向给出).
 *   可见窗口因此变成: 前方 vd + S 区块, 后方 vd - S 区块.
 *
 * ============================ 安全性 (逐条, 与 fabric 一致) ============================
 *   1. 只在高速移动时生效 (ForwardWindowController 的速度门控); 静止/走路返回原版;
 *      且整体挂在**已有**的 `[forward_window] enabled` 开关下 (默认 true).
 *   2. shift 被钳制在 [0, viewDistance] (由 ForwardWindowController 保证),
 *      玩家自身区块永远留在窗口内.
 *   3. 窗口仍是 ChunkTrackingView.Positioned 的对称方块, 半径不变, 所以客户端
 *      ClientChunkCache.Storage 的环形缓冲不会冲突 (服务端只发窗口内区块).
 *   4. ChunkTrackingView.difference(from,to) 在 from.equals(to) 时直接 return,
 *      所以中心没变的 tick 不产生任何额外工作量.
 *   5. **任何异常都回退原版**; shift 为 0 时逐字返回 `ChunkTrackingView.of(chunkPos, vd)`,
 *      与原版**完全等价** (同一个静态调用、同样的参数).
 *
 * ============================ javap 依据 (四个版本逐版核对, 字节码形状完全一致) ============================
 *   net.minecraft.server.level.ChunkMap:
 *     private void updateChunkTracking(net.minecraft.server.level.ServerPlayer);
 *   net.minecraft.server.level.ChunkTrackingView:
 *     public static ChunkTrackingView of(net.minecraft.world.level.ChunkPos, int);
 *
 *   反汇编 (javap -c, NeoForge -server.jar) 实证四个版本的 updateChunkTracking 里
 *   `ChunkTrackingView.of` 都只有**一处**调用, 且位于
 *     offset 57: invokestatic  ChunkTrackingView.of:(Lnet/minecraft/world/level/ChunkPos;I)Lnet/minecraft/server/level/ChunkTrackingView;
 *     offset 60: invokevirtual applyChunkTrackingView:(LServerPlayer;LChunkTrackingView;)V
 *   ⇒ @Redirect 唯一命中, 且重定向的返回值直接喂给 applyChunkTrackingView,
 *     handler 的额外参数 (ServerPlayer) 正好是宿主方法唯一的参数.
 *
 * 防御性注入: `require = 0, expect = 0` —— 目标缺失只是窗口不前移, 一切按原版.
 
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
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackingViewMixin {

    /** 诊断限流: 每 10 秒最多打一行"窗口前移确实生效"的证据. */
    private static volatile long cpLastLogSecond = 0L;

    /**
     * ★ 26.x 诊断: @Redirect handler 被调用的次数 + 首次调用打一行 INFO.
     * 为什么需要: 本注入是 `require = 0, expect = 0` —— 目标缺失时**静默跳过**。
     *   没有这一行,"mixin 没生效"与"生效了但 shift 恰好为 0"在日志里完全分不清
     *   (端口 26.1 实测踩到: 三个新 mixin 一条日志都没有, 无法判断是被跳过还是条件未满足)。
     * 只在首次调用打一行, 无长期噪声。
     */
    /// ⚠ Mixin 硬约束 (端口 26.1 实测踩到): mixin 类里**不允许非 private 的 static 方法**
    ///   —— 会以 `InvalidMixinException: contains non-private static method` 直接**崩服**
    ///   (实测: `public static long chunkpilot$handlerCalls()` 让 `ServerChunkCache.<init>`
    ///    在加载 ChunkMap 时 MixinApplyError → FatalStartupException)。
    ///   因此诊断计数器只做字段, 不暴露访问器。
    private static final java.util.concurrent.atomic.AtomicLong cpHandlerCalls =
        new java.util.concurrent.atomic.AtomicLong();

    @Redirect(
        method = "updateChunkTracking",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTrackingView;of(Lnet/minecraft/world/level/ChunkPos;I)Lnet/minecraft/server/level/ChunkTrackingView;"
        ),
        require = 0,
        expect = 0
    )
    private ChunkTrackingView chunkpilot$shiftedTrackingView(ChunkPos chunkPos, int viewDistance,
                                                             ServerPlayer player) {
        try {
            if (cpHandlerCalls.incrementAndGet() == 1L) {
                org.slf4j.LoggerFactory.getLogger("ChunkPilot").info(
                    "[ChunkPilot] 发送侧窗口前移(neoforge): @Redirect handler 首次被调用 "
                    + "(说明 updateChunkTracking 的注入点已命中)");
            }
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null || player == null) return ChunkTrackingView.of(chunkPos, viewDistance);
            if (cp.getConfig() == null || cp.getConfig().forwardWindow == null
                    || !cp.getConfig().forwardWindow.enabled) {
                return ChunkTrackingView.of(chunkPos, viewDistance);
            }
            ForwardWindowController fw = cp.getOptimizer().getForwardWindow();
            if (fw == null) return ChunkTrackingView.of(chunkPos, viewDistance);
            int[] shift = fw.trackingShift(player.getUUID());
            if (shift == null || (shift[0] == 0 && shift[1] == 0)) {
                return ChunkTrackingView.of(chunkPos, viewDistance);
            }
            chunkpilot$maybeLog(chunkPos, viewDistance, shift);
            return ChunkTrackingView.of(new ChunkPos(chunkPos.x() + shift[0], chunkPos.z() + shift[1]),
                viewDistance);
        } catch (Throwable t) {
            return ChunkTrackingView.of(chunkPos, viewDistance);
        }
    }

    /** 低频率日志: 证明发送侧窗口前移在 neoforge 上**真的**被调用到了 (验收证据之一). */
    private static void chunkpilot$maybeLog(ChunkPos pos, int vd, int[] shift) {
        try {
            long sec = System.currentTimeMillis() / 10000L;
            if (sec == cpLastLogSecond) return;
            cpLastLogSecond = sec;
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").info(
                "[ChunkPilot] 发送侧窗口前移(neoforge): 玩家窗口中心 {} -> {} (vd={})",
                pos, new ChunkPos(pos.x() + shift[0], pos.z() + shift[1]), vd);
        } catch (Throwable t) { /* ignore */ }
    }
}
