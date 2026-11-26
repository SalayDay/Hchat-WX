package h.Hchat.hooks.items.purify

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 界面净化：隐藏列表分割线、隐藏聊天里的系统灰字提示。
 *
 * 两项都只做「识别明确的目标就处理、识别不到就完全不动」：
 * - 分割线：只在首页会话列表上跳过类名含 Divider/Separator 的 ItemDecoration；
 * - 系统提示：只在聊天列表 item 的消息类型是 10000（微信的系统提示）时隐藏该行，
 *   不会按文字内容猜，避免误伤正常消息。
 */
class ChatPurifyFeature : BaseFeature() {
    private var runtime: ChatPurifyRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "界面净化"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ChatPurifySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        // 安装结果一律走 boot 缓冲：功能在启动期安装，而日志采集通常是之后才打开。
        runtime = ChatPurifyRuntime(context) { message, throwable ->
            if (throwable == null) logBoot(message) else logError(message, throwable)
        }
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) { runtime?.install() == true }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "chat_purify"
        const val KEY_HIDE_DIVIDER = "hide_divider"
        const val KEY_HIDE_SYSTEM_TIP = "hide_system_tip"
        const val DEFAULT_HIDE_DIVIDER = false
        const val DEFAULT_HIDE_SYSTEM_TIP = false

        /** 微信的系统提示消息类型。 */
        const val MESSAGE_TYPE_SYSTEM = 10000
    }
}

