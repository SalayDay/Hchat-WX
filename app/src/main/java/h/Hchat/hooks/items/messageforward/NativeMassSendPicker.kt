package h.Hchat.hooks.items.messageforward

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedList

/**
 * 用微信群发助手自己的好友选择页选人，选完直接把名单交回给模块发送。
 *
 * 为什么要接这一层：模块自带的选择器和微信群发助手的选择器是两套体验。原生那套
 * 有 A-Z 索引、搜索、标签入口、全选，用户更熟悉；但原生选择页点「下一步」会跳进
 * 它自己的群发编辑页，让我们没法把"已选的消息"塞进去。
 *
 * 这里在「下一步」的点击回调上截一刀：
 *
 * - 如果这次选择页是我们拉起来的（有 pending 回调），就把已选名单读出来，
 *   拦掉原生跳转，直接关闭选择页并把名单交回去；
 * - 否则完全放行，用户在微信里自己用群发助手时行为不变。
 *
 * 名单本身取自 `MassSendSelectContactUI` 的已选列表，微信自己也是从这里读的
 * （点击「下一步」时的 `LinkedList.toArray` 就是它）。字段名被混淆，所以按
 * 「类型匹配」优先、字段名兜底的方式读，尽量扛住小版本变化。
 */
internal class NativeMassSendPicker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var pending: ((List<String>) -> Unit)? = null
    @Volatile private var pendingToken = 0
    @Volatile private var installedGeneration = -1L

    fun install(): Boolean {
        val generation = HookRegistry.get().installationGeneration()
        if (HookRegistry.get().isInstallationGenerationActive(installedGeneration)) return true
        val method = locateNextButton() ?: run {
            logger("未定位到微信群发选择页的「下一步」回调", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, NextClickHook(this))
            // 用户从选择页直接返回时，pending 必须立刻清掉；只靠 8 分钟超时会让
            // 他在超时窗口内自己用群发助手时被我们劫持。
            installDismissCleanup()
            installedGeneration = generation
            HLog.event("$TAG 已挂钩 ${method.declaringClass.name}#${method.name}")
            true
        }.onFailure {
            logger("微信群发选择页Hook安装失败: ${method.toGenericString()}", it)
        }.getOrDefault(false)
    }

    /**
     * 拉起微信群发助手的好友选择页。[onPicked] 在用户点「下一步」后回到主线程。
     * 返回 false 表示没能拉起，调用方应当降级到模块自己的选择器。
     */
    fun start(activity: Activity, onPicked: (List<String>) -> Unit): Boolean {
        if (!install()) return false
        pending = onPicked
        val token = pendingToken + 1
        pendingToken = token
        val intent = Intent().apply {
            setClassName(activity.packageName, SELECT_UI)
        }
        val started = runCatching { activity.startActivity(intent) }
            .onFailure {
                if (pendingToken == token) pending = null
                logger("启动微信群发选择页失败", it)
            }
            .isSuccess
        if (!started) return false
        // 双保险：正常返回由 onDestroy 清理，这里只兜住「Activity 被系统回收」等极端情况。
        main.postDelayed({
            if (pendingToken == token) pending = null
        }, PENDING_TIMEOUT_MS)
        return true
    }

    /** 选择页被关闭（返回键 / 我们主动 finish）时清空回调。 */
    private fun installDismissCleanup() {
        val ui = KavaReflector.loadClass(SELECT_UI_CLASS_NAME, context.hostClassLoader()) ?: return
        val destroy = KavaReflector.findMethodRecursive(ui, "onDestroy") ?: return
        runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(destroy) ?: destroy, DismissCleanupHook(this))
        }.onFailure {
            logger("群发选择页生命周期Hook安装失败", it)
        }
    }

    fun cancel() {
        pending = null
        pendingToken += 1
    }

    private class NextClickHook(private val owner: NativeMassSendPicker) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (owner.handleNextClick(chain.thisObject)) return true
            return chain.proceed()
        }
    }

    private class DismissCleanupHook(private val owner: NativeMassSendPicker) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            owner.pending = null
            return result
        }
    }

    private fun handleNextClick(receiver: Any?): Boolean {
        val callback = pending ?: return false
        val activity = findActivity(receiver) ?: return false
        val selected = readSelectedUsers(receiver)
        if (selected.isEmpty()) {
            // 一个人都没选：交给微信自己弹「未选择」提示。
            return false
        }
        pending = null
        pendingToken += 1
        // 拦掉原生跳转，避免闪一下群发编辑页；再关掉选择页回到原聊天。
        runCatching { activity.finish() }
        main.post { callback(selected) }
        return true
    }

    private fun findActivity(listener: Any?): Activity? {
        val selector = findFieldValue(listener, SELECT_UI_CLASS_NAME, context.hostClassLoader())
        return selector as? Activity
    }

    private fun readSelectedUsers(listener: Any?): List<String> {
        val selector = findFieldValue(listener, SELECT_UI_CLASS_NAME, context.hostClassLoader())
            ?: return emptyList()
        val list = readFieldNamed(selector, SELECTED_FIELD)
            ?: findFieldValueByType(selector, LinkedList::class.java)
            ?: return emptyList()
        val users = (list as? Collection<*>)
            ?.mapNotNull { it as? String }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        return users
    }

    /** 按类名找字段：不依赖监听器里那个混淆字段叫什么。 */
    private fun findFieldValue(target: Any?, className: String, loader: ClassLoader?): Any? {
        val wanted = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: return null
        return findFieldValueByType(target, wanted)
    }

    private fun findFieldValueByType(target: Any?, wanted: Class<*>): Any? {
        var clazz: Class<*>? = target?.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (!wanted.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
                if (value != null) return value
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun readFieldNamed(target: Any, name: String): Any? {
        var clazz: Class<*>? = target::class.java
        while (clazz != null && clazz != Any::class.java) {
            val field = try {
                clazz.getDeclaredField(name)
            } catch (_: Throwable) {
                null
            }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun locateNextButton(): Method? {
        // 锚点用「下一步」按钮的日志文案：这是点击回调里独有的字符串，
        // 比混淆类名稳，也不受选择页字段改名影响。
        val found = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(NEXT_BUTTON_ANCHOR)
                            returnType("boolean")
                            paramCount(1)
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
        }.onFailure {
            logger("定位微信群发「下一步」回调失败", it)
        }.getOrDefault(emptyList())
        return found.firstOrNull { method ->
            !Modifier.isAbstract(method.modifiers) &&
                method.parameterTypes.firstOrNull()?.isAssignableFrom(MenuItem::class.java) != false
        }
    }

    private companion object {
        const val TAG = "[Hchat:MassPicker]"
        const val SELECT_UI = "com.tencent.mm.plugin.masssend.ui.MassSendSelectContactUI"
        const val SELECT_UI_CLASS_NAME = "com.tencent.mm.plugin.masssend.ui.MassSendSelectContactUI"
        const val SELECTED_FIELD = "E"
        const val NEXT_BUTTON_ANCHOR = "Click Next Btn"
        const val PENDING_TIMEOUT_MS = 8 * 60 * 1000L
    }
}
