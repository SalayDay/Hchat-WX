package h.Hchat.hooks.items.antidetect

import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import h.Hchat.utils.SelfFrameRegistry
import java.lang.reflect.Method

/**
 * 隐藏模块调用栈（防封轮 · ①隐藏栈帧）。
 *
 * ## 为什么要做
 *
 * 微信的风控代码会在运行期遍历调用栈找痕迹，常见探针：
 *
 * ```java
 * for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
 *     if (e.getClassName().contains("xposed") || e.getClassName().contains("LSPHooker_")) ...
 * }
 * new Exception().getStackTrace()   // 同一实现，继承自 Throwable
 * ```
 *
 * LSPosed 的 hook 代理会在栈上留下两类痕迹：
 * - `de.robv.android.xposed.XposedBridge.handleHookedMethod` / `LSPHooker_xxx` 帧；
 * - LSPosed 生成的 `LSPHooker_` 前缀方法名。
 *
 * 本模块自身（`h.Hchat.*`）的帧同理——被 hook 的宿主方法回调里如果宿主抓栈，
 * 就能直接看到我们的类名。
 *
 * ## 做法
 *
 * hook `Thread#getStackTrace()` 与 `Throwable#getStackTrace()`（`new Exception()`
 * 走的就是后者），在**返回前**把这几类帧摘掉。摘帧只是隐藏"我们是谁"，
 * 不改变栈的结构与长度语义——正常帧一个不少，宿主自己的业务栈完全不受影响。
 *
 * 没有命中时原数组原样返回（引用相等短路），日常栈遍历几乎零开销。
 *
 * ## 边界
 *
 * - `Throwable#printStackTrace` 内部读的是私有字段，不走公开的 getStackTrace()，
 *   覆盖不到；但用 printStackTrace 做"逻辑判定"的探针没有（它只是打日志）。
 * - `Thread#getAllStackTraces()` 内部逐线程调用公开的 getStackTrace()，天然被覆盖。
 * - hook 是常驻的，开关关闭时过滤直接放行（每次调用读一次配置，共享内存读）。
 */
class StackTraceGuardFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "隐藏模块调用栈"

    /**
     * 每次抓取调用栈都会读一次开关。热路径上不能直接读配置文件
     * （拼字符串 + FastKV 取锁），走 3 秒 TTL 的进程内缓存。
     */
    @Volatile
    private var enabled: () -> Boolean = { DEFAULT_ENABLED }

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            object : SimpleFeatureSettingsProvider(
                ID,
                name(),
                "微信遍历调用栈探测 hook 痕迹时，自动摘掉 Xposed/LSPosed 与 Hchat 自身的栈帧。" +
                    "默认开启，无需任何配置。",
                FeatureSettingsProvider.CATEGORY_ENHANCE
            ) {}
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val config = context.configStore()
        enabled = HotConfig.flag(ID, KEY_ENABLED, DEFAULT_ENABLED) {
            config.getBoolean(ID, KEY_ENABLED, DEFAULT_ENABLED)
        }
        val stackHook = StackTraceHooker(this)

        var installed = 0
        for (owner in arrayOf(Thread::class.java, Throwable::class.java)) {
            val methods = KavaReflector.declaredMethods(owner)
                .filter { it.name == "getStackTrace" && it.parameterTypes.isEmpty() }
            for (method in methods) {
                try {
                    HookRegistry.get().hook(method, stackHook)
                    installed++
                } catch (e: Throwable) {
                    logError("挂载 ${owner.name}#${method.name} 失败", e)
                }
            }
        }
        if (installed == 0) {
            logError("getStackTrace 锚点一个都没挂上（微信 ${wechatVersion()}）", null)
            return
        }
        HLog.e("$TAG 栈帧过滤已挂载 owners=2 methods=$installed，微信 ${wechatVersion()}")
    }

    private fun wechatVersion(): String = WeChatApis.version()?.current()?.versionName ?: "未知"

    companion object {
        const val ID = "anti_detect_stack"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = true

        private const val TAG = "[Hchat:AntiDetectStack]"
    }

    private class StackTraceHooker(private val owner: StackTraceGuardFeature) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val args = chain.args.toTypedArray()
            val receiver = chain.thisObject
            var result = chain.proceed()
            with(owner) {
            run update@{
                if (!enabled()) return@update
                // 回调挂在 Throwable#getStackTrace 上，LSPosed 记录 hook 异常日志时
                // 会走同一条路；这里任何意外都不能再抛，否则框架错误处理自激。
                runCatching {
                    val stack = result as? Array<StackTraceElement> ?: return@update
                    // release 下自身类名被 R8 改短，必须叠加运行时登记表才拦得住回调帧。
                    val cleaned = StackFramePolicy.clean(stack) { SelfFrameRegistry.contains(it) }
                    if (cleaned !== stack) result = cleaned
                }
            }
            return result
            }
        }
    }

}
