package h.Hchat.hooks.items.theme

import android.content.Context
import android.net.Uri
import h.Hchat.preferences.HchatStorage
import java.io.File

/**
 * 美化素材仓库：背景图、字体、主题包统一放这里。
 *
 * 素材必须落成模块目录里的文件，不能只存 `content://` Uri：
 * 微信进程重启、SAF 授权失效后 Uri 就读不到了，而背景/字体是每次启动都要用的。
 */
object HchatAssetStore {
    private const val DIR = "beautify_assets"
    private const val MAX_IMAGE_BYTES = 24L * 1024L * 1024L
    private const val MAX_FONT_BYTES = 24L * 1024L * 1024L

    fun assetDir(context: Context): File {
        val dir = File(HchatStorage.storageDir(context), DIR)
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /**
     * 把 Uri 指向的图片复制进素材目录。
     *
     * @return 保存后的绝对路径；失败返回 null。
     */
    fun importImage(context: Context, uri: Uri, tag: String = "bg"): String? =
        import(context, uri, tag, MAX_IMAGE_BYTES) { bytes ->
            // 靠文件头判断扩展名，避免 MIME 不准时拿到 .bin 打不开。
            when {
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
                bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF8") -> "gif"
                bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
                else -> "img"
            }
        }

    /** 导入字体文件（ttf/otf/ttc），返回保存路径。 */
    fun importFont(context: Context, uri: Uri): String? =
        import(context, uri, "font", MAX_FONT_BYTES) { bytes ->
            when {
                bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "OTTO" -> "otf"
                bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "ttcf" -> "ttc"
                else -> "ttf"
            }
        }

    private fun import(
        context: Context,
        uri: Uri,
        tag: String,
        maxBytes: Long,
        extensionOf: (ByteArray) -> String
    ): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: return null
        if (bytes.isEmpty()) return null
        val target = File(
            assetDir(context),
            "${tag}_${System.currentTimeMillis()}.${extensionOf(bytes)}"
        )
        target.writeBytes(bytes)
        // 存相对路径：主题包换机/换目录后依然能对上。
        target.relativeToOrNull(HchatStorage.storageDir(context))?.path ?: target.absolutePath
    }.getOrNull()

    /** 把配置里存的相对路径解析成真实文件；不存在返回 null。 */
    fun resolve(context: Context, stored: String?): File? {
        if (stored.isNullOrBlank()) return null
        val file = if (File(stored).isAbsolute) File(stored) else File(HchatStorage.storageDir(context), stored)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun delete(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        // 只允许删素材目录里的文件，别让配置里的脏路径删到用户数据。
        if (!file.absolutePath.startsWith(assetDir(context).absolutePath)) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun exists(context: Context, path: String?): Boolean = resolve(context, path) != null

    // ---------------------------------------------------------------- 主题包

    /**
     * 导出的主题包放这里，方便用户自己拷走分享。
     */
    fun themeDir(context: Context): File {
        val dir = File(HchatStorage.storageDir(context), "themes")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }
}