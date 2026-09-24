package com.chunkpilot.config;

import com.chunkpilot.config.ChunkPilotConfig.SpeedTier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 极简 TOML 配置加载器
 * 仅支持 ChunkPilot 用到的语法：
 *  - [section] / [[arrayOfTables]]
 *  - key = value（string / int / double / bool / 数组 / 行内表）
 *  - # 注释
 *  - 多行字符串、单引号、双引号
 *
 * 不实现：heredoc、复杂 unicode escape、inline table 嵌套对象
 * 复杂场景下回退到默认值并日志告警
 */
public class ConfigLoader {

    public static void loadFromToml(ChunkPilotConfig config, Path file) throws IOException {
        if (!Files.exists(file)) {
            System.out.println("[ChunkPilot] Config file not found: " + file + " (using defaults)");
            return;
        }
        List<String> lines = Files.readAllLines(file);
        System.out.println("[ChunkPilot] Loading config from: " + file);

        String currentSection = "";
        String currentArraySection = "";
        SpeedTier currentTier = null;
        boolean userProvidedTiers = false;
        boolean tiersCleared = false; // 遇到真实 [[speedTiers.tier]] 前, 不清默认档位
        // 多行值拼接（用于跨行数组）
        StringBuilder multiLineBuf = null;

        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            String raw = lines.get(lineIdx);

            // 多行模式：拼接直到 ] 出现
            if (multiLineBuf != null) {
                String line = stripInlineComment(raw).trim();
                multiLineBuf.append(line);
                if (line.contains("]")) {
                    String fullValue = multiLineBuf.toString();
                    // 需要从原始上下文取 key：但简单起见，我们把 key 在 multiLineBuf 外记录
                    // 这里重新设计：使用一个专用变量存储 multiLine key
                    // 简单做法：重新收集 — 补一个独立字段
                    multiLineBuf = null;
                    // 此 value 已在 multiLineKey 变量和 multiLineSection 中暂存
                    if (multiLineKey != null) {
                        String cleanValue = fullValue;
                        // extract from [ to ] inclusive
                        int lb = cleanValue.indexOf('[');
                        int rb = cleanValue.indexOf(']', lb);
                        if (lb >= 0 && rb > lb) {
                            cleanValue = cleanValue.substring(lb, rb + 1);
                        }
                        applyKeyValue(config, multiLineSection, multiLineArraySection, multiLineKey, cleanValue);
                        multiLineKey = null;
                    }
                }
                continue;
            }

            String line = stripInlineComment(raw).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("[[") && line.endsWith("]]")) {
                // 进入新 tier 之前先 flush 上一个
                if (currentTier != null && currentTier.name != null) {
                    config.speedTiers.add(currentTier);
                }
                currentArraySection = line.substring(2, line.length() - 2).trim();
                if ("speedTiers.tier".equals(currentArraySection)) {
                    // 首次遇到真实档位表: 替换默认档位 (load() 不再预清, 避免漏配档位时静默失效)
                    if (!tiersCleared) {
                        config.speedTiers.clear();
                        tiersCleared = true;
                    }
                    currentTier = new SpeedTier();
                    userProvidedTiers = true;
                } else {
                    currentTier = null;
                }
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                // 进入新 section 前先 flush pending tier
                if (currentTier != null && currentTier.name != null) {
                    config.speedTiers.add(currentTier);
                    currentTier = null;
                }
                currentSection = line.substring(1, line.length() - 1).trim();
                currentArraySection = "";
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());

            // 多行数组：value 以 [ 开头但未以 ] 结尾
            if (value.startsWith("[") && !value.contains("]")) {
                multiLineBuf = new StringBuilder(value);
                multiLineKey = key;
                multiLineSection = currentSection;
                multiLineArraySection = currentArraySection;
                continue;
            }

            // [[speedTiers.tier]] 内的 key：暂存在 pendingTiers 列表
            if (currentTier != null) {
                switch (key) {
                    case "name" -> currentTier.name = value;
                    case "minSpeed" -> currentTier.minSpeed = parseDoubleSilent(value);
                    case "maxSpeed" -> currentTier.maxSpeed = parseDoubleSilent(value);
                    case "coreRadius" -> currentTier.coreRadius = parseIntSilent(value);
                    case "sectorAngle" -> currentTier.sectorAngle = parseDoubleSilent(value);
                    case "sectorRadius" -> currentTier.sectorRadius = parseIntSilent(value);
                    case "tailRadius" -> currentTier.tailRadius = parseIntSilent(value);
                }
                continue;
            }

