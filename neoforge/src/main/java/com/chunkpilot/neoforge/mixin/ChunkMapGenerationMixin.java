package com.chunkpilot.neoforge.mixin;

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
 * v0.11.0 CP 独占生成 (`exclusiveGenerationNoC2me`) —— neoforge 移植 (main 作业书 §2 第 5 项)。
 *
 * **语义与 `com.chunkpilot.fabric.mixin.ChunkMapGenerationMixin` 逐条等价** (只把
 * `FabricPlatform.isChunkRequestedByCp/isChunkTicketedByCp` 换成 NeoForgePlatform 的同名静态方法):
 *   拦截 `ChunkMap.runGenerationTask` 里对 `ChunkTaskDispatcher.submit` 的调用, 对 CP 请求/持票的 chunk
 *   包一层 IntSupplier, 把投递优先级 (level) 压到 CP_PRIORITY_CAP=33。
 *
 * **绝不丢弃任务** —— 这一点是关键: 旧实现曾 `ci.cancel()` 掉"不在 CP 请求集合"的生成任务,
 *   在 1.21.3+ 必然 watchdog 死锁 (区块升到 FULL 要求 5x5 邻域全 FULL; 丢掉一个就永久卡死)。
 *   现在的做法只改**排序 key**, 不改任务本身、不改 level 语义、不动 ChunkHolder/ChunkMap 的字段。
 *   优先级只在 ChunkTaskPriorityQueue 内部用于排序; 33 让 CP 前方 chunk 排在原版环状浅层依赖 (34~41)
 *   之前, 但**不会插到玩家自身区域 (31~32) 前面** (避免玩家所在区块被饿死)。
 *
 * 兼容性 (作业书 §3):
 *   - 挂在**已有的** `[generation] enabled` + `[generation] exclusiveGenerationNoC2me` 之下,
 *     且 `isModLoaded("c2me")` 时**自动让位给 C2ME** (有 C2ME 就不插手);
 *   - 只在"有高速移动玩家"时启用 (`SpeedTracker.hasPlayerAboveSpeedChunksPerSecond(v_min)`),
 *     静止时保持原版顺序;
 *   - 任何异常 → `effective = level` 原样提交; `require=0, expect=0` 软失败; 不新增配置键。
 *
 * javap 依据 (1.21.9/1.21.10/1.21.11 逐版本核对, 调用点完全同型):
 *   ChunkMap: private void runGenerationTask(ChunkGenerationTask)
 *     34: invokevirtual Method ChunkTaskDispatcher.submit:(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V
 *   ChunkTaskDispatcher.submit(Runnable, long, IntSupplier) -> void
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGenerationMixin {

    /** CP 请求/持票 chunk 的投递优先级上限 (33 = FULL 生成; 见 fabric 同名类的停摆根因说明). */
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
        require = 0, expect = 0
    )
    private void chunkpilot$submitWithCpPriority(ChunkTaskDispatcher dispatcher, Runnable runnable,
                                                 long chunkPosLong, IntSupplier level) {
        IntSupplier effective = level;
        try {
            if (chunkpilot$shouldPrioritize(chunkPosLong)) {
                final IntSupplier base = level;
                effective = () -> Math.min(base.getAsInt(), CP_PRIORITY_CAP);
                long n = cpBoosted.incrementAndGet();
                if (n == 1) {
                    log("CP 独占生成: 开始对 CP 请求区块提优先级 (cap=" + CP_PRIORITY_CAP + ")");
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

    /** CP 请求集合 / CP 持票集合里的 chunk 才提优先级. */
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
            log("CP 独占生成统计: CP 优先=" + cpBoosted.get() + ", 原版正常=" + cpNormal.get());
        }
    }

    private static void log(String msg) {
        try {
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").info("[ChunkPilot] " + msg);
        } catch (Throwable t) { /* ignore */ }
    }
}
