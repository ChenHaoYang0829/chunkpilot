package com.chunkpilot.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.0 ChunkWeightCalculatorTest — 统一公式正确性
 *
 * 挡: 公式方向错误 (发送器必须前方优先)
 * 挡: C1/v_ref 硬编码语义错位
 * 挡: 边界 (v<0, γ<1, D<=0) 不 NaN
 *
 * 统一公式: w = (cos α + C1)^γ / (1 + k·ρ) × (1 + β·v/v_ref)
 * v_ref = 30.0 硬编码
 */
class ChunkWeightCalculatorTest {

    // ===== T1: 前方优先 =====

    @Test
    void 前方权重高于侧方() {
        // 发送器参数: γ=4.0, k=0.15, C1=0.1, β=1.0, v=30 m/s
        // 前方 α=0°, d=5, D=20 → (1.1)^4/(1+0.15*0.25) × (1+1.0) = 1.4641/1.0375 × 2 = 2.822
        // 侧方 α=90°, d=5 → (0.1)^4/(1.0375) × 2 = 0.0001/1.0375 × 2 = 0.00019
        double wFront = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 30.0, 20, 0.1, 0.15, 4.0, 1.0);
        double wSide = ChunkWeightCalculator.computeWeight(
            Math.toRadians(90), 5, 30.0, 20, 0.1, 0.15, 4.0, 1.0);

