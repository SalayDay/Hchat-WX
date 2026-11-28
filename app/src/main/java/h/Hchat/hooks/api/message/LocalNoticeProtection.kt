package h.Hchat.hooks.api.message

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块提示及已核实宿主原生提示入口的来源登记；不按正文词语猜测，也不修改微信消息格式。
 * 精确身份由会话、数据库毫秒时间、完整内容组成，落盘只保存 SHA-256 和插入 ID。
 */
internal class LocalNoticeProtection(private val persistence: Persistence) {
    interface Persistence {
        fun loadEntries(): Map<String, Long>
        fun save(key: String, localId: Long): Boolean
    }

    class Pending internal constructor(internal val key: String)
    private val confirmed = ConcurrentHashMap<String, Long>().apply { putAll(persistence.loadEntries()) }
    private val pendingCounts = ConcurrentHashMap<String, Int>()

    fun hasEntries(): Boolean = confirmed.isNotEmpty() || pendingCounts.isNotEmpty()

    fun prepare(account: String, talker: String, createTime: Long, content: String): Pending {
        val key = identity(account, talker, createTime, content)
            ?: throw IllegalArgumentException("本地提示身份不完整")
        pendingCounts.compute(key) { _, count -> (count ?: 0) + 1 }
        return Pending(key)
    }

    /** 必须得到微信真实插入结果；返回 void 或占位的 1 不能证明落库成功。 */
    fun complete(pending: Pending, localId: Long): Boolean {
        try {
            if (localId <= 0L) return false
            // 当前进程立即可见；持久化失败只影响下次进程恢复，不能让刚插入的提示被净化隐藏。
            val entryKey = registryKey(pending.key, localId)
            confirmed[entryKey] = localId
            return runCatching { persistence.save(entryKey, localId) }.getOrDefault(false)
        } finally {
            pendingCounts.computeIfPresent(pending.key) { _, count -> if (count <= 1) null else count - 1 }
        }
    }

    fun preserve(
        account: String, talker: String, createTime: Long, content: String,
        localId: Long, serverId: Long
    ): Boolean {
        if (serverId != 0L) return false
        val key = identity(account, talker, createTime, content) ?: return false
        // 最强身份是原生 storage 返回的本地 msgId 与完整行字段同时匹配。
        if (localId > 0L && confirmed[registryKey(key, localId)] == localId) return true
        // 插入同步通知若发生在 storage 返回 ID 前，仅暂时认可精确 pending 行。
        return (pendingCounts[key] ?: 0) > 0
    }

    private fun registryKey(identity: String, localId: Long): String = "$identity:$localId"

    private fun identity(account: String, talker: String, createTime: Long, content: String): String? {
        if (account.isBlank() || talker.isBlank() || createTime <= 0L || content.isEmpty()) return null
        val input = "local-notice-v1\u0000${account.length}:$account\u0000${talker.length}:$talker\u0000$createTime\u0000$content"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
