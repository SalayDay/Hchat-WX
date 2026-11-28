package h.Hchat.hooks.items.emojilimit

import io.github.libxposed.api.XposedInterface
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatVersionMatch
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class EmojiCollectionLimitFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "扩展表情收藏上限"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(object : SimpleFeatureSettingsProvider(
            ID, name(), "将本机收藏上限提高到 5000", FeatureSettingsProvider.CATEGORY_PRACTICAL
        ) {})
    }

    override fun onFeatureInstall(context: FeatureContext) {
        // 不要在这里取版本：onFeatureInstall 期间 WeChatApis 可能还没 init，
        // 取到 null 就直接 return，等于本进程永久不再安装，且没有任何日志。
        // 统一放进调度器，等 DexKit/公共 API 就绪后再判断，失败还能重试。
        DexInstallScheduler.schedule(ID, name()) { installLimitHook(context) }
    }

    private fun installLimitHook(context: FeatureContext): Boolean {
        val version = WeChatApis.version()?.current() ?: return false
        // 注意：这里不能拿 versionCode 比 3220。3220 是 versionName 里的内部
        // build 号（8.0.80_3220），PackageInfo.versionCode 实际是 280005032，
        // 直接比较会恒为 false，导致 Hook 静默不安装。
        if (version.packageName != "com.tencent.mm") return true
        if (!WeChatVersionMatch.matches(version, TARGET_MAIN_VERSION, TARGET_BUILD_NO)) {
            // 版本不匹配是确定性结果，重试没有意义：记录一次日志后放弃本轮。
            logError(
                "微信版本不匹配（当前 ${version.versionName} / build ${version.versionCode}，" +
                    "需要 ${TARGET_MAIN_VERSION}_$TARGET_BUILD_NO），跳过 Hook",
                null
            )
            return true
        }

        val host = context.hostContext()
        val loader = context.hostClassLoader()
        val prefs = DexMethodCache.prefs(host, "Hchat_emoji_collection_limit_method_cache")
        val runtimeKey = DexMethodCache.runtimeKey(host, loader)
        val cached = DexMethodCache.load(prefs, runtimeKey, loader, CACHE_METHOD)?.takeIf(::isLimitGetter)
        // The storage full check, collection manager and album picker share this getter.
        val method = cached ?: context.dexKitBridge().findMethod(
            FindMethod().matcher(
                MethodMatcher().declaredClass("tr.a0").name("a").paramCount(0)
                    .returnType("int").usingEqStrings("CustomEmojiMaxSize")
            )
        ).singleOrNull()?.getMethodInstance(loader)?.takeIf(::isLimitGetter)
        if (method == null) {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_METHOD)
            logError("未匹配收藏上限方法 tr.a0.a()I，跳过 Hook", null)
            return false
        }

        val config = context.configStore()
        val enabled = HotConfig.flag(ID, KEY_ENABLED, DEFAULT_ENABLED) {
            config.getBoolean(ID, KEY_ENABLED, DEFAULT_ENABLED)
        }
        HookRegistry.get().hook(method, CollectionLimitHooker(enabled))
        if (cached == null) DexMethodCache.save(prefs, runtimeKey, CACHE_METHOD, method)
        return true
    }

    private class CollectionLimitHooker(private val enabled: () -> Boolean) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (enabled()) return EXPANDED_LIMIT
            return chain.proceed()
        }
    }

    private fun isLimitGetter(method: Method): Boolean =
        method.declaringClass.name == "tr.a0" && method.name == "a" && method.parameterCount == 0 &&
            method.returnType == Int::class.javaPrimitiveType && Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers)

    companion object {
        const val ID = "emoji_collection_limit"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = false
        private const val EXPANDED_LIMIT = 5000
        private const val CACHE_METHOD = "limit_getter_v1"

        /** 主版本名，对应 `PackageInfo.versionName` 去掉 `_build` 后缀。 */
        private const val TARGET_MAIN_VERSION = "8.0.80"

        /** 内部 build 号，对应 `versionName` 里的 `_3220`，不是 `PackageInfo.versionCode`。 */
        private const val TARGET_BUILD_NO = 3220L
    }
}
