package h.Hchat.hooks.items.addemoji

import org.junit.Assert.*
import org.junit.Test

class EmojiCollectionChecksTest {
    private val infoClass = "com.tencent.mm.storage.emotion.EmojiInfo"
    private val expected = EmojiCollectionChecks.Expected(
        "0123456789abcdef0123456789abcdef", 1, 1234, 320, 240
    )
    private val complete = EmojiCollectionChecks.Snapshot(
        expected.md5, 81, 1, 1234, 320, 240, 0, 0, 27, 0
    )
    private val rawFile = EmojiCollectionChecks.FileSnapshot(true, true, 1234L, expected.md5, false)
    private val missing = EmojiCollectionChecks.FileSnapshot(false, false, 0L, null, false)

    @Test fun nativePathSelectionSurvivesThe76To80MethodRename() {
        // 实锤方法名不同；80 的 T0 已经是 (..,..):void，不能按方法名兼容。
        val candidates76 = mapOf(
            "T0" to path(),
            "I" to path(strings = setOf(EmojiCollectionChecks.PATH_FAILURE, "/"))
        )
        val candidates80 = mapOf(
            "T0" to path(parameterCount = 2, returnType = "void"),
            "b1" to path(),
            "W" to path(strings = setOf(EmojiCollectionChecks.PATH_FAILURE, "/"))
        )
        assertEquals(listOf("T0"), candidates76.filterValues(::isPath).keys.toList())
        assertEquals(listOf("b1"), candidates80.filterValues(::isPath).keys.toList())
    }

    @Test fun pathAnchorMustBelongToTheSameMethodAndExactEmojiInfoClass() {
        assertFalse(isPath(path(owner = "com.tencent.mm.api.IEmojiInfo")))
        assertFalse(isPath(path(strings = setOf(EmojiCollectionChecks.PATH_FAILURE, "capture"))))
        assertFalse(isPath(path(strings = setOf("prefix ${EmojiCollectionChecks.PATH_FAILURE}", "capture", "/"))))
        assertFalse(isPath(path(isStatic = true)))
        assertFalse(isPath(path(parameterCount = 1)))
        assertFalse(isPath(path(returnType = "[B")))
    }

    @Test fun onlyTheIntegerPayloadSelectsCustomAndCaptureRemainsUntouched() {
        val payloads = listOf(
            EmojiCollectionChecks.PayloadCandidate("g", listOf(EmojiCollectionChecks.PayloadField("a", "int"))),
            EmojiCollectionChecks.PayloadCandidate("h", listOf(EmojiCollectionChecks.PayloadField("a", "boolean")))
        )
        val event = mutableMapOf("g" to 0, "h" to 0)
        val target = EmojiCollectionChecks.customPanelPayload(payloads)
        assertEquals("g", target)
        event[target!!] = EmojiCollectionChecks.CUSTOM_PANEL_TYPE
        assertEquals(1, event["g"])
        assertEquals(0, event["h"])
        assertNotEquals(2, event["g"])
    }

    @Test fun ambiguousOrStaticPayloadFailsClosed() {
        val integerField = listOf(EmojiCollectionChecks.PayloadField("a", "int"))
        assertNull(EmojiCollectionChecks.customPanelPayload(listOf(
            EmojiCollectionChecks.PayloadCandidate("first", integerField),
            EmojiCollectionChecks.PayloadCandidate("second", integerField)
        )))
        assertNull(EmojiCollectionChecks.customPanelPayload(listOf(
            EmojiCollectionChecks.PayloadCandidate("static", listOf(
                EmojiCollectionChecks.PayloadField("a", "int", isStatic = true)
            ))
        )))
    }

    @Test fun successfulNativeSetterCannotCompensateForAMissingFreshRow() {
        // 即使 native create/update 的返回值表示成功，没有 fresh DB 行也不能 Added。
        assertEquals("回读表情库未找到记录",
            EmojiCollectionChecks.verificationError(null, expected, rawFile))
    }

    @Test fun providerTemporaryRecordIsNotAcceptedAsFinalCustomEmoji() {
        val providerCreated = complete.copy(state = 3, temp = 1)
        assertNotNull(EmojiCollectionChecks.verificationError(providerCreated, expected, rawFile))
        assertEquals(EmojiCollectionChecks.Plan.Save(false),
            EmojiCollectionChecks.plan(providerCreated, expected, rawFile))
    }

