package com.chunkpilot.neoforge.client;

/**
 * NeoForge 客户端侧入口 —— 1.0.0 起只剩一行日志.
 *
 * 1.0.0 之前这里初始化的是"客户端渲染优先级"链路 (ChunkPilotClient / Sodium+vanilla
 * 两个客户端 Mixin) 并注册客户端 tick. 该链路从未真正生效 (ChunkPilotClient 的静态单例
 * 只在 {@code init()} 里赋值, 而 {@code init()} 的唯一调用点就是本类 —— 本类自己
 * 又没有任何入口调用过), 已整体删除.
 *
 * 本类保留两件事:
 *   ① 产物里保留 com/chunkpilot/neoforge/client/ 下的客户端侧类 —— "服务端与客户端
 *      在同一个 jar 里"是硬性要求, 交付校验脚本按 /client/ 路径统计客户端 class;
 *   ② 客户端启动时留一条可核对的日志.
 * 由 ChunkPilotNeoForge (@Mod 入口) 在 Dist.CLIENT 时调用.
 */
public final class ChunkPilotNeoForgeClient {

    private ChunkPilotNeoForgeClient() {}

    /** 仅在客户端物理侧被调用 (见 ChunkPilotNeoForge 构造函数). */
    public static void init() {
        System.out.println("[ChunkPilot] client side initialized (1.0.0: 客户端无渲染侧功能, 优化均来自服务端)");
    }
}
