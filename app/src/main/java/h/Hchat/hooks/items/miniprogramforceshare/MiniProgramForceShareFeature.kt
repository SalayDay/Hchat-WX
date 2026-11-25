package h.Hchat.hooks.items.miniprogramforceshare

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HotConfig
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Modifier

/**
 * 小程序强制分享（对齐 Wren「小程序强制分享」）。
 *
 * 小程序通过 `wx.hideShareMenu()` / `wx.hideShareTimelineMenu()` 关掉右上角菜单里的
 * 转发入口。8.0.80 的实现链是：
 *
 * ```
 * jsapi/share/i.A (JsApiHideShareMenu)        → page.b6.o1(3, true)
 * jsapi/share/j.A (JsApiHideShareTimelineMenu)→ page.b6.o1(4, true)
 * page.b6.o1(index, hidden)                    → 找到 e1.a == index 的菜单项，写 e1.c = hidden
 * page.t7.k1()                                 → 构建菜单列表，w1.a(list, index, flag, hidden)
 * ```
 *
 * 所以拦截点只有一个：`b6.o1(IZ)` 里把 hidden=true 改回 false。顺带在列表构建时
 * （`w1.a` 的第 4 个参数）兜一层，避免菜单在 Hook 生效前就建好并保持隐藏。
 *
 * 旧版按 JSAPI 名字符串做 DexKit 定位的方案已删除：8.0.80 里这两个名字不再是
 * 方法体内的常量，两次全量扫描永远零命中，白白拖慢首次启动。
 */
class MiniProgramForceShareFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "小程序强制分享"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            object : SimpleFeatureSettingsProvider(
                ID,
                name(),
                "拦截 hideShareMenu，禁止小程序隐藏右上角转发入口",
                FeatureSettingsProvider.CATEGORY_PRACTICAL
            ) {}
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name()) { installHooks(context) }
    }

    private fun installHooks(context: FeatureContext): Boolean {
        val config = context.configStore()
        val enabled = HotConfig.flag(ID, KEY_ENABLED, DEFAULT_ENABLED) {
            config.getBoolean(ID, KEY_ENABLED, DEFAULT_ENABLED)
        }
        // 8.0.80 的 hideShareMenu 确实存在，只是不在 appbrand.jsapi 包内按名字注册：
        // `jsapi.share.i`（JsApiHideShareMenu）直接调用 `page.b6#o1(3, true)`，
        // `jsapi.share.j`（JsApiHideShareTimelineMenu）调用 `o1(4, true)`。
        // 所以真正的拦截点是菜单可见性设置方法，而不是 JSAPI 名扫描。
        val installed = installMenuHooks(context, enabled)
        if (installed == 0) {
            logError(
                "菜单注册表与可见性开关都没定位到（微信 " +
                    (WeChatApis.version()?.current()?.versionName ?: "未知") + "）",
                null
            )
            return false
        }
        HLog.e("$TAG 已装载 $installed 处菜单拦截")
        return true
    }

    /**
     * 菜单层拦截，分三处：
     *
     * 1. `menu.w1.a(List, int index, boolean, boolean hidden)` —— 构建菜单列表时
     *    第 4 个参数最终写入 `e1.c`，而 `e1.c = true` 表示**该项被隐藏**
     *    （`JsApiHideShareMenu` 正是这么调的）。把转发/朋友圈两项强制改成 false。
     * 2. `page.b6.o1(int index, boolean hidden)` —— 运行时开关的唯一落点，
     *    `wx.hideShareMenu()` / `hideShareTimelineMenu()` 都走这里。拦掉后，
     *    小程序在页面生命周期内反复调用也藏不住入口。
     * 3. `page.t7.k1()` —— 列表构建完成后的兜底校验，缺项时从注册表补回。
     *
     * 全程 fail-safe：任何一步失败只记日志，不影响小程序本身运行。
     */
    private fun installMenuHooks(context: FeatureContext, enabled: () -> Boolean): Int {
        val loader = context.hostClassLoader()
        var installed = 0
        val page = MenuTargets.resolve(loader)
        if (page == null) {
            HLog.e("$TAG 未定位到小程序菜单体系（微信 ${wechatVersion()}）")
            return 0
        }
        val registry = page.registry
        if (registry == null) {
            HLog.e("$TAG 未找到菜单注册表（微信 ${wechatVersion()}）")
        } else {
            val add = registry.declaredMethods.firstOrNull(::isMenuItemAppend)
            if (add == null) {
                HLog.e("$TAG 未找到菜单项追加方法 ${registry.name}.a(List,int,boolean,boolean)")
            } else {
                HookRegistry.get().hook(add, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        val index = param.args?.getOrNull(1) as? Int ?: return
                        if (index != SHARE_INDEX && index != TIMELINE_INDEX) return
                        // args[3] 才是 e1.c（隐藏标记）；args[2] 是首屏主菜单位，不要动。
                        param.args[3] = false
                        if (HLog.isCaptureEnabled()) {
                            HLog.event("$TAG 强制可见 index=$index flag=${param.args[2]}")
                        }
                    }
                })
                installed++
            }
        }
        // 可见性开关声明在菜单页基类上（子类只是继承），必须按基类定位。
        val gate = page.pageBase?.let { base ->
            KavaReflector.declaredMethods(base).firstOrNull { method ->
                method.name == page.gateName &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0] == Integer.TYPE &&
                    method.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    method.returnType == java.lang.Void.TYPE
            }
        }
        if (gate == null) {
            HLog.e("$TAG 未找到菜单可见性开关（微信 ${wechatVersion()}）")
        } else {
            HookRegistry.get().hook(KavaReflector.accessible(gate) ?: gate, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val index = param.args?.getOrNull(0) as? Int ?: return
                    if (index != SHARE_INDEX && index != TIMELINE_INDEX) return
                    if (param.args[1] != true) return
                    // 把「隐藏」改成「显示」；小程序调用仍返回 ok，行为无感。
                    param.args[1] = false
                    if (hitHidden.compareAndSet(false, true)) {
                        HLog.e("$TAG 已拦截 hideShareMenu(index=$index)")
                    }
                }
            })
            installed++
        }
        val build = page.listMethod
        if (build == null || build.returnType != java.util.List::class.java) {
            HLog.e("$TAG 未找到菜单列表构建方法（微信 ${wechatVersion()}）")
        } else {
            HookRegistry.get().hook(build, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled() || param.hasThrowable()) return
                    val list = param.result as? MutableList<*> ?: return
                    ensureShareItem(loader, list, page)
                    if (HLog.isCaptureEnabled()) {
                        HLog.event("$TAG 菜单列表 ${describeMenu(page, list)}")
                    }
                }
            })
            installed++
        }
        return installed
    }

    /** 微信版本名，用于把定位结果写进日志时区分版本。 */
    private fun wechatVersion(): String =
        runCatching { WeChatApis.version()?.current()?.versionName }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "未知"

    /**
     * 菜单体系的运行期定位结果。
     *
     * 8.0.76 与 8.0.80 的混淆类名完全不同（`menu.m1`/`page.u5` vs `menu.w1`/`page.b6`），
     * 所以不能写死类名，改为按**结构**定位：
     *
     * - `registry`：唯一带 `static (List, int, boolean, boolean)V` 的类；
     * - `pageBase`：唯一带 `(int, boolean)V` 的菜单页基类，`gateName` 就是它的方法名；
     * - `listMethod`：基类上无参返回 `List` 的构建方法；
     * - `menuItem`：`(int, boolean, boolean)` 三元组的菜单项类（用于补回转发项）。
     */
    internal class MenuTargets(
        val registry: Class<*>?,
        val pageBase: Class<*>?,
        val gateName: String,
        val listMethod: java.lang.reflect.Method?,
        val menuItem: Class<*>?,
        val holderField: java.lang.reflect.Field?
    ) {
        companion object {
            fun resolve(loader: ClassLoader): MenuTargets? {
                val registry = findMenuItemAppendOwner(loader)
                val gateOwner = findVisibilityGateOwner(loader)
                if (registry == null && gateOwner == null) return null
                val gateName = gateOwner?.let { (_, name) -> name }.orEmpty()
                val pageBase = gateOwner?.first
                val listMethod = pageBase?.let { base ->
                    KavaReflector.declaredMethods(base).firstOrNull {
                        it.parameterCount == 0 && it.returnType == java.util.List::class.java
                    }
                }
                val menuItem = findMenuItemClass(loader, registry)
                val holderField = registry?.let { cls ->
                    KavaReflector.declaredFields(cls).firstOrNull { field ->
                        Modifier.isStatic(field.modifiers) &&
                            android.util.SparseArray::class.java.isAssignableFrom(field.type)
                    }
                }
                return MenuTargets(registry, pageBase, gateName, listMethod, menuItem, holderField)
            }

            private fun findMenuItemAppendOwner(loader: ClassLoader): Class<*>? {
                val candidates = MENU_CANDIDATE_CLASSES.mapNotNull {
                    KavaReflector.loadClass(it, loader)
                }
                return candidates.firstOrNull { cls ->
                    cls.declaredMethods.any { method ->
                        Modifier.isStatic(method.modifiers) &&
                            method.returnType == Void.TYPE &&
                            method.parameterCount == 4 &&
                            method.parameterTypes[0] == java.util.List::class.java &&
                            method.parameterTypes[1] == Integer.TYPE &&
                            method.parameterTypes[2] == java.lang.Boolean.TYPE &&
                            method.parameterTypes[3] == java.lang.Boolean.TYPE
                    }
                }
            }

            private fun findVisibilityGateOwner(loader: ClassLoader): Pair<Class<*>, String>? {
                for (name in PAGE_CANDIDATE_CLASSES) {
                    val cls = KavaReflector.loadClass(name, loader) ?: continue
                    val method = KavaReflector.declaredMethods(cls).firstOrNull {
                        it.parameterCount == 2 &&
                            it.parameterTypes[0] == Integer.TYPE &&
                            it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                            it.returnType == Void.TYPE
                    } ?: continue
                    return cls to method.name
                }
                return null
            }

            private fun findMenuItemClass(loader: ClassLoader, registry: Class<*>?): Class<*>? {
                for (name in MENU_ITEM_CANDIDATES) {
                    KavaReflector.loadClass(name, loader)?.let { return it }
                }
                return null
            }
        }
    }

    private fun isMenuItemAppend(method: java.lang.reflect.Method): Boolean {
        if (!Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) return false
        val types = method.parameterTypes
        return types.size == 4 &&
            types[0] == java.util.List::class.java &&
            types[1] == Integer.TYPE &&
            types[2] == java.lang.Boolean.TYPE &&
            types[3] == java.lang.Boolean.TYPE
    }

    /** 菜单构建完成后若缺少转发项，从注册表取出原始菜单项重新包装后补回队首。 */
    private fun ensureShareItem(loader: ClassLoader, list: MutableList<*>, page: MenuTargets) {
        runCatching {
            val itemClass = page.menuItem ?: return
            val idField = KavaReflector.declaredFields(itemClass).firstOrNull {
                it.type == Integer.TYPE
            } ?: return
            idField.isAccessible = true
            val present = list.mapNotNull { idField.get(it) as? Int }
            if (SHARE_INDEX in present) return
            val holderField = page.holderField ?: return
            holderField.isAccessible = true
            val registry = holderField.get(null) ?: return
            // 注册表本体是 android.util.SparseArray，按已知类型直接取。
            val array = KavaReflector.readField(registry, "a") as? android.util.SparseArray<Any?> ?: return
            val item = array.get(SHARE_INDEX) ?: return
            val ctor = itemClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 } ?: return
            ctor.isAccessible = true
            val wrapper = ctor.newInstance(SHARE_INDEX, true, item)
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Any?>).add(0, wrapper)
            HLog.event("$TAG 菜单缺少转发项，已补回 index=$SHARE_INDEX")
        }.onFailure { failure ->
            HLog.e("$TAG 补回转发菜单项失败：${failure.javaClass.simpleName}")
        }
    }

    private fun describeMenu(page: MenuTargets, list: List<*>): String {
        val itemClass = page.menuItem ?: return "size=${list.size}"
        val idField = KavaReflector.declaredFields(itemClass).firstOrNull {
            it.type == Integer.TYPE
        } ?: return "size=${list.size}"
        idField.isAccessible = true
        val ids = list.take(40).mapNotNull { idField.get(it) as? Int }
        return "size=${list.size} ids=$ids"
    }

    companion object {
        const val ID = "mini_program_force_share"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = false

        private const val TAG = "[Hchat:ForceShare]"

        // 菜单体系按版本变化的混淆类名（8.0.80 / 8.0.76 已用 DexSQL 逐项核对）。
        // 定位逻辑不依赖这些名字本身：按方法签名在候选里挑，名字只是候选取样，
        // 新版本只要类还在这几个包内，换代时把新名字追加进来即可。
        private val MENU_CANDIDATE_CLASSES = listOf(
            // 8.0.80：w1 是 List(int,int,boolean,boolean) 的持有者
            "com.tencent.mm.plugin.appbrand.menu.w1",
            // 8.0.76：m1
            "com.tencent.mm.plugin.appbrand.menu.m1",
            "com.tencent.mm.plugin.appbrand.menu.w0",
            "com.tencent.mm.plugin.appbrand.menu.v0"
        )

        private val PAGE_CANDIDATE_CLASSES = listOf(
            // 8.0.80：b6 声明 o1(IZ)V，t7 是子类
            "com.tencent.mm.plugin.appbrand.page.b6",
            // 8.0.76：u5 声明 n1(IZ)V
            "com.tencent.mm.plugin.appbrand.page.u5",
            "com.tencent.mm.plugin.appbrand.page.t7",
            "com.tencent.mm.plugin.appbrand.page.m7"
        )

        private val MENU_ITEM_CANDIDATES = listOf(
            // 8.0.80：e1 带 (int,boolean,boolean) 构造
            "com.tencent.mm.plugin.appbrand.menu.e1",
            // 8.0.76：u0 带 (int,boolean,boolean) 三元组字段
            "com.tencent.mm.plugin.appbrand.menu.u0"
        )

        /**
         * 菜单项 index：3 = 发送给朋友（menu.v0），4 = 分享到朋友圈（menu.x0）。
         *
         * 已核对：`jsapi/share/i`（JsApiHideShareMenu）→ `b6.o1(3, true)`；
         * `jsapi/share/j`（JsApiHideShareTimelineMenu）→ `b6.o1(4, true)`。
         * 第二个参数为 true 表示隐藏。
         */
        private const val SHARE_INDEX = 3
        private const val TIMELINE_INDEX = 4

        /** 只用于日志去重：hideShareMenu 会被反复调用，没必要每次都写一条。 */
        private val hitHidden = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
