package h.Hchat.hooks.api.message

import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 聊天长按菜单的原生创建和选择回调。
 *
 * 8.0.76/80 的 MMPopupMenu 直接把 ContextMenu 的具体实现传给 viewitems 的创建回调，
 * 点击经 OnClick/OnItemClick 分发给同包 onMMMenuItemSelected。具体类名和混淆方法名
 * 均由日志锚点和接口形状定位；此前认定首参不是菜单、改挂 presenter 的判断不成立。
 */
object SingleMessageMenuLocator {
    const val HCHAT_REPEAT_MENU_ITEM_ID = 0x48435250
    const val HCHAT_FORWARD_MENU_ITEM_ID = 0x48434657
    private const val PACKAGE = "com.tencent.mm.ui.chatting.viewitems."
    private const val PREFS = "Hchat_single_message_menu_method_cache"
    private const val CREATE = "menu_create_native_v2"
    private const val CLICK = "menu_click_native_v2"

    @JvmStatic
    fun menuCreateMethods(context: FeatureContext, logger: (String, Throwable?) -> Unit): List<Method> =
        find(context, logger, CREATE, "msg is null!", ChatMenuMethodShape::isCreate, 3)

    @JvmStatic
    fun menuClickMethods(context: FeatureContext, logger: (String, Throwable?) -> Unit): List<Method> =
        find(context, logger, CLICK, "context item select failed, null dataTag", ChatMenuMethodShape::isClick, 2)

    private fun find(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit,
        name: String,
        anchor: String,
        shape: (Method) -> Boolean,
        parameterCount: Int
    ): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val valid: (Method) -> Boolean = { method ->
            method.declaringClass.name.startsWith(PACKAGE) && shape(method)
        }
        val cached = DexMethodCache.loadList(prefs, key, context.hostClassLoader(), name)
        if (cached.isNotEmpty() && cached.all(valid)) return cached.distinct()
        val methods = runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().apply {
                    declaredClass(PACKAGE, StringMatchType.StartsWith, false)
                    usingEqStrings("MicroMsg.ChattingItem", anchor)
                    returnType("void")
                    paramCount(parameterCount)
                })
            }).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(valid).distinct()
        }.getOrElse {
            logger("定位原生单消息菜单失败: $name", it)
            emptyList()
        }
        if (methods.isEmpty()) {
            DexMethodCache.clear(prefs, key, name)
        } else {
            DexMethodCache.saveList(prefs, key, name, methods)
        }
        return methods
    }
}

/** 静态接口约束；具体 ContextMenu 实现类的名称可以混淆。 */
internal object ChatMenuMethodShape {
    private fun executable(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            !method.declaringClass.isInterface && method.returnType == Void.TYPE

    fun isCreate(method: Method): Boolean {
        if (!executable(method)) return false
        val types = method.parameterTypes
        return types.size == 3 && ContextMenu::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) &&
            types[2] == ContextMenu.ContextMenuInfo::class.java
    }

    fun isClick(method: Method): Boolean {
        if (!executable(method)) return false
        val types = method.parameterTypes
        return types.size == 2 && MenuItem::class.java.isAssignableFrom(types[0]) &&
            types[1] == Integer.TYPE
    }
}
