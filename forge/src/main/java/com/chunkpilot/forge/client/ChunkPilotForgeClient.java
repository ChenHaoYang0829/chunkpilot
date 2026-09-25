package com.chunkpilot.forge.client;

/**
 * Forge 1.20.1 客户端侧入口 —— 1.0.0 起只剩一行日志.
 *
 * 1.0.0 之前这里初始化"客户端渲染优先级"链路 (ChunkPilotClient / ChunkRenderScheduler /
 * Sodium + vanilla 两个客户端 Mixin). 该链路**从未真正生效**: ChunkPilotClient 的静态单例
 * 只在 init() 里赋值, 而 init() 的唯一调用点是自己那个零外部引用的类; 两个客户端 Mixin 的
 * 注入方法首行就是 `if (client == null) return;`. 已整体删除 (见 PORTING_REPORT §7.3b 同类清理).
 *
 * 本类保留的理由: 产物里"服务端与客户端在同一个 jar"是硬性要求, 交付校验按 /client/ 路径
 * 统计客户端 class 数. 由 {@link ChunkPilotForgeClientEvents} 在客户端首个 tick 调用一次.
 */
public final class ChunkPilotForgeClient {

    private ChunkPilotForgeClient() {}

    private static boolean announced = false;

    /** 仅在客户端物理侧被调用一次 (见 ChunkPilotForgeClientEvents). */
    public static void init() {
        if (announced) return;
        announced = true;
        System.out.println("[ChunkPilot] client side initialized (1.0.0: 客户端无渲染侧功能, 优化均来自服务端)");
    }
}