        assertTrue(wFront > wSide,
            "前方优先级必须高于侧方 (wFront=" + wFront + " vs wSide=" + wSide + ")");
    }

    @Test
    void 前方权重高于后方() {
        double wFront = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 30.0, 20, 0.1, 0.15, 4.0, 1.0);
        double wBack = ChunkWeightCalculator.computeWeight(
            Math.toRadians(180), 5, 30.0, 20, 0.1, 0.15, 4.0, 1.0);

        assertTrue(wFront > wBack,
            "前方优先级必须高于后方 (wFront=" + wFront + " vs wBack=" + wBack + ")");
    }

    // ===== T2: 距离衰减 =====

    @Test
    void 距离越远权重越低() {
        double wNear = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 2, 30.0, 20, 0.1, 0.15, 4.0, 1.0);
        double wFar = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 15, 30.0, 20, 0.1, 0.15, 4.0, 1.0);

        assertTrue(wNear > wFar, "距离越远权重越低 (衰减生效)");
    }

    // ===== T3: 速度加成 =====

    @Test
    void 高速时前方权重提升() {
        // v=30 (v_ref) → vBoost = 1+β = 2.0
        // v=60 → vBoost = 1+β*2 = 3.0
        double w30 = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 30.0, 20, 0.1, 0.15, 4.0, 1.0);
        double w60 = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 60.0, 20, 0.1, 0.15, 4.0, 1.0);

        assertTrue(w60 > w30, "高速时前方权重应提升 (w60=" + w60 + " vs w30=" + w30 + ")");
    }

    @Test
    void 静止时无速度加成() {
        // v=0 → vBoost = 1.0, 公式退化为 (cos α + C1)^γ / (1 + k·ρ)
        double w0 = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 0.0, 20, 0.1, 0.15, 4.0, 1.0);
        double expected = Math.pow(1.1, 4.0) / (1.0 + 0.15 * 0.25);
        assertEquals(expected, w0, 1e-9, "v=0 时 vBoost=1.0");
    }

    // ===== T4: 边界防御 =====

    @Test
    void v为负时作为零处理() {
        double wNeg = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, -1.0, 20, 0.1, 0.15, 4.0, 1.0);
        double wZero = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 0.0, 20, 0.1, 0.15, 4.0, 1.0);
        assertFalse(Double.isNaN(wNeg), "v=-1 不能 NaN");
        assertEquals(wZero, wNeg, 1e-9, "v<0 等同 v=0");
    }

    @Test
    void gamma小于1时强制等于1() {
        double wSafe = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 30.0, 20, 0.1, 0.15, 0.5, 1.0);
        double wGamma1 = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 30.0, 20, 0.1, 0.15, 1.0, 1.0);
        assertEquals(wGamma1, wSafe, 1e-9, "γ<1 强制提升到 γ=1");
    }

    @Test
    void D小于等于0时权重为0() {
        double w = ChunkWeightCalculator.computeWeight(
            Math.toRadians(0), 5, 30.0, 0.0, 0.1, 0.15, 4.0, 1.0);
        assertEquals(0.0, w, "D<=0 必须权重 0");
    }

    @Test
    void C1为零_α180度_不NaN() {
        // C1=0, α=180° → cos α + C1 = -1+0 = -1 ≤ 0 → 防御 1e-9
        double w = ChunkWeightCalculator.computeWeight(
            Math.toRadians(180), 5, 30.0, 20, 0.0, 0.15, 4.0, 1.0);
        assertFalse(Double.isNaN(w), "C1=0, α=180° 边界不能 NaN");
        assertTrue(w > 0, "C1=0, α=180° 应有非零值 (angleFactor 用 1e-9 代替)");
    }

    // ===== T5: shouldActivate =====

    @Test
    void 静止_不激活() {
        assertFalse(ChunkWeightCalculator.shouldActivate(0.0, 0.7));
        assertFalse(ChunkWeightCalculator.shouldActivate(0.5, 0.7));
    }

    @Test
    void 极高速_不激活() {
        // 超过 200 chunks/s (tp 瞬移) 不激活
        assertFalse(ChunkWeightCalculator.shouldActivate(200.0, 0.7));
    }

    @Test
    void 正常速度_激活() {
        // v=3.0 blocks/tick = 3.75 chunks/s ≥ 0.7
        assertTrue(ChunkWeightCalculator.shouldActivate(3.0, 0.7));
    }

    // ===== T6: computeAngleRad =====

    @Test
    void 夹角_正前方为0() {
        // 玩家 (0,0) 朝 +X, chunk 中心 (5.5, 0.0) → 夹角 0
        double angle = ChunkWeightCalculator.computeAngleRad(
            5.5, 0.0, 0, 0, 1, 0);
        assertEquals(0.0, angle, 1e-6, "正前方夹角 = 0");
    }

    @Test
    void 夹角_正后方为π() {
        double angle = ChunkWeightCalculator.computeAngleRad(
            -5.5, 0.0, 0, 0, 1, 0);
        assertEquals(Math.PI, angle, 1e-6, "正后方夹角 = π");
    }

    @Test
    void 夹角_侧方为π_2() {
        double angle = ChunkWeightCalculator.computeAngleRad(
            0.0, 5.5, 0, 0, 1, 0);
        assertEquals(Math.PI / 2, angle, 1e-6, "侧方夹角 = π/2");
    }

    // ===== T7: 投影版权重公式 (v0.9.0) =====
    // w = [v·(c-p)/|c-p|] / (1+k·ρ), 负权重取倒数

    @Test
    void 投影_正前方权重为正() {
        // 玩家 (0,0) 朝 +X, v=30 m/s, chunk 中心 (5.5,0) 正前方
        // 投影 = 30*(1*5.5+0*0)/5.5 = 30; ρ=5.5/20=0.275; w=30/(1+0.15*0.275)=28.8
        double w = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 5.5, 0, 0, 0, 20, 0.15);
        assertTrue(w > 0, "正前方投影权重应为正 (w=" + w + ")");
        assertEquals(30.0 / (1 + 0.15 * 0.275), w, 1e-6);
    }

    @Test
    void 投影_正后方权重为负取倒数() {
        // 玩家 (0,0) 朝 +X, chunk 中心 (-5.5,0) 正后方
        // 投影 = 30*(1*(-5.5)+0*0)/5.5 = -30; w=-30/(1+0.15*0.275)=-28.8 → 取倒数 = -0.0347
        double w = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, -5.5, 0, 0, 0, 20, 0.15);
        assertTrue(w < 0, "正后方投影权重应为负(取倒数后仍负) (w=" + w + ")");
        double raw = -30.0 / (1 + 0.15 * 0.275);
        assertEquals(1.0 / raw, w, 1e-6, "负权重应取倒数");
    }

    @Test
    void 投影_侧方权重接近零() {
        // 玩家 (0,0) 朝 +X, chunk 中心 (0,5.5) 正侧方
        // 投影 = 30*(1*0+0*5.5)/5.5 = 0 → w=0
        double w = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 0, 5.5, 0, 0, 20, 0.15);
        assertEquals(0.0, w, 1e-9, "正侧方投影权重 = 0");
    }

    @Test
    void 投影_前方权重高于后方() {
        double wFront = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 5.5, 0, 0, 0, 20, 0.15);
        double wBack = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, -5.5, 0, 0, 0, 20, 0.15);
        assertTrue(wFront > wBack, "前方权重必须高于后方 (wFront=" + wFront + " vs wBack=" + wBack + ")");
    }

    @Test
    void 投影_速度越快权重越高() {
        // 正前方, v=30 vs v=60 → 投影 30 vs 60, 权重 28.8 vs 57.6
        double w30 = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 5.5, 0, 0, 0, 20, 0.15);
        double w60 = ChunkWeightCalculator.computeWeightProjection(
            60.0, 1, 0, 5.5, 0, 0, 0, 20, 0.15);
        assertTrue(w60 > w30, "速度越快权重越高 (w60=" + w60 + " vs w30=" + w30 + ")");
    }

    @Test
    void 投影_距离越远权重越低() {
        double wNear = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 2.5, 0, 0, 0, 20, 0.15);
        double wFar = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 15.5, 0, 0, 0, 20, 0.15);
        assertTrue(wNear > wFar, "距离越远权重越低 (衰减生效)");
    }

    @Test
    void 投影_D小于等于0时权重为0() {
        double w = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 5.5, 0, 0, 0, 0, 0.15);
        assertEquals(0.0, w, "D<=0 必须权重 0");
    }

    @Test
    void 投影_玩家所在chunk权重为0() {
        // chunk 中心 = 玩家位置, d≈0 → 投影 0, 权重 0
        double w = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, 0.5, 0.5, 0.5, 0.5, 20, 0.15);
        assertEquals(0.0, w, 1e-9, "玩家所在 chunk 权重 = 0");
    }

    @Test
    void 投影_负权重取倒数后不NaN() {
        // 正后方, 投影为负, 取倒数后应为有限负值
        double w = ChunkWeightCalculator.computeWeightProjection(
            30.0, 1, 0, -5.5, 0, 0, 0, 20, 0.15);
        assertFalse(Double.isNaN(w), "负权重取倒数不能 NaN");
        assertFalse(Double.isInfinite(w), "负权重取倒数不能 Infinite");
    }
}
