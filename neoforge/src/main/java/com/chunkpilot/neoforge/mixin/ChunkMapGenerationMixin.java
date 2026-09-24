package com.chunkpilot.neoforge.mixin;

// port/26.2: 从 fabric 侧按语义等价移植 (二阶段整改"neoforge 缺那族 mixin");
//   源: fabric/src/main/java/com/chunkpilot/fabric/mixin/<同名> (权威语义) +
//       /data/cp-port/1.21.5/neoforge/... 的成品 (含 require=0/expect=0 防御性注入);
//   26.2 适配: 仅 ChunkPos API (asLong→pack / toLong→pack); 其余逐行未改。

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.GenerationConfig;
import com.chunkpilot.neoforge.platform.NeoForgePlatform;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * 二阶段 B2 整改: 把 fabric 的 {@code ChunkMapGenerationMixin} 移植到 neoforge
 * (作业书 §2 表格 "❌ 缺" 的第六项; §7.5.1a: "NeoForge 上
 * {@code generation.exclusiveGenerationNoC2me} 空转").
 *
 * ============================ 做法: 只调优先级, 绝不丢弃任务 ============================
 * 拦截 {@code ChunkMap.runGenerationTask} 里对 {@code ChunkTaskDispatcher.submit} 的调用,
 * 对 CP 请求/持票的 chunk 包一层 IntSupplier, 把投递优先级 (level) 压到 CP_PRIORITY_CAP.
 *   - 优先级只在 ChunkTaskPriorityQueue 内部用于排序 (低 level 先做), 不影响正确性;
 *   - 任务一个都不丢, 5x5 依赖链完整 → 不会再死锁;
 *   - 上限 33 = 玩家所在 chunk 的 level 附近, CP 前方 chunk 因此排在原版环状浅层依赖
 *     (34~41) 之前, 但**不会插到玩家自身区域 (31~32) 前面** (避免玩家所在区块被饿死).
 *
 * 历史教训 (fabric 侧注释, 同样适用): 旧实现在 runGenerationTask 里 ci.cancel() 掉
 * "不在 CP 请求集合"的生成任务, 在 1.21.3+ 上**必然**导致 watchdog 死锁 ——
 * ① 升到 FULL 要求整个 5x5 邻域全 FULL, 丢任一邻域任务 ⇒ 玩家自己的 chunk 永远升不到 FULL;
 * ② GenerationChunkHolder.task 仍指向被丢弃的任务 ⇒ 之后重排被拒 ⇒ 永久卡死.
 * 所以本类**只改排序 key, 不取消任何任务**.
 *
 * ============================ 开关 (全部是**已有**配置项, 未新增) ============================
 *   生效需要同时满足:
 *     - `[generation] enabled = true` (jar 内模板默认 true) 且
 *       `exclusiveGenerationNoC2me = true` (默认 true);
 *     - 没有 C2ME (neoforge 生态本来就没有);
 *     - 场上有**速度超过 `v_min`** 的玩家 (静止/走路时完全走原版顺序);
 *     - 该 chunk 在 CP 的本 tick 请求集合或 CP 活跃票集合里.
 *   任何一条不满足 ⇒ 逐字走原版 `dispatcher.submit(runnable, chunkPosLong, level)`.
 *   任何异常 ⇒ 同上, 原版参数原样提交.
 *
 * ============================ javap 依据 (四个版本逐版核对, 字节码形状完全一致) ============================
 *   net.minecraft.server.level.ChunkMap:
 *     private final ChunkTaskDispatcher worldgenTaskDispatcher;
 *     private void runGenerationTask(net.minecraft.server.level.ChunkGenerationTask);
 *   net.minecraft.server.level.ChunkTaskDispatcher:
 *     public void submit(java.lang.Runnable, long, java.util.function.IntSupplier);
 *
 *   反汇编 (javap -c, 四个版本的 NeoForge -server.jar) 实证 runGenerationTask 里
 *   `ChunkTaskDispatcher.submit` 只有**一处**调用, 且是**直接**写在 runGenerationTask 字节码里
 *   (不是藏在 lambda 里):
 *     offset 34: invokevirtual ChunkTaskDispatcher.submit:(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V
 *   (同类的 lambda$runGenerationTask$21/$22 只做 runUntilWait/thenRun, 不含 submit.)
 *   ⇒ @Redirect 精确命中. 若某版本把它挪进 lambda, 本类因 require=0 **静默失效**而不崩服.
 *
 * ============================ 关于 neoforge 侧的 CP 请求集合 ============================
 *   fabric 的判据是 `FabricPlatform.isChunkRequestedByCp / isChunkTicketedByCp`.
 *   neoforge 平台此前**没有**这两个集合 (PORTING_REPORT §7.5.0c 明确写了"不输出
 *   cpRequested/cpTicketed"), 本整改按 fabric 的同一语义在 NeoForgePlatform 上补齐了
 *   等价记账 (只在 CP 自己的 requestChunkAsync/addChunkTicket/removeChunkTicket 里 add/remove,
 *   **不触碰任何原版字段或行为**), 并把 `rebuildCpTicketMarks` 覆写为与 fabric 同法.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGenerationMixin {

    /** CP 请求/持票 chunk 的投递优先级上限 (与 fabric 完全一致; 见类注释的历史沿革). */
    private static final int CP_PRIORITY_CAP = 33;

    private static final AtomicLong cpBoosted = new AtomicLong();
    private static final AtomicLong cpNormal = new AtomicLong();
    private static final AtomicLong cpLastLog = new AtomicLong();

    @Redirect(
        method = "runGenerationTask",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskDispatcher;submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V"
        ),
        require = 0,
        expect = 0
    )
    private void chunkpilot$submitWithCpPriority(ChunkTaskDispatcher dispatcher, Runnable runnable,
                                                 long chunkPosLong, IntSupplier level) {
        IntSupplier effective = level;
        try {
            if (chunkpilot$shouldPrioritize(chunkPosLong)) {
                final IntSupplier base = level;
                // 只包一层, 不改变任务本身; 排序 key 取 min(原 level, CP_PRIORITY_CAP)
                effective = () -> Math.min(base.getAsInt(), CP_PRIORITY_CAP);
                long n = cpBoosted.incrementAndGet();
                if (n == 1) {
                    log("CP 独占生成(neoforge): 开始对 CP 请求区块提优先级 (cap=" + CP_PRIORITY_CAP + ")");
                }
            } else {
                cpNormal.incrementAndGet();
            }
            chunkpilot$maybeLogSummary();
        } catch (Throwable t) {
            effective = level; // 任何异常都不影响原版提交
        }
        dispatcher.submit(runnable, chunkPosLong, effective);
    }

    /** CP 请求集合 / CP 持票集合里的 chunk 才提优先级 (与 fabric 同判据). */
    private static boolean chunkpilot$shouldPrioritize(long chunkPosLong) {
        try {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null) return false;
            GenerationConfig genCfg = cp.getConfig().generation;
            if (genCfg == null || !genCfg.enabled) return false;
            if (!genCfg.exclusiveGenerationNoC2me) return false;
            // 有 C2ME 时由 C2ME 管生成, CP 不插手
            if (cp.getPlatform().isModLoaded("c2me")) return false;
            // 只有快速移动玩家在场时才启用 (静止时保持原版顺序)
            if (!cp.getOptimizer().getSpeedTracker()
                    .hasPlayerAboveSpeedChunksPerSecond(genCfg.v_min)) return false;
            return NeoForgePlatform.isChunkRequestedByCp(chunkPosLong)
                || NeoForgePlatform.isChunkTicketedByCp(chunkPosLong);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每 20000 次提交打一行统计, 便于飞行测试时判断独占生成是否真的在起作用. */
    private static void chunkpilot$maybeLogSummary() {
        long total = cpBoosted.get() + cpNormal.get();
        long last = cpLastLog.get();
        if (total - last >= 20_000 && cpLastLog.compareAndSet(last, total)) {
            log("CP 独占生成统计(neoforge): CP 优先=" + cpBoosted.get() + ", 原版正常=" + cpNormal.get());
        }
    }

    private static void log(String msg) {
        try {
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").info("[ChunkPilot] " + msg);
        } catch (Throwable t) { /* ignore */ }
    }
}
