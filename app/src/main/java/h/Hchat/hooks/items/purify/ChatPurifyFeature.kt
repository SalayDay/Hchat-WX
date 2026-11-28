package h.Hchat.hooks.items.purify

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.api.message.ChatAdapterBindLocator
import h.Hchat.hooks.api.message.ChatAdapterBinding
import h.Hchat.hooks.api.message.LocalNoticeProtectionStore
import h.Hchat.hooks.api.message.NativeSystemMessageAccess
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * 界面净化：隐藏列表分割线、隐藏聊天里的系统灰字提示。
 *
 * 两项都只做「识别明确的目标就处理、识别不到就完全不动」：
 * - 分割线：只在首页会话列表上跳过类名含 Divider/Separator 的 ItemDecoration；
 * - 系统提示：按原生消息字段识别 type=10000；保留精确登记的防撤回及原生红包领取提示。
 */
class ChatPurifyFeature : BaseFeature() {
    private var runtime: ChatPurifyRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "界面净化"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ChatPurifySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        // 安装结果一律走 boot 缓冲：功能在启动期安装，而日志采集通常是之后才打开。
        runtime = ChatPurifyRuntime(context) { message, throwable ->
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
        const val ID = "chat_purify"
        const val KEY_HIDE_DIVIDER = "hide_divider"
        const val KEY_HIDE_SYSTEM_TIP = "hide_system_tip"
        const val DEFAULT_HIDE_DIVIDER = false
        const val DEFAULT_HIDE_SYSTEM_TIP = false

        /** 微信的系统提示消息类型。 */
        const val MESSAGE_TYPE_SYSTEM = 10000
    }
}

