package com.chunkpilot.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.11.10: 非阻塞开关族的解析回归测试.
 *
 * 为什么需要它: ConfigLoader 是**手写解析器** + 独立的 chunkpilot.toml 模板, 两边不一致是这个仓库的历史高发区
 *   (audit 记录里就有"配置模板与解析器一致性清理")。本次新增
 *   `[protection] nonBlockingUnloadCheck` (NeoForge 非阻塞 isExistingChunkFull 的开关),
 *   这个测试保证 ① 默认值是 true, ② 文件里写 false 真的能改到它, ③ 兄弟开关没被顺手改坏。
 */
class ConfigLoaderNonBlockingTest {

    private static Path write(String content) throws IOException {
        Path p = Files.createTempFile("cp-config-test", ".toml");
        p.toFile().deleteOnExit();
        Files.writeString(p, content);
        return p;
    }

    @Test
    void defaultsAreNonBlockingCollisionAndUnloadCheckOn() {
        ChunkPilotConfig cfg = new ChunkPilotConfig();
        assertTrue(cfg.nonBlockingCollision, "nonBlockingCollision 默认应为 true (fabric 侧一直在用)");
        assertTrue(cfg.nonBlockingUnloadCheck, "nonBlockingUnloadCheck 默认应为 true (NeoForge 防崩)");
        assertFalse(cfg.nonBlockingReads, "nonBlockingReads 默认应为 false (与 fabric 口径一致)");
        assertFalse(cfg.nonBlockingGetChunk, "nonBlockingGetChunk 默认应为 false (最激进项)");
    }

    @Test
    void protectionSectionParsesUnloadCheckBothWays() throws IOException {
        Path off = write("[protection]\nnonBlockingUnloadCheck = false\n");
        ChunkPilotConfig c1 = new ChunkPilotConfig();
        ConfigLoader.loadFromToml(c1, off);
        assertFalse(c1.nonBlockingUnloadCheck, "文件里写 false 必须能关掉它");

        Path on = write("[protection]\nnonBlockingUnloadCheck = true\nnonBlockingCollision = false\n");
        ChunkPilotConfig c2 = new ChunkPilotConfig();
        ConfigLoader.loadFromToml(c2, on);
        assertTrue(c2.nonBlockingUnloadCheck, "文件里写 true 必须能打开它");
        assertFalse(c2.nonBlockingCollision, "同一个 switch 里的兄弟开关不能被带坏");
    }
}
