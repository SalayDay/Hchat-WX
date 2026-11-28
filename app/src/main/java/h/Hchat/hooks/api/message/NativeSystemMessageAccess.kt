package h.Hchat.hooks.api.message

import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/** 读取已核对的消息持久化字段；不用不存在的 getTalker/getContent 或泛 getType 猜对象。 */
internal object NativeSystemMessageAccess {
    data class Snapshot(
        val talker: String,
        val content: String,
        val createTime: Long,
        val localId: Long,
        val serverId: Long,
        val type: Int
    )

    private class Fields(
        val talker: Field, val content: Field, val createTime: Field,
        val localId: Field, val serverId: Field, val type: Field
    )
    private val cache = ConcurrentHashMap<Class<*>, Any>()
    private val missing = Any()

    fun isMessageClass(clazz: Class<*>): Boolean = fields(clazz) != null

    fun snapshot(message: Any): Snapshot? {
        val fields = fields(message.javaClass) ?: return null
        return Snapshot(
            KavaReflector.readField(fields.talker, message) as? String ?: return null,
            KavaReflector.readField(fields.content, message) as? String ?: return null,
            (KavaReflector.readField(fields.createTime, message) as? Number)?.toLong() ?: return null,
            (KavaReflector.readField(fields.localId, message) as? Number)?.toLong() ?: return null,
            (KavaReflector.readField(fields.serverId, message) as? Number)?.toLong() ?: return null,
            (KavaReflector.readField(fields.type, message) as? Number)?.toInt() ?: return null
        )
    }

    private fun fields(clazz: Class<*>): Fields? {
        cache[clazz]?.let { return it as? Fields }
        fun field(name: String, type: Class<*>): Field? =
            KavaReflector.findFieldRecursive(clazz, name)?.takeIf {
                !KavaReflector.isStatic(it) && it.type == type
            }
        val resolved = run {
            Fields(
                field("field_talker", String::class.java) ?: return@run null,
                field("field_content", String::class.java) ?: return@run null,
                field("field_createTime", java.lang.Long.TYPE) ?: return@run null,
                field("field_msgId", java.lang.Long.TYPE) ?: return@run null,
                field("field_msgSvrId", java.lang.Long.TYPE) ?: return@run null,
                field("field_type", Integer.TYPE) ?: return@run null
            )
        }
        return (cache.putIfAbsent(clazz, resolved ?: missing) ?: resolved) as? Fields
    }
}
