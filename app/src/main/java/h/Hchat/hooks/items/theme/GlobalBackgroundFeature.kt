package h.Hchat.hooks.items.theme

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 全局背景（含预模糊「毛玻璃」）。
 *
 * 只把背景铺在 `LauncherUI` 的 decorView 上，并把 `android.R.id.content` 的直接
 * 子 View 背景清成透明 —— 首页、通讯录、发现、我 都在 LauncherUI 内，所以一张图
 * 能贯穿四个 tab。
 *
 * 关于模糊：**不做实时高斯模糊**。`RenderEffect` 模糊的是「某个 View 自己的内容」，
 * 不是它背后的内容，套在顶栏/底栏上只会把栏里的文字糊掉。这里的做法是把背景图
 * 先裁剪成屏幕比例、再离线模糊一次，然后铺在窗口背景上；半透明的顶栏/底栏浮在上
 * 面，静态壁纸下观感与 iOS 毛玻璃一致，且零每帧开销。
 */
class GlobalBackgroundFeature : BaseFeature() {
    private var runtime: GlobalBackgroundRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "全局背景"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GlobalBackgroundSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        // 安装结果一律走 boot 缓冲：功能在启动期安装，而日志采集通常是之后才打开。
        runtime = GlobalBackgroundRuntime(context) { message, throwable ->
            if (throwable == null) logBoot(message) else logError(message, throwable)
        }
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
        const val ID = "global_background"
        const val KEY_ENABLED = "enabled"
        const val KEY_IMAGE = "image_path"
        const val KEY_BLUR = "blur_level"
        const val KEY_DIM = "dim_level"
        const val DEFAULT_ENABLED = false
        const val DEFAULT_BLUR = 2
        const val DEFAULT_DIM = 0
        const val PICK_REQUEST_CODE = 0x484301
        const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"

        /** 模糊档位 → 半径（px 以 dp 计，0 = 不模糊）。 */
        val BLUR_RADII = intArrayOf(0, 6, 14, 26)

        /** 暗化档位 → 黑色蒙版透明度。 */
        val DIM_ALPHAS = intArrayOf(0, 46, 92)
    }
}

