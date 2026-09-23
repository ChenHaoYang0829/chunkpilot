package com.chunkpilot.neoforge.mixin;

import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 重写 NeoForge 的 CheckExtensibleEnums.start()
 *
 * 默认逻辑：OTHER 类型客户端 + 有非 optional clientbound extended enums → disconnect
 *
 * 问题：bench bot 是 vanilla-like 客户端，NeoForge 的 extensible enum 检查会断开它。
 *
 * 修复：直接 finishCurrentTask，跳过 extensible enum 检查。
 */
@Mixin(targets = "net.neoforged.neoforge.network.configuration.CheckExtensibleEnums", remap = false)
public class CheckExtensibleEnumsMixin {
    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot/CheckExtensibleEnumsMixin");

    private static Class<?> cls = null;
    private static Field listenerField = null;
    private static Field typeField = null;

    static {
        try {
            cls = Class.forName("net.neoforged.neoforge.network.configuration.CheckExtensibleEnums");
            listenerField = cls.getDeclaredField("listener");
            listenerField.setAccessible(true);
            typeField = cls.getDeclaredField("TYPE");
            typeField.setAccessible(true);
        } catch (Exception e) {
            LOG.error("[ChunkPilot] Failed to setup CheckExtensibleEnums reflection", e);
        }
    }

    @Overwrite
    public void start(Consumer<Packet<?>> consumer) {
        try {
            if (cls == null || listenerField == null || typeField == null) {
                LOG.error("[ChunkPilot] CheckExtensibleEnumsMixin not ready (reflection setup failed)");
                return;
            }
            Object listener = listenerField.get(this);
            Object type = typeField.get(null);
            // port/1.21.7: finishCurrentTask 在 1.21.7 是 **private**
            //   (javap 实证 ServerConfigurationPacketListenerImpl: `private void
            //    finishCurrentTask(ConfigurationTask$Type)`), 而旧代码用 getMethod(...)
            //   只能找 public 方法 ⇒ 1.21.7 上必然 NoSuchMethodException → 配置阶段任务永远
            //   完不成 → 客户端卡在 configuration 阶段 (连不进去).
            //   改成 declared-method + setAccessible, 并在类层次里向上找.
            Method m = finishCurrentTask(listener.getClass());
            if (m == null) {
                LOG.error("[ChunkPilot] finishCurrentTask not found on {}", listener.getClass());
                return;
            }
            m.setAccessible(true);
            m.invoke(listener, type);
        } catch (Exception e) {
            LOG.error("[ChunkPilot] CheckExtensibleEnumsMixin.start failed", e);
        }
    }

    /** 在 listener 的类层次里找 finishCurrentTask(ConfigurationTask$Type) (含 private). */
    private static Method finishCurrentTask(Class<?> c) {
        try {
            Class<?> taskType = Class.forName("net.minecraft.server.network.ConfigurationTask$Type");
            for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
                try {
                    return cur.getDeclaredMethod("finishCurrentTask", taskType);
                } catch (NoSuchMethodException ignored) {
                    // 继续向上找
                }
            }
        } catch (Throwable t) {
            LOG.error("[ChunkPilot] finishCurrentTask lookup failed", t);
        }
        return null;
    }
}
