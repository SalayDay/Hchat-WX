package h.Hchat.hooks.items.chatanimation

import android.view.View
import android.view.animation.OvershootInterpolator
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
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
        // 聊天列表是 RecyclerView 架构（ChattingDataAdapterV3），**没有 getView 调用点**，
        // 日志里的「找不到 getView」就是这么来的。改用项目里已验证的定位方式：
        // 按方法体内 "MicroMsg.ChattingDataAdapterV3" / "_onBindViewHolder[" 找绑定方法。
        val method = locateBindMethod() ?: run {
            logger("消息入场动效：未定位到聊天消息绑定方法", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleBind(param)
                }
            })
            installed = true
            logger("消息入场动效：已挂载 ${method.declaringClass.simpleName}#${method.name}", null)
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

    /**
     * 只对**首次出现在屏幕上的** item 做动画。
     *
     * 用 `animated` 弱表记住已经动画过的 View：滚动回流时同一个 View 不会再动，
     * 所以滑动过程零额外开销，也不会出现「滚动时消息反复弹出」。
     */
    private fun handleBind(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val holder = param.args?.getOrNull(0) ?: return
        val view = holderItemView(holder) ?: return
        if (animated.put(view, true) != null) return
        animate(view)
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
     * 找聊天消息的 `onBindViewHolder(holder, position)`。
     *
     * 锚点是 `MicroMsg.ChattingDataAdapterV3` + `_onBindViewHolder[`，这两个串在
     * 项目里已被「消息文字颜色」「滑动引用」验证过，跨版本稳定。
     */
    private fun locateBindMethod(): Method? {
        DexMethodCache.load(prefs, cacheKey, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf(::isBindCandidate)
            ?.let { return it }

        val method = findByStrings("MicroMsg.ChattingDataAdapterV3", "_onBindViewHolder[", "msgInfo")
            .firstOrNull(::isBindCandidate)
            ?: findByStrings("MicroMsg.ChattingDataAdapterV3", "holder", "itemView")
                .firstOrNull(::isBindCandidate)
        if (method == null) {
            DexMethodCache.clear(prefs, cacheKey, CACHE_KEY)
        } else {
            DexMethodCache.save(prefs, cacheKey, CACHE_KEY, method)
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

    private companion object {
        const val CACHE_KEY = "chat_bind_v2"
        const val DURATION_MS = 200L
        const val START_SCALE = 0.90f
        const val START_OFFSET_DP = 8f
    }
}