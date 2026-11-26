package h.Hchat.hooks.items.addemoji

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
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
    /** 已有本地路径时直接提交（内部起后台线程，可在主线程调用）。 */
    fun addImage(imagePath: String, activity: Activity?, showToast: Boolean = true) {
        submit(activity, showToast) { imagePath }
    }

    /** 路径需要现算时用这个（朋友圈图片可能要现下载），避免调用方再套一层线程。 */
    fun addImageLazy(activity: Activity?, showToast: Boolean = true, pathProvider: () -> String?) {
        submit(activity, showToast, pathProvider)
    }

    private fun submit(activity: Activity?, showToast: Boolean, pathProvider: () -> String?) {
        Thread({
            val path = runCatching { pathProvider() }.getOrNull()
            val result = if (path.isNullOrBlank()) {
                Result.Failed("图片还没下载好，先打开看一下再长按")
            } else {
                runCatching { addImageSync(path) }.getOrElse { error ->
                    logger("保存表情失败: ${error.message}", error)
                    Result.Failed(error.message ?: "保存表情失败")
                }
            }
            if (showToast) toast(activity, result.toMessage())
        }, "Hchat-AddEmoji").start()
    }

    private fun Result.toMessage(): String = when (this) {
        is Result.Added -> "已存为表情"
        is Result.AlreadyExists -> "该图片已在表情库"
        is Result.Failed -> reason
    }

    private fun addImageSync(imagePath: String): Result {
        val source = File(imagePath)
        if (!source.isFile || source.length() <= 0L) return Result.Failed("图片文件不存在")
        val data = runCatching { source.readBytes() }.getOrElse { return Result.Failed("读取图片失败") }
        // 必须先缩放/重编码，再按**最终字节**算 md5：表情文件名就是 md5，
        // 若先算原图 md5 再写入缩过的内容，文件名和内容就对不上了。
        val payload = preparePayload(data)
        val md5 = md5Of(payload.bytes) ?: return Result.Failed("计算图片指纹失败")
        val located = targets() ?: return Result.Failed("表情库接口未就绪")

        // 已经在库里（catalog=81 即自定义表情）就不用重复插入。
        // md5 是 EmojiInfo 表的主键，同一张图不可能存在两行。已经存在时必须**复用那一行**
        // 再改 catalog —— 微信自己的「添加表情」也是这么做的（按 md5 重新取 EmojiInfo 后
        // 把 catalog 改成 0x51），否则插入会撞主键。
        val existing = queryEmojiByMd5(located.infoClass, md5)
        if (existing != null && catalogOf(existing) == CUSTOM_CATALOG) {
            return Result.AlreadyExists(md5)
        }

        val basePath = accPath() ?: return Result.Failed("获取表情目录失败")
        val type = if (isGif(payload.bytes)) TYPE_GIF else TYPE_STATIC
        val emojiInfo = existing
            ?: createEmojiInfo(md5, CUSTOM_CATALOG, type, payload.bytes.size, located.infoClass)
            ?: return Result.Failed("创建表情信息失败")

        val targetPath = basePath + md5
        if (!writeFile(payload.bytes, File(targetPath))) return Result.Failed("写入表情文件失败")

        val maxIndex = located.maxIndex?.let {
            (KavaReflector.invoke(it, located.storage) as? Number)?.toInt()
        } ?: 0

        applyCustomFields(
            emojiInfo, md5, type, payload.bytes.size, payload.width, payload.height, maxIndex + 1
        )

        val inserted = writeEmoji(located, emojiInfo, existing != null)
        if (!inserted) {
            // 复用已有行时不能删文件：那可能是别的表情/消息正在引用的同一份内容。
            if (existing == null) File(targetPath).delete()
            return Result.Failed("写入表情库失败")
        }

        // 落库后用微信自己的路径函数校正一次（groupId 非空时目录会不同）。
        val officialPath = KavaReflector.invokeMethod(emojiInfo, "T0")?.toString().orEmpty()
        if (officialPath.isNotBlank() && officialPath != targetPath) {
            runCatching {
                val official = File(officialPath)
                if (!official.isFile) {
                    official.parentFile?.mkdirs()
                    official.writeBytes(payload.bytes)
                }
            }
        }
        notifyPanel()
        return Result.Added(md5)
    }

    /**
     * 表情文件有尺寸和体积上限，微信自己添加超限时会直接拒绝（日志 `over max size`）。
     *
     * 过大或过肥的图片等比缩到 [MAX_SIDE] 并重新编码成 PNG；GIF 不重编码（会丢动画），
     * 只保留原始字节。
     */
    private fun preparePayload(data: ByteArray): PreparedPayload {
        val size = bounds(data)
        val width = size?.first ?: DEFAULT_SIDE
        val height = size?.second ?: DEFAULT_SIDE
        if (isGif(data)) return PreparedPayload(data, width, height)
        if (data.size <= MAX_BYTES && maxOf(width, height) <= MAX_SIDE) {
            return PreparedPayload(data, width, height)
        }
        return downscale(data) ?: PreparedPayload(data, width, height)
    }

    private fun downscale(data: ByteArray): PreparedPayload? = runCatching {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return@runCatching null
        var sample = 1
        while (probe.outWidth / (sample * 2) >= MAX_SIDE &&
            probe.outHeight / (sample * 2) >= MAX_SIDE
        ) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@runCatching null
        val longest = maxOf(decoded.width, decoded.height)
        val bitmap = if (longest > MAX_SIDE) {
            val ratio = MAX_SIDE.toFloat() / longest
            val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
            val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true)
        } else {
            decoded
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        PreparedPayload(out.toByteArray(), bitmap.width, bitmap.height)
    }.getOrNull()

    private data class PreparedPayload(val bytes: ByteArray, val width: Int, val height: Int)

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

    /**
     * 落库。
     *
     * 已存在的行必须走 update，否则插入会撞 md5 主键；新行优先 create
     * （自带 `create_emoji_info_notify`，与微信自己添加表情的路径一致）。
     */
    private fun writeEmoji(targets: Targets, emojiInfo: Any, existing: Boolean): Boolean {
        if (existing) {
            targets.update?.let { method ->
                if (KavaReflector.invoke(method, targets.storage, emojiInfo) as? Boolean == true) {
                    return true
                }
            }
        }
        targets.create?.let { method ->
            if (KavaReflector.invoke(method, targets.storage, emojiInfo, false) != null) return true
        }
        targets.insert?.let { method ->
            if (KavaReflector.invoke(method, targets.storage, emojiInfo) as? Boolean == true) return true
        }
        return false
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

    private fun catalogOf(emojiInfo: Any): Int? {
        val field = KavaReflector.declaredFields(emojiInfo.javaClass)
            .firstOrNull { it.name == "field_catalog" } ?: return null
        return (KavaReflector.readField(field, emojiInfo) as? Number)?.toInt()
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
        /** `createEmojiInfo(emoji, temp)`：新增，自带 `create_emoji_info_notify`。 */
        val create: Method?,
        /** `updateEmojiInfo(emoji)`：更新已有行，带 `event_update_emoji` 通知。 */
        val update: Method?,
        /** 纯 `insert(emoji)`：create 不可用时的兜底。 */
        val insert: Method?,
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
                logger("表情库接口定位失败，图片保存为表情不可用", null)
                return null
            }
            targets = resolved
            return resolved
        }
    }

    /**
     * 定位表情库写入接口。
     *
     * 三个方法签名几乎一样（都是 `(EmojiInfo) → boolean` 或 `(EmojiInfo, boolean) → EmojiInfo`），
     * 只按签名筛会选错 —— 8.0.80 上带 `insert assertion!, invalid emojiInfo` 的方法有三份
     * （update / updateNoNotify / insert）。所以统一按**方法体内语义字符串**锚定：
     *
     * - create：`create_emoji_info_notify`
     * - update：`event_update_emoji`
     * - insert：`insert: %s, %s`
     */
    private fun locateTargets(): Targets? {
        val prefs = DexMethodCache.prefs(hostContext, CACHE_PREFS)
        val cacheKey = DexMethodCache.runtimeKey(hostContext, context.hostClassLoader())
        val loader = context.hostClassLoader()

        // 先定「存储类」：`insert: %s, %s` 只在 EmojiInfoStorage 里出现。
        // 同一批语义串在别的类里也有（8.0.76 的 ui/g0、l32/v 同样含 create_emoji_info_notify），
        // 所以后面每个候选都必须和这个类一致，否则会挂到别的类的同名方法上。
        val insert = DexMethodCache.load(prefs, cacheKey, loader, CACHE_INSERT)
            ?.takeIf { isInsertCandidate(it, null) }
            ?: dexFind(INSERT_ANCHOR)
                .firstOrNull { isInsertCandidate(it, null) }
                ?.also { DexMethodCache.save(prefs, cacheKey, CACHE_INSERT, it) }

        val create = DexMethodCache.load(prefs, cacheKey, loader, CACHE_CREATE)
            ?.takeIf { isCreateCandidate(it, insert?.declaringClass) }
            ?: dexFind(CREATE_ANCHOR)
                .firstOrNull { isCreateCandidate(it, insert?.declaringClass) }
                ?.also { DexMethodCache.save(prefs, cacheKey, CACHE_CREATE, it) }

        val storageClass = insert?.declaringClass ?: create?.declaringClass ?: return null
        val infoClass = insert?.parameterTypes?.firstOrNull()
            ?: create?.parameterTypes?.firstOrNull()
            ?: return null

        val update = DexMethodCache.load(prefs, cacheKey, loader, CACHE_UPDATE)
            ?.takeIf { isUpdateCandidate(it, storageClass, infoClass) }
            ?: dexFind(UPDATE_ANCHOR)
                .firstOrNull { isUpdateCandidate(it, storageClass, infoClass) }
                ?.also { DexMethodCache.save(prefs, cacheKey, CACHE_UPDATE, it) }

        val maxIndex = DexMethodCache.load(prefs, cacheKey, loader, CACHE_MAX_INDEX)
            ?.takeIf { isMaxIndexCandidate(it, storageClass) }
            ?: dexFind(MAX_INDEX_ANCHOR)
                .firstOrNull { isMaxIndexCandidate(it, storageClass) }
                ?.also { DexMethodCache.save(prefs, cacheKey, CACHE_MAX_INDEX, it) }

        val storage = storageInstance(storageClass, loader) ?: return null
        return Targets(infoClass, storageClass, storage, create, update, insert, maxIndex)
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

    private fun isCreateCandidate(method: Method, storageClass: Class<*>?): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            (storageClass == null || method.declaringClass.name == storageClass.name) &&
            types.size == 2 &&
            isEmojiInfoClass(types[0]) &&
            types[1] == Boolean::class.javaPrimitiveType &&
            isEmojiInfoClass(method.returnType)
    }

    private fun isInsertCandidate(method: Method, storageClass: Class<*>?): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            types.size == 1 &&
            isEmojiInfoClass(types[0]) &&
            (storageClass == null || method.declaringClass.name == storageClass.name)
    }

    private fun isUpdateCandidate(
        method: Method,
        storageClass: Class<*>,
        infoClass: Class<*>
    ): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            method.declaringClass.name == storageClass.name &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            types.size == 1 &&
            types[0] == infoClass
    }

    private fun isMaxIndexCandidate(method: Method, storageClass: Class<*>): Boolean {
        return !Modifier.isStatic(method.modifiers) &&
            method.declaringClass.name == storageClass.name &&
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
        const val CACHE_CREATE = "emoji_create_v2"
        const val CACHE_UPDATE = "emoji_update_v2"
        const val CACHE_INSERT = "emoji_insert_v2"
        const val CACHE_MAX_INDEX = "emoji_max_index_v2"

        /**
         * 新增表情（insert + 观察者通知），全 dex 唯一。
         *
         * 不能用 `insert assertion!, invalid emojiInfo` 去锚 insert —— 8.0.80 上有三份方法
         * 带这个断言（update / updateNoNotify / insert），只按签名筛会选错。
         */
        const val CREATE_ANCHOR = "create_emoji_info_notify"

        /** 更新已有表情行，带 event_update_emoji 通知，全 dex 唯一。 */
        const val UPDATE_ANCHOR = "event_update_emoji"

        /** 纯 insert 的日志串，用来和 update 系列区分。 */
        const val INSERT_ANCHOR = "insert: %s, %s"

        /** getCustomEmojiMaxIndex 的日志串，全 dex 唯一。 */
        const val MAX_INDEX_ANCHOR = "getCustomEmojiMaxIndex :%s"

        /** 超过这个边长或体积就等比缩小重编码，避免被微信判为超限表情。 */
        const val MAX_SIDE = 512
        const val MAX_BYTES = 900 * 1024
    }
}
