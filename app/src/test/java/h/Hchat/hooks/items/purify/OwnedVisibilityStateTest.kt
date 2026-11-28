package h.Hchat.hooks.items.purify

import org.junit.Assert.*
import org.junit.Test

class OwnedVisibilityStateTest {
    private class Row(var visibility: Int)

    @Test fun restoreOnlyViewsHiddenByThisFeature() {
        val state = OwnedVisibilityState<Row>({ it.visibility }, { row, value -> row.visibility = value })
        val hiddenByHost = Row(8)
        val visible = Row(0)
        state.hide(hiddenByHost)
        state.hide(visible)
        assertEquals(8, hiddenByHost.visibility)
        assertEquals(8, visible.visibility)
        state.restoreAll()
        assertEquals(8, hiddenByHost.visibility)
        assertEquals(0, visible.visibility)
    }

    @Test fun restoreAndHideAreIdempotentAcrossViewReuse() {
        val state = OwnedVisibilityState<Row>({ it.visibility }, { row, value -> row.visibility = value })
        val row = Row(4)
        state.hide(row)
        state.hide(row)
        assertEquals(8, row.visibility)
        state.restore(row)
        assertEquals(4, row.visibility)
        state.restore(row)
        assertEquals(4, row.visibility)
    }
}
