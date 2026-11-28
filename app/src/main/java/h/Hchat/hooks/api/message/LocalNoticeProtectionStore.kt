package h.Hchat.hooks.api.message

import android.content.Context
import h.Hchat.preferences.HchatStorage
import java.util.concurrent.ConcurrentHashMap

/** 同一宿主进程共享来源登记，创建时读一次；聊天绑定只查询内存。 */
internal object LocalNoticeProtectionStore {
    private val stores = ConcurrentHashMap<String, LocalNoticeProtection>()

    fun get(context: Context): LocalNoticeProtection {
        val app = context.applicationContext ?: context
        val directory = HchatStorage.storageDir(app).absolutePath
        return stores.getOrPut(directory) {
            val prefs = HchatStorage.preferences(app, "Hchat_protected_local_notices_v1")
            LocalNoticeProtection(object : LocalNoticeProtection.Persistence {
                override fun loadEntries(): Map<String, Long> = prefs.all.mapNotNull { (key, value) ->
                    val parts = key.split(':', limit = 2)
                    val hash = parts.getOrNull(0)?.takeIf {
                        it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
                    }
                    val storedId = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
                    val valueId = (value as? Number)?.toLong()?.takeIf { it > 0L }
                    if (hash != null && storedId != null && storedId == valueId) key to storedId else null
                }.toMap()

                override fun save(key: String, localId: Long): Boolean =
                    prefs.edit().putLong(key, localId).commit()
            })
        }
    }
}
