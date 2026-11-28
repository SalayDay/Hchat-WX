package h.Hchat.hooks.items.menustyle

/** 菜单显示只在真实聊天长按入口的同步调用内识别，退出后不保留时间窗口。 */
internal class ChatMenuDisplayScope<T : Any> {
    private val active = ThreadLocal<T?>()

    fun current(): T? = active.get()

    fun <R> during(anchor: T, action: () -> R): R {
        val previous = active.get()
        active.set(anchor)
        try {
            return action()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