    @Test fun eachPanelAndLocalFileFieldMustSurviveFreshReadback() {
        val corruptions = listOf(
            complete.copy(md5 = "ffffffffffffffffffffffffffffffff"),
            complete.copy(catalog = 18),
            complete.copy(temp = 1),
            complete.copy(state = 4),
            complete.copy(type = 2),
            complete.copy(size = 0),
            complete.copy(width = 0),
            complete.copy(height = 0),
            complete.copy(order = null),
            complete.copy(reserved4 = null)
        )
        for (current in corruptions) {
            assertNotNull("unexpected accepted row: $current",
                EmojiCollectionChecks.verificationError(current, expected, rawFile))
        }
        assertNull(EmojiCollectionChecks.verificationError(complete, expected, rawFile, order = 27))
        assertNotNull(EmojiCollectionChecks.verificationError(complete, expected, rawFile, order = 28))
    }

    @Test fun equalLengthDoesNotMakeWrongFileContentsSuccessful() {
        val wrong = rawFile.copy(md5 = "ffffffffffffffffffffffffffffffff")
        assertNotNull(EmojiCollectionChecks.verificationError(complete, expected, wrong))
        assertTrue(EmojiCollectionChecks.plan(complete, expected, wrong) is EmojiCollectionChecks.Plan.Reject)
    }

    @Test fun missingCustomFileIsRepairedInsteadOfReturningAlreadyExists() {
        val decision = EmojiCollectionChecks.plan(complete, expected, missing)
        assertEquals(EmojiCollectionChecks.Plan.Save(writeMissingFile = true), decision)
        assertNotNull(EmojiCollectionChecks.verificationError(complete, expected, missing))
        // 补完文件并 fresh 回读才通过；writer 对 Save 和 AlreadyPresent 共用最终刷新。
        assertNull(EmojiCollectionChecks.verificationError(complete, expected, rawFile))
    }

    @Test fun missingEncryptedFileCanBeRepairedOnlyWithMatchingRawFlag() {
        val encryptedRow = complete.copy(reserved4 = 5)
        assertEquals(EmojiCollectionChecks.Plan.Save(true),
            EmojiCollectionChecks.plan(encryptedRow, expected, missing.copy(encrypted = true)))
        assertNotNull(EmojiCollectionChecks.verificationError(encryptedRow, expected, rawFile))
        assertNull(EmojiCollectionChecks.verificationError(
            encryptedRow.copy(reserved4 = 4), expected, rawFile
        ))
    }

    @Test fun validExistingNativeCiphertextIsPreservedAndNeverReportedAsNewlyAdded() {
        val encrypted = rawFile.copy(md5 = null, encrypted = true)
        val existing = complete.copy(reserved4 = 1)
        assertEquals(EmojiCollectionChecks.Plan.AlreadyPresent,
            EmojiCollectionChecks.plan(existing, expected, encrypted))
        assertNull(EmojiCollectionChecks.verificationError(existing, expected, encrypted,
            allowExistingEncrypted = true))
        assertNotNull(EmojiCollectionChecks.verificationError(existing, expected, encrypted))
        assertTrue(EmojiCollectionChecks.plan(existing.copy(catalog = 18), expected, encrypted)
            is EmojiCollectionChecks.Plan.Reject)
    }

    @Test fun preexistingWrongLengthOrDirectoryIsNeverOverwritten() {
        assertTrue(EmojiCollectionChecks.plan(complete, expected, rawFile.copy(length = 12))
            is EmojiCollectionChecks.Plan.Reject)
        assertTrue(EmojiCollectionChecks.plan(complete, expected,
            rawFile.copy(isFile = false, length = 0, md5 = null)) is EmojiCollectionChecks.Plan.Reject)
    }

    @Test fun catalogAndOtherPersistentFieldsCanLiveInAParentMetadataClass() {
        data class Metadata(val parent: Metadata?, val values: Map<String, Any>)
        val parent = Metadata(null, mapOf(
            "field_md5" to expected.md5, "field_catalog" to 81, "field_temp" to 0,
            "field_state" to 0, "field_size" to 1234, "field_type" to 1,
            "field_width" to 320, "field_height" to 240,
            "field_reserved3" to 27, "field_reserved4" to 0
        ))
        val leaf = Metadata(parent, mapOf("N2" to "/native/root/"))
        val actual = EmojiCollectionChecks.snapshot { name ->
            EmojiCollectionChecks.findInHierarchy(leaf, { it.parent }) { it.values[name] }
        }
        assertEquals(complete, actual)
        assertEquals(EmojiCollectionChecks.Plan.AlreadyPresent,
            EmojiCollectionChecks.plan(actual, expected, rawFile))
    }

    private fun path(
        owner: String = infoClass,
        parameterCount: Int = 0,
        returnType: String = "java.lang.String",
        isStatic: Boolean = false,
        strings: Set<String> = EmojiCollectionChecks.PATH_STRINGS
    ) = EmojiCollectionChecks.PathMethod(owner, parameterCount, returnType, isStatic, strings)

    private fun isPath(method: EmojiCollectionChecks.PathMethod): Boolean =
        EmojiCollectionChecks.isNativePathMethod(method, infoClass)
}
