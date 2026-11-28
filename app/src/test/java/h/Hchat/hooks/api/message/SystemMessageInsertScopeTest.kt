package h.Hchat.hooks.api.message

import org.junit.Assert.*
import org.junit.Test

class SystemMessageInsertScopeTest {
    @Test fun onlyTheExpectedLocalSystemRowCanBeCaptured() {
        val request = SystemMessageInsertScope.Request("room", 1_001L, true, "self")
        assertFalse(request.claim("room", 1, 1_001L, 0L))
        assertFalse(request.claim("other", 10_000, 1_001L, 0L))
        assertFalse(request.claim("room", 10_000, 1_002L, 0L))
        assertFalse(request.claim("room", 10_000, 1_001L, 42L))
        assertTrue(request.claim("room", 10_000, 1_001L, 0L))
        assertFalse(request.claim("room", 10_000, 1_001L, 0L))
    }

    @Test fun nativeNoticeMatchesTheCompleteServerContextWithoutGuessingWords() {
        val content = "you received <a href=\"weixin://weixinhongbao/receivehongbao?sendid=packet\">gift</a>"
        val request = SystemMessageInsertScope.Request(
            "friend", null, true, "self", expectedContent = content, cancelOnProtectionFailure = false
        )
        assertFalse(request.claim("friend", 10_000, 2_001L, 0L, "other text"))
        assertFalse(request.claim("other", 10_000, 2_001L, 0L, content))
        assertFalse(request.claim("friend", 1, 2_001L, 0L, content))
        assertFalse(request.claim("friend", 10_000, 2_001L, 42L, content))
        assertTrue(request.claim("friend", 10_000, 2_001L, 0L, content))
        assertFalse(request.claim("friend", 10_000, 2_002L, 0L, content))
        assertFalse(request.cancelOnProtectionFailure)
    }

    @Test fun moduleGeneratedNoticeRetainsFailClosedPolicy() {
        val recall = SystemMessageInsertScope.Request("room", 1001L, true, "self")
        assertTrue(recall.cancelOnProtectionFailure)
        // PocketMoney Listener 可对正文作替换；旧防撤回身份继续以实际 storage 行登记。
        assertTrue(recall.claim("room", 10_000, 1001L, 0L, "native substituted content"))
    }

    @Test fun nativeNoticeRestoresOuterRecallInsertAndDoesNotOverrideWechatTime() {
        val scope = SystemMessageInsertScope()
        val timeScope = LocalMessageTimeScope()
        val recall = SystemMessageInsertScope.Request("friend", 1001L, true, "self")
        val native = SystemMessageInsertScope.Request("friend", null, true, "self", "server context", false)
        scope.during(recall) {
            timeScope.during("friend", 1001L) {
                assertEquals(1001L, timeScope.createTimeFor("friend"))
                try {
                    timeScope.during("friend", null) {
                        scope.during(native) {
                            assertSame(native, scope.current())
                            assertNull(timeScope.createTimeFor("friend"))
                            error("native helper threw")
                        }
                    }
                } catch (_: IllegalStateException) {
                    assertSame(recall, scope.current())
                    assertEquals(1001L, timeScope.createTimeFor("friend"))
                }
            }
        }
        assertNull(scope.current())
        assertNull(timeScope.createTimeFor("friend"))
    }

    @Test fun nestedInsertScopeRestoresOuterRequestAfterFailure() {
        val scope = SystemMessageInsertScope()
        val outer = SystemMessageInsertScope.Request("outer", 1L, true, "self")
        val inner = SystemMessageInsertScope.Request("inner", 2L, true, "self")
        scope.during(outer) {
            assertSame(outer, scope.current())
            try {
                scope.during(inner) {
                    assertSame(inner, scope.current())
                    error("native insert failed")
                }
            } catch (_: IllegalStateException) {
                assertSame(outer, scope.current())
            }
        }
        assertNull(scope.current())
    }

    @Test fun captureScopeIsThreadLocal() {
        val scope = SystemMessageInsertScope()
        val request = SystemMessageInsertScope.Request("room", 1L, false, "")
        var otherThreadSawRequest = true
        scope.during(request) {
            val thread = Thread { otherThreadSawRequest = scope.current() != null }
            thread.start()
            thread.join(1_000)
            assertFalse(thread.isAlive)
        }
        assertFalse(otherThreadSawRequest)
    }
}
