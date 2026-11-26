package h.Hchat.hooks.items.theme

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局字体与字重。
 *
 * 拦 `TextView.setTypeface` 两个重载，把传入的 Typeface 换成「导入字体 + 字重」的组合。
 *
 * 表情会不会花：API 29+ 用 `Typeface.CustomFallbackBuilder` 把系统字体挂成回退链，
 * 缺字（含 emoji）会落回系统字体，不会出现方块；低版本只能直接替换，需要用户自己
 * 确认所选字体是否自带 emoji。
 */
class GlobalFontFeature : BaseFeature() {
    private var runtime: GlobalFontRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "全局字体"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GlobalFontSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = GlobalFontRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) { runtime?.install() == true }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "global_font"
        const val KEY_ENABLED = "enabled"
        const val KEY_FONT = "font_path"
        const val KEY_WEIGHT = "font_weight"
        const val DEFAULT_ENABLED = false
        const val DEFAULT_WEIGHT = 0
        const val PICK_REQUEST_CODE = 0x484302

        /** 字重档位：0 = 不调，其余直接给 Typeface 的 weight 值。 */
        val WEIGHTS = intArrayOf(0, 400, 500, 600, 700)
    }
}

private class GlobalFontRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = context.configStore().getFeaturePrefs(GlobalFontFeature.ID)
    private val cache = ConcurrentHashMap<Int, Typeface>()
    @Volatile private var customTypeface: Typeface? = null
    @Volatile private var customPath = ""
    private var installed = false

    fun install(): Boolean {
        if (installed) return true
        val plain = KavaReflector.findMethodRecursive(
            TextView::class.java,
            "setTypeface",
            Typeface::class.java
        )
        val styled = KavaReflector.findMethodRecursive(
            TextView::class.java,
            "setTypeface",
            Typeface::class.java,
            Integer.TYPE
        )
        if (plain == null && styled == null) {
            logger("全局字体：找不到 TextView.setTypeface", null)
            return false
        }
        var hooked = 0
        plain?.let { method ->
            if (hook(method) { param ->
                    val original = param.args?.getOrNull(0) as? Typeface
                    resolve(original, Typeface.NORMAL)?.let { param.args[0] = it }
                }
            ) {
                hooked++
            }
        }
        styled?.let { method ->
            if (hook(method) { param ->
                    val original = param.args?.getOrNull(0) as? Typeface
                    val style = (param.args?.getOrNull(1) as? Number)?.toInt() ?: Typeface.NORMAL
                    resolve(original, style)?.let { param.args[0] = it }
                }
            ) {
                hooked++
            }
        }
        installed = hooked > 0
        if (installed) {
            logger("全局字体：已挂载 TextView.setTypeface ×$hooked", null)
        } else {
            logger("全局字体 Hook 未安装", null)
        }
        return installed
    }

    private fun hook(method: java.lang.reflect.Method, body: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    runCatching { body(param) }
                }
            })
            true
        }.getOrElse {
            logger("全局字体 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    fun destroy() {
        installed = false
        cache.clear()
        customTypeface = null
        customPath = ""
    }

    private fun enabled(): Boolean =
        prefs.getBoolean(GlobalFontFeature.KEY_ENABLED, GlobalFontFeature.DEFAULT_ENABLED)

    /**
     * 返回要替换成的 Typeface；null 表示这次不动（未开启、未选字体且未调字重）。
     */
    private fun resolve(original: Typeface?, style: Int): Typeface? {
        val weight = GlobalFontFeature.WEIGHTS[
            prefs.getInt(GlobalFontFeature.KEY_WEIGHT, GlobalFontFeature.DEFAULT_WEIGHT)
                .coerceIn(0, GlobalFontFeature.WEIGHTS.size - 1)
        ]
        val custom = customTypeface()
        if (custom == null && weight == 0) return null
        val base = custom ?: original ?: return null
        // 等宽/装饰字体（例如代码块、表情专用字体）不替换，避免显示异常。
        if (custom == null && (original == Typeface.MONOSPACE || original == Typeface.SERIF)) return null
        val key = weight * 4 + (style and 3)
        cache[key]?.let { return it }
        val built = buildTypeface(base, weight, style)
        cache[key] = built
        return built
    }

    private fun buildTypeface(base: Typeface, weight: Int, style: Int): Typeface {
        if (weight <= 0) return base
        val italic = style and Typeface.ITALIC != 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Typeface.create(base, weight, italic) }.getOrNull()?.let { return it }
        }
        return runCatching {
            Typeface.create(base, if (italic) Typeface.BOLD_ITALIC else Typeface.BOLD)
        }.getOrDefault(base)
    }

    /** 懒加载导入字体；路径变了就重建，并把回退链挂上系统字体。 */
    private fun customTypeface(): Typeface? {
        val path = prefs.getString(GlobalFontFeature.KEY_FONT, "").orEmpty()
        val file = HchatAssetStore.resolve(context.hostContext(), path) ?: return null
        customTypeface?.let { if (customPath == path) return it }
        synchronized(this) {
            customTypeface?.let { if (customPath == path) return it }
            val loaded = runCatching {
                val raw = Typeface.createFromFile(file)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // CustomFallbackBuilder 只接受 FontFamily；把导入字体包成 family 后
                    // 再把系统字体挂成回退，缺字/emoji 才不会变成方块。
                    runCatching {
                        val family = FontFamily.Builder(
                            Font.Builder(file).build()
                        ).build()
                        Typeface.CustomFallbackBuilder(family)
                            .setSystemFallback("sans-serif")
                            .build()
                    }.getOrDefault(raw)
                } else {
                    raw
                }
            }.getOrElse {
                logger("加载导入字体失败", it)
                null
            }
            customTypeface = loaded
            customPath = if (loaded != null) path else ""
            return loaded
        }
    }
}
