package h.Hchat.hooks.api.message

import android.content.Context
import android.text.TextUtils
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import io.github.libxposed.api.XposedInterface
import h.Hchat.dexkit.DexFinder
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.api.media.WeChatInternalServices
import h.Hchat.utils.HLog
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 微信本地消息 API。
 *
 * 系统提示优先走微信自身的 PocketMoneyNewXMLListener 插入链路，避免直接写 message 表。
 */
class WeChatLocalMessageApi @JvmOverloads constructor(
    private val dexFinder: DexFinder?,
    private val logger: Logger?,
    hostContext: Context? = null
) {
    private val insertTimeScope = LocalMessageTimeScope()
    private val nativeInsertScope = SystemMessageInsertScope()
    private val noticeProtection = hostContext?.let { LocalNoticeProtectionStore.get(it) }
    @Volatile private var insertCaptureInstalled = false
    @Volatile private var luckyMoneyNoticeHooksInstalled = false
    private val luckyMoneyNoticeMethods = HashSet<Method>()
    @Volatile
    private var createTimeHookInstalled = false

    fun interface Logger {
        fun log(message: String)
    }

    fun isAvailable(): Boolean {
        return dexFinder?.hasLocalMessageApi() == true
    }

    fun ensureReady() {
        val finder = dexFinder ?: return
        if (!finder.hasLocalMessageApi() || finder.localMessageCreateTimeMethod == null ||
            finder.localSystemMessageStorageInsertMethod == null) {
            DexInstallScheduler.runDexKitTask { finder.resolveLocalMessageApi() }
        }
        installCreateTimeHook()
        installInsertCaptureHook()
    }

    fun installCreateTimeHook(): Boolean {
        if (createTimeHookInstalled) return true
        val finder = dexFinder ?: return false
        if (finder.localMessageCreateTimeMethod == null) {
            DexInstallScheduler.runDexKitTask { finder.resolveLocalMessageApi() }
        }
        val method = finder.localMessageCreateTimeMethod ?: return false
        synchronized(this) {
            if (createTimeHookInstalled) return true
            HookRegistry.get().hook(method, CreateTimeHooker(this))
            createTimeHookInstalled = true
        }
        return true
    }

    private class CreateTimeHooker(private val owner: WeChatLocalMessageApi) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val talker = chain.args.getOrNull(0) as? String ?: return chain.proceed()
            return owner.insertTimeScope.createTimeFor(talker) ?: chain.proceed()
        }
    }

    private fun installInsertCaptureHook(): Boolean {
        if (insertCaptureInstalled) return true
        val finder = dexFinder ?: return false
        if (finder.localSystemMessageStorageInsertMethod == null) {
            DexInstallScheduler.runDexKitTask { finder.resolveLocalMessageApi() }
        }
        val method = finder.localSystemMessageStorageInsertMethod ?: return false
        synchronized(this) {
            if (insertCaptureInstalled) return true
            HookRegistry.get().hook(method, SystemInsertCaptureHooker(this))
            insertCaptureInstalled = true
        }
        return true
    }

    /** 由公共 API 的有限补装任务调用，不依赖自动抢红包开关。 */
    fun installLuckyMoneyNoticeProtection(context: FeatureContext): Boolean {
        if (luckyMoneyNoticeHooksInstalled) return true
        var installed = false
        DexInstallScheduler.runDexKitTask {
            if (!installInsertCaptureHook()) return@runDexKitTask
            val storageInsert = dexFinder?.localSystemMessageStorageInsertMethod ?: return@runDexKitTask
            val methods = LuckyMoneySystemNoticeLocator.locate(context, storageInsert) { message, error ->
                if (error == null) HLog.e("$TAG $message") else HLog.e("$TAG $message", error)
            }
            if (methods.isEmpty()) return@runDexKitTask
            synchronized(this) {
                for (method in methods) {
                    if (method in luckyMoneyNoticeMethods) continue
                    HookRegistry.get().hook(method, LuckyMoneyNoticeHooker(this))
                    luckyMoneyNoticeMethods.add(method)
                }
                luckyMoneyNoticeHooksInstalled = methods.all { it in luckyMoneyNoticeMethods }
                installed = luckyMoneyNoticeHooksInstalled
            }
        }
        return installed
    }

    private class LuckyMoneyNoticeHooker(private val owner: WeChatLocalMessageApi) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            // 已核实的原生 helper 固定 (SystemMsgContext, sessionUserName)，不是网络 callback。
            val content = chain.args.getOrNull(0) as? String ?: return chain.proceed()
            val talker = chain.args.getOrNull(1) as? String ?: return chain.proceed()
            val request = owner.nativeNoticeRequest(talker, content) ?: return chain.proceed()
            return owner.insertTimeScope.during(talker, null) {
                owner.nativeInsertScope.during(request) {
                    // 只执行一次原生 helper，不继承外层指定时间；保留原文、链接和存储通知。
                    val result = chain.proceed()
                    request.error?.let { HLog.e("$TAG 原生红包提示来源登记失败: $it") }
                    request.warning?.let { HLog.e("$TAG $it") }
                    val localId = request.result
                    if (localId != null && localId > 0L && request.error == null) {
                        HLog.event("$TAG 原生红包领取提示已登记: msgId=$localId")
                    } else if (request.error == null) {
                        HLog.e("$TAG 原生红包提示未捕获有效插入 ID: result=$localId target=${chain.executable}")
                    }
                    result
                }
            }
        }
    }

    private fun nativeNoticeRequest(talker: String, content: String): SystemMessageInsertScope.Request? {
        if (talker.isBlank() || content.isBlank() || !insertCaptureInstalled || noticeProtection == null) return null
        return try {
            val account = WeChatApis.account()?.selfWxId().orEmpty()
            if (account.isBlank()) return null
            SystemMessageInsertScope.Request(
                talker, null, true, account,
                expectedContent = content,
                cancelOnProtectionFailure = false
            )
        } catch (error: Throwable) {
            // 登记不可用不能阻止宿主收红包；原生 helper 仍由调用者正常 proceed。
            HLog.e("$TAG 原生红包提示来源作用域准备失败", error)
            null
        }
    }

    private class SystemInsertCaptureHooker(private val owner: WeChatLocalMessageApi) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val request = owner.nativeInsertScope.current() ?: return chain.proceed()
            val message = chain.args.firstOrNull() ?: return chain.proceed()
            val snapshot = NativeSystemMessageAccess.snapshot(message) ?: return chain.proceed()
            if (!request.claim(snapshot.talker, snapshot.type, snapshot.createTime,
                    snapshot.serverId, snapshot.content)) return chain.proceed()
            var pending: LocalNoticeProtection.Pending? = null
            if (request.protected) {
                pending = try {
                    owner.noticeProtection?.prepare(request.account, snapshot.talker,
                        snapshot.createTime, snapshot.content)
                } catch (failure: Throwable) {
                    owner.log("登记本地提示来源失败: ${failure.javaClass.simpleName}")
                    null
                }
                if (pending == null) {
                    request.error = "本地提示来源登记不可用"
                    // 模块自建提示可以取消；宿主自己生成的提示必须照常插入。
                    if (request.cancelOnProtectionFailure) return 0L
                }
            }
            var insertedId = 0L
            try {
                val result = chain.proceed()
                insertedId = (result as? Number)?.toLong() ?: 0L
                request.result = insertedId
                return result
            } finally {
                if (pending != null) {
                    try {
                        if (!owner.noticeProtection!!.complete(pending, insertedId) && insertedId > 0L) {
                            request.warning = "本地提示已插入，但来源登记未能持久化"
                        }
                    } catch (failure: Throwable) {
                        request.error = "本地提示来源保存失败: ${failure.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    /** 指定时间插入，并登记来源，使模块自己的必要提示不被系统灰字净化隐藏。 */
    fun insertProtectedSystemMessageAt(talker: String?, content: String?, createTime: Long): Long =
        insertSystemMessage(talker, content, createTime, false, true)

    fun insertSystemMessage(talker: String?, content: String?, createTime: Long = System.currentTimeMillis()): Long {
        return insertSystemMessage(talker, content, createTime, true)
    }

    fun insertSystemMessageAt(talker: String?, content: String?, createTime: Long): Long {
        return insertSystemMessage(talker, content, createTime, false)
    }

    private fun insertSystemMessage(
        talker: String?,
        content: String?,
        createTime: Long,
        useWechatCreateTime: Boolean,
        protectNotice: Boolean = false
    ): Long {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(content)) {
            log("插入系统消息失败: talker/content 为空")
            return 0L
        }
        val finder = dexFinder
        if (finder == null || !finder.hasLocalMessageApi()) {
            log("插入系统消息失败: 本地消息API未就绪")
            return 0L
        }
        return runCatching {
            val pinnedCreateTime = if (!useWechatCreateTime) {
                val time = normalizeCreateTimeMillis(createTime)
                require(time > 0L) { "指定插入时间无效: $createTime" }
                // 指定时间的契约不能静默退回会话末尾；定位失败明确返回失败。
                if (finder.localSystemMessageMethod != null && !installCreateTimeHook()) {
                    throw IllegalStateException("系统提示时间方法未找到，取消指定位置插入")
                }
                time
            } else null
            insertViaWechatSystemMessageMethod(
                finder,
                talker.orEmpty(),
                content.orEmpty(),
                pinnedCreateTime,
                protectNotice
            )?.let {
                return@runCatching it
            }
            if (protectNotice) throw IllegalStateException("未定位可验证来源的系统提示插入链")
            val msg = newMessage(finder, talker.orEmpty())
                ?: throw IllegalStateException("消息对象创建失败")
            fillSystemMessage(msg, talker.orEmpty(), content.orEmpty(), createTime, useWechatCreateTime)
            val result = KavaReflector.invoke(finder.localMessageInsertMethod, null, msg)
            (result as? Number)?.toLong() ?: 0L
        }.onFailure {
            log("插入系统消息失败: ${it.message}")
        }.getOrDefault(0L)
    }

    private fun insertViaWechatSystemMessageMethod(
        finder: DexFinder,
        talker: String,
        content: String,
        createTime: Long?,
        protectNotice: Boolean
    ): Long? {
        val method = finder.localSystemMessageMethod ?: return null
        if (protectNotice) {
            check(installInsertCaptureHook()) { "系统提示真实插入结果入口未找到" }
        }
        val owner = newSystemMessageOwner(finder, method)
            ?: throw IllegalStateException("系统消息API实例创建失败")
        val account = if (protectNotice) WeChatApis.account()?.selfWxId().orEmpty() else ""
        if (protectNotice) check(account.isNotBlank() && noticeProtection != null) { "提示来源账号尚未就绪" }
        if (!protectNotice) {
            return insertTimeScope.during(talker, createTime) {
                // 非受保护提示保留原 API 语义，不依赖防撤回专用的 storage capture Hook。
                KavaReflector.invokeOrThrow(method, owner, talker, content, "")
                1L
            }
        }
        val request = SystemMessageInsertScope.Request(talker, createTime, true, account)
        return nativeInsertScope.during(request) {
            insertTimeScope.during(talker, createTime) {
                KavaReflector.invokeOrThrow(method, owner, talker, content, "")
            }
            check(request.error == null) { request.error.orEmpty() }
            request.warning?.let { log(it) }
            // Listener 正常 return-void 不代表成功；只接受 storage 返回的真实本地 msgId.
            request.result?.takeIf { it > 0L }
                ?: throw IllegalStateException("系统提示原生插入未返回有效消息 ID")
        }
    }

    private fun newSystemMessageOwner(finder: DexFinder, method: Method): Any? {
        val ownerClass = method.declaringClass ?: return null
        WeChatInternalServices.getService(finder, ownerClass)?.let { return it }
        KavaReflector.staticInstance(ownerClass)?.let { return it }
        staticOwnerInstance(ownerClass)?.let { return it }

        val errors = ArrayList<String>()
        KavaReflector.newInstanceByArgs(ownerClass, emptyArray())?.let { return it }
        runCatching {
            KavaReflector.findConstructor(ownerClass)?.let { ctor ->
                KavaReflector.newInstance(ctor)?.let { return it }
            }
        }.onFailure {
            errors.add("KavaCtor=${it.javaClass.simpleName}:${it.message}")
        }

        val unsafe = unsafeAllocate(ownerClass).also {
            if (it == null) errors.add("UnsafeAllocate=null")
        }
        if (unsafe != null) return unsafe

        log("系统消息API实例创建失败: owner=${ownerClass.name} service=false static=false ${errors.joinToString(";")}")
        return null
    }

    private fun unsafeAllocate(ownerClass: Class<*>): Any? {
        for (unsafeClassName in arrayOf("sun.misc.Unsafe", "jdk.internal.misc.Unsafe")) {
            val result = runCatching {
                val unsafeClass = KavaReflector.loadClass(unsafeClassName, ownerClass.classLoader) ?: return@runCatching null
                val unsafe = KavaReflector.declaredFields(unsafeClass).firstNotNullOfOrNull { field ->
                    if (!KavaReflector.isStatic(field) || !unsafeClass.isAssignableFrom(field.type)) {
                        null
                    } else {
                        KavaReflector.readField(field, null as Any?)
                    }
                } ?: return@runCatching null
                val allocate = KavaReflector.findDeclaredMethod(unsafeClass, "allocateInstance", Class::class.java)
                KavaReflector.invoke(allocate, unsafe, ownerClass)
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun staticOwnerInstance(ownerClass: Class<*>): Any? {
        for (field in KavaReflector.declaredFields(ownerClass)) {
            if (!KavaReflector.isStatic(field)) continue
            if (!ownerClass.isAssignableFrom(field.type)) continue
            val value = KavaReflector.readField(field, null as Any?)
            if (value != null) return value
        }
        return null
    }

    private fun newMessage(finder: DexFinder, talker: String): Any? {
        val ctor = finder.localMessageCtor ?: return null
        return if (ctor.parameterTypes.isEmpty()) {
            KavaReflector.newInstance(ctor)
        } else {
            KavaReflector.newInstance(ctor, talker)
        }
    }

    private fun fillSystemMessage(
        msg: Any,
        talker: String,
        content: String,
        createTime: Long,
        useWechatCreateTime: Boolean
    ) {
        val wechatCreateTime = if (useWechatCreateTime) {
            computeWechatCreateTime(talker, createTime)
        } else {
            normalizeCreateTimeMillis(createTime)
        }
        callIntSetter(msg, 0, "U0", "k1", "j1")
            || setIntField(msg, 0, "field_isSend", "isSend")
        callStringSetter(msg, talker, "i1", "A1", "H1", "C1", "u1")
            || setStringField(msg, talker, "field_talker", "talker")
        callIntSetter(msg, 3, "h1", "z1", "E1", "y1", "t1", "r1")
            || setIntField(msg, 3, "field_status", "status")
        callStringSetter(msg, content, "J0", "Y0", "d1", "c1")
            || setStringField(msg, content, "field_content", "content")
        callLongSetter(msg, wechatCreateTime, "K0", "a1", "f1", "e1", "d1")
            || setLongField(msg, wechatCreateTime, "field_createTime", "createTime")
        callIntSetter(msg, WeChatMessageTypes.SYSTEM, "setType")
            || setIntField(msg, WeChatMessageTypes.SYSTEM, "field_type", "type")
        setLongField(msg, 0L, "field_msgSvrId", "msgSvrId")
        setStringField(msg, "", "field_imgPath", "imgPath")
        setStringField(msg, "", "field_reserved", "reserved")
        setStringField(msg, "", "field_transContent", "transContent")
        setStringField(msg, "", "field_msgSource", "msgSource")
    }

    private fun normalizeCreateTimeMillis(createTime: Long): Long {
        return if (createTime > 0L && createTime < 10_000_000_000L) createTime * 1000L else createTime
    }

    private fun computeWechatCreateTime(talker: String, createTime: Long): Long {
        val method = dexFinder?.localMessageCreateTimeMethod ?: return createTime
        val seconds = if (createTime > 10_000_000_000L) createTime / 1000L else createTime
        val value = KavaReflector.invoke(method, null, talker, seconds)
        return (value as? Number)?.toLong() ?: createTime
    }

    private fun callStringSetter(target: Any, value: String, vararg preferredNames: String): Boolean {
        return callSetter(target, String::class.java, value, *preferredNames)
    }

    private fun callIntSetter(target: Any, value: Int, vararg names: String): Boolean {
        return callSetter(target, Integer.TYPE, value, *names)
            || callSetter(target, Integer::class.java, value, *names)
    }

    private fun callLongSetter(target: Any, value: Long, vararg names: String): Boolean {
        return callSetter(target, java.lang.Long.TYPE, value, *names)
            || callSetter(target, java.lang.Long::class.java, value, *names)
    }

    private fun callSetter(target: Any, parameterType: Class<*>, value: Any, vararg preferredNames: String): Boolean {
        for (name in preferredNames.distinct()) {
            val method = KavaReflector.findMethodRecursive(target.javaClass, name, parameterType) ?: continue
            if (invokeSetter(method, target, value)) return true
        }
        return false
    }

    private fun invokeSetter(method: Method, target: Any, value: Any): Boolean {
        return KavaReflector.invokeSuccessfully(method, target, value)
    }

    private fun setStringField(target: Any, value: String, vararg names: String): Boolean {
        for (name in names) {
            val field = KavaReflector.findFieldRecursive(target.javaClass, name) ?: continue
            if (setField(field, target, value)) return true
        }
        return false
    }

    private fun setIntField(target: Any, value: Int, vararg names: String): Boolean {
        for (name in names) {
            val field = KavaReflector.findFieldRecursive(target.javaClass, name) ?: continue
            if (setField(field, target, value)) return true
        }
        return false
    }

    private fun setLongField(target: Any, value: Long, vararg names: String): Boolean {
        for (name in names) {
            val field = KavaReflector.findFieldRecursive(target.javaClass, name) ?: continue
            if (setField(field, target, value)) return true
        }
        return false
    }

    private fun setField(field: Field, target: Any, value: Any): Boolean {
        return KavaReflector.writeField(field, target, value)
    }

    private fun log(message: String) {
        HLog.e("$TAG $message")
        logger?.log("[WeChatLocalMessageApi] $message")
    }

    companion object {
        private const val TAG = "[Hchat:LocalMessage]"
    }

}
