package h.Hchat.hooks.items.multirecall

import android.app.Activity
import android.view.MenuItem
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.MultiSelectMessageMenuLocator
import h.Hchat.hooks.api.message.MultiSelectMessageResolver
import h.Hchat.hooks.api.message.MultiSelectMessageUi
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class MultiRecallFeature : BaseFeature() {
    private var hooker: MultiRecallHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "多选撤回"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MultiRecallSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = MultiRecallHooker(context, ::logError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    companion object {
        const val ID = "multi_recall"
    }
}

private class MultiRecallHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), MultiRecallSettings.PREFS_NAME)
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    fun install(): Boolean {
        val menuCreateMethod = MultiSelectMessageMenuLocator.menuCreateMethod(context, logger)
        val menuClickMethod = MultiSelectMessageMenuLocator.menuClickMethod(context, logger)
        val exitMethod = menuClickMethod?.let {
            MultiSelectMessageMenuLocator.multiSelectExitMethod(context, it, logger)
        }
        val createHooked = menuCreateMethod != null && exitMethod != null && hookMethod(menuCreateMethod, RecallMenuHook(this))
        val clickHooked = menuClickMethod != null && exitMethod != null && hookMethod(menuClickMethod, RecallMenuClickHook(this, exitMethod))
        if (!createHooked) logger("多选撤回菜单创建Hook未安装", null)
        if (!clickHooked) logger("多选撤回菜单点击Hook未安装", null)
        return createHooked && clickHooked
    }

    private fun addRecallMenu(receiver: Any?, args: Array<Any?>) {
        if (!isEnabled()) return
        val selected = MultiSelectMessageResolver.resolve(receiver)
        if (recallMessages(selected) == null) return
        val menu = args?.getOrNull(0) ?: return
        addMenuItem(menu)
    }

    private fun handleRecallMenuClick(receiver: Any?, args: Array<Any?>, exitMethod: Method): Boolean {
        if (!isEnabled()) return false
        val menuItem = args?.getOrNull(0) as? MenuItem ?: return false
        if (menuItem.itemId != MENU_ITEM_ID) return false

        val activity = currentActivity()
        val selected = MultiSelectMessageResolver.resolve(receiver)
        val messages = recallMessages(selected)
        if (messages == null) {
            toast(activity, if (selected.isEmpty()) "未找到选中的消息" else "只能批量撤回自己发送的消息")
            return true
        }
        val exitTarget = MultiSelectMessageUi.resolveExitTarget(receiver, exitMethod, logger)
        if (exitTarget == null) {
            toast(activity, "无法退出多选状态，请稍后重试")
            return true
        }
        val sender = WeChatApis.message().sender()
        val success = messages.count { message ->
            runCatching { sender?.revokeNative(message) == true }
                .onFailure { logger("批量撤回消息异常: msgId=${messageId(message)}", it) }
                .getOrDefault(false)
        }
        toast(activity, "已发起撤回 $success/${messages.size} 条消息")
        exitTarget.exit(logger)
        return true
    }

    private fun recallMessages(messages: List<Any>): List<Any>? {
        if (messages.isEmpty()) return null
        val ids = messages.map { message ->
            messageId(message).takeIf { it > 0L && isOutgoing(message, it) } ?: return null
        }
        return messages.takeIf { ids.distinct().size == messages.size }
    }

    private fun addMenuItem(menu: Any) {
        if (KavaReflector.invokeMethod(menu, "findItem", MENU_ITEM_ID) != null) return
        val added = KavaReflector.invokeMethod(menu, "add", 0, MENU_ITEM_ID, 0, MENU_TITLE)
            ?: KavaReflector.invokeMethod(menu, "add", 0, MENU_ITEM_ID, 0, MENU_TITLE as CharSequence)
        if (added != null) return
        KavaReflector.invokeMethod(menu, "f", MENU_ITEM_ID, MENU_TITLE)
            ?: KavaReflector.invokeMethod(menu, "f", MENU_ITEM_ID, MENU_TITLE as CharSequence)
    }

    private fun messageId(message: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID", "getId")) {
            (KavaReflector.invokeMethod(message, name) as? Number)?.toLong()?.takeIf { it > 0L }?.let { return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID", "id")) {
            (KavaReflector.readField(message, name) as? Number)?.toLong()?.takeIf { it > 0L }?.let { return it }
        }
        return 0L
    }

    private fun isOutgoing(message: Any, messageId: Long): Boolean {
        for (name in arrayOf("field_isSend", "isSend")) {
            when (val value = KavaReflector.readField(message, name)) {
                is Boolean -> return value
                is Number -> return value.toInt() != 0
            }
        }
        for (name in arrayOf("getIsSend", "isSend")) {
            when (val value = KavaReflector.invokeMethod(message, name)) {
                is Boolean -> return value
                is Number -> return value.toInt() != 0
            }
        }
        return WeChatApis.message().store()?.getMessageById(messageId)?.isSend == 1
    }

    private fun currentActivity(): Activity? {
        return (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeUnless { it.isFinishing }
    }

    private fun toast(activity: Activity?, text: String) {
        val target = activity ?: currentActivity() ?: return
        target.runOnUiThread { Toast.makeText(target, text, Toast.LENGTH_SHORT).show() }
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(MultiRecallSettings.KEY_ENABLE, MultiRecallSettings.DEFAULT_ENABLE)
    }

    private class RecallMenuHook(private val owner: MultiRecallHooker) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            owner.addRecallMenu(chain.thisObject, chain.args.toTypedArray())
            return result
        }
    }

    private class RecallMenuClickHook(private val owner: MultiRecallHooker, private val exitMethod: Method) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (owner.handleRecallMenuClick(chain.thisObject, chain.args.toTypedArray(), exitMethod)) return null
            return chain.proceed()
        }
    }

    private fun hookMethod(method: Method, callback: XposedInterface.Hooker): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, callback)
            true
        }.onFailure {
            hookedMethods.remove(method)
            logger("多选撤回Hook安装失败: ${method.toGenericString()}", it)
        }.getOrDefault(false)
    }

    companion object {
        private const val MENU_ITEM_ID = 0x48435243
        private const val MENU_TITLE = "批量撤回[H]"
    }
}
