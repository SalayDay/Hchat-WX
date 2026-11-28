package h.Hchat.hooks.items.script

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.Collections

/**
 * 脚本侧受限的 interceptor chain，只在收到宿主回调的那个线程、那次 `intercept` 内有效。
 *
 * 这里刻意不暴露 LibXposed 的 [XposedInterface.Chain]、[XposedInterface.HookHandle]
 * 和任何旧 Xposed 兼容层类型：脚本只能看到可执行的属性、只读参数和 [proceed]。
 *
 * 生命周期：
 * - 由模块在拦截开始时创建，拦截结束时 [close]，之后任何访问都抛 [IllegalStateException]；
 * - 只能在创建它的线程上使用，跨线程访问同样抛 [IllegalStateException]；
 * - [proceed] 捕获到的宿主 [Throwable] 以对象形式保存在这里，绝不经过 Rust 边界序列化，
 *   脚本没有恢复时由分发器把这个原始对象重新抛回宿主。
 */
class ScriptHookChain internal constructor(
    private val chain: XposedInterface.Chain,
    internal val pluginId: String,
    internal val functionName: String
) {
    private val ownerThreadId = Thread.currentThread().id
    private val argumentSnapshot: List<Any?> =
        Collections.unmodifiableList(ArrayList(chain.args))

    @Volatile
    private var closed = false

    @Volatile
    private var proceeded = 0

    @Volatile
    private var hostFailure: Throwable? = null

    /** 宿主方法 / 构造函数。 */
    val executable: Executable
        get() {
            checkUsable()
            return chain.executable
        }

    /** 当前调用的 `this`，静态成员为 `null`。 */
    val thisObject: Any?
        get() {
            checkUsable()
            return chain.thisObject
        }

    /** 参数只读副本；要改参数请调用 [proceed]。 */
    val args: List<Any?>
        get() {
            checkUsable()
            return argumentSnapshot
        }

    /** 参数个数。 */
    val argCount: Int
        get() {
            checkUsable()
            return argumentSnapshot.size
        }

    /** 第 [index] 个参数；越界抛 [IndexOutOfBoundsException]。 */
    fun arg(index: Int): Any? {
        checkUsable()
        return argumentSnapshot[index]
    }

    /** [arg] 的 Java 风格别名。 */
    fun getArg(index: Int): Any? = arg(index)

    /** 是否已经调用过 [proceed]。 */
    fun hasProceeded(): Boolean {
        checkUsable()
        return proceeded > 0
    }

    /** 调用过 [proceed] 的次数。 */
    fun proceedCount(): Int {
        checkUsable()
        return proceeded
    }

    /**
     * 继续执行链上的下一个拦截器或宿主原方法。
     *
     * 宿主抛出的 [Throwable] 会原样抛给脚本，同时保存为原始对象；脚本没有恢复它时，
     * 分发器会把这个原始对象重新抛回宿主。
     */
    @Throws(Throwable::class)
    fun proceed(): Any? = proceedThrough(null)

    /**
     * 用 [newArgs] 替换参数后继续执行链上的下一个拦截器或宿主原方法。
     *
     * 参数个数必须与目标成员一致，否则立即抛 [IllegalArgumentException]。
     */
    @Throws(Throwable::class)
    fun proceed(newArgs: Array<Any?>?): Any? {
        if (newArgs == null) return proceedThrough(null)
        val expected = argumentSnapshot.size
        if (newArgs.size != expected) {
            throw IllegalArgumentException(
                "proceed 参数个数不匹配: ${chain.executable}, 期望 $expected, 实际 ${newArgs.size}"
            )
        }
        return proceedThrough(newArgs.copyOf())
    }

    /** 脚本没有恢复的宿主异常；没有宿主异常时为 `null`。 */
    internal fun hostThrowable(): Throwable? = hostFailure

    internal fun close() {
        closed = true
    }

    private fun proceedThrough(replacement: Array<Any?>?): Any? {
        checkUsable()
        proceeded++
        try {
            return if (replacement == null) chain.proceed() else chain.proceed(replacement)
        } catch (error: Throwable) {
            // 保留宿主抛出的同一个对象，供分发器原样回抛。
            hostFailure = error
            throw error
        }
    }

    private fun checkUsable() {
        if (closed) {
            throw IllegalStateException(
                "Hook 拦截上下文已结束：ScriptHookChain 不能保存到 intercept 之外再使用"
            )
        }
        val current = Thread.currentThread().id
        if (current != ownerThreadId) {
            throw IllegalStateException(
                "Hook 拦截上下文只能在同一线程使用：所属线程 $ownerThreadId，当前线程 $current"
            )
        }
    }

    override fun toString(): String {
        return "ScriptHookChain(executable=${chain.executable}, plugin=$pluginId, function=$functionName)"
    }
}
