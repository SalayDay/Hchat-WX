package h.Hchat.hooks.items.addemoji

import android.app.Activity
import android.view.MenuItem
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.ChatPresenterMenuLocator
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.api.sns.SnsContentTypes
import h.Hchat.hooks.api.sns.SnsContextMenuDispatcher
import h.Hchat.hooks.api.sns.SnsContextMenuTarget
import h.Hchat.hooks.api.sns.SnsForwardContentResolver
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片「保存为表情」。
 *
 * 长按聊天里的普通图片、或朋友圈的图片，菜单里多一项「保存为表情[H]」，点了就把这张图
 * 写进微信「自定义表情」库。落库方式见 [EmojiCollectionWriter]：完全照微信自己
 * `EmojiAddCustomDialogUI.save` 的序列走（catalog=0x51 + 排序位 + `EmojiInfoStorage` 落库）。
 *
 * 两处入口：
 * - 聊天：`ChatPresenterMenuLocator`（presenter 的 `onCreateContextMenu`，args[0] 才是菜单本体）
 * - 朋友圈：`SnsContextMenuDispatcher`（和「转发[H]」同一套菜单注册表）
 */
class AddEmojiFeature : BaseFeature() {
    private var runtime: AddEmojiRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "图片保存为表情"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AddEmojiSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = AddEmojiRuntime(context, ::logFeatureError, ::logFeatureBoot)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
            runtime?.retrySnsMenu()
        }
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

    /** 安装结果只输出一次，必须走 boot 缓冲，否则采集开启前会被丢掉。 */
    private fun logFeatureBoot(message: String) {
        logBoot(message)
    }

    companion object {
        const val ID = "add_emoji_from_image"
        internal const val MENU_ITEM_ID = 0x4841454d
        internal const val MENU_TITLE = "保存为表情[H]"
    }
}

