package h.Hchat.hooks.items.purify

import java.util.WeakHashMap

/** 只恢复本功能曾改动的可见性；原生自己隐藏的 View 不被强制显示。主线程使用。 */
internal class OwnedVisibilityState<T : Any>(
    private val read: (T) -> Int,
    private val write: (T, Int) -> Unit,
    private val hidden: Int = 8
) {
    private val originals = WeakHashMap<T, Int>()

    fun hide(item: T) {
        if (item in originals) return
        val original = read(item)
        if (original == hidden) return
        originals[item] = original
        write(item, hidden)
    }

    fun restore(item: T) {
        val original = originals.remove(item) ?: return
        write(item, original)
    }

    fun restoreAll() {
        val snapshot = originals.entries.map { it.key to it.value }
        originals.clear()
        for ((item, visibility) in snapshot) write(item, visibility)
    }
}
