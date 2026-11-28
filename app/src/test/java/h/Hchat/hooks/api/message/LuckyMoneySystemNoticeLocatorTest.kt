package h.Hchat.hooks.api.message

import h.Hchat.hooks.api.message.LuckyMoneySystemNoticeFacts.Callback
import h.Hchat.hooks.api.message.LuckyMoneySystemNoticeFacts.Helper
import h.Hchat.hooks.api.message.LuckyMoneySystemNoticeFacts.Path
import h.Hchat.hooks.api.message.LuckyMoneySystemNoticeFacts.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** 直接测试生产定位器共用的事实筛选；不依赖微信类、Android stub 或模拟 DexKit 查询。 */
class LuckyMoneySystemNoticeLocatorTest {
    private val model = "com.tencent.mm.plugin.luckymoney.model"
    private val storage80 = "Lcom/tencent/mm/storage/g9;->Zb(Lcom/tencent/mm/storage/f9;)J"
    private val storage76 = "Lcom/tencent/mm/storage/f9;->H9(Lcom/tencent/mm/storage/e9;)J"

    @Test fun wechat80NormalAndUnionShareTheStringlessHelper() {
        val q = helper("o5", "q", storage80)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("h6", Path.NORMAL, listOf(q)),
            callback("i6", Path.UNION, listOf(q))
        ), storage80)

        assertTrue(result.problems.isEmpty())
        assertEquals(listOf(q.method), result.methods)
        // helper 事实根本不携带 usingStrings：三个锚点只属于 callback。
    }

    @Test fun wechat76NormalAndUnionUseTheirRenamedHelperAndStorage() {
        val q = helper("m5", "q", storage76)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("f6", Path.NORMAL, listOf(q)),
            callback("g6", Path.UNION, listOf(q))
        ), storage76)

        assertTrue(result.problems.isEmpty())
        assertEquals(listOf(q.method), result.methods)
    }

    @Test fun obfuscatedOwnersAndHelperNamesAreNotSelectionRules() {
        val renamed = helper("anythingNew", "renamed", storage80)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("ordinaryRenamed", Path.NORMAL, listOf(renamed)),
            callback("unionRenamed", Path.UNION, listOf(renamed))
        ), storage80)

        assertTrue(result.problems.isEmpty())
        assertEquals(listOf(renamed.method), result.methods)
    }

    @Test fun allThreeExactStringsMustBelongToOneCallbackBody() {
        val q = helper("o5", "q", storage80)
        val normal = callback("h6", Path.NORMAL, listOf(q), setOf(
            LuckyMoneySystemNoticeFacts.CONTEXT_ANCHOR, Path.NORMAL.tag
        ))
        val separateError = callback("other", Path.NORMAL, listOf(q), setOf(
            LuckyMoneySystemNoticeFacts.INSERT_ERROR_ANCHOR, Path.NORMAL.tag
        ))
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            normal, separateError, callback("i6", Path.UNION, listOf(q))
        ), storage80)

        assertTrue(result.methods.isEmpty())
        assertTrue(result.problems.any { it.startsWith("NORMAL callback count=0") })
        for (anchor in LuckyMoneySystemNoticeFacts.anchors(Path.NORMAL)) {
            val almost = LuckyMoneySystemNoticeFacts.anchors(Path.NORMAL).toMutableSet()
            almost.remove(anchor)
            almost += "$anchor suffix"
            assertFalse("substring must not count: $anchor", LuckyMoneySystemNoticeFacts.hasAnchors(almost, Path.NORMAL))
        }
    }

    @Test fun unionTagCannotSatisfyNormalTagBySubstring() {
        val q = helper("o5", "q", storage80)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("i6", Path.UNION, listOf(q))
        ), storage80)

        assertTrue(result.methods.isEmpty())
        assertTrue(result.problems.any { it.startsWith("NORMAL callback count=0") })
        assertFalse(LuckyMoneySystemNoticeFacts.hasAnchors(
            LuckyMoneySystemNoticeFacts.anchors(Path.UNION).toSet(), Path.NORMAL
        ))
    }

    @Test fun callbackRequiresExactConcreteInstanceSignatureAndModelPackage() {
        val valid = callback("h6", Path.NORMAL, emptyList()).shape
        assertTrue(LuckyMoneySystemNoticeFacts.isCallback(valid))
        val invalid = listOf(
            valid.copy(owner = "$model.fake.h6"),
            valid.copy(owner = "elsewhere.h6"),
            valid.copy(name = "onGYNetEndOther"),
            valid.copy(returnType = "boolean"),
            valid.copy(parameterTypes = listOf("java.lang.Integer", "java.lang.String", "org.json.JSONObject")),
            valid.copy(parameterTypes = listOf("int", "java.lang.String", "java.lang.Object")),
            valid.copy(parameterTypes = listOf("int", "java.lang.String")),
            valid.copy(modifiers = Modifier.PUBLIC or Modifier.STATIC),
            valid.copy(modifiers = Modifier.PUBLIC or Modifier.ABSTRACT),
            valid.copy(modifiers = Modifier.PUBLIC or Modifier.NATIVE),
            valid.copy(ownerIsInterface = true)
        )
        invalid.forEach { assertFalse(it.toString(), LuckyMoneySystemNoticeFacts.isCallback(it)) }
    }

    @Test fun helperRequiresPublicStaticVoidTwoStringsAndModelPackage() {
        val valid = helper("o5", "q", storage80).shape
        assertTrue(LuckyMoneySystemNoticeFacts.isHelper(valid))
        val invalid = listOf(
            valid.copy(owner = "other.o5"),
            valid.copy(owner = "$model.extra.o5"),
            valid.copy(modifiers = Modifier.PRIVATE or Modifier.STATIC),
            valid.copy(modifiers = Modifier.PUBLIC),
            valid.copy(modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.ABSTRACT),
            valid.copy(modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.NATIVE),
            valid.copy(returnType = "long"),
            valid.copy(parameterTypes = listOf("java.lang.String")),
            valid.copy(parameterTypes = listOf("java.lang.String", "java.lang.Object")),
            valid.copy(ownerIsInterface = true)
        )
        invalid.forEach { assertFalse(it.toString(), LuckyMoneySystemNoticeFacts.isHelper(it)) }
    }

    @Test fun helperMustDirectlyInvokeTheSuppliedRealStorageInsert() {
        val unrelated = helper("o5", "q", storage76)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("h6", Path.NORMAL, listOf(unrelated)),
            callback("i6", Path.UNION, listOf(unrelated))
        ), storage80)

        assertTrue(result.methods.isEmpty())
        assertEquals(2, result.problems.count { it.contains("helper count=0") })
    }

    @Test fun shapeOnlyHelperOrTransitiveStorageChainIsNotEnough() {
        val direct = helper("other", "insertLater", storage80)
        val indirect = helper("o5", "q", direct.method)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("h6", Path.NORMAL, listOf(indirect)),
            callback("i6", Path.UNION, listOf(indirect))
        ), storage80)

        assertTrue(result.methods.isEmpty())
        assertEquals(2, result.problems.count { it.contains("helper count=0") })
    }

    @Test fun multipleEligibleHelpersInOneCallbackRejectTheWholeResult() {
        val first = helper("o5", "q", storage80)
        val second = helper("other", "r", storage80)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("h6", Path.NORMAL, listOf(first, second)),
            callback("i6", Path.UNION, listOf(first))
        ), storage80)

        assertTrue(result.methods.isEmpty())
        assertTrue(result.problems.any { it.startsWith("NORMAL") && it.contains("helper count=2") })
    }

    @Test fun unionNeedsItsOwnEligibleHelperAndCannotBorrowNormals() {
        val q = helper("o5", "q", storage80)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("h6", Path.NORMAL, listOf(q)),
            callback("i6", Path.UNION, emptyList())
        ), storage80)

        assertTrue(result.methods.isEmpty())
        assertTrue(result.problems.any { it.startsWith("UNION") && it.contains("helper count=0") })
    }

    @Test fun normalSuccessCannotHideMissingOrAmbiguousUnionCallback() {
        val q = helper("o5", "q", storage80)
        val normal = callback("h6", Path.NORMAL, listOf(q))
        val missing = LuckyMoneySystemNoticeFacts.select(listOf(normal), storage80)
        val ambiguous = LuckyMoneySystemNoticeFacts.select(listOf(
            normal, callback("i6", Path.UNION, listOf(q)), callback("another", Path.UNION, listOf(q))
        ), storage80)

        assertTrue(missing.methods.isEmpty())
        assertTrue(missing.problems.any { it.startsWith("UNION callback count=0") })
        assertTrue(ambiguous.methods.isEmpty())
        assertTrue(ambiguous.problems.any { it.startsWith("UNION callback count=2") })
    }

    @Test fun bothPathsAreEvaluatedEvenWhenTheFirstOneIsInvalid() {
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("h6", Path.NORMAL, emptyList()),
            callback("i6", Path.UNION, emptyList())
        ), storage80)

        assertTrue(result.methods.isEmpty())
        assertEquals(2, result.problems.count { it.contains("helper count=0") })
        assertTrue(result.problems.any { it.startsWith("NORMAL") })
        assertTrue(result.problems.any { it.startsWith("UNION") })
    }

    @Test fun distinctHelpersForBothPathsArePreservedRatherThanReturningEarly() {
        val normal = helper("ordinary", "a", storage80)
        val union = helper("combined", "b", storage80)
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("i6", Path.UNION, listOf(union)),
            callback("h6", Path.NORMAL, listOf(normal))
        ), storage80)

        assertTrue(result.problems.isEmpty())
        assertEquals(listOf(normal.method, union.method), result.methods)
    }

    @Test fun duplicateInvokeFactsDoNotCreateFalseAmbiguity() {
        val q = helper("o5", "q", storage80)
        val wrongShape = helper("bad", "q", storage80).let {
            it.copy(shape = it.shape.copy(modifiers = Modifier.PRIVATE or Modifier.STATIC))
        }
        val result = LuckyMoneySystemNoticeFacts.select(listOf(
            callback("h6", Path.NORMAL, listOf(q, q, wrongShape)),
            callback("i6", Path.UNION, listOf(q))
        ), storage80)

        assertTrue(result.problems.isEmpty())
        assertEquals(listOf(q.method), result.methods)
    }

    @Test fun cacheRequiresOneOrTwoHelpersWithEveryMemberHavingTheRightShape() {
        val valid = helper("o5", "q", storage80).shape
        val other = valid.copy(owner = "$model.another", name = "renamed")
        val invalid = valid.copy(modifiers = Modifier.PUBLIC)
        assertFalse(LuckyMoneySystemNoticeFacts.isValidCache(emptyList()))
        assertTrue(LuckyMoneySystemNoticeFacts.isValidCache(listOf(valid)))
        assertTrue(LuckyMoneySystemNoticeFacts.isValidCache(listOf(valid, other)))
        assertFalse(LuckyMoneySystemNoticeFacts.isValidCache(listOf(valid, invalid)))
        assertFalse(LuckyMoneySystemNoticeFacts.isValidCache(listOf(valid, other, valid)))
    }

    @Test fun cacheWithOneUnresolvableDescriptorIsNotSilentlyAcceptedAsOneSharedHelper() {
        assertFalse(LuckyMoneySystemNoticeFacts.isCompleteCache(
            listOf("normal descriptor", "union descriptor"), listOf("resolved normal")
        ))
        assertTrue(LuckyMoneySystemNoticeFacts.isCompleteCache(
            listOf("shared descriptor"), listOf("shared helper")
        ))
    }

    @Test fun duplicateDescriptorsOrDuplicateResolvedHelpersInvalidateCache() {
        assertFalse(LuckyMoneySystemNoticeFacts.isCompleteCache(
            listOf("shared", "shared"), listOf("one", "two")
        ))
        assertFalse(LuckyMoneySystemNoticeFacts.isCompleteCache(
            listOf("one", "two"), listOf("shared", "shared")
        ))
        assertTrue(LuckyMoneySystemNoticeFacts.isCompleteCache(
            listOf("normal", "union"), listOf("normal helper", "union helper")
        ))
    }

    @Test fun aConstructorResolutionExceptionPreservesItsCauseAndDoesNotStopLaterInvokes() {
        val constructorFailure = IllegalArgumentException("<init> is not a Method")
        val errors = mutableListOf<Pair<String, Throwable>>()
        val resolved = LuckyMoneySystemNoticeFacts.resolveInvokes(listOf("before", "<init>", "helper"),
            resolve = { entry -> if (entry == "<init>") throw constructorFailure else entry },
            onError = { entry, error -> errors += entry to error })

        assertEquals(listOf("before", "helper"), resolved)
        assertEquals("<init>", errors.single().first)
        assertSame(constructorFailure, errors.single().second)
    }

    private fun helper(owner: String, name: String, storage: String): Helper<String> {
        val method = "$model.$owner.$name(String,String):void"
        return Helper(method, Shape("$model.$owner", name, "void",
            listOf("java.lang.String", "java.lang.String"), Modifier.PUBLIC or Modifier.STATIC), setOf(storage))
    }

    private fun callback(
        owner: String,
        path: Path,
        helpers: List<Helper<String>>,
        strings: Set<String> = LuckyMoneySystemNoticeFacts.anchors(path).toSet()
    ): Callback<String> = Callback("$model.$owner.onGYNetEnd", Shape(
        "$model.$owner", "onGYNetEnd", "void", listOf("int", "java.lang.String", "org.json.JSONObject"),
        Modifier.PUBLIC
    ), strings, helpers)
}
