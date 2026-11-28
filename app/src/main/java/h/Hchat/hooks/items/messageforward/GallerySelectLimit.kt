package h.Hchat.hooks.items.messageforward

import android.app.Activity
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method

/**
 * 解除「一次最多发 9 张图」的限制。
 *
 * 限制不在发送环节，而在相册选择器读参数的时候：
 *
 * ```
 * intent.getIntExtra("max_select_count", 9)
 * ```
 *
 * 微信自己的发图入口（聊天「+」→ 相册、最近图片气泡）根本不传这个 extra，
 * 所以只要没人改，选择器就按默认值 9 张封顶。
 *
 * 这里的做法是在两个选择器读取参数**之前**，把 Intent 里的上限改成更大的值：
 * 有人在 Intent 里显式写了别的数字（例如选头像只允许 1 张）就原样放过，
 * 只改「没传」和「等于默认 9」这两种情况，避免影响别的场景。
 */
internal class GallerySelectLimit(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), MessageForwardSettings.PREFS_NAME)

    /**
     * 安装代次。DexInstallScheduler 会在「功能安装」和「DexReady」各调度一次，
     * 没有这个判断就会对同一个方法挂两遍回调（改写是幂等的，但纯属浪费）。
     */
    @Volatile private var installedGeneration = -1L

    fun install(): Boolean {
        if (HookRegistry.get().isInstallationGenerationActive(installedGeneration)) return true
        val generation = HookRegistry.get().installationGeneration()
        val album = hookClass(ALBUM_PREVIEW_UI, INIT_VIEW)
        val entry = hookClass(GALLERY_ENTRY_UI, ON_RESUME)
        if (!album && !entry) {
            logger("图片数量限制Hook未安装（未找到相册入口）", null)
            return false
        }
        installedGeneration = generation
        return true
    }

    private fun hookClass(className: String, methodName: String): Boolean {
        val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return false
        val method = findMethod(clazz, methodName) ?: return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, GalleryEntryHook(this))
            true
        }.onFailure {
            logger("$className#$methodName Hook 安装失败", it)
        }.getOrDefault(false)
    }

    private class GalleryEntryHook(private val owner: GallerySelectLimit) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val activity = chain.thisObject as? Activity
            if (owner.enabled() && activity != null) owner.rewrite(activity)
            return chain.proceed()
        }
    }

    private fun findMethod(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun rewrite(activity: Activity) {
        val intent: Intent = activity.intent ?: return
        // 朋友圈九宫格（is_from_sns_comment）和视频号动态（album_from_finder_add_media）
        // 都是产品规则上的上限，不是"转发限制"。放开会把它们的 9 张变成 300 张，
        // 直接破坏发布流程，所以这两类入口原样放过。
        if (intent.hasExtra(EXTRA_FROM_SNS_COMMENT)) return
        if (intent.hasExtra(EXTRA_FROM_FINDER)) return
        val current = if (intent.hasExtra(MAX_SELECT_COUNT)) {
            intent.getIntExtra(MAX_SELECT_COUNT, WECHAT_DEFAULT_LIMIT)
        } else {
            WECHAT_DEFAULT_LIMIT
        }
        // 只放开「没传」和「正好等于微信默认 9」两种情况；其它数字是调用方
        // 有意设定的（例如只允许选 1 张），改大反而会破坏原场景。
        if (current != WECHAT_DEFAULT_LIMIT) return
        runCatching { intent.putExtra(MAX_SELECT_COUNT, EXPANDED_LIMIT) }
    }

    private fun enabled(): Boolean =
        prefs.getBoolean(MessageForwardSettings.KEY_GALLERY_LIMIT, false)

    private companion object {
        const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
        const val GALLERY_ENTRY_UI = "com.tencent.mm.plugin.gallery.ui.GalleryEntryUI"
        const val INIT_VIEW = "initView"
        const val ON_RESUME = "onResume"
        const val MAX_SELECT_COUNT = "max_select_count"
        const val WECHAT_DEFAULT_LIMIT = 9

        /** 朋友圈发图入口的标记（SnsCommentFooter 写入）。 */
        const val EXTRA_FROM_SNS_COMMENT = "is_from_sns_comment"

        /** 视频号动态发图入口的标记（FinderAlbumUI 读取）。 */
        const val EXTRA_FROM_FINDER = "album_from_finder_add_media"

        /**
         * 不是真正的"无限"：一次选几百张原图会把内存和发送队列打满，
         * 300 张已经远超实际使用，同时留了安全边界。
         */
        const val EXPANDED_LIMIT = 300
    }
}
