package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.neoforge.util.MoveGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 二阶段 B2 整改: 把 fabric 的 {@code LevelNonBlockingReadMixin} **按语义等价移植到 neoforge**
 * (作业书 §2 表格里 "❌ 缺" 的第一项).
 *
 * ============================ 为什么必须有它 (已定位的根因之一) ============================
 * PORTING_REPORT §7.3b / §7.5.0b: neoforge 上装 CP 后主线程仍会 park, 崩服栈是
 *
 *   Unsafe.park ← BlockableEventLoop.managedBlock(:137) ← ServerChunkCache$MainThreadExecutor.managedBlock(:584)
 *     ← ServerChunkCache.getChunk(:174) ← Level.getChunk(:201) ← Level.getChunkForCollisions(:796)
 *
 * 而 `[protection] nonBlockingCollision` **默认 true** 却在 neoforge 上一直空转
 * (§7.5.1a: "只能靠 sync-chunk-writes=false"). 本类就是那条开关的落点.
 *
 * 关键: `load=false` **并不等于不阻塞**. 只要 ChunkHolder 在可见表里 (玩家票范围内)
 * 但还没到 FULL, `ServerChunkCache.getChunk` 依然 managedBlock 等它完成.
 *
 * 更致命的是这是个**正反馈死锁**: 主线程被 park 住 → `ChunkMap.tick`/`runAllUpdates`
 * 这些"把生成任务推下去"的步骤全都停摆 → 那个区块永远到不了 FULL → park 永远不返回.
 *
 * ============================ 本 Mixin 做什么 (与 fabric 逐条一致) ============================
 *   三个入口, 全部先判断"该区块是否**已经** FULL":
 *     1. Level.getChunkForCollisions  (始终生效, 挂在 nonBlockingCollision 下) → 未就绪返回 null
 *        (这是原版自己的"区块不存在"返回值, BlockCollisions 直接跳过, 无需调用方适配)
 *     2. Level.getBlockState          (仅玩家移动包处理期间) → 未就绪返回 AIR
 *     3. Level.getFluidState          (仅玩家移动包处理期间) → 未就绪返回 EMPTY
 *
 * 语义影响: "还没生成完的区块" 对碰撞/方块/流体读而言等于不存在 ——
 *   这与原版**客户端**的行为一致 (客户端没有的区块本来就没有方块/碰撞).
 *   已加载 (FULL) 区块的任何返回值**完全不变**; 返回类型不变; 异常一律吞掉走原版.
 *   只作用于 ServerLevel (客户端 Level 本来就不阻塞).
 *
 * ============================ javap 依据 (四个版本逐版核对, 结论完全一致) ============================
 *   net.minecraft.world.level.Level:
 *     public net.minecraft.world.level.BlockGetter getChunkForCollisions(int, int);
 *     public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos);
 *     public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos);
 *   net.minecraft.server.level.ChunkHolder:
 *     public int getTicketLevel();
 *     public java.util.concurrent.CompletableFuture&lt;ChunkResult&lt;LevelChunk&gt;&gt; getFullChunkFuture();
 *   核对对象: ① NeoForge 打补丁后的 -server.jar (运行期真字节码) ② loom 缓存同版本 mapped MC jar.
 *
 * ============================ 防御性注入 ============================
 *   每个注入点都写了 `require = 0, expect = 0` (作业书 §3.3): 若某版本某个目标不存在,
 *   **只是该 mixin 不生效**, 绝不让服务端启动崩溃.
 */
@Mixin(Level.class)
public abstract class LevelNonBlockingReadMixin {

    /** 该区块是否**已经**生成到 FULL (全程不阻塞: 一次 map 查找 + CompletableFuture.getNow). */
    private static boolean chunkpilot$isFullyReady(ServerLevel level, int chunkX, int chunkZ) {
        ChunkHolder holder = ((ServerChunkCacheAccessor) (ServerChunkCache) level.getChunkSource())
            .chunkpilot$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        if (holder == null) return false;
        CompletableFuture<ChunkResult<LevelChunk>> full = holder.getFullChunkFuture();
        if (!full.isDone()) return false;
        ChunkResult<LevelChunk> res = full.getNow(null);
        return res != null && res.isSuccess() && res.orElse(null) != null;
    }

    /** 与 fabric 同源: 由已有的 `[protection] nonBlockingCollision` 开关控制 (默认 true). */
    private static boolean chunkpilot$enabled() {
        ChunkPilot cp = ChunkPilot.getInstance();
        return cp != null && cp.getConfig() != null && cp.getConfig().nonBlockingCollision;
    }

    /** 与 fabric 同源: 方块/流体读取是否全局不阻塞 (false = 只在玩家移动包处理期间). */
    private static boolean chunkpilot$readsAlways() {
        ChunkPilot cp = ChunkPilot.getInstance();
        return cp == null || cp.getConfig() == null || cp.getConfig().nonBlockingReads;
    }

    // ---- 1) 碰撞查询: 始终生效 (只影响实体碰撞, 影响面最小, 收益最大) ----
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void chunkpilot$nonBlockingCollision(int chunkX, int chunkZ,
                                                 CallbackInfoReturnable<BlockGetter> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, chunkX, chunkZ)) return;
            cir.setReturnValue(null); // 原版"区块不存在"路径
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 2) 方块读取: 只在玩家移动包处理期间生效 (nonBlockingReads=false 时) ----
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void chunkpilot$nonBlockingBlockState(BlockPos pos,
                                                  CallbackInfoReturnable<BlockState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 3) 流体读取: 只在玩家移动包处理期间生效 (nonBlockingReads=false 时) ----
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void chunkpilot$nonBlockingFluidState(BlockPos pos,
                                                  CallbackInfoReturnable<FluidState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }
}
