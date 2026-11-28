package h.Hchat.hooks.items.floatingshortcut

import android.app.Activity
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object FloatingShortcutIconPicker {
    private data class Pending(
        val activity: WeakReference<Activity>,
        val key: String,
        val callback: (FloatingShortcutIconPickResult) -> Unit
    )

    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val hookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    fun launch(
        activity: Activity,
        key: String,
        callback: (FloatingShortcutIconPickResult) -> Unit
    ) {
        hookActivityHierarchy(activity.javaClass)
        val requestCode = allocateRequestCode()
        pending[requestCode] = Pending(WeakReference(activity), key, callback)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    activity.startActivityForResult(
                        Intent.createChooser(fallback, "选择悬浮菜单图标"),
                        requestCode
                    )
                }.onFailure {
                    pending.remove(requestCode)?.callback?.invoke(FloatingShortcutIconPickResult.FAILED)
                }
            }
    }

    private fun hookActivityHierarchy(activityClass: Class<*>) {
        var current: Class<*>? = activityClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            hookActivityResult(current)
            hookActivityDestroy(current)
            current = current.superclass
        }
    }

    private fun allocateRequestCode(): Int {
        repeat(REQUEST_CODE_END - REQUEST_CODE_START + 1) {
            val candidate = nextRequestCode.updateAndGet { current ->
                if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
            }
            if (!pending.containsKey(candidate)) return candidate
        }
        val oldest = pending.keys.minOrNull() ?: REQUEST_CODE_START
        pending.remove(oldest)?.callback?.invoke(FloatingShortcutIconPickResult.CANCELLED)
        return oldest
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (!hookedClasses.add(clazz)) return
        runCatching {
            val handles = HookRegistry.get().hookAllMethods(clazz, "onActivityResult", IconResultHook())
            val expected = KavaReflector.declaredMethods(clazz).count { it.name == "onActivityResult" }
            if (handles.size != expected) {
                handles.forEach { handle ->
                    runCatching { HookRegistry.get().unhook(handle) }
                        .onFailure { error ->
                            HookRegistry.get().add(handle)
                            HLog.e("[Hchat] 回滚结果 Hook 失败: ${clazz.name}#onActivityResult", error)
                        }
                }
                error("结果 Hook 安装不完整: ${clazz.name}#onActivityResult ${handles.size}/$expected")
            }
        }.onFailure { hookedClasses.remove(clazz) }
    }

    @Synchronized
    private fun hookActivityDestroy(clazz: Class<*>) {
        if (!destroyHookedClasses.add(clazz)) return
        runCatching {
            val handles = HookRegistry.get().hookAllMethods(clazz, "onDestroy", ActivityDestroyHook())
            val expected = KavaReflector.declaredMethods(clazz).count { it.name == "onDestroy" }
            if (handles.size != expected) {
                handles.forEach { handle ->
                    runCatching { HookRegistry.get().unhook(handle) }
                        .onFailure { error ->
                            HookRegistry.get().add(handle)
                            HLog.e("[Hchat] 回滚结果 Hook 失败: ${clazz.name}#onDestroy", error)
                        }
                }
                error("结果 Hook 安装不完整: ${clazz.name}#onDestroy ${handles.size}/$expected")
            }
        }.onFailure {
            destroyHookedClasses.remove(clazz)
        }
    }

    private val destroyHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    private const val REQUEST_CODE_START = 0x7410
    private const val REQUEST_CODE_END = 0x74ff

    private class IconResultHook : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val outcome = chain.proceed()
            val args = chain.args
            val requestCode = args.getOrNull(0) as? Int ?: return outcome
            val result = pending[requestCode] ?: return outcome
            val activity = result.activity.get()
            if (activity == null) {
                pending.remove(requestCode, result)
                return outcome
            }
            if (chain.thisObject !== activity || !pending.remove(requestCode, result)) return outcome
            val resultCode = args.getOrNull(1) as? Int ?: return outcome
            val data = args.getOrNull(2) as? Intent
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) {
                result.callback(FloatingShortcutIconPickResult.CANCELLED)
                return outcome
            }
            Thread({
                val path = runCatching {
                    FloatingShortcutIconStore.saveFromUri(activity, result.key, uri)
                }.getOrNull()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        FloatingShortcutIconStore.delete(activity, path)
                        return@runOnUiThread
                    }
                    result.callback(
                        if (path != null) {
                            FloatingShortcutIconPickResult.Saved(path)
                        } else {
                            FloatingShortcutIconPickResult.FAILED
                        }
                    )
                }
            }, "Hchat-FloatingShortcutIcon").start()
            return outcome
        }
    }

    private class ActivityDestroyHook : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val outcome = chain.proceed()

            val activity = chain.thisObject as? Activity ?: return outcome
            pending.entries.forEach { entry ->
                val owner = entry.value.activity.get()
                if (owner == null || owner === activity) {
                    pending.remove(entry.key, entry.value)
                }
            }
            return outcome
        }
    }
}

sealed class FloatingShortcutIconPickResult {
    data class Saved(val path: String) : FloatingShortcutIconPickResult()
    object CANCELLED : FloatingShortcutIconPickResult()
    object FAILED : FloatingShortcutIconPickResult()
}
