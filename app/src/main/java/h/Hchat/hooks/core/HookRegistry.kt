package h.Hchat.hooks.core

import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import h.Hchat.utils.SelfFrameRegistry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.util.concurrent.CancellationException

/** 原生 HookHandle 的生命周期登记表，不转换拦截器或调用上下文。 */
class HookRegistry private constructor() {
    private val lock = Any()
    private val handles = LinkedHashSet<HookHandle>()
    private val installationLease = ThreadLocal<Long>()
    @Volatile private var generation = 0L

    fun installationGeneration(): Long = installationLease.get() ?: generation

    fun isInstallationGenerationActive(generation: Long): Boolean = this.generation == generation

    fun <T> withInstallationGeneration(generation: Long, action: () -> T): T {
        val previous = installationLease.get()
        if (!isInstallationGenerationActive(generation) || previous != null && previous != generation) {
            throw CancellationException("安装代次已失效")
        }
        installationLease.set(generation)
        try {
            return action()
        } finally {
            if (previous == null) installationLease.remove() else installationLease.set(previous)
        }
    }

    @JvmOverloads
    fun hook(
        method: Member,
        hooker: Hooker,
        priority: Int = XposedInterface.PRIORITY_DEFAULT
    ): HookHandle = synchronized(lock) {
        if (!isInstallationGenerationActive(installationGeneration())) {
            throw CancellationException("安装代次已失效")
        }
        val executable = method as? Executable
            ?: throw IllegalArgumentException("只能 Hook Method / Constructor：$method")
        val handle = ModuleRuntime.api().hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(hooker)
        handles.add(handle)
        // 保存经过 R8 后的真实类名，维持模块自身栈帧的归属判断。
        SelfFrameRegistry.register(hooker.javaClass)
        handle
    }

    @JvmOverloads
    fun hookAllMethods(
        clazz: Class<*>?,
        methodName: String?,
        hooker: Hooker,
        priority: Int = XposedInterface.PRIORITY_DEFAULT
    ): Set<HookHandle> {
        if (clazz == null || methodName.isNullOrEmpty()) return emptySet()
        val installed = LinkedHashSet<HookHandle>()
        for (method in KavaReflector.declaredMethods(clazz)) {
            if (method.name != methodName) continue
            try {
                installed.add(hook(method, hooker, priority))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                HLog.e("[Hchat:Hook] 安装失败: ${method.toGenericString()}", failure)
            }
        }
        return installed
    }

    @JvmOverloads
    fun hookAllConstructors(
        clazz: Class<*>?,
        hooker: Hooker,
        priority: Int = XposedInterface.PRIORITY_DEFAULT
    ): Set<HookHandle> {
        if (clazz == null) return emptySet()
        val installed = LinkedHashSet<HookHandle>()
        for (constructor in KavaReflector.declaredConstructors(clazz)) {
            try {
                installed.add(hook(constructor, hooker, priority))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                HLog.e("[Hchat:Hook] 安装失败: ${constructor.toGenericString()}", failure)
            }
        }
        return installed
    }

    /** 对已登记的句柄幂等；过期任务新产生的句柄立即卸载。 */
    fun add(handle: HookHandle?) {
        if (handle == null) return
        synchronized(lock) {
            if (isInstallationGenerationActive(installationGeneration())) {
                handles.add(handle)
                return
            }
        }
        try {
            handle.unhook()
        } catch (failure: Throwable) {
            HLog.e("[Hchat:Hook] 卸载过期安装失败", failure)
        }
        throw CancellationException("安装代次已失效")
    }

    fun size(): Int = synchronized(lock) { handles.size }

    fun unhook(handle: HookHandle?) {
        if (handle == null) return
        try {
            handle.unhook()
        } finally {
            synchronized(lock) { handles.remove(handle) }
        }
    }

    fun unhookAll() {
        val previous = synchronized(lock) {
            generation++
            handles.toList().also { handles.clear() }
        }
        for (handle in previous) {
            try {
                handle.unhook()
            } catch (failure: Throwable) {
                HLog.e("[Hchat:Hook] 卸载失败", failure)
            }
        }
    }

    companion object {
        private val INSTANCE = HookRegistry()

        @JvmStatic
        fun get(): HookRegistry = INSTANCE
    }
}
