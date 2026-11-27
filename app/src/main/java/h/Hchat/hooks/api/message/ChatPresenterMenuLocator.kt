package h.Hchat.hooks.api.message

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 聊天消息长按菜单的**创建/点击**入口定位器（RecyclerView 架构版本）。
 *
 * 老实现（`SingleMessageMenuLocator`）在 `ui.chatting.viewitems` 里按
 * `(非 MenuItem, View, ContextMenuInfo)` 找创建方法，8.0.80 上命中
 * `viewitems.o0#a(ak5.h4, View, ContextMenuInfo)` —— 但那个方法的**第一个参数是
 * 聊天上下文，不是菜单**，菜单项自然加不进去，功能看起来就是「完全没反应」。
 *
 * 真正的菜单构建入口在 `com.tencent.mm.ui.chatting.presenter.*`：
 * `onCreateContextMenu(ContextMenu, View, ContextMenuInfo)`，第一个参数就是菜单本体，
 * `add` / `findItem` 都能直接用。8.0.80 上共有 9 个 presenter 声明它。
 */
object ChatPresenterMenuLocator {
    private const val CHATTING_PRESENTER = "com.tencent.mm.ui.chatting.presenter."
    private const val CACHE_PREFS = "Hchat_chat_presenter_menu_cache"
    private const val CACHE_CREATE = "create_v1"
    private const val CACHE_CLICK = "click_v1"

    /** 菜单创建方法：`onCreateContextMenu(ContextMenu, View, ContextMenuInfo)`。 */
    fun createMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.loadList(prefs, key, context.hostClassLoader(), CACHE_CREATE)
            .filter(::isCreateCandidate)
        if (cached.isNotEmpty()) return cached

        val methods = findMethods(
            context,
            logger,
            MethodMatcher().apply {
                declaredClass(CHATTING_PRESENTER, StringMatchType.Contains, false)
                name("onCreateContextMenu")
                paramCount(3)
                returnType("void")
            }
        ).filter(::isCreateCandidate).distinctBy { it.toGenericString() }

        saveMethods(prefs, key, CACHE_CREATE, methods)
        return methods
    }

    /** 菜单点击方法：`onMMMenuItemSelected(MenuItem, int)`。 */
    fun clickMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.loadList(prefs, key, context.hostClassLoader(), CACHE_CLICK)
            .filter(::isClickCandidate)
        if (cached.isNotEmpty()) return cached

        val methods = findMethods(
            context,
            logger,
            MethodMatcher().apply {
                declaredClass(CHATTING_PRESENTER, StringMatchType.Contains, false)
                name("onMMMenuItemSelected")
                paramCount(2)
                returnType("void")
            }
        ).filter(::isClickCandidate).distinctBy { it.toGenericString() }

        saveMethods(prefs, key, CACHE_CLICK, methods)
        return methods
    }

    private fun findMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit,
        matcher: MethodMatcher
    ): List<Method> = runCatching {
        context.dexKitBridge().findMethod(FindMethod().apply { matcher(matcher) })
            .mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
    }.getOrElse {
        logger("定位聊天菜单 presenter 方法失败", it)
        emptyList()
    }

    private fun saveMethods(
        prefs: android.content.SharedPreferences,
        key: String,
        name: String,
        methods: List<Method>
    ) {
        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(prefs, key, name, methods)
        } else {
            DexMethodCache.clear(prefs, key, name)
        }
    }

    private fun isCreateCandidate(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) return false
        if (!method.declaringClass.name.startsWith(CHATTING_PRESENTER)) return false
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 3 &&
            types[0].name == "android.view.ContextMenu" &&
            android.view.View::class.java.isAssignableFrom(types[1])
    }

    private fun isClickCandidate(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) return false
        if (!method.declaringClass.name.startsWith(CHATTING_PRESENTER)) return false
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 2 &&
            android.view.MenuItem::class.java.isAssignableFrom(types[0]) &&
            types[1] == Integer.TYPE
    }
}
