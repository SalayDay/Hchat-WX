package h.Hchat.hooks.items.bottombar

import android.app.Activity
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature
import h.Hchat.preferences.HotConfig
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 悬浮底栏滑动隐藏。
 *
 * 首页会话列表是 `com.tencent.mm.ui.conversation.recycler.ConversationRecyclerView`，
 * 直接 hook `RecyclerView.onTouchEvent(MotionEvent)`：只在会话列表上按滚动方向收放
 * 模块自己加的悬浮底栏（`CustomBottomBarFeature.FLOATING_BOTTOM_BAR_TAG`）。
 *
 * 底栏是模块自己 addView 进去的，所以这里改它的 translationY 不会和微信抢同一个
 * 属性；找不到底栏（用户没开悬浮底栏）就整条链路空转。
 */
class BottomBarAutoHideFeature : BaseFeature() {
    private var runtime: BottomBarAutoHideRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "悬浮底栏滑动隐藏"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(BottomBarAutoHideSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = BottomBarAutoHideRuntime(context, ::logFeatureError)
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
        const val ID = "bottom_bar_auto_hide"
    }
}

private class BottomBarAutoHideRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val bars = Collections.synchronizedMap(WeakHashMap<Activity, View?>())
    private val hidden = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /** 每个列表上一次的竖向滚动偏移，用来算这一次滚了多少。 */
    private val lastOffsets = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** `computeVerticalScrollOffset` 是保留名，滚动偏移只能靠它读。 */
    private val verticalScrollOffsetMethod: Method? by lazy {
        KavaReflector.loadClass(
            "androidx.recyclerview.widget.RecyclerView",
            context.hostClassLoader()
        )?.let { clazz ->
            KavaReflector.findMethodRecursive(clazz, "computeVerticalScrollOffset")
        }
    }

    private val cachePrefs = DexMethodCache.prefs(
        context.hostContext(),
        "Hchat_bottom_bar_auto_hide_cache"
    )
    private var installed = false

    private val enabled = HotConfig.flag(
        BottomBarAutoHideFeature.ID,
        BottomBarAutoHideSettings.KEY_ENABLED,
        BottomBarAutoHideSettings.DEFAULT_ENABLED
    ) {
        context.configStore().getBoolean(
            BottomBarAutoHideFeature.ID,
            BottomBarAutoHideSettings.KEY_ENABLED,
            BottomBarAutoHideSettings.DEFAULT_ENABLED
        )
    }

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        // 日志里的「找不到 onScrolled」根因：onScrolled 是 OnScrollListener **接口**
        // （w2）的方法，RecyclerView 类本身没有，反射永远拿不到。
        // scrollBy / onScrollChanged 也不通用：触摸滚动走内部 scrollByInternal，
        // 手指滑动不一定经过公开 scrollBy；onScrollChanged 只有重写过的子类才有。
        // 因此改为拦 onTouchEvent（真实保留名，滚动必经），用
        // computeVerticalScrollOffset() 的差值判断方向 —— 该方法同样是保留名。
        val viewClass = KavaReflector.loadClass(
            "androidx.recyclerview.widget.RecyclerView",
            context.hostClassLoader()
        ) ?: run {
            logger("悬浮底栏滑动隐藏：找不到 RecyclerView", null)
            return false
        }
        val method = locateOnTouchEvent(viewClass) ?: run {
            logger("悬浮底栏滑动隐藏：找不到 onTouchEvent", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleTouch(param)
                }
            })
            installed = true
            logger("悬浮底栏滑动隐藏：已挂载 onTouchEvent", null)
            true
        }.getOrElse {
            logger("悬浮底栏滑动隐藏 Hook 安装失败", it)
            false
        }
    }

    fun destroy() {
        installed = false
        bars.clear()
        hidden.clear()
        lastOffsets.clear()
    }

    private fun locateOnTouchEvent(viewClass: Class<*>): Method? {
        val cacheKey = DexMethodCache.runtimeKey(
            context.hostContext(),
            context.hostClassLoader()
        )
        DexMethodCache.load(cachePrefs, cacheKey, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf { isOnTouchEvent(it, viewClass) }
            ?.let { return it }
        val method = KavaReflector.findMethodRecursive(
            viewClass,
            "onTouchEvent",
            android.view.MotionEvent::class.java
        )?.takeIf { isOnTouchEvent(it, viewClass) }
        if (method == null) {
            DexMethodCache.clear(cachePrefs, cacheKey, CACHE_KEY)
        } else {
            DexMethodCache.save(cachePrefs, cacheKey, CACHE_KEY, method)
        }
        return method
    }

    private fun isOnTouchEvent(method: Method, viewClass: Class<*>): Boolean {
        if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) return false
        if (!viewClass.isAssignableFrom(method.declaringClass)) return false
        return method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == android.view.MotionEvent::class.java &&
            method.returnType == Boolean::class.javaPrimitiveType
    }

    /**
     * 用滚动偏移的差值判断方向。
     *
     * RecyclerView 滚动时 `computeVerticalScrollOffset()` 会变化；每次触摸事件
     * 与上次记录的值比较即可得到「这一下滚了多少」。手指停下（无位移）就不动，
     * 所以不会因为惯性滚动反复收放。
     */
    private fun handleTouch(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val view = param.thisObject as? View ?: return
        if (!isConversationList(view)) return
        val event = param.args?.getOrNull(0) as? android.view.MotionEvent ?: return
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lastOffsets[view] = scrollOffset(view)
                return
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                lastOffsets.remove(view)
                return
            }
        }
        val current = scrollOffset(view) ?: return
        val previous = lastOffsets[view] ?: run {
            lastOffsets[view] = current
            return
        }
        if (current == previous) return
        lastOffsets[view] = current
        val dy = current - previous
        val activity = view.context as? Activity ?: return
        val bar = findBar(activity) ?: return
        when {
            dy >= HIDE_THRESHOLD -> hide(bar)
            dy <= -SHOW_THRESHOLD -> show(bar)
        }
    }

    /** 读竖向滚动偏移；拿不到返回 null（不影响其它逻辑）。 */
    private fun scrollOffset(view: View): Int? {
        val method = verticalScrollOffsetMethod ?: return null
        return runCatching { KavaReflector.invoke(method, view) as? Number }
            .getOrNull()?.toInt()
    }

    /** 只处理首页会话列表，避免影响聊天、朋友圈里的其它 RecyclerView。 */
    private fun isConversationList(view: View): Boolean {
        return view.javaClass.name.contains("conversation", ignoreCase = true)
    }

    private fun findBar(activity: Activity): View? {
        bars[activity]?.let { cached ->
            if (cached == null || cached.isAttachedToWindow) return cached
        }
        val decor = activity.window?.decorView as? View ?: return null
        val bar = decor.findViewWithTag<View>(CustomBottomBarFeature.FLOATING_BOTTOM_BAR_TAG)
        bars[activity] = bar
        return bar
    }

    private fun hide(bar: View) {
        if (bar.visibility != View.VISIBLE) return
        if (hidden[bar] == true) return
        if (bar.height <= 0) return
        hidden[bar] = true
        val offset = bar.height + (bar.resources.displayMetrics.density * SLIDE_EXTRA_DP)
        bar.animate()
            .translationY(offset)
            .setDuration(SLIDE_DURATION_MS)
            .start()
    }

    private fun show(bar: View) {
        if (hidden[bar] != true) return
        hidden[bar] = false
        bar.animate()
            .translationY(0f)
            .setDuration(SLIDE_DURATION_MS)
            .start()
    }

    private companion object {
        const val CACHE_KEY = "recycler_touch_v3"
        const val HIDE_THRESHOLD = 8
        const val SHOW_THRESHOLD = 8
        const val SLIDE_DURATION_MS = 180L
        const val SLIDE_EXTRA_DP = 8f
    }
}