private class GlobalBackgroundRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = context.configStore().getFeaturePrefs(GlobalBackgroundFeature.ID)
    private val main = Handler(Looper.getMainLooper())
    private val originalWindowBackground = Collections.synchronizedMap(WeakHashMap<View, Drawable?>())
    private val originalChildBackgrounds =
        Collections.synchronizedMap(WeakHashMap<View, Drawable?>())
    private var cachedBitmap: Bitmap? = null
    private var cachedKey = ""
    private var installed = false

    fun install(): Boolean {
        ActivityResultBridge.install(context, logger)
        if (installed) return true
        val launcher = KavaReflector.loadClass(
            GlobalBackgroundFeature.LAUNCHER_UI,
            context.hostClassLoader()
        ) ?: run {
            logger("全局背景：找不到 LauncherUI", null)
            return false
        }
        val method = KavaReflector.findMethodRecursive(launcher, "onResume")
            ?: run {
                logger("全局背景：找不到 LauncherUI.onResume", null)
                return false
            }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    main.post { applyTo(activity) }
                }
            })
            installed = true
            logger("全局背景：已挂载 LauncherUI#onResume", null)
            true
        }.getOrElse {
            logger("全局背景 Hook 安装失败", it)
            false
        }
    }

    fun destroy() {
        installed = false
        cachedBitmap = null
        cachedKey = ""
        originalWindowBackground.clear()
        originalChildBackgrounds.clear()
    }

    private fun applyTo(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val enabled = prefs.getBoolean(GlobalBackgroundFeature.KEY_ENABLED, false)
        val path = prefs.getString(GlobalBackgroundFeature.KEY_IMAGE, "").orEmpty()
        if (!enabled || !HchatAssetStore.exists(activity, path)) {
            restore(activity, decor)
            return
        }
        val bitmap = render(activity, path) ?: run {
            restore(activity, decor)
            return
        }
        if (originalWindowBackground[decor] === null && !originalWindowBackground.containsKey(decor)) {
            originalWindowBackground[decor] = decor.background
        }
        decor.background = BitmapDrawable(activity.resources, bitmap)
        makeContentTransparent(activity)
    }

    private fun restore(activity: Activity, decor: View) {
        if (originalWindowBackground.containsKey(decor)) {
            decor.background = originalWindowBackground.remove(decor)
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (originalChildBackgrounds.containsKey(child)) {
                child.background = originalChildBackgrounds.remove(child)
            }
        }
    }

    private fun makeContentTransparent(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (!originalChildBackgrounds.containsKey(child)) {
                originalChildBackgrounds[child] = child.background
            }
            child.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    /**
     * 生成铺满屏幕的背景位图：先按短边放大裁剪到屏幕比例，再按档位模糊、暗化。
     *
     * 结果按「路径+档位+屏幕尺寸」缓存，切 tab、来回 resume 都不会重复解码。
     */
    private fun render(activity: Activity, storedPath: String): Bitmap? {
        val file = HchatAssetStore.resolve(activity, storedPath) ?: return null
        val path = file.absolutePath
        val metrics = activity.resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val blurRadius = GlobalBackgroundFeature.BLUR_RADII[
            prefs.getInt(GlobalBackgroundFeature.KEY_BLUR, GlobalBackgroundFeature.DEFAULT_BLUR)
                .coerceIn(0, GlobalBackgroundFeature.BLUR_RADII.size - 1)
        ]
        val dimAlpha = GlobalBackgroundFeature.DIM_ALPHAS[
            prefs.getInt(GlobalBackgroundFeature.KEY_DIM, GlobalBackgroundFeature.DEFAULT_DIM)
                .coerceIn(0, GlobalBackgroundFeature.DIM_ALPHAS.size - 1)
        ]
        val key = "$path|$blurRadius|$dimAlpha|$screenWidth|$screenHeight"
        cachedBitmap?.let { if (cachedKey == key && !it.isRecycled) return it }

        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= screenWidth &&
                bounds.outHeight / (sample * 2) >= screenHeight
            ) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            var result = centerCrop(decoded, screenWidth, screenHeight)
            if (result !== decoded) decoded.recycle()
            if (blurRadius > 0) {
                val blurred = boxBlur(result, blurRadius)
                if (blurred !== result) result.recycle()
                result = blurred
            }
            if (dimAlpha > 0) {
                val canvas = Canvas(result)
                canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
            }
            result
        }.getOrElse {
            logger("生成背景图失败", it)
            null
        } ?: return null

        cachedBitmap?.takeIf { it !== bitmap }?.recycle()
        cachedBitmap = bitmap
        cachedKey = key
        return bitmap
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(width)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val left = ((scaledWidth - width) / 2).coerceAtLeast(0)
        val top = ((scaledHeight - height) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(scaled, left, top, width, height)
        if (cropped !== scaled && scaled !== source) scaled.recycle()
        return cropped
    }

    /** 三次方框模糊近似高斯；背景图只处理一次，用不着更高级的算法。 */
    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val temp = IntArray(pixels.size)
        repeat(3) {
            blurHorizontal(pixels, temp, width, height, radius)
            blurVertical(temp, pixels, width, height, radius)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /** 横向滑动窗口平均，索引 = y * width + x。 */
    private fun blurHorizontal(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val rowStart = y * width
            var r = 0
            var g = 0
            var b = 0
            for (x in -radius..radius) {
                val px = input[rowStart + x.coerceIn(0, width - 1)]
                r += px shr 16 and 0xFF
                g += px shr 8 and 0xFF
                b += px and 0xFF
            }
            for (x in 0 until width) {
                output[rowStart + x] =
                    (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = input[rowStart + (x - radius).coerceIn(0, width - 1)]
                val into = input[rowStart + (x + radius + 1).coerceIn(0, width - 1)]
                r += (into shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (into shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (into and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** 纵向滑动窗口平均，索引仍是 y * width + x，按列扫。 */
    private fun blurVertical(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var r = 0
            var g = 0
            var b = 0
            for (y in -radius..radius) {
                val px = input[y.coerceIn(0, height - 1) * width + x]
                r += px shr 16 and 0xFF
                g += px shr 8 and 0xFF
                b += px and 0xFF
            }
            for (y in 0 until height) {
                output[y * width + x] =
                    (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = input[(y - radius).coerceIn(0, height - 1) * width + x]
                val into = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                r += (into shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (into shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (into and 0xFF) - (out and 0xFF)
            }
        }
    }
}