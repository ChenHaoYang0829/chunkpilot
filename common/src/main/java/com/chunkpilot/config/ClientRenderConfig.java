package com.chunkpilot.config;

/**
 * v0.8.0 客户端自适应渲染配置 — 统一公式骨架 + 发送器参数集
 *
 * 2026-08-10 定稿 (deepseek-v4-pro 评审 + 用户拍板):
 *   - 与服务端 [chunk_send] 对称, 共用统一公式
 *   - v_ref 硬编码 30.0, C1 硬编码 0.1
 *   - 删除 v0.7.0 的 v_ref / v_boost_beta 可调参数
 *
 * v0.5.0 变更 (历史):
 *   - 删除 n, direction_boost, direction_penalty, lateral_penalty (由新公式 cos(α) 统一处理)
 *   - 新增 c1 (角度保底), k (距离衰减速率)
 */
public class ClientRenderConfig {
    /** 风险开关: false = 完全使用 vanilla/Sodium 默认渲染逻辑 */
    public boolean enabled = false;

    /** 目标 FPS (0 = 使用显示器刷新率) */
    public int target_fps = 60;

    /** 最低速度阈值 (chunks/s), 低于此速度不干预 */
    public double v_min = 0.7;

    /** 预测时长 (秒), 客户端简化版: 只用当前速度方向, 不做完整路径预测 */
    public double lookAheadSeconds = 3.0;

    // ===== v0.8.0 发送器参数集 (与服务端 [chunk_send] 对称) =====

    /** 方向锐化指数 γ (默认 4.0, 窄锥) */
    public double direction_gamma = 4.0;

    /** 距离衰减系数 k (默认 0.15) */
    public double k = 0.15;

    /** 速度敏感系数 β (无量纲, 默认 1.0) */
    public double v_boost_beta = 1.0;

    // ===== 帧时间监控 =====

    /** 滑动窗口大小 (帧) */
    public int frame_window = 60;

    /** 帧时间波动容忍度 (ms) */
    public double stable_threshold_ms = 2.0;

    /** 帧超此倍数视为过载 (1.1 = 超预算 10% 开始降) */
    public double overload_factor = 1.1;

    /** 帧低此倍数可加预算 (0.7 = 低于预算 30% 可加) */
    public double underload_factor = 0.7;

    // ===== Sodium 兼容 =====

    /** 检测到 Sodium 时走 Mixin 兼容路径 */
    public boolean sodium_compatible = true;
}
