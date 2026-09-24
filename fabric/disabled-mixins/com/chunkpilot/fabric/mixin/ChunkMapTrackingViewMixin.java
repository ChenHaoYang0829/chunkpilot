package com.chunkpilot.fabric.mixin;

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
 * v0.11.6 前瞻窗口 — 发送侧 (客户端可见窗口前移).
 *
 * ============================ 原版行为 (1.21.3 实证) ============================
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
 *
 * ============================ 本 Mixin 做什么 ============================
 *   把 ChunkTrackingView.of(chunkPos, vd) 的**中心**换成
 *   chunkPos + shift (shift 由 ForwardWindowController 按玩家速度/方向给出).
 *   可见窗口因此变成: 前方 vd + S 区块, 后方 vd - S 区块.
 *   配合生成侧锚点票 (ForwardWindowController 的锚点票把前方区块真的加载出来),
 *   前方区块一生成就立刻下发, 而不是等玩家走近才进入窗口。
 *
 * ============================ 安全性 ============================
 *   1. 只在高速移动时生效 (ForwardWindowController 的速度门控); 静止/走路返回原版.
 *   2. shift 被钳制在 [0, viewDistance], 玩家自身区块永远留在窗口内 (不会出现
 *      "玩家脚下不在可见窗口"这种荒唐状态).
 *   3. 窗口仍是 ChunkTrackingView.Positioned 的对称方块, 半径不变, 所以客户端
 *      ClientChunkCache.Storage 的环形缓冲不会冲突 (服务端只发窗口内区块).
 *   4. ChunkTrackingView.difference(from,to) 在 from.equals(to) 时直接 return,
 *      所以"中心没变"的 tick 不产生任何额外工作量.
 *   5. 任何异常都回退原版.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackingViewMixin {

    @Redirect(
        method = "updateChunkTracking",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTrackingView;of(Lnet/minecraft/world/level/ChunkPos;I)Lnet/minecraft/server/level/ChunkTrackingView;"
        )
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
            return ChunkTrackingView.of(new ChunkPos(chunkPos.x + shift[0], chunkPos.z + shift[1]),
                viewDistance);
        } catch (Throwable t) {
            return ChunkTrackingView.of(chunkPos, viewDistance);
        }
    }
}
