package com.chunkpilot.porting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 跨版本回归闸门: **平台代码里不得再出现 `addTicketWithRadius(...)`**。
 *
 * ============================ 为什么需要这条机械闸门 ============================
 * `ServerChunkCache.addTicketWithRadius(TicketType, ChunkPos, radius)` 的第三个参数是 **radius**,
 * 不是票等级; 它内部会换算成 `new Ticket(type, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius)`。
 *
 * javap 实证 (26.3 内层 server jar → `TicketStorage.addTicketWithRadius`):
 *     getstatic FullChunkStatus.FULL
 *     invokestatic ChunkLevel.byStatus(FullChunkStatus)   // = 33
 *     iload_3                                             // radius
 *     isub                                                // 33 - radius
 *     new Ticket(type, 33 - radius)
 * 而 `ChunkLevel.byStatus(FullChunkStatus)` 的值 (javap tableswitch 实证):
 *     FULL -> 33 / BLOCK_TICKING -> 32 / ENTITY_TICKING -> 31 / INACCESSIBLE -> MAX_LEVEL
 *
 * ⇒ 写成 `addTicketWithRadius(GEN_TICKET, pos, 31)` 的人以为"31 = FULL_TICKING",
 *   实际得到票等级 **33 - 31 = 2**, 比 ENTITY_TICKING(31) 还深 ⇒ **每请求一个区块就把
 *   周围半径 31 区块 (63×63 ≈ 3969 块) 拉进深加载**。症状: 探针 `own_ticket_level` 落到 2~5,
 *   覆盖崩到 0.05~0.37、被服务端反复拉回、前方前沿几乎为 0, 而 `lead_loaded_srv` 仍是 1.0
 *   ("票生效了, 但生效成了灾难")。port/1.21.9/1.21.10/1.21.11 与 port/26.3 都踩过同一个坑。
 *
 * 正确写法只有一个: `addTicket(new Ticket(type, level), pos)` —— 显式给**等级**。
 * 撤票可以继续用 `removeTicketWithRadius(type, pos, 33 - level)`, 它反推出的等级恰好等于原 level,
 * 是对称的 (见 NeoForgePlatform.removeChunkTicket)。
 *
 * 本测试**只做机械扫描**: 去掉注释后, `fabric`/`neoforge` 两个平台的 `.java` 里都不允许出现
 * `addTicketWithRadius(`。找不到仓库根 (例如在别的构建里跑) 就 skip, 绝不误报。
 */
class TicketRadiusGuardTest {

    /** 去掉行注释与块注释, 避免把说明文字当成代码扫出来。 */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\n", -1)) {
            int i = line.indexOf("//");
            sb.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        return sb.toString();
    }

    /** 从 user.dir 往上找含 settings.gradle 的目录 = 仓库根。 */
    private static Path findRepoRoot() {
        Path p = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))) return p;
            p = p.getParent();
        }
        return null;
    }

    @Test
    @DisplayName("平台代码里不得出现 addTicketWithRadius —— 它的第三参是 radius(会被换算成 33-radius), 不是票等级")
    void noAddTicketWithRadiusInPlatforms() throws IOException {
        Path root = findRepoRoot();
        assumeTrue(root != null, "找不到仓库根 (无 settings.gradle) ⇒ 本闸门跳过");
        System.out.println("[TicketRadiusGuard] 仓库根 = " + root);

        List<Path> files = new ArrayList<>();
        for (String mod : new String[]{"fabric", "neoforge"}) {
            Path dir = root.resolve(mod + "/src/main/java/com/chunkpilot/" + mod + "/platform");
            assumeTrue(Files.isDirectory(dir), "平台目录不存在 ⇒ 跳过: " + dir);
            try (Stream<Path> st = Files.walk(dir)) {
                st.filter(f -> f.toString().endsWith(".java")).forEach(files::add);
            }
        }
        assertTrue(!files.isEmpty(), "没有扫到任何平台源文件");

        List<String> offenders = new ArrayList<>();
        for (Path f : files) {
            String code = stripComments(Files.readString(f, StandardCharsets.UTF_8));
            int idx = 0;
            while ((idx = code.indexOf("addTicketWithRadius(", idx)) >= 0) {
                // "removeTicketWithRadius(" 里也含子串, 要排除
                boolean isRemove = idx >= 6 && code.startsWith("removeTicketWithRadius(", idx - 6);
                if (!isRemove) {
                    int line = code.substring(0, idx).split("\n", -1).length;
                    offenders.add(f.getFileName() + ":" + line);
                }
                idx += 20;
            }
        }
        assertTrue(offenders.isEmpty(),
            "平台代码里出现了 addTicketWithRadius (第三参是 radius, 会被换算成 33-radius ⇒ 票等级变 2): "
                + offenders + "。请改用 addTicket(new Ticket(type, level), pos)。");
        System.out.println("[TicketRadiusGuard] ✅ 已扫描 " + files.size() + " 个平台源文件, 0 处 addTicketWithRadius");
    }
}
