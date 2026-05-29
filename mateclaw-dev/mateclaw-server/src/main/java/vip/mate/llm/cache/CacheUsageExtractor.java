package vip.mate.llm.cache;

import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 从 Spring AI {@link Usage} 中提取 Anthropic 的 prompt cache token 计数。
 *
 * <p>spring-ai 的高层 {@code Usage} 接口只暴露 {@code promptTokens} / {@code completionTokens}，
 * 没有 cache 维度；但 {@link Usage#getNativeUsage()} 会返回 provider 的原生 usage 对象。
 * 对 Anthropic 而言是 {@code AnthropicApi.Usage} record，含 {@code cacheCreationInputTokens}
 * 与 {@code cacheReadInputTokens}。</p>
 *
 * <p>采用反射调用以避免：
 * <ul>
 *   <li>对 spring-ai 内部 record 形态的硬编码（未来字段重命名风险小）</li>
 *   <li>对其它 provider（OpenAI 兼容、DashScope）的 ClassCastException</li>
 * </ul>
 * 反射结果按类缓存，热路径性能可接受。</p>
 *
 * <p>不可变、线程安全。</p>
 */
public final class CacheUsageExtractor {

    /** {@code (cacheReadTokens, cacheWriteTokens)}；任一字段不可得时为 0。 */
    public record CacheTokens(int cacheReadTokens, int cacheWriteTokens) {
        public static final CacheTokens EMPTY = new CacheTokens(0, 0);

        public boolean isEmpty() { return cacheReadTokens == 0 && cacheWriteTokens == 0; }
    }

    /** 缓存 (Class, methodName) → reflected Method（命中失败时为标记 NULL_METHOD）。 */
    private static final ConcurrentMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Method NULL_METHOD;
    static {
        try {
            NULL_METHOD = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CacheUsageExtractor() {}

    /** 从 spring-ai Usage 中尽力抽取 cache token；不支持的 provider 返回 EMPTY。 */
    public static CacheTokens extract(Usage usage) {
        if (usage == null) return CacheTokens.EMPTY;
        Object native_ = usage.getNativeUsage();
        if (native_ == null) return CacheTokens.EMPTY;

        int read  = invokeIntAccessor(native_, "cacheReadInputTokens");
        int write = invokeIntAccessor(native_, "cacheCreationInputTokens");
        return (read == 0 && write == 0) ? CacheTokens.EMPTY : new CacheTokens(read, write);
    }

    private static int invokeIntAccessor(Object target, String accessor) {
        Class<?> cls = target.getClass();
        String key = cls.getName() + "#" + accessor;
        Method m = METHOD_CACHE.computeIfAbsent(key, k -> resolveAccessor(cls, accessor));
        if (m == NULL_METHOD) return 0;
        try {
            Object v = m.invoke(target);
            if (v instanceof Number n) return n.intValue();
            return 0;
        } catch (ReflectiveOperationException ignored) {
            return 0;
        }
    }

    private static Method resolveAccessor(Class<?> cls, String accessor) {
        // 1) record-style getter: cacheReadInputTokens()
        try {
            Method m = cls.getMethod(accessor);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            // 2) bean-style getter: getCacheReadInputTokens()
            String beanName = "get" + Character.toUpperCase(accessor.charAt(0)) + accessor.substring(1);
            try {
                Method m = cls.getMethod(beanName);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored2) {
                return NULL_METHOD;
            }
        }
    }
}
