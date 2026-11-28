package h.Hchat.hooks.core

import android.content.pm.ApplicationInfo

/**
 * 一次宿主进程初始化的不可变信息。
 *
 * ClassLoader 来自原生 PackageReadyParam；Tinker 切换后的实际加载器仍由入口按原有规则解析。
 * 不持有调用链，也不实现旧框架的 LoadPackage 参数接口。
 */
data class HostRuntime(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val applicationInfo: ApplicationInfo,
    val isFirstPackage: Boolean
)
