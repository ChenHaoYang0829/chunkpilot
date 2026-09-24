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
 * port/26.2 新增: 发送侧前瞻窗口 (把客户端可见窗口整体前移).
 *
 * ============================ 为什么本轮才补 ============================
 *   PORTING_REPORT §7.5.1a 的机械核对结论: **NeoForge 是"削弱版"移植** ——
 *   fabric 有 8 个服务端 mixin, neoforge 只有 6 个, 缺的正是本类
 *   (`ChunkMapTrackingViewMixin`) 代表的**发送侧窗口前移**族。
 *   行为证据: neoforge 的"身后已加载"压不下来 (NF CP ≈118~120 ≈ NF vanilla 116.8,
 *   而 fabric CP 能压到 31)。§7.3b 把"补这一族"列为下一轮首选改进项。
 *
 * ============================ 原版行为 (26.2 javap 实证) ============================
 *   `ChunkMap.updateChunkTracking(ServerPlayer)` 里调用
 *   `ChunkTrackingView.of(ChunkPos, int)`(javap: public static, 26.2 仍在)
 *   得到"服务端愿意下发给这个玩家的区块"的唯一闸门; 它是以玩家为中心的**对称**方块,
 *   所以玩家前方的可用提前量 = 视距。把中心换成 `chunkPos + shift` 之后,
 *   可见窗口变成"前方 vd+S / 后方 vd-S"。
 *
 * ============================ 安全性 (与 fabric 侧逐条相同) ============================
 *   1. 只在高速移动时生效 (`ForwardWindowController` 的速度门控), 静止/走路走原版;
 *   2. shift 被钳制在 [0, viewDistance], 玩家自身区块永远在窗口内;
 *   3. 窗口仍是半径不变的正方形, 客户端环形缓冲不会冲突;
 *   4. `ChunkTrackingView.difference(from,to)` 在 from.equals(to) 时直接 return,
 *      "中心没变"的 tick 不产生额外工作量;
 *   5. 任何异常都回退原版。
 *
 * ⚠ 目标方法是**不带硬编码 descriptor 的方法名**(走 mixin 的按名解析), 26.x 不重映射 ⇒
 *   注解里的 `Lnet/minecraft/...` 目标名就是运行期名字, 不需要 refmap。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackingViewMixin {

    @Redirect(
        method = "updateChunkTracking",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTrackingView;of(Lnet/minecraft/world/level/ChunkPos;I)Lnet/minecraft/server/level/ChunkTrackingView;"
        ),
        // 用户第 1 项兼容性硬要求: 目标缺失只"不生效", 绝不启动崩
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
            return ChunkTrackingView.of(new ChunkPos(chunkPos.x() + shift[0], chunkPos.z() + shift[1]),
                viewDistance);
        } catch (Throwable t) {
            return ChunkTrackingView.of(chunkPos, viewDistance);
        }
    }
}
