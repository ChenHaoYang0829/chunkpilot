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
 * v0.11.6 前瞻窗口 — **发送侧** (客户端可见窗口前移). NeoForge 移植, 与 fabric 侧
 * `ChunkMapTrackingViewMixin` **逐条等价** (同一配置节 `[forward_window]`, 同一速度门控).
 *
 * ============================ 原版行为 ============================
 *   ChunkMap.tick() → for each player: updateChunkTracking(player)
 *       ChunkPos chunkPos = player.chunkPosition();
 *       int vd = getPlayerViewDistance(player);
 *       if (player.getChunkTrackingView() 已是以 chunkPos 为中心、半径 vd) return;
 *       applyChunkTrackingView(player, ChunkTrackingView.of(chunkPos, vd));
 *   而 ChunkMap.onChunkReadyToSend(holder, chunk) 里有:
 *       if (player.getChunkTrackingView().contains(chunk.getPos())) markChunkPendingToSend(player, chunk);
 *
 *   ⇒ **ChunkTrackingView 就是"服务端愿意下发给这个玩家的区块"的唯一闸门**.
 *     NeoForge 组此前**缺这一支** ⇒ 区块生成了也送不到玩家手上 (`lead_loaded_srv` 长期偏低),
 *     这是 PORTING_REPORT §7.5.1a 点名的第 ③ 条根因.
 *
 * ============================ 本 Mixin 做什么 ============================
 *   把 ChunkTrackingView.of(chunkPos, vd) 的**中心**换成 chunkPos + shift
 *   (shift 由 ForwardWindowController 按玩家速度/方向给出) ⇒ 可见窗口变成
 *   前 vd + S / 后 vd - S, 前方区块一生成立刻下发, 而不是等玩家走近.
 *
 * 安全性 (与 fabric 相同):
 *   1. 只在高速移动时生效 (ForwardWindowController 的速度门控); 静止/走路返回原版;
 *   2. shift 被钳制在 [0, viewDistance], 玩家自身区块永远留在窗口内;
 *   3. 窗口仍是对称方块、半径不变 ⇒ 客户端 ClientChunkCache 环形缓冲不冲突;
 *   4. ChunkTrackingView.difference(from,to) 在 from.equals(to) 时直接 return, 无额外工作量;
 *   5. 任何异常都回退原版. `[forward_window] enabled=false` → 100% 原版.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackingViewMixin {

    @Redirect(
        method = "updateChunkTracking",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTrackingView;of(Lnet/minecraft/world/level/ChunkPos;I)Lnet/minecraft/server/level/ChunkTrackingView;"
        ),
        require = 0, expect = 0
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
