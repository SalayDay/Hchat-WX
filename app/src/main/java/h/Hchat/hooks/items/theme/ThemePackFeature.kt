package h.Hchat.hooks.items.theme

import android.content.Context
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 主题包导入导出。
 *
 * 直接打包整个 `Hchat` 配置目录（FastKV 的 .kva/.kvb/.kvc + `beautify_assets` 素材）：
 * 美化项分散在十几个 feature prefs 里，逐个字段挑出来维护成本太高，整目录打包
 * 既不会漏，也不需要每加一个美化开关就改一次导出逻辑。
 *
 * 导入后**必须重启微信**：FastKV 实例在进程内已缓存，覆盖文件不会让已打开的实例
 * 立即读到新值，热重载会拿到半新半旧的状态。
 */
class ThemePackFeature : BaseFeature() {
    private var bridgeReady = false

    override fun featureId(): String = ID

    override fun name(): String = "主题包"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ThemePackSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        bridgeReady = ActivityResultBridge.install(context, ::logFeatureError)
        if (!bridgeReady) logError("主题包：文件选择通道不可用", null)
        subscribe(Events.DexReady::class.java) {
            if (!bridgeReady) bridgeReady = ActivityResultBridge.install(context, ::logFeatureError)
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "theme_pack"
        const val PICK_REQUEST_CODE = 0x484303
        private const val MANIFEST = "manifest.json"
        internal const val THEMES_DIR = "themes"
    }
}

class ThemePackSettingsProvider : h.Hchat.ui.SimpleFeatureSettingsProvider(
    ThemePackFeature.ID,
    "主题包",
    "把当前全部模块配置与素材导出成 zip，或用别人的包一键套用",
    h.Hchat.ui.FeatureSettingsProvider.CATEGORY_PRACTICAL
)

object ThemePackManager {
    /** 导出当前配置；返回 zip 的绝对路径。 */
    fun export(context: Context): String? = runCatching {
        val root = HchatStorage.storageDir(context)
        val themesDir = HchatAssetStore.themeDir(context)
        val name = "HchatTheme_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"
        val target = File(themesDir, name)
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val manifest = JSONObject().apply {
                put("package", context.packageName)
                put("createdAt", System.currentTimeMillis())
                put("schema", 1)
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            root.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (file.name == ThemePackFeature.THEMES_DIR) return@forEach
                    addDirectory(zip, file, file.name)
                } else if (!file.name.endsWith(".zip")) {
                    addFile(zip, file, file.name)
                }
            }
        }
        target.absolutePath
    }.getOrNull()

    /** 把主题包写回配置目录；返回成功写入的文件数。 */
    fun import(context: Context, uri: android.net.Uri): Int = runCatching {
        val root = HchatStorage.storageDir(context)
        var written = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (entry.isDirectory || name == "manifest.json") {
                        zip.closeEntry()
                        continue
                    }
                    // 只接受配置目录内的相对路径，防止 zip 里写 ../ 越界覆盖系统文件。
                    if (name.contains("..") || name.startsWith("/") ||
                        name.startsWith(ThemePackFeature.THEMES_DIR + "/")
                    ) {
                        zip.closeEntry()
                        continue
                    }
                    val target = File(root, name)
                    target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                    target.outputStream().use { output -> zip.copyTo(output) }
                    written++
                    zip.closeEntry()
                }
            }
        }
        written
    }.getOrDefault(0)

    private fun addDirectory(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.forEach { child ->
            val path = "$prefix/${child.name}"
            if (child.isDirectory) addDirectory(zip, child, path) else addFile(zip, child, path)
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        // 导出时把素材里的绝对路径替换成相对路径，换机/换目录后仍能对上。
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(file.readBytes())
        zip.closeEntry()
    }
}