package h.Hchat.hooks.items.menustyle

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ChatMenuDisplayScopeTest {
    @Test fun popupShownInsideOriginalCallHasScopeAlready() {
        val scope = ChatMenuDisplayScope<Any>()
        val anchor = Any()
        var sawMenu = false
        val result = scope.during(anchor) {
            // 模拟 host 的 proceed 在返回之前就执行 PopupWindow.show。
            sawMenu = scope.current() === anchor
            "native-result"
        }
        assertTrue(sawMenu)
        assertEquals("native-result", result)
        assertNull(scope.current())
    }

    @Test fun laterPopupHasNoResidualLongPressWindow() {
        val scope = ChatMenuDisplayScope<Any>()
        scope.during(Any()) { assertNotNull(scope.current()) }
        // 选择手柄/其它弹窗随后显示，不因离长按时间近而获得菜单资格。
        assertNull(scope.current())
    }

    @Test fun nestedOpeningRestoresTheOuterAnchor() {
        val scope = ChatMenuDisplayScope<Any>()
        val outer = Any()
        val inner = Any()
        scope.during(outer) {
            scope.during(inner) { assertSame(inner, scope.current()) }
            assertSame(outer, scope.current())
        }
        assertNull(scope.current())
    }

    @Test fun throwingHostCallPreservesThrowableAndReleasesScope() {
        val scope = ChatMenuDisplayScope<Any>()
        val failure = IllegalStateException("native failure")
        try {
            scope.during(Any()) { throw failure }
            fail("host failure was swallowed")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertNull(scope.current())
    }

    @Test fun anotherThreadDoesNotReceiveTheMenuScope() {
        val scope = ChatMenuDisplayScope<Any>()
        val observed = AtomicReference<Any?>()
        val anchor = Any()
        scope.during(anchor) {
            val worker = Thread { observed.set(scope.current()) }
            worker.start()
            worker.join(1000)
            assertFalse(worker.isAlive)
            assertNull(observed.get())
            assertSame(anchor, scope.current())
        }
    }
}
