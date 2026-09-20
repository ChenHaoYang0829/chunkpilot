package com.chunkpilot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0.5 AdaptiveLoadControllerTest — 共享负载自适应控制器 (v0.10 重构).
 *
 * 挡 (用户 2026-08-27 评审 + 落地):
 *   1. 票侧预算在高负载档位必须真的降, 不能被保底下限顶回去 (旧版自废武功).
 *   2. 生成侧按 loadFactor 连续映射, 高负载可降到 0 (紧急停手).
 *   3. MSPT 不对称 EMA: 上升快(响应生成爆炸), 下降慢(避免抖动).
 */
class AdaptiveLoadControllerTest {

    @Test
    void 紧急mspt_生成与预算都归零() {
        AdaptiveLoadController c = new AdaptiveLoadController();
        c.sample(100.0);
        assertEquals(0, c.suggestGenerationCount(4));
        assertEquals(0, c.suggestBudget(100));
    }

    @Test
    void 健康mspt_生成满额预算满额() {
        AdaptiveLoadController c = new AdaptiveLoadController();
        c.sample(10.0);
        assertEquals(4, c.suggestGenerationCount(4));
        assertEquals(100, c.suggestBudget(100));
    }

    @Test
    void 高负载档位_预算真正下降不被保底顶回() {
        AdaptiveLoadController c = new AdaptiveLoadController();
        // 平滑到 ~45ms (高负载): 预算应降到 20% 而不是被保底抬回
        for (int i = 0; i < 50; i++) c.sample(45.0);
        int budget = c.suggestBudget(100);
        assertEquals(20, budget,
            "mspt≈45 (高负载) 预算应=20%×100=20, 不能被保底顶回 (实际=" + budget + ")");
    }

    @Test
    void 中负载_预算40不保底() {
        AdaptiveLoadController c = new AdaptiveLoadController();
        for (int i = 0; i < 50; i++) c.sample(42.0);
        assertEquals(40, c.suggestBudget(100));
    }

    @Test
    void 低负载_保底生效防止饿死() {
        AdaptiveLoadController c = new AdaptiveLoadController();
        for (int i = 0; i < 50; i++) c.sample(36.0);
        // mspt 36 (低): 60%×100=60, 保底 20 不干扰
        assertEquals(60, c.suggestBudget(100));
    }

    @Test
    void 不对称EMA_上升快下降慢() {
        AdaptiveLoadController c = new AdaptiveLoadController();
        c.sample(10.0); // 初始化到 10
        double up1 = c.sample(45.0);   // 一次大幅升高
        double up2 = c.sample(45.0);
        double rise = up2 - up1;

        // 重置为高值, 测试下降慢
        AdaptiveLoadController d = new AdaptiveLoadController();
        d.sample(45.0);
        double down1 = d.sample(10.0); // 一次大幅降低
        double down2 = d.sample(10.0);
        double fall = Math.abs(down2 - down1);

        assertTrue(rise > fall,
            "上升应快于下降 (rise=" + rise + " fall=" + fall + ")");
    }

    @Test
    void 生成侧连续映射_负载越低生成越多() {
        // 不经过 EMA, 直接用静态方法验证连续映射
        assertEquals(4, AdaptiveLoadController.suggestGenerationCount(4, 30.0));
        assertEquals(3, AdaptiveLoadController.suggestGenerationCount(4, 35.0));
        assertEquals(1, AdaptiveLoadController.suggestGenerationCount(4, 40.0));
        assertEquals(0, AdaptiveLoadController.suggestGenerationCount(4, 45.0));
        assertEquals(0, AdaptiveLoadController.suggestGenerationCount(4, 60.0));
    }
}
