package h.Hchat.hooks.items.groupadminlabel

import android.widget.TextView
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import h.Hchat.utils.WeChatIdRules
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天消息「发送者昵称」的绑定点。
 *
 * 微信在群聊里给每条消息加昵称，走的是 chatting viewitems 基类的同一个填充方法：
 * 参数形如 `(holder, 聊天上下文, 消息, username)`，方法体里带
 * `"fillingUsername:need getKfInfo"` 日志。这个方法名和类名逐版本变化
 * （8.0.76 是 `viewitems/a0;->o`，8.0.80 是 `viewitems/b0;->p`），**日志串不变**，
 * 所以按字符串定位，并缓存到 `DexMethodCache`。
 *
 * 群聊昵称的实际渲染在 holder 的 `userTV` 上，昵称文本由微信自己算好；
 * 因此这里只负责拿到 TextView 和发送者 wxid，装饰交给
 * `ChatNameDecorationLayout` 统一合成。
 */
internal object ChatUsernameBind {
    private const val CACHE_PREFS = "Hchat_chat_username_bind_cache"
    private const val CACHE_KEY = "username_bind_v1"
    private const val ANCHOR = "fillingUsername:need getKfInfo"
    private const val ANCHOR_CLASS = "MicroMsg.ChattingItem"

    private val textFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val likelyMsgCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val senderCache = Collections.synchronizedMap(WeakHashMap<Any, String>())

    data class BindArgs(
        val message: Any?,
        val raw: String,
        val candidates: List<String>
    )

    fun locate(context: FeatureContext, logger: (String, Throwable?) -> Unit): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, key, context.hostClassLoader(), CACHE_KEY)
            ?.takeIf(::isCandidate)
            ?.let { return it }
        val method = findMethods(context, logger, ANCHOR_CLASS, ANCHOR)
            .ifEmpty { findMethods(context, logger, ANCHOR) }
            .firstOrNull(::isCandidate)
        if (method == null) {
            DexMethodCache.clear(prefs, key, CACHE_KEY)
        } else {
            DexMethodCache.save(prefs, key, CACHE_KEY, method)
        }
        return method
    }

    private fun findMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit,
        vararg strings: String
    ): List<Method> = runCatching {
        context.dexKitBridge().findMethod(
            org.luckypray.dexkit.query.FindMethod().apply {
                matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                    usingStrings(strings.toList())
                })
            }
        ).mapNotNull { data ->
            runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
        }
    }.getOrElse {
        logger("群聊标签定位昵称绑定方法失败", it)
        emptyList()
    }

    fun isCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size >= 3 &&
            types.any { it == String::class.java } &&
            types.any(::isLikelyMessageClass) &&
            findUserTextField(types[0]) != null
    }

    fun findUserTextView(holder: Any): TextView? {
        textFieldCache[holder.javaClass]?.let {
            return KavaReflector.readField(it, holder) as? TextView
        }
        val field = findUserTextField(holder.javaClass) ?: return null
        textFieldCache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? TextView
    }

    private fun findUserTextField(clazz: Class<*>?): Field? {
        if (clazz == null) return null
        textFieldCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.type == TextView::class.java &&
                    (it.name == "userTV" || it.name == "brc" || it.name.lowercase().contains("user"))
            }
            if (field != null) {
                textFieldCache[clazz] = field
                return field
            }
            current = current.superclass
        }
        return null
    }

    fun isLikelyMessageClass(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        likelyMsgCache[clazz]?.let { return it }
        val result = clazz.name.startsWith("com.tencent.mm.storage.") ||
            KavaReflector.declaredMethods(clazz).any {
                it.returnType == String::class.java && it.parameterTypes.isEmpty() &&
                    arrayOf("getSender", "getSendTalker", "getTalker").contains(it.name)
            }
        likelyMsgCache[clazz] = result
        return result
    }

    fun collect(args: Array<Any?>): BindArgs {
        var message: Any? = null
        var raw = ""
        val candidates = mutableListOf<String>()
        args.forEach { arg ->
            if (arg is String) {
                if (raw.isEmpty()) raw = arg
                val value = arg.trim()
                if (isValidWxid(value)) candidates += value
            }
            if (arg != null && isLikelyMessageClass(arg.javaClass)) message = arg
        }
        return BindArgs(message, raw, candidates)
    }

    fun resolveSender(bindArgs: BindArgs): String {
        bindArgs.candidates.firstOrNull { !isSelf(it) }?.let { return it }
        contentSender(bindArgs.message)?.takeIf { !isSelf(it) }?.let { return it }
        return extractSender(bindArgs.message, bindArgs.raw)
    }

    private fun extractSender(msg: Any?, fallback: String): String {
        if (msg == null) return fallback.takeIf(::isValidWxid).orEmpty()
        senderCache[msg]?.let { return it }
        val methodNames = arrayOf(
            "getSender", "getSendTalker", "getTalker", "R1", "P1", "o0", "x0", "j0"
        )
        for (name in methodNames) {
            val value = KavaReflector.invoke(
                KavaReflector.findMethod(msg.javaClass, name), msg
            ) as? String
            if (isValidWxid(value)) {
                val sender = value!!.trim()
                senderCache[msg] = sender
                return sender
            }
        }
        val fieldNames = arrayOf("field_talker", "talker", "field_sender", "sender", "sendTalker")
        for (name in fieldNames) {
            val value = KavaReflector.readField(msg, name) as? String
            if (isValidWxid(value)) {
                val sender = value!!.trim()
                senderCache[msg] = sender
                return sender
            }
        }
        contentSender(msg)?.let {
            senderCache[msg] = it
            return it
        }
        return fallback.takeIf(::isValidWxid).orEmpty()
    }

    private fun contentSender(msg: Any?): String? {
        if (msg == null) return null
        val contentFields = arrayOf("field_content", "content", "msgContent")
        for (name in contentFields) {
            val content = KavaReflector.readField(msg, name) as? String ?: continue
            val sender = groupSenderPrefix(content)
            if (isValidWxid(sender)) return sender
        }
        return null
    }

    /** 群消息 content 头部是 `发送者wxid:\n`；单聊没有这段前缀。 */
    private fun groupSenderPrefix(content: String): String {
        val index = when {
            content.indexOf(":\n") > 0 -> content.indexOf(":\n")
            content.indexOf(":\r\n") > 0 -> content.indexOf(":\r\n")
            else -> -1
        }
        if (index <= 0 || index > 80) return ""
        return content.substring(0, index).takeIf(::isValidWxid).orEmpty()
    }

    fun isOutgoingMessage(msg: Any?): Boolean {
        if (msg == null) return false
        for (name in arrayOf("field_isSend", "isSend")) {
            val value = KavaReflector.readField(msg, name)
            if (value is Number) return value.toInt() == 1
            if (value is Boolean) return value
        }
        return false
    }

    fun currentRoomId(): String = WeChatApis.chatPage()?.currentTalker().orEmpty()

    fun isRoom(value: String?): Boolean {
        val id = value.orEmpty()
        return id.endsWith("@chatroom") || id.endsWith("@im.chatroom")
    }

    fun isSelf(wxid: String): Boolean {
        val self = WeChatApis.account()?.selfWxId().orEmpty()
        return self.isNotEmpty() && self == wxid
    }

    fun isValidWxid(value: String?): Boolean = WeChatIdRules.isLikelyContactId(value)
}