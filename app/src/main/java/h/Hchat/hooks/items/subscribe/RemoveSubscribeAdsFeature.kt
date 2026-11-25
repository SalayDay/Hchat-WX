package h.Hchat.hooks.items.subscribe

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.ConfigStore
import h.Hchat.preferences.HotConfig
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 去除「公众号 / 订阅号消息」信息流广告。
 *
 * ## 为什么之前那只拦 JSAPI 不够（真机现象：每次杀后台重开都有广告）
 *
 * 微信 8.0.80 的订阅号信息流是 MagicBrush(Flutter) 页面渲染的，广告数据有两条独立通路：
 *
 * ```
 * 通路一（拉取参数，已拦）：
 *   Flutter --JSAPI--> cy.g/h/i --> com.tencent.wechat.mm.biz.m1.e/f/g/h --> native libaff_biz.so
 *
 * 通路二（广告数据下发，本版新增拦截）：
 *   native libaff_biz.so
 *     --> ZIDL_AV  onAdServerInfoEvent adinfo=...      (订阅号信息流广告)
 *     --> ZIDL_BV  onRecFeedsAdServerInfoEvent         (推荐流广告)
 *     --> ZIDL_DV  onOftenReadAdCards                  (常读广告卡)
 *         --> wx.p0.Xj(feedsType, adInfo)
 *             --> wx.e0.invoke()  封装 MBJsEventOnAdServerInfo / OnRecFeeds / OnOftenRead
 *                 --> wx.i1.H1(eventName, json)  →  mu0.i.H1  → sendEvent  →  Flutter 渲染广告卡
 * ```
 *
 * 通路一是"告诉 native 广告请求参数"，拦掉只是让 native 拿不到参数；
 * **广告卡本身是 native 走通路二主动推给 Flutter 的**，冷启动时 native 会先用
 * 本地已缓存的广告数据直接推一轮，完全绕开通路一 —— 这正是"杀后台重开必现广告"的根因。
 *
 * ## 第三条通路（8.0.80 实测确认，本版新增拦截）
 *
 * 广告卡不是"事件里带内容"，而是 Flutter 反过来**按 cardType 向 native 要卡片列表**：
 *
 * ```
 * Flutter 广告服务实例 (MagicAdBrandService)
 *   --> JSAPI cy.b.t / wx.m0.Rj
 *       --> px.b.Fj(cardType)                    cardType: 1=广告 2=品牌 3=推荐 5=常读
 *           --> com.tencent.wechat.mm.biz.m1.c(I)  同步 ZIDL 调用进 native
 *               --> ZIDL_HtXvE4pW3K.ZIDL_B(long, int) → 返回 x4 卡片列表 protobuf
 * ```
 *
 * 也就是说：**即使事件被丢掉，只要 native 里还留着广告卡（离线动态卡，冷启动必加载），
 * Flutter 问一次 `Fj(1)` 就能把广告画出来**。这是"杀后台重开还有广告"的真正原因，
 * 前面两门都拦在它上游。
 *
 * ## 本版做法
 *
 * 1. **JSAPI 拉取门**：m1.e/f/g/h 返回 false，微信官方语义就是"广告拉取失败"。
 * 2. **事件出口门**：在 `sendEvent` 上按事件名精确丢弃三个广告事件。
 * 3. **广告卡列表门**：`px.b.Fj(cardType)` 在 cardType=1（广告服务实例）时返回空列表；
 *    其它 cardType（品牌/推荐/常读）原样放行。
 * 4. **广告卡服务总开关**：`wx.w1.Oj()`（final，p0/g2 共用）强制 false。
 *    native 的 `ZIDL_CV`（帧通知）、`ZIDL_DV`（常读广告卡）、`ZIDL_EV`（状态变化）
 *    进门第一件事都是查 `Oj()`，返回 false 等于"这个广告服务没开"，整条链在源头断掉。
 *    `Qj()` 只是配置读取（`openAdBrandServiceCard`），native 回调根本不看它 ——
 *    这就是上一版"Qj 已拦、广告还在"的原因。
 * 5. **广告卡片服务门（只挂 `wx.p0`）**：`Qj()` / `Rj(msgId, frameSetName, frameSetData)` /
 *    `Xj(feedsType, adInfo)` / `Zj(event, payload)` 四个出口整条跳过。
 *    `Xj` 是 native 把广告信息落地的唯一 Java 口，`Rj` 是推帧口，`Zj` 是常读广告会话口；
 *    上一版只靠 `Oj()` 间接挡，`Oj` 一旦被子类或换版本绕开就全漏，这里直接掐出口。
 * 6. **帧转发门**：基类静态 `wx.w1.Ej(self, cardType, frameSetName, frameSetData)`，
 *    **只丢第 1 个参数是 `wx.p0` 的帧**；`wx.g2`（正常品牌卡）原样放行。
 * 7. **事件出口门**：`wx.b0` / `wx.c2` 覆写的 `P9(key, value)` 与基类 `wx.i1.H1(key, value)`
 *    全量丢弃 —— 这两个类只被广告卡服务创建。
 * 8. **广告帧提交门**：`m1.d(cardType, frameSetName, frameSetData)`，cardType=1 时跳过，
 *    防止 Java 侧再把广告帧写回 native 缓存（否则下次冷启动 native 自己就能推）。
 * 9. **提前安装**：都不依赖 DexKit，冷启动在 Application.onCreate 之后立刻挂。
 *
 * ## 观察口径（排查时先看这个）
 *
 * 每一道门在**第一次命中**时走 `HLog.boot`，即使「记录运行日志」当时是关的，
 * 打开后也会以「（启动期）」补写进缓冲。所以一份日志里只要看到某门的首行，
 * 就说明这一门真的命中了；一条都没有，就说明广告根本不走那条路 —— 别再猜。
 * 顺带一提：`wx.g2` 是**正常品牌卡服务**，任何门都不能碰它，碰了就是"广告还在、
 * 正常卡片没了"。
 *
 * ## 一个踩过的坑（所有门都必须遵守）
 *
 * XposedBridge 判断"要不要跳过原方法"看的是 `param.returnEarly`，
 * 只有 `param.setResult(x)` 会把它置位；直接写 `param.result = x` 只改了字段，
 * 原方法照样执行、结果被覆盖。上一版全部用的是字段赋值，**日志打了但一点没拦住**，
 * 这正是"每次都有日志、广告一直在"的直接原因。本文件已全部改为 `setResult`。
 *
 * ## 边界
 * - 只丢广告事件，不碰正常消息、feeds 数据、常读账号、朋友圈、小程序。
 * - 已经渲染在屏幕上的广告卡要退出页面重进才消失（数据不再下发，重进不会回来）。
 * - `wx.p0` / `wx.g2` / `wx.i1` / `wx.w1` 都是混淆类名，微信升级后可能变化：
 *   定位失败只打日志并按未挂载处理，功能自动降级为"只拦 JSAPI"，不会崩。
 */