private class ChatPurifyRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = context.configStore().getFeaturePrefs(ChatPurifyFeature.ID)
    private val cachePrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_chat_purify_cache")
    private var installed = false

    fun install(): Boolean {
        if (installed) return true
        val dividerHooked = installDividerHook()
        val tipHooked = installSystemTipHook()
        installed = dividerHooked || tipHooked
        if (!installed) logger("界面净化：两项都未安装成功", null)
        return installed
    }

    fun destroy() {
        installed = false
    }

    // ------------------------------------------------------------ 隐藏分割线

    private fun installDividerHook(): Boolean {
        val recyclerClass = KavaReflector.loadClass(
            "androidx.recyclerview/widget/RecyclerView",
            context.hostClassLoader()
        ) ?: return false
        val decorationClass = KavaReflector.loadClass(
            "androidx.recyclerview.widget.RecyclerView\$ItemDecoration",
            context.hostClassLoader()
        ) ?: return false
        // 8.0.80 里 addItemDecoration 被混淆成单字母（实测 `P`），按名字找不到。
        // 但它签名是全类唯一的：唯一参数是 ItemDecoration、返回 void。按签名找才跨版本。
        val method = findAddItemDecoration(recyclerClass, decorationClass) ?: run {
            logger("界面净化：找不到 addItemDecoration", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled(ChatPurifyFeature.KEY_HIDE_DIVIDER, ChatPurifyFeature.DEFAULT_HIDE_DIVIDER)) {
                        return
                    }
                    val recycler = param.thisObject as? View ?: return
                    if (!recycler.javaClass.name.contains("conversation", ignoreCase = true)) return
                    val decoration = param.args?.getOrNull(0) ?: return
                    val name = decoration.javaClass.name
                    if (name.contains("Divider", ignoreCase = true) ||
                        name.contains("Separator", ignoreCase = true) ||
                        name.contains("ItemOffset", ignoreCase = true)
                    ) {
                        // 跳过这次注册。必须走 setResult(null)：直接赋 result 不会置
                        // returnEarly，原方法照样执行。
                        param.setResult(null)
                    }
                }
            })
            logger("界面净化：分割线 Hook 已挂载 ${method.name}#${method.declaringClass.simpleName}", null)
            true
        }.getOrElse {
            logger("界面净化：分割线 Hook 安装失败", it)
            false
        }
    }

    private fun findAddItemDecoration(
        recyclerClass: Class<*>,
        decorationClass: Class<*>
    ): Method? {
        var current: Class<*>? = recyclerClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull { candidate ->
                val types = candidate.parameterTypes
                types.size == 1 &&
                    types[0].name == decorationClass.name &&
                    candidate.returnType == Void.TYPE &&
                    !Modifier.isStatic(candidate.modifiers)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    // -------------------------------------------------------------- 系统灰字

    private fun installSystemTipHook(): Boolean {
        val method = locateBindMethod() ?: run {
            logger("界面净化：未定位到聊天消息绑定方法", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled(
                            ChatPurifyFeature.KEY_HIDE_SYSTEM_TIP,
                            ChatPurifyFeature.DEFAULT_HIDE_SYSTEM_TIP
                        )
                    ) {
                        return
                    }
                    // 聊天列表是 RecyclerView 架构，没有 getView；绑定方法的参数是
                    // (holder, position)。消息对象只能从 holder 上拿。
                    val holder = param.args?.getOrNull(0) ?: return
                    val view = holderItemView(holder) ?: return
                    val message = holderMessage(holder, param) ?: return
                    if (messageType(message) == ChatPurifyFeature.MESSAGE_TYPE_SYSTEM) {
                        hideItem(view)
                    } else if (view.visibility == View.GONE) {
                        // 复用自愈：这条 View 之前被隐藏过，现在绑的是普通消息，恢复显示。
                        view.visibility = View.VISIBLE
                    }
                }
            })
            logger("界面净化：系统提示 Hook 已挂载 ${method.declaringClass.simpleName}#${method.name}", null)
            true
        }.getOrElse {
            logger("界面净化：系统提示 Hook 安装失败", it)
            false
        }
    }

    private fun holderItemView(holder: Any): View? {
        (KavaReflector.readField(holder, "itemView") as? View)?.let { return it }
        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).firstOrNull { it.type == View::class.java }
                ?.let { return KavaReflector.readField(it, holder) as? View }
            current = current.superclass
        }
        return null
    }

    /**
     * 从 holder 上读它当前绑定的消息对象。
     *
     * holder 通常持有消息字段；字段名逐版本变化，所以按「带 getType 且返回 int」的
     * 对象特征找，而不是按名字找。找不到就退回用 position 问 adapter 要。
     */
    private fun holderMessage(holder: Any, param: XC_MethodHook.MethodHookParam): Any? {
        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.isPrimitive || field.type == String::class.java) continue
                val value = KavaReflector.readField(field, holder) ?: continue
                if (messageType(value) != null) return value
            }
            current = current.superclass
        }
        val position = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return null
        return KavaReflector.invokeMethod(param.thisObject, "getItem", position)
    }

    private fun hideItem(view: View) {
        if (view.visibility == View.GONE) return
        // 只改可见性。不要动 layoutParams.height：列表项 View 会被回收复用，
        // 压成 0 之后复用到正常消息上，那条消息会跟着消失。
        view.visibility = View.GONE
    }

    private fun messageType(message: Any): Int? {
        (KavaReflector.invokeMethod(message, "getType") as? Number)?.let { return it.toInt() }
        (KavaReflector.readField(message, "field_type") as? Number)?.let { return it.toInt() }
        return null
    }

    private fun locateBindMethod(): Method? {
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(cachePrefs, cacheKey, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf(::isBindCandidate)
            ?.let { return it }
        val method = findByStrings("MicroMsg.ChattingDataAdapterV3", "_onBindViewHolder[", "msgInfo")
            .firstOrNull(::isBindCandidate)
            ?: findByStrings("MicroMsg.ChattingDataAdapterV3", "holder", "itemView")
                .firstOrNull(::isBindCandidate)
        if (method == null) {
            DexMethodCache.clear(cachePrefs, cacheKey, CACHE_KEY)
        } else {
            DexMethodCache.save(cachePrefs, cacheKey, CACHE_KEY, method)
        }
        return method
    }

    private fun findByStrings(vararg strings: String): List<Method> = runCatching {
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
        logger("DexKit 定位聊天绑定方法失败", it)
        emptyList()
    }

    private fun isBindCandidate(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) ||
            Modifier.isAbstract(method.modifiers) ||
            method.declaringClass.isInterface
        ) {
            return false
        }
        val types = method.parameterTypes
        return types.size == 2 &&
            types[1] == Integer.TYPE &&
            method.returnType == Void.TYPE
    }

    private fun enabled(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    private companion object {
        const val CACHE_KEY = "chat_bind_v2"
    }
}

class ChatPurifySettingsProvider : h.Hchat.ui.SimpleFeatureSettingsProvider(
    ChatPurifyFeature.ID,
    "界面净化",
    "隐藏首页列表分割线、聊天里的系统灰字提示",
    h.Hchat.ui.FeatureSettingsProvider.CATEGORY_PRACTICAL
)
