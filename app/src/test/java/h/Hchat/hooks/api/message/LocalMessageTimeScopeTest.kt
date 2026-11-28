package h.Hchat.hooks.api.message

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LocalMessageTimeScopeTest {
    @Test fun pinnedTimeOnlyAppliesToTargetConversation() {
        val scope = LocalMessageTimeScope()
        scope.during("room", 1_795_800_000_101L) {
            assertEquals(1_795_800_000_101L, scope.createTimeFor("room"))
            assertNull(scope.createTimeFor("other"))
        }
        assertNull(scope.createTimeFor("room"))
    }

    @Test fun nestedInsertionRestoresOuterTime() {
        val scope = LocalMessageTimeScope()
        scope.during("room", 100L) {
            scope.during("room", 200L) { assertEquals(200L, scope.createTimeFor("room")) }
            assertEquals(100L, scope.createTimeFor("room"))
        }
        assertNull(scope.createTimeFor("room"))
    }

    @Test fun ordinaryInsertionDoesNotInheritOuterPin() {
        val scope = LocalMessageTimeScope()
        scope.during("room", 100L) {
            scope.during("room", null) { assertNull(scope.createTimeFor("room")) }
            assertEquals(100L, scope.createTimeFor("room"))
        }
    }

    @Test fun exceptionsRestoreAndReleaseTheScope() {
        val scope = LocalMessageTimeScope()
        val expected = IllegalStateException("host failure")
        scope.during("room", 100L) {
            try {
                scope.during("other", 200L) { throw expected }
                fail("exception should propagate")
            } catch (actual: IllegalStateException) {
                assertSame(expected, actual)
            }
            assertEquals(100L, scope.createTimeFor("room"))
            assertNull(scope.createTimeFor("other"))
        }
        assertNull(scope.createTimeFor("room"))
    }

    @Test fun otherThreadsDoNotInheritThePinnedTime() {
        val scope = LocalMessageTimeScope()
        val observed = AtomicReference<Long?>()
        scope.during("room", 100L) {
            val thread = Thread { observed.set(scope.createTimeFor("room")) }
            thread.start()
            thread.join(1000L)
            assertFalse(thread.isAlive)
            assertNull(observed.get())
            assertEquals(100L, scope.createTimeFor("room"))
        }
    }
}
