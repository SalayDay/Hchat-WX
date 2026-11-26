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
 * 直接 hook `RecyclerView.onScrolled(int, int)`：只在会话列表上按滚动方向收放
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
        val recyclerClass = KavaReflector.loadClass(
            "androidx.recyclerview.widget.RecyclerView",
            context.hostClassLoader()
        ) ?: run {
            logger("悬浮底栏滑动隐藏：找不到 RecyclerView", null)
            return false
        }
        val method = locateOnScrolled(recyclerClass) ?: run {
            logger("悬浮底栏滑动隐藏：找不到 onScrolled", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleScroll(param)
                }
            })
            installed = true
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
    }

    private fun locateOnScrolled(recyclerClass: Class<*>): Method? {
        val cacheKey = DexMethodCache.runtimeKey(
            context.hostContext(),
            context.hostClassLoader()
        )
        DexMethodCache.load(cachePrefs, cacheKey, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf { isOnScrolled(it, recyclerClass) }
            ?.let { return it }
        val method = listOf("onScrolled", "onScrolledCompat").firstNotNullOfOrNull { name ->
            KavaReflector.findMethodRecursive(
                recyclerClass,
                name,
                Integer.TYPE,
                Integer.TYPE
            )?.takeIf { isOnScrolled(it, recyclerClass) }
        }
        if (method == null) {
            DexMethodCache.clear(cachePrefs, cacheKey, CACHE_KEY)
        } else {
            DexMethodCache.save(cachePrefs, cacheKey, CACHE_KEY, method)
        }
        return method
    }

    private fun isOnScrolled(method: Method, recyclerClass: Class<*>): Boolean {
        if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) return false
        if (!recyclerClass.isAssignableFrom(method.declaringClass)) return false
        val types = method.parameterTypes
        return types.size == 2 &&
            types[0] == Integer.TYPE &&
            types[1] == Integer.TYPE &&
            method.returnType == Void.TYPE
    }

    private fun handleScroll(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val recycler = param.thisObject as? View ?: return
        if (!isConversationList(recycler)) return
        val dy = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
        val activity = recycler.context as? Activity ?: return
        val bar = findBar(activity) ?: return
        when {
            dy >= HIDE_THRESHOLD -> hide(bar)
            dy <= -SHOW_THRESHOLD -> show(bar)
        }
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
        const val CACHE_KEY = "recycler_on_scrolled_v1"
        const val HIDE_THRESHOLD = 8
        const val SHOW_THRESHOLD = 8
        const val SLIDE_DURATION_MS = 180L
        const val SLIDE_EXTRA_DP = 8f
    }
}