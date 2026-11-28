package h.Hchat.hooks.api.message

import android.content.Context
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

class ChatMenuMethodShapeTest {
    abstract class ObfuscatedMenu : ContextMenu

    @Suppress("UNUSED_PARAMETER")
    class Targets {
        fun create(menu: ContextMenu, view: View, info: ContextMenu.ContextMenuInfo?) {}
        fun concreteCreate(menu: ObfuscatedMenu, view: View, info: ContextMenu.ContextMenuInfo?) {}
        fun wrongContext(context: Context, view: View, info: ContextMenu.ContextMenuInfo?) {}
        fun wrongThird(menu: ContextMenu, view: View, info: Any?) {}
        fun click(item: MenuItem, index: Int) {}
        fun wrongIndex(item: MenuItem, index: Long) {}
        fun wrongReturn(item: MenuItem, index: Int): Boolean = true

        companion object {
            @JvmStatic fun staticCreate(menu: ContextMenu, view: View, info: ContextMenu.ContextMenuInfo?) {}
        }
    }

    abstract class AbstractTarget {
        abstract fun create(menu: ContextMenu, view: View, info: ContextMenu.ContextMenuInfo?)
    }

    private fun target(name: String): Method = Targets::class.java.declaredMethods.single { it.name == name }

    @Test fun acceptsContextMenuInterfaceAndItsObfuscatedImplementation() {
        assertTrue(ChatMenuMethodShape.isCreate(target("create")))
        assertTrue(ChatMenuMethodShape.isCreate(target("concreteCreate")))
    }

    @Test fun rejectsContextAsFirstParameterRatherThanGuessingByClassName() {
        assertFalse(ChatMenuMethodShape.isCreate(target("wrongContext")))
        assertFalse(ChatMenuMethodShape.isCreate(target("wrongThird")))
    }

    @Test fun clickRequiresMenuItemIntAndVoid() {
        assertTrue(ChatMenuMethodShape.isClick(target("click")))
        assertFalse(ChatMenuMethodShape.isClick(target("wrongIndex")))
        assertFalse(ChatMenuMethodShape.isClick(target("wrongReturn")))
    }

    @Test fun staticAndAbstractMethodsCannotBeHookTargets() {
        assertFalse(ChatMenuMethodShape.isCreate(target("staticCreate")))
        val abstractMethod = AbstractTarget::class.java.declaredMethods.single { it.name == "create" }
        assertFalse(ChatMenuMethodShape.isCreate(abstractMethod))
    }
}
