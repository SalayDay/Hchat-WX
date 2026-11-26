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
        runtime = ChatPurifyRuntime(context, ::logFeatureError)
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
            "androidx.recyclerview.widget.RecyclerView",
            context.hostClassLoader()
        ) ?: return false
        val decorationClass = KavaReflector.loadClass(
            "androidx.recyclerview.widget.RecyclerView\$ItemDecoration",
            context.hostClassLoader()
        ) ?: return false
        val method = KavaReflector.findMethodRecursive(
            recyclerClass,
            "addItemDecoration",
            decorationClass
        ) ?: return false
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
                        // 跳过这次 addItemDecoration：分割线装饰根本不注册。
                        // 必须走 setResult(null)：直接赋 result 不会置 returnEarly，原方法照样执行。
                        param.setResult(null)
                    }
                }
            })
            true
        }.getOrElse {
            logger("界面净化：分割线 Hook 安装失败", it)
            false
        }
    }

    // -------------------------------------------------------------- 系统灰字

    private fun installSystemTipHook(): Boolean {
        val method = locateGetView() ?: return false
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
                    val view = param.result as? View ?: return
                    val position = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    val adapter = param.thisObject ?: return
                    val message = KavaReflector.invokeMethod(adapter, "getItem", position) ?: return
                    if (messageType(message) == ChatPurifyFeature.MESSAGE_TYPE_SYSTEM) {
                        hideItem(view)
                    } else if (view.visibility == View.GONE) {
                        // 复用自愈：这条 View 之前被隐藏过，现在绑的是普通消息，恢复显示。
                        view.visibility = View.VISIBLE
                    }
                }
            })
            true
        }.getOrElse {
            logger("界面净化：系统提示 Hook 安装失败", it)
            false
        }
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

    private fun locateGetView(): Method? {
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(cachePrefs, cacheKey, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf(::isGetViewCandidate)
            ?.let { return it }
        val method = findGetView("com.tencent.mm.ui.chatting.adapter.")
            ?: findGetView("com.tencent.mm.ui.chatting.")
        if (method == null) {
            DexMethodCache.clear(cachePrefs, cacheKey, CACHE_KEY)
        } else {
            DexMethodCache.save(cachePrefs, cacheKey, CACHE_KEY, method)
        }
        return method
    }

    private fun findGetView(packageAnchor: String): Method? = runCatching {
        context.dexKitBridge().findMethod(
            FindMethod().apply {
                matcher(
                    MethodMatcher().apply {
                        declaredClass(packageAnchor, StringMatchType.Contains, false)
                        name("getView")
                        paramCount(3)
                        returnType("android.view.View")
                    }
                )
            }
        ).mapNotNull { data ->
            runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
        }.firstOrNull(::isGetViewCandidate)
    }.getOrElse {
        logger("DexKit 定位聊天 getView 失败: $packageAnchor", it)
        null
    }

    private fun isGetViewCandidate(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) ||
            Modifier.isAbstract(method.modifiers) ||
            method.declaringClass.isInterface
        ) {
            return false
        }
        val types = method.parameterTypes
        return method.returnType == View::class.java &&
            types.size == 3 &&
            types[0] == Integer.TYPE &&
            types[1] == View::class.java &&
            types[2] == ViewGroup::class.java
    }

    private fun enabled(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    private companion object {
        const val CACHE_KEY = "chat_get_view_v1"
    }
}

class ChatPurifySettingsProvider : h.Hchat.ui.SimpleFeatureSettingsProvider(
    ChatPurifyFeature.ID,
    "界面净化",
    "隐藏首页列表分割线、聊天里的系统灰字提示",
    h.Hchat.ui.FeatureSettingsProvider.CATEGORY_PRACTICAL
)