package h.Hchat.hooks.items.groupadminlabel

import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.contact.WeChatChatroomApi
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.chatname.ChatNameDecorationLayout
import h.Hchat.hooks.items.chatname.ChatNameDecorationLayout.PrefixBadge
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.hooks.items.realtail.RealNameTailStore
import h.Hchat.preferences.HotConfig
import java.util.Collections
import java.util.WeakHashMap

/**
 * 群聊管理员标签显示。
 *
 * 在群聊成员昵称前插入小标签：
 * - 群主：`chatroom.roomowner` 就是这条消息的发送者
 * - 管理员：`chatroom` 成员表里该成员的 `roomFlag` 命中 `0x800`
 * - 部分真名：复用 `RealNameTailStore` 已缓存/已查询到的脱敏真名（如 `张*`）
 *
 * 数据全部来自模块已有的 `WeChatChatroomApi` / `RealNameTailStore`，不新增逆向。
 * 渲染复用 `ChatNameDecorationLayout` 的 prefix 槽位，和「实名尾字」「群昵称颜色」
 * 共用同一个装饰行，互不覆盖：前缀在名字前，尾字仍在名字后。
 */
class GroupAdminLabelFeature : BaseFeature() {
    private var runtime: GroupAdminLabelRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "群聊管理员标签显示"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GroupAdminLabelSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = GroupAdminLabelRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) { runtime?.install() == true }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "group_admin_label"
    }
}