private class AddEmojiRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit,
    private val bootLog: (String) -> Unit
) {
    private val writer = EmojiCollectionWriter(context, logger)
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, Any>())
    /** 每个 tag 类的「消息 getter」缓存，null 表示这个类没有。 */
    private val messageGetterCache = ConcurrentHashMap<String, Method?>()
    /** 诊断三类结果各记一次，便于从日志定位卡点。 */
    private val diagnostics = Collections.synchronizedSet(HashSet<String>())
    private val enabled = HotConfig.flag(
        AddEmojiFeature.ID,
        AddEmojiSettings.KEY_ENABLED,
        AddEmojiSettings.DEFAULT_ENABLED
    ) {
        context.configStore().getBoolean(
            AddEmojiFeature.ID,
            AddEmojiSettings.KEY_ENABLED,
            AddEmojiSettings.DEFAULT_ENABLED
        )
    }
    private val snsResolver by lazy { SnsForwardContentResolver(context, logger) }
    private var snsRegistered = false
    @Volatile private var snsHooked = false

    @Synchronized
    fun install(): Boolean {
        // 返回 false 会让 DexInstallScheduler 在 DexKit 还没就绪时重试（最多 3 次）；
        // 已经 hook 过的方法由 hookedMethods 去重，重试不会重复挂。
        val chatHooked = installChatMenu()
        val snsInstalled = installSnsMenu()
        bootLog(
            "图片保存为表情挂载：聊天菜单门 ${if (chatHooked) "已挂载" else "未命中"}" +
                "，朋友圈菜单门 ${if (snsInstalled) "已挂载" else "未命中"}" +
                "，开关 ${if (enabled()) "开" else "关"}"
        )
        return chatHooked || snsInstalled
    }

    /** DexReady 之后补装朋友圈菜单：调度器标记成功后不会再调 install()。 */
    @Synchronized
    fun retrySnsMenu() {
        if (snsHooked) return
        snsRegistered = false
        installSnsMenu()
    }

    fun destroy() {
        bindings.clear()
        if (snsRegistered) {
            SnsContextMenuDispatcher.unregister(OWNER_SNS)
            snsRegistered = false
        }
        snsHooked = false
    }

    // ------------------------------------------------------------------ 聊天

    private fun installChatMenu(): Boolean {
        // 8.0.80 上必须用 presenter 的 onCreateContextMenu(ContextMenu, View, Info)：
        // 它的 args[0] 才是菜单本体。老入口 viewitems#a 的 args[0] 是聊天上下文，
        // 往它上面 add 菜单项等于什么都没做 —— 这就是菜单项从不出现的原因。
        val createHooked = ChatPresenterMenuLocator.createMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addChatMenu(param)
                }
            })
        }
        val clickHooked = ChatPresenterMenuLocator.clickMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleChatClick(param)
                }
            })
        }
        if (createHooked <= 0) logger("图片保存为表情菜单创建 Hook 未安装", null)
        if (clickHooked <= 0) logger("图片保存为表情菜单点击 Hook 未安装", null)
        return createHooked > 0 && clickHooked > 0
    }

    private fun addChatMenu(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val args = param.args
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View ?: return
        val message = resolveNativeMessage(view.tag) ?: resolveNativeMessage(args)
        if (message == null) {
            diag("resolved", "菜单已创建但取不到消息对象（tag=${view.tag?.javaClass?.name}）")
            return
        }
        if (!isImageMessage(message)) return
        val item = addMenuItem(menu, view)
        if (item == null) {
            diag("added", "拿到图片消息但注入失败（菜单=${menu.javaClass.name}）")
            return
        }
        bindings[item] = message
        diag("added", "已注入「保存为表情」菜单项，菜单=${menu.javaClass.name}")
    }

    /** 每种结果只记录一次，避免刷屏；下一次日志就能看出卡在哪一步。 */
    private fun diag(key: String, message: String) {
        if (diagnostics.add(key)) bootLog(message)
    }

    private fun handleChatClick(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val item = param.args.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != AddEmojiFeature.MENU_ITEM_ID) return
        val message = bindings.remove(item) ?: resolveNativeMessage(param.args) ?: return
        // 解析原图路径可能要把 VFS 内容落到缓存文件，属于 IO，不能留在主线程。
        writer.addImageLazy(WeChatApis.currentActivity()?.currentActivity()) {
            resolveChatImagePath(message)
        }
    }

    private fun resolveChatImagePath(message: Any): String {
        val images = WeChatApis.media()?.images() ?: return ""
        val direct = runCatching { images.resolveBestAvailablePath(message) }.getOrNull().orEmpty()
        if (direct.isNotBlank()) return direct
        // 退一步：消息里带的 imgPath 可能只是一个路径标识，按标识再解析一次。
        val token = readString(message, "getImgPath", "field_imgPath", "imgPath")
        if (token.isBlank()) return ""
        return runCatching { images.resolvePathToken(token) }.getOrNull().orEmpty()
    }

    /**
     * 只对**普通图片消息**（type 3）放行。
     *
     * 本功能是「把图片存成表情」，所以：
     * - 表情消息（type 47）不放行 —— 它本来就在表情库里，不需要再存一次；
     * - 视频、文件、链接、小程序卡片都不放行。
     * 类型判定走 `WeChatMessageTypes.isImage`，它会先归一化高位标志位。
     */
    private fun isImageMessage(message: Any): Boolean {
        val type = readNumber(message, "getType", "field_type", "type")?.toInt() ?: return false
        return WeChatMessageTypes.isImage(type)
    }

    // ---------------------------------------------------------------- 朋友圈

    private fun installSnsMenu(): Boolean {
        if (snsRegistered) return true
        SnsContextMenuDispatcher.register(
            SnsContextMenuDispatcher.Entry(
                owner = OWNER_SNS,
                itemId = AddEmojiFeature.MENU_ITEM_ID,
                title = AddEmojiFeature.MENU_TITLE,
                order = 12,
                iconName = "icons_filled_emoticon",
                isEnabled = { enabled() },
                isApplicable = { target -> target.snapshot.isImage && imageMedia(target) != null },
                onClick = { activity, target -> handleSnsClick(activity, target) }
            )
        )
        snsRegistered = true
        val installed = SnsContextMenuDispatcher.install(context, snsResolver, logger)
        snsHooked = installed
        if (!installed) logger("朋友圈图片保存为表情 Hook 未安装", null)
        return installed
    }

    private fun handleSnsClick(activity: Activity, target: SnsContextMenuTarget) {
        val media = imageMedia(target) ?: return
        writer.addImageLazy(activity) { runCatching { snsResolver.imagePath(media) }.getOrNull() }
    }

    private fun imageMedia(target: SnsContextMenuTarget) =
        target.snapshot.media.firstOrNull { SnsContentTypes.isImageMedia(it.type) }

    // ------------------------------------------------------------------ 公共

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("图片保存为表情 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addMenuItem(menu: Any, view: View): MenuItem? {
        (KavaReflector.invokeMethod(menu, "findItem", AddEmojiFeature.MENU_ITEM_ID) as? MenuItem)
            ?.let { return it }
        val groupId = readMenuGroupId(menu)
        val iconRes = menuIconResId(view)
        if (iconRes != 0) {
            val method = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { candidate ->
                val types = candidate.parameterTypes
                candidate.name == "c" && types.size == 5 &&
                    types[0] == Integer.TYPE && types[1] == Integer.TYPE && types[2] == Integer.TYPE &&
                    types[3].isAssignableFrom(String::class.java) && types[4] == Integer.TYPE
            }
            if (KavaReflector.invokeSuccessfully(
                    method, menu, groupId, AddEmojiFeature.MENU_ITEM_ID, 0,
                    AddEmojiFeature.MENU_TITLE, iconRes
                )
            ) {
                return KavaReflector.invokeMethod(menu, "findItem", AddEmojiFeature.MENU_ITEM_ID)
                    as? MenuItem
            }
        }
        val added = KavaReflector.invokeMethod(
            menu, "add", groupId, AddEmojiFeature.MENU_ITEM_ID, 0, AddEmojiFeature.MENU_TITLE
        ) ?: KavaReflector.invokeMethod(
            menu, "add", groupId, AddEmojiFeature.MENU_ITEM_ID, 0,
            AddEmojiFeature.MENU_TITLE as CharSequence
        )
        if (added is MenuItem) {
            if (iconRes != 0) runCatching { added.setIcon(iconRes) }
            return added
        }
        val fallback = KavaReflector.invokeMethod(
            menu, "f", AddEmojiFeature.MENU_ITEM_ID, AddEmojiFeature.MENU_TITLE
        ) ?: KavaReflector.invokeMethod(
            menu, "f", AddEmojiFeature.MENU_ITEM_ID, AddEmojiFeature.MENU_TITLE as CharSequence
        )
        return (fallback as? MenuItem)
            ?: KavaReflector.invokeMethod(menu, "findItem", AddEmojiFeature.MENU_ITEM_ID) as? MenuItem
    }

    private fun readMenuGroupId(menu: Any): Int {
        val size = (KavaReflector.invokeMethod(menu, "size") as? Number)?.toInt() ?: 0
        for (index in 0 until size) {
            val item = KavaReflector.invokeMethod(menu, "getItem", index) as? MenuItem ?: continue
            return item.groupId
        }
        return 0
    }

    private fun menuIconResId(view: View): Int {
        val resources = view.context.resources
        val packageName = view.context.packageName
        for (name in ICON_NAMES) {
            for (type in arrayOf("raw", "drawable")) {
                val id = resources.getIdentifier(name, type, packageName)
                if (id != 0) return id
            }
        }
        return 0
    }

    private fun readString(source: Any, getter: String, field: String, fallback: String): String {
        return KavaReflector.invokeMethod(source, getter)?.toString()
            ?: KavaReflector.readField(source, field)?.toString()
            ?: KavaReflector.readField(source, fallback)?.toString()
            ?: ""
    }

    private fun readNumber(source: Any, getter: String, field: String, fallback: String): Number? {
        return KavaReflector.invokeMethod(source, getter) as? Number
            ?: KavaReflector.readField(source, field) as? Number
            ?: KavaReflector.readField(source, fallback) as? Number
    }

    /**
     * 从长按上下文里挖出原生消息对象。
     *
     * 长按菜单拿不到消息本身。8.0.80 里 `view.tag` 是 `chatting.viewitems.ws`，
     * 消息由它的 **方法** `c()` 返回（不是字段！）—— 只顺着字段找永远找不到，
     * 这正是菜单项一直不出现的原因。所以这里字段和方法都要试。
     */
    private fun resolveNativeMessage(source: Any?): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return resolveNativeMessage(source, visited, 0)
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > MAX_RESOLVE_DEPTH || !visited.add(source)) return null
        val messageId = readNumber(source, "getMsgId", "field_msgId", "msgId")
            ?: KavaReflector.invokeMethod(source, "getMsgID") as? Number
        if (source.javaClass.name.startsWith(MESSAGE_PACKAGE) &&
            messageId?.toLong()?.let { it > 0L } == true
        ) {
            return source
        }
        if (source is View) return resolveNativeMessage(source.tag, visited, depth + 1)
        if (source is Array<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { found -> return found } }
            return null
        }
        if (source is Collection<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { found -> return found } }
            return null
        }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        // 先试「无参、返回 storage 消息」的 getter：ws#c() 就是这类。
        messageGetter(className)?.let { getter ->
            val value = KavaReflector.invoke(getter, source)
            resolveNativeMessage(value, visited, depth + 1)?.let { return it }
        }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.isPrimitive || field.type == String::class.java) continue
                val value = KavaReflector.readField(field, source) ?: continue
                resolveNativeMessage(value, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    /**
     * 找「无参、返回 `com.tencent.mm.storage.*`」的 getter。
     *
     * 8.0.80 的长按 tag 类是 `chatting.viewitems.ws`，消息由 `c()` 返回。
     * 方法名逐版本变化，所以按返回类型特征找，不写死 `c`。结果按类缓存。
     */
    private fun messageGetter(className: String): Method? {
        if (messageGetterCache.containsKey(className)) return messageGetterCache[className]
        val clazz = runCatching { Class.forName(className) }.getOrNull() ?: run {
            messageGetterCache[className] = null
            return null
        }
        val getter = runCatching {
            KavaReflector.declaredMethods(clazz).firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.returnType.name.startsWith(MESSAGE_PACKAGE)
            }
        }.getOrNull()
        messageGetterCache[className] = getter
        return getter
    }


    private companion object {
        const val OWNER_SNS = "add_emoji_from_image"
        const val MESSAGE_PACKAGE = "com.tencent.mm.storage."
        const val MAX_RESOLVE_DEPTH = 5
        val ICON_NAMES = listOf("icons_filled_emoticon", "icons_filled_emoji", "icons_filled_sticker")
    }
}
