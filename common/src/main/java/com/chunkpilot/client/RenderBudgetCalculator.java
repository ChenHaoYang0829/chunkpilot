package com.chunkpilot.client;

import com.chunkpilot.config.ClientRenderConfig;

/**
 * 渲染预算计算器 (DESIGN.md §12.2)
 *
 * 根据目标 FPS 和实际帧时间, 动态计算每帧可用于 chunk meshing 的预算.
 *
 * 对称于服务端 AdaptiveController:
 *   服务端: MSPT > target → 减少 maxChunksPerTick
 *   客户端: FPS 低于 target → 减少 maxChunksPerFrame
 *
 * 滞回带 (hysteresis) 避免在目标附近抖动.
 */
public class RenderBudgetCalculator {

    /** 每帧 meshing 上限 (绝对值) */
    private static final int MAX_CHUNKS_PER_FRAME = 8;
    /** 每帧 meshing 下限 */
    private static final int MIN_CHUNKS_PER_FRAME = 1;
    /** 保守模式初始预算 (维度刚加载时) */
    private static final int CONSERVATIVE_INITIAL = 2;

    private int currentBudget;
    private final ClientRenderConfig config;

    public RenderBudgetCalculator(ClientRenderConfig config) {
        this.config = config;
        this.currentBudget = CONSERVATIVE_INITIAL;
    }

    /**
     * 根据当前帧时间调整每帧 meshing 预算.
     *
     * @param sampler 帧时间采样器
     * @return 本帧可提交的 chunk meshing 数量上限
     */
    public int updateBudget(FrameTimeSampler sampler) {
        if (!sampler.hasEnoughData()) {
            // 样本不足, 用保守值
            return currentBudget;
        }

        double targetFrameTimeMs = 1000.0 / Math.max(1, config.target_fps);
        double actualFrameTimeMs = sampler.getEmaFrameTimeMs();
        boolean jittery = sampler.isJittery();

        // 抖动时用更保守的阈值
        double overloadThreshold = targetFrameTimeMs * config.overload_factor;
        double underloadThreshold = targetFrameTimeMs * config.underload_factor;

        if (jittery) {
            // 抖动: 保守降预算
            overloadThreshold = targetFrameTimeMs * 1.0;  // 抖动时只要超目标就降
        }

        if (actualFrameTimeMs > overloadThreshold) {
            // 过载: 降预算
            currentBudget = Math.max(MIN_CHUNKS_PER_FRAME, currentBudget - 1);
        } else if (actualFrameTimeMs < underloadThreshold) {
            // 富余: 加预算
            currentBudget = Math.min(MAX_CHUNKS_PER_FRAME, currentBudget + 1);
        }
        // 在滞回带内: 保持不变

        return currentBudget;
    }

    /** 重置 (维度切换/传送后) */
    public void reset() {
        currentBudget = CONSERVATIVE_INITIAL;
    }
}