private class GroupAdminLabelRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val realNameStore = RealNameTailStore(context.hostContext())
    private val memberTitleStore = MemberTitleStore(context.hostContext())
    private val boundViews = Collections.synchronizedMap(WeakHashMap<TextView, String>())
    private var installed = false

    private val showOwner = HotConfig.flag(
        GroupAdminLabelFeature.ID,
        GroupAdminLabelSettings.KEY_OWNER,
        GroupAdminLabelSettings.DEFAULT_SHOW
    ) {
        context.configStore().getBoolean(
            GroupAdminLabelFeature.ID,
            GroupAdminLabelSettings.KEY_OWNER,
            GroupAdminLabelSettings.DEFAULT_SHOW
        )
    }
    private val showAdmin = HotConfig.flag(
        GroupAdminLabelFeature.ID,
        GroupAdminLabelSettings.KEY_ADMIN,
        GroupAdminLabelSettings.DEFAULT_SHOW
    ) {
        context.configStore().getBoolean(
            GroupAdminLabelFeature.ID,
            GroupAdminLabelSettings.KEY_ADMIN,
            GroupAdminLabelSettings.DEFAULT_SHOW
        )
    }
    private val showRealName = HotConfig.flag(
        GroupAdminLabelFeature.ID,
        GroupAdminLabelSettings.KEY_REAL_NAME,
        GroupAdminLabelSettings.DEFAULT_SHOW
    ) {
        context.configStore().getBoolean(
            GroupAdminLabelFeature.ID,
            GroupAdminLabelSettings.KEY_REAL_NAME,
            GroupAdminLabelSettings.DEFAULT_SHOW
        )
    }

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val method = ChatUsernameBind.locate(context, logger) ?: run {
            logger("群聊管理员标签：未定位到昵称绑定方法", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleUsernameBind(param)
                }
            })
            installed = true
            true
        }.getOrElse {
            logger("群聊管理员标签 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    fun destroy() {
        installed = false
        boundViews.clear()
    }

    private fun handleUsernameBind(param: XC_MethodHook.MethodHookParam) {
        val args = param.args
        val holder = args.firstOrNull() ?: return
        val tv = ChatUsernameBind.findUserTextView(holder) ?: return
        if (!showOwner() && !showAdmin() && !showRealName()) {
            hideBadges(tv)
            return
        }
        val roomId = ChatUsernameBind.currentRoomId()
        if (!ChatUsernameBind.isRoom(roomId)) {
            hideBadges(tv)
            return
        }
        val bindArgs = ChatUsernameBind.collect(args)
        if (ChatUsernameBind.isOutgoingMessage(bindArgs.message)) {
            hideBadges(tv)
            return
        }
        val sender = ChatUsernameBind.resolveSender(bindArgs)
        if (!ChatUsernameBind.isValidWxid(sender) || ChatUsernameBind.isSelf(sender)) {
            hideBadges(tv)
            return
        }
        applyBadges(tv, roomId, sender)
    }

    private fun applyBadges(tv: TextView, roomId: String, sender: String) {
        boundViews[tv] = "$roomId|$sender"
        renderBadges(tv, roomId, sender)
        // 部分消息类型会在填充昵称之后又覆盖一次文本，补一次延迟重设兜住。
        WeChatApis.tasks()?.runOnMainDelayed("hchat_group_label_${System.identityHashCode(tv)}", 120L) {
            if (boundViews[tv] == "$roomId|$sender") renderBadges(tv, roomId, sender)
        }
    }

    private fun renderBadges(tv: TextView, roomId: String, sender: String) {
        val badges = buildBadges(roomId, sender)
        val row = ChatNameDecorationLayout.ensure(tv)
        if (badges.isEmpty()) {
            if (ChatNameDecorationLayout.prefixBadgeTexts(row).isNotEmpty()) {
                ChatNameDecorationLayout.hidePrefixBadges(row)
            }
            return
        }
        // 必须每次重设：列表复用时微信会先 setText 把前缀冲掉，但 state 里的标签还在，
        // 只比较标签文本会误判成「没变化」而跳过一次合成，前缀就丢了。
        ChatNameDecorationLayout.showPrefixBadges(row, badges)
    }

    private fun buildBadges(roomId: String, sender: String): List<PrefixBadge> {
        val badges = mutableListOf<PrefixBadge>()
        val chatroom = WeChatApis.chatrooms()
        val owner = runCatching { chatroom?.getOwner(roomId) }.getOrNull().orEmpty()
        val isOwner = owner.isNotEmpty() && owner == sender
        val role = if (isOwner) {
            WeChatChatroomApi.ROLE_OWNER
        } else {
            runCatching { chatroom?.getMemberRole(roomId, sender) }.getOrNull()
                ?: WeChatChatroomApi.ROLE_MEMBER
        }
        // 「成员头衔」功能如果已经给这个角色配了头衔并显示，这里就不再叠一个同名标签，
        // 否则昵称前会出现「群主 群主 昵称」。
        val titleFeatureCoversRole = memberTitleEnabled() && memberTitleStore.roleTitle(role).isNotEmpty()
        if (showOwner() && isOwner && !titleFeatureCoversRole) {
            badges += PrefixBadge("群主", OWNER_BG, OWNER_BG, BADGE_TEXT, BADGE_TEXT)
        }
        if (showAdmin() && role == WeChatChatroomApi.ROLE_ADMIN && !titleFeatureCoversRole) {
            badges += PrefixBadge("管理员", ADMIN_BG, ADMIN_BG, BADGE_TEXT, BADGE_TEXT)
        }
        if (showRealName()) {
            val tail = runCatching { realNameStore.displayTail(sender) }.getOrNull().orEmpty()
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_REAL_NAME_LABEL)
            if (tail.isNotEmpty()) {
                badges += PrefixBadge(tail, REAL_NAME_BG, REAL_NAME_BG, BADGE_TEXT, BADGE_TEXT)
            }
        }
        return badges
    }

    private fun memberTitleEnabled(): Boolean =
        runCatching { memberTitleStore.isEnabled() }.getOrDefault(false)

    private fun hideBadges(tv: TextView) {
        boundViews.remove(tv)
        val row = ChatNameDecorationLayout.current(tv) ?: return
        if (ChatNameDecorationLayout.prefixBadgeTexts(row).isEmpty()) return
        ChatNameDecorationLayout.hidePrefixBadges(row)
    }

    private companion object {
        const val OWNER_BG = 0xFFD08A1E.toInt()
        const val ADMIN_BG = 0xFF3F7FBF.toInt()
        const val REAL_NAME_BG = 0xFF6E7781.toInt()
        const val BADGE_TEXT = 0xFFFFFFFF.toInt()
        const val MAX_REAL_NAME_LABEL = 12
    }
}