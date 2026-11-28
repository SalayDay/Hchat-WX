package h.Hchat.hooks.core

import io.github.libxposed.api.XposedInterface

/** 当前进程的原生框架接口；不转换 Hook 回调、调用链或卸载句柄。 */
object ModuleRuntime {
    @Volatile
    private var framework: XposedInterface? = null

    @JvmStatic
    fun bind(api: XposedInterface) {
        // 新代次覆盖旧值：热重载会创建新的模块实例，这里不能因为旧绑定还在就抛异常，
        // 否则整个入口在该进程直接失效。
        framework = api
    }

    @JvmStatic
    fun api(): XposedInterface = checkNotNull(framework) { "LibXposed 模块尚未加载" }
}