class RemoveSubscribeAdsFeature : BaseFeature() {
    override fun featureId() = ID

    override fun name() = "去除公众号信息流广告"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            object : SimpleFeatureSettingsProvider(
                ID,
                name(),
                "拦截公众号 feeds 广告的拉取参数与下发事件，广告卡不再产生",
                FeatureSettingsProvider.CATEGORY_PRACTICAL
            ) {}
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        install(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        const val ID = "remove_subscribe_ads"
        const val KEY_ENABLE = "enabled"
        const val DEFAULT_ENABLE = true

        private const val TAG = "[Hchat:SubscribeAds]"

        /** 广告 native 桥，明文类名，跨版本稳定。 */
        private const val JNI_CLASS = "com.tencent.wechat.mm.biz.m1"
        private val JNI_METHODS = setOf("e", "f", "g", "h")

        /**
         * native → Java 的广告事件回调宿主（zidl 派发类，后缀随版本变化时退化为未挂载）。
         *
         * 8.0.80 实测有**两个**模块都会调 `wx.p0.Xj` 推广告：
         * - `com.tencent.wechat.mm.biz.ZIDL_fvl8iIsEB`（公众号/订阅号）
         * - `com.tencent.wechat.mm.finder_box.ZIDL_ql26v1FcB`（视频号 finder_box）
         * 只挂前一个是之前的漏点，两个都要挂。
         */
        private val NATIVE_EVENT_CLASSES = listOf(
            "com.tencent.wechat.mm.biz.ZIDL_fvl8iIsEB",
            "com.tencent.wechat.mm.finder_box.ZIDL_ql26v1FcB"
        )
        private val NATIVE_AD_EVENTS = listOf("ZIDL_AV", "ZIDL_BV")

        /**
         * 广告事件下发出口的候选类，按优先级尝试：
         * - 广告卡专用出口（范围最小、最准，命中即全量丢弃）
         * - MagicBrush 运行时基类（兜底，只能按事件名精确匹配）
         *
         * 运行期按候选表逐个尝试，见 [EVENT_GATE_CLASSES] 的使用点。
         */
        private val AD_CARD_EVENT_CANDIDATES = listOf("wx.i1", "fx.w1")

        private val EVENT_GATE_CLASSES = AD_CARD_EVENT_CANDIDATES + listOf("mu0.i")

        /** 明文事件名，来自 MBJsEventOnXxxServerInfo 三个事件类的构造常量。 */
        private val AD_EVENTS = setOf(
            "onAdServerInfo",
            "onRecFeedsAdServerInfo",
            "onOftenReadAdServerInfo"
        )

        /**
         * 卡片列表接口（混淆类名，8.0.80 为 `px.b`）。
         *
         * `Fj(int cardType)` 返回该 cardType 下的全部动态卡片，是 Flutter 拿卡片的唯一入口。
         */
        private const val CARD_LIST_CLASS = "px.b"
        private const val CARD_LIST_METHOD = "Fj"

        /**
         * 广告卡片列表的 cardType。
         *
         * 来源：`cy.b.t`（MBJsApiGetAllDynamicCardInfo）按 JSAPI 实例名映射
         * MagicAdBrandService → 1、MagicBrandService → 2、isRec → 3、isOftenRead → 5。
         * `wx.m0` 统计 "MagicAdBrandServiceCardNum" 时也是取 `Fj(1)`。
         */
        private const val AD_CARD_TYPE = 1

        /**
         * 广告卡片服务 `MagicAdBrandServiceFeatureService` 的候选类名（按版本从新到旧）。
         *
         * 基类在同一版本里有两个子类，**必须分清，不能一起拦**：
         * - 广告卡服务 → 构造串 `MagicAdBrandService`、方法串 `MagicAdBrandServiceFeatureService`
         *   8.0.80 = `wx.p0`，8.0.76 = `fx.f0`，本功能的唯一目标；
         * - 正常品牌卡服务 → 构造串 `MagicBrandService`、方法串 `MagicBrandServiceFeatureService`
         *   8.0.80 = `wx.g2`，8.0.76 = `fx.t1`，**绝不能碰**。
         *
         * 两版的广告服务名是一一对应的（`wx.p0` ↔ `fx.f0`），所以按「第一个能加载到的」
         * 取即可，不存在把正常品牌卡认成广告服务的风险。
         */
        private val AD_CARD_SERVICE_CANDIDATES = listOf("wx.p0", "fx.f0")

        /** 广告卡服务的两个 MagicBrush 实例（都继承事件出口类，都覆写了 `P9`）。 */
        private val AD_CARD_INSTANCE_CANDIDATES = listOf(
            listOf("wx.b0", "wx.c2"),
            listOf("fx.b0", "fx.c2")
        )

        /** `Ej` 所在的基类（静态转发，把 frame 交给广告卡模型）。 */
        private val AD_CARD_BASE_CANDIDATES = listOf("wx.w1", "fx.j1")

        /** 广告卡拉取任务（唯一构造点是广告服务的 `Sj`）。 */
        private val AD_FETCH_TASK_CANDIDATES = listOf("wx.m0", "fx.m0")

        /** 广告卡帧任务（真正「取数 + 分发」的那个任务）。 */
        private val AD_FRAME_TASK_CANDIDATES = listOf("wx.v1", "fx.v1")

        /** 按候选顺序取第一个能加载到的类名，全都不存在时返回 null。 */
        private fun resolveClassName(loader: ClassLoader, candidates: List<String>): String? =
            candidates.firstOrNull { candidate ->
                runCatching { Class.forName(candidate, false, loader) }.isSuccess
            }

        /** 按候选顺序取第一个能加载到的类名，全都不存在时退回首个候选（保持日志可读）。 */
        private fun resolveClassNameOrFirst(loader: ClassLoader, candidates: List<String>): String =
            resolveClassName(loader, candidates) ?: candidates.first()

        // ==== 运行期解析出的混淆类名（见 install 里的 resolveClassNameOrFirst）====
        @Volatile private var resolvedAdServiceClass: String = "wx.p0"
        @Volatile private var resolvedAdBaseClass: String = "wx.w1"
        @Volatile private var resolvedAdFetchTaskClass: String = "wx.m0"
        @Volatile private var resolvedAdFrameTaskClass: String = "wx.v1"
        @Volatile private var resolvedAdInstanceClasses: List<String> = listOf("wx.b0", "wx.c2")

        private val hits = AtomicInteger()
        private val dropped = AtomicInteger()

        /** 总开关 `wx.w1.Oj()` 调用极频繁，只在第一次命中时打日志，避免刷屏。 */
        private val switchLogged = AtomicBoolean(false)
        /** 各门"首次命中"标记：首次用 boot（不怕采集关闭），之后才走 event。 */
        private val pullLogged = AtomicBoolean(false)
        private val cardGateLogged = AtomicBoolean(false)
        private val frameSubmitLogged = AtomicBoolean(false)
        private val eventGateLogged = AtomicBoolean(false)
        private val nativeGateLogged = AtomicBoolean(false)
        private val installedLoaders = Collections.synchronizedSet(HashSet<String>())
        private val lock = Any()

        /** 事件门延迟重试的单线程调度器；lazy 保证定位一次就成功的常规路径不会创建线程。 */
        private val retryExecutor: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "Hchat-SubscribeAds-Retry").apply { isDaemon = true }
            }
        }

        @Volatile
        private var mounted = false

        /**
         * 最近一次挂载的逐门结果。
         *
         * 单独存一份的原因：挂载只发生一次，而用户往往在页面出现广告之后才打开
         * 「记录运行日志」，那时候启动期的 buffered 日志可能已经被缓冲上限挤掉。
         * 把它拼进"卡片门首次命中"那条日志里，只要这道门命中过，就一定能看到
         * 其余门到底挂没挂上 —— 这是判断"广告走的是哪条通路"的唯一依据。
         */
        @Volatile
        private var mountSummary = "未记录（安装入口未执行）"

        /**
         * 安装时拿到的宿主上下文与 ClassLoader。
         *
         * 挂载日志发生在 `ModuleEntry.onCreate` 之后的几百毫秒，那时
         * `WeChatApis` 可能还没被 `WechatApiFeature` 注入（`versionApi` 为 null），
         * 于是"微信 未知"。把宿主参数留一份，版本探测就可以自己直接查，
         * 不再依赖 API 的初始化顺序。
         */
        @Volatile
        private var hostContext: Context? = null

        @Volatile
        private var hostClassLoader: ClassLoader? = null

        /** 安装时 HookRegistry 的代次；unhookAll 之后必须重装，否则功能会静默失效。 */
        @Volatile
        private var installedGeneration = Long.MIN_VALUE

        data class Stats(val mounted: Boolean, val hits: Int, val dropped: Int)

        fun stats(): Stats = Stats(mounted, hits.get(), dropped.get())

        fun resetStats() {
            hits.set(0)
            dropped.set(0)
        }

        /** 供早期入口（Application.onCreate 之后）直接读配置，不依赖 FeatureContext。 */
        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            return runCatching {
                ConfigStore(context).getBoolean(ID, KEY_ENABLE, DEFAULT_ENABLE)
            }.getOrDefault(DEFAULT_ENABLE)
        }

        /**
         * 幂等安装。onCreate 之后的早期入口与 FeatureManager 都会调用，
         * 同一个 ClassLoader + 同一个 Hook 代次只挂一次。
         *
         * 全部走反射、不碰 DexKit。
         */
        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?): Boolean {
            if (context == null || classLoader == null) return false
            hostContext = context
            hostClassLoader = classLoader
            val loaderKey = classLoader.javaClass.name + "@" + System.identityHashCode(classLoader)
            val generation = HookRegistry.get().installationGeneration()
            synchronized(lock) {
                if (installedGeneration != generation) {
                    // Hook 被整体卸载过（设置变更重建），之前的挂载点已失效。
                    installedLoaders.clear()
                    installedGeneration = generation
                    mounted = false
                } else if (installedLoaders.contains(loaderKey)) {
                    return mounted
                }
                val enabledFlag = HotConfig.flag(ID, KEY_ENABLE, DEFAULT_ENABLE) {
                    isEnabled(context)
                }
                // 混淆类名逐版本变化（8.0.80 = wx.*，8.0.76 = fx.*）：先按候选表解析一次，
                // 之后各门都用解析结果，避免每个门各自重复尝试。
                resolvedAdServiceClass = resolveClassNameOrFirst(classLoader, AD_CARD_SERVICE_CANDIDATES)
                resolvedAdBaseClass = resolveClassNameOrFirst(classLoader, AD_CARD_BASE_CANDIDATES)
                resolvedAdFetchTaskClass = resolveClassNameOrFirst(classLoader, AD_FETCH_TASK_CANDIDATES)
                resolvedAdFrameTaskClass = resolveClassNameOrFirst(classLoader, AD_FRAME_TASK_CANDIDATES)
                resolvedAdInstanceClasses = AD_CARD_INSTANCE_CANDIDATES.firstOrNull { names ->
                    names.any { runCatching { Class.forName(it, false, classLoader) }.isSuccess }
                } ?: AD_CARD_INSTANCE_CANDIDATES.first()
                val pullHooks = hookAdPullGate(classLoader, enabledFlag)
                val cardGate = hookCardListGate(classLoader, enabledFlag)
                val fetchTaskGate = hookAdFetchTaskGate(classLoader, enabledFlag)
                val frameTaskGate = hookAdFrameTaskGate(classLoader, enabledFlag)
                val serviceGate = hookAdCardServiceGate(classLoader, enabledFlag)
                val frameGate = hookFrameSubmitGate(classLoader, enabledFlag)
                val gateTarget = hookEventGate(classLoader, enabledFlag)
                // 事件出口是混淆类，微信升级后可能定位不到；此时退回 native → Java
                // 的广告事件回调（明文类名），保证仍有拦截能力。
                val nativeHooks = if (gateTarget == null) {
                    hookNativeAdEvents(classLoader, enabledFlag)
                } else 0
                // 拉取门挂上了、两个事件出口却都定位不到：多半是 install 时（onCreate
                // 之后几百毫秒）Flutter 运行时类还没链接完，Class.forName 抛了
                // NoClassDefFoundError。等宿主首页加载完再补几次，只在失败路径上花成本。
                if (gateTarget == null && nativeHooks == 0 && pullHooks > 0) {
                    scheduleGateRetry(classLoader, enabledFlag, 1)
                }
                val ok = pullHooks > 0 || cardGate || fetchTaskGate || frameTaskGate || serviceGate > 0 ||
                    frameGate || gateTarget != null || nativeHooks > 0
                if (ok) installedLoaders.add(loaderKey)
                mounted = ok
                // 用 boot 而不是 e：这条只在启动时打一次，用户往往是几秒后才打开
                // 「记录运行日志」，用 e 会正好落在采集关闭的窗口里，永远看不到。
                val summary =
                    "拉取门 $pullHooks/4，卡片门 ${if (cardGate) "已挂载" else "未命中"}" +
                        "，拉取任务门 ${if (fetchTaskGate) "已挂载" else "未命中"}" +
                        "，帧任务门 ${if (frameTaskGate) "已挂载" else "未命中"}" +
                        "，广告服务门 $serviceGate/8" +
                        "，帧提交门 ${if (frameGate) "已挂载" else "未命中"}" +
                        "，事件门 ${gateTarget ?: "未命中"}" +
                        "，native兜底 ${if (nativeHooks > 0) "已挂载" else "未启用"}"
                mountSummary = summary
                HLog.boot(
                    (if (ok) "$TAG 广告拦截挂载：" else "$TAG 广告拦截挂载失败：") +
                        summary + "，微信 ${wechatVersion()}"
                )
                return ok
            }
        }

        /** 通路一：JSAPI 拉取参数入口。返回 false = 微信官方的"广告拉取失败"。 */
        private fun hookAdPullGate(loader: ClassLoader, enabled: () -> Boolean): Int {
            val methods = locateJniMethods(loader)
            var installed = 0
            for (method in methods) {
                val ok = runCatching {
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!enabled()) return
                            hits.incrementAndGet()
                            if (pullLogged.compareAndSet(false, true)) {
                                HLog.boot("$TAG 拦下广告拉取 ${method.name}")
                            } else {
                                HLog.event("$TAG 拦下广告拉取 ${method.name}")
                            }
                            param.setResult(false)
                        }
                    })
                    true
                }.onFailure {
                    HLog.e("$TAG hook ${method.name} 失败: ${it.message}", it)
                }.getOrDefault(false)
                if (ok) installed++
            }
            if (installed == 0 && methods.isEmpty()) {
                HLog.e("$TAG 未找到广告 native 桥 m1（微信 ${wechatVersion()}）", null)
            }
            return installed
        }

        /**
         * 通路三：广告卡片列表入口（本版新增，也是冷启动广告的最后一道门）。
         *
         * `px.b.Fj(int)` 是 Flutter 向 native 索取动态卡片的唯一方法，native 侧
         * 用本地缓存/离线动态卡直接返回，**不经过任何事件**。所以在 cardType=1
         * （MagicAdBrandService，纯广告服务实例）时把返回值换成空列表，
         * 广告卡就从数据源头消失了 —— 与有没有推送、有没有缓存无关。
         *
         * 其它 cardType（2=品牌 3=推荐 5=常读）原样放行，正常文章卡不受影响。
         */
        private fun hookCardListGate(loader: ClassLoader, enabled: () -> Boolean): Boolean {
            val method = exactMethod(CARD_LIST_CLASS, loader, CARD_LIST_METHOD, Integer.TYPE)
                ?: run {
                    HLog.event("$TAG 卡片列表接口 $CARD_LIST_CLASS#$CARD_LIST_METHOD 不存在（微信 ${wechatVersion()}）")
                    return false
                }
            if (method.returnType != java.util.List::class.java) {
                HLog.event("$TAG 卡片列表接口 $CARD_LIST_CLASS#$CARD_LIST_METHOD 签名不匹配")
                return false
            }
            return runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        val type = (param.args.getOrNull(0) as? Int) ?: return
                        if (type != AD_CARD_TYPE) return
                        // 不能在这里反调原方法做统计：反射 invoke 会再次进入本 Hook 造成
                        // 无限递归；而且原方法是一次同步 ZIDL 调用，跳过它反而省一次跨语言开销。
                        dropped.incrementAndGet()
                        if (cardGateLogged.compareAndSet(false, true)) {
                            // 首次命中一并带出：①各门挂载结果 ②是谁在要 cardType=1。
                            // 只有这道门出现在日志里时，这两条信息才决定下一步往哪修：
                            // 栈里出现 wx.p0 / wx.g2 → 广告卡服务；只有 cy.b / px.b → 纯卡片列表；
                            // 全是 proto/m1 → 数据来自 native 缓存。
                            HLog.boot(
                                "$TAG 丢弃广告卡片列表 cardType=$type，微信 ${wechatVersion()}，" +
                                    "挂载：$mountSummary"
                            )
                            HLog.boot(
                                "$TAG Fj($type) 调用方（${param.method.declaringClass.name}#${param.method.name}）" +
                                    "：${callerTrace(param.method)}"
                            )
                        } else {
                            HLog.event("$TAG 丢弃广告卡片列表 cardType=$type")
                        }
                        // 每次返回新列表：调用方（Flutter 桥）可能对返回值做 add/clear，
                        // 共享同一个实例会把状态带到下一次请求。
                        param.setResult(java.util.LinkedList<Any?>())
                    }
                })
                true
            }.onFailure {
                HLog.e("$TAG hook $CARD_LIST_CLASS#$CARD_LIST_METHOD 失败: ${it.message}", it)
            }.getOrDefault(false)
        }

        /**
         * 通路三之二：广告卡拉取任务 `wx.m0.run()`。
         *
         * 这条是 6.7.7.2 实机日志里 `Fj(1)` 调用方栈暴露出来的：真正的调用者是
         * `wx.m0`，而它**只**由 `wx.p0.Sj(String)`（广告卡服务）创建，`run()` 里
         * 唯一的正事就是 `px.b.Fj(1)` + `LinkedList.size()` 统计 + 一条 magicbrush 监控。
         *
         * 它不查 `Oj()`、也不走 `Qj/Rj/Xj/Zj`，所以前 8 道服务门对它完全无效；
         * 卡片门虽能兜住（把返回值换成空表），但任务本体照样跑：跨 ZIDL 取数、
         * 统计、上报都会发生，等于每次冷启动白跑一趟。这里直接在任务入口丢掉，
         * 比拦 `Fj` 更省（少一次跨语言调用 + 少一次监控上报）。
         */
        private fun hookAdFetchTaskGate(loader: ClassLoader, enabled: () -> Boolean): Boolean {
            val method = exactMethod(resolvedAdFetchTaskClass, loader, "run") ?: run {
                HLog.event("$TAG 广告卡拉取任务 ${resolvedAdFetchTaskClass}#run 未命中（微信 ${wechatVersion()}）")
                return false
            }
            if (method.parameterCount != 0 || method.returnType != java.lang.Void.TYPE) {
                HLog.event("$TAG 广告卡拉取任务 ${resolvedAdFetchTaskClass}#run 签名不匹配")
                return false
            }
            // void 方法：setResult(null) 即"整个 run() 不执行"。
            return hookFirstHit(
                method,
                enabled,
                "${resolvedAdFetchTaskClass}#run",
                "丢弃广告卡拉取任务"
            )
        }

        /**
         * 广告卡帧任务门：`wx.v1.run()`。
         *
         * 与上面 m0 门的区别：m0 只做统计，v1 才是真正的"取数 + 分发"。两者实例上都
         * 挂着发起方服务，**必须按服务类型区分**，否则会把 `wx.g2`（正常品牌卡）一起干掉。
         */
        private fun hookAdFrameTaskGate(loader: ClassLoader, enabled: () -> Boolean): Boolean {
            val method = exactMethod(resolvedAdFrameTaskClass, loader, "run") ?: run {
                HLog.event("$TAG 广告卡帧任务 ${resolvedAdFrameTaskClass}#run 未命中（微信 ${wechatVersion()}）")
                return false
            }
            if (method.parameterCount != 0 || method.returnType != java.lang.Void.TYPE) {
                HLog.event("$TAG 广告卡帧任务 ${resolvedAdFrameTaskClass}#run 签名不匹配")
                return false
            }
            // 实例字段 d: Lwx/w1;（按类型找，不写死混淆字段名）。
            val serviceField = runCatching {
                val serviceType = Class.forName(resolvedAdBaseClass, false, loader)
                Class.forName(resolvedAdFrameTaskClass, false, loader).declaredFields
                    .firstOrNull { it.type == serviceType }?.also { it.isAccessible = true }
            }.getOrNull()
            val adServiceType = runCatching {
                Class.forName(resolvedAdServiceClass, false, loader)
            }.getOrNull()
            if (serviceField == null || adServiceType == null) {
                // 拿不到服务字段就无法区分广告与正常品牌卡，宁可放行也不误杀。
                HLog.boot("$TAG 广告卡帧任务字段未命中（微信 ${wechatVersion()}），本门放行")
                return false
            }
            val frameLogged = AtomicBoolean(false)
            return runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        val owner = runCatching { serviceField.get(param.thisObject) }.getOrNull() ?: return
                        if (!adServiceType.isInstance(owner)) return
                        dropped.incrementAndGet()
                        if (frameLogged.compareAndSet(false, true)) {
                            HLog.boot(
                                "$TAG 丢弃广告卡帧任务 ${resolvedAdFrameTaskClass}#run（服务 $resolvedAdServiceClass）"
                            )
                        }
                        param.setResult(null)
                    }
                })
                true
            }.onFailure {
                HLog.e("$TAG hook ${resolvedAdFrameTaskClass}#run 失败: ${it.message}", it)
            }.getOrDefault(false)
        }

        /**
         * 通路四：广告卡片服务本身（本版新增，也是之前一直漏掉的真正出口）。
         *
         * 8.0.80 的信息流广告卡**不走** `Fj(1)` 的卡片列表，而是由
         * `MagicAdBrandServiceFeatureService` 独立产生一帧，直接塞给渲染器：
         *
         * ```
         * native ZIDL_CV / ZIDL_DV / ZIDL_EV
         *   └─ if (wx.w1.Oj())                       ← 真正的总开关（final，p0/g2 共用）
         *        wx.p0 / wx.g2   (MagicAdBrandServiceFeatureService 的两个实现)
         *          Qj()Z                              ← 配置 openAdBrandServiceCard，仅日志用
         *          Rj(long msgId, String frameSetName, String frameSetData)
         *               → wx.w1.Ej(state, index, frameSetName, frameSetData)   静态转发
         *                   → wx.c1.run()
         *                       → wx.i1.H1(eventName, json)  → 渲染器画卡
         * ```
         *
         * 之前只在 `H1` 上按 3 个事件名丢弃，但广告帧用的并不是那 3 个名字；
         * 上一版又加了 `Qj()`，可 native 回调根本不读 `Qj()`（它只写一行
         * "openAdBrandServiceCard" 日志），所以帧照样通过。这里改成拦
         * `Oj()` —— native 进门第一件事就是查它。
         */
        private fun hookAdCardServiceGate(loader: ClassLoader, enabled: () -> Boolean): Int {
            var installed = 0
            val service = resolvedAdServiceClass

            // ① 总开关：wx.w1.Oj()Z（public final，p0/g2 共用）。native 的 ZIDL_CV / ZIDL_DV /
            //    ZIDL_EV 进门先查它；Xj / Rj / Zj 三个 Java 回调里也各自再查一遍。
            val masterSwitch = exactMethod(resolvedAdBaseClass, loader, "Oj")
            if (masterSwitch != null && masterSwitch.parameterCount == 0 &&
                masterSwitch.returnType == java.lang.Boolean.TYPE
            ) {
                val ok = runCatching {
                    HookRegistry.get().hook(masterSwitch, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!enabled()) return
                            dropped.incrementAndGet()
                            if (switchLogged.compareAndSet(false, true)) {
                                HLog.boot("$TAG 关闭广告卡服务总开关 ${resolvedAdBaseClass}#Oj")
                            }
                            param.setResult(false)
                        }
                    })
                    true
                }.onFailure {
                    HLog.e("$TAG hook ${resolvedAdBaseClass}#Oj 失败: ${it.message}", it)
                }.getOrDefault(false)
                if (ok) installed++
            } else {
                HLog.boot("$TAG 广告卡服务总开关 ${resolvedAdBaseClass}#Oj 未命中（微信 ${wechatVersion()}）")
            }

            // ② 广告服务（只挂 wx.p0，正常品牌卡 wx.g2 一律不碰）的四个出口。
            //    Qj()                            配置开关 openAdBrandServiceCard
            //    Rj(long,String,String)          native 推广告帧
            //    Xj(int,String)                  native 推广告信息 → 组 onAdServerInfo 事件（本版新增）
            //    Zj(String,String)               常读广告会话事件（本版新增）
            val toggle = exactMethod(service, loader, "Qj")
            if (toggle != null && toggle.parameterCount == 0 && toggle.returnType == java.lang.Boolean.TYPE) {
                // 返回 boolean 的方法必须给 false，给 null 会在调用方拆箱时炸。
                if (hookFirstHit(toggle, enabled, "$service#Qj", "关闭广告卡片开关", false)) installed++
            }
            val notify = exactMethod(
                service, loader, "Rj", java.lang.Long.TYPE, String::class.java, String::class.java
            )
            if (notify != null && notify.returnType == java.lang.Void.TYPE) {
                if (hookFirstHit(notify, enabled, "$service#Rj", "拦下广告帧 Rj")) installed++
            }
            // Xj(int feedsType, String adInfo)：native 唯一的广告信息落地口。
            // 上一版只靠 Oj() 间接挡，Oj 被子类/换版本绕开时这条就漏了，这里直接掐。
            val adInfo = exactMethod(service, loader, "Xj", Integer.TYPE, String::class.java)
            if (adInfo != null && adInfo.returnType == java.lang.Void.TYPE) {
                if (hookFirstHit(adInfo, enabled, "$service#Xj", "拦下广告信息推送")) installed++
            }
            // Zj(String,String)：常读栏广告会话事件（同样先查 Oj，再发给渲染器）。
            val oftenRead = exactMethod(service, loader, "Zj", String::class.java, String::class.java)
            if (oftenRead != null && oftenRead.returnType == java.lang.Void.TYPE) {
                if (hookFirstHit(oftenRead, enabled, "$service#Zj", "拦下常读广告事件")) installed++
            }

            // ③ 帧→渲染器的静态转发 Ej(w1 self, int, String, String)。
            //    第 1 个参数就是发起帧的服务实例：**只丢 wx.p0 的广告帧**，
            //    wx.g2（正常品牌卡）原样放行 —— 上一版无差别丢弃，把正常卡片也一起干掉了。
            val dispatch = runCatching {
                val cls = Class.forName(resolvedAdBaseClass, false, loader)
                cls.declaredMethods.firstOrNull { method ->
                    method.name == "Ej" && Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.size == 4 && method.returnType == java.lang.Void.TYPE
                }?.also { it.isAccessible = true }
            }.getOrNull()
            val adServiceClass = runCatching { Class.forName(service, false, loader) }.getOrNull()
            if (dispatch != null && adServiceClass != null) {
                val ejLogged = AtomicBoolean(false)
                val ok = runCatching {
                    HookRegistry.get().hook(dispatch, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!enabled()) return
                            if (!adServiceClass.isInstance(param.args.getOrNull(0))) return
                            dropped.incrementAndGet()
                            if (ejLogged.compareAndSet(false, true)) {
                                HLog.boot("$TAG 拦下广告帧 Ej（实例 ${service}）")
                            }
                            param.setResult(null)
                        }
                    })
                    true
                }.onFailure {
                    HLog.e("$TAG hook ${resolvedAdBaseClass}#Ej 失败: ${it.message}", it)
                }.getOrDefault(false)
                if (ok) installed++
            } else {
                HLog.boot("$TAG 广告帧转发点 ${resolvedAdBaseClass}#Ej 未命中（微信 ${wechatVersion()}）")
            }

            // ④ 事件发送 P9(key,value)：wx.b0 / wx.c2 各自**覆写**了它（内部转 mu0.i#P9），
            //    是广告卡实例往 JS 推数据的另一条出口，只走 H1 会漏。
            for (className in resolvedAdInstanceClasses) {
                val send = exactMethod(className, loader, "P9", String::class.java, String::class.java)
                if (send != null && send.returnType == java.lang.Void.TYPE) {
                    if (hookFirstHit(send, enabled, "$className#P9", "丢弃广告事件 P9")) installed++
                }
            }

            if (installed == 0) {
                HLog.boot("$TAG 广告卡片服务未命中（微信 ${wechatVersion()}）")
            }
            return installed
        }

        /**
         * 通路五：Java → native 的广告帧提交。
         *
         * `px.b.Gj(cardType, frameSetName, frameSetData)` 唯一上游，落到
         * `m1.d(int, String, String)` 写进 native 缓存。广告帧一旦写进去，
         * 下次冷启动 native 自己就能推给 Flutter，和 Java 层拦不拦无关。
         *
         * 因此这里按 cardType 精确过滤：只丢 1（广告服务实例），
         * 2/3/5（品牌 / 推荐 / 常读）原样放行。
         */
        private fun hookFrameSubmitGate(loader: ClassLoader, enabled: () -> Boolean): Boolean {
            val method = runCatching {
                val cls = Class.forName(JNI_CLASS, false, loader)
                cls.getDeclaredMethod("d", Integer.TYPE, String::class.java, String::class.java)
                    .also { it.isAccessible = true }
            }.getOrNull() ?: run {
                HLog.event("$TAG 广告帧提交 $JNI_CLASS#d 未命中（微信 ${wechatVersion()}）")
                return false
            }
            if (method.returnType != java.lang.Void.TYPE) return false
            return runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        val type = (param.args.getOrNull(0) as? Int) ?: return
                        if (type != AD_CARD_TYPE) return
                        dropped.incrementAndGet()
                        if (frameSubmitLogged.compareAndSet(false, true)) {
                            HLog.boot("$TAG 拦下广告帧提交 cardType=$type")
                        } else {
                            HLog.event("$TAG 拦下广告帧提交 cardType=$type")
                        }
                        param.setResult(null)
                    }
                })
                true
            }.onFailure {
                HLog.e("$TAG hook $JNI_CLASS#d 失败: ${it.message}", it)
            }.getOrDefault(false)
        }

        /**
         * 统一的 hook 包装：开关打开才动作，**首次命中**留一条启动期日志（只留一次，不刷屏）。
         *
         * 为什么首次命中要用 `HLog.boot` 而不是 `HLog.event`：
         * `event` 在「记录运行日志」关闭时直接丢弃，而广告相关回调基本都发生在
         * 冷启动/首次进页面的那几秒，用户往往是看到广告之后才去开日志 ——
         * 于是"这一门到底有没有命中"永远看不到，只能靠猜。`boot` 会先暂存，
         * 采集一打开就补写进缓冲，一定看得到。
         *
         * 注意**必须用 `setResult`**：XposedBridge 判断是否跳过原方法看的是
         * `returnEarly` 标志，而 `returnEarly` 只有 `setResult` 会置位。
         * 直接写 `param.result = x` 只改了字段，原方法照跑、结果被覆盖，
         * 表现就是"日志打了、广告还在"。
         */
        /**
         * 当前调用栈中"谁在要 cardType=1"的部分，保留**全限定类名**。
         *
         * 三条实机教训：
         * 1. 只打类名末段（`substringAfterLast('.')`）不可用 —— 8.0.80 的 dex 里
         *    根本没有 `CairtBlierkaelClat` / `FerietGrai` 这种类，短名既读不懂也无法回溯。
         * 2. 按"类名 + 方法名"找锚点会失败：钩子被框架代理过一层之后，栈上那一帧的
         *    类名不是 `px.b`（实测是 `android.provider.FerietGrai`），锚点找不到就退回
         *    首帧，打出来的是调度帧，等于白打。
         * 3. 所以要**只按方法名**找锚点（`Fj` 在一条栈里只出现一次），并把实际帧类名
         *    一起打出来 —— 它和 `px.b` 不一致本身就说明"有人代理了这个方法"。
         */
        // Xposed 的 MethodHookParam.method 声明成 Member（兼容构造函数 Hook），这里跟着放宽。
        private fun callerTrace(target: Member): String = runCatching {
            val frames = Throwable().stackTrace
            val anchoredAt = frames.indexOfLast { it.methodName == target.name }
            val anchorNote = if (anchoredAt >= 0) {
                val anchor = frames[anchoredAt]
                if (anchor.className != target.declaringClass.name) {
                    "锚点帧=${anchor.className}#${anchor.methodName}（与 ${target.declaringClass.name} 不一致，疑被代理）；"
                } else ""
            } else ""
            val tail = if (anchoredAt >= 0) {
                frames.drop(anchoredAt + 1)
            } else {
                frames.drop(1).filterNot { isHookPlumbing(it) }
            }
            val text = tail.take(10).joinToString(" <- ") { "${it.className}#${it.methodName}" }
            val capped = if (text.length > 700) text.take(700) + "…" else text
            if (anchoredAt >= 0) anchorNote + capped else "（未找到锚点帧）" + capped
        }.getOrDefault("（无法获取调用栈）")

        /** Hook 调度链自身的帧；判断用全限定名，混淆与非混淆构建都适用。 */
        private fun isHookPlumbing(frame: StackTraceElement): Boolean {
            val name = frame.className
            val method = frame.methodName
            return name.startsWith("de.robv.android.xposed") ||
                name.contains("XC_MethodHook") ||
                name.contains("XposedBridge") ||
                name.contains("LSPosed") ||
                method == "beforeHookedMethod" ||
                method == "afterHookedMethod" ||
                method == "intercept" ||
                method == "proceed"
        }

        private fun hookFirstHit(
            method: Method,
            enabled: () -> Boolean,
            label: String,
            logText: String,
            result: Any? = null
        ): Boolean {
            val logged = AtomicBoolean(false)
            return runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        dropped.incrementAndGet()
                        if (logged.compareAndSet(false, true)) HLog.boot("$TAG $logText $label")
                        param.setResult(result)
                    }
                })
                true
            }.onFailure {
                HLog.e("$TAG hook $label 失败: ${it.message}", it)
            }.getOrDefault(false)
        }

        /**
         * 通路二：广告数据下发出口。
         *
         * `H1(String eventName, String json)` 是 MagicBrush 的 sendEvent 唯一出口，
         * 三个广告事件名都是明文常量，命中就整条丢弃 —— 数据不进 JS，Flutter 不会建卡。
         *
         * 只挂第一个能定位到的出口就停：`mu0.i` 是 MagicBrush 运行时，方法表极大，
         * 为了兜底把它一起加载并解析，会让上千个反射对象常驻，是进程内存上涨的
         * 主要来源之一。`wx.i1` 命中时完全不需要碰它。
         *
         * @return 命中的类名；全部定位失败返回 null
         */
        private fun hookEventGate(loader: ClassLoader, enabled: () -> Boolean): String? {
            for (className in EVENT_GATE_CLASSES) {
                val method = lookupSendEvent(className, loader)
                if (method == null) {
                    HLog.event("$TAG 下发出口 $className#H1 不存在（微信 ${wechatVersion()}）")
                    continue
                }
                if (method.returnType != java.lang.Void.TYPE) {
                    HLog.event("$TAG 下发出口 $className#H1 签名不匹配")
                    continue
                }
                // wx.i1 是广告卡专用基类（只有 wx.b0 / wx.c2 两个子类，都由
                // MagicAdBrandServiceFeatureService 创建），它的 H1(key,value) 只转调
                // super.sendEvent，发给 Flutter 的全是广告内容 —— 全量丢弃，不认名字。
                // 广告帧用的 key 并不是那三个 onXxxAdServerInfo，这也是之前只按名字
                // 丢弃却拦不住的原因。
                val dropAll = className in AD_CARD_EVENT_CANDIDATES
                val ok = runCatching {
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!enabled()) return
                            val key = param.args.getOrNull(0) as? String ?: return
                            if (!dropAll && key !in AD_EVENTS) return
                            dropped.incrementAndGet()
                            val payload = param.args.getOrNull(1) as? String
                            val line = "$TAG 丢弃广告事件 $key len=${payload?.length ?: 0}"
                            if (eventGateLogged.compareAndSet(false, true)) HLog.boot(line)
                            else HLog.event(line)
                            // setResult(null) 才会置 returnEarly，原方法不执行，事件不进 JS 队列。
                            param.setResult(null)
                        }
                    })
                    true
                }.onFailure {
                    HLog.e("$TAG hook $className#H1 失败: ${it.message}", it)
                }.getOrDefault(false)
                if (ok) return className
            }
            return null
        }

        /**
         * 定位 sendEvent 出口。
         *
         * 先按 (String, String) 精确取；签名对不上时只在**这个类**内部扫一遍方法表兜底。
         * 不做递归/父类查找——`H1` 就在出口类自己身上，扫父类会把 Flutter 基类整条链拉进来。
         */
        private fun lookupSendEvent(className: String, loader: ClassLoader): Method? {
            val cls = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: return null
            runCatching {
                cls.getDeclaredMethod("H1", String::class.java, String::class.java)
                    .also { it.isAccessible = true }
            }.getOrNull()?.let { return it }
            return runCatching {
                cls.declaredMethods.firstOrNull { m ->
                    m.name == "H1" && m.returnType == java.lang.Void.TYPE && m.parameterTypes.size == 2
                }?.also { it.isAccessible = true }
            }.getOrNull()
        }

        /**
         * 兜底：native → Java 的广告事件回调。
         *
         * 只拦 `ZIDL_AV`（onAdServerInfoEvent）与 `ZIDL_BV`（onRecFeedsAdServerInfoEvent），
         * 不碰 `ZIDL_DV`（onOftenReadAdCards，常读栏的正常业务也走这条）。
         */
        private fun hookNativeAdEvents(loader: ClassLoader, enabled: () -> Boolean): Int {
            var installed = 0
            for (className in NATIVE_EVENT_CLASSES) {
                for (name in NATIVE_AD_EVENTS) {
                    val method = exactMethod(
                        className,
                        loader,
                        name,
                        Integer.TYPE,
                        ByteArray::class.java
                    ) ?: continue
                    if (method.returnType != java.lang.Void.TYPE) continue
                    val ok = runCatching {
                        HookRegistry.get().hook(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!enabled()) return
                                dropped.incrementAndGet()
                                if (nativeGateLogged.compareAndSet(false, true)) {
                                    HLog.boot("$TAG 拦下 native 广告事件 $className#$name")
                                } else {
                                    HLog.event("$TAG 拦下 native 广告事件 $className#$name")
                                }
                                param.setResult(null)
                            }
                        })
                        true
                    }.onFailure {
                        HLog.e("$TAG hook $className#$name 失败: ${it.message}", it)
                    }.getOrDefault(false)
                    if (ok) installed++
                }
            }
            return installed
        }

        /** m1 的 4 个 boolean 广告方法；明文类优先，签名严格校验。 */
        private fun locateJniMethods(loader: ClassLoader): List<Method> {            val cls = runCatching { Class.forName(JNI_CLASS, false, loader) }
                .onFailure { HLog.e("$TAG 加载广告 native 桥失败: ${it.message}", it) }
                .getOrNull() ?: return emptyList()
            val result = ArrayList<Method>(JNI_METHODS.size)
            for (name in JNI_METHODS) {
                val params = if (name == "e") {
                    arrayOf(java.lang.Long.TYPE, String::class.java)
                } else {
                    arrayOf(String::class.java, String::class.java)
                }
                val method = runCatching {
                    cls.getDeclaredMethod(name, *params).also { it.isAccessible = true }
                }.getOrNull() ?: continue
                if (Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != java.lang.Boolean.TYPE) continue
                result.add(method)
            }
            if (result.isEmpty()) {
                HLog.e("$TAG 未找到广告 native 桥 m1（微信 ${wechatVersion()}）", null)
            }
            return result
        }

        /**
         * 事件门定位失败时的延迟重试。
         *
         * install 在 Application.onCreate 之后几百毫秒就跑，此时个别 Flutter 运行时类
         * 可能还没完成链接，Class.forName 会抛 NoClassDefFoundError。重试只在失败路径上
         * 发生，最多 3 次，成功即停；不会重复挂拉取门，也不会重复加载已命中的出口。
         */
        private fun scheduleGateRetry(loader: ClassLoader, enabled: () -> Boolean, attempt: Int) {
            if (attempt > 3) {
                HLog.boot("$TAG 下发事件门三次重试仍未命中，本进程仅保留 JSAPI 拉取门")
                return
            }
            val delay = when (attempt) {
                1 -> 4_000L
                2 -> 10_000L
                else -> 20_000L
            }
            runCatching {
                retryExecutor.schedule({
                    try {
                        val gate = hookEventGate(loader, enabled)
                        val target = gate
                            ?: if (hookNativeAdEvents(loader, enabled) > 0) "native回调" else null
                        if (target != null) {
                            mounted = true
                            HLog.boot("$TAG 下发事件门延迟命中：$target（第 $attempt 次重试）")
                        } else {
                            scheduleGateRetry(loader, enabled, attempt + 1)
                        }
                    } catch (t: Throwable) {
                        HLog.e("$TAG 事件门重试异常: ${t.message}", t)
                    }
                }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.onFailure {
                HLog.e("$TAG 事件门重试任务提交失败: ${it.message}", it)
            }
        }

        /**
         * 按签名精确取方法。
         *
         * 不用「遍历整张方法表再过滤」：`ZIDL_fvl8iIsEB` 有上百个 native 回调，
         * `mu0.i` 更是 Flutter 运行时大类，全表 resolve 会为每个方法创建 Method 对象
         * 并逐个 setAccessible，既慢又常驻内存。getDeclaredMethod 只造需要的那一个。
         */
        private fun exactMethod(
            className: String,
            loader: ClassLoader,
            name: String,
            vararg params: Class<*>
        ): Method? = runCatching {
            val cls = Class.forName(className, false, loader)
            cls.getDeclaredMethod(name, *params).also { it.isAccessible = true }
        }.getOrNull()

        /**
         * 微信版本名，永不返回 null。
         *
         * 优先走公共 API；但挂载发生在 `ModuleEntry.onCreate` 之后几百毫秒，
         * 那时 `WechatApiFeature` 可能还没把 `WeChatApis.versionApi` 注入进去，
         * `WeChatApis.version()` 返回 null，日志就成了"微信 未知"。
         * 所以退一步用 `WeChatVersionApi.build(...)` 这个纯静态入口自己查一次：
         * 内部有 3 秒缓存，代价只有一次 getPackageInfo。
         */
        private fun wechatVersion(): String {
            runCatching {
                h.Hchat.hooks.api.core.WeChatApis.version()?.current()?.versionName
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            return runCatching {
                val ctx = hostContext ?: return@runCatching "未知"
                h.Hchat.hooks.api.runtime.WeChatVersionApi.build(ctx, hostClassLoader)
                    .versionName.ifBlank { "未知" }
            }.getOrDefault("未知")
        }
    }
}
