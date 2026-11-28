package h.Hchat.hooks.api.message

import android.view.View
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 聊天消息长按菜单的打开入口。微信 8.0.76/80 已确认：此调用同步创建并显示菜单。
 * 只用于限定弹窗样式的业务作用域，不接管任何点击、菜单项或消息数据。
 */
object ChatMenuPopupLocator {
    private const val PREFS = "Hchat_chat_menu_popup_cache"
    private const val METHOD_KEY = "open_context_v1"
    private const val LOG_TAG = "MicroMsg.ChattingItem"
    private const val OPEN_LOG = "openContextMenu[%s] left:%s, top:%s, width:%s, height:%s"
    private const val POSITION_LOG = "openContextMenu posX:%s, posY:%s"

    fun openMethods(context: FeatureContext, logger: (String, Throwable?) -> Unit): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.loadList(prefs, key, context.hostClassLoader(), METHOD_KEY)
        if (cached.isNotEmpty() && cached.all(::isOpenMethod)) return cached

        val found = runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().apply {
                    usingEqStrings(LOG_TAG, OPEN_LOG, POSITION_LOG)
                    paramTypes("android.view.View")
                    returnType("void")
                })
            }).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isOpenMethod).distinct()
        }.getOrElse {
            logger("菜单美化：定位聊天长按菜单打开入口失败", it)
            emptyList()
        }
        if (found.isEmpty()) {
            DexMethodCache.clear(prefs, key, METHOD_KEY)
        } else {
            DexMethodCache.saveList(prefs, key, METHOD_KEY, found)
        }
        return found
    }

    private fun isOpenMethod(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.declaringClass.name.startsWith("com.tencent.mm.ui.chatting.viewitems.") &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(View::class.java))
}
