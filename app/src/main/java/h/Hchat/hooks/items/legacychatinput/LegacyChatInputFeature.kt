package h.Hchat.hooks.items.legacychatinput

import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatVersionMatch
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector

class LegacyChatInputFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "恢复旧版聊天输入框"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(LegacyChatInputSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val hostRuntime = context.hostRuntime()
        val packageName = hostRuntime.packageName
        if (!packageName.startsWith(WECHAT_PACKAGE_PREFIX)) return
        if (hostRuntime.processName != packageName) return

        // 版本判定改用 versionName 归一化比较。
        // 原实现拿 PackageInfo.versionCode 直接比 3220，但 3220 是 versionName 里的
        // 内部 build 号（8.0.80_3220），PackageInfo 实际返回 280005032，判断恒为 false，
        // Hook 会静默不安装。
        val version = WeChatApis.version()?.current() ?: run {
            logError("读取微信版本失败，旧版输入框 Hook 未安装", null)
            return
        }
        if (!WeChatVersionMatch.matches(version, TARGET_MAIN_VERSION, TARGET_BUILD_NO)) return

        val classLoader = context.hostClassLoader()
        val configManagerClass = KavaReflector.loadClass(CONFIG_MANAGER_CLASS, classLoader)
        val configBaseClass = KavaReflector.loadClass(CONFIG_BASE_CLASS, classLoader)
        val inputBarConfigClass = KavaReflector.loadClass(
            LegacyChatInputPolicy.INPUT_BAR_CONFIG_CLASS_NAME,
            classLoader
        )
        if (configManagerClass == null || configBaseClass == null || inputBarConfigClass == null) {
            logError("微信 8.0.80 输入栏配置类未找到，旧版输入框 Hook 未安装", null)
            return
        }
        if (!configBaseClass.isAssignableFrom(inputBarConfigClass)) {
            logError("输入栏配置类与配置读取方法参数不匹配，旧版输入框 Hook 未安装", null)
            return
        }

        val readMethods = runCatching {
            KavaReflector.declaredMethods(configManagerClass).filter { method ->
                method.name == CONFIG_READ_METHOD &&
                    method.returnType == Integer.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(configBaseClass))
            }
        }.getOrElse {
            logError("定位微信输入栏配置读取方法失败", it)
            return
        }
        val readMethod = readMethods.singleOrNull() ?: run {
            logError(
                "微信输入栏配置读取方法不唯一或不存在: " +
                    "$CONFIG_MANAGER_CLASS#$CONFIG_READ_METHOD(${configBaseClass.name}):int",
                null
            )
            return
        }
        val preferences = LegacyChatInputSettings.preferences(context.hostContext())

        runCatching {
            HookRegistry.get().hook(readMethod, LegacyInputConfigHooker(preferences))
        }.onFailure {
            logError("安装微信原生旧版输入栏配置 Hook 失败", it)
        }
    }

    private class LegacyInputConfigHooker(
        private val preferences: android.content.SharedPreferences
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val config = chain.args.firstOrNull() ?: return chain.proceed()
            val enabled = preferences.getBoolean(
                LegacyChatInputSettings.KEY_ENABLE,
                LegacyChatInputSettings.DEFAULT_ENABLE
            )
            if (LegacyChatInputPolicy.shouldForceLegacy(enabled, config.javaClass.name)) return 0
            return chain.proceed()
        }
    }

    companion object {
        const val ID = "restore_legacy_chat_input"

        private const val WECHAT_PACKAGE_PREFIX = "com.tencent.mm"
        private const val CONFIG_MANAGER_CLASS = "tc2.e"
        private const val CONFIG_BASE_CLASS = "ee5.e"
        private const val CONFIG_READ_METHOD = "c"

        // 与 LegacyChatInputPolicy 里的常量保持同值，直接内联避免跨 object 的
        // 编译期常量解析问题。
        private const val TARGET_MAIN_VERSION = "8.0.80"
        private const val TARGET_BUILD_NO = 3220L
    }
}

internal object LegacyChatInputPolicy {
    /**
     * 主版本名，对应 `PackageInfo.versionName` 去掉 `_build` 后缀。
     * 真正的门控见 [WeChatVersionMatch.matches]。
     */
    const val TARGET_MAIN_VERSION = "8.0.80"

    /** 内部 build 号，对应 `versionName` 的 `_3220` 后缀，**不是** `PackageInfo.versionCode`。 */
    const val TARGET_BUILD_NO = 3220L

    const val INPUT_BAR_CONFIG_CLASS_NAME =
        "com.tencent.mm.repairer.config.chatting.RepairerConfigInputBarVoiceInput"

    /**
     * 版本匹配已在安装阶段完成（见 [LegacyChatInputFeature.onFeatureInstall]），
     * 这里只判断开关与配置对象，避免每次读配置都重复比对版本。
     */
    fun shouldForceLegacy(enabled: Boolean, configClassName: String): Boolean =
        enabled && configClassName == INPUT_BAR_CONFIG_CLASS_NAME
}
