package com.chunkpilot.config;

/**
 * v0.8.0 区块发送顺序优化配置 — 统一公式骨架 + 发送器参数集
 *
 * 2026-08-10 定稿 (deepseek-v4-pro 评审 + 用户拍板):
 *   - 统一公式: w = (cos α + C1)^γ / (1 + k·ρ) × (1 + β·v/v_ref)
 *   - v_ref 硬编码 30.0 (典型鞘翅速度 m/s), 不暴露
 *   - C1 硬编码 0.1 (后方底线权重), 不暴露
 *   - 发送器参数集: γ=4.0 (窄锥), k=0.15 (强衰减), β=1.0 (速度敏感)
 *   - 删除 v0.7.0 的 v_ref / v_boost_beta 可调参数 (v_ref 变常量)
 *
 * v0.5.0 变更 (历史):
 *   - 删除 n, directionBoost, directionPenalty, lateralPenalty (由新公式 cos(α) 统一处理)
 *   - 核心改动: 只改排序, 不注入超视距 chunk (修复 v0.4.0 链路注入 bug)
 */
public class ChunkSendConfig {
    /** 风险开关: false = 完全使用 vanilla 距离排序 */
    public boolean enabled = false;

    /** 最低速度阈值 (chunks/s), 低于此速度不干预, 回退 vanilla 排序 */
    public double v_min = 0.7;

    /** 预测时长 (秒), 与 GenerationConfig.lookAheadSeconds 保持一致 */
    public double lookAheadSeconds = 3.0;

    // ===== v0.8.0 发送器参数集 =====

    /** 方向锐化指数 γ (默认 4.0, 窄锥; =1.0 无锐化) */

    /** 距离衰减系数 k (默认 0.6, 近前方优先; v0.10.6 由 0.15 上调, 前方集中度 0.410->0.674, mspt 更优) */
    public double k = 0.6;

    /** 速度敏感系数 β (无量纲, 默认 1.0; =0 无速度加成) */
}
