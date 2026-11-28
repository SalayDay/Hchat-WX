package h.Hchat.hooks.api.message

import org.junit.Assert.*
import org.junit.Test

class LocalNoticeProtectionTest {
    private class MemoryPersistence : LocalNoticeProtection.Persistence {
        val records = HashMap<String, Long>()
        override fun loadEntries(): Map<String, Long> = records.toMap()
        override fun save(key: String, localId: Long): Boolean {
            records[key] = localId
            return true
        }
    }

    @Test fun onlyTheExactAccountChatTimeContentAndLocalIdAreProtected() {
        val db = MemoryPersistence()
        val protection = LocalNoticeProtection(db)
        val pending = protection.prepare("self", "room@chatroom", 1001L, "notice")
        assertTrue(protection.complete(pending, 101L))
        assertTrue(protection.preserve("self", "room@chatroom", 1001L, "notice", 101L, 0L))
        assertFalse(protection.preserve("self", "room@chatroom", 1001L, "notice", 102L, 0L))
        assertFalse(protection.preserve("other", "room@chatroom", 1001L, "notice", 101L, 0L))
        assertFalse(protection.preserve("self", "other@chatroom", 1001L, "notice", 101L, 0L))
        assertFalse(protection.preserve("self", "room@chatroom", 1001L, "notice2", 101L, 0L))
        assertFalse(protection.preserve("self", "room@chatroom", 1001L, "notice", 101L, 999L))
    }

    @Test fun persistentRegistrySurvivesProcessRecreation() {
        val db = MemoryPersistence()
        val first = LocalNoticeProtection(db)
        val pending = first.prepare("self", "room", 2000L, "system notice")
        first.complete(pending, 77L)
        val second = LocalNoticeProtection(db)
        assertTrue(second.hasEntries())
        assertTrue(second.preserve("self", "room", 2000L, "system notice", 77L, 0L))
    }

    @Test fun failedStorageInsertNeverBecomesProtected() {
        val db = MemoryPersistence()
        val protection = LocalNoticeProtection(db)
        val pending = protection.prepare("self", "room", 2000L, "notice")
        assertFalse(protection.complete(pending, 0L))
        assertFalse(protection.hasEntries())
        assertFalse(protection.preserve("self", "room", 2000L, "notice", 0L, 0L))
    }

    @Test fun twoInsertsWithIdenticalTextAndTimeKeepSeparateLocalIds() {
        val db = MemoryPersistence()
        val protection = LocalNoticeProtection(db)
        val first = protection.prepare("self", "room", 2000L, "notice")
        val second = protection.prepare("self", "room", 2000L, "notice")
        assertTrue(protection.complete(first, 101L))
        assertTrue(protection.complete(second, 102L))
        val restored = LocalNoticeProtection(db)
        assertTrue(restored.preserve("self", "room", 2000L, "notice", 101L, 0L))
        assertTrue(restored.preserve("self", "room", 2000L, "notice", 102L, 0L))
        assertFalse(restored.preserve("self", "room", 2000L, "notice", 103L, 0L))
    }

    @Test fun persistenceFailureStillProtectsTheSuccessfullyInsertedRowInThisProcess() {
        val protection = LocalNoticeProtection(object : LocalNoticeProtection.Persistence {
            override fun loadEntries(): Map<String, Long> = emptyMap()
            override fun save(key: String, localId: Long): Boolean = false
        })
        val pending = protection.prepare("self", "room", 2000L, "notice")
        assertFalse(protection.complete(pending, 77L))
        assertTrue(protection.preserve("self", "room", 2000L, "notice", 77L, 0L))
        assertFalse(protection.preserve("self", "room", 2000L, "notice", 0L, 0L))
    }

    @Test fun pendingRequestIsScopedToLocalNoticeWithoutServerId() {
        val protection = LocalNoticeProtection(MemoryPersistence())
        protection.prepare("self", "room", 2000L, "notice")
        assertTrue(protection.preserve("self", "room", 2000L, "notice", 0L, 0L))
        assertFalse(protection.preserve("self", "room", 2000L, "notice", 0L, 12L))
        assertFalse(protection.preserve("self", "room", 2001L, "notice", 0L, 0L))
    }
}
