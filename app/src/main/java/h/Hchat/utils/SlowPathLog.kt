package h.Hchat.utils

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * 热路径耗时诊断。
 *
 * 消息收发、列表绑定这些路径上如果模块自己耗时过长，用户感知就是「消息有延迟」。
 * 这里只在超过阈值时按 key 限频记一条日志，正常情况只有两次时钟读取的开销，
 * 便于从真机日志里直接看出卡在哪个环节。
 */
object SlowPathLog {
    private const val WINDOW_MS = 10_000L
    private val lastLogged = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun elapsedSince(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L

    @JvmStatic
    fun now(): Long = SystemClock.elapsedRealtimeNanos()

    /**
     * @param key 限频键，同一 key 10 秒内只记一次
     * @param elapsedMs 本次耗时
     * @param thresholdMs 超过该值才记录
     * @param what 行为描述
     */
    @JvmStatic
    @JvmOverloads
    fun report(key: String, elapsedMs: Long, thresholdMs: Long, what: String, detail: String = "") {
        if (elapsedMs < thresholdMs) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastLogged[key]
        if (previous != null && now - previous < WINDOW_MS) return
        lastLogged[key] = now
        val suffix = if (detail.isEmpty()) "" else "（$detail）"
        HLog.e("[Hchat:Slow] $what 耗时 ${elapsedMs}ms$suffix")
    }
}
