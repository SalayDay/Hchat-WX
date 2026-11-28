package h.Hchat.hooks.items.theme

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.util.Collections
import java.util.WeakHashMap

/**
 * 聊天界面沉浸化。
 *
 * 把聊天页的标题栏（ActionBar 一类）和输入面板（ChatFooter）背景清成透明，
 * 让「全局背景」能从聊天页透出来，同时把聊天窗口自身的底色也清掉。
 *
 * **没有**把消息列表改成 `clipToPadding=false` 让它从栏下穿过：那需要按版本拿到
 * 列表真实 id 与栏高度做 padding 补偿，盲改会把消息顶到栏上或留出空洞。
 * 这一版只做「栏透明 + 窗口透明」，视觉上已经是三者同底，且不动物流。
 */
class ChatImmersionFeature : BaseFeature() {
    private var runtime: ChatImmersionRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "聊天沉浸式"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ChatImmersionSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        // 安装结果一律走 boot 缓冲：功能在启动期安装，而日志采集通常是之后才打开。
        runtime = ChatImmersionRuntime(context) { message, throwable ->
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
        const val ID = "chat_immersion"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = false
        const val CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI"
    }
}

private class ChatImmersionRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = context.configStore().getFeaturePrefs(ChatImmersionFeature.ID)
    private val originals = Collections.synchronizedMap(
        WeakHashMap<View, Drawable?>()
    )
    private var installed = false

    fun install(): Boolean {
        if (installed) return true
        val chattingUi = KavaReflector.loadClass(
            ChatImmersionFeature.CHATTING_UI,
            context.hostClassLoader()
        ) ?: run {
            logger("聊天沉浸式：找不到 ChattingUI", null)
            return false
        }
        val method = KavaReflector.findMethodRecursive(chattingUi, "onResume")
            ?: run {
                logger("聊天沉浸式：找不到 ChattingUI.onResume", null)
                return false
            }
        return runCatching {
            HookRegistry.get().hook(method, ResumeHooker(this))
            installed = true
            logger("聊天沉浸式：已挂载 ChattingUI#onResume，开关 ${if (enabled()) "开" else "关"}", null)
            true
        }.getOrElse {
            logger("聊天沉浸式 Hook 安装失败", it)
            false
        }
    }

    private class ResumeHooker(private val runtime: ChatImmersionRuntime) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val activity = chain.thisObject as? Activity ?: return result
            activity.window?.decorView?.post { runtime.applyTo(activity) }
            return result
        }
    }

    fun destroy() {
        installed = false
        originals.clear()
    }

    private fun enabled(): Boolean =
        prefs.getBoolean(ChatImmersionFeature.KEY_ENABLED, ChatImmersionFeature.DEFAULT_ENABLED)

    private fun applyTo(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (!enabled()) {
            restore(activity, decor)
            return
        }
        val targets = collectTargets(activity, decor)
        if (targets.isEmpty()) return
        // 只把栏本身透明化，让**聊天背景**从栏下透出来。
        // 不清窗口/内容层底色：清了会露出系统窗口色（白色或黑色），
        // 反而把微信自己的聊天壁纸盖掉，观感更差。
        targets.forEach { view ->
            if (!originals.containsKey(view)) originals[view] = view.background
            view.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun restore(activity: Activity, decor: ViewGroup) {
        val snapshot = synchronized(originals) { originals.keys.toList() }
        snapshot.forEach { view ->
            if (originals.containsKey(view)) view.background = originals.remove(view)
        }
    }

    /**
     * 找标题栏与输入面板：按类名特征识别，加上位置约束避开误伤
     * （顶部 1/5 才算标题栏，底部 1/3 才算输入面板）。
     */
    private fun collectTargets(activity: Activity, decor: ViewGroup): List<View> {
        val screenHeight = activity.resources.displayMetrics.heightPixels
        val out = mutableListOf<View>()
        traverse(decor) { view ->
            val name = view.javaClass.name
            if (view.height <= 0 || view.visibility != View.VISIBLE) return@traverse
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val top = location[1]
            val bottom = top + view.height
            val isBar = name.contains("ActionBar", ignoreCase = true) ||
                name.contains("MMTitle", ignoreCase = true)
            val isFooter = name.contains("ChatFooter", ignoreCase = true)
            when {
                isBar && top < screenHeight / 5 -> out += view
                isFooter && bottom > screenHeight * 2 / 3 -> out += view
            }
        }
        return out
    }

    private fun traverse(root: View, visitor: (View) -> Unit) {
        visitor(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                traverse(root.getChildAt(index), visitor)
            }
        }
    }
}

class ChatImmersionSettingsProvider : h.Hchat.ui.SimpleFeatureSettingsProvider(
    ChatImmersionFeature.ID,
    "聊天沉浸式",
    "聊天标题栏与输入面板透明，和全局背景融为一体",
    h.Hchat.ui.FeatureSettingsProvider.CATEGORY_PRACTICAL
)