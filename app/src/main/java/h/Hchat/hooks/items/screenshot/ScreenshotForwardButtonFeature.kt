package h.Hchat.hooks.items.screenshot

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 隐藏「转发截图」浮层按钮（微信 8.0.80_3220 已反编译核对）。
 *
 * ## 反编译依据
 *
 * 资源表里 `string/og0` = 「转发截图」（英文 Forward screenshot），id `0x7f104db0`，
 * 全 dex 只有**一处**引用它：
 *
 * ```
 * Lcom/tencent/mm/ui/feature/api/screenshot/u0;->a(Lzs5/w;)Ljava/util/List;
 *   if (b.invoke() == true) return qa6.q0.d;        // ← 微信自己也会返回空列表
 *   s = getString(2131774896);                       // 「转发截图」
 *   icon = getString(2131823295);
 *   return listOf(zs5.s("forward", s, ..., icon, ...)) // ← 动作 id 就叫 "forward"
 * ```
 *
 * 这个列表最终交给截图动作浮层：
 *
 * ```
 * Lcom/tencent/mm/ui/screenshot/action/ScreenShotActionOverlay;->k(Ljava/util/List;)Lat5/f;
 *   for (z in list) { label = z.c.b; createButton(z.a /*动作 id*/, label, ...) }
 * ```
 *
 * ## 两处挂钩
 *
 * | 位置 | 做法 | 作用 |
 * | --- | --- | --- |
 * | `u0.a(w): List` | 直接返回空列表 | 从源头不生成这一项，等价微信自己的门槛分支 |
 * | `ScreenShotActionOverlay.k(List)` | 摘掉文案为「转发截图」的项 | 兜底：万一上游换了 provider，按钮也绑不出来 |
 *
 * 两处都只摘「转发」这一项，编辑/收藏等其它动作不受影响；截图与浮层本身照旧。
 */
class ScreenshotForwardButtonFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "隐藏截图转发按钮"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            object : SimpleFeatureSettingsProvider(
                ID,
                name(),
                "截图后不再弹出右下角的「转发截图」浮层按钮",
                FeatureSettingsProvider.CATEGORY_ENHANCE
            ) {}
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name()) { installHook(context) }
    }

    private fun installHook(context: FeatureContext): Boolean {
        val config = context.configStore()
        val enabled = HotConfig.flag(ID, KEY_ENABLED, DEFAULT_ENABLED) {
            config.getBoolean(ID, KEY_ENABLED, DEFAULT_ENABLED)
        }
        val providerInstalled = installProviderHook(context, enabled)
        val overlayInstalled = installOverlayHook(context, enabled)
        if (!providerInstalled && !overlayInstalled) {
            logError("截图转发按钮两处锚点都没挂上（微信 ${wechatVersion()}）", null)
            return false
        }
        HLog.e(
            "$TAG 已挂钩 转发provider=$providerInstalled 浮层绑定=$overlayInstalled" +
                "，微信 ${wechatVersion()}"
        )
        return true
    }

    /**
     * 拦「转发截图」动作的 provider，直接返回空列表。
     *
     * 用 DexKit 按「方法里有字符串 forward + 单参数 + 返回 List」定位，再限定在
     * `com.tencent.mm.ui.feature.api.screenshot` 包内：包名是明文的，类名和方法名都是混淆的，
     * 所以只能这样锚。
     */
    private fun installProviderHook(context: FeatureContext, enabled: () -> Boolean): Boolean {
        val loader = context.hostClassLoader()
        val host = context.hostContext()
        // 定位结果落盘缓存：DexKit 全 dex 扫描是启动期最贵的操作之一，
        // 同一个微信版本只需要扫一次（AGENTS.md 明确要求不要每次启动无条件重跑）。
        val prefs = DexMethodCache.prefs(host, CACHE_PREFS)
        val runtimeKey = DexMethodCache.runtimeKey(host, loader)
        val cached = DexMethodCache.loadList(prefs, runtimeKey, loader, CACHE_PROVIDER)
            .filter(::isForwardProviderMethod)
        val methods = if (cached.isNotEmpty()) {
            cached
        } else {
            // 动作 id 的**字符串常量**在不同微信版本写法不一样：
            //   8.0.80 → "forward"（u0.a 里 zs5.s("forward", ...) 的首参）
            //   8.0.76 → 只有 "forward_method" / "forward_screenshot_btn" 这类带后缀的键，
            //            纯 "forward" 在那个方法体里根本不出现，只锚 "forward" 会零命中，
            //            于是 installProviderHook 直接返回 false，整个功能静默失效。
            // 这里按候选串逐个扫，命中即停，避免一次性放宽成模糊匹配把别的 provider 也扫进来。
            val located = runCatching {
                var found = emptyList<Method>()
                for (anchor in FORWARD_ACTION_ANCHORS) {
                    found = context.dexKitBridge().findMethod(
                        FindMethod().apply {
                            matcher(
                                MethodMatcher().apply {
                                    usingEqStrings(anchor)
                                    paramCount(1)
                                    returnType("java.util.List")
                                }
                            )
                        }
                    ).mapNotNull { data -> runCatching { data.getMethodInstance(loader) }.getOrNull() }
                        .filter(::isForwardProviderMethod)
                    if (found.isNotEmpty()) break
                }
                found
            }.getOrElse {
                logError("定位截图转发 provider 失败", it)
                return false
            }
            if (located.isNotEmpty()) {
                DexMethodCache.saveList(prefs, runtimeKey, CACHE_PROVIDER, located)
            }
            located
        }
        if (methods.isEmpty()) {
            logError("截图转发 provider 未命中（微信 ${wechatVersion()}）", null)
            return false
        }
        methods.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    // 空列表就是微信自己在门槛分支里的返回值，不会让调用方异常。
                    param.setResult(emptyList<Any>())
                    if (providerHitLogged.compareAndSet(false, true)) {
                        HLog.e("$TAG 已拦截转发截图动作: ${method.declaringClass.name}#${method.name}")
                    }
                }
            })
        }
        return true
    }

    /**
     * 兜底：在截图动作浮层把动作列表绑成按钮时，摘掉文案命中的那一项。
     *
     * 浮层类名是明文的（`com.tencent.mm.ui.screenshot.action.ScreenShotActionOverlay`），
     * 但方法名 `k` 是混淆的，所以按「单 List 参数 + 有返回值」锚定，不写死方法名。
     */
    private fun installOverlayHook(context: FeatureContext, enabled: () -> Boolean): Boolean {
        val loader = context.hostClassLoader()
        // 8.0.76 起浮层类被混淆，「类名固定」的前提不再成立：
        // 按候选名找，再退到包内按方法签名找（单 List 参数 + 有返回值）。
        val clazz = OVERLAY_CANDIDATE_CLASSES.asSequence()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .firstOrNull { candidate ->
                KavaReflector.declaredMethods(candidate).any(::isActionBindMethod)
            }
        if (clazz == null) {
            HLog.e("$TAG 未找到截图动作浮层（微信 ${wechatVersion()}），跳过兜底过滤")
            return false
        }
        val targets = KavaReflector.declaredMethods(clazz).filter(::isActionBindMethod)
        if (targets.isEmpty()) {
            HLog.e("$TAG 浮层没有「单 List 参数」的绑定方法，跳过兜底过滤")
            return false
        }
        targets.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val list = param.args?.getOrNull(0) as? List<*> ?: return
                    if (list.isEmpty()) return
                    val kept = ArrayList<Any?>(list.size)
                    var dropped = 0
                    for (item in list) {
                        val texts = ScreenshotActionTexts.of(item)
                        if (texts.any(ScreenshotActionLabelPolicy::shouldHide)) dropped++ else kept += item
                        if (probeLogged.compareAndSet(false, true)) {
                            HLog.e("$TAG 截图动作浮层候选项: ${texts.joinToString("|")}")
                        }
                    }
                    if (dropped == 0) return
                    // 换掉整个 List 而不是 removeIf：原集合可能是只读实现，会抛。
                    param.args[0] = kept
                    if (overlayHitLogged.compareAndSet(false, true)) {
                        HLog.e("$TAG 浮层已摘掉转发按钮: 丢弃 $dropped 项，保留 ${kept.size}/${list.size}")
                    }
                }
            })
        }
        return true
    }

    private fun isForwardProviderMethod(method: Method): Boolean =
        method.declaringClass.name.startsWith(PROVIDER_PACKAGE) &&
            method.parameterCount == 1 &&
            method.returnType == List::class.java &&
            !Modifier.isStatic(method.modifiers)

    private fun isActionBindMethod(method: Method): Boolean =
        method.name != "<init>" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == List::class.java &&
            method.returnType != Void.TYPE &&
            !Modifier.isStatic(method.modifiers)

    private fun wechatVersion(): String = WeChatApis.version()?.current()?.versionName ?: "未知"

    companion object {
        const val ID = "screenshot_forward_button"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = false

        private const val TAG = "[Hchat:ScreenshotButton]"
        private const val CACHE_PREFS = "Hchat_screenshot_button_method_cache"
        private const val CACHE_PROVIDER = "provider_v2"
        private const val PROVIDER_PACKAGE = "com.tencent.mm.ui.feature.api.screenshot."

        /**
         * 转发动作 id 的候选串，按出现频率从新到旧。
         *
         * 8.0.80 的动作表用纯 `forward`；8.0.76 只有带后缀的键
         * （`forward_method` / `forward_screenshot_btn`），纯 `forward` 在该方法体里
         * 不存在。逐个尝试、命中即停，比放宽成长度/前缀匹配安全得多
         * （模糊匹配会把 `forward` 以外的分享动作也扫进来）。
         */
        private val FORWARD_ACTION_ANCHORS = listOf(
            "forward",
            "forward_method",
            "forward_screenshot_btn"
        )

        /**
         * 截图动作浮层的候选类名。
         *
         * 8.0.80 是明文的 `ScreenShotActionOverlay`；8.0.76 起该类被整包混淆，
         * 只保留在 `com.tencent.mm.ui.screenshot` 包下（实测只剩 MMImageBottomSheet）。
         * 因此浮层兜底改为「包内按方法签名找」，类名列表不再视为必需。
         */
        private val OVERLAY_CANDIDATE_CLASSES = listOf(
            "com.tencent.mm.ui.screenshot.action.ScreenShotActionOverlay",
            "com.tencent.mm.ui.screenshot.MMImageBottomSheet"
        )

        private val providerHitLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        private val overlayHitLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        private val probeLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

/**
 * 按钮文案判定。抽成纯函数是为了能在 JVM 单测里锁死边界，
 * 不必起微信进程。
 */
internal object ScreenshotActionLabelPolicy {
    private const val FORWARD_SCREENSHOT = "转发截图"

    fun shouldHide(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        // 中文文案：资源 string/og0，8.0.80 里只被转发动作引用。
        if (value.contains(FORWARD_SCREENSHOT)) return true
        // 英文/内部标识兜底，防止文案本地化后漏判。
        return value.contains("forward", ignoreCase = true) &&
            value.contains("screenshot", ignoreCase = true)
    }
}

/**
 * 从 `at5/z` 这类动作描述对象里挖出所有文案。
 *
 * 不写死字段名（`c` / `b` 都是混淆名且会随版本变）：逐层读成员变量，
 * 最多下探两层，收集所有 CharSequence。动作项只有 3 个字段，
 * 这点反射成本远低于它替我们省掉的版本适配工作。
 */
internal object ScreenshotActionTexts {
    private const val MAX_DEPTH = 2
    private const val MAX_FIELDS = 12

    fun of(item: Any?): List<String> {
        val out = ArrayList<String>(4)
        collect(item, 0, out, HashSet())
        return out
    }

    private fun collect(value: Any?, depth: Int, out: MutableList<String>, seen: MutableSet<Any>) {
        if (value == null || depth > MAX_DEPTH) return
        if (value is CharSequence) {
            val text = value.toString()
            if (text.isNotEmpty()) out += text
            return
        }
        val clazz = value.javaClass
        if (clazz.isPrimitive || clazz.isEnum || clazz.name.startsWith("java.lang.")) return
        if (!seen.add(value)) return
        var visited = 0
        for (field in KavaReflector.declaredFields(clazz)) {
            if (visited >= MAX_FIELDS) break
            if (Modifier.isStatic(field.modifiers)) continue
            val fieldValue = runCatching { KavaReflector.readField(field, value) }.getOrNull() ?: continue
            visited++
            collect(fieldValue, depth + 1, out, seen)
        }
    }
}
