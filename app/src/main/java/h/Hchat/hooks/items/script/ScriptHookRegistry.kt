package h.Hchat.hooks.items.script

import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 一次脚本 Hook 拦截的结果。 */
internal sealed interface ScriptHookOutcome {
    /** 解释器当前没有执行权或插件已不可用：直接放行宿主方法，不阻塞、不排队。 */
    object Proceed : ScriptHookOutcome

    /** 脚本返回值，直接作为宿主方法的结果。 */
    class Return(val value: Any?) : ScriptHookOutcome

    /** 脚本执行失败。宿主原始异常已由 [ScriptHookChain] 保存时优先回抛它。 */
    class Failure(val error: Throwable) : ScriptHookOutcome
}

/**
 * 脚本插件 Hook 的安装、按插件记账和拦截分发。
 *
 * 对外只有一个不透明 token：脚本拿不到 [io.github.libxposed.api.XposedInterface.HookHandle]，
 * 也拿不到任何旧 Xposed 兼容层类型。原生句柄按 pluginId 记账，插件卸载、禁用、重载或加载失败时
 * 通过 [unhookPlugin] 统一回收，避免句柄泄漏。
 */
internal object ScriptHookRegistry {
    private const val TAG = "[Hchat:Script]"
    private const val LABEL = "脚本 Hook"
    private val FUNCTION_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val nextToken = AtomicLong(1L)
    private val installed = ConcurrentHashMap<Long, InstalledHook>()
    private val pluginTokens = ConcurrentHashMap<String, MutableSet<Long>>()

    private class InstalledHook(
        val token: Long,
        val pluginId: String,
        val functionName: String,
        val executable: Executable,
        val handle: io.github.libxposed.api.XposedInterface.HookHandle
    )

    /** 安装一个 Hook，返回给脚本的不透明句柄。 */
    fun install(pluginId: String?, member: Member?, functionName: String?, priority: Int): Long {
        val owner = pluginId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("脚本 Hook 缺少插件 ID")
        val executable = member as? Executable
            ?: throw IllegalArgumentException("只能 Hook Method / Constructor：$member")
        val function = functionName?.trim().orEmpty()
        if (!function.matches(FUNCTION_NAME)) {
            throw IllegalArgumentException("脚本函数名不合法：$functionName")
        }
        val handle = HookRegistry.get().hook(executable, ScriptHooker(owner, function), priority)
        val token = nextToken.getAndIncrement()
        installed[token] = InstalledHook(token, owner, function, executable, handle)
        pluginTokens.compute(owner) { _, current ->
            (current ?: Collections.synchronizedSet(LinkedHashSet())).apply { add(token) }
        }
        HLog.boot(
            "$TAG $LABEL 已安装: plugin=$owner #$function -> ${executable.toGenericString()} priority=$priority"
        )
        return token
    }

    /** 取消一个 Hook；token 只在本插件范围内有效。 */
    fun unhook(pluginId: String?, token: Any?): Boolean {
        val id = when (token) {
            is Number -> token.toLong()
            is String -> token.trim().toLongOrNull()
            else -> null
        } ?: return false
        val entry = installed[id] ?: return false
        val owner = pluginId?.takeIf { it.isNotBlank() }
        if (owner != null && owner != entry.pluginId) {
            HLog.e("$TAG $LABEL 忽略跨插件卸载: plugin=$owner token=$id 属于 ${entry.pluginId}")
            return false
        }
        return remove(entry)
    }

    /** 回收某个插件的全部 Hook。插件卸载、禁用、重载和加载失败都必须调用。 */
    fun unhookPlugin(pluginId: String?) {
        val owner = pluginId?.takeIf { it.isNotBlank() } ?: return
        val tokens = pluginTokens.remove(owner) ?: return
        val snapshot = synchronized(tokens) { tokens.toList() }
        var removed = 0
        for (token in snapshot) {
            val entry = installed[token] ?: continue
            if (remove(entry)) removed++
        }
        if (removed > 0) HLog.boot("$TAG $LABEL 已随插件回收 $removed 个句柄: plugin=$owner")
    }

    /** 回收全部脚本 Hook。 */
    fun unhookAll() {
        for (token in installed.keys.toList()) {
            installed[token]?.let { remove(it) }
        }
    }

    fun size(): Int = installed.size

    private fun remove(entry: InstalledHook): Boolean {
        if (installed.remove(entry.token) == null) return false
        pluginTokens[entry.pluginId]?.let { tokens ->
            val empty = synchronized(tokens) {
                tokens.remove(entry.token)
                tokens.isEmpty()
            }
            if (empty) pluginTokens.remove(entry.pluginId, tokens)
        }
        return runCatching {
            HookRegistry.get().unhook(entry.handle)
            true
        }.getOrElse {
            HLog.e("$TAG $LABEL 卸载失败: ${entry.executable.toGenericString()}", it)
            false
        }
    }

    /**
     * 原生 [Hooker]。这里只把调用转给脚本，并负责三件事：
     * 同线程同步执行、busy 时直接放行宿主、以及在脚本没有恢复宿主异常时原样回抛原始对象。
     */
    private class ScriptHooker(
        private val pluginId: String,
        private val functionName: String
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!ScriptPluginRuntime.isHookDispatchAvailable(pluginId)) return chain.proceed()
            val facade = ScriptHookChain(chain, pluginId, functionName)
            try {
                return when (val outcome =
                    ScriptPluginRuntime.invokeScriptHook(pluginId, functionName, facade)) {
                    ScriptHookOutcome.Proceed -> chain.proceed()
                    is ScriptHookOutcome.Return -> outcome.value
                    is ScriptHookOutcome.Failure -> throw restoredThrowable(facade, outcome.error)
                }
            } finally {
                facade.close()
            }
        }

        private fun restoredThrowable(facade: ScriptHookChain, error: Throwable): Throwable {
            val host = facade.hostThrowable() ?: return error
            // 宿主异常必须原样回抛，绝不在 Rust 边界上重建。
            if (!isHostRethrow(host, error)) {
                HLog.e(
                    "$TAG $LABEL 脚本在宿主异常之后又抛出了 ${error.javaClass.name}，" +
                        "改为原样回抛宿主异常: plugin=$pluginId #$functionName",
                    error
                )
            }
            return host
        }

        /**
         * 解释器把宿主异常转述成 `TargetError: <类名>` 后重新抛给 Java，
         * 所以脚本原样回抛同一个对象时只能靠类名识别，避免每次都误报成脚本自身错误。
         */
        private fun isHostRethrow(host: Throwable, error: Throwable): Boolean {
            if (error === host || error.cause === host) return true
            val message = error.message.orEmpty()
            if (message.isEmpty()) return false
            return message.contains(host.javaClass.name)
        }
    }
}
