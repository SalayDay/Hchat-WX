package h.Hchat.hooks.items.antirecall

import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import org.junit.Assert.*
import org.junit.Test

class RecallNoticeAnchorTest {
    private val room = "test@chatroom"
    private val baseTime = 1_795_800_000_000L

    private fun message(localId: Long, serverId: Long, time: Long, talker: String = room,
                        type: Int = 1) = WeChatMessage(
        localId, serverId, type, 3, 0, time, talker, "text", "", "", "", 0
    )

    @Test fun middleMessageNoticeStaysBetweenOriginalAndFollowingMessage() {
        val records = listOf(
            message(101, 9101, baseTime),
            message(102, 9102, baseTime + 100),
            message(103, 9103, baseTime + 200),
            message(104, 9104, baseTime + 300)
        )
        val source = RecallNoticeAnchor.resolve(room, listOf(9102),
            stored = { talker, id -> records.firstOrNull { it.talker == talker && it.msgSvrId == id } },
            cached = { _, _ -> null })
        assertEquals(102L, source!!.msgId)
        val noticeTime = RecallNoticeAnchor.noticeTime(source.createTime)
        assertEquals(baseTime + 101, noticeTime)
        assertTrue(noticeTime > records[1].createTime)
        assertTrue(noticeTime < records[2].createTime)
    }

    @Test fun storedMillisecondTimeWinsOverRoundedEventCache() {
        val exact = message(1, 99, baseTime + 375)
        val event = message(0, 99, baseTime)
        val result = RecallNoticeAnchor.resolve(room, listOf(99),
            stored = { _, _ -> exact }, cached = { _, _ -> event })
        assertSame(exact, result)
        assertEquals(baseTime + 376, RecallNoticeAnchor.noticeTime(result!!.createTime))
    }

    @Test fun sameNumericLocalIdIsNotTheTargetServerId() {
        val collision = message(99, 77, baseTime + 9000)
        assertFalse(RecallNoticeAnchor.matches(collision, room, 99))
        val result = RecallNoticeAnchor.resolve(room, listOf(99),
            stored = { _, _ -> collision }, cached = { _, _ -> null })
        assertNull(result)
    }

    @Test fun differentChatCannotAnchorTheNotice() {
        val other = message(1, 99, baseTime, "other@chatroom")
        assertNull(RecallNoticeAnchor.resolve(room, listOf(99),
            stored = { _, _ -> other }, cached = { _, _ -> null }))
    }

    @Test fun recallCommandCannotReplaceThePreservedOriginal() {
        val command = message(5, 100, baseTime + 9000, type = WeChatMessageTypes.RECALLED)
        val original = message(1, 99, baseTime + 123)
        val result = RecallNoticeAnchor.resolve(room, listOf(100, 99),
            stored = { _, id -> if (id == 100L) command else original }, cached = { _, _ -> null })
        assertSame(original, result)
    }

    @Test fun cachedSourceIsUsedWhenStoredSourceIsUnavailable() {
        val cached = message(2, 99, baseTime + 25)
        assertSame(cached, RecallNoticeAnchor.resolve(room, listOf(0, 99, 99),
            stored = { _, _ -> null }, cached = { _, _ -> cached }))
    }

    @Test fun missingSourceDoesNotProduceCurrentTimePlacement() {
        assertNull(RecallNoticeAnchor.resolve(room, listOf(99),
            stored = { _, _ -> null }, cached = { _, _ -> null }))
        assertEquals(0L, RecallNoticeAnchor.noticeTime(0))
        assertEquals(0L, RecallNoticeAnchor.noticeTime(Long.MAX_VALUE))
    }

    @Test fun secondPrecisionTimesAreNormalizedOnlyOnce() {
        assertEquals(baseTime + 1L, RecallNoticeAnchor.noticeTime(baseTime / 1000L))
        assertEquals(baseTime + 124L, RecallNoticeAnchor.noticeTime(baseTime + 123L))
    }
}
