package h.Hchat.hooks.items.theme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import io.github.libxposed.api.XposedInterface
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统文件选择的结果回传通道。
 *
 * 设置页是注入在微信 Activity 上的 Compose，拿不到 ActivityResultRegistry，
 * 所以这里 hook 一次 `Activity.onActivityResult`，按 requestCode 把结果交回
 * 发起方。requestCode 统一用 `0x4843xx` 段，避开微信自己的取值。
 */
internal object ActivityResultBridge {
    private val pending = ConcurrentHashMap<Int, (Uri?) -> Unit>()
    @Volatile private var installed = false

    fun install(context: FeatureContext, logger: (String, Throwable?) -> Unit): Boolean {
        if (installed) return true
        val method = KavaReflector.findMethodRecursive(
            Activity::class.java,
            "onActivityResult",
            Integer.TYPE,
            Integer.TYPE,
            Intent::class.java
        ) ?: run {
            logger("文件选择通道：找不到 onActivityResult", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, ActivityResultHooker())
            installed = true
            true
        }.getOrElse {
            logger("文件选择通道 Hook 安装失败", it)
            false
        }
    }

    private class ActivityResultHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val requestCode = (chain.args.getOrNull(0) as? Number)?.toInt() ?: return result
            val callback = ActivityResultBridge.pending.remove(requestCode) ?: return result
            val resultCode = (chain.args.getOrNull(1) as? Number)?.toInt()
            val data = chain.args.getOrNull(2) as? Intent
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            runCatching { callback(uri) }
            return result
        }
    }

    /**
     * 打开系统文件选择器。
     *
     * @return true 表示选择器已拉起（结果会回调到 [onResult]）。
     */
    fun pickDocument(
        activity: Activity,
        mimeTypes: Array<String>,
        requestCode: Int,
        onResult: (Uri?) -> Unit
    ): Boolean {
        pending[requestCode] = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            activity.startActivityForResult(intent, requestCode)
            true
        }.getOrElse {
            pending.remove(requestCode)
            false
        }
    }

    fun requestCodes(): IntRange = 0x484300..0x4843FF
}