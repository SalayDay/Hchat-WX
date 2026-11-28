package h.Hchat.hooks.items.articleads

import android.content.SharedPreferences
import android.view.View
import android.webkit.ValueCallback
import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 独立的文章页 DOM 处理；不修改已验证的信息流 JNI 拦截。 */
class RemoveArticleAdsFeature : BaseFeature() {
    private var installedRuntime: ArticleAdsRuntime? = null

    override fun featureId() = ID
    override fun name() = "去除公众号文章内广告"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(object : SimpleFeatureSettingsProvider(
            ID, name(), "处理公众号文章中已识别的文中、文末和评论广告，保留正文和正常评论",
            FeatureSettingsProvider.CATEGORY_PRACTICAL
        ) {})
    }

    override fun onFeatureInstall(context: FeatureContext) {
        try {
            val worker = ArticleAdsRuntime(context)
            installedRuntime = worker
            runtime = worker
            worker.install()
        } catch (failure: Throwable) {
            runCatching { installedRuntime?.destroy() }
            if (runtime === installedRuntime) runtime = null
            installedRuntime = null
            mounted = false
            lastError = "安装失败：${failure.javaClass.simpleName}"
            HLog.e("$TAG 文章处理初始化失败", failure)
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        installedRuntime?.destroy()
        if (runtime === installedRuntime) runtime = null
        installedRuntime = null
        mounted = false
    }

    companion object {
        const val ID = "remove_article_ads"
        const val KEY_ENABLE = "enabled"
        const val DEFAULT_ENABLE = false
        private const val TAG = "[Hchat:ArticleAds]"
        @Volatile private var runtime: ArticleAdsRuntime? = null
        @Volatile private var mounted = false
        @Volatile private var lastError = ""
        @Volatile private var lastCheckedAt = 0L
        @Volatile private var lastStatus = "尚未检查文章"
        @Volatile private var lastRegions = "尚无记录"
        private val checked = AtomicInteger()
        private val hidden = AtomicLong()

        data class Stats(
            val mounted: Boolean, val checked: Int, val hidden: Long,
            val lastCheckedAt: Long, val status: String, val error: String, val regions: String
        )
        fun stats() = Stats(mounted, checked.get(), hidden.get(), lastCheckedAt, lastStatus, lastError, lastRegions)
        fun refresh() { runtime?.refresh() }
        fun resetStats() {
            checked.set(0)
            hidden.set(0)
            lastCheckedAt = 0
            lastStatus = "统计已清空"
            lastRegions = "尚无记录"
        }

        private class PageState(val url: String) {
            val nonce: String = UUID.randomUUID().toString()
            var observedHidden = 0L
            var applied = false
            /** 上一次写日志时的"检查结果指纹"；相同就不再写，稳态检查在日志里静默。 */
            var loggedSignature: String? = null
        }

        /** 仅持有宿主应用配置及弱 View 引用；没有常驻计时轮询。 */
        private class ArticleAdsRuntime(private val context: FeatureContext) {
            private val prefs = context.configStore().getFeaturePrefs(ID)
            private val pages = WeakHashMap<View, PageState>()
            private lateinit var script: String
            private lateinit var evaluate: Method
            private lateinit var getUrl: Method
            private var destroyed = false
            private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == KEY_ENABLE) refresh()
            }

            fun install() {
                val version = context.hostContext().packageManager
                    .getPackageInfo(context.hostContext().packageName, 0).versionName.orEmpty()
                if (version != "8.0.80") {
                    lastError = "当前仅核对微信 8.0.80，检测到 $version"
                    HLog.e("$TAG $lastError")
                    return
                }
                val loader = context.hostClassLoader()
                val web = KavaReflector.loadClass("com.tencent.xweb.WebView", loader)
                    ?: error("XWeb WebView 不存在")
                val mmWeb = KavaReflector.loadClass("com.tencent.mm.ui.widget.MMWebView", loader)
                    ?: error("MMWebView 不存在")
                val client = KavaReflector.loadClass("com.tencent.mm.plugin.webview.core.f3", loader)
                    ?: error("文章控制器不存在")
                val activity = KavaReflector.loadClass("com.tencent.mm.plugin.webview.ui.tools.WebViewUI", loader)
                    ?: error("网页容器不存在")
                val finish = KavaReflector.findDeclaredMethod(client, "p", web, String::class.java)
                    ?: error("文章页面完成回调不存在")
                check(finish.returnType == Void.TYPE && !KavaReflector.isStatic(finish))
                evaluate = KavaReflector.findMethodRecursive(
                    mmWeb, "evaluateJavascript", String::class.java, ValueCallback::class.java
                ) ?: error("脚本执行接口不存在")
                getUrl = KavaReflector.findMethodRecursive(mmWeb, "getUrl")
                    ?: error("网页地址接口不存在")
                script = readScript()
                check(script.contains("__HCHAT_ARTICLE_CONFIG__")) { "文章脚本配置占位缺失" }
                val webField = KavaReflector.findDeclaredField(activity, "f")
                    ?: error("网页字段不存在")
                check(webField.type == mmWeb && evaluate.returnType == Void.TYPE && getUrl.returnType == String::class.java)
                HookRegistry.get().hook(finish, ArticleFinishedHooker(this, mmWeb))
                for (name in listOf("onResume", "onPause", "onDestroy")) {
                    val method = KavaReflector.findDeclaredMethod(activity, name) ?: continue
                    HookRegistry.get().hook(method, ArticleLifecycleHooker(this, name, webField))
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                mounted = true
                lastError = ""
                HLog.e("$TAG 已挂载文章页生命周期，微信 $version；处理规则需以实际命中为准")
            }

            private fun readScript(): String {
                val resource = RemoveArticleAdsFeature::class.java.classLoader
                    ?.getResourceAsStream("assets/article_ad_filter.js")
                val input = resource ?: context.moduleContext().assets.open("article_ad_filter.js")
                return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            private fun enabled() = !destroyed && prefs.getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

            private fun currentUrl(view: View): String? = runCatching {
                KavaReflector.invokeOrThrow(getUrl, view) as? String
            }.getOrNull()

            private fun currentPage(view: View): PageState? = synchronized(pages) { pages[view] }

            private fun rememberPage(view: View, url: String) {
                if (!ArticlePagePolicy.isArticleUrl(url)) {
                    synchronized(pages) { pages.remove(view) }
                    return
                }
                val documentUrl = url.substringBefore('#')
                // 同一文档重复回调（微信会多次触发 onPageFinished）复用原状态：
                // 之前每次重建都会重置 nonce，旧实例 stop() 恢复广告，造成广告闪一下又消失。
                val state = synchronized(pages) {
                    val previous = pages[view]
                    if (previous == null || previous.url != documentUrl) {
                        PageState(documentUrl).also {
                            it.applied = previous?.applied == true
                            pages[view] = it
                        }
                    } else previous
                }
                if (enabled()) dispatch(view, state, true)
                else if (state.applied) dispatch(view, state, false)
            }

            private fun dispatch(view: View, state: PageState, active: Boolean, paused: Boolean = false) {
                val reference = WeakReference(view)
                view.post {
                    val target = reference.get() ?: return@post
                    if (!target.isAttachedToWindow) return@post
                    if (active && (destroyed || !enabled())) return@post
                    if (!active && !state.applied) return@post
                    if (currentPage(target) !== state && !destroyed) return@post
                    val url = currentUrl(target) ?: return@post
                    if (!ArticlePagePolicy.isArticleUrl(url)) return@post
                    if (active && url.substringBefore('#') != state.url) return@post
                    if (active) state.applied = true
                    // 诊断只在前几次和每 20 次取一次样本，避免每次页面变化都遍历全树。
                    val checkedNow = checked.get()
                    val diagnostics = HLog.isCaptureEnabled() && (checkedNow < 6 || checkedNow % 20 == 0)
                    val config = JSONObject().put("enabled", active).put("nonce", state.nonce)
                        .put("paused", active && paused)
                        .put("diagnostics", diagnostics)
                    val code = script.replace("__HCHAT_ARTICLE_CONFIG__", config.toString())
                    val callback = ValueCallback<String> { raw ->
                        val currentView = reference.get() ?: return@ValueCallback
                        if (destroyed || currentPage(currentView) !== state) return@ValueCallback
                        receive(raw, state, active)
                    }
                    runCatching { KavaReflector.invokeOrThrow(evaluate, target, code, callback) }
                        .onFailure { failure ->
                            lastError = "文章脚本执行失败：${failure.javaClass.simpleName}"
                            HLog.e("$TAG $lastError", failure)
                        }
                }
            }

            private fun receive(raw: String?, state: PageState, active: Boolean) {
                runCatching {
                    check((raw?.length ?: 0) <= 32768) { "脚本回执过大" }
                    val decoded = JSONTokener(raw ?: "null").nextValue()
                    val data = when (decoded) {
                        is String -> JSONObject(decoded)
                        is JSONObject -> decoded
                        else -> error("无脚本回执")
                    }
                    if (data.optString("nonce") != state.nonce) return
                    val status = data.optString("status")
                    check(status != "error" && status != "unsupported") { "文章脚本未进入有效处理状态" }
                    if (status == "paused") {
                        if (active) lastStatus = "本页已暂停检查，回到文章后自动恢复"
                        return
                    }
                    if (!active) state.applied = false
                    val total = data.optLong("hiddenTotal", 0).coerceIn(0, 1000000)
                    val counts = data.optJSONObject("counts") ?: JSONObject()
                    fun count(key: String) = counts.optLong(key).coerceIn(0, 1000000)
                    lastRegions = "文中 ${count("body")} / 文末 ${count("footer")} / 评论 ${count("comments")} / " +
                        "其他 ${count("header") + count("sponsor") + count("modal") + count("tips")}"
                    // 页面真重载后脚本从 0 重新计数，基准同步归零，避免少计。
                    if (total < state.observedHidden) state.observedHidden = 0
                    val delta = (total - state.observedHidden).coerceAtLeast(0)
                    state.observedHidden = total
                    hidden.addAndGet(delta)
                    checked.incrementAndGet()
                    lastCheckedAt = System.currentTimeMillis()
                    lastError = ""
                    lastStatus = if (!active) "本页广告处理已关闭，已恢复页面元素"
                        else if (total > 0) "本页匹配并处理 $total 个广告容器"
                        else "本页暂未匹配广告容器；延迟内容由页面事件继续检查"
                    // 每条回执都打会把 2000 条环形缓冲刷掉，而页面每 12 秒就检查一次，稳态内容完全一样。
                    // 这里用"结果指纹"去重：只有 hidden/区域计数/状态变化时才写日志，
                    // 重复检查在日志里彻底静默（也省掉每次拼候选 JSON 的分配）。
                    // 采集关闭时不拼指纹（否则每次检查都白造一个字符串）。
                    val signature = if (HLog.isCaptureEnabled()) "$active|$total|$lastRegions|$status" else null
                    if (signature != null && signature != state.loggedSignature) {
                        state.loggedSignature = signature
                        val candidates = JSONArray()
                        val entries = data.optJSONArray("candidates")
                        for (index in 0 until minOf(entries?.length() ?: 0, 8)) {
                            val entry = entries?.optJSONObject(index) ?: continue
                            candidates.put(JSONObject().put("id", entry.optString("id").take(64))
                                .put("class", entry.optString("className").take(96)))
                        }
                        val matchedEntries = data.optJSONArray("matchedIds")
                        val matched = StringBuilder()
                        for (index in 0 until minOf(matchedEntries?.length() ?: 0, 12)) {
                            val rule = matchedEntries?.optString(index).orEmpty()
                            if (rule.isEmpty()) continue
                            if (matched.isNotEmpty()) matched.append(',')
                            matched.append(rule)
                        }
                        HLog.event("$TAG 文章检查 enabled=$active hidden=$total regions=$lastRegions " +
                            "matched=[$matched] status=${status.take(48)} candidates=$candidates")
                    }
                }.onFailure { failure ->
                    lastError = "文章脚本回执异常：${failure.javaClass.simpleName}"
                    HLog.e("$TAG $lastError", failure)
                }
            }

            fun refresh() {
                val copy = synchronized(pages) { pages.entries.map { WeakReference(it.key) to it.value } }
                for ((reference, state) in copy) {
                    val view = reference.get() ?: continue
                    if (enabled()) currentUrl(view)?.let { rememberPage(view, it) }
                    else dispatch(view, state, false)
                }
            }

            fun destroy() {
                destroyed = true
                runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
                val copy = synchronized(pages) { pages.entries.map { WeakReference(it.key) to it.value } }
                if (::evaluate.isInitialized && ::script.isInitialized && ::getUrl.isInitialized) {
                    for ((reference, state) in copy) reference.get()?.let { dispatch(it, state, false) }
                }
                synchronized(pages) { pages.clear() }
            }

    private class ArticleFinishedHooker(private val owner: ArticleAdsRuntime, private val mmWeb: Class<*>) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val args = chain.args.toTypedArray()
            val receiver = chain.thisObject
            var result = chain.proceed()
            with(owner) {
            run update@{
                if (destroyed) return@update
                val view = args?.getOrNull(0) as? View ?: return@update
                if (!mmWeb.isInstance(view)) return@update
                val url = args?.getOrNull(1) as? String ?: return@update
                rememberPage(view, url)
            }
            return result
            }
        }
    }

    private class ArticleLifecycleHooker(private val owner: ArticleAdsRuntime, private val name: String, private val webField: java.lang.reflect.Field) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val args = chain.args.toTypedArray()
            val receiver = chain.thisObject
            with(owner) {
            run patch@{
                if (name != "onPause" || destroyed) return@patch
                val view = KavaReflector.readField(webField, receiver) as? View ?: return@patch
                // 切到后台只停扫描、保留已隐藏结果，避免返回时广告重新闪一下。
                val state = currentPage(view) ?: return@patch
                if (state.applied) dispatch(view, state, enabled(), true)
            }
            var result = chain.proceed(args)
            run update@{
                if (destroyed) return@update
                val view = KavaReflector.readField(webField, receiver) as? View ?: return@update
                if (name == "onDestroy") {
                    synchronized(pages) { pages.remove(view) }
                } else if (name == "onResume") {
                    currentUrl(view)?.let { rememberPage(view, it) }
                }
            }
            return result
            }
        }
    }

}
    }
}