private class ChatPurifyRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = context.configStore().getFeaturePrefs(ChatPurifyFeature.ID)
    private val cachePrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_chat_purify_cache")
    private var installed = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val protectedNotices = LocalNoticeProtectionStore.get(context.hostContext())
    private val hiddenItems = OwnedVisibilityState<View>({ it.visibility }, { item, state -> item.visibility = state })
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ChatPurifyFeature.KEY_HIDE_SYSTEM_TIP &&
            !enabled(ChatPurifyFeature.KEY_HIDE_SYSTEM_TIP, ChatPurifyFeature.DEFAULT_HIDE_SYSTEM_TIP)) {
            mainHandler.post { hiddenItems.restoreAll() }
        }
    }
    init { prefs.registerOnSharedPreferenceChangeListener(settingsListener) }

    /** 诊断结果各记一次，便于从日志定位卡点。 */
    private val diagnostics = Collections.synchronizedSet(HashSet<String>())

    /** 同一轮绑定里两参/三参层层回调的深度，只在最外层处理一次。 */
    private val bindDepth = ThreadLocal<Int>()

    /** holder 类 → itemView 字段；扫过一次就不再扫。 */
    private val itemViewFields = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    /** holder 类 → 消息字段（或 NONE_FIELD 表示没有）。 */
    private val messageFields = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    fun install(): Boolean {
        if (installed) return true
        // 安装任务在 DexInstallScheduler 后台执行；绑定只读 cachedSelfWxId，不触发数据库查询。
        if (protectedNotices.hasEntries()) WeChatApis.account()?.selfWxId()
        val dividerHooked = installDividerHook()
        val tipHooked = installSystemTipHook()
        installed = dividerHooked || tipHooked
        if (!installed) logger("界面净化：两项都未安装成功", null)
        return installed
    }

    fun destroy() {
        installed = false
        prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) hiddenItems.restoreAll()
        else mainHandler.post { hiddenItems.restoreAll() }
        itemViewFields.clear()
        messageFields.clear()
    }

    // ------------------------------------------------------------ 隐藏分割线

    private fun installDividerHook(): Boolean {
        val recyclerClass = KavaReflector.loadClass(
            "androidx.recyclerview.widget.RecyclerView",
            context.hostClassLoader()
        ) ?: return false
        // 8.0.80 里 addItemDecoration 与 ItemDecoration 都被混淆（实测方法名 `P`、
        // 参数类型 `RecyclerView$a`），按名字和类型都找不到。
        // 但它一定满足「声明在 RecyclerView、返回 void、只有一个非基本类型参数」，
        // 因此按这个形状把候选全挂上，运行时再看参数类名是不是分割线。
        val candidates = KavaReflector.declaredMethods(recyclerClass).filter { candidate ->
            candidate.returnType == Void.TYPE &&
                !Modifier.isStatic(candidate.modifiers) &&
                candidate.parameterTypes.size == 1 &&
                !candidate.parameterTypes[0].isPrimitive
        }
        if (candidates.isEmpty()) {
            logger("界面净化：找不到疑似 addItemDecoration 的方法", null)
            return false
        }
        var hooked = 0
        for (method in candidates) {
            runCatching {
                HookRegistry.get().hook(method, DividerDecorationHooker(this))
                hooked++
            }.onFailure { logger("界面净化：分割线候选 Hook 失败 ${method.name}", it) }
        }
        if (hooked > 0) logger("界面净化：分割线候选已挂载 ×$hooked", null)
        return hooked > 0
    }

    /** 每种结果只记一次，避免刷屏。 */
    private fun diag(key: String, message: String) {
        if (diagnostics.add(key)) logger(message, null)
    }
    // -------------------------------------------------------------- 系统灰字

    private fun installSystemTipHook(): Boolean {
        // 真正执行的是继承链上最派生的 onBindViewHolder（含 payload 三参），
        // 定位器返回链上全部重载，运行时再按适配器实例过滤。
        val binding = ChatAdapterBindLocator.locate(context, logger)
        if (binding == null) {
            logger("界面净化：未定位到聊天绑定方法", null)
            return false
        }
        var hooked = 0
        binding.methods.forEach { method ->
            runCatching {
                HookRegistry.get().hook(method, SystemTipBindHooker(this, binding))
                hooked++
            }.onFailure {
                logger("界面净化：系统提示 Hook 安装失败 ${method.toGenericString()}", it)
            }
        }
        if (hooked == 0) {
            logger("界面净化：系统提示 Hook 安装失败", null)
            return false
        }
        logger(
            "界面净化：系统提示 Hook 已挂载 ×$hooked " +
                "(${binding.methods.joinToString("、") { "${it.declaringClass.simpleName}#${it.name}(${it.parameterCount})" }})" +
                "，适配器 ${binding.adapterClass.simpleName}" +
                "，开关 ${if (enabled(ChatPurifyFeature.KEY_HIDE_SYSTEM_TIP, ChatPurifyFeature.DEFAULT_HIDE_SYSTEM_TIP)) "开" else "关"}",
            null
        )
        return true
    }

    private class DividerDecorationHooker(private val runtime: ChatPurifyRuntime) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!runtime.enabled(
                    ChatPurifyFeature.KEY_HIDE_DIVIDER,
                    ChatPurifyFeature.DEFAULT_HIDE_DIVIDER
                )
            ) return chain.proceed()
            val recycler = chain.thisObject as? View ?: return chain.proceed()
            if (!recycler.javaClass.name.contains("conversation", ignoreCase = true)) return chain.proceed()
            val decoration = chain.args.getOrNull(0) ?: return chain.proceed()
            val name = decoration.javaClass.name
            if (name.contains("Divider", ignoreCase = true) ||
                name.contains("Separator", ignoreCase = true) ||
                name.contains("ItemOffset", ignoreCase = true)
            ) {
                runtime.diag("divider", "界面净化：已拦下分割线装饰 $name")
                return null
            }
            return chain.proceed()
        }
    }

    private class SystemTipBindHooker(
        private val runtime: ChatPurifyRuntime,
        private val binding: ChatAdapterBinding
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!binding.matches(chain.thisObject)) return chain.proceed()
            // 开关关闭时也需要恢复本功能之前隐藏的复用行，不能直接放行留下 GONE。
            val outermost = runtime.enterBind()
            try {
                if (outermost) {
                    chain.args.firstOrNull()?.let { runtime.holderItemView(it) }
                        ?.let { runtime.hiddenItems.restore(it) }
                }
                val result = chain.proceed()
                if (outermost && runtime.enabled(ChatPurifyFeature.KEY_HIDE_SYSTEM_TIP,
                        ChatPurifyFeature.DEFAULT_HIDE_SYSTEM_TIP)) runtime.applySystemTip(chain)
                return result
            } finally {
                runtime.exitBind()
            }
        }
    }

    /** 判断 holder 是不是系统提示消息，是则隐藏；只改 visibility，靠复用时恢复。 */
    private fun applySystemTip(chain: XposedInterface.Chain) {
        val started = h.Hchat.utils.SlowPathLog.now()
        val holder = chain.args.getOrNull(0) ?: return
        val view = holderItemView(holder) ?: return
        val message = holderMessage(holder, chain.thisObject, chain.args) ?: return
        val snapshot = NativeSystemMessageAccess.snapshot(message) ?: return
        if (snapshot.type == ChatPurifyFeature.MESSAGE_TYPE_SYSTEM) {
            val account = if (protectedNotices.hasEntries()) WeChatApis.account()?.cachedSelfWxId().orEmpty() else ""
            if (protectedNotices.preserve(account, snapshot.talker, snapshot.createTime,
                    snapshot.content, snapshot.localId, snapshot.serverId)) {
                diag("protected-notice", "界面净化：已保留登记来源的防撤回或红包领取提示")
            } else {
                hiddenItems.hide(view)
                diag("tip-hit", "界面净化：已命中并隐藏普通系统提示消息（首次）")
            }
        }
        h.Hchat.utils.SlowPathLog.report(
            "purify.bind",
            h.Hchat.utils.SlowPathLog.elapsedSince(started),
            SLOW_BIND_MS,
            "界面净化绑定处理",
            holder.javaClass.simpleName
        )
    }

    private fun enterBind(): Boolean {
        val depth = bindDepth.get() ?: 0
        bindDepth.set(depth + 1)
        return depth == 0
    }

    private fun exitBind() {
        val depth = (bindDepth.get() ?: 1) - 1
        if (depth <= 0) bindDepth.remove() else bindDepth.set(depth)
    }

    /** 结果按 holder 类缓存：绑定是热路径，不能每次都遍历整条字段表。 */
    private fun holderItemView(holder: Any): View? {
        val field = itemViewField(holder.javaClass) ?: return null
        return KavaReflector.readField(field, holder) as? View
    }

    private fun itemViewField(holderClass: Class<*>): java.lang.reflect.Field? {
        itemViewFields[holderClass]?.let { return it as? java.lang.reflect.Field }
        var found: java.lang.reflect.Field? = null
        var current: Class<*>? = holderClass
        while (current != null && current != Any::class.java) {
            found = KavaReflector.declaredFields(current).firstOrNull { it.type == View::class.java }
            if (found != null) break
            current = current.superclass
        }
        itemViewFields[holderClass] = found ?: NONE_FIELD
        return found
    }

    /**
     * 从 holder 上读它当前绑定的消息对象。
     *
     * 只接受具备微信原生消息持久化字段形状的对象；不把任意带 getType 的 holder 字段当消息。
     * 命中的字段按 holder 类缓存；取不到时用已核实的 adapter.getItem(position) 内存回退。
     */
    private fun holderMessage(holder: Any, owner: Any?, args: List<Any?>): Any? {
        val cached = messageFields[holder.javaClass]
        if (cached != null && cached !== NONE_FIELD) {
            (KavaReflector.readField(cached as java.lang.reflect.Field, holder))
                ?.takeIf { NativeSystemMessageAccess.isMessageClass(it.javaClass) }
                ?.let { return it }
        } else if (cached == null) {
            var current: Class<*>? = holder.javaClass
            while (current != null && current != Any::class.java) {
                for (field in KavaReflector.declaredFields(current)) {
                    if (field.type.isPrimitive || field.type == String::class.java) continue
                    val value = KavaReflector.readField(field, holder) ?: continue
                    if (NativeSystemMessageAccess.isMessageClass(value.javaClass)) {
                        messageFields[holder.javaClass] = field
                        return value
                    }
                }
                current = current.superclass
            }
            // 该类没有消息字段（例如系统提示 holder），记下来避免下次再扫。
            messageFields[holder.javaClass] = NONE_FIELD
        }
        val position = (args.getOrNull(1) as? Number)?.toInt() ?: return null
        // 8.0.80 的 getItem(int) 是 bridge，直接从适配器内存列表返回当前原生消息。
        return KavaReflector.invokeMethod(owner, "getItem", position)?.takeIf {
            NativeSystemMessageAccess.isMessageClass(it.javaClass)
        }
    }

    private fun enabled(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    private companion object {
        const val CACHE_KEY = "purify_v2"
        /** 字段扫描的负结果哨兵：ConcurrentHashMap 不能存 null。 */
        val NONE_FIELD = Any()
        /** 单次绑定处理超过这个耗时就记一条慢路径日志。 */
        const val SLOW_BIND_MS = 8L
    }
}

class ChatPurifySettingsProvider : h.Hchat.ui.SimpleFeatureSettingsProvider(
    ChatPurifyFeature.ID,
    "界面净化",
    "隐藏首页列表分割线、聊天里的系统灰字提示",
    h.Hchat.ui.FeatureSettingsProvider.CATEGORY_PRACTICAL
)
