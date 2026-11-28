package h.Hchat.hooks.items.chatanimation

import android.view.View
import android.view.animation.OvershootInterpolator
import io.github.libxposed.api.XposedInterface
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.api.message.ChatAdapterBindLocator
import h.Hchat.hooks.api.message.ChatAdapterBinding
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
        // 安装结果一律走 boot 缓冲：功能在启动期安装，而日志采集通常是之后才打开。
        runtime = ChatMessageEnterAnimationRuntime(context) { message, throwable ->
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
    private val bindHitLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 同一轮绑定里两参/三参层层回调的深度，只在最外层播放动画。 */
    private val bindDepth = ThreadLocal<Int>()
    /** holder 类 → itemView 字段，避免每次绑定都遍历字段表。 */
    private val itemViewFields = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private var installed = false

    fun enterBind(): Boolean {
        val depth = bindDepth.get() ?: 0
        bindDepth.set(depth + 1)
        return depth == 0
    }

    fun exitBind() {
        val depth = (bindDepth.get() ?: 1) - 1
        if (depth <= 0) bindDepth.remove() else bindDepth.set(depth)
    }

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
        // 聊天列表是 RecyclerView 架构（ChattingDataAdapterV3），没有 getView 调用点。
        // 也不能挂适配器自己被混淆的 per-item 回调（实测挂到 k#C0 也没被调用）。
        // 真正执行的是继承链上最派生的 onBindViewHolder（含 payload 三参），
        // 所以定位器会返回链上全部重载，运行时再用适配器类过滤 thisObject。
        val binding = ChatAdapterBindLocator.locate(context, logger) ?: run {
            logger("消息入场动效：未定位到聊天绑定方法", null)
            return false
        }
        var hooked = 0
        val hookedNames = StringBuilder()
        binding.methods.forEach { method ->
            runCatching {
                HookRegistry.get().hook(method, ChatBindHooker(this, binding))
                hooked++
                if (hookedNames.isNotEmpty()) hookedNames.append('、')
                hookedNames.append(method.declaringClass.simpleName)
                hookedNames.append('#').append(method.name)
                hookedNames.append('(').append(method.parameterCount).append(')')
            }.onFailure {
                logger("消息入场动效 Hook 安装失败: ${method.toGenericString()}", it)
            }
        }
        installed = hooked > 0
        if (installed) {
            logger(
                "消息入场动效：已挂载 $hookedNames（适配器 ${binding.adapterClass.simpleName}），" +
                    "开关 ${if (enabled()) "开" else "关"}",
                null
            )
        }
        return installed
    }

    private class ChatBindHooker(
        private val runtime: ChatMessageEnterAnimationRuntime,
        private val binding: ChatAdapterBinding
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!binding.matches(chain.thisObject)) return chain.proceed()
            if (!runtime.enabled()) return chain.proceed()
            // 两参与 payload 三参会在同一轮绑定里层层回调，只在最外层处理，
            // 否则每条消息要跑好几遍反射。
            val outermost = runtime.enterBind()
            try {
                val result = chain.proceed()
                if (outermost) runtime.handleBind(chain.args.toTypedArray())
                return result
            } finally {
                runtime.exitBind()
            }
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
    private fun handleBind(args: Array<Any?>) {
        if (!enabled()) return
        val started = h.Hchat.utils.SlowPathLog.now()
        val holder = args.getOrNull(0) ?: return
        val view = holderItemView(holder) ?: return
        if (animated.put(view, true) != null) return
        animate(view)
        if (bindHitLogged.compareAndSet(false, true)) {
            logger("消息入场动效：绑定回调已命中并播放动画（首次）", null)
        }
        h.Hchat.utils.SlowPathLog.report(
            "chatanim.bind",
            h.Hchat.utils.SlowPathLog.elapsedSince(started),
            SLOW_BIND_MS,
            "消息入场动效绑定处理",
            holder.javaClass.simpleName
        )
    }

    private fun holderItemView(holder: Any): View? {
        val field = itemViewField(holder.javaClass) ?: return null
        return KavaReflector.readField(field, holder) as? View
    }

    private fun itemViewField(holderClass: Class<*>): java.lang.reflect.Field? {
        itemViewFields[holderClass]?.let { return it as? java.lang.reflect.Field }
        var found: java.lang.reflect.Field? = null
        var current: Class<*>? = holderClass
        while (current != null && current != Any::class.java) {
            // RecyclerView.ViewHolder.itemView 就是 View 字段，按类型找最稳。
            found = KavaReflector.declaredFields(current).firstOrNull { it.type == View::class.java }
            if (found != null) break
            current = current.superclass
        }
        itemViewFields[holderClass] = found ?: NONE_FIELD
        return found
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
    private companion object {
        const val CACHE_KEY = "chat_bind_v2"
        /** 字段扫描的负结果哨兵：ConcurrentHashMap 不能存 null。 */
        val NONE_FIELD = Any()
        /** 单次绑定处理超过这个耗时就记一条慢路径日志。 */
        const val SLOW_BIND_MS = 8L
        const val DURATION_MS = 200L
        const val START_SCALE = 0.90f
        const val START_OFFSET_DP = 8f
    }
}
