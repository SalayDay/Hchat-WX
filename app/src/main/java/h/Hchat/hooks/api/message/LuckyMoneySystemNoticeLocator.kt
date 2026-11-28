package h.Hchat.hooks.api.message

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * 定位红包领取灰字的原生系统提示 helper，只返回方法，不安装 Hook。
 *
 * 8.0.76/80 静态证据：普通/Union 的 onGYNetEnd 方法体各自同时持有三条完整锚点，
 * 并直接调用 model 包的 public static void(String content, String talker) helper。
 * helper 本身没有 method strings；它保留 content，构造 type=10000，并同步调用真实
 * storage insert。因此只沿 caller strings -> caller invokes -> helper invokes -> storage
 * 验证，不给 helper 附加 usingStrings，不按混淆类名或方法名猜测。
 *
 * 调用方必须在 DexInstallScheduler 的统一串行门内调用本函数；包括缓存未命中后的
 * bridge 查询、getMethodData、getInvokes 和成员解析，不能与其它 DexKit 任务并发。
 * 本定位器不创建线程或独立 bridge。logger 由调用方接入 HLog.e，异常原样传递。
 */
object LuckyMoneySystemNoticeLocator {
    private const val CACHE_PREFS = "Hchat_lucky_money_system_notice_cache"
    private const val CACHE_KEY = "system_notice_helpers_v1" + "_v1"

    fun locate(
        context: FeatureContext,
        storageInsert: Method,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        var runtimeKey = ""
        return try {
            val loader = context.hostClassLoader()
            // 每次定位都实时读取，不能用对象字段保留跨版本/热更新的 runtime key 或 Method。
            runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), loader)
            if (runtimeKey.isBlank()) {
                logger("红包系统提示定位失败 stage=runtime_key: 运行时身份未就绪", null)
                emptyList()
            } else {
                val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
                val cached = DexMethodCache.loadList(prefs, runtimeKey, loader, CACHE_KEY)
                val descriptors = prefs.getString(CACHE_KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }
                if (LuckyMoneySystemNoticeFacts.isCompleteCache(descriptors, cached) &&
                    LuckyMoneySystemNoticeFacts.isValidCache(cached.map(::shape))) {
                    cached
                } else {
                    DexMethodCache.clear(prefs, runtimeKey, CACHE_KEY)
                    val scan = Scan(context, runtimeKey, logger)
                    val callbacks = scan.callbacks()
                    val selected = LuckyMoneySystemNoticeFacts.select(callbacks, storageInsert)
                    selected.problems.forEach {
                        logger("红包系统提示定位失败 stage=select runtime=$runtimeKey: $it", null)
                    }
                    if (scan.incomplete || selected.problems.isNotEmpty()) {
                        if (scan.incomplete) {
                            logger("红包系统提示定位失败 stage=chain runtime=$runtimeKey: 两路调用链未完整验证", null)
                        }
                        emptyList()
                    } else {
                        // 只有普通和 Union 均完整命中，且各自唯一 helper 时才允许持久化。
                        DexMethodCache.saveList(prefs, runtimeKey, CACHE_KEY, selected.methods)
                        selected.methods
                    }
                }
            }
        } catch (error: Throwable) {
            logger("红包系统提示定位异常 runtime=$runtimeKey", error)
            emptyList()
        }
    }

    private class Scan(
        private val context: FeatureContext,
        private val runtimeKey: String,
        private val logger: (String, Throwable?) -> Unit
    ) {
        var incomplete = false
            private set
        private val loader = context.hostClassLoader()
        private val bridge = context.dexKitBridge()
        private val helpers = mutableMapOf<Method, LuckyMoneySystemNoticeFacts.Helper<Method>?>()

        fun callbacks(): List<LuckyMoneySystemNoticeFacts.Callback<Method>> {
            val callbacks = linkedMapOf<Method, LuckyMoneySystemNoticeFacts.Callback<Method>>()
            // 不能命中普通入口后提前 return；Union 必须独立扫描、验证。
            for (path in LuckyMoneySystemNoticeFacts.Path.values()) {
                try {
                    val candidates = bridge.findMethod(FindMethod().apply {
                        matcher(MethodMatcher().apply {
                            usingEqStrings(*LuckyMoneySystemNoticeFacts.anchors(path).toTypedArray())
                        })
                    })
                    for (data in candidates) {
                        try {
                            val method = data.getMethodInstance(loader)
                            val methodShape = shape(method)
                            val strings = data.usingStrings.toSet()
                            if (!LuckyMoneySystemNoticeFacts.isCallback(methodShape) ||
                                !LuckyMoneySystemNoticeFacts.hasAnchors(strings, path) || method in callbacks
                            ) continue
                            val ownHelpers = invokedMethods(data).filter {
                                LuckyMoneySystemNoticeFacts.isHelper(shape(it))
                            }.distinct().mapNotNull(::helper)
                            callbacks[method] = LuckyMoneySystemNoticeFacts.Callback(
                                method, methodShape, strings, ownHelpers
                            )
                        } catch (error: Throwable) {
                            incomplete = true
                            logger("红包系统提示定位异常 stage=callback path=${path.name} " +
                                "target=${data.descriptor} runtime=$runtimeKey", error)
                        }
                    }
                } catch (error: Throwable) {
                    incomplete = true
                    logger("红包系统提示定位异常 stage=anchors path=${path.name} runtime=$runtimeKey", error)
                }
            }
            return callbacks.values.toList()
        }

        private fun helper(method: Method): LuckyMoneySystemNoticeFacts.Helper<Method>? {
            if (helpers.containsKey(method)) return helpers[method]
            val facts = try {
                val data = bridge.getMethodData(method)
                if (data == null) {
                    incomplete = true
                    logger("红包系统提示定位失败 stage=helper_data target=$method runtime=$runtimeKey: 无方法体数据", null)
                    null
                } else {
                    LuckyMoneySystemNoticeFacts.Helper(method, shape(method), invokedMethods(data).toSet())
                }
            } catch (error: Throwable) {
                incomplete = true
                logger("红包系统提示定位异常 stage=helper target=$method runtime=$runtimeKey", error)
                null
            }
            helpers[method] = facts
            return facts
        }

        private fun invokedMethods(data: MethodData): List<Method> =
            LuckyMoneySystemNoticeFacts.resolveInvokes(data.invokes,
                resolve = { it.getMethodInstance(loader) },
                onError = { invoked, error ->
                    logger("红包系统提示调用项解析失败 stage=invoke caller=${data.descriptor} " +
                        "target=${invoked.descriptor} runtime=$runtimeKey", error)
                })
    }

    private fun shape(method: Method) = LuckyMoneySystemNoticeFacts.Shape(
        method.declaringClass.name, method.name, method.returnType.name,
        method.parameterTypes.map { it.name }, method.modifiers, method.declaringClass.isInterface
    )
}
