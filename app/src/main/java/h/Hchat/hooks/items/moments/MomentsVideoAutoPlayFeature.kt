package h.Hchat.hooks.items.moments

import android.content.Context
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.util.HashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 朋友圈视频禁止自动播放。
 *
 * ## 挂在「自动播放判定方法」上
 *
 * 朋友圈信息流的视频自动播放不是播放器自己决定的，而是进页面时先算一个
 * 「本次是否允许自动播放」的布尔值，再交给列表使用。这个判定方法的返回值就是
 * 最终的自动播放开关，把它改成 false 即可，无需触碰播放器状态。
 *
 * ## 8.0.80 的实际结构（离线反编译官方包确认）
 *
 * 微信 7.x 时代这段逻辑在 SnsTimeLineUI 里，方法返回 void、把结果写进实例字段；
 * 8.0.80 起重构为两个返回布尔值的独立方法：
 *
 *   1. `SnsAutoPlayUtil.checkAutoPlay(SnsNetworkMgr)` → `Z`
 *      混淆名 `pm4/e`，方法 `a(Lcom/tencent/mm/plugin/sns/ui/wo;)Z`，
 *      日志 tag `MicroMsg.SnsAutoPlayUtil`，被 sns.ui.uo 与 SnsWsFoldDetailUI 调用。
 *   2. `ImproveAutoPlayManager$autoPlay$2.invoke()` → `Ljava/lang/Object`（Boxed Boolean）
 *      混淆名 `fl4/b`，只被 `fl4/d`（ImproveAutoPlayManager）引用，是新版信息流主路径。
 *
 * 两者方法体都同时包含下面三条日志字符串，从 7.x 保留至今：
 *
 *   "autoPlay DynamicConfig false" / "2G/3G autoplay false" / "wifi autoplay true"
 *
 * 其中 `wifi autoplay true` 正是「WiFi 下强制自动播放」分支——微信自带的
 * 「移动网络下视频自动播放」开关在 WiFi 下完全无效，就是因为这个分支写死返回 true。
 *
 * ## 关键教训：不能用包名过滤
 *
 * 微信混淆后类名被搬到根包（`Lpm4/e;`、`Lfl4/b;`），**不再位于**
 * `com.tencent.mm.plugin.sns.*` 下。任何 `startsWith("com.tencent.mm.plugin.sns.")`
 * 的过滤都会把目标类全部筛掉，表现就是「永远未定位到」。因此这里只靠
 * 「三条日志字符串 AND + 返回类型」定位，包名仅作为优先级提示，不做硬过滤。
 *
 * ## 开销控制
 *
 * - 开关关闭（默认）：启动只读一次 SharedPreferences 就返回，不扫 dex、不挂 hook。
 * - 开关打开：DexKit 扫描一次（后台线程），结果按 微信版本+APK+Tinker 落盘缓存。
 * - Hook 热路径：只读一个 @Volatile 布尔缓存，不碰 SharedPreferences；
 *   不持有 Bitmap / MediaPlayer，无内存常驻。
 */
class MomentsVideoAutoPlayFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "禁止朋友圈视频自动播放"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsVideoAutoPlaySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        // 只保存引用，不做扫描：让设置页刚打开开关时可以立刻装 hook，不必等微信重启。
        appContext = context.hostContext().applicationContext ?: context.hostContext()
        bridge = context.dexKitBridge()
        classLoader = context.hostClassLoader()
        enabledState = -1

        // 走统一调度而不是直接同步安装：Feature 安装阶段 DexKit 可能还没就绪，
        // 直接调会静默失败且无人重试。调度器会在 BRIDGE 就绪后执行，并做有限重试。
        DexInstallScheduler.schedule(
            key = AUTO_KEY,
            label = "朋友圈视频自动播放",
            stage = DexInstallScheduler.Stage.BRIDGE
        ) { installHooks() }
    }

    companion object {
        const val ID = "moments_video_autoplay"

        private const val TAG = "[Hchat:SnsAutoPlay]"

        private const val AUTO_KEY = "moments_video_autoplay_auto"
        private const val MANUAL_KEY_PREFIX = "moments_video_autoplay_manual_"

        /** 仅用于「优先尝试」，不做硬过滤（混淆后目标类不在这个包下）。 */
        private const val SNS_PACKAGE = "com.tencent.mm.plugin.sns."

        /** 命中多个方法时最多 hook 几个，防止误伤。 */
        private const val MAX_TARGET_METHODS = 8

        /** 拦截日志最多打印几次，避免刷屏（只影响日志，不影响拦截）。 */
        private const val MAX_HIT_LOGS = 3

        /** 拦截计数写盘节流间隔。 */
        private const val HIT_WRITE_INTERVAL_MS = 10_000L

        /**
         * 从强到弱依次尝试的锚点组合（同一方法内 AND 语义）。
         *
         * 首选三条同时命中——8.0.80 的两个判定方法都同时含这三条，几乎不可能误命中；
         * 后续组合是版本裁剪日志时的兜底。这里刻意不放单字符串锚点：去掉包名硬过滤后，
         * 单个 "wifi autoplay true" 可能命中其他模块的无关方法。
         */
        private val ANCHOR_GROUPS: List<List<String>> = listOf(
            listOf(
                "autoPlay DynamicConfig false",
                "wifi autoplay true",
                "2G/3G autoplay false"
            ),
            listOf("autoPlay DynamicConfig false", "wifi autoplay true"),
            listOf("autoPlay DynamicConfig false", "2G/3G autoplay false"),
            listOf("wifi autoplay true", "2G/3G autoplay false")
        )

        private val installedLoaders = HashSet<String>()
        private val hitLogCount = AtomicInteger(0)
        private val loggedMissing = AtomicBoolean(false)
        private val manualSeq = AtomicInteger(0)
        /** 累计拦截次数与写盘节流时钟（都跨 hook 回调并发访问）。 */
        private val HIT_COUNT = AtomicInteger(0)
        private val lastHitWriteAtMs = AtomicLong(0L)

        @Volatile private var bridge: DexKitBridge? = null
        @Volatile private var classLoader: ClassLoader? = null
        @Volatile private var appContext: Context? = null

        /**
         * 开关状态缓存：-1 未知 / 0 关 / 1 开。
         * hook 热路径每次调用都要判断开关，不能每次都读 SharedPreferences。
         * 用 Int 而不是 Boolean?，避免每次读取都发生装箱。
         */
        @Volatile private var enabledState = -1

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            val cached = enabledState
            if (cached >= 0) return cached == 1
            val ctx = context ?: appContext ?: return false
            val value = try {
                HchatStorage.preferences(
                    ctx,
                    MomentsVideoAutoPlaySettings.PREFS_NAME
                ).getBoolean(
                    MomentsVideoAutoPlaySettings.KEY_DISABLE_AUTOPLAY,
                    MomentsVideoAutoPlaySettings.DEFAULT_DISABLE_AUTOPLAY
                )
            } catch (e: Throwable) {
                HLog.e("$TAG 读取开关失败: ${e.message}")
                MomentsVideoAutoPlaySettings.DEFAULT_DISABLE_AUTOPLAY
            }
            enabledState = if (value) 1 else 0
            return value
        }

        /**
         * 设置页拨开关后调用。设置页与微信同进程、且微信早已启动完毕，
         * 此时 DexKit 必然就绪，直接安装即可，不必再排队等 bridge
         * （排队还会被调度器里「已 installed」的标记挡住，导致拨开关无效）。
         */
        @JvmStatic
        fun onPreferenceChanged(enabled: Boolean) {
            enabledState = if (enabled) 1 else 0
            if (!enabled) return
            // 扫描 dex 要几百毫秒到几秒，不能压在设置页 UI 线程上。
            // 用递增 key 保证每次拨开都是一次全新调度，不会被历史 installed 挡住。
            val key = MANUAL_KEY_PREFIX + manualSeq.incrementAndGet()
            DexInstallScheduler.schedule(
                key = key,
                label = "朋友圈视频自动播放",
                stage = DexInstallScheduler.Stage.BRIDGE,
                priority = -1
            ) { installHooks() }
        }

        /**
         * @return true 表示本次已处理完毕（装上，或确认当前版本定位不到 / 开关是关的）；
         *         false 表示条件还不满足（上下文或 DexKit 未就绪），值得稍后重试。
         */
        @JvmStatic
        fun installHooks(): Boolean {
            val hostContext = appContext ?: return false
            val bridge = bridge ?: return false
            val loader = classLoader ?: return false

            if (!isEnabled(hostContext)) {
                // 开关关着：不扫 dex、不挂 hook。返回 true 表示本轮无需重试。
                // 用户之后拨开开关会走 onPreferenceChanged 的独立调度。
                writeStatus(hostContext, MomentsVideoAutoPlaySettings.STATUS_DISABLED)
                return true
            }

            val loaderKey = "${loader.javaClass.name}@${System.identityHashCode(loader)}"
            synchronized(installedLoaders) {
                if (installedLoaders.contains(loaderKey)) return true

                HLog.e("$TAG 开始定位：微信 ${wechatVersion(hostContext)}，loader=$loaderKey")

                val methods = resolveJudgeMethods(bridge, loader, hostContext)
                if (methods.isEmpty()) {
                    writeStatus(hostContext, MomentsVideoAutoPlaySettings.STATUS_NOT_FOUND)
                    if (loggedMissing.compareAndSet(false, true)) {
                        HLog.e(
                            "$TAG 未定位到自动播放判定方法（微信 ${wechatVersion(hostContext)}）。" +
                                "锚点字符串可能已被该版本裁剪，需要重新反编译定位。"
                        )
                    }
                    // 版本不匹配时重试只会反复全量扫 dex，直接收工。
                    return true
                }

                var hooked = 0
                for (method in methods) {
                    try {
                        // 返回类型只在安装期算一次，回调里不再做反射 getReturnType。
                        val primitive = method.returnType == java.lang.Boolean.TYPE
                        HookRegistry.get().hook(
                            method,
                            AutoPlayGuardHook(hostContext, primitive, method)
                        )
                        hooked++
                        HLog.e(
                            "$TAG Hook 成功: ${method.declaringClass.name}#${method.name}" +
                                " (返回 ${method.returnType.simpleName})"
                        )
                    } catch (e: Throwable) {
                        HLog.e(
                            "$TAG Hook 失败: ${method.declaringClass.name}#${method.name}: ${e.message}",
                            e
                        )
                    }
                }
                if (hooked > 0) {
                    installedLoaders.add(loaderKey)
                    writeStatus(
                        hostContext,
                        MomentsVideoAutoPlaySettings.STATUS_OK_PREFIX + hooked
                    )
                    HLog.e("$TAG 已安装朋友圈自动播放拦截，判定方法 $hooked 个")
                } else {
                    writeStatus(hostContext, MomentsVideoAutoPlaySettings.STATUS_NOT_FOUND)
                }
                return true
            }
        }

        private fun writeStatus(context: Context, status: String) {
            try {
                HchatStorage.preferences(
                    context,
                    MomentsVideoAutoPlaySettings.PREFS_NAME
                ).edit()
                    .putString(MomentsVideoAutoPlaySettings.KEY_LAST_STATUS, status)
                    .apply()
            } catch (e: Throwable) {
                HLog.e("$TAG 写入状态失败: ${e.message}")
            }
        }

        private fun wechatVersion(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Throwable) {
            "?"
        }

        private fun resolveJudgeMethods(
            bridge: DexKitBridge,
            loader: ClassLoader,
            context: Context
        ): List<Method> {
            val prefs = DexMethodCache.prefs(context, MomentsVideoAutoPlaySettings.CACHE_PREFS_NAME)
            val runtimeKey = DexMethodCache.runtimeKey(context, loader)
            val cached = DexMethodCache.loadList(
                prefs,
                runtimeKey,
                loader,
                MomentsVideoAutoPlaySettings.CACHE_KEY_JUDGE_METHODS
            ).filter { isCandidateReturnType(it) || isObjectReturning(it) }
                .distinctBy { it.toGenericString() }
            if (cached.isNotEmpty()) {
                HLog.e("$TAG 命中定位缓存，判定方法 ${cached.size} 个")
                return cached
            }
            if (cached.isEmpty()) {
                // 缓存描述符存在但全部被类型过滤掉时（例如旧版本只存了 Boolean 形态），
                // 清掉旧缓存强制走下面的完整扫描，避免永远困在残缺缓存里。
                val raw = prefs.getString(MomentsVideoAutoPlaySettings.CACHE_KEY_JUDGE_METHODS, null)
                if (!raw.isNullOrBlank()) {
                    DexMethodCache.clear(prefs, runtimeKey, MomentsVideoAutoPlaySettings.CACHE_KEY_JUDGE_METHODS)
                    HLog.e("$TAG 缓存类型不完整，已清除并重新扫描")
                }
            }

            for ((index, anchors) in ANCHOR_GROUPS.withIndex()) {
                val all = queryJudgeMethods(bridge, loader, anchors)
                if (all.isEmpty()) {
                    HLog.e("$TAG 锚点组#${index + 1} 未命中（${anchors.size} 个字符串）")
                    continue
                }
                // 包名只用于「收紧」：目标类混淆后多在根包，所以这里不再按包名硬过滤，
                // 只把 sns 包的方法排在前面，让 hook 数量上限优先用在这些方法上。
                val sorted = all.sortedBy { m ->
                    if (m.declaringClass.name.startsWith(SNS_PACKAGE)) 0 else 1
                }.take(MAX_TARGET_METHODS)

                HLog.e(
                    "$TAG 锚点组#${index + 1} 命中 ${all.size} 个方法，" +
                        "取前 ${sorted.size} 个接入"
                )
                return saveResolvedMethods(prefs, runtimeKey, sorted)
            }
            return emptyList()
        }

        private fun saveResolvedMethods(
            prefs: android.content.SharedPreferences,
            runtimeKey: String,
            found: List<Method>
        ): List<Method> {
            DexMethodCache.saveList(
                prefs,
                runtimeKey,
                MomentsVideoAutoPlaySettings.CACHE_KEY_JUDGE_METHODS,
                found
            )
            return found
        }

        /** 判定方法返回 boolean 或 Boolean（8.0.80 两种形态都存在）。 */
        private fun isCandidateReturnType(method: Method): Boolean {
            val rt = method.returnType
            return rt == java.lang.Boolean.TYPE || rt == java.lang.Boolean::class.java
        }

        /** `invoke()` 这种 Kotlin lambda 返回 `Object`，运行期装的是 Boolean。 */
        private fun isObjectReturning(method: Method): Boolean =
            method.returnType == Any::class.java

        private fun queryJudgeMethods(
            bridge: DexKitBridge,
            loader: ClassLoader,
            anchors: List<String>
        ): List<Method> {
            return try {
                val request = FindMethod().apply {
                    // 不写死 returnType：checkAutoPlay 返回 Z，autoPlay$2.invoke 返回 Object。
                    matcher(MethodMatcher().apply { usingStrings(anchors) })
                }
                bridge.findMethod(request)
                    .asSequence()
                    .mapNotNull { data -> runCatching { data.getMethodInstance(loader) }.getOrNull() }
                    .filter { isCandidateReturnType(it) || isObjectReturning(it) }
                    .distinctBy { it.toGenericString() }
                    .toList()
            } catch (e: Throwable) {
                HLog.e("$TAG DexKit 查询失败: ${e.message}", e)
                emptyList()
            }
        }

        /**
         * 判定方法返回「是否允许自动播放」；hook 在方法执行后把结果改成 false。
         * 零副作用、零字段猜测：不碰播放器状态（封面/尺寸/静音照常），只覆盖最终开关。
         */
        private class AutoPlayGuardHook(
            private val hostContext: Context,
            /** 目标方法是否返回 primitive boolean（否则是 Boxed Boolean）。安装期算好。 */
            private val primitiveResult: Boolean,
            private val method: Method
        ) : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (!isEnabled(hostContext) || result !is Boolean || !result) return result
                val hits = HIT_COUNT.incrementAndGet()
                if (hitLogCount.getAndIncrement() < MAX_HIT_LOGS) {
                    HLog.e(
                        "$TAG 已拦截自动播放：${method.declaringClass.name}" +
                            "#${method.name} 返回 true → false（累计 $hits）"
                    )
                }
                writeHitsThrottled(hostContext, hits)
                return if (primitiveResult) false else java.lang.Boolean.FALSE
            }
        }

        /**
         * 拦截计数回显到设置页。拦截结果处理可能发生在主线程（信息流布局），
         * 不能每条都写偏好——用 CAS 节流，每 10 秒最多写一次盘；第一次命中立即写，
         * 用户拨完开关进入朋友圈马上能看到「已拦截 1 次」。
         */
        private fun writeHitsThrottled(context: Context, hits: Int) {
            val now = SystemClock.elapsedRealtime()
            val last = lastHitWriteAtMs.get()
            if (hits > 1 && now - last < HIT_WRITE_INTERVAL_MS) return
            if (!lastHitWriteAtMs.compareAndSet(last, now)) return
            try {
                HchatStorage.preferences(context, MomentsVideoAutoPlaySettings.PREFS_NAME)
                    .edit().putInt(MomentsVideoAutoPlaySettings.KEY_HIT_COUNT, hits).apply()
            } catch (e: Throwable) {
                HLog.e("$TAG 写入拦截计数失败: ${e.message}")
            }
        }
    }
}
