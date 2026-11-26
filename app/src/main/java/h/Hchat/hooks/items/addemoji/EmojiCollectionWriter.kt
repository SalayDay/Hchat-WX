package h.Hchat.hooks.items.addemoji

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.media.WeChatInternalServices
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest

/**
 * 把本地图片写进微信「自定义表情」库。
 *
 * ## 为什么按微信自己的写法来
 *
 * 微信添加自定义表情的真实路径（8.0.76 反编译核对）：
 *
 * ```
 * EmojiAddCustomDialogUI.save
 *   → field_catalog = 0x51 (81)            // 自定义表情的 catalog
 *   → field_reserved3 = EmojiInfoStorage.getCustomEmojiMaxIndex() + 1   // 面板排序位
 *   → EmojiInfoStorage.saveEmojiInfo(emoji, false) / insert(emoji)      // 落库
 *   → EmojiUpdatePanelEvent                 // 通知表情面板刷新
 * ```
 *
 * 所以这里完全照抄该序列：catalog 用 81、排序位取当前最大值 +1、落库走
 * `EmojiInfoStorage` 自己的方法、再补一次面板事件。**不自己拼 SQL 写 EmojiInfo 表**，
 * 否则会漏掉微信对这些列的约束和观察者通知。
 *
 * 混淆类名/方法名逐版本变化，因此插入方法、排序方法、存储实例都按**方法体内的
 * 字符串常量**在 DexKit 里定位并缓存，不写死 `ga5.l` 之类的短名。
 *
 * ## 文件位置
 *
 * 表情文件就是「表情根目录 + md5」（微信 `EmojiInfo.getPath()` 在 groupId 为空时
 * 也是这么拼），落库后再用一次 `getPath()` 校正，路径不一致就补一份。
 */
