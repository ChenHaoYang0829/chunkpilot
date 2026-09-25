package com.chunkpilot.forge.client;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 客户端 tick 事件监听器 —— **只在 Dist.CLIENT 下被实例化** (见 ChunkPilotForge 构造器里的
 * `FMLEnvironment.dist.isClient()` 判断).
 *
 * 1.0.0 起客户端侧只剩"启动日志"这一件事 (渲染优先级链路从未生效, 已删); 这里在客户端首个
 * tick 调一次 {@link ChunkPilotForgeClient#init()}, 之后不再做任何事 —— 保留本类是为了让
 * 客户端入口在产物里**可达**, 而不是留一个零引用的类.
 *
 * 本类不含任何客户端专用 MC 类型: TickEvent.ClientTickEvent 属 Forge 通用事件类,
 * 在专用服务端上引用它也不会加载客户端类.
 */
public class ChunkPilotForgeClientEvents {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            ChunkPilotForgeClient.init();
        } catch (Throwable t) {
            // 客户端侧异常绝不上抛
        }
    }
}
