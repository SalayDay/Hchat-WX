package h.Hchat.hooks.items.addemoji

/** 保存链路的纯判定；不接触微信、文件系统或 Android 主线程。 */
internal object EmojiCollectionChecks {
    const val CUSTOM_CATALOG = 81
    const val CUSTOM_PANEL_TYPE = 1
    const val PATH_FAILURE = "[cpan] get icon path failed. product id and md5 are null."
    val PATH_STRINGS: Set<String> = setOf(PATH_FAILURE, "capture", "/")

    data class PathMethod(
        val owner: String,
        val parameterCount: Int,
        val returnType: String,
        val isStatic: Boolean,
        val strings: Set<String>
    )

    fun isNativePathMethod(method: PathMethod, infoClass: String): Boolean =
        method.owner == infoClass && method.parameterCount == 0 &&
            method.returnType == "java.lang.String" && !method.isStatic &&
            method.strings.containsAll(PATH_STRINGS)

    data class PayloadField(val name: String, val type: String, val isStatic: Boolean = false)
    data class PayloadCandidate<T>(val id: T, val fields: List<PayloadField>)

    /** event.g.a 是 int；另一份 event.h.a 是 boolean，不能将其当作面板类型。 */
    fun <T> customPanelPayload(candidates: List<PayloadCandidate<T>>): T? =
        candidates.singleOrNull { candidate ->
            candidate.fields.any { it.name == "a" && it.type == "int" && !it.isStatic }
        }?.id

    fun <T : Any, R : Any> findInHierarchy(
        root: T,
        parent: (T) -> T?,
        find: (T) -> R?
    ): R? {
        var current: T? = root
        while (current != null) {
            find(current)?.let { return it }
            current = parent(current)
        }
        return null
    }

    data class Snapshot(
        val md5: String?,
        val catalog: Int?,
        val type: Int?,
        val size: Int?,
        val width: Int?,
        val height: Int?,
        val state: Int?,
        val temp: Int?,
        val order: Int?,
        val reserved4: Int?
    )

    fun snapshot(read: (String) -> Any?): Snapshot = Snapshot(
        md5 = read("field_md5") as? String,
        catalog = (read("field_catalog") as? Number)?.toInt(),
        type = (read("field_type") as? Number)?.toInt(),
        size = (read("field_size") as? Number)?.toInt(),
        width = (read("field_width") as? Number)?.toInt(),
        height = (read("field_height") as? Number)?.toInt(),
        state = (read("field_state") as? Number)?.toInt(),
        temp = (read("field_temp") as? Number)?.toInt(),
        order = (read("field_reserved3") as? Number)?.toInt(),
        reserved4 = (read("field_reserved4") as? Number)?.toInt()
    )

    data class Expected(val md5: String, val type: Int, val size: Int, val width: Int, val height: Int)
    data class FileSnapshot(
        val exists: Boolean,
        val isFile: Boolean,
        val length: Long,
        val md5: String?,
        val encrypted: Boolean
    )

    sealed interface Plan {
        data object AlreadyPresent : Plan
        data class Save(val writeMissingFile: Boolean) : Plan
        data class Reject(val reason: String) : Plan
    }

    fun plan(existing: Snapshot?, expected: Expected, file: FileSnapshot): Plan {
        if (existing?.catalog == CUSTOM_CATALOG &&
            verificationError(existing, expected, file, allowExistingEncrypted = true) == null
        ) return Plan.AlreadyPresent
        if (!file.exists) return Plan.Save(writeMissingFile = true)
        val problem = fileError(expected, file, allowExistingEncrypted = false)
        return if (problem == null) Plan.Save(writeMissingFile = false) else Plan.Reject(problem)
    }

    /** 只有 fresh provider 快照与真实文件同时过关，调用方才可以报告 Added。 */
    fun verificationError(
        current: Snapshot?,
        expected: Expected,
        file: FileSnapshot,
        allowExistingEncrypted: Boolean = false,
        order: Int? = null
    ): String? {
        if (current == null) return "回读表情库未找到记录"
        if (current.md5 != expected.md5) return "回读表情指纹不一致"
        if (current.catalog != CUSTOM_CATALOG) return "回读表情未进入自定义分类"
        if (current.temp != 0) return "回读表情仍是临时记录"
        if (current.state != 0) return "回读表情尚未处于本地可用状态"
        if (current.type != expected.type) return "回读表情类型不一致"
        if (current.size != expected.size) return "回读表情字节数不一致"
        if (current.width != expected.width || current.height != expected.height) {
            return "回读表情尺寸不一致"
        }
        if (current.order == null || current.order < 0 || (order != null && current.order != order)) {
            return "回读表情排序位不一致"
        }
        if (current.reserved4 == null) return "回读表情缺少文件标记"
        if ((current.reserved4 and 1 != 0) != file.encrypted) return "表情文件标记不一致"
        return fileError(expected, file, allowExistingEncrypted)
    }

    private fun fileError(expected: Expected, file: FileSnapshot, allowExistingEncrypted: Boolean): String? {
        if (!file.exists || !file.isFile) return "微信原生路径下的表情文件不存在"
        if (file.length != expected.size.toLong()) return "表情文件长度校验失败"
        if (file.encrypted) {
            // 原生 decoder 在校验失败时会删除共享文件，因此这里绝不调用它。
            return if (allowExistingEncrypted) null else "已有微信加密表情无法安全校验，未改动原文件"
        }
        if (file.md5 != expected.md5) return "表情文件指纹校验失败，未覆盖已有文件"
        return null
    }
}
