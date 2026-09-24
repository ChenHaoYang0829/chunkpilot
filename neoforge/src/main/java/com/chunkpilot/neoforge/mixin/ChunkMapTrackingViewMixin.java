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
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackingViewMixin {

    /** 诊断限流: 每 10 秒最多打一行"窗口前移确实生效"的证据. */
    private static volatile long cpLastLogSecond = 0L;

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
            return ChunkTrackingView.of(new ChunkPos(chunkPos.x + shift[0], chunkPos.z + shift[1]),
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
                pos, new ChunkPos(pos.x + shift[0], pos.z + shift[1]), vd);
        } catch (Throwable t) { /* ignore */ }
    }
}
