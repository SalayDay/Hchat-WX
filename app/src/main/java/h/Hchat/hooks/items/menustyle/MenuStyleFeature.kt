package h.Hchat.hooks.items.menustyle

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.PopupWindow
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector

/**
 * 长按菜单磨砂卡片化。
 *
 * 微信的长按菜单是 `PopupWindow` 承载的自绘视图，这里不去改它的内部布局（那是按
 * 版本逐个 view 做手术），只在弹出时把容器的背景换成圆角半透明卡片，并留出内边距。
 * 只处理**内容视图类名含 Menu** 的弹窗，其它 PopupWindow（输入法候选、下拉框等）
 * 完全不碰，避免误伤。
 */
class MenuStyleFeature : BaseFeature() {
    private var runtime: MenuStyleRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "长按菜单磨砂卡片"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MenuStyleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MenuStyleRuntime(context, ::logFeatureError)
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
    private var installed = false

    fun install(): Boolean {
        if (installed) return true
        val popupClass = KavaReflector.loadClass(
            "android.widget.PopupWindow",
            context.hostClassLoader()
        ) ?: run {
            logger("菜单美化：找不到 PopupWindow", null)
            return false
        }
        val methods = listOfNotNull(
            KavaReflector.findMethodRecursive(
                popupClass,
                "showAtLocation",
                View::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            ),
            KavaReflector.findMethodRecursive(
                popupClass,
                "showAsDropDown",
                View::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            )
        )
        if (methods.isEmpty()) {
            logger("菜单美化：找不到弹窗显示方法", null)
            return false
        }
        var hooked = 0
        methods.forEach { method ->
            runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        val popup = param.thisObject as? PopupWindow ?: return
                        runCatching { stylePopup(popup) }.onFailure {
                            logger("菜单美化：套用样式失败", it)
                        }
                    }
                })
                hooked++
            }.onFailure {
                logger("菜单美化：Hook 安装失败 ${method.name}", it)
            }
        }
        installed = hooked > 0
        return installed
    }

    fun destroy() {
        installed = false
    }

    private fun enabled(): Boolean =
        prefs.getBoolean(MenuStyleFeature.KEY_ENABLED, MenuStyleFeature.DEFAULT_ENABLED)

    private fun stylePopup(popup: PopupWindow) {
        val content = popup.contentView ?: return
        if (!content.javaClass.name.contains("menu", ignoreCase = true)) return
        val resources = content.context.resources
        val density = resources.displayMetrics.density
        val radius = density * 16f
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val card = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (dark) 0xF22B2B2B.toInt() else 0xF2FFFFFF.toInt())
            // 极淡的描边，避免纯色块贴在背景上看不出边界。
            setStroke(
                (density * 0.5f).toInt().coerceAtLeast(1),
                if (dark) 0x33FFFFFF else 0x14000000
            )
        }
        // 弹窗外层背景必须透明，否则圆角外面会露出原来的矩形底。
        popup.setBackgroundDrawable(ColorDrawable(0x00000000))
        val padding = (density * 8f).toInt()
        content.setBackgroundDrawable(card)
        content.setPadding(padding, padding, padding, padding)
        // WRAP_CONTENT(-2)/MATCH_PARENT(-1) 不能做加法，只有固定宽度才补。
        if (popup.width > 0) popup.width = popup.width + padding * 2
    }
}

class MenuStyleSettingsProvider : h.Hchat.ui.SimpleFeatureSettingsProvider(
    MenuStyleFeature.ID,
    "长按菜单磨砂卡片",
    "长按消息弹出的菜单改为圆角半透明卡片",
    h.Hchat.ui.FeatureSettingsProvider.CATEGORY_PRACTICAL
)