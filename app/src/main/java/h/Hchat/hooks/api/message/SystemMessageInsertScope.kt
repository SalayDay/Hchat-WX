package h.Hchat.hooks.api.message

/** 捕获已核实提示入口当前线程的实际插入；作用域外的宿主插入完全不受影响。 */
internal class SystemMessageInsertScope {
    class Request(
        val talker: String,
        val expectedTime: Long?,
        val protected: Boolean,
        val account: String,
        val expectedContent: String? = null,
        val cancelOnProtectionFailure: Boolean = true
    ) {
        var matched: Boolean = false
        var result: Long? = null
        var error: String? = null
        var warning: String? = null

        fun claim(
            actualTalker: String, type: Int, createTime: Long, serverId: Long, content: String? = null
        ): Boolean {
            if (matched || actualTalker != talker || type != 10000 || serverId != 0L ||
                (expectedTime != null && createTime != expectedTime) ||
                (expectedContent != null && content != expectedContent)) return false
            matched = true
            return true
        }
    }

    private val active = ThreadLocal<Request?>()
    fun current(): Request? = active.get()

    fun <T> during(request: Request, action: () -> T): T {
        val previous = active.get()
        active.set(request)
        try {
            return action()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
