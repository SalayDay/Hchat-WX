package h.Hchat.hooks.items.antirecall

import h.Hchat.hooks.api.model.WeChatMessage

/** 撤回提示只关联同一会话、同一服务端消息 ID 的原记录，不把服务端 ID 当本地 ID。 */
internal object RecallNoticeAnchor {
    fun matches(message: WeChatMessage?, talker: String, serverId: Long): Boolean =
        message != null && serverId > 0L && message.talker == talker &&
            message.msgSvrId == serverId && !message.isRecalled()

    fun resolve(
        talker: String,
        serverIds: List<Long>,
        stored: (String, Long) -> WeChatMessage?,
        cached: (String, Long) -> WeChatMessage?
    ): WeChatMessage? {
        val ids = serverIds.filter { it > 0L }.distinct()
        // 事件缓存可能只有服务器秒时间；原表毫秒时间才是列表实际的排序依据。
        for (id in ids) {
            stored(talker, id)?.takeIf { matches(it, talker, id) }?.let { return it }
        }
        for (id in ids) {
            cached(talker, id)?.takeIf { matches(it, talker, id) }?.let { return it }
        }
        return null
    }

    fun normalizeMillis(value: Long): Long =
        if (value > 0L && value < 10_000_000_000L) value * 1000L else value

    /** 仅在两条记录之间存在毫秒间隔时，+1 可提供严格的中间排序位置。 */
    fun noticeTime(originalCreateTime: Long): Long {
        val millis = normalizeMillis(originalCreateTime)
        return if (millis > 0L && millis < Long.MAX_VALUE) millis + 1L else 0L
    }
}
