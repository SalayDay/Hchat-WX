package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.content.res.Resources
import android.view.MenuItem
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

object ModuleResourceMenuFallback {
    private const val MENU_BUILDER = "com.android.internal.view.menu.MenuBuilder"
    private val INT = Int::class.javaPrimitiveType!!
    private val selectionMenus = java.util.WeakHashMap<Any, Boolean>()
    private val LABELS = mapOf(
        "copy_text" to "复制", "paste_text" to "粘贴",
        "text_selection_more" to "更多", "select_all" to "全选"
    )
    private val LABELS_BY_ITEM = mapOf(1 to "复制", 2 to "粘贴", 3 to "更多")

    fun install(classLoader: ClassLoader) {
        val menuClass = KavaReflector.loadClass(MENU_BUILDER, classLoader) ?: return
        for (name in listOf("add", "addSubMenu")) {
            runCatching {
                val method = KavaReflector.findMethod(menuClass, name, INT, INT, INT, INT)
                    ?: error("未找到菜单资源重载: $name")
                HookRegistry.get().hook(method, TitleResourceHooker(name))
            }.onFailure { HLog.e("菜单资源兜底安装失败: $name", it) }
        }
        runCatching {
            val method = KavaReflector.findMethod(menuClass, "dispatchMenuItemSelected", menuClass, MenuItem::class.java)
                ?: error("未找到选词菜单点击入口")
            HookRegistry.get().hook(method, MenuClickHooker())
        }.onFailure { HLog.e("选词菜单点击接管安装失败", it) }
    }

    private class MenuClickHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val item = chain.args.getOrNull(1) as? MenuItem ?: return chain.proceed()
            val menu = chain.args.getOrNull(0) ?: chain.thisObject
            if (!isSelectionMenu(menu) && !isSelectionMenu(chain.thisObject)) return chain.proceed()
            val terminal = TerminalBridge.current() ?: return chain.proceed()
            when (item.itemId) {
                1 -> {
                    val text = terminal.selectedText()
                    if (text.isNullOrEmpty()) return chain.proceed()
                    terminal.copyToClipboard(text)
                    terminal.stopSelection()
                }
                2 -> { terminal.stopSelection(); terminal.pasteFromClipboard() }
                3 -> { terminal.stopSelection(); terminal.showMoreMenu() }
                else -> return chain.proceed()
            }
            closeActionMode(menu)
            return true
        }
    }

    private class TitleResourceHooker(private val name: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val args = chain.args
            val resId = args.getOrNull(3) as? Int ?: return chain.proceed()
            if (resId == 0) return chain.proceed()
            val menu = chain.thisObject ?: return chain.proceed()
            val context = contextOf(menu) ?: return chain.proceed()
            val entry = ModuleResources.entryName(context, resId)
            val hostRes = resourcesOf(menu)
            val hostIsString = hostRes != null && runCatching {
                hostRes.getResourceTypeName(resId)
            }.getOrNull() == "string"
            val termuxMenu = isTermuxSelectionMenu(menu)
            val label: CharSequence? = when {
                entry != null && LABELS.containsKey(entry) && (!hostIsString || termuxMenu) -> LABELS[entry]
                entry != null && !hostIsString && termuxMenu -> ModuleResources.text(context, resId)
                entry == null && !hostIsString && termuxMenu -> LABELS_BY_ITEM[args[1] as? Int ?: -1]
                else -> null
            }
            if (label == null) return chain.proceed()
            if (termuxMenu) markSelectionMenu(menu)
            val overload = KavaReflector.findMethod(menu.javaClass, name, INT, INT, INT, CharSequence::class.java)
                ?: return chain.proceed()
            return KavaReflector.invoke(overload, menu, args[0], args[1], args[2], label)
        }
    }

    private fun field(target: Any?, name: String): Any? {
        val member = KavaReflector.findFieldRecursive(target?.javaClass, name) ?: return null
        return KavaReflector.readField(member, target)
    }

    private fun closeActionMode(menu: Any?) {
        runCatching {
            val actionMode = field(menu, "mCallback") ?: return
            KavaReflector.invokeMethod(actionMode, "finish")
        }
    }

    private fun markSelectionMenu(menuBuilder: Any) {
        synchronized(selectionMenus) { selectionMenus[menuBuilder] = true }
    }

    private fun isSelectionMenu(menuBuilder: Any?): Boolean = menuBuilder != null &&
        synchronized(selectionMenus) { selectionMenus.containsKey(menuBuilder) }

    private fun isTermuxSelectionMenu(menuBuilder: Any?): Boolean = fromCallStack() || fromMenuCallback(menuBuilder)

    private fun fromCallStack(): Boolean = runCatching {
        Throwable().stackTrace.any { it.className.startsWith("com.termux.view") }
    }.getOrDefault(false)

    private fun fromMenuCallback(menuBuilder: Any?): Boolean = runCatching {
        val actionMode = field(menuBuilder, "mCallback") ?: return false
        val callback = field(actionMode, "mCallback") ?: return false
        callback.javaClass.name.startsWith("com.termux.view")
    }.getOrDefault(false)

    private fun resourcesOf(menuBuilder: Any?): Resources? =
        runCatching { field(menuBuilder, "mResources") as? Resources }.getOrNull()
            ?: runCatching { KavaReflector.invokeMethod(menuBuilder, "getResources") as? Resources }.getOrNull()

    private fun contextOf(menuBuilder: Any?): Context? =
        runCatching { KavaReflector.invokeMethod(menuBuilder, "getContext") as? Context }.getOrNull()
}