internal class EmojiCollectionWriter(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val hostContext: Context = context.hostContext()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var targets: Targets? = null
    @Volatile private var locateFailed = false

    /** 结果：成功 / 已存在 / 失败（带可读原因）。 */
    sealed interface Result {
        data class Added(val md5: String) : Result
        data class AlreadyExists(val md5: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * 后台线程处理图片；结束后在主线程 toast（`showToast=false` 时静默）。
     *
     * 必须在后台调用：要读图片文件、算 md5、写表情目录、再落库，都是 IO。
     */
    fun addImage(imagePath: String, activity: Activity?, showToast: Boolean = true) {
        Thread({
            val result = runCatching { addImageSync(imagePath) }.getOrElse { error ->
                logger("添加表情失败: ${error.message}", error)
                Result.Failed(error.message ?: "添加表情失败")
            }
            if (showToast) {
                val message = when (result) {
                    is Result.Added -> "已添加到表情"
                    is Result.AlreadyExists -> "该图片已在表情库"
                    is Result.Failed -> result.reason
                }
                toast(activity, message)
            }
        }, "Hchat-AddEmoji").start()
    }

    private fun addImageSync(imagePath: String): Result {
        val source = File(imagePath)
        if (!source.isFile || source.length() <= 0L) return Result.Failed("图片文件不存在")
        val data = runCatching { source.readBytes() }.getOrElse { return Result.Failed("读取图片失败") }
        val md5 = md5Of(data) ?: return Result.Failed("计算图片指纹失败")
        val located = targets() ?: return Result.Failed("表情库接口未就绪")

        // 已经在库里（catalog=81 即自定义表情）就不用重复插入。
        existingCatalog(located.infoClass, md5)?.let { catalog ->
            if (catalog == CUSTOM_CATALOG) return Result.AlreadyExists(md5)
        }

        val basePath = accPath() ?: return Result.Failed("获取表情目录失败")
        val type = if (isGif(data)) TYPE_GIF else TYPE_STATIC
        val emojiInfo = createEmojiInfo(md5, CUSTOM_CATALOG, type, data.size, located.infoClass)
            ?: return Result.Failed("创建表情信息失败")

        val targetPath = basePath + md5
        if (!writeFile(data, File(targetPath))) return Result.Failed("写入表情文件失败")

        val width = imageWidth(data)
        val height = imageHeight(data)
        val maxIndex = located.maxIndex?.let {
            (KavaReflector.invoke(it, located.storage) as? Number)?.toInt()
        } ?: 0

        applyCustomFields(emojiInfo, md5, type, data.size, width, height, maxIndex + 1)

        val inserted = insertEmoji(located, emojiInfo)
        if (!inserted) {
            File(targetPath).delete()
            return Result.Failed("写入表情库失败")
        }

        // 落库后用微信自己的路径函数校正一次（groupId 非空时目录会不同）。
        val officialPath = KavaReflector.invokeMethod(emojiInfo, "T0")?.toString().orEmpty()
        if (officialPath.isNotBlank() && officialPath != targetPath) {
            runCatching {
                val official = File(officialPath)
                if (!official.isFile) {
                    official.parentFile?.mkdirs()
                    official.writeBytes(data)
                }
            }
        }
        notifyPanel()
        return Result.Added(md5)
    }

    private fun applyCustomFields(
        emojiInfo: Any,
        md5: String,
        type: Int,
        size: Int,
        width: Int,
        height: Int,
        order: Int
    ) {
        setField(emojiInfo, "field_md5", md5)
        setField(emojiInfo, "field_catalog", CUSTOM_CATALOG)
        setField(emojiInfo, "field_type", type)
        setField(emojiInfo, "field_size", size)
        setField(emojiInfo, "field_state", 0)
        setField(emojiInfo, "field_start", 0)
        setField(emojiInfo, "field_temp", 0)
        setField(emojiInfo, "field_needupload", 0)
        setField(emojiInfo, "field_groupId", "")
        setField(emojiInfo, "field_name", "")
        setField(emojiInfo, "field_content", "")
        setField(emojiInfo, "field_width", width)
        setField(emojiInfo, "field_height", height)
        setField(emojiInfo, "field_reserved3", order)
    }

    private fun insertEmoji(targets: Targets, emojiInfo: Any): Boolean {
        // 优先 saveEmojiInfo(emoji, false)：它自带 "create_emoji_info_notify"，
        // 落库的同时通知观察者，比裸 insert 更接近微信自己的行为。
        targets.save?.let { save ->
            val saved = KavaReflector.invoke(save, targets.storage, emojiInfo, false)
            if (saved != null) return true
        }
        val inserted = KavaReflector.invoke(targets.insert, targets.storage, emojiInfo)
        return inserted as? Boolean == true
    }

    /** 表情面板刷新事件；拿不到就跳过（下次打开表情面板仍会读库）。 */
    private fun notifyPanel() {
        runCatching {
            val loader = context.hostClassLoader()
            val eventClass = KavaReflector.loadClass(EVENT_PANEL, loader) ?: return
            val event = KavaReflector.newInstance(KavaReflector.findConstructor(eventClass))
                ?: return
            // IEvent.post() 在 8.x 里被混淆成 e()；事件类自己可能不声明，需要顺接口找。
            val post = findPostMethod(eventClass) ?: return
            KavaReflector.invoke(post, event)
        }.onFailure { logger("通知表情面板刷新失败", it) }
    }

    private fun findPostMethod(eventClass: Class<*>): Method? {
        val visited = HashSet<Class<*>>()
        var current: Class<*>? = eventClass
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { candidate ->
                candidate.name == "e" && candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == Boolean::class.javaPrimitiveType
            }?.let { return it }
            for (itf in current.interfaces) {
                if (!visited.add(itf)) continue
                itf.declaredMethods.firstOrNull { candidate ->
                    candidate.name == "e" && candidate.parameterTypes.isEmpty() &&
                        candidate.returnType == Boolean::class.javaPrimitiveType
                }?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun existingCatalog(infoClass: Class<*>, md5: String): Int? {
        val existing = queryEmojiByMd5(infoClass, md5) ?: return null
        val field = KavaReflector.declaredFields(existing.javaClass)
            .firstOrNull { it.name == "field_catalog" } ?: return null
        return (KavaReflector.readField(field, existing) as? Number)?.toInt()
    }

    private fun createEmojiInfo(
        md5: String,
        catalog: Int,
        type: Int,
        size: Int,
        infoClass: Class<*>
    ): Any? {
        val args = Bundle().apply {
            putString("key_md5", md5)
            putInt("key_group", catalog)
            putInt("key_type", type)
            putInt("key_size", size)
        }
        val result = providerCall("createEmojiInfo", args) ?: return null
        runCatching { result.classLoader = infoClass.classLoader }
        @Suppress("DEPRECATION")
        return runCatching { result.get("key_emoji_info") }.getOrNull()
    }

    private fun queryEmojiByMd5(infoClass: Class<*>, md5: String): Any? {
        val args = Bundle().apply { putString("key_md5", md5) }
        val result = providerCall("getEmojiByMd5", args) ?: return null
        runCatching { result.classLoader = infoClass.classLoader }
        @Suppress("DEPRECATION")
        return runCatching { result.get("key_emoji_info") }.getOrNull()
    }

    private fun accPath(): String? {
        val result = providerCall("getAccPath", Bundle()) ?: return null
        val path = result.getString("path").orEmpty()
        if (path.isBlank()) return null
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun providerCall(method: String, args: Bundle): Bundle? = runCatching {
        hostContext.contentResolver.call(emotionUri(), method, null, args)
    }.onFailure {
        logger("表情服务调用失败: $method", it)
    }.getOrNull()

    private fun emotionUri(): Uri =
        Uri.parse("content://" + hostContext.packageName + EMOTION_PROVIDER + "/")

    // ------------------------------------------------------------------ 定位

    private data class Targets(
        val infoClass: Class<*>,
        val storageClass: Class<*>,
        val storage: Any,
        val insert: Method,
        val save: Method?,
        val maxIndex: Method?
    )

    private fun targets(): Targets? {
        targets?.let { return it }
        if (locateFailed) return null
        synchronized(this) {
            targets?.let { return it }
            if (locateFailed) return null
            val resolved = runCatching { locateTargets() }.getOrElse { error ->
                logger("定位表情库接口失败: ${error.message}", error)
                null
            }
            if (resolved == null) {
                locateFailed = true
                logger("表情库接口定位失败，图片添加到表情不可用", null)
                return null
            }
            targets = resolved
            return resolved
        }
    }

    private fun locateTargets(): Targets? {
        val prefs = DexMethodCache.prefs(hostContext, CACHE_PREFS)
        val cacheKey = DexMethodCache.runtimeKey(hostContext, context.hostClassLoader())
        val loader = context.hostClassLoader()

        val insert = DexMethodCache.load(prefs, cacheKey, loader, CACHE_INSERT)
            ?.takeIf(::isInsertCandidate)
            ?: dexFind(INSERT_ANCHOR)
                .firstOrNull(::isInsertCandidate)
                ?.also { DexMethodCache.save(prefs, cacheKey, CACHE_INSERT, it) }
            ?: return null

        val storageClass = insert.declaringClass
        val infoClass = insert.parameterTypes.first()

        val save = DexMethodCache.load(prefs, cacheKey, loader, CACHE_SAVE)
            ?.takeIf { isSaveCandidate(it, storageClass, infoClass) }
            ?: dexFind(SAVE_ANCHOR)
                .firstOrNull { isSaveCandidate(it, storageClass, infoClass) }
                ?.also { DexMethodCache.save(prefs, cacheKey, CACHE_SAVE, it) }

        val maxIndex = DexMethodCache.load(prefs, cacheKey, loader, CACHE_MAX_INDEX)
            ?.takeIf { isMaxIndexCandidate(it, storageClass) }
            ?: dexFind(MAX_INDEX_ANCHOR)
                .firstOrNull { isMaxIndexCandidate(it, storageClass) }
                ?.also { DexMethodCache.save(prefs, cacheKey, CACHE_MAX_INDEX, it) }

        val storage = storageInstance(storageClass, loader) ?: return null
        return Targets(infoClass, storageClass, storage, insert, save, maxIndex)
    }

    private fun dexFind(anchor: String): List<Method> = runCatching {
        context.dexKitBridge().findMethod(
            FindMethod().apply {
                matcher(MethodMatcher().apply { usingEqStrings(anchor) })
            }
        ).mapNotNull { data ->
            runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
        }.filter { method ->
            !Modifier.isAbstract(method.modifiers) && !method.declaringClass.isInterface
        }
    }.getOrElse {
        logger("DexKit 定位表情库失败: $anchor", it)
        emptyList()
    }

    private fun dexFindByReturnType(returnType: String): List<Method> = runCatching {
        context.dexKitBridge().findMethod(
            FindMethod().apply {
                matcher(
                    MethodMatcher().apply {
                        this.returnType(returnType)
                        paramCount(0)
                    }
                )
            }
        ).mapNotNull { data ->
            runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
        }
    }.getOrElse {
        logger("DexKit 定位表情存储入口失败: $returnType", it)
        emptyList()
    }

    private fun isInsertCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            (method.returnType == Boolean::class.javaPrimitiveType) &&
            types.size == 1 &&
            isEmojiInfoClass(types[0])
    }

    private fun isSaveCandidate(method: Method, storageClass: Class<*>, infoClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            method.declaringClass == storageClass &&
            types.size == 2 &&
            types[0] == infoClass &&
            types[1] == Boolean::class.javaPrimitiveType
    }

    private fun isMaxIndexCandidate(method: Method, storageClass: Class<*>): Boolean {
        return !Modifier.isStatic(method.modifiers) &&
            method.declaringClass == storageClass &&
            method.parameterTypes.isEmpty() &&
            method.returnType == Integer.TYPE
    }

    private fun isEmojiInfoClass(clazz: Class<*>): Boolean =
        clazz.name.endsWith("storage.emotion.EmojiInfo")

    /**
     * 取存储实例。
     *
     * 微信通过 `StorageManager.getEmojiStorage()` 拿它，但两个类名都是混淆的，
     * 所以按结构找：「无参、返回存储类」的方法就是那个 getter，它所在的类即
     * StorageManager；再在该类里找「静态、无参、返回自身」的实例方法。
     */
    private fun storageInstance(storageClass: Class<*>, loader: ClassLoader): Any? {
        val getter = dexFindByReturnType(storageClass.name).firstOrNull {
            it.returnType == storageClass && it.declaringClass != storageClass &&
                !Modifier.isStatic(it.modifiers)
        }
        if (getter != null) {
            val managerClass = getter.declaringClass
            val singleton = managerClass.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType == managerClass
            }
            val manager = singleton?.let { KavaReflector.invoke(it, null) }
            if (manager != null) {
                val storage = KavaReflector.invoke(getter, manager)
                if (storageClass.isInstance(storage)) return storage
            }
        }
        // 兜底：按插件服务定位器找（部分版本把 Storage 也注册成服务）。
        return WeChatInternalServices.getService(context.dexFinder(), storageClass)
    }

    // ------------------------------------------------------------------ 工具

    private fun setField(target: Any, name: String, value: Any?) {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.set(target, value)
                }.onFailure { logger("写入表情字段失败: $name", it) }
                return
            }
            current = current.superclass
        }
    }

    private fun md5Of(data: ByteArray): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                if (value < 0x10) append('0')
                append(Integer.toHexString(value))
            }
        }
    }.getOrNull()

    private fun isGif(data: ByteArray): Boolean =
        data.size >= 6 && String(data, 0, 6, Charsets.US_ASCII).let {
            it == "GIF87a" || it == "GIF89a"
        }

    private fun imageWidth(data: ByteArray): Int = bounds(data)?.first ?: DEFAULT_SIDE

    private fun imageHeight(data: ByteArray): Int = bounds(data)?.second ?: DEFAULT_SIDE

    private fun bounds(data: ByteArray): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width > 0 && height > 0) width to height else null
    }.getOrNull()

    private fun writeFile(data: ByteArray, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        FileOutputStream(target, false).use { it.write(data) }
        target.isFile && target.length() == data.size.toLong()
    }.getOrElse {
        runCatching { target.delete() }
        false
    }

    private fun toast(activity: Activity?, message: String) {
        mainHandler.post {
            val host = activity ?: runCatching {
                h.Hchat.hooks.api.core.WeChatApis.currentActivity()?.currentActivity()
            }.getOrNull()
            if (host != null && !host.isFinishing && !host.isDestroyed) {
                Toast.makeText(host, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        /** 自定义表情的 catalog，与微信 `EmojiAddCustomDialogUI.save` 里的 0x51 一致。 */
        const val CUSTOM_CATALOG = 0x51
        const val TYPE_STATIC = 1
        const val TYPE_GIF = 2
        const val DEFAULT_SIDE = 320
        const val EMOTION_PROVIDER = ".storage.provider.emotion"
        const val EVENT_PANEL = "com.tencent.mm.autogen.events.EmojiUpdatePanelEvent"

        const val CACHE_PREFS = "Hchat_emoji_collection_method_cache"
        const val CACHE_INSERT = "emoji_insert_v1"
        const val CACHE_SAVE = "emoji_save_v1"
        const val CACHE_MAX_INDEX = "emoji_max_index_v1"

        /** EmojiInfoStorage.insert 里的断言串，全 dex 唯一。 */
        const val INSERT_ANCHOR = "insert assertion!, invalid emojiInfo"

        /** EmojiInfoStorage.saveEmojiInfo 里的通知串，全 dex 唯一。 */
        const val SAVE_ANCHOR = "create_emoji_info_notify"

        /** EmojiInfoStorage.getCustomEmojiMaxIndex 的日志串，全 dex 唯一。 */
        const val MAX_INDEX_ANCHOR = "getCustomEmojiMaxIndex :%s"
    }
}