package h.Hchat.hooks.api.message

import java.lang.reflect.Modifier

/** 定位器与 JVM 回归共用的事实筛选，不依赖 Android、宿主类或 DexKit。 */
internal object LuckyMoneySystemNoticeFacts {
    private const val MODEL_PACKAGE = "com.tencent.mm.plugin.luckymoney.model"
    const val CONTEXT_ANCHOR = "SystemMsgContext"
    const val INSERT_ERROR_ANCHOR = "insertLocalSysMsgIfNeed error: %s"

    enum class Path(val tag: String) {
        NORMAL("MicroMsg.NetSceneOpenLuckyMoney"),
        UNION("MicroMsg.NetSceneOpenLuckyMoneyUnion")
    }

    data class Shape(
        val owner: String,
        val name: String,
        val returnType: String,
        val parameterTypes: List<String>,
        val modifiers: Int,
        val ownerIsInterface: Boolean = false
    )

    data class Helper<T>(val method: T, val shape: Shape, val invokes: Set<T>)

    data class Callback<T>(
        val method: T,
        val shape: Shape,
        val strings: Set<String>,
        val helpers: List<Helper<T>>
    )

    data class Selection<T>(val methods: List<T>, val problems: List<String>)

    fun anchors(path: Path): List<String> = listOf(CONTEXT_ANCHOR, INSERT_ERROR_ANCHOR, path.tag)

    fun hasAnchors(strings: Set<String>, path: Path): Boolean = strings.containsAll(anchors(path))

    fun isCallback(shape: Shape): Boolean = inModelPackage(shape) && isConcrete(shape) &&
        !Modifier.isStatic(shape.modifiers) && shape.name == "onGYNetEnd" &&
        shape.returnType == "void" &&
        shape.parameterTypes == listOf("int", "java.lang.String", "org.json.JSONObject")

    fun isHelper(shape: Shape): Boolean = inModelPackage(shape) && isConcrete(shape) &&
        Modifier.isPublic(shape.modifiers) && Modifier.isStatic(shape.modifiers) &&
        shape.returnType == "void" &&
        shape.parameterTypes == listOf("java.lang.String", "java.lang.String")

    /** 缓存只保存完整两路验证后的结果；同一个 helper 可同时服务两路，因此允许 1 或 2 项。 */
    fun isValidCache(shapes: List<Shape>): Boolean = shapes.size in 1..2 && shapes.all(::isHelper)

    /** loadList 会跳过解析失败的 descriptor；解析出一项不等于原先保存的两项都有效。 */
    fun <T> isCompleteCache(descriptors: List<String>, resolved: List<T>): Boolean =
        descriptors.size == resolved.size && descriptors.distinct().size == descriptors.size &&
            resolved.distinct().size == resolved.size

    /**
     * 每个入口必须自己直接调用唯一的合格 helper，helper 必须直接调用指定 storage insert。
     * 两路都遍历完才提交结果，任何一路缺失或歧义都不能返回另一条的部分结果。
     */
    fun <T> select(callbacks: List<Callback<T>>, storageInsert: T): Selection<T> {
        val selected = linkedSetOf<T>()
        val problems = mutableListOf<String>()
        for (path in Path.values()) {
            val ownCallbacks = callbacks.filter { isCallback(it.shape) && hasAnchors(it.strings, path) }
                .distinctBy { it.method }
            if (ownCallbacks.size != 1) {
                problems += "${path.name} callback count=${ownCallbacks.size} (expected 1)"
            }
            for (callback in ownCallbacks) {
                val eligible = callback.helpers.filter {
                    isHelper(it.shape) && storageInsert in it.invokes
                }.distinctBy { it.method }
                if (eligible.size != 1) {
                    problems += "${path.name} callback=${callback.method} helper count=${eligible.size} (expected 1)"
                } else {
                    selected += eligible.single().method
                }
            }
        }
        return Selection(if (problems.isEmpty()) selected.toList() else emptyList(), problems)
    }

    /** getInvokes 可含构造器；逐项解析，保留原始异常并继续后续条目。 */
    fun <T, R : Any> resolveInvokes(
        entries: Iterable<T>,
        resolve: (T) -> R,
        onError: (T, Throwable) -> Unit
    ): List<R> = entries.mapNotNull { entry ->
        try {
            resolve(entry)
        } catch (error: Throwable) {
            onError(entry, error)
            null
        }
    }

    private fun inModelPackage(shape: Shape): Boolean = shape.owner.substringBeforeLast('.', "") == MODEL_PACKAGE

    private fun isConcrete(shape: Shape): Boolean = !shape.ownerIsInterface &&
        !Modifier.isAbstract(shape.modifiers) && !Modifier.isNative(shape.modifiers)
}
