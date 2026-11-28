package h.Hchat.hooks.items.inputhint

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.script.ScriptSendButtonHook
import h.Hchat.utils.KavaReflector
import java.util.Collections
import java.util.WeakHashMap

class InputHintFeature : BaseFeature() {
    private var runtime: InputHintRuntime? = null
    private var statisticsInstalled = false

    override fun featureId(): String = ID

    override fun name(): String = "输入框提示"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(InputHintSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = InputHintRuntime(context.hostContext(), context.hostClassLoader(), ::logError)
        runtime?.installInputHook()
        installStatistics()
        subscribe(Events.DexReady::class.java) {
            runtime?.installInputHook()
            installStatistics()
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
        statisticsInstalled = false
    }

    private fun installStatistics() {
        if (statisticsInstalled) return
        val subscription = runtime?.installStatistics() ?: return
        trackSubscription(subscription)
        statisticsInstalled = true
    }

    companion object {
        const val ID = "input_hint"
    }
}

private class InputHintRuntime(
    context: Context,
    private val classLoader: ClassLoader,
    private val logger: (String, Throwable?) -> Unit
) {
    private val appContext = context.applicationContext ?: context
    private val prefs = InputHintSettings.preferences(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputBindings = Collections.synchronizedMap(
        WeakHashMap<Any, InputHintBinding>()
    )
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == InputHintSettings.KEY_ENABLE ||
            key == InputHintSettings.KEY_TEMPLATE ||
            key == InputHintSettings.KEY_STATISTICS_ENABLE
        ) {
            refreshAttachedInputs()
        }
    }

    @Volatile
    private var active = true

    @Volatile
    private var inputHookInstalled = false

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    @Synchronized
    fun installInputHook(): Boolean {
        if (inputHookInstalled) return true
        val footerClass = KavaReflector.loadClass(CHAT_FOOTER_CLASS, classLoader) ?: run {
            logger("未找到微信聊天输入区类", null)
            return false
        }
        val attachMethod = KavaReflector.declaredMethods(footerClass).singleOrNull { method ->
            method.name == "onAttachedToWindow" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.isEmpty()
        } ?: run {
            logger("未找到聊天输入区挂载方法", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(attachMethod, FooterAttachHooker(this))
            inputHookInstalled = true
            true
        }.getOrElse {
            logger("聊天输入框提示 Hook 安装失败", it)
            false
        }
    }

    private class FooterAttachHooker(private val runtime: InputHintRuntime) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runtime.bindFooter(chain.thisObject)
            return result
        }
    }

    fun installStatistics(): OutgoingMessageStatsRepository.Subscription? {
        if (!OutgoingMessageStatsRepository.install(logger)) {
            logger("消息变更 API 未就绪，发送统计暂不可用", null)
            return null
        }
        return OutgoingMessageStatsRepository.subscribe(::refreshAttachedInputs)
    }

    fun destroy() {
        active = false
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        val inputs = synchronized(inputBindings) {
            inputBindings.entries.map { it.key to it.value }.also { inputBindings.clear() }
        }
        inputs.forEach { (input, binding) ->
            postToInput(input) {
                if (binding.appliedHint != null && TextUtils.equals(readHint(input), binding.appliedHint)) {
                    writeHint(input, binding.originalHint)
                }
            }
        }
    }

    private fun bindFooter(footer: Any?) {
        if (!active || footer == null) return
        // 这个回调挂在 ChatFooter.onAttachedToWindow 上，正处于 View 树 attach 期间。
        // 原来在这里直接做全量反射扫描（遍历所有字段 + 每个字段类型沿继承链找方法），
        // 一旦耗时就会顶住输入分发 —— 线上 ANR 报告就是 "Waited 8001ms for MotionEvent"。
        // 现在只做一次纯缓存查询，未命中就把解析推到下一个消息，让 attach 先完成。
        val cached = ScriptSendButtonHook.findInputViewCached(footer)
        if (cached != null) {
            bindInput(cached)
            postToInput(cached) { bindInput(cached) }
            return
        }
        val view = footer as? View
        if (view != null) {
            view.post { resolveAndBind(footer) }
        } else {
            mainHandler.post { resolveAndBind(footer) }
        }
    }

    private fun resolveAndBind(footer: Any?) {
        if (!active || footer == null) return
        val input = ScriptSendButtonHook.findInputView(footer) ?: return
        bindInput(input)
        postToInput(input) { bindInput(input) }
    }

    private fun bindInput(input: Any) {
        ScriptSendButtonHook.trackInputView(input)
        if (!supportsHint(input)) return
        synchronized(inputBindings) {
            if (!inputBindings.containsKey(input)) {
                inputBindings[input] = InputHintBinding(readHint(input))
            }
        }
        applyHint(input)
    }

    private fun refreshAttachedInputs() {
        val inputs = synchronized(inputBindings) { inputBindings.keys.toList() }
        mainHandler.post {
            inputs.forEach { input ->
                if ((input as? View)?.isAttachedToWindow != false) applyHint(input)
            }
        }
    }

    private fun applyHint(input: Any) {
        if (!active) return
        val binding = synchronized(inputBindings) { inputBindings[input] } ?: return
        if (!prefs.getBoolean(InputHintSettings.KEY_ENABLE, InputHintSettings.DEFAULT_ENABLE)) {
            if (binding.appliedHint != null && TextUtils.equals(readHint(input), binding.appliedHint)) {
                writeHint(input, binding.originalHint)
            }
            binding.appliedHint = null
            return
        }
        val statisticsEnabled = prefs.getBoolean(
            InputHintSettings.KEY_STATISTICS_ENABLE,
            InputHintSettings.DEFAULT_STATISTICS_ENABLE
        )
        val stats = if (statisticsEnabled) {
            OutgoingMessageStatsRepository.current()
        } else {
            InputHintStats()
        }
        val renderedHint = InputHintSettings.renderTemplate(InputHintSettings.template(prefs), stats)
        val currentHint = readHint(input)
        if (binding.appliedHint == null || !TextUtils.equals(currentHint, binding.appliedHint)) {
            binding.originalHint = currentHint
        }
        binding.appliedHint = renderedHint
        writeHint(input, renderedHint)
    }

    private fun supportsHint(input: Any): Boolean {
        return input is TextView || KavaReflector.findMethodRecursive(
            input.javaClass,
            "setHint",
            CharSequence::class.java
        ) != null
    }

    private fun readHint(input: Any): CharSequence? {
        if (input is TextView) return input.hint
        return KavaReflector.invoke(
            KavaReflector.findMethodRecursive(input.javaClass, "getHint"),
            input
        ) as? CharSequence
    }

    private fun writeHint(input: Any, hint: CharSequence?) {
        if (input is TextView) {
            input.hint = hint
            return
        }
        KavaReflector.invokeSuccessfully(
            KavaReflector.findMethodRecursive(
                input.javaClass,
                "setHint",
                CharSequence::class.java
            ),
            input,
            hint
        )
    }

    private fun postToInput(input: Any, block: () -> Unit) {
        val view = input as? View
        if (view != null && view.isAttachedToWindow) {
            view.post { block() }
        } else {
            mainHandler.post { block() }
        }
    }

    companion object {
        private const val CHAT_FOOTER_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    }

    private data class InputHintBinding(
        var originalHint: CharSequence?,
        var appliedHint: CharSequence? = null
    )
}
