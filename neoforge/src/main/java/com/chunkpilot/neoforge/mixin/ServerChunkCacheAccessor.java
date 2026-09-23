package com.chunkpilot.neoforge.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v0.11.10: 取 {@code ServerChunkCache} 的私有入口 {@code getVisibleChunkIfPresent(long)}.
 *
 * 为什么不用反射: fabric 侧就是被这条坑过(main 的 3ccbf0c) —— 可读名反射在
 *   intermediary 运行期必然失败, probe 的 ticketLevel 恒为 -1。这里直接用
 *   {@code @Invoker} 由 mixin 生成访问器, 运行期名字由 Mixin 自己解析, 与命名空间无关。
 *   neoforge 运行期是 mojmap, 可读名反射本来也能成功, 但 accessor 更稳也更快(无反射开销)。
 *
 * 实测(1.21.3 neoforge 运行时 jar): {@code ServerChunkCache.getVisibleChunkIfPresent(long)} 是 **private**;
 *   注意 NeoForge 只把 {@code ChunkMap.getVisibleChunkIfPresent(long)} 从 protected 放成了 public,
 *   ServerChunkCache 上这个入口没变 ⇒ 仍然需要 accessor。
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder chunkpilot$getVisibleChunkIfPresent(long chunkPosLong);
}
