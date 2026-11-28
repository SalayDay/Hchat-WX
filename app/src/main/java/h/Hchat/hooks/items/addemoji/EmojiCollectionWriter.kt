package h.Hchat.hooks.items.addemoji

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 经微信自己的 EmojiInfo 路径与存储接口收藏图片，不直接写 SQL。
 *
 * 8.0.76/80 的 provider createEmojiInfo 已经创建临时记录并通知；它不是纯构造器。
 * 因此最终字段和文件就绪后仍须调用带通知的 storage update，再 fresh 回读验收。
 * EmojiUpdatePanelEvent 的 int payload 必须为 1（custom），并在主线程发布。
 */
internal class EmojiCollectionWriter(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val hostContext: Context = context.hostContext()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var resolvedTargets: Targets? = null

    sealed interface Result {
        data class Added(val md5: String) : Result
        data class AlreadyExists(val md5: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun addImage(imagePath: String, activity: Activity?, showToast: Boolean = true) {
        submit(activity, showToast) { imagePath }
    }

    fun addImageLazy(activity: Activity?, showToast: Boolean = true, pathProvider: () -> String?) {
        submit(activity, showToast, pathProvider)
    }

    private fun submit(activity: Activity?, showToast: Boolean, pathProvider: () -> String?) {
        // 所有 writer 共用单队列：同 md5 的检查/补文件和 maxIndex+1 不得彼此竞争。
        saveExecutor.execute {
            val result = try {
                val path = stage("获取图片") { pathProvider() }
                if (path.isNullOrBlank()) {
                    Result.Failed("图片还没下载好，先打开看一下再长按")
                } else {
                    addImageSync(path)
                }
            } catch (error: Throwable) {
                val failure = error as? SaveFailure
                val cause = failure?.cause ?: error
                logger(
                    "保存表情失败 stage=${failure?.stage ?: "submit"} " +
                        "runtime=${resolvedTargets?.runtimeKey.orEmpty()} " +
                        "targets=${resolvedTargets?.diagnostic().orEmpty()}: ${readableCause(cause)}",
                    error
                )
                val prefix = if (failure?.stage == "刷新表情面板") "表情已保存，面板刷新" else failure?.stage ?: "保存表情"
                Result.Failed("${prefix}失败：${readableCause(cause).substringBefore("; snapshot=")}")
            }
            if (showToast) toast(activity, result.toMessage())
        }
    }

    private fun Result.toMessage(): String = when (this) {
        is Result.Added -> "已存为表情"
        is Result.AlreadyExists -> "该图片已在表情库"
        is Result.Failed -> reason
    }

    private fun addImageSync(imagePath: String): Result {
        val payload = stage("读取和预处理图片") {
            val source = File(imagePath)
            check(source.isFile && source.length() > 0L) { "图片文件不存在" }
            preparePayload(source.readBytes())
        }
        val md5 = stage("计算图片指纹") { md5Of(payload.bytes) }
        val expected = EmojiCollectionChecks.Expected(
            md5, if (isGif(payload.bytes)) TYPE_GIF else TYPE_STATIC,
            payload.bytes.size, payload.width, payload.height
        )
        val located = stage("定位表情库接口") { targets() }
        val storage = stage("获取当前表情库") { storageInstance(located) }
        val existing = stage("查询已有表情") { queryEmojiByMd5(located.infoClass, md5) }
        val before = existing?.let(::snapshot)
        val info = existing ?: stage("创建原生表情记录") {
            createEmojiInfo(expected, located.infoClass)
        }
        val order = stage("读取表情排序位") {
            before?.takeIf { it.catalog == EmojiCollectionChecks.CUSTOM_CATALOG }
                ?.order?.takeIf { it >= 0 }
                ?: run {
                    val max = (KavaReflector.invokeOrThrow(located.maxIndex, storage) as? Number)?.toInt()
                        ?: error("排序接口未返回整数: ${located.maxIndex.toGenericString()}")
                    check(max < Int.MAX_VALUE) { "自定义表情排序位已满" }
                    (max + 1).coerceAtLeast(0)
                }
        }
        stage("设置本地收藏字段") { applyCustomFields(info, expected, order) }
        // 路径仅来自当前 EmojiInfo。getAccPath 是账号根，不能当作 emoji 根来拼接。
        val target = stage("读取微信原生表情路径") { nativeFile(located, info, md5) }
        val originalFile = stage("检查已有表情文件") { inspectFile(target, snapshot(info)) }
        val plan = EmojiCollectionChecks.plan(before, expected, originalFile)
        val result = when (plan) {
            EmojiCollectionChecks.Plan.AlreadyPresent -> {
                // 即使行和文件已齐，也补发 storage notice；单发切 tab 事件不会标脏面板缓存。
                stage("刷新已有收藏记录并通知") { writeEmoji(located, storage, info) }
                stage("回读已有表情") {
                    verifyFresh(located, expected, target, order, allowExistingEncrypted = true)
                }
                Result.AlreadyExists(md5)
            }
            is EmojiCollectionChecks.Plan.Reject -> errorAt("检查已有表情文件", plan.reason)
            is EmojiCollectionChecks.Plan.Save -> {
                if (plan.writeMissingFile) {
                    stage("补写原生表情文件") {
                        // 只在官方文件确实缺失、将写入 raw 字节时清最低加密位。
                        // 保留其它 reserved4 位及 groupId/name/content 等既有元信息。
                        val flags = snapshot(info).reserved4 ?: error("缺少 field_reserved4")
                        setRequiredField(info, "field_reserved4", flags and 1.inv())
                        writeMissingFile(payload.bytes, target)
                    }
                }
                stage("写入最终表情记录并通知") { writeEmoji(located, storage, info) }
                stage("回读校验最终表情") {
                    verifyFresh(located, expected, target, order, allowExistingEncrypted = false)
                }
                Result.Added(md5)
            }
        }
        // AlreadyExists 也刷新：面板可能尚未消费上次的通知；修复缺失文件必走 Save。
        stage("刷新表情面板") { notifyPanel() }
        HLog.event("[Hchat:AddEmoji] stage=verified result=${result.javaClass.simpleName} " +
            "md5=$md5 bytes=${payload.bytes.size} runtime=${located.runtimeKey}")
        return result
    }

    private fun applyCustomFields(info: Any, expected: EmojiCollectionChecks.Expected, order: Int) {
        setRequiredField(info, "field_md5", expected.md5)
        setRequiredField(info, "field_catalog", EmojiCollectionChecks.CUSTOM_CATALOG)
        setRequiredField(info, "field_type", expected.type)
        setRequiredField(info, "field_size", expected.size)
        setRequiredField(info, "field_state", 0)
        setRequiredField(info, "field_temp", 0)
        setRequiredField(info, "field_width", expected.width)
        setRequiredField(info, "field_height", expected.height)
        setRequiredField(info, "field_reserved3", order)
    }

    private fun snapshot(info: Any): EmojiCollectionChecks.Snapshot = EmojiCollectionChecks.snapshot { name ->
        field(info.javaClass, name)?.let { KavaReflector.readField(it, info) }
    }

    private fun field(type: Class<*>, name: String): Field? =
        EmojiCollectionChecks.findInHierarchy<Class<*>, Field>(type, { it.superclass }) { current ->
            KavaReflector.declaredFields(current).firstOrNull { it.name == name }
        }

    private fun setRequiredField(info: Any, name: String, value: Any) {
        val target = field(info.javaClass, name) ?: error("微信表情缺少字段 $name")
        check(KavaReflector.writeField(target, info, value)) { "微信表情字段写入失败: $name" }
        check(KavaReflector.readField(target, info) == value) { "微信表情字段写后校验失败: $name" }
    }

    private fun writeEmoji(targets: Targets, storage: Any, info: Any) {
        // provider 新建的行也已经存在；与原生添加流程一样，在最终文件完成后 update + notify。
        val updated = KavaReflector.invokeOrThrow(targets.update, storage, info)
        if (updated == true) return
        check(updated == false) { "更新表情接口返回值异常: ${targets.update.toGenericString()}" }
        logger("表情最终 update 未命中，尝试原生 create(notify=true): ${targets.update.toGenericString()}", null)
        // 两版 create 的第二参均为 notify。其非 null 返回证明 native replace 成功，
        // 但仍不能据此报告 Added，调用方必须继续 fresh provider + 文件验收。
        val created = KavaReflector.invokeOrThrow(targets.create, storage, info, true)
        check(targets.infoClass.isInstance(created)) {
            "微信未保存最终表情记录: ${targets.create.toGenericString()}"
        }
    }

    private fun verifyFresh(
        targets: Targets,
        expected: EmojiCollectionChecks.Expected,
        writtenPath: File,
        order: Int,
        allowExistingEncrypted: Boolean
    ) {
        val fresh = queryEmojiByMd5(targets.infoClass, expected.md5)
            ?: error("回读表情库未找到记录")
        val current = snapshot(fresh)
        val actualPath = nativeFile(targets, fresh, expected.md5)
        check(actualPath == writtenPath) { "回读表情的微信原生路径发生变化" }
        val actualFile = inspectFile(actualPath, current)
        val problem = EmojiCollectionChecks.verificationError(
            current, expected, actualFile, allowExistingEncrypted, order
        )
        check(problem == null) { "$problem; snapshot=$current file=$actualFile" }
    }

    private fun nativeFile(targets: Targets, info: Any, md5: String): File {
        val path = KavaReflector.invokeOrThrow(targets.path, info) as? String
            ?: error("原生路径接口未返回字符串: ${targets.path.toGenericString()}")
        check(path.isNotBlank()) { "原生表情路径为空" }
        val native = File(path)
        check(native.isAbsolute && native.name == md5) { "原生表情路径不合法" }
        return native.canonicalFile.also {
            check(it.name == md5) { "原生表情路径指向了不同文件" }
        }
    }

    private fun inspectFile(file: File, info: EmojiCollectionChecks.Snapshot): EmojiCollectionChecks.FileSnapshot {
        val flags = info.reserved4 ?: error("缺少表情文件标记 field_reserved4")
        val encrypted = flags and 1 != 0
        val exists = file.exists()
        val regular = file.isFile
        return EmojiCollectionChecks.FileSnapshot(
            exists, regular, if (regular) file.length() else 0L,
            if (regular && !encrypted) md5Of(file) else null, encrypted
        )
    }

    private fun writeMissingFile(data: ByteArray, target: File) {
        val parent = target.parentFile ?: error("原生表情路径没有父目录")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建原生表情目录" }
        check(!target.exists()) { "原生表情文件已由其它流程创建，请重试" }
        // 仅写模块自建临时文件；move 不使用 REPLACE_EXISTING，绝不覆写其它流程的文件。
        val temporary = File.createTempFile(TEMP_PREFIX, ".tmp", parent).canonicalFile
        try {
            FileOutputStream(temporary).use { output ->
                output.write(data)
                output.fd.sync()
            }
            check(temporary.length() == data.size.toLong() && md5Of(temporary) == md5Of(data)) {
                "临时表情文件校验失败"
            }
            try {
                Files.move(temporary.toPath(), target.toPath())
            } catch (error: FileAlreadyExistsException) {
                throw IllegalStateException("原生表情文件已由其它流程创建，请重试", error)
            }
            check(target.isFile && target.length() == data.size.toLong() && md5Of(target) == md5Of(data)) {
                "原生表情文件写后校验失败"
            }
        } finally {
            // 删除范围仅限本方法创建、同父目录、固定私有前缀的临时文件；不删除目标原图。
            if (temporary.exists()) {
                check(temporary.parentFile == parent.canonicalFile && temporary.name.startsWith(TEMP_PREFIX)) {
                    "拒绝清理非模块临时文件"
                }
                if (!temporary.delete()) logger("表情临时文件清理失败: ${temporary.name}", null)
            }
        }
    }

    private fun notifyPanel() {
        val task = FutureTask<Unit> {
            check(Looper.myLooper() == Looper.getMainLooper()) { "表情面板事件必须在主线程发布" }
            val eventClass = KavaReflector.loadClass(EVENT_PANEL, context.hostClassLoader())
                ?: error("未找到 EmojiUpdatePanelEvent")
            val event = KavaReflector.newInstance(KavaReflector.findConstructor(eventClass))
                ?: error("创建 EmojiUpdatePanelEvent 失败")
            val candidates = KavaReflector.declaredFields(eventClass).filter {
                it.declaringClass == eventClass && !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive
            }.map { eventField ->
                EmojiCollectionChecks.PayloadCandidate(eventField,
                    KavaReflector.declaredFields(eventField.type).map {
                        EmojiCollectionChecks.PayloadField(it.name, it.type.name, Modifier.isStatic(it.modifiers))
                    })
            }
            val payloadField = EmojiCollectionChecks.customPanelPayload(candidates)
                ?: error("无法唯一确定面板事件的 int a payload")
            val payload = KavaReflector.readField(payloadField, event)
                ?: error("面板事件 payload 为空")
            setRequiredField(payload, "a", EmojiCollectionChecks.CUSTOM_PANEL_TYPE)
            val post = KavaReflector.findMethodRecursive(eventClass, "e")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            } ?: error("未找到 IEvent.e():boolean")
            val hasListener = KavaReflector.invokeOrThrow(post, event)
            check(hasListener is Boolean) { "IEvent.e() 返回值异常" }
            // false 仅表示当前没有面板监听器；storage 的最终通知已负责标脏，不能误报保存失败。
            HLog.event("[Hchat:AddEmoji] stage=panel_post type=1 hasListener=$hasListener")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            check(mainHandler.post(task)) { "主线程拒绝表情面板事件" }
        }
        try {
            task.get(PANEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            if (!task.isDone) task.cancel(false)
        }
    }

    private fun createEmojiInfo(expected: EmojiCollectionChecks.Expected, infoClass: Class<*>): Any {
        val args = Bundle().apply {
            putString("key_md5", expected.md5)
            putInt("key_group", EmojiCollectionChecks.CUSTOM_CATALOG)
            putInt("key_type", expected.type)
            putInt("key_size", expected.size)
        }
        return providerInfo("createEmojiInfo", args, infoClass)
            ?: error("微信未返回新建表情记录")
    }

    private fun queryEmojiByMd5(infoClass: Class<*>, md5: String): Any? =
        providerInfo("getEmojiByMd5", Bundle().apply { putString("key_md5", md5) }, infoClass)?.also {
            check(snapshot(it).md5 == md5) { "微信返回了不同指纹的表情记录" }
        }

    private fun providerInfo(method: String, args: Bundle, infoClass: Class<*>): Any? {
        val result = hostContext.contentResolver.call(
            Uri.parse("content://${hostContext.packageName}$EMOTION_PROVIDER/"), method, null, args
        ) ?: error("表情服务 $method 未返回结果")
        result.classLoader = infoClass.classLoader
        check(result.containsKey("key_emoji_info")) { "表情服务 $method 缺少 key_emoji_info" }
        @Suppress("DEPRECATION")
        val info = result.get("key_emoji_info") ?: return null
        check(infoClass.isInstance(info)) { "表情服务 $method 返回了错误对象: ${info.javaClass.name}" }
        return info
    }

    // ------------------------------------------------------------------ 定位与缓存

    private data class Targets(
        val runtimeKey: String,
        val loader: ClassLoader,
        val infoClass: Class<*>,
        val storageClass: Class<*>,
        val create: Method,
        val update: Method,
        val maxIndex: Method,
        val path: Method,
        val storageGetter: Method,
        val managerSingleton: Method
    ) {
        fun diagnostic(): String = listOf(update, create, path, storageGetter, managerSingleton)
            .joinToString { it.toGenericString() }
    }

    private fun targets(): Targets {
        val loader = context.hostClassLoader()
        // 每次用户提交读取当前 key，不能让实例缓存跨热更新继续使用旧 Method。
        val runtimeKey = DexMethodCache.runtimeKey(hostContext, loader)
        check(runtimeKey.isNotBlank()) { "微信运行时身份未就绪" }
        resolvedTargets?.takeIf { it.runtimeKey == runtimeKey && it.loader === loader }?.let { return it }
        resolvedTargets = null
        var resolved: Targets? = null
        // 此 API 只有 Runnable 版；所有 bridge 查询、缓存解析和存储入口定位都走串行门。
        DexInstallScheduler.runDexKitTask(Runnable {
            resolved = locateTargets(runtimeKey, loader)
        })
        return (resolved ?: error("表情接口定位任务已失效，请重试")).also { resolvedTargets = it }
        // 不保存 locateFailed；一次提交只扫描一轮，下次点击仍可补定位。
    }

    private fun locateTargets(runtimeKey: String, loader: ClassLoader): Targets {
        val prefs = DexMethodCache.prefs(hostContext, CACHE_PREFS)
        val insert = cachedMethod(prefs, runtimeKey, loader, CACHE_INSERT,
            valid = { isInsertCandidate(it) }, find = { dexFind(INSERT_ANCHOR) })
        val storageClass = insert.declaringClass
        val infoClass = insert.parameterTypes[0]
        val create = cachedMethod(prefs, runtimeKey, loader, CACHE_CREATE,
            valid = { isCreateCandidate(it, storageClass, infoClass) }, find = { dexFind(CREATE_ANCHOR) })
        val update = cachedMethod(prefs, runtimeKey, loader, CACHE_UPDATE,
            valid = { isUpdateCandidate(it, storageClass, infoClass) }, find = { dexFind(UPDATE_ANCHOR) })
        val maxIndex = cachedMethod(prefs, runtimeKey, loader, CACHE_MAX_INDEX,
            valid = { it.declaringClass == storageClass && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() && it.returnType == Integer.TYPE },
            find = { dexFind(MAX_INDEX_ANCHOR) })
        val path = cachedMethod(prefs, runtimeKey, loader, CACHE_PATH,
            valid = { it.declaringClass == infoClass && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() && it.returnType == String::class.java },
            find = { dexFindPath(infoClass) })
        val storageGetter = cachedMethod(prefs, runtimeKey, loader, CACHE_STORAGE_GETTER,
            valid = { it.returnType == storageClass && it.parameterTypes.isEmpty() &&
                it.declaringClass != storageClass && !Modifier.isStatic(it.modifiers) &&
                singletonMethod(it.declaringClass) != null },
            find = { dexFindByReturnType(storageClass.name) })
        val managerSingleton = cachedMethod(prefs, runtimeKey, loader, CACHE_MANAGER_SINGLETON,
            valid = { it.declaringClass == storageGetter.declaringClass && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() && it.returnType == storageGetter.declaringClass },
            find = { listOfNotNull(singletonMethod(storageGetter.declaringClass)) })
        return Targets(runtimeKey, loader, infoClass, storageClass, create, update, maxIndex,
            path, storageGetter, managerSingleton)
    }

    private fun cachedMethod(
        prefs: SharedPreferences,
        runtimeKey: String,
        loader: ClassLoader,
        key: String,
        valid: (Method) -> Boolean,
        find: () -> List<Method>
    ): Method {
        DexMethodCache.load(prefs, runtimeKey, loader, key)?.takeIf(valid)?.let { return it }
        DexMethodCache.clear(prefs, runtimeKey, key)
        val candidates = find().filter(valid).distinctBy { it.toGenericString() }
        check(candidates.size == 1) {
            "表情接口 $key 不能唯一定位(${candidates.size}): ${candidates.joinToString { it.toGenericString() }}"
        }
        return candidates.single().also { DexMethodCache.save(prefs, runtimeKey, key, it) }
    }

    private fun dexFind(anchor: String): List<Method> = dexMethods(
        MethodMatcher().apply { usingEqStrings(anchor) }
    )

    private fun dexFindByReturnType(returnType: String): List<Method> = dexMethods(
        MethodMatcher().apply { this.returnType(returnType); paramCount(0) }
    )

    private fun dexFindPath(infoClass: Class<*>): List<Method> =
        context.dexKitBridge().findMethod(FindMethod().apply {
            matcher(MethodMatcher().apply {
                declaredClass(infoClass.name)
                paramCount(0)
                returnType(String::class.java.name)
                usingEqStrings(*EmojiCollectionChecks.PATH_STRINGS.toTypedArray())
            })
        }).mapNotNull { data ->
            val method = data.getMethodInstance(context.hostClassLoader())
            val facts = EmojiCollectionChecks.PathMethod(
                method.declaringClass.name, method.parameterTypes.size, method.returnType.name,
                Modifier.isStatic(method.modifiers), data.usingStrings.toSet()
            )
            method.takeIf { EmojiCollectionChecks.isNativePathMethod(facts, infoClass.name) }
        }

    private fun dexMethods(methodMatcher: MethodMatcher): List<Method> =
        context.dexKitBridge().findMethod(FindMethod().apply { matcher(methodMatcher) }).map { data ->
            data.getMethodInstance(context.hostClassLoader())
        }.filter { !Modifier.isAbstract(it.modifiers) && !it.declaringClass.isInterface }

    private fun isInsertCandidate(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType &&
            method.parameterTypes.size == 1 && isEmojiInfoClass(method.parameterTypes[0])

    private fun isCreateCandidate(method: Method, storageClass: Class<*>, infoClass: Class<*>): Boolean =
        !Modifier.isStatic(method.modifiers) && method.declaringClass == storageClass &&
            method.parameterTypes.contentEquals(arrayOf(infoClass, Boolean::class.javaPrimitiveType)) &&
            method.returnType == infoClass

    private fun isUpdateCandidate(method: Method, storageClass: Class<*>, infoClass: Class<*>): Boolean =
        !Modifier.isStatic(method.modifiers) && method.declaringClass == storageClass &&
            method.parameterTypes.contentEquals(arrayOf(infoClass)) &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun isEmojiInfoClass(clazz: Class<*>): Boolean = clazz.name == EMOJI_INFO

    private fun singletonMethod(managerClass: Class<*>): Method? =
        KavaReflector.declaredMethods(managerClass).singleOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && it.returnType == managerClass
        }

    /** 每次保存重新从 manager 取当前账号的 storage，不缓存账号对象。 */
    private fun storageInstance(targets: Targets): Any {
        val manager = KavaReflector.invokeOrThrow(targets.managerSingleton, null)
            ?: error("微信表情管理器未就绪: ${targets.managerSingleton.toGenericString()}")
        return KavaReflector.invokeOrThrow(targets.storageGetter, manager).also {
            check(targets.storageClass.isInstance(it)) {
                "微信表情存储未就绪: ${targets.storageGetter.toGenericString()}"
            }
        } ?: error("微信表情存储为空")
    }

    // ------------------------------------------------------------------ 图片与错误处理

    private data class PreparedPayload(val bytes: ByteArray, val width: Int, val height: Int)

    private fun preparePayload(data: ByteArray): PreparedPayload {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, probe)
        check(probe.outWidth > 0 && probe.outHeight > 0) { "图片格式无法识别" }
        if (data.size <= MAX_BYTES && maxOf(probe.outWidth, probe.outHeight) <= MAX_SIDE) {
            return PreparedPayload(data, probe.outWidth, probe.outHeight)
        }
        check(!isGif(data)) { "GIF 超过 512 像素或 900KB，无法无损保存为表情" }
        var sample = 1
        while (maxOf(probe.outWidth / sample, probe.outHeight / sample) > MAX_SIDE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("解码图片失败")
        var scaled: Bitmap? = null
        try {
            val ratio = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
            var width = (decoded.width * ratio).toInt().coerceAtLeast(1)
            var height = (decoded.height * ratio).toInt().coerceAtLeast(1)
            repeat(8) {
                scaled?.takeIf { it !== decoded }?.recycle()
                val bitmap = Bitmap.createScaledBitmap(decoded, width, height, true)
                scaled = bitmap
                val bytes = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图片重编码失败" }
                    output.toByteArray()
                }
                if (bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
                    return PreparedPayload(bytes, width, height)
                }
                width = (width * 0.85f).toInt().coerceAtLeast(1)
                height = (height * 0.85f).toInt().coerceAtLeast(1)
            }
            error("图片缩放后仍超过 900KB")
        } finally {
            scaled?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }

    private fun md5Of(data: ByteArray): String = digestString(MessageDigest.getInstance("MD5").digest(data))

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digestString(digest.digest())
    }

    private fun digestString(digest: ByteArray): String = buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            if (value < 0x10) append('0')
            append(Integer.toHexString(value))
        }
    }

    private fun isGif(data: ByteArray): Boolean = data.size >= 6 &&
        String(data, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }

    private class SaveFailure(val stage: String, cause: Throwable) : RuntimeException(cause)

    private inline fun <T> stage(name: String, operation: () -> T): T = try {
        operation()
    } catch (error: SaveFailure) {
        throw error
    } catch (error: Throwable) {
        throw SaveFailure(name, error)
    }

    private fun errorAt(stage: String, message: String): Nothing =
        throw SaveFailure(stage, IllegalStateException(message))

    private fun readableCause(error: Throwable): String {
        var current = error
        while ((current is InvocationTargetException || current is java.util.concurrent.ExecutionException) &&
            current.cause != null
        ) current = current.cause!!
        return current.message?.takeIf { it.isNotBlank() } ?: current.javaClass.simpleName
    }

    private fun toast(activity: Activity?, message: String) {
        if (!mainHandler.post {
            try {
                val host = activity ?: h.Hchat.hooks.api.core.WeChatApis.currentActivity()?.currentActivity()
                if (host != null && !host.isFinishing && !host.isDestroyed) {
                    Toast.makeText(host, message, Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                logger("展示保存表情结果失败", error)
            }
        }) logger("保存表情结果提示未能投递到主线程", null)
    }

    private companion object {
        val saveExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Hchat-AddEmoji").apply { isDaemon = true }
        }
        const val TYPE_STATIC = 1
        const val TYPE_GIF = 2
        const val EMOJI_INFO = "com.tencent.mm.storage.emotion.EmojiInfo"
        const val EMOTION_PROVIDER = ".storage.provider.emotion"
        const val EVENT_PANEL = "com.tencent.mm.autogen.events.EmojiUpdatePanelEvent"
        const val TEMP_PREFIX = ".hchat-emoji-"
        const val PANEL_TIMEOUT_SECONDS = 10L

        // v3 同时升级缓存容器和 descriptor schema：旧 getAccPath/T0 路径策略不再复用。
        const val CACHE_PREFS = "Hchat_emoji_collection_method_cache_v3"
        const val CACHE_CREATE = "emoji_create_v3"
        const val CACHE_UPDATE = "emoji_update_v3"
        const val CACHE_INSERT = "emoji_insert_v3"
        const val CACHE_MAX_INDEX = "emoji_max_index_v3"
        const val CACHE_PATH = "emoji_native_path_v3"
        const val CACHE_STORAGE_GETTER = "emoji_storage_getter_v3"
        const val CACHE_MANAGER_SINGLETON = "emoji_manager_singleton_v3"
        const val CREATE_ANCHOR = "create_emoji_info_notify"
        const val UPDATE_ANCHOR = "event_update_emoji"
        const val INSERT_ANCHOR = "insert: %s, %s"
        const val MAX_INDEX_ANCHOR = "getCustomEmojiMaxIndex :%s"
        const val MAX_SIDE = 512
        const val MAX_BYTES = 900 * 1024
    }
}
