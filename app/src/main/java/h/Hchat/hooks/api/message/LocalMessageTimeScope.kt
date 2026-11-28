package h.Hchat.hooks.api.message

/** 仅在当前同步插入期间覆盖指定会话的时间，嵌套调用结束后恢复外层状态。 */
internal class LocalMessageTimeScope {
    private data class Target(val talker: String, val createTime: Long)
    private val current = ThreadLocal<Target?>()

    fun createTimeFor(talker: String): Long? =
        current.get()?.takeIf { it.talker == talker }?.createTime

    fun <T> during(talker: String, createTime: Long?, action: () -> T): T {
        val previous = current.get()
        if (createTime == null) current.remove() else current.set(Target(talker, createTime))
        try {
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
