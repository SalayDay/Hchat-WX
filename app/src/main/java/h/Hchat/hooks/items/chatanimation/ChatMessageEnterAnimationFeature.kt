package h.Hchat.hooks.items.chatanimation

import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 聊天消息入场动效。
 *
 * 只在聊天列表**新建**一个 item View 时做动画（`convertView == null`）。
 * 复用 View 的滚动过程完全不碰，所以滑动不会被动画拖慢，
 * 也不会影响微信自己的 item 复用逻辑。
 */
class ChatMessageEnterAnimationFeature : BaseFeature() {
    private var runtime: ChatMessageEnterAnimationRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "消息入场动效"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ChatMessageEnterAnimationSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = ChatMessageEnterAnimationRuntime(context, ::logFeatureError)
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
        const val ID = "chat_message_enter_animation"
    }
}

private class ChatMessageEnterAnimationRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = DexMethodCache.prefs(
        context.hostContext(),
        "Hchat_chat_enter_animation_cache"
    )
    private val cacheKey = DexMethodCache.runtimeKey(
        context.hostContext(),
        context.hostClassLoader()
    )
    private val animated = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private var installed = false

    private val enabled = HotConfig.flag(
        ChatMessageEnterAnimationFeature.ID,
        ChatMessageEnterAnimationSettings.KEY_ENABLED,
        ChatMessageEnterAnimationSettings.DEFAULT_ENABLED
    ) {
        context.configStore().getBoolean(
            ChatMessageEnterAnimationFeature.ID,
            ChatMessageEnterAnimationSettings.KEY_ENABLED,
            ChatMessageEnterAnimationSettings.DEFAULT_ENABLED
        )
    }

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val method = locateGetView() ?: run {
            logger("消息入场动效：未定位到聊天列表 getView", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleGetView(param)
                }
            })
            installed = true
            true
        }.getOrElse {
            logger("消息入场动效 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    fun destroy() {
        installed = false
        animated.clear()
    }

    private fun handleGetView(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        // convertView 复用时不做动画：滚动里每次绑定都动画会明显拖慢列表。
        if (param.args?.getOrNull(1) != null) return
        val view = param.result as? View ?: return
        if (animated.put(view, true) != null) return
        animate(view)
    }

    private fun animate(view: View) {
        val distance = view.resources.displayMetrics.density * START_OFFSET_DP
        view.alpha = 0f
        view.scaleX = START_SCALE
        view.scaleY = START_SCALE
        view.translationY = distance
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
    }

    /**
     * 聊天消息列表的 `getView(int, View, ViewGroup)`。
     *
     * 优先限定 `ui.chatting.adapter` 包（8.0.80 上是 `chatting/adapter/k`），
     * 找不到再放宽到 `ui.chatting` 整个包，靠签名（int, View, ViewGroup → View）判断，
     * 不写死混淆类名。
     */
    private fun locateGetView(): Method? {
        DexMethodCache.load(prefs, cacheKey, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf(::isGetViewCandidate)
            ?.let { return it }

        val method = findGetView("com.tencent.mm.ui.chatting.adapter.")
            ?: findGetView("com.tencent.mm.ui.chatting.")
        if (method == null) {
            DexMethodCache.clear(prefs, cacheKey, CACHE_KEY)
        } else {
            DexMethodCache.save(prefs, cacheKey, CACHE_KEY, method)
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

    private companion object {
        const val CACHE_KEY = "chat_get_view_v1"
        const val DURATION_MS = 200L
        const val START_SCALE = 0.90f
        const val START_OFFSET_DP = 8f
    }
}