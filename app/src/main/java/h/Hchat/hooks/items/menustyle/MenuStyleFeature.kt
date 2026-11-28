package h.Hchat.hooks.items.menustyle

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.PopupWindow
import h.Hchat.event.Events
import h.Hchat.hooks.api.message.ChatMenuPopupLocator
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** 聊天消息长按菜单的圆角半透明卡片样式。 */
class MenuStyleFeature : BaseFeature() {
    private var runtime: MenuStyleRuntime? = null

    override fun featureId(): String = ID
    override fun name(): String = "长按菜单磨砂卡片"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MenuStyleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MenuStyleRuntime(context) { message, throwable ->
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

    companion object {
        const val ID = "menu_style"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = false
    }
}

private class MenuStyleRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = context.configStore().getFeaturePrefs(MenuStyleFeature.ID)
    private val displayScope = ChatMenuDisplayScope<View>()
    private val registeredMethods = HashSet<Method>()
    private val styles = Collections.synchronizedMap(WeakHashMap<PopupWindow, OriginalStyle>())
    private val diagnostics = Collections.synchronizedSet(HashSet<String>())
    @Volatile private var installed = false
    @Volatile private var destroyed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val openMethods = ChatMenuPopupLocator.openMethods(context, logger)
        if (openMethods.isEmpty()) {
            logger("菜单美化：未找到已验证的聊天长按打开入口，未启用弹窗处理", null)
            return false
        }
        val showMethods = listOfNotNull(
            KavaReflector.findMethodRecursive(
                PopupWindow::class.java, "showAtLocation", View::class.java,
                Integer.TYPE, Integer.TYPE, Integer.TYPE
            ),
            KavaReflector.findMethodRecursive(
                PopupWindow::class.java, "showAsDropDown", View::class.java,
                Integer.TYPE, Integer.TYPE, Integer.TYPE
            )
        ).distinct()
        if (showMethods.isEmpty()) {
            logger("菜单美化：找不到 PopupWindow 显示方法", null)
            return false
        }
        destroyed = false
        var openCount = 0
        var showCount = 0
        for (method in openMethods) {
            if (register(method, ChatMenuOpenHooker(this))) openCount++
        }
        for (method in showMethods) {
            if (register(method, PopupShowHooker(this))) showCount++
        }
        // 部分失败可以由安装调度器重试，已经注册的 Method 不重复挂载。
        installed = openCount == openMethods.size && showCount == showMethods.size
        if (installed) {
            logger("菜单美化：聊天菜单打开入口 ×$openCount，弹窗显示 ×$showCount，" +
                "开关 ${if (enabled()) "开" else "关"}", null)
        }
        return installed
    }

    private fun register(method: Method, hooker: XposedInterface.Hooker): Boolean {
        if (method in registeredMethods) return true
        return runCatching {
            HookRegistry.get().hook(method, hooker)
            registeredMethods.add(method)
            true
        }.getOrElse {
            logger("菜单美化：Hook 安装失败 ${method.toGenericString()}", it)
            false
        }
    }

    private class ChatMenuOpenHooker(private val runtime: MenuStyleRuntime) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val anchor = chain.args.firstOrNull() as? View ?: return chain.proceed()
            if (runtime.destroyed || !runtime.enabled()) return chain.proceed()
            // 逆向已确认菜单在此调用内部同步显示。先建立上下文再执行，finally 清除；
            // 不 Hook View.performLongClick，不保留“任意长按后两秒”的广域窗口。
            return runtime.displayScope.during(anchor) { chain.proceed() }
        }
    }

    private class PopupShowHooker(private val runtime: MenuStyleRuntime) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val popup = chain.thisObject as? PopupWindow ?: return chain.proceed()
            if (runtime.destroyed || !runtime.enabled()) {
                runtime.restore(popup)
                return chain.proceed()
            }
            val origin = runtime.displayScope.current() ?: return chain.proceed()
            val hostAnchor = chain.args.firstOrNull() as? View ?: return chain.proceed()
            // 仅处理当前聊天窗口中、已验证长按打开入口内部同步出现的弹窗。
            if (origin.rootView !== hostAnchor.rootView) return chain.proceed()
            runCatching { runtime.applyCard(popup) }.onFailure {
                runtime.logger("菜单美化：套用样式失败", it)
            }
            return chain.proceed()
        }
    }

    private class OriginalStyle(
        val content: java.lang.ref.WeakReference<View>,
        val background: Drawable?,
        val popupBackground: Drawable?,
        val width: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clipToOutline: Boolean
    )

    private fun applyCard(popup: PopupWindow) {
        val content = popup.contentView ?: return
        val previous = styles[popup]
        val original = if (previous != null && previous.content.get() === content) {
            previous
        } else {
            OriginalStyle(java.lang.ref.WeakReference(content), content.background,
                popup.background, popup.width, content.paddingLeft, content.paddingTop,
                content.paddingRight, content.paddingBottom, content.clipToOutline).also { styles[popup] = it }
        }
        val density = content.resources.displayMetrics.density
        val dark = (content.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val padding = (density * 8f).toInt()
        content.background = GradientDrawable().apply {
            cornerRadius = density * 16f
            setColor(if (dark) 0xF22B2B2B.toInt() else 0xF2FFFFFF.toInt())
            setStroke((density * 0.5f).toInt().coerceAtLeast(1),
                if (dark) 0x33FFFFFF else 0x14000000)
        }
        popup.setBackgroundDrawable(ColorDrawable(0x00000000))
        content.setPadding(original.left + padding, original.top + padding,
            original.right + padding, original.bottom + padding)
        // 永远从原始宽度计算，避免同一个弹窗反复打开不断变宽。
        if (original.width > 0) popup.width = original.width + padding * 2
        content.clipToOutline = true
        val signature = popup.javaClass.name + "/" + content.javaClass.name
        if (diagnostics.add(signature)) {
            logger("菜单美化：已应用聊天菜单卡片 popup=${popup.javaClass.name} " +
                "content=${content.javaClass.name}", null)
        }
    }

    private fun restore(popup: PopupWindow) {
        val original = styles.remove(popup) ?: return
        runCatching {
            original.content.get()?.let { content ->
                content.background = original.background
                content.clipToOutline = original.clipToOutline
                content.setPadding(original.left, original.top, original.right, original.bottom)
            }
            popup.setBackgroundDrawable(original.popupBackground)
            popup.width = original.width
        }.onFailure { logger("菜单美化：恢复原生样式失败", it) }
    }

    fun destroy() {
        destroyed = true
        installed = false
        val popups = synchronized(styles) { styles.keys.toList() }
        for (popup in popups) {
            val content = popup.contentView
            if (content != null && android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                content.post { restore(popup) }
            } else restore(popup)
        }
        registeredMethods.clear()
        diagnostics.clear()
    }

    private fun enabled(): Boolean =
        prefs.getBoolean(MenuStyleFeature.KEY_ENABLED, MenuStyleFeature.DEFAULT_ENABLED)
}

class MenuStyleSettingsProvider : h.Hchat.ui.SimpleFeatureSettingsProvider(
    MenuStyleFeature.ID,
    "长按菜单磨砂卡片",
    "长按消息弹出的菜单改为圆角半透明卡片",
    h.Hchat.ui.FeatureSettingsProvider.CATEGORY_PRACTICAL
)