            applyKeyValue(config, currentSection, currentArraySection, key, value);
        }

        if (userProvidedTiers) {
            // flush 最后一个 tier
            if (currentTier != null && currentTier.name != null) {
                config.speedTiers.add(currentTier);
            }
            config.speedTiers.removeIf(t -> t.name == null);
        }

        System.out.println("[ChunkPilot] Config loaded: enabled=" + config.enabled
            + ", speedTiers=" + config.speedTiers.size()
            + ", integrationEnabled=" + config.integrationEnabled
            + ", generation.enabled=" + config.generation.enabled);
    }

    // 多行解析状态字段
    private static String multiLineKey = null;
    private static String multiLineSection = "";
    private static String multiLineArraySection = "";

    /**
     * 去掉行内 # 注释，但保留引号字符串内的 #。
     * 简单实现：遇 " 或 ' 后切换 inQuote 状态
     */
    private static String stripInlineComment(String s) {
        StringBuilder out = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                out.append(c);
                if (c == quoteChar) inQuote = false;
            } else {
                if (c == '"' || c == '\'') {
                    inQuote = true;
                    quoteChar = c;
                    out.append(c);
                } else if (c == '#') {
                    break; // 注释开始
                } else {
                    out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static void applyKeyValue(ChunkPilotConfig cfg, String section, String arrSection,
                                       String key, String value) {
        try {
            switch (section) {
                case "" -> {} // 顶层 key（暂不用）
                case "general" -> {
                    switch (key) {
                        case "enabled" -> cfg.enabled = parseBool(value);
                        case "logLevel" -> cfg.logLevel = value;
                        case "language" -> cfg.language = value;
                    }
                }
                case "speed" -> {
                    switch (key) {
                        case "windowTicks" -> cfg.speedWindowTicks = parseInt(value);
                        case "recentTicks" -> cfg.speedRecentTicks = parseInt(value);
                        case "lowSpeedThreshold" -> cfg.lowSpeedThreshold = parseDouble(value);
                        case "lowSpeedRevoke" -> cfg.lowSpeedRevoke = parseDouble(value);
                    }
                }
                case "speedTiers" -> {
                    // 1.0.0: curveMode / customFormula 已删除 (只被解析、零处读取);
                    //   [[speedTiers.tier]] 的档位表解析仍在上面的 currentTier 分支里.
                }
                case "protection" -> {
                    switch (key) {
                        case "maxMspt" -> cfg.maxMspt = parseInt(value);
                        case "disableMspt" -> cfg.disableMspt = parseInt(value);
                        case "nonBlockingCollision" -> cfg.nonBlockingCollision = parseBool(value);
                        case "nonBlockingReads" -> cfg.nonBlockingReads = parseBool(value);
                        case "nonBlockingGetChunk" -> cfg.nonBlockingGetChunk = parseBool(value);
                        case "maxSectorTickets" -> cfg.maxSectorTickets = parseInt(value);
                        case "maxNewTicketsPerTick" -> cfg.maxNewTicketsPerTick = parseInt(value);
                        // v0.11.x: 扇区浅层半径/状态 (同样是"代码读、解析器没接"的键, 2026-09-13 补上)
                        case "sectorShallowRadius" -> cfg.sectorShallowRadius = parseInt(value);
                        case "sectorShallowStatus" -> cfg.sectorShallowStatus = value;
                    }
                }
                case "integration" -> {
                    if ("enabled".equals(key)) cfg.integrationEnabled = parseBool(value);
                    else if (arrSection != null && !arrSection.isEmpty()) {
                        // [integration.mtr] 这种子节
                        applyIntegration(cfg, arrSection, key, value);
                    } else {
                        // [integration] 直接子键（保留）
                    }
                }
                case "generation" -> {
                    // v0.8.0 生成器配置 (deadline 排序)
                    switch (key) {
                        case "enabled" -> cfg.generation.enabled = parseBool(value);
                        case "v_min" -> cfg.generation.v_min = parseDouble(value);
                        case "lookAheadSeconds" -> cfg.generation.lookAheadSeconds = parseDouble(value);
                        case "predictionTTLSeconds" -> cfg.generation.predictionTTLSeconds = parseDouble(value);
                        case "maxChunksPerTick" -> cfg.generation.maxChunksPerTick = parseInt(value);
                        case "targetMspt" -> cfg.generation.targetMspt = parseDouble(value);
                        // v0.8.0 deadline 排序参数
                        case "sectorAngleBaseDeg" -> cfg.generation.sectorAngleBaseDeg = parseDouble(value);
                        case "sectorAngleMaxDeg" -> cfg.generation.sectorAngleMaxDeg = parseDouble(value);
                        case "vMinMs" -> cfg.generation.vMinMs = parseDouble(value);
                        // v0.6.0 浅层预生成 (调度器而非增压器)
                        case "shallowGenerationEnabled" -> cfg.generation.shallowGenerationEnabled = parseBool(value);
                        case "shallowTargetStatus" -> cfg.generation.shallowTargetStatus = value;
                        case "shallowDistanceRadius" -> cfg.generation.shallowDistanceRadius = parseInt(value);
                        // v0.6.0 FULL 待办回补队列
                        case "deferredFullQueueEnabled" -> cfg.generation.deferredFullQueueEnabled = parseBool(value);
                        case "deferredFullQueueMax" -> cfg.generation.deferredFullQueueMax = parseInt(value);
                        case "deferredFullMsptMargin" -> cfg.generation.deferredFullMsptMargin = parseDouble(value);
                        case "deferredFullMaxPerTick" -> cfg.generation.deferredFullMaxPerTick = parseInt(value);
                        case "deferredFullTtlTicks" -> cfg.generation.deferredFullTtlTicks = parseInt(value);
                        // v0.11.x: 独占生成 + 在途请求窗口 (2026-09-13 修复)
                        //   注意: 这几个键此前代码在读、ConfigLoader 却没解析 → toml 里写了也不生效 (静默走 Java 默认值).
                        case "exclusiveGenerationNoC2me" -> cfg.generation.exclusiveGenerationNoC2me = parseBool(value);
                        case "maxOutstandingRequests" -> cfg.generation.maxOutstandingRequests = parseInt(value);
                        case "outstandingTtlTicks" -> cfg.generation.outstandingTtlTicks = parseInt(value);
                        case "strictSkipGenerated" -> cfg.generation.strictSkipGenerated = parseBool(value);
                    }
                }
                case "chunk_send" -> {
                    // v0.8.0 区块发送顺序优化配置 (统一公式 + 发送器参数集)
                    switch (key) {
                        case "enabled" -> cfg.chunkSend.enabled = parseBool(value);
                        case "v_min" -> cfg.chunkSend.v_min = parseDouble(value);
                        case "k" -> cfg.chunkSend.k = parseDouble(value);
                        case "lookAheadSeconds" -> cfg.chunkSend.lookAheadSeconds = parseDouble(value);
                    }
                }
                case "forward_window" -> {
                    // v0.11.6 前瞻窗口 (生成侧锚点票 + 发送侧跟踪窗口前移)
                    switch (key) {
                        case "enabled" -> cfg.forwardWindow.enabled = parseBool(value);
                        case "minSpeed" -> cfg.forwardWindow.minSpeed = parseDouble(value);
                        case "aheadChunks" -> cfg.forwardWindow.aheadChunks = parseInt(value);
                        case "forwardExtra" -> cfg.forwardWindow.forwardExtra = parseInt(value);
                        case "trackingShift" -> cfg.forwardWindow.trackingShift = parseInt(value);
                        case "updateTicks" -> cfg.forwardWindow.updateTicks = parseInt(value);
                        case "refreshTicks" -> cfg.forwardWindow.refreshTicks = parseInt(value);
                        case "turnThresholdDeg" -> cfg.forwardWindow.turnThresholdDeg = parseDouble(value);
                        case "maxSpeed" -> cfg.forwardWindow.maxSpeed = parseDouble(value);
                    }
                }
                case "client_render" -> {
                    // 1.0.0: [client_render] 整节随"客户端渲染优先级"链路一起删除.
                    //   旧配置里的本节键现在落到 default 分支, 静默忽略 (不报错, 不影响其它键).
                }
                default -> {
                    // [integration.xxx]
                    if (section.startsWith("integration.")) {
                        applyIntegration(cfg, section.substring("integration.".length()), key, value);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ChunkPilot] Failed to parse [" + section + "] " + key + " = " + value + ": " + e.getMessage());
        }
    }

    private static void applyIntegration(ChunkPilotConfig cfg, String name, String key, String value) {
        ChunkPilotConfig.Integration target = switch (name) {
            case "create" -> cfg.create;
            case "create_aeronautics" -> cfg.createAeronautics;
            case "mtr" -> cfg.mtr;
            case "immersive_railroading" -> cfg.immersiveRailroading;
            case "immersive_vehicles" -> cfg.immersiveVehicles;
            default -> null;
        };
        if (target == null) return;
        try {
            switch (key) {
                case "enabled" -> target.enabled = parseBool(value);
                case "lookAheadChunks" -> target.lookAheadChunks = parseInt(value);
                case "trackSideRadius" -> target.trackSideRadius = parseInt(value);
                case "minSpeed" -> target.minSpeed = parseDouble(value);
                case "sectorAngle" -> target.sectorAngle = parseDouble(value);
                case "cacheTicks" -> target.cacheTicks = parseInt(value);
            }
        } catch (Exception e) {
            System.err.println("[ChunkPilot] Failed to parse integration." + name + "." + key + ": " + e.getMessage());
        }
    }

    // ===== 解析辅助 =====

    private static String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean parseBool(String s) {
        return Boolean.parseBoolean(s);
    }

    private static int parseInt(String s) {
        return Integer.parseInt(s);
    }

    private static int parseIntSilent(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static double parseDouble(String s) {
        return Double.parseDouble(s);
    }

    private static double parseDoubleSilent(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }
}
