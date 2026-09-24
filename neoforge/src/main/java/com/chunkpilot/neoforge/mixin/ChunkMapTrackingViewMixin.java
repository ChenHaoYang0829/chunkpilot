package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.core.ForwardWindowController;
import com.chunkpilot.neoforge.util.MixinProbe;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v0.11.6 前瞻窗口 — 发送侧 (客户端可见窗口前移) —— neoforge 移植 (main 作业书 §2 第 4 项)。
 *
 * 这是 §7.5.1a 列的"NeoForge 缺发送侧窗口前移 mixin ⇒ 区块生成了也送不到玩家手上"那一项。
 *
 * **语义与 `com.chunkpilot.fabric.mixin.ChunkMapTrackingViewMixin` 逐条等价**:
 *   把 `ChunkMap.updateChunkTracking` 里 `ChunkTrackingView.of(chunkPos, vd)` 的**中心**
 *   换成 `chunkPos + shift` (shift 由 `ForwardWindowController` 按玩家速度/方向给出)。
 *   可见窗口变成: 前方 vd + S 区块, 后方 vd - S 区块。
 *
 * 兼容性 (作业书 §3):
 *   1. 只在高速移动时生效 (`ForwardWindowController` 的速度门控); 静止/走路返回原版;
 *   2. shift 被钳制在 [0, viewDistance] (在 ForwardWindowController 内), 玩家自身区块永远在窗口内;
 *   3. 窗口仍是 `ChunkTrackingView.Positioned` 的对称方块、半径不变 ⇒ 客户端
 *      `ClientChunkCache.Storage` 的环形缓冲不会冲突 (服务端只发窗口内区块);
 *   4. `ChunkTrackingView.difference(from,to)` 在 from.equals(to) 时直接 return ⇒ "中心没变"的 tick 零额外开销;
 *   5. 开关 = **已有的** `[forward_window] enabled` (默认 true, 但 shift==0 时严格返回原版);
 *   6. 任何异常都回退原版 `ChunkTrackingView.of(chunkPos, viewDistance)`; 不新增配置键。
 *
 * javap 依据 (1.21.9/1.21.10/1.21.11 逐版本核对, 调用点常量池完全同型):
 *   ChunkMap: private void updateChunkTracking(ServerPlayer)
 *     57: invokestatic InterfaceMethod ChunkTrackingView.of:(Lnet/minecraft/world/level/ChunkPos;I)Lnet/minecraft/server/level/ChunkTrackingView;
 *     60: invokevirtual ChunkMap.applyChunkTrackingView:(ServerPlayer;ChunkTrackingView)V
 *   ⇒ 每版本恰好 1 处 `ChunkTrackingView.of` 调用点, @Redirect 不会误伤其它调用。
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
            MixinProbe.once("trackingShift",
                "ChunkMap.updateChunkTracking 前瞻窗口偏移已生效 (开关 [forward_window] enabled=true)");
            return ChunkTrackingView.of(new ChunkPos(chunkPos.x + shift[0], chunkPos.z + shift[1]),
                viewDistance);
        } catch (Throwable t) {
            // 任何异常都回退原版 (与 fabric 侧逐字一致)
            return ChunkTrackingView.of(chunkPos, viewDistance);
        }
    }
}
