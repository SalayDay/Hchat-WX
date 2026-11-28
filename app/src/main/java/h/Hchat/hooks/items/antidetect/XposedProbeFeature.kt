package h.Hchat.hooks.items.antidetect

import android.content.pm.PackageManager
import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog

/**
 * 伪装 Xposed 环境探测（防封轮 · ②环境伪装）。
 *
 * ## 探针与对策
 *
 * | 探针 | 做法 |
 * | --- | --- |
 * | `packageManager.getInstalledPackages()` 找模块管理器 | 结果列表里摘掉包名含 xposed/lsposed/edxposed 的项 |
 * | `packageManager.getPackageInfo("...")` 定点探测 | 直接抛 `NameNotFoundException` |
 *
 * 正常业务不会去扫"Xposed"字样的安装应用，误伤面为零；而风控探测一旦命中，
 * 得到的是和"干净环境"完全一致的答复。
 *
 * ## ⛔ 明确不做：hook `Class.forName`
 *
 * 6.9.6.5~6.9.6.7 曾 hook `Class.forName` 两个重载并抛 CNFE 伪装"类不存在"，
 * 实测导致微信启动即闪退（6.9.6.8 移除）。原因：`de.robv.android.xposed.*` 正是
 * LSPosed 框架自身的 API 包名，框架的 hook 派发链内部也通过 `Class.forName`
 * 解析这些类——把它伪装成"不存在"等于炸掉框架自己的类解析；且 `forName(String)`
 * 内部转发 3 参重载，双重 hook 让最热的反射入口每次调用跑两遍回调。
 * 结论（C 类教训）：**凡是框架自身身份所在的名字空间，一律不能在类加载层伪装。**
 *
 * ## 边界
 *
 * - PackageManager 查询是低频调用，hook 开销无感；回调不做任何主动 throw，
 *   只有 `getPackageInfo/getApplicationInfo` 命中探测包名时抛该方法自身的
 *   `NameNotFoundException`（与宿主方法语义一致，安全）。
 * - 本模块自己的代码不会通过包名含 xposed 的应用列表拿信息，不受影响。
 */
class XposedProbeFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "伪装Xposed探测"

    /**
     * PackageManager 查询虽是低频，但仍走热路径缓存：
     * `getInstalledPackages` 在微信启动和设置页会连发几十次，每次读配置文件不值当。
     */
    @Volatile
    private var enabled: () -> Boolean = { DEFAULT_ENABLED }

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            object : SimpleFeatureSettingsProvider(
                ID,
                name(),
                "微信扫描安装应用探测 Xposed/LSPosed 环境时，把模块管理器从结果中摘除，" +
                    "伪装成干净环境。默认开启。",
                FeatureSettingsProvider.CATEGORY_ENHANCE
            ) {}
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val config = context.configStore()
        enabled = HotConfig.flag(ID, KEY_ENABLED, DEFAULT_ENABLED) {
            config.getBoolean(ID, KEY_ENABLED, DEFAULT_ENABLED)
        }
        val loader = context.hostClassLoader()
        val installed = installPackageManagerHooks(loader)
        if (installed == 0) {
            logError("环境伪装锚点一个都没挂上（微信 ${wechatVersion()}）", null)
            return
        }
        HLog.e("$TAG Xposed 探测伪装已挂载 hooks=$installed，微信 ${wechatVersion()}")
    }

    /** hook PackageManager 的安装列表查询与定点查询。 */
    private fun installPackageManagerHooks(loader: ClassLoader): Int {
        val pmClass = runCatching {
            Class.forName("android.app.ApplicationPackageManager", false, loader)
        }.getOrNull()
        if (pmClass == null) {
            HLog.e("$TAG 未找到 ApplicationPackageManager，跳过包探测伪装")
            return 0
        }
        var installed = 0
        for (method in pmClass.declaredMethods.toList()) {
            when {
                // getInstalledPackages(int) / getInstalledApplications(int)：
                // 结果列表里摘掉模块管理器，让"扫安装列表"看不到任何痕迹。
                (method.name == "getInstalledPackages" || method.name == "getInstalledApplications")
                    && method.parameterTypes.size == 1 -> {
                    try {
                        HookRegistry.get().hook(method, InstalledPackagesHooker(this))
                        installed++
                    } catch (e: Throwable) {
                        logError("挂载 ${method.name} 失败", e)
                    }
                }
                // getPackageInfo / getApplicationInfo 定点探测：命中即伪装成"应用不存在"。
                (method.name == "getPackageInfo" || method.name == "getApplicationInfo")
                    && method.parameterTypes.isNotEmpty()
                    && method.parameterTypes[0] == String::class.java -> {
                    try {
                        HookRegistry.get().hook(method, PackageProbeHooker(this))
                        installed++
                    } catch (e: Throwable) {
                        logError("挂载 ${method.name} 失败", e)
                    }
                }
            }
        }
        return installed
    }

    private fun packageInfoName(item: Any?): String? = when (item) {
        is android.content.pm.PackageInfo -> item.packageName
        is android.content.pm.ApplicationInfo -> item.packageName
        else -> null
    }

    private fun wechatVersion(): String = WeChatApis.version()?.current()?.versionName ?: "未知"

    companion object {
        const val ID = "anti_detect_xposed_probe"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = true

        private const val TAG = "[Hchat:AntiDetectProbe]"
        private val packageProbeLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        private val packageListProbeLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private class InstalledPackagesHooker(private val owner: XposedProbeFeature) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val args = chain.args.toTypedArray()
            val receiver = chain.thisObject
            var result = chain.proceed()
            with(owner) {
            run update@{
                if (!enabled()) return@update
                val raw = result ?: return@update
                if (raw !is List<*>) return@update
                val kept = raw.filter { item ->
                    !XposedProbePolicy.isDeniedPackageName(
                        packageInfoName(item)
                    )
                }
                if (kept.size != raw.size) {
                    if (packageListProbeLogged.compareAndSet(false, true)) {
                        HLog.e("$TAG 安装列表已摘除痕迹: ${raw.size} -> ${kept.size}")
                    }
                    result = ArrayList(kept)
                }
            }
            return result
            }
        }
    }

    private class PackageProbeHooker(private val owner: XposedProbeFeature) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val args = chain.args.toTypedArray()
            val receiver = chain.thisObject
            with(owner) {
            run patch@{
                if (!enabled()) return@patch
                val name = args?.getOrNull(0) as? String ?: return@patch
                if (XposedProbePolicy.isDeniedPackageName(name)) {
                    if (packageProbeLogged.compareAndSet(false, true)) {
                        HLog.e("$TAG 已伪装包探测: $name -> NameNotFoundException")
                    }
                    throw PackageManager.NameNotFoundException(name)
                }
            }
            return chain.proceed(args)
            }
        }
    }

}
