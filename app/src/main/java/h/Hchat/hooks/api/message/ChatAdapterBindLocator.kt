package h.Hchat.hooks.api.message

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 聊天列表 RecyclerView 适配器的**绑定入口**定位器。
 *
 * 8.0.80 的聊天列表是 RecyclerView 架构（`ChattingDataAdapterV3`），
 * `getView` 在全 dex 没有任何调用点，`ListAdapter` 那套完全不适用。
 *
 * 适配器自己的 `onBindViewHolder` 也可能被改写（实测 `chatting.adapter.k` 声明的是
 * `C0(holder, position)`，名字被混淆）。真正保证被调用的是**父类**里那个覆写
 * AndroidX 的 `onBindViewHolder(ViewHolder, int)`（名字是覆写所以不会被混淆），
 * 所以这里从适配器类沿继承链往上找它。
 *
 * 找到后：`thisObject` 是适配器实例、`args[0]` 是 holder、`args[1]` 是 position。
 */
object ChatAdapterBindLocator {
    private const val CACHE_PREFS = "Hchat_chat_adapter_bind_cache"
    private const val CACHE_KEY = "bind_v1"

    fun locate(context: FeatureContext, logger: (String, Throwable?) -> Unit): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, key, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf(::isBindCandidate)
            ?.let { return it }

        val adapterClass = locateAdapterClass(context, logger)
        val method = adapterClass?.let(::findBindInHierarchy)
        if (method == null) {
            DexMethodCache.clear(prefs, key, CACHE_KEY)
        } else {
            DexMethodCache.save(prefs, key, CACHE_KEY, method)
        }
        return method
    }

    /** 用项目里已验证的锚点先拿到聊天适配器类，再沿继承链找绑定方法。 */
    private fun locateAdapterClass(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): Class<*>? {
        val anchors = listOf(
            arrayOf("MicroMsg.ChattingDataAdapterV3", "_onBindViewHolder[", "msgInfo"),
            arrayOf("MicroMsg.ChattingDataAdapterV3", "holder", "itemView"),
            arrayOf("MicroMsg.ChattingDataAdapterV3")
        )
        for (anchor in anchors) {
            val found = findByStrings(context, logger, *anchor)
                .firstOrNull { it.declaringClass.name.contains("chatting", ignoreCase = true) }
                ?.declaringClass
            if (found != null) return found
        }
        return null
    }

    private fun findByStrings(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit,
        vararg strings: String
    ): List<Method> = runCatching {
        context.dexKitBridge().findMethod(
            FindMethod().apply {
                matcher(
                    MethodMatcher().apply {
                        usingStrings(strings.toList())
                    }
                )
            }
        ).mapNotNull { data ->
            runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
        }
    }.getOrElse {
        logger("定位聊天适配器绑定方法异常", it)
        emptyList()
    }

    private fun findBindInHierarchy(adapterClass: Class<*>): Method? {
        var current: Class<*>? = adapterClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull(::isBindCandidate)?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun isBindCandidate(method: Method): Boolean {
        if (method.name != "onBindViewHolder") return false
        if (Modifier.isAbstract(method.modifiers) || Modifier.isStatic(method.modifiers)) return false
        val types = method.parameterTypes
        return types.size == 2 &&
            types[1] == Integer.TYPE &&
            method.returnType == Void.TYPE
    }
}
