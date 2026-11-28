package h.Hchat.hooks.api.message

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 聊天列表适配器的绑定入口。
 *
 * @property adapterClass 宿主真正使用的聊天适配器（`ChattingDataAdapterV3`）。
 *   实现方法可能声明在它的**祖先**类上，所以 Hook 挂在祖先方法上，但运行时必须用
 *   [adapterClass] 过滤 `thisObject`，否则同一个祖先基类被其它列表复用时会被误触发。
 * @property methods 需要安装的全部 `onBindViewHolder` 重载（两参 + payload 三参）。
 */
class ChatAdapterBinding(
    val adapterClass: Class<*>,
    val methods: List<Method>
) {
    /**
     * 判断这次绑定是不是聊天列表自己的。
     *
     * 绑定方法可能声明在公共基类上（`qw5.n0` / `SynchronizedAdapter`），
     * 同一个基类还被其它列表复用，所以必须按实例过滤：要么是锚点适配器本身
     * （或它的子类），要么实例类就在 `com.tencent.mm.ui.chatting` 包下。
     */
    fun matches(instance: Any?): Boolean {
        if (instance == null) return false
        if (adapterClass.isInstance(instance)) return true
        return instance.javaClass.name.startsWith("com.tencent.mm.ui.chatting")
    }
}

/**
 * 聊天列表 RecyclerView 适配器的**绑定入口**定位器。
 *
 * 8.0.80 实测结构（dexsql 核对）：
 *
 * ```text
 * com.tencent.mm.ui.chatting.adapter.k            (ChattingDataAdapterV3，锚点类)
 *   → yt3.y1
 *     → com.tencent.mm.view.recyclerview.WxRecyclerAdapter
 *       → qw5.n0                                  (声明两参 + 三参)
 *         → com.tencent.mm.ui.recyclerview.SynchronizedAdapter  (只声明三参 payload)
 *           → androidx.recyclerview.widget.f2     (AndroidX Adapter)
 * ```
 *
 * RecyclerView 走的是 `onBindViewHolder(holder, position, payloads)`，虚分发会命中
 * **最派生**的覆写（这里是 `SynchronizedAdapter` 的三参）。旧实现只沿继承链取第一个
 * 命中（`qw5.n0` 的两参），局部刷新路径完全收不到回调——表现就是「日志说已挂载，
 * 功能却一点反应都没有」。
 *
 * 因此这里同时做两件事：
 * 1. 收集继承链上**所有** `com.tencent.mm` 类声明的两参/三参重载并全部安装；
 * 2. 把锚点适配器类一起返回，让功能在拦截时按 `thisObject` 精确过滤，
 *    避免挂在公共基类上时误伤其它 RecyclerView。
 *
 * 定位锚点仍是项目里已验证的方法体内字符串（`MicroMsg.ChattingDataAdapterV3` 等）。
 */
object ChatAdapterBindLocator {
    private const val CACHE_PREFS = "Hchat_chat_adapter_bind_cache"
    /**
     * v4：v3 及更早版本按 `com.tencent.mm` 前缀区分微信类和框架类，
     * 会把 `qw5.n0` 这类混淆后的微信类误判成框架类丢掉，导致只挂到 1 个绑定重载。
     * 升级缓存键让旧结果立即失效，重新按正确规则收集。
     */
    private const val CACHE_KEY = "bind_v4"
    private const val CACHE_ANCHOR = "bind_v4_anchor"

    fun locate(context: FeatureContext, logger: (String, Throwable?) -> Unit): ChatAdapterBinding? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())

        val cachedAnchor = DexMethodCache
            .loadConstructor(prefs, key, context.hostClassLoader(), CACHE_ANCHOR)
            ?.declaringClass
        val cachedMethods = DexMethodCache
            .loadList(prefs, key, context.hostClassLoader(), CACHE_KEY)
            .filter(::isBindCandidate)
        // 只有两参或只有一个重载时视为旧结果不完整：聊天列表必然同时存在
        // `(holder, position)` 与 payload 三参刷新，缺任何一条都会漏掉局部更新。
        if (cachedAnchor != null && cachedMethods.size >= 2) {
            return ChatAdapterBinding(cachedAnchor, order(cachedMethods))
        }

        val adapterClass = locateAdapterClass(context, logger)
        if (adapterClass == null) {
            logger("聊天适配器定位失败：锚点字符串未命中", null)
            return null
        }
        val methods = findBindsInHierarchy(adapterClass)
        if (methods.isEmpty()) {
            DexMethodCache.clear(prefs, key, CACHE_KEY)
            DexMethodCache.clear(prefs, key, CACHE_ANCHOR)
            return null
        }
        // 锚点类用一个构造器做键，读回时取 declaringClass；适配器类结构在进程内不会变。
        KavaReflector.declaredConstructors(adapterClass).firstOrNull()
            ?.let { DexMethodCache.saveConstructor(prefs, key, CACHE_ANCHOR, it) }
        DexMethodCache.saveList(prefs, key, CACHE_KEY, methods)
        return ChatAdapterBinding(adapterClass, order(methods))
    }

    private fun order(methods: List<Method>): List<Method> =
        methods.distinctBy { it.toGenericString() }.sortedBy { it.parameterTypes.size }

    /** 用项目里已验证的锚点先拿到聊天适配器类。 */
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

    /**
     * 沿继承链收集所有绑定重载。
     *
     * 只跳过真正的框架类（androidx / android / java / kotlin）。微信自己的代码大量落在
     * 混淆过的顶层包（`qw5.n0`、`yt3.y1`），**不能**按 `com.tencent.mm` 前缀判断，
     * 否则会把它们当成框架类丢掉——8.0.80 上就会出现「只挂到 SynchronizedAdapter
     * 的三参、漏掉 qw5.n0 的两参」。真的一个都没找到时才回退到框架基类。
     */
    private fun findBindsInHierarchy(adapterClass: Class<*>): List<Method> {
        val own = ArrayList<Method>(4)
        val framework = ArrayList<Method>(2)
        var current: Class<*>? = adapterClass
        while (current != null && current != Any::class.java) {
            val bucket = if (isFrameworkClass(current.name)) framework else own
            KavaReflector.declaredMethods(current)
                .filter(::isBindCandidate)
                .forEach { candidate ->
                    if (bucket.none { it.toGenericString() == candidate.toGenericString() }) {
                        bucket.add(candidate)
                    }
                }
            current = current.superclass
        }
        return if (own.isNotEmpty()) own else framework
    }

    private fun isFrameworkClass(name: String): Boolean =
        name.startsWith("androidx.") ||
            name.startsWith("android.") ||
            name.startsWith("java.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("dalvik.")

    /**
     * 两参 `onBindViewHolder(VH, int)` 与三参 payload 版本都算候选。
     *
     * 三参只接受 `List` 作为第三参，避免把别的同名重载当绑定入口。
     */
    private fun isBindCandidate(method: Method): Boolean {
        if (method.name != "onBindViewHolder") return false
        if (Modifier.isAbstract(method.modifiers) || Modifier.isStatic(method.modifiers)) return false
        if (method.returnType != Void.TYPE) return false
        val types = method.parameterTypes
        if (types.size == 2) return types[1] == Integer.TYPE
        if (types.size == 3) return types[1] == Integer.TYPE && List::class.java.isAssignableFrom(types[2])
        return false
    }
}